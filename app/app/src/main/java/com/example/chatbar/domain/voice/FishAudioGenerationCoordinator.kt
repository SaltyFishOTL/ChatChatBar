package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.data.security.FishAudioCredentialStore
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

enum class VoiceGenerationPhase {
    QUEUED,
    TAGGING,
    AWAITING_TEXT_CONFIRMATION,
    SYNTHESIZING,
    FAILED,
    CANCELLED
}

data class VoiceTextConfirmationState(
    val targetId: String,
    val characterName: String,
    val originalText: String,
    val proposedTaggedText: String
)

data class VoiceGenerationBatchState(
    val id: String,
    val sessionId: String,
    val messageId: String,
    val phase: VoiceGenerationPhase,
    val totalCount: Int = 0,
    val completedCount: Int = 0,
    val receivedBytes: Long = 0L,
    val tagStreamText: String = "",
    val textConfirmations: List<VoiceTextConfirmationState> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis()
)

private data class VoiceGenerationTarget(
    val message: ChatMessage,
    val segment: CurrentVoiceSegment,
    val anchorId: String,
    val sourceOrder: Long,
    val character: CharacterInfo
)

private data class TargetGenerationResult(
    val target: VoiceGenerationTarget,
    val voice: GeneratedVoiceMessage? = null,
    val error: String? = null
)

private data class PendingAutoPlay(
    val batchId: String,
    val sessionId: String,
    val messageOrder: Long,
    val foregroundGeneration: Long,
    val createdAt: Long,
    val terminal: Boolean = false,
    val voices: List<GeneratedVoiceMessage>? = null
)

class FishAudioGenerationCoordinator(
    private val scope: CoroutineScope,
    private val chatRepository: ChatRepository,
    private val characterRepository: CharacterRepository,
    private val settingsRepository: SettingsRepository,
    private val voiceRepository: VoiceMessageRepository,
    private val credentials: FishAudioCredentialStore,
    private val fishAudioService: FishAudioService,
    private val tagService: FishAudioTagService,
    private val modelResolver: EffectiveModelResolver,
    private val storage: FishAudioStorage,
    private val playback: VoicePlaybackController
) {
    private val ttsSlots = Semaphore(5)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val pendingTextConfirmations =
        ConcurrentHashMap<String, PendingVoiceTextConfirmation>()
    private val autoPlayMutex = Mutex()
    private val pendingAutoPlay = linkedMapOf<String, PendingAutoPlay>()
    @Volatile
    private var foregroundSessionId: String? = null
    private val foregroundGeneration = AtomicLong(0L)

    private val _batches = MutableStateFlow<List<VoiceGenerationBatchState>>(emptyList())
    val batches: StateFlow<List<VoiceGenerationBatchState>> = _batches.asStateFlow()

    private data class PendingVoiceTextConfirmation(
        val context: GenerationContext,
        val targets: List<VoiceGenerationTarget>,
        val fishModelId: String,
        val tagResult: VoiceTagBatchResult,
        val playAllMessageVoices: Boolean
    )

    fun setForegroundSession(sessionId: String?) {
        foregroundSessionId = sessionId
        val activeGeneration = foregroundGeneration.incrementAndGet()
        scope.launch {
            autoPlayMutex.withLock {
                pendingAutoPlay.entries.removeAll {
                    it.value.foregroundGeneration != activeGeneration
                }
            }
        }
    }

    fun generateSingle(
        sessionId: String,
        messageId: String,
        segmentIndex: Int
    ): String = launchBatch(sessionId, messageId) { batchId, screenGeneration ->
        val context = loadContext(sessionId, messageId)
        val target = resolveTargets(context.message, context.card)
            .firstOrNull { it.segment.segmentIndex == segmentIndex }
            ?: error("该段没有可唯一匹配的角色音色")
        registerAutoPlay(batchId, context, screenGeneration)
        runGeneration(
            batchId = batchId,
            context = context,
            targets = listOf(target),
            playAllMessageVoices = false
        )
    }

    fun generateWhole(
        sessionId: String,
        messageId: String
    ): String = launchBatch(sessionId, messageId) { batchId, screenGeneration ->
        val context = loadContext(sessionId, messageId)
        registerAutoPlay(batchId, context, screenGeneration)
        val existing = voiceRepository.listForMessage(messageId)
        val voicedAnchors = existing.mapNotNull(GeneratedVoiceMessage::anchorId).toSet()
        val targets = resolveTargets(context.message, context.card)
            .filterNot { it.anchorId in voicedAnchors }
        if (targets.isEmpty()) {
            finishSuccessfulBatch(
                batchId = batchId,
                context = context,
                playAllMessageVoices = true
            )
            return@launchBatch
        }
        runGeneration(
            batchId = batchId,
            context = context,
            targets = targets,
            playAllMessageVoices = true
        )
    }

    fun regenerate(voiceId: String, editedTaggedText: String): String {
        val batchId = UUID.randomUUID().toString()
        val screenGeneration = foregroundGeneration.get()
        setBatch(
            VoiceGenerationBatchState(
                id = batchId,
                sessionId = "",
                messageId = "",
                phase = VoiceGenerationPhase.QUEUED,
                totalCount = 1
            )
        )
        val job = scope.launch {
            try {
                val existing = voiceRepository.get(voiceId) ?: error("语音消息不存在")
                val sourceMessage = chatRepository.getMessage(
                    existing.messageId,
                    existing.sessionId
                ) ?: error("消息不存在")
                registerAutoPlay(
                    batchId = batchId,
                    sessionId = existing.sessionId,
                    messageOrder = sourceMessage.orderKey,
                    screenGeneration = screenGeneration
                )
                updateBatch(batchId) {
                    it.copy(sessionId = existing.sessionId, messageId = existing.messageId)
                }
                FishAudioTagPolicy.analyze(
                    originalText = existing.sourceText,
                    taggedText = editedTaggedText,
                    mode = FishAudioTagPolicy.markerMode(existing.fishModelId)
                ).getOrThrow()
                val apiKey = credentials.load() ?: error("Fish Audio API Key 未配置")
                updateBatch(batchId) { it.copy(phase = VoiceGenerationPhase.SYNTHESIZING) }
                val artifactId = UUID.randomUUID().toString()
                val audio = AiBackgroundWorkManager.run(existing.sessionId) {
                    ttsSlots.withPermit {
                        fishAudioService.synthesize(
                            apiKey = apiKey,
                            modelId = existing.fishModelId,
                            referenceId = existing.voice.referenceId,
                            text = editedTaggedText.trim(),
                            sessionId = existing.sessionId,
                            voiceId = artifactId
                        ) { progress ->
                            updateBatch(batchId) {
                                it.copy(receivedBytes = progress.bytesReceived)
                            }
                        }
                    }
                }
                val replacement = existing.copy(
                    taggedText = editedTaggedText.trim(),
                    audioPath = audio.path,
                    durationMs = audio.durationMs,
                    byteLength = audio.byteLength,
                    updatedAt = System.currentTimeMillis()
                )
                try {
                    voiceRepository.replace(replacement)
                } catch (error: Throwable) {
                    storage.deleteIfOwned(audio.path)
                    throw error
                }
                storage.deleteIfOwned(existing.audioPath)
                removeBatch(batchId)
                finishAutoPlay(batchId, listOf(replacement))
            } catch (cancelled: CancellationException) {
                markCancelled(batchId)
                finishAutoPlay(batchId, null)
                throw cancelled
            } catch (error: Throwable) {
                failBatch(batchId, error)
                finishAutoPlay(batchId, null)
            } finally {
                jobs.remove(batchId)
            }
        }
        jobs[batchId] = job
        return batchId
    }

    fun resolveTextConfirmation(batchId: String, useAiText: Boolean) {
        val pending = pendingTextConfirmations.remove(batchId) ?: return
        updateBatch(batchId) {
            it.copy(
                phase = VoiceGenerationPhase.QUEUED,
                textConfirmations = emptyList()
            )
        }
        val job = scope.launch {
            try {
                val confirmedText = if (useAiText) {
                    pending.tagResult.confirmationRequiredById
                } else {
                    emptyMap()
                }
                val rejectedErrors = if (useAiText) {
                    emptyMap()
                } else {
                    pending.tagResult.confirmationRequiredById.keys.associateWith {
                        "用户未采用 AI 改写文本"
                    }
                }
                runSynthesis(
                    batchId = batchId,
                    context = pending.context,
                    targets = pending.targets,
                    fishModelId = pending.fishModelId,
                    taggedTextById = pending.tagResult.taggedTextById + confirmedText,
                    initialErrors = pending.tagResult.errorsById + rejectedErrors,
                    playAllMessageVoices = pending.playAllMessageVoices
                )
            } catch (cancelled: CancellationException) {
                markCancelled(batchId)
                finishAutoPlay(batchId, null)
                throw cancelled
            } catch (error: Throwable) {
                failBatch(batchId, error)
                finishAutoPlay(batchId, null)
            } finally {
                jobs.remove(batchId)
            }
        }
        jobs[batchId] = job
    }

    fun cancel(batchId: String) {
        if (pendingTextConfirmations.remove(batchId) != null) {
            markCancelled(batchId)
            scope.launch { finishAutoPlay(batchId, null) }
            return
        }
        jobs[batchId]?.cancel()
    }

    suspend fun cancelAndJoinForMessage(sessionId: String, messageId: String) {
        cancelAndJoinMatching { state ->
            state.sessionId == sessionId && state.messageId == messageId
        }
    }

    suspend fun cancelAndJoinForSession(sessionId: String) {
        cancelAndJoinMatching { state -> state.sessionId == sessionId }
    }

    fun dismiss(batchId: String) {
        if (jobs[batchId]?.isActive == true) return
        removeBatch(batchId)
    }

    fun playSingle(voice: GeneratedVoiceMessage) {
        playback.stop()
        scope.launch {
            clearAutoPlay()
            playback.playSingle(voice)
        }
    }

    fun stopPlayback() {
        playback.stop()
        scope.launch {
            clearAutoPlay()
        }
    }

    fun deleteVoice(voiceId: String) {
        scope.launch {
            voiceRepository.delete(voiceId)?.let { storage.deleteIfOwned(it.audioPath) }
            if (playback.state.value.currentVoiceId == voiceId) playback.stop()
        }
    }

    private fun launchBatch(
        sessionId: String,
        messageId: String,
        block: suspend (batchId: String, foregroundGeneration: Long) -> Unit
    ): String {
        val batchId = UUID.randomUUID().toString()
        setBatch(
            VoiceGenerationBatchState(
                id = batchId,
                sessionId = sessionId,
                messageId = messageId,
                phase = VoiceGenerationPhase.QUEUED
            )
        )
        val screenGeneration = foregroundGeneration.get()
        val job = scope.launch {
            try {
                block(batchId, screenGeneration)
            } catch (cancelled: CancellationException) {
                markCancelled(batchId)
                finishAutoPlay(batchId, null)
                throw cancelled
            } catch (error: Throwable) {
                failBatch(batchId, error)
                finishAutoPlay(batchId, null)
            } finally {
                jobs.remove(batchId)
            }
        }
        jobs[batchId] = job
        return batchId
    }

    private suspend fun runGeneration(
        batchId: String,
        context: GenerationContext,
        targets: List<VoiceGenerationTarget>,
        playAllMessageVoices: Boolean
    ) {
        val settings = settingsRepository.getAppSettings()
        val tagModel = if (settings.voiceTagModelId.isNullOrBlank()) {
            modelResolver.resolveChatModel(context.session.modelId, settings)
                ?: error("当前会话没有可用对话模型")
        } else {
            modelResolver.resolveAuxiliaryTextModelExact(settings.voiceTagModelId, settings)
                ?: error("所选语音标签模型已失效，请在设置中重新选择")
        }
        require(tagModel.hasConfiguredAuthentication(settings)) {
            "语音标签模型/API Key 未配置"
        }
        val fishModelId = settings.fishAudioTtsModelId
            .takeIf { it in FishAudioTtsModels.supported }
            ?: FishAudioTtsModels.S2_1_PRO_FREE
        updateBatch(batchId) {
            it.copy(phase = VoiceGenerationPhase.TAGGING, totalCount = targets.size)
        }
        val tagResult = AiBackgroundWorkManager.run(context.session.id) {
            tagService.generate(
                modelConfig = tagModel,
                fishModelId = fishModelId,
                previousUserMessage = context.previousUserMessage,
                assistantResponse = context.message.displayContent,
                inputs = targets.map { target ->
                    VoiceTagInput(
                        id = target.anchorId,
                        text = target.segment.spokenText,
                        characterName = target.character.name,
                        speakingStyle = target.character.speakingStyle
                    )
                },
                onDelta = { delta ->
                    updateBatch(batchId) { state ->
                        state.copy(tagStreamText = state.tagStreamText + delta)
                    }
                }
            )
        }
        if (tagResult.confirmationRequiredById.isNotEmpty()) {
            val targetById = targets.associateBy(VoiceGenerationTarget::anchorId)
            val confirmations = tagResult.confirmationRequiredById.mapNotNull { (targetId, text) ->
                targetById[targetId]?.let { target ->
                    VoiceTextConfirmationState(
                        targetId = targetId,
                        characterName = target.character.name,
                        originalText = target.segment.spokenText,
                        proposedTaggedText = text
                    )
                }
            }
            pendingTextConfirmations[batchId] = PendingVoiceTextConfirmation(
                context = context,
                targets = targets,
                fishModelId = fishModelId,
                tagResult = tagResult,
                playAllMessageVoices = playAllMessageVoices
            )
            updateBatch(batchId) {
                it.copy(
                    phase = VoiceGenerationPhase.AWAITING_TEXT_CONFIRMATION,
                    tagStreamText = "",
                    textConfirmations = confirmations,
                    errors = tagResult.errorsById
                )
            }
            return
        }
        runSynthesis(
            batchId = batchId,
            context = context,
            targets = targets,
            fishModelId = fishModelId,
            taggedTextById = tagResult.taggedTextById,
            initialErrors = tagResult.errorsById,
            playAllMessageVoices = playAllMessageVoices
        )
    }

    private suspend fun runSynthesis(
        batchId: String,
        context: GenerationContext,
        targets: List<VoiceGenerationTarget>,
        fishModelId: String,
        taggedTextById: Map<String, String>,
        initialErrors: Map<String, String>,
        playAllMessageVoices: Boolean
    ) {
        updateBatch(batchId) {
            it.copy(
                phase = VoiceGenerationPhase.SYNTHESIZING,
                tagStreamText = "",
                textConfirmations = emptyList(),
                errors = initialErrors
            )
        }
        if (taggedTextById.isEmpty()) {
            updateBatch(batchId) { it.copy(phase = VoiceGenerationPhase.FAILED) }
            finishAutoPlay(batchId, null)
            return
        }
        val apiKey = credentials.load() ?: error("Fish Audio API Key 未配置")
        AiBackgroundWorkManager.run(context.session.id) {
            val bytesByTarget = ConcurrentHashMap<String, Long>()
            val completedTargets = AtomicInteger(0)
            val results = supervisorScope {
                targets.mapNotNull { target ->
                    val taggedText = taggedTextById[target.anchorId]
                    if (taggedText == null) {
                        null
                    } else {
                        async {
                            val result = synthesizeTarget(
                                apiKey = apiKey,
                                fishModelId = fishModelId,
                                target = target,
                                taggedText = taggedText,
                                onBytes = { bytes ->
                                    bytesByTarget[target.anchorId] = bytes
                                    updateBatch(batchId) {
                                        it.copy(receivedBytes = bytesByTarget.values.sum())
                                    }
                                }
                            )
                            if (result.voice != null) {
                                val completed = completedTargets.incrementAndGet()
                                updateBatch(batchId) { it.copy(completedCount = completed) }
                            }
                            result
                        }
                    }
                }.awaitAll()
            }
            val generationErrors = results.mapNotNull { result ->
                result.error?.let { result.target.anchorId to it }
            }.toMap()
            val allErrors = initialErrors + generationErrors
            val completedCount = results.count { it.voice != null }
            updateBatch(batchId) {
                it.copy(completedCount = completedCount, errors = allErrors)
            }
            if (allErrors.isNotEmpty()) {
                updateBatch(batchId) { it.copy(phase = VoiceGenerationPhase.FAILED) }
                finishAutoPlay(batchId, null)
            } else {
                finishSuccessfulBatch(
                    batchId,
                    context,
                    playAllMessageVoices,
                    results.mapNotNull(TargetGenerationResult::voice)
                )
            }
        }
    }

    private suspend fun synthesizeTarget(
        apiKey: String,
        fishModelId: String,
        target: VoiceGenerationTarget,
        taggedText: String,
        onBytes: (Long) -> Unit
    ): TargetGenerationResult = try {
        val binding = checkNotNull(target.character.fishAudioVoice)
        val artifactId = UUID.randomUUID().toString()
        val audio = ttsSlots.withPermit {
            fishAudioService.synthesize(
                apiKey = apiKey,
                modelId = fishModelId,
                referenceId = binding.referenceId,
                text = taggedText,
                sessionId = target.message.sessionId,
                voiceId = artifactId
            ) { progress -> onBytes(progress.bytesReceived) }
        }
        try {
            val currentAnchorId = resolveCurrentAnchorId(target)
            val voice = GeneratedVoiceMessage.create(
                sessionId = target.message.sessionId,
                messageId = target.message.id,
                anchorId = currentAnchorId,
                sourceOrder = target.sourceOrder,
                sourceSegmentKind = target.segment.kind.name,
                sourceSpeakerName = target.segment.speakerName.orEmpty(),
                sourceText = target.segment.spokenText,
                taggedText = taggedText,
                characterId = target.character.id,
                characterName = target.character.name,
                voice = binding,
                fishModelId = fishModelId,
                audioPath = audio.path,
                durationMs = audio.durationMs,
                byteLength = audio.byteLength
            )
            voiceRepository.save(voice)
            TargetGenerationResult(target, voice = voice)
        } catch (error: Throwable) {
            storage.deleteIfOwned(audio.path)
            throw error
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        TargetGenerationResult(target, error = error.message ?: error.javaClass.simpleName)
    }

    private suspend fun resolveCurrentAnchorId(target: VoiceGenerationTarget): String? {
        val currentMessage = chatRepository.getMessage(
            target.message.id,
            target.message.sessionId
        ) ?: error("消息已删除")
        val currentAnchors = voiceRepository.ensureAnchors(currentMessage)
        currentAnchors.firstOrNull { it.anchor.id == target.anchorId }?.let { return it.anchor.id }
        return currentAnchors
            .filter { it.anchor.sourceOrder <= target.sourceOrder }
            .maxByOrNull { it.anchor.sourceOrder }
            ?.anchor
            ?.id
    }

    private suspend fun finishSuccessfulBatch(
        batchId: String,
        context: GenerationContext,
        playAllMessageVoices: Boolean,
        generatedVoices: List<GeneratedVoiceMessage> = emptyList()
    ) {
        val voices = if (playAllMessageVoices) {
            chatRepository.getMessage(context.message.id, context.session.id)
                ?.let { currentMessage ->
                    voiceRepository.placementsForMessage(currentMessage).map { it.voice }
                }
                .orEmpty()
        } else {
            generatedVoices.sortedBy(GeneratedVoiceMessage::createdAt)
        }
        removeBatch(batchId)
        finishAutoPlay(batchId, voices)
    }

    private suspend fun loadContext(sessionId: String, messageId: String): GenerationContext {
        val session = chatRepository.getSession(sessionId) ?: error("会话不存在")
        val message = chatRepository.getMessage(messageId, sessionId) ?: error("消息不存在")
        require(message.role == MessageRole.ASSISTANT) { "只支持助手消息生成语音" }
        val card = characterRepository.getById(session.characterCardId) ?: error("角色卡不存在")
        val previousUser = chatRepository.getMessages(sessionId)
            .filter { it.role == MessageRole.USER && it.orderKey < message.orderKey }
            .maxWithOrNull(ChatMessage.TimelineComparator)
            ?.displayContent
            .orEmpty()
        return GenerationContext(session, message, card, previousUser)
    }

    private suspend fun resolveTargets(
        message: ChatMessage,
        card: CharacterCard
    ): List<VoiceGenerationTarget> {
        val anchors = voiceRepository.ensureAnchors(message)
        return anchors.mapNotNull { anchored ->
            val speaker = anchored.segment.speakerName?.trim()?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            val matches = card.characters.filter {
                it.name.trim().equals(speaker, ignoreCase = true)
            }
            val character = matches.singleOrNull()?.takeIf { it.fishAudioVoice != null }
                ?: return@mapNotNull null
            VoiceGenerationTarget(
                message = message,
                segment = anchored.segment,
                anchorId = anchored.anchor.id,
                sourceOrder = anchored.anchor.sourceOrder,
                character = character
            )
        }
    }

    private suspend fun registerAutoPlay(
        batchId: String,
        context: GenerationContext,
        screenGeneration: Long
    ) {
        registerAutoPlay(
            batchId = batchId,
            sessionId = context.session.id,
            messageOrder = context.message.orderKey,
            screenGeneration = screenGeneration
        )
    }

    private suspend fun registerAutoPlay(
        batchId: String,
        sessionId: String,
        messageOrder: Long,
        screenGeneration: Long
    ) {
        autoPlayMutex.withLock {
            pendingAutoPlay[batchId] = PendingAutoPlay(
                batchId = batchId,
                sessionId = sessionId,
                messageOrder = messageOrder,
                foregroundGeneration = screenGeneration,
                createdAt = System.currentTimeMillis()
            )
        }
    }

    private suspend fun finishAutoPlay(
        batchId: String,
        voices: List<GeneratedVoiceMessage>?
    ) {
        autoPlayMutex.withLock {
            val pending = pendingAutoPlay[batchId] ?: return
            pendingAutoPlay[batchId] = pending.copy(terminal = true, voices = voices)
            drainAutoPlayLocked()
        }
    }

    private fun drainAutoPlayLocked() {
        while (true) {
            val next = pendingAutoPlay.values.minWithOrNull(
                compareBy<PendingAutoPlay>(PendingAutoPlay::messageOrder)
                    .thenBy(PendingAutoPlay::createdAt)
            ) ?: return
            if (!next.terminal) return
            pendingAutoPlay.remove(next.batchId)
            val shouldPlay = next.voices?.isNotEmpty() == true &&
                foregroundSessionId == next.sessionId &&
                foregroundGeneration.get() == next.foregroundGeneration
            if (shouldPlay) playback.enqueueSequence(checkNotNull(next.voices))
        }
    }

    private suspend fun clearAutoPlay() {
        autoPlayMutex.withLock {
            foregroundGeneration.incrementAndGet()
            pendingAutoPlay.clear()
        }
    }

    private fun setBatch(state: VoiceGenerationBatchState) {
        _batches.update { current -> current.filterNot { it.id == state.id } + state }
    }

    private suspend fun cancelAndJoinMatching(
        predicate: (VoiceGenerationBatchState) -> Boolean
    ) {
        val targetIds = _batches.value.filter(predicate).map(VoiceGenerationBatchState::id)
        targetIds.forEach(::cancel)
        targetIds.mapNotNull(jobs::get).joinAll()
    }

    private fun updateBatch(
        batchId: String,
        transform: (VoiceGenerationBatchState) -> VoiceGenerationBatchState
    ) {
        _batches.update { current ->
            current.map { if (it.id == batchId) transform(it) else it }
        }
    }

    private fun removeBatch(batchId: String) {
        pendingTextConfirmations.remove(batchId)
        _batches.update { current -> current.filterNot { it.id == batchId } }
    }

    private fun failBatch(batchId: String, error: Throwable) {
        updateBatch(batchId) {
            it.copy(
                phase = VoiceGenerationPhase.FAILED,
                tagStreamText = "",
                errors = it.errors + ("batch" to (error.message ?: error.javaClass.simpleName))
            )
        }
    }

    private fun markCancelled(batchId: String) {
        updateBatch(batchId) {
            it.copy(phase = VoiceGenerationPhase.CANCELLED, tagStreamText = "")
        }
    }

    private data class GenerationContext(
        val session: ChatSession,
        val message: ChatMessage,
        val card: CharacterCard,
        val previousUserMessage: String
    )
}
