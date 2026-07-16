package com.example.chatbar.ui.chat

import android.net.Uri
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.card.CharacterCardImageTarget
import com.example.chatbar.domain.card.CharacterCardImagePolicy
import com.example.chatbar.domain.card.CharacterCardImageUpdater
import com.example.chatbar.domain.card.NamePolicy
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.MessageFormatRepairPolicy
import com.example.chatbar.domain.chat.PlaceholderRenderer
import com.example.chatbar.domain.chat.PromptCacheKeyFactory
import com.example.chatbar.domain.chat.SaveSlotJsonTransfer
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.chat.TimelineArchiveBoundaryPolicy
import com.example.chatbar.domain.chat.editRoleplayMessageSegment
import com.example.chatbar.domain.chat.stripRoleplayStatusSegments
import com.example.chatbar.domain.image.NovelAiImageEvent
import com.example.chatbar.domain.image.ImageFileEncoder
import com.example.chatbar.domain.image.GlobalImageGenerationConcurrencyGate
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.domain.image.NovelAiImageSize
import com.example.chatbar.domain.image.NovelAiImageSizePolicy
import com.example.chatbar.domain.image.NovelAiPromptPlan
import com.example.chatbar.domain.memory.MemoryPromptView
import com.example.chatbar.domain.memory.MemoryBackfillEstimate
import com.example.chatbar.domain.memory.MemoryBackfillProgress
import com.example.chatbar.domain.memory.MemoryTimelinePolicy
import com.example.chatbar.domain.rag.RetrievedKnowledgeCard
import com.example.chatbar.domain.rag.RetrievalPlan
import com.example.chatbar.domain.rag.RagSourcePlan
import com.example.chatbar.domain.rag.ChatMemoryIndexPolicy
import com.example.chatbar.domain.rag.chatMemoryChunkId
import com.example.chatbar.domain.rag.isChatMemoryForSession
import com.example.chatbar.domain.worldbook.WorldBookEngine
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import com.example.chatbar.domain.service.BackgroundGenerationProtectionCancellationException
import com.example.chatbar.domain.service.StreamingNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

enum class ImageGenerationPhase {
    QUEUED,
    DESIGNING,
    GENERATING,
    STREAMING,
    SAVING,
    CANCELLED,
    FAILED
}

data class ImageGenerationState(
    val taskId: String,
    val anchorMessageId: String,
    val phase: ImageGenerationPhase,
    val previewImage: ByteArray? = null,
    val promptDraft: String = "",
    val progress: Float = 0f,
    val error: String? = null,
    val imageContentHint: String = "",
    val finalPromptRequirement: String = ""
)

data class MemoryNodeDiffUi(
    val label: String,
    val before: String?,
    val after: String?
)

data class MemoryVersionUi(
    val revision: MemoryTierRevision,
    val diffs: List<MemoryNodeDiffUi>,
    val isCurrent: Boolean,
    val affectedRangeLabel: String,
    val restoreBeforeRevisionId: String? = null
)

data class LongTermMemoryUiState(
    val nodes: List<MemoryNode> = emptyList(),
    val head: MemoryHead? = null,
    val memoryState: MemorySessionState? = null,
    val versionsByTier: Map<MemoryTier, List<MemoryVersionUi>> = emptyMap(),
    val historyHasMoreByTier: Map<MemoryTier, Boolean> = emptyMap(),
    val preview: String = "",
    val effectiveBudgetChars: Int = 0,
    val usedArchiveChars: Int = 0,
    val backfillEstimate: MemoryBackfillEstimate? = null,
    val backfillProgress: MemoryBackfillProgress? = null,
    val headPresent: Boolean = false,
    val headInitializationPending: Boolean = false,
    val headBackfillRequired: Boolean = false,
    val warnings: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

private const val MEMORY_HISTORY_PAGE_SIZE = 20

internal data class NovelAiImageRegenerationDraft(
    val baseCaption: String,
    val characterPrompts: List<GeneratedImageCharacterPrompt>,
    val negativePrompt: String,
    val sizePreset: String,
    val width: Int,
    val height: Int
) {
    fun toPromptPlan(): NovelAiPromptPlan = NovelAiPromptPlan(
        baseCaption = baseCaption,
        characterCaptions = characterPrompts.map {
            com.example.chatbar.domain.image.NovelAiCharacterCaption(
                prompt = it.prompt,
                center = com.example.chatbar.domain.image.DesignedCharacterCenter(it.centerX, it.centerY)
            )
        },
        sizePreset = com.example.chatbar.domain.image.NovelAiImageSizePreset.from(sizePreset),
        negativePrompt = negativePrompt
    )
}

internal fun GeneratedImageMetadata.toRegenerationDraft(): NovelAiImageRegenerationDraft =
    NovelAiImageRegenerationDraft(
        baseCaption = baseCaption,
        characterPrompts = characterPrompts,
        negativePrompt = negativePrompt,
        sizePreset = sizePreset,
        width = width,
        height = height
    )

data class MessageFormatRepairState(
    val messageId: String,
    val previewContent: String
)

val ImageGenerationState.isTerminal: Boolean
    get() = phase == ImageGenerationPhase.FAILED || phase == ImageGenerationPhase.CANCELLED

val ImageGenerationState.isCancellable: Boolean
    get() = when (phase) {
        ImageGenerationPhase.QUEUED,
        ImageGenerationPhase.DESIGNING,
        ImageGenerationPhase.GENERATING,
        ImageGenerationPhase.STREAMING -> true
        ImageGenerationPhase.SAVING,
        ImageGenerationPhase.CANCELLED,
        ImageGenerationPhase.FAILED -> false
    }

/**
 * 聊天会话 ViewModel
 */
class ChatViewModel(private val sessionId: String) : ViewModel() {

    private val chatRepository = ChatBarApp.instance.chatRepository
    private val characterRepository = ChatBarApp.instance.characterRepository
    private val modelRepository = ChatBarApp.instance.modelRepository
    private val formatCardRepository = ChatBarApp.instance.formatCardRepository
    private val worldBookRepository = ChatBarApp.instance.worldBookRepository
    private val saveSlotRepository = ChatBarApp.instance.saveSlotRepository
    private val settingsRepository = ChatBarApp.instance.settingsRepository
    private val ragManager = ChatBarApp.instance.ragManager
    private val retrievalPlanner = ChatBarApp.instance.retrievalPlanner
    private val promptAssembler = ChatBarApp.instance.promptAssembler
    private val contextWindowManager = ChatBarApp.instance.contextWindowManager
    private val longTermMemoryService = ChatBarApp.instance.longTermMemoryService
    private val streamingChatService = ChatBarApp.instance.streamingChatService
    private val messageFormatRepairService = ChatBarApp.instance.messageFormatRepairService
    private val imageUnderstandingService = ChatBarApp.instance.imageUnderstandingService
    private val modelResolver = ChatBarApp.instance.effectiveModelResolver
    private val novelAiCredentials = ChatBarApp.instance.novelAiCredentialStore
    private val novelAiPromptDesigner = ChatBarApp.instance.novelAiPromptDesigner
    private val novelAiImageService = ChatBarApp.instance.novelAiImageService
    private val novelAiImageStorage = ChatBarApp.instance.novelAiImageStorage
    private val ragMemoryMutationMutex = Mutex()

    // UI 状态
    private val _session = MutableStateFlow<ChatSession?>(null)
    val session: StateFlow<ChatSession?> = _session.asStateFlow()

    private val _characterCard = MutableStateFlow<CharacterCard?>(null)
    val characterCard: StateFlow<CharacterCard?> = _characterCard.asStateFlow()

    private val _isArchived = MutableStateFlow(false)
    val isArchived: StateFlow<Boolean> = _isArchived.asStateFlow()

    private val _modelConfigurationErrors = MutableStateFlow<List<String>>(emptyList())
    val modelConfigurationErrors: StateFlow<List<String>> = _modelConfigurationErrors.asStateFlow()
    private val _isModelUsable = MutableStateFlow(false)
    val isModelUsable: StateFlow<Boolean> = _isModelUsable.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _draftInput = MutableStateFlow("")
    val draftInput: StateFlow<String> = _draftInput.asStateFlow()
    private val _draftLoaded = MutableStateFlow(false)
    val draftLoaded: StateFlow<Boolean> = _draftLoaded.asStateFlow()

    private val _isResponding = MutableStateFlow(false)
    val isResponding: StateFlow<Boolean> = _isResponding.asStateFlow()

    private val _streamingMessage = MutableStateFlow<ChatMessage?>(null)
    val streamingMessage: StateFlow<ChatMessage?> = _streamingMessage.asStateFlow()

    private val _messageFormatRepairState = MutableStateFlow<MessageFormatRepairState?>(null)
    val messageFormatRepairState: StateFlow<MessageFormatRepairState?> =
        _messageFormatRepairState.asStateFlow()

    private val _messageFormatRepairEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messageFormatRepairEvents: SharedFlow<String> = _messageFormatRepairEvents.asSharedFlow()

    private val _memoryCompressionEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val memoryCompressionEvents: SharedFlow<String> = _memoryCompressionEvents.asSharedFlow()
    private val memoryHistoryLimits = ConcurrentHashMap<MemoryTier, Int>()

    private val _isDeletingMemory = MutableStateFlow(false)
    val isDeletingMemory: StateFlow<Boolean> = _isDeletingMemory.asStateFlow()

    private val _chatBubbleFontScale = MutableStateFlow(1.0f)
    val chatBubbleFontScale: StateFlow<Float> = _chatBubbleFontScale.asStateFlow()

    private val _assistantSegmentedBubblesEnabled = MutableStateFlow(true)
    val assistantSegmentedBubblesEnabled: StateFlow<Boolean> = _assistantSegmentedBubblesEnabled.asStateFlow()

    private val _contextWindowSize = MutableStateFlow(20)
    val contextWindowSize: StateFlow<Int> = _contextWindowSize.asStateFlow()
    val novelAiConfigured: StateFlow<Boolean> = novelAiCredentials.configured

    private val imageGenerationTaskStore = ImageGenerationTaskStateStore()
    val imageGenerations: StateFlow<List<ImageGenerationState>> = imageGenerationTaskStore.states

    private val _showBatteryOptimizationHint = MutableStateFlow(false)
    val showBatteryOptimizationHint: StateFlow<Boolean> = _showBatteryOptimizationHint.asStateFlow()

    // 各种配置项列表（用于设置面板）
    private val _availableModels = MutableStateFlow<List<ModelConfig>>(emptyList())
    val availableModels: StateFlow<List<ModelConfig>> = _availableModels.asStateFlow()

    private val _availableFormats = MutableStateFlow<List<FormatCard>>(emptyList())
    val availableFormats: StateFlow<List<FormatCard>> = _availableFormats.asStateFlow()

    private val _availableWorldBooks = MutableStateFlow<List<WorldBook>>(emptyList())
    val availableWorldBooks: StateFlow<List<WorldBook>> = _availableWorldBooks.asStateFlow()

    private val _effectiveDefaultModelId = MutableStateFlow<String?>(null)
    val effectiveDefaultModelId: StateFlow<String?> = _effectiveDefaultModelId.asStateFlow()

    private val _effectiveDefaultImageModelId = MutableStateFlow<String?>(null)
    val effectiveDefaultImageModelId: StateFlow<String?> = _effectiveDefaultImageModelId.asStateFlow()

    private val _effectiveDefaultFormatCardId = MutableStateFlow<String?>(null)
    val effectiveDefaultFormatCardId: StateFlow<String?> = _effectiveDefaultFormatCardId.asStateFlow()

    private val _availableSaveSlots = MutableStateFlow<List<SaveSlotSummary>>(emptyList())
    val availableSaveSlots: StateFlow<List<SaveSlotSummary>> = _availableSaveSlots.asStateFlow()

    private val _ragMemoryChunks = MutableStateFlow<List<VectorChunk>>(emptyList())
    val ragMemoryChunks: StateFlow<List<VectorChunk>> = _ragMemoryChunks.asStateFlow()

    private val _ragMemoryStatus = MutableStateFlow<String?>(null)
    val ragMemoryStatus: StateFlow<String?> = _ragMemoryStatus.asStateFlow()

    private val _ragMemoryBusy = MutableStateFlow(false)
    val ragMemoryBusy: StateFlow<Boolean> = _ragMemoryBusy.asStateFlow()

    private val _longTermMemoryUiState = MutableStateFlow(LongTermMemoryUiState(loading = true))
    val longTermMemoryUiState: StateFlow<LongTermMemoryUiState> = _longTermMemoryUiState.asStateFlow()
    @Volatile
    private var memoryBackfillProgress: MemoryBackfillProgress? = null

    private var draftTouched = false
    private var draftSaveSequence = 0
    private var responseJob: Job? = null
    private var manualFormatRepairJob: Job? = null
    private var activeFormatRepairJob: Job? = null
    private var formatRepairStopRequested = false
    private val imageGenerationJobs = ConcurrentHashMap<String, Job>()
    private val imageGenerationPromptCheckpoints =
        ConcurrentHashMap<String, Pair<NovelAiPromptPlan, NovelAiImageSize>>()
    private val imageGenerationCompletedImageCheckpoints = ConcurrentHashMap<String, ByteArray>()
    private val imageGenerationRegenerationSources = ConcurrentHashMap<String, Pair<String, String>>()
    private val imagePromptPreferenceMutationMutex = Mutex()
    private val imagePromptPreferenceSaveSequence = AtomicLong()
    private val messageRefreshSequence = AtomicLong()

    private data class PlaceholderRenderContext(
        val playerName: String?,
        val botName: String
    )

    init {
        loadSessionData()
        refreshConfigurations()
        observeSessionChanges()
        observeCharacterCardChanges()
        observeSettingsChanges()
    }

    private fun observeSessionChanges() {
        viewModelScope.launch {
            chatRepository.observeSessions().collect { sessions ->
                sessions.find { it.id == sessionId }?.let { updatedSession ->
                    _session.value = updatedSession
                }
            }
        }
    }

    private fun observeSettingsChanges() {
        viewModelScope.launch {
            settingsRepository.appSettings.collect { settings ->
                _chatBubbleFontScale.value = settings.chatBubbleFontScale
                _assistantSegmentedBubblesEnabled.value = settings.assistantSegmentedBubblesEnabled
            }
        }
    }

    private fun observeCharacterCardChanges() {
        viewModelScope.launch {
            characterRepository.initialize()
            combine(_session, characterRepository.characters) { session, cards ->
                session?.let { activeSession ->
                    cards.firstOrNull { it.id == activeSession.characterCardId }
                }
            }.collect { card ->
                if (_session.value != null) {
                    _characterCard.value = card
                    _isArchived.value = card == null
                }
            }
        }
    }

    fun loadSessionData() {
        viewModelScope.launch {
            val s = chatRepository.getSession(sessionId)
            _session.value = s
            if (s != null && !draftTouched) {
                _draftInput.value = chatRepository.getSessionDraft(sessionId)
            }
            _draftLoaded.value = true
            val settings = settingsRepository.getAppSettings()
            _contextWindowSize.value = settings.defaultContextWindowSize.coerceAtLeast(1)
            _chatBubbleFontScale.value = settings.chatBubbleFontScale
            _assistantSegmentedBubblesEnabled.value = settings.assistantSegmentedBubblesEnabled
            if (s != null) {
                val card = characterRepository.getById(s.characterCardId)
                _characterCard.value = card
                _isArchived.value = card == null
                refreshMessages()
                refreshSaveSlots()
                _longTermMemoryUiState.value = runCatching { loadLongTermMemoryUiState() }
                    .getOrElse { error ->
                        LongTermMemoryUiState(
                            loading = false,
                            error = error.message ?: "长期记忆读取失败"
                        )
                    }
            }
        }
    }

    fun refreshConfigurations() {
        viewModelScope.launch {
            val settings = settingsRepository.getAppSettings()
            val availableModels = modelResolver.availableChatModels(settings)
            _availableModels.value = availableModels
            _effectiveDefaultModelId.value = modelResolver.resolveChatModel(null, settings)?.id
            _effectiveDefaultImageModelId.value = modelResolver.resolveImageModel(null, settings)?.id
            val formats = formatCardRepository.getAll()
            _availableFormats.value = formats
            _availableWorldBooks.value = worldBookRepository.getAll()
            _effectiveDefaultFormatCardId.value = settings.defaultFormatCardId
            val status = modelResolver.status(settings)
            _modelConfigurationErrors.value = status.errors
            _isModelUsable.value = status.isUsable
        }
    }

    fun refreshMessages() {
        viewModelScope.launch {
            try {
                replaceMessagesFromRepository()
            } catch (_: Exception) {}
        }
    }

    private suspend fun replaceMessagesFromRepository() {
        val sequence = messageRefreshSequence.incrementAndGet()
        val refreshed = chatRepository.getMessages(sessionId)
        if (sequence == messageRefreshSequence.get()) {
            _messages.value = refreshed
        }
    }

    fun refreshSaveSlots() {
        viewModelScope.launch {
            try {
                _availableSaveSlots.value = saveSlotRepository.getBySessionId(sessionId)
            } catch (_: Exception) {}
        }
    }

    private suspend fun placeholderRenderContext(
        session: ChatSession? = _session.value
    ): PlaceholderRenderContext? {
        val currentSession = session ?: chatRepository.getSession(sessionId) ?: return null
        val card = _characterCard.value?.takeIf { it.id == currentSession.characterCardId }
            ?: characterRepository.getById(currentSession.characterCardId)
        val globalPlayerName = settingsRepository.getPlayerSetting()
            .playerName
            .takeIf { it.isNotBlank() }
        val playerName = currentSession.playerName?.takeIf { it.isNotBlank() } ?: globalPlayerName
        return PlaceholderRenderContext(
            playerName = playerName,
            botName = card?.name ?: currentSession.title
        )
    }

    private fun ChatMessage.renderWith(context: PlaceholderRenderContext?): ChatMessage =
        context?.let { PlaceholderRenderer.renderMessage(this, it.playerName, it.botName) } ?: this

    private fun List<ChatMessage>.renderWith(context: PlaceholderRenderContext?): List<ChatMessage> =
        if (context == null) this else map { it.renderWith(context) }

    private fun imageGenerationState(taskId: String): ImageGenerationState? =
        imageGenerationTaskStore.get(taskId)

    private fun putImageGeneration(state: ImageGenerationState) {
        imageGenerationTaskStore.put(state)
    }

    private fun updateImageGeneration(
        taskId: String,
        transform: (ImageGenerationState) -> ImageGenerationState
    ) {
        imageGenerationTaskStore.update(taskId, transform)
    }

    private fun removeImageGeneration(taskId: String) {
        imageGenerationTaskStore.remove(taskId)
        imageGenerationPromptCheckpoints.remove(taskId)
        imageGenerationCompletedImageCheckpoints.remove(taskId)
        imageGenerationRegenerationSources.remove(taskId)
    }

    private fun persistImagePromptPreference(preference: String) {
        val current = _session.value ?: return
        if (current.imagePromptPreference == preference) return
        val sequence = imagePromptPreferenceSaveSequence.incrementAndGet()
        _session.value = current.copy(imagePromptPreference = preference)
        ChatBarApp.instance.applicationScope.launch {
            imagePromptPreferenceMutationMutex.withLock {
                if (sequence != imagePromptPreferenceSaveSequence.get()) return@withLock
                val stored = chatRepository.getSession(sessionId) ?: return@withLock
                if (stored.imagePromptPreference != preference) {
                    chatRepository.updateSession(stored.copy(imagePromptPreference = preference))
                }
            }
        }
    }

    fun generateNovelAiImage(
        anchorMessageId: String,
        imageContentHint: String = "",
        imagePromptPreference: String? = null,
        persistPreference: Boolean = false
    ) {
        startNovelAiImageGeneration(
            taskId = UUID.randomUUID().toString(),
            anchorMessageId = anchorMessageId,
            imageContentHint = imageContentHint,
            imagePromptPreference = imagePromptPreference,
            persistPreference = persistPreference
        )
    }

    fun retryNovelAiImageGeneration(taskId: String) {
        val failed = imageGenerationState(taskId)?.takeIf { it.isTerminal } ?: return
        imageGenerationJobs.remove(taskId)?.cancel(CancellationException("用户重试生图"))
        val checkpoint = imageGenerationPromptCheckpoints[taskId]
        val regenerationSource = imageGenerationRegenerationSources[taskId]
        if (checkpoint == null && regenerationSource != null) {
            loadNovelAiRegeneration(
                taskId = taskId,
                messageId = regenerationSource.first,
                imagePath = regenerationSource.second
            )
            return
        }
        startNovelAiImageGeneration(
            taskId = taskId,
            anchorMessageId = failed.anchorMessageId,
            imageContentHint = failed.imageContentHint,
            imagePromptPreference = failed.finalPromptRequirement,
            promptOverride = checkpoint?.first,
            imageSizeOverride = checkpoint?.second
        )
    }

    fun refreshRagMemoryChunks() {
        viewModelScope.launch {
            runCatching { loadRagMemoryChunks() }
                .onFailure { _ragMemoryStatus.value = "读取 RAG 检索库失败：${it.message}" }
        }
    }

    suspend fun saveRagMemoryChunk(chunkId: String?, content: String): Boolean {
        val normalizedContent = content.trim()
        if (normalizedContent.isBlank()) {
            _ragMemoryStatus.value = "RAG 块内容不能为空。"
            return false
        }
        if (_ragMemoryBusy.value) return false

        _ragMemoryBusy.value = true
        _ragMemoryStatus.value = if (chunkId == null) "正在创建 RAG 块…" else "正在更新 RAG 块…"
        return try {
            ragMemoryMutationMutex.withLock {
                val embeddingConfig = modelResolver.embeddingModel(settingsRepository.getAppSettings())
                    ?: error("当前配置层级没有可用向量模型")
                val embedding = ChatBarApp.instance.embeddingService.getEmbedding(
                    normalizedContent,
                    embeddingConfig
                )
                val existing = chunkId?.let { ChatBarApp.instance.ragRepository.getChunkById(it) }
                if (chunkId != null && existing?.isChatMemoryForSession(sessionId) != true) {
                    error("目标 RAG 块不存在或不属于当前会话")
                }
                val now = System.currentTimeMillis()
                val updatedIndexMode = if (existing?.let(ChatMemoryIndexPolicy::needsAutomaticRebuild) == true) {
                    "manual"
                } else {
                    existing?.metadata?.get("indexMode") ?: "manual"
                }
                val chunk = existing?.copy(
                    content = normalizedContent,
                    embedding = embedding,
                    metadata = existing.metadata + mapOf(
                        "embeddingKey" to ragManager.embeddingKey(embeddingConfig),
                        "editedAt" to now.toString(),
                        "indexMode" to updatedIndexMode,
                        "contentVersion" to "2"
                    )
                ) ?: VectorChunk.create(
                    sourceType = ChunkSourceType.CHAT_MEMORY,
                    sourceId = sessionId,
                    content = normalizedContent,
                    embedding = embedding,
                    metadata = mapOf(
                        "sessionId" to sessionId,
                        "indexMode" to "manual",
                        "role" to "MANUAL",
                        "embeddingKey" to ragManager.embeddingKey(embeddingConfig)
                    )
                )
                ChatBarApp.instance.ragRepository.saveChunks(listOf(chunk))
                loadRagMemoryChunks()
            }
            _ragMemoryStatus.value = if (chunkId == null) "RAG 块已创建。" else "RAG 块已更新。"
            true
        } catch (e: Exception) {
            _ragMemoryStatus.value = "保存失败：${e.message}"
            false
        } finally {
            _ragMemoryBusy.value = false
        }
    }

    suspend fun deleteRagMemoryChunk(chunkId: String): Boolean {
        if (_ragMemoryBusy.value) return false
        _ragMemoryBusy.value = true
        _ragMemoryStatus.value = "正在删除 RAG 块…"
        return try {
            ragMemoryMutationMutex.withLock {
                val existing = ChatBarApp.instance.ragRepository.getChunkById(chunkId)
                if (existing?.isChatMemoryForSession(sessionId) != true) {
                    error("目标 RAG 块不存在或不属于当前会话")
                }
                ChatBarApp.instance.ragRepository.deleteChunkById(chunkId)
                loadRagMemoryChunks()
            }
            _ragMemoryStatus.value = "RAG 块已删除。"
            true
        } catch (e: Exception) {
            _ragMemoryStatus.value = "删除失败：${e.message}"
            false
        } finally {
            _ragMemoryBusy.value = false
        }
    }

    suspend fun rebuildLegacyRagMemoryChunks(): Boolean {
        if (_ragMemoryBusy.value) return false
        _ragMemoryBusy.value = true
        _ragMemoryStatus.value = "正在读取已滑出上下文的原始T轮次…"
        return try {
            ragMemoryMutationMutex.withLock {
                val repository = ChatBarApp.instance.ragRepository
                val allMessages = chatRepository.getMessages(sessionId)
                repository.pruneChatMemory(
                    sessionId = sessionId,
                    liveMessageIds = allMessages.mapTo(mutableSetOf()) { it.id }
                )
                val activeIds = contextWindowManager
                    .getRecentMessages(allMessages, effectiveContextWindowSize())
                    .mapTo(mutableSetOf()) { it.id }
                val renderContext = placeholderRenderContext()
                val renderedMessages = allMessages.renderWith(renderContext)
                val turnsToRebuild = ChatMemoryIndexPolicy.buildIndexableTurns(
                    messages = renderedMessages,
                    activeMessageIds = activeIds
                )

                if (turnsToRebuild.isNotEmpty()) {
                    val embeddingConfig = modelResolver.embeddingModel(settingsRepository.getAppSettings())
                        ?: error("当前配置层级没有可用向量模型")
                    AiBackgroundWorkManager.run(sessionId) {
                        turnsToRebuild.forEachIndexed { index, turn ->
                            _ragMemoryStatus.value = "正在重建RAG索引 ${index + 1}/${turnsToRebuild.size}…"
                            ragManager.indexTimelineTurnMemory(turn, sessionId, embeddingConfig)
                        }
                    }
                }
                repository.deleteObsoleteAutomaticChatMemory(
                    sessionId = sessionId,
                    keepChunkIds = turnsToRebuild.mapTo(mutableSetOf()) { turn ->
                        chatMemoryChunkId(sessionId, turn.identityKey)
                    }
                )
                loadRagMemoryChunks()
                _ragMemoryStatus.value = if (turnsToRebuild.isEmpty()) {
                    "当前没有已滑出上下文、需要建立索引的T轮次。"
                } else {
                    "RAG索引已按完整T轮次重建。"
                }
            }
            true
        } catch (e: Exception) {
            _ragMemoryStatus.value = "重建失败：${e.message}"
            runCatching { loadRagMemoryChunks() }
            false
        } finally {
            _ragMemoryBusy.value = false
        }
    }

    fun clearRagMemoryStatus() {
        _ragMemoryStatus.value = null
    }

    private suspend fun loadRagMemoryChunks() {
        _ragMemoryChunks.value = ChatBarApp.instance.ragRepository
            .getAllChunksForSession(sessionId)
            .sortedByDescending { it.createdAt }
    }

    internal suspend fun loadNovelAiImageRegenerationDraft(
        messageId: String,
        imagePath: String
    ): NovelAiImageRegenerationDraft {
        val message = _messages.value.firstOrNull { it.id == messageId }
            ?: error("原图片消息不存在")
        val metadata = message.generatedImageMetadata.firstOrNull { it.imagePath == imagePath }
            ?: withContext(Dispatchers.IO) {
                com.example.chatbar.domain.image.NovelAiPngMetadataReader.read(imagePath)
            }
            ?: error("该图片不含可复用的 NovelAI 元数据")
        return metadata.toRegenerationDraft()
    }

    fun refreshLongTermMemory() {
        viewModelScope.launch {
            _longTermMemoryUiState.value = _longTermMemoryUiState.value.copy(loading = true, error = null)
            runCatching {
                val settings = settingsRepository.getAppSettings()
                _contextWindowSize.value = settings.defaultContextWindowSize.coerceAtLeast(1)
                longTermMemoryService.refreshForCurrentConditions(sessionId)
                loadLongTermMemoryUiState()
            }
                .onSuccess { _longTermMemoryUiState.value = it }
                .onFailure {
                    _longTermMemoryUiState.value = _longTermMemoryUiState.value.copy(
                        loading = false,
                        error = it.message ?: "长期记忆读取失败"
                    )
                }
        }
    }

    fun editMemoryNode(nodeId: String, bodyText: String) {
        viewModelScope.launch {
            val error = runCatching {
                longTermMemoryService.editNode(sessionId, nodeId, bodyText)
            }.exceptionOrNull()
            refreshMemoryAfterAction(error)
        }
    }

    fun editMemoryHead(head: MemoryHead) {
        viewModelScope.launch {
            val error = runCatching {
                longTermMemoryService.editHead(sessionId, head)
            }.exceptionOrNull()
            refreshMemoryAfterAction(error)
        }
    }

    fun restoreMemoryVersion(revisionId: String) {
        viewModelScope.launch {
            val error = runCatching {
                longTermMemoryService.restoreTierRevision(sessionId, revisionId)
            }.exceptionOrNull()
            refreshMemoryAfterAction(error)
        }
    }

    private suspend fun refreshMemoryAfterAction(actionError: Throwable?) {
        _session.value = chatRepository.getSession(sessionId)
        val loaded = runCatching { loadLongTermMemoryUiState() }
        _longTermMemoryUiState.value = loaded.getOrElse { loadError ->
            _longTermMemoryUiState.value.copy(
                loading = false,
                error = loadError.message ?: "长期记忆读取失败"
            )
        }.let { state ->
            if (actionError == null) state else state.copy(
                error = actionError.message ?: "长期记忆操作失败"
            )
        }
    }

    fun increaseMemoryLimit() {
        viewModelScope.launch {
            longTermMemoryService.increaseLimit(sessionId)
            _session.value = chatRepository.getSession(sessionId)
            refreshLongTermMemory()
        }
    }

    fun retryMemoryMaintenance() {
        viewModelScope.launch {
            val error = runCatching {
                val current = chatRepository.getSession(sessionId) ?: error("会话不存在")
                val model = modelResolver.resolveChatModel(current.modelId) ?: error("对话模型未配置")
                longTermMemoryService.updateArchiveAfterReply(
                    sessionId,
                    model,
                    _contextWindowSize.value
                )
            }.exceptionOrNull()
            refreshMemoryAfterAction(error)
            drainMemoryCompressionEvents()
        }
    }

    fun retryMemoryHead() {
        viewModelScope.launch {
            val error = runCatching {
                val current = chatRepository.getSession(sessionId) ?: error("会话不存在")
                val model = modelResolver.resolveChatModel(current.modelId) ?: error("对话模型未配置")
                longTermMemoryService.prepareHeadBeforePrompt(sessionId, model)
            }.exceptionOrNull()
            refreshMemoryAfterAction(error)
        }
    }

    fun drainMemoryCompressionEvents() {
        viewModelScope.launch {
            longTermMemoryService.consumeCompressionEvents(sessionId).forEach { event ->
                val message = when (event.kind) {
                    MemoryCompressionKind.EPISODE_TO_ARC ->
                        "近期流程已有 ${event.consumedCount} 条压缩到事件总结"
                    MemoryCompressionKind.ARC_TO_ERA ->
                        "事件总结已有 ${event.consumedCount} 条压缩到故事进程"
                    MemoryCompressionKind.ERA_TO_ERA ->
                        "故事进程已有 ${event.consumedCount} 条进一步压缩为 1 条故事进程"
                }
                _memoryCompressionEvents.emit(message)
            }
        }
    }

    fun pauseMemoryUpdates() {
        viewModelScope.launch {
            longTermMemoryService.pauseBackfill(sessionId)
            _session.value = chatRepository.getSession(sessionId)
            refreshLongTermMemory()
        }
    }

    fun markMemorySourcesCorrected(nodeIds: List<String>) {
        viewModelScope.launch {
            val error = runCatching {
                longTermMemoryService.markSourcesCorrected(sessionId, nodeIds)
            }.exceptionOrNull()
            refreshMemoryAfterAction(error)
        }
    }

    fun loadMoreMemoryHistory(tier: MemoryTier) {
        memoryHistoryLimits.compute(tier) { _, current ->
            (current ?: MEMORY_HISTORY_PAGE_SIZE) + MEMORY_HISTORY_PAGE_SIZE
        }
        refreshLongTermMemory()
    }

    fun resolveMemoryCompressionDecision(expand: Boolean) {
        viewModelScope.launch {
            val error = runCatching {
                val current = chatRepository.getSession(sessionId) ?: error("会话不存在")
                val model = modelResolver.resolveChatModel(current.modelId) ?: error("对话模型未配置")
                longTermMemoryService.resolveCompressionDecision(
                    sessionId = sessionId,
                    expand = expand,
                    modelConfig = model,
                    contextWindowSize = _contextWindowSize.value
                )
            }.exceptionOrNull()
            refreshMemoryAfterAction(error)
        }
    }

    fun rebuildMemoryFromOriginal() = launchMemoryBackfill()

    private fun launchMemoryBackfill(prepare: suspend () -> Unit = {}) {
        val currentState = _longTermMemoryUiState.value
        _longTermMemoryUiState.value = currentState.copy(
            memoryState = currentState.memoryState?.copy(
                backfill = currentState.memoryState.backfill.copy(
                    status = MemoryBackfillStatus.RUNNING,
                    error = null
                )
            )
        )
        memoryBackfillProgress = null
        viewModelScope.launch {
            val error = runCatching {
                prepare()
                val current = chatRepository.getSession(sessionId) ?: error("会话不存在")
                val model = modelResolver.resolveChatModel(current.modelId) ?: error("对话模型未配置")
                longTermMemoryService.startBackfill(sessionId, model) { progress ->
                    memoryBackfillProgress = progress
                    _longTermMemoryUiState.update { it.copy(backfillProgress = progress) }
                }
            }.exceptionOrNull()
            memoryBackfillProgress = null
            refreshMemoryAfterAction(error)
        }
    }

    private suspend fun loadLongTermMemoryUiState(): LongTermMemoryUiState {
        val view = longTermMemoryService.promptView(sessionId)
        val state = longTermMemoryService.currentState(sessionId) ?: error("当前记忆状态不存在")
        val nodes = longTermMemoryService.activeNodes(sessionId)
        val backfillEstimate = longTermMemoryService.estimateBackfill(sessionId)
        fun rangeLabel(sourceTurnIds: List<String>): String {
            val range = MemoryTimelinePolicy.range(sourceTurnIds, state.timeline)
            return if (range == null) "T?" else "T${range.startT}-T${range.endT}"
        }
        val historyHasMore = mutableMapOf<MemoryTier, Boolean>()
        val versionsByTier = listOf(MemoryTier.EPISODE, MemoryTier.ARC, MemoryTier.ERA)
            .associateWith { tier ->
                val limit = memoryHistoryLimits[tier] ?: MEMORY_HISTORY_PAGE_SIZE
                val history = longTermMemoryService.history(sessionId, tier, limit + 1)
                historyHasMore[tier] = history.size > limit
                history.take(limit).map { revision ->
                    val after = longTermMemoryService.revisionNodes(revision.id)
                    val before = revision.parentRevisionId
                        ?.let { longTermMemoryService.revisionNodes(it) }
                        .orEmpty()
                    fun key(node: MemoryNode): String = node.sourceTurnIds.joinToString("\u001F")
                    val beforeByRange = before.associateBy(::key)
                    val afterByRange = after.associateBy(::key)
                    val diffs = (beforeByRange.keys + afterByRange.keys).distinct().mapNotNull { key ->
                        val old = beforeByRange[key]
                        val new = afterByRange[key]
                        if (old?.body == new?.body && old?.id == new?.id) return@mapNotNull null
                        MemoryNodeDiffUi(
                            label = rangeLabel((new ?: old)?.sourceTurnIds.orEmpty()),
                            before = old?.body,
                            after = new?.body
                        )
                    }
                    MemoryVersionUi(
                        revision = revision,
                        diffs = diffs,
                        isCurrent = state.page(tier).currentRevisionId == revision.id,
                        affectedRangeLabel = rangeLabel(revision.affectedSourceTurnIds),
                        restoreBeforeRevisionId = revision.parentRevisionId
                    )
                }
            }
        return LongTermMemoryUiState(
            nodes = nodes,
            head = state.head,
            memoryState = state,
            versionsByTier = versionsByTier,
            historyHasMoreByTier = historyHasMore,
            preview = view.fullText,
            effectiveBudgetChars = view.effectiveBudgetChars,
            usedArchiveChars = view.usedArchiveChars,
            backfillEstimate = backfillEstimate,
            backfillProgress = memoryBackfillProgress,
            headPresent = view.headPresent,
            headInitializationPending = view.headInitializationPending,
            headBackfillRequired = view.headBackfillRequired,
            warnings = view.warnings.map { it.message },
            loading = false
        )
    }

    internal fun regenerateNovelAiImage(
        messageId: String,
        imagePath: String,
        draft: NovelAiImageRegenerationDraft
    ) {
        if (_messages.value.none { it.id == messageId } || draft.baseCaption.isBlank()) return
        val taskId = UUID.randomUUID().toString()
        imageGenerationRegenerationSources[taskId] = messageId to imagePath
        startNovelAiImageGeneration(
            taskId = taskId,
            anchorMessageId = messageId,
            promptOverride = draft.toPromptPlan(),
            imageSizeOverride = NovelAiImageSize(draft.width, draft.height, "复用原图尺寸")
        )
    }

    fun regenerateNovelAiImage(messageId: String, imagePath: String) {
        if (_messages.value.none { it.id == messageId }) return
        val taskId = UUID.randomUUID().toString()
        imageGenerationRegenerationSources[taskId] = messageId to imagePath
        loadNovelAiRegeneration(taskId, messageId, imagePath)
    }

    private fun loadNovelAiRegeneration(taskId: String, messageId: String, imagePath: String) {
        putImageGeneration(
            ImageGenerationState(
                taskId = taskId,
                anchorMessageId = messageId,
                phase = ImageGenerationPhase.QUEUED
            )
        )
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val message = _messages.value.firstOrNull { it.id == messageId }
            if (message == null) {
                updateImageGeneration(taskId) {
                    it.copy(phase = ImageGenerationPhase.FAILED, error = "原图片消息不存在")
                }
                return@launch
            }
            val metadata = try {
                message.generatedImageMetadata.firstOrNull { it.imagePath == imagePath }
                    ?: withContext(Dispatchers.IO) {
                        com.example.chatbar.domain.image.NovelAiPngMetadataReader.read(imagePath)
                    }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                updateImageGeneration(taskId) {
                    it.copy(
                        phase = ImageGenerationPhase.FAILED,
                        error = "读取图片元数据失败：${error.message ?: "未知错误"}"
                    )
                }
                return@launch
            }
            if (metadata == null) {
                updateImageGeneration(taskId) {
                    it.copy(
                        phase = ImageGenerationPhase.FAILED,
                        error = "该图片不含可复用的 NovelAI 元数据"
                    )
                }
                return@launch
            }
            val prompt = metadata.toRegenerationDraft().toPromptPlan()
            startNovelAiImageGeneration(
                taskId = taskId,
                anchorMessageId = messageId,
                promptOverride = prompt,
                imageSizeOverride = NovelAiImageSize(metadata.width, metadata.height, "复用原图尺寸")
            )
        }
        imageGenerationJobs[taskId] = job
        job.invokeOnCompletion { error ->
            imageGenerationJobs.remove(taskId, job)
            if (error != null && error !is CancellationException) {
                updateImageGeneration(taskId) { state ->
                    if (state.isTerminal) state else state.copy(
                        phase = ImageGenerationPhase.FAILED,
                        error = "生图任务异常结束：${error.message ?: "未知错误"}"
                    )
                }
            }
        }
        job.start()
    }

    private fun startNovelAiImageGeneration(
        taskId: String,
        anchorMessageId: String,
        imageContentHint: String = "",
        imagePromptPreference: String? = null,
        persistPreference: Boolean = false,
        promptOverride: NovelAiPromptPlan? = null,
        imageSizeOverride: NovelAiImageSize? = null
    ) {
        if (promptOverride == null) {
            imageGenerationPromptCheckpoints.remove(taskId)
            imageGenerationCompletedImageCheckpoints.remove(taskId)
        } else if (imageSizeOverride != null) {
            imageGenerationPromptCheckpoints[taskId] = promptOverride to imageSizeOverride
            imageGenerationCompletedImageCheckpoints.remove(taskId)
        }
        val initialPreference = imagePromptPreference ?: _session.value?.imagePromptPreference.orEmpty()
        if (persistPreference) persistImagePromptPreference(initialPreference)
        putImageGeneration(ImageGenerationState(
            taskId = taskId,
            anchorMessageId = anchorMessageId,
            phase = ImageGenerationPhase.QUEUED,
            imageContentHint = imageContentHint,
            finalPromptRequirement = initialPreference
        ))
        val job = ChatBarApp.instance.applicationScope.launch(start = CoroutineStart.LAZY) {
            GlobalImageGenerationConcurrencyGate.instance.run generationPermit@ {
            updateImageGeneration(taskId) {
                it.copy(
                    phase = if (promptOverride == null) {
                        ImageGenerationPhase.DESIGNING
                    } else {
                        ImageGenerationPhase.GENERATING
                    },
                    error = null
                )
            }
            val token = novelAiCredentials.load()
            val currentSession = chatRepository.getSession(sessionId)
            val finalPromptRequirement = initialPreference
            val card = currentSession?.let { characterRepository.getById(it.characterCardId) }
            val settings = settingsRepository.getAppSettings()
            val model = currentSession?.let { modelResolver.resolveImageModel(it.imageModelId, settings) }
            val imageRatioError = NovelAiImageSizePolicy.validationError(settings.novelAiImageAspectRatio)
            val globalPlayerName = settingsRepository.getPlayerSetting()
                .playerName
                .takeIf { it.isNotBlank() }
            val playerName = currentSession?.playerName?.takeIf { it.isNotBlank() } ?: globalPlayerName
            if (token == null || (promptOverride == null && (card == null || model == null || model.apiKey.isBlank()))) {
                val missing = mutableListOf<String>()
                if (token == null) missing.add("NovelAI Token")
                if (promptOverride == null && card == null) missing.add("角色卡")
                if (promptOverride == null && (model == null || model.apiKey.isBlank())) missing.add("默认生图模型/API Key")
                updateImageGeneration(taskId) {
                    it.copy(
                        phase = ImageGenerationPhase.FAILED,
                        error = "缺少配置：${missing.joinToString("、")} 不可用，无法生图"
                    )
                }
                return@generationPermit
            }
            if (promptOverride == null && imageRatioError != null) {
                updateImageGeneration(taskId) {
                    it.copy(phase = ImageGenerationPhase.FAILED, error = imageRatioError)
                }
                return@generationPermit
            }
            try {
                AiBackgroundWorkManager.run(sessionId) {
                val prompt = promptOverride ?: novelAiPromptDesigner.design(
                    chatRepository.getMessages(sessionId),
                    anchorMessageId,
                    card!!,
                    model!!,
                    playerName = playerName,
                    sessionId = sessionId,
                    imageContentHint = imageContentHint,
                    finalPromptRequirement = finalPromptRequirement
                ) { draft ->
                    val current = imageGenerationState(taskId)
                    if (current?.phase == ImageGenerationPhase.DESIGNING) {
                        updateImageGeneration(taskId) { it.copy(promptDraft = draft) }
                    }
                }
                val imageSize = imageSizeOverride
                    ?: NovelAiImageSizePolicy.resolve(settings.novelAiImageAspectRatio, prompt.sizePreset)
                imageGenerationPromptCheckpoints[taskId] = prompt to imageSize
                imageGenerationCompletedImageCheckpoints[taskId]?.let { completedImage ->
                    saveNovelAiImageResult(taskId, anchorMessageId, prompt, imageSize, completedImage)
                    return@run
                }
                val seed = novelAiImageService.newSeed()
                val requestBody = novelAiImageService.buildRequestBody(prompt, seed, imageSize)
                com.example.chatbar.utils.DebugLogManager.recordCompleted(
                    sessionId = sessionId,
                    modelName = "NovelAI Diffusion V4.5 Full",
                    apiUrl = "https://image.novelai.net/ai/generate-image-stream",
                    requestBodyJson = requestBody,
                    rawAiOutput = debugPrompt(prompt, imageSize)
                )
                updateImageGeneration(taskId) {
                    it.copy(phase = ImageGenerationPhase.GENERATING, error = null)
                }
                var attempt = 0
                var retry = true
                while (retry && attempt < 3) {
                    attempt++
                    retry = false
                    val currentSeed = if (attempt == 1) seed else novelAiImageService.newSeed()
                    try {
                        novelAiImageService.generate(token, prompt, currentSeed, imageSize).collect { event ->
                            when (event) {
                                is NovelAiImageEvent.Intermediate -> {
                                    updateImageGeneration(taskId) {
                                        it.copy(
                                            phase = ImageGenerationPhase.STREAMING,
                                            previewImage = event.image,
                                            progress = event.progress,
                                            error = null
                                        )
                                    }
                                }
                                is NovelAiImageEvent.Final -> {
                                    imageGenerationCompletedImageCheckpoints[taskId] = event.image
                                    saveNovelAiImageResult(taskId, anchorMessageId, prompt, imageSize, event.image)
                                }
                                is NovelAiImageEvent.Error -> {
                                    if (isRetryableError(event.message) && attempt < 3) {
                                        retry = true
                                        updateImageGeneration(taskId) {
                                            it.copy(phase = ImageGenerationPhase.GENERATING, error = null)
                                        }
                                    } else {
                                        updateImageGeneration(taskId) {
                                            it.copy(phase = ImageGenerationPhase.FAILED, error = event.message)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        if (isRetryableError(error.message) && attempt < 3) {
                            retry = true
                            updateImageGeneration(taskId) {
                                it.copy(phase = ImageGenerationPhase.GENERATING, error = null)
                            }
                        } else {
                            updateImageGeneration(taskId) {
                                it.copy(
                                    phase = ImageGenerationPhase.FAILED,
                                    error = "生图失败 (网络/连接错误, 第${attempt}次尝试): ${error.message ?: "未知错误"}"
                                )
                            }
                        }
                    }
                }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                updateImageGeneration(taskId) {
                    it.copy(
                        phase = ImageGenerationPhase.FAILED,
                        error = if (promptOverride == null) {
                            "生图失败 (提示设计阶段): ${error.message ?: "未知错误"}"
                        } else {
                            "生图失败 (复用图片元数据): ${error.message ?: "未知错误"}"
                        }
                    )
                }
            }
            }
        }
        imageGenerationJobs[taskId] = job
        job.invokeOnCompletion { error ->
            imageGenerationJobs.remove(taskId, job)
            if (error != null && error !is CancellationException) {
                updateImageGeneration(taskId) { state ->
                    if (state.isTerminal) state else state.copy(
                        phase = ImageGenerationPhase.FAILED,
                        error = "生图任务异常结束：${error.message ?: "未知错误"}"
                    )
                }
            }
        }
        job.start()
    }

    private suspend fun saveNovelAiImageResult(
        taskId: String,
        anchorMessageId: String,
        prompt: NovelAiPromptPlan,
        imageSize: NovelAiImageSize,
        image: ByteArray
    ) {
        updateImageGeneration(taskId) {
            it.copy(
                phase = ImageGenerationPhase.SAVING,
                previewImage = image,
                progress = 1f,
                error = null
            )
        }
        val path = withContext(Dispatchers.IO) {
            novelAiImageStorage.save(sessionId, image)
        }
        chatRepository.addMessageAfter(
            ChatMessage.create(
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                content = "",
                images = listOf(path),
                generatedImageMetadata = listOf(prompt.toGeneratedImageMetadata(path, imageSize)),
                generatedFromMessageId = anchorMessageId
            ),
            anchorMessageId
        )
        refreshMessages()
        removeImageGeneration(taskId)
    }

    private fun debugPrompt(prompt: NovelAiPromptPlan, imageSize: NovelAiImageSize): String = buildString {
        appendLine("Size: ${imageSize.label} (${imageSize.width}x${imageSize.height})")
        appendLine("Base: ${prompt.baseCaption}")
        prompt.characterCaptions.forEachIndexed { index, caption ->
            appendLine("Character ${index + 1}: ${caption.prompt}")
        }
    }

    private fun isRetryableError(message: String?): Boolean {
        val msg = message?.lowercase(Locale.ROOT) ?: return false
        return listOf("connection", "timeout", "closed", "eof", "reset", "refused", "unreachable", "network", "socket")
            .any { msg.contains(it) }
    }

    fun dismissNovelAiImageGeneration(taskId: String) {
        val current = imageGenerationState(taskId)?.takeIf { it.isTerminal } ?: return
        imageGenerationJobs.remove(taskId)?.cancel(CancellationException("用户关闭生图任务"))
        removeImageGeneration(current.taskId)
    }

    fun cancelNovelAiImageGeneration(taskId: String) {
        val current = imageGenerationState(taskId)?.takeIf { it.isCancellable } ?: return
        putImageGeneration(current.copy(phase = ImageGenerationPhase.CANCELLED, error = null))
        imageGenerationJobs[taskId]?.cancel(CancellationException("用户停止生图"))
    }

    fun dismissBatteryOptimizationHint() {
        _showBatteryOptimizationHint.value = false
        ChatBarApp.batteryOptimizationHintShown = true
    }

    private fun checkBatteryOptimization() {
        if (ChatBarApp.batteryOptimizationHintShown) return
        val ctx = ChatBarApp.instance
        val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(ctx.packageName)) {
            _showBatteryOptimizationHint.value = true
        }
    }

    private fun startStreamingForegroundWork() {
        AiBackgroundWorkManager.start(sessionId)
        checkBatteryOptimization()
    }

    private fun stopStreamingForegroundWork() {
        AiBackgroundWorkManager.finish()
    }

    private suspend fun effectiveContextWindowSize(): Int {
        val settings = settingsRepository.getAppSettings()
        val size = settings.defaultContextWindowSize.coerceAtLeast(1)
        _contextWindowSize.value = size
        return size
    }

    private fun effectiveContextWindowSize(session: ChatSession?, settings: AppSettings): Int {
        val size = settings.defaultContextWindowSize.coerceAtLeast(1)
        _contextWindowSize.value = size
        return size
    }

    private suspend fun buildWorldBookPrompt(
        card: CharacterCard,
        session: ChatSession,
        messages: List<ChatMessage>,
        previousTimed: Map<String, com.example.chatbar.data.local.entity.TimedEffectState>
    ): Triple<String?, Map<String, String>, Map<String, com.example.chatbar.data.local.entity.TimedEffectState>> {
        val engine = ChatBarApp.instance.worldBookEngine
        val worldBooks = mutableListOf<com.example.chatbar.data.local.entity.WorldBook>()

        card.characterBook?.let { worldBooks += it }
        card.boundWorldBookId?.let { id ->
            worldBookRepository.getById(id)?.let { worldBooks += it }
        }
        card.worldBookIds.forEach { id ->
            worldBookRepository.getById(id)?.let { worldBooks += it }
        }
        session.extraWorldBookIds.forEach { id ->
            worldBookRepository.getById(id)?.let { worldBooks += it }
        }
        val orderedWorldBooks = worldBooks.distinctBy { it.id }

        if (orderedWorldBooks.isEmpty()) return Triple(null, emptyMap(), emptyMap())

        val tokens = mutableSetOf(card.name.lowercase())
        card.characters.mapTo(tokens) { it.name.lowercase() }
        if (card.editMode == com.example.chatbar.data.local.entity.CharacterEditMode.FREEFORM) {
            Regex("【角色名称】\\s*\\n?\\s*(\\S+)").findAll(card.freeformCharacterText)
                .mapTo(tokens) { it.groupValues[1].lowercase().trim() }
        }

        val timedStates = previousTimed.mapValues { (_, v) ->
            WorldBookEngine.TimedState(v.entryId, v.stickyUntil, v.cooldownUntil)
        }
        val bookTimedStates = orderedWorldBooks.associate { it.id to timedStates }
        val activated = engine.evaluateAll(orderedWorldBooks, messages,
            messageCount = messages.size, characterTokens = tokens, timedStates = bookTimedStates)
        val before = activated.filter { it.entry.position == com.example.chatbar.data.local.entity.WorldBookPosition.BEFORE_CHAR }
        val after = activated.filter { it.entry.position == com.example.chatbar.data.local.entity.WorldBookPosition.AFTER_CHAR }
        val allEntries = before + after
        val outlets = engine.collectOutlets(activated)

        val prompt = if (allEntries.isEmpty()) null else {
            val globalPlayerName = ChatBarApp.instance.settingsRepository.getPlayerSetting()
                .playerName
                .takeIf { it.isNotBlank() }
            val playerName = session.playerName?.takeIf { it.isNotBlank() } ?: globalPlayerName
            engine.buildWorldBookPrompt(allEntries, card.name, playerName)
        }

        // Compute new timed states from activated entries
        val activatedIds = activated.map { it.entry.id }.toSet()
        val entryMap = orderedWorldBooks.flatMap { it.entries }.associateBy { it.id }
        val newTimed = engine.computeTimedStates(timedStates, activatedIds, entryMap, messages.size)
            .mapValues { (_, v) -> com.example.chatbar.data.local.entity.TimedEffectState(v.entryId, v.stickyUntil, v.cooldownUntil) }

        return Triple(prompt, outlets, newTimed)
    }

    /**
     * 发送文本及图片
     */
    fun sendMessage(content: String, imagePaths: List<String> = emptyList()): Boolean {
        if (_isArchived.value || !_isModelUsable.value || _isResponding.value) return false
        if (blockChatDuringMemoryBackfill()) return false
        val isBlank = content.isBlank() && imagePaths.isEmpty()
        val effectiveContent = if (isBlank) "continue" else content
        if (!isBlank && _draftInput.value.isNotEmpty()) updateDraftInput("")
        sendMessageInternal(content = effectiveContent, imagePaths = imagePaths, persistUserMessage = !isBlank)
        return true
    }

    fun cancelResponseGeneration() {
        if (!_isResponding.value) return
        activeFormatRepairJob?.let { repairJob ->
            formatRepairStopRequested = true
            repairJob.cancel(CancellationException("用户停止格式修复"))
            return
        }
        ChatBarApp.instance.streamingStopRequested.value = true
        responseJob?.cancel(CancellationException("用户停止生成"))
    }

    fun repairMessageFormat(messageId: String) {
        if (_isResponding.value) return
        _isResponding.value = true
        val job = viewModelScope.launch {
            try {
                val message = chatRepository.getMessage(messageId, sessionId) ?: return@launch
                if (message.role != MessageRole.ASSISTANT) return@launch
                val repaired = performMessageFormatRepair(message, automatic = false)
                if (repaired.displayContent != message.displayContent) {
                    refreshMemoryAfterMessageEdit(repaired)
                }
            } finally {
                _messageFormatRepairState.value = null
                _isResponding.value = false
            }
        }
        manualFormatRepairJob = job
        job.invokeOnCompletion {
            if (manualFormatRepairJob == job) manualFormatRepairJob = null
        }
    }

    fun restoreMessageFormatOriginal(messageId: String) {
        if (_isResponding.value) return
        viewModelScope.launch {
            val message = chatRepository.getMessage(messageId, sessionId) ?: return@launch
            val restored = MessageFormatRepairPolicy.restoreOriginal(message) ?: return@launch
            chatRepository.updateMessage(restored)
            replaceMessagesFromRepository()
            refreshMemoryAfterMessageEdit(restored)
        }
    }

    private suspend fun performMessageFormatRepair(
        message: ChatMessage,
        automatic: Boolean
    ): ChatMessage {
        val appSettings = settingsRepository.getAppSettings()
        val currentSession = chatRepository.getSession(sessionId) ?: return message
        val renderContext = placeholderRenderContext(currentSession)
        val formatCardId = currentSession.formatCardId ?: appSettings.defaultFormatCardId
        val formatCard = formatCardId
            ?.let { formatCardRepository.getById(it) }
            ?.content
            ?.let { content ->
                renderContext?.let { context ->
                    PlaceholderRenderer.render(content, context.playerName, context.botName)
                } ?: content
            }
            ?.takeIf(String::isNotBlank)
        val segmentedFormat = if (appSettings.assistantSegmentedBubblesEnabled) {
            val card = _characterCard.value?.takeIf { it.id == currentSession.characterCardId }
                ?: characterRepository.getById(currentSession.characterCardId)
            PromptTemplates.roleplaySpeakerFormatSystemPrompt(
                card?.characters?.map { it.name }.orEmpty()
            )
        } else {
            null
        }
        if (formatCard == null && segmentedFormat == null) {
            if (!automatic) _messageFormatRepairEvents.tryEmit("当前无可检查规则")
            return message
        }

        val model = modelResolver.resolveFormatRepairModel(
            appSettings.formatRepairModelId,
            appSettings
        )
        if (model == null || model.apiKey.isBlank()) {
            return persistFormatRepairFailure(message, "格式修复模型及默认对话模型未配置或已失效")
        }

        val originalContent = message.displayContent
        var result = message
        var repairedPrefix = ""
        var sawDone = false
        formatRepairStopRequested = false
        _messageFormatRepairState.value = MessageFormatRepairState(
            messageId = message.id,
            previewContent = originalContent
        )

        coroutineScope {
            val repairJob = launch {
                try {
                    messageFormatRepairService.streamRepair(
                        originalContent = originalContent,
                        formatCard = formatCard,
                        segmentedBubbleFormat = segmentedFormat,
                        modelConfig = model
                    ).collect { event ->
                        when (event) {
                            is StreamEvent.Delta -> {
                                repairedPrefix += event.text
                                _messageFormatRepairState.value = MessageFormatRepairState(
                                    messageId = message.id,
                                    previewContent = MessageFormatRepairPolicy.progressiveOverlay(
                                        original = originalContent,
                                        repairedPrefix = repairedPrefix
                                    )
                                )
                            }
                            is StreamEvent.Error -> throw IllegalStateException(event.message)
                            StreamEvent.Done -> sawDone = true
                            is StreamEvent.ReasoningDelta,
                            is StreamEvent.Usage -> Unit
                        }
                    }
                    if (!sawDone || repairedPrefix.isBlank()) {
                        throw IllegalStateException("格式修复模型返回空结果")
                    }
                    result = when {
                        repairedPrefix == originalContent -> {
                            val unchanged = message.copy(
                                formatRepairNotice = null,
                                updatedAt = System.currentTimeMillis()
                            )
                            if (unchanged != message) persistFormatRepairMessage(unchanged)
                            _messageFormatRepairEvents.tryEmit("格式无需修复")
                            unchanged
                        }
                        else -> {
                            val notice = if (
                                MessageFormatRepairPolicy.isLengthAnomalous(
                                    original = originalContent,
                                    repaired = repairedPrefix
                                )
                            ) {
                                MessageFormatRepairNotice(
                                    kind = MessageFormatRepairNoticeKind.LENGTH_ANOMALY,
                                    targetContent = repairedPrefix,
                                    originalContent = originalContent
                                )
                            } else {
                                null
                            }
                            persistFormatRepairMessage(
                                MessageFormatRepairPolicy.replaceCurrentDisplayContent(
                                    message = message,
                                    replacement = repairedPrefix,
                                    notice = notice
                                )
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    if (!formatRepairStopRequested) throw cancelled
                    val mixedContent = MessageFormatRepairPolicy.progressiveOverlay(
                        original = originalContent,
                        repairedPrefix = repairedPrefix
                    )
                    result = persistFormatRepairMessage(
                        MessageFormatRepairPolicy.replaceCurrentDisplayContent(
                            message = message,
                            replacement = mixedContent,
                            notice = MessageFormatRepairNotice(
                                kind = MessageFormatRepairNoticeKind.STOPPED,
                                targetContent = mixedContent,
                                originalContent = originalContent
                            )
                        )
                    )
                } catch (error: Exception) {
                    result = persistFormatRepairFailure(
                        message = message,
                        error = error.message ?: "格式修复失败"
                    )
                }
            }
            activeFormatRepairJob = repairJob
            try {
                repairJob.join()
            } finally {
                if (activeFormatRepairJob == repairJob) activeFormatRepairJob = null
                formatRepairStopRequested = false
            }
        }
        _messageFormatRepairState.value = null
        return result
    }

    private suspend fun persistFormatRepairFailure(
        message: ChatMessage,
        error: String
    ): ChatMessage = persistFormatRepairMessage(
        message.copy(
            formatRepairNotice = MessageFormatRepairNotice(
                kind = MessageFormatRepairNoticeKind.ERROR,
                targetContent = message.displayContent,
                errorMessage = error
            ),
            updatedAt = System.currentTimeMillis()
        )
    )

    private suspend fun persistFormatRepairMessage(message: ChatMessage): ChatMessage {
        chatRepository.updateMessage(message)
        replaceMessagesFromRepository()
        return message
    }

    fun updateDraftInput(text: String) {
        if (_draftInput.value == text) return
        draftTouched = true
        _draftInput.value = text
        val sequence = ++draftSaveSequence
        ChatBarApp.instance.applicationScope.launch {
            try {
                if (sequence == draftSaveSequence) {
                    chatRepository.updateSessionDraft(sessionId, text)
                }
            } catch (_: Exception) {}
        }
    }

    private fun sendMessageInternal(
        content: String,
        imagePaths: List<String> = emptyList(),
        persistUserMessage: Boolean,
        alternativeTargetMessageId: String? = null,
        respondingAlreadyStarted: Boolean = false
    ) {
        if (_isResponding.value && !respondingAlreadyStarted) return

        val job = ChatBarApp.instance.applicationScope.launch {
            if (!respondingAlreadyStarted) {
                _isResponding.value = true
            }

            // 1. 获取全局与会话设定
            val cachedSession = _session.value ?: return@launch
            val currentSession = chatRepository.getSession(sessionId) ?: cachedSession
            _session.value = currentSession
            if (currentSession.longTermMemoryEnabled &&
                longTermMemoryService.currentState(sessionId)?.backfill?.status == MemoryBackfillStatus.RUNNING
            ) {
                _memoryCompressionEvents.emit("长期记忆正在补录；请先暂停补录再继续聊天")
                _isResponding.value = false
                return@launch
            }
            val charCard = characterRepository.getById(currentSession.characterCardId)
            if (charCard == null) {
                _characterCard.value = null
                _isArchived.value = true
                _isResponding.value = false
                return@launch
            }
            _characterCard.value = charCard
            val appSettings = settingsRepository.getAppSettings()
            val playerSettingObj = settingsRepository.getPlayerSetting()
            val activePlayerSetting = currentSession.playerSetting?.takeIf { it.isNotBlank() }
                ?: playerSettingObj.globalPersona
            val activePlayerName = currentSession.playerName?.takeIf { it.isNotBlank() }
                ?: playerSettingObj.playerName
            val activePlayerNameOrNull = activePlayerName.takeIf { it.isNotBlank() }
            val renderSessionText: (String) -> String = { text ->
                PlaceholderRenderer.render(text, activePlayerNameOrNull, charCard.name)
            }

            // 确定要使用的 LLM 模型
            val configurationStatus = modelResolver.status(appSettings)
            val modelConfig = modelResolver.resolveChatModel(currentSession.modelId, appSettings)
                ?: run {
                    addSystemMessage("错误：${configurationStatus.errors.firstOrNull() ?: "未找到可用模型配置"}")
                    _isResponding.value = false
                    return@launch
                }
            if (modelConfig.apiKey.isBlank()) {
                addSystemMessage("错误：${configurationStatus.errors.firstOrNull() ?: "API Key 未配置"}")
                _isResponding.value = false
                return@launch
            }

            // 确定是否要用 Embedding 做 RAG 检索
            startStreamingForegroundWork()
            val generationJob = coroutineContext[Job]
            val protectionLossHandle = AiBackgroundWorkManager.observeProtectionLoss { reason ->
                generationJob?.cancel(BackgroundGenerationProtectionCancellationException(reason))
            }
            try {
            AiBackgroundWorkManager.awaitForegroundProtection()
            val embeddingConfig = modelResolver.embeddingModel(appSettings)

            // 2. 多模态图片处理 (如果是纯文本模型但附带了图片，则先调用视觉模型生成图片描述)
            var finalUserContent = content
            val userMsgImages = mutableListOf<String>()

            if (imagePaths.isNotEmpty()) {
                userMsgImages.addAll(imagePaths)
                
                if (!modelConfig.isMultimodal) {
                    try {
                        val imageUnderstanding = imageUnderstandingService.prepare(
                            imageBase64s = listOf(encodeImageToBase64(imagePaths.first())),
                            generationModel = modelConfig,
                            requireUnderstanding = false,
                            onStatus = { addSystemMessage(it) }
                        )
                        if (imageUnderstanding.descriptions.isNotEmpty()) {
                            finalUserContent += "\n[用户附图描述: ${imageUnderstanding.descriptions.joinToString("\n")}]"
                        } else if (!imageUnderstanding.unavailableReason.isNullOrBlank()) {
                            addSystemMessage("${imageUnderstanding.unavailableReason}，将作为无图消息发送。")
                        }
                    } catch (e: Exception) {
                        addSystemMessage("图片解析失败: ${e.message}。将作为无图消息发送。")
                    }
                }
            }

            // 3. 将用户消息存入仓库并更新 UI
            val userMsg = ChatMessage.create(
                sessionId = sessionId,
                role = MessageRole.USER,
                content = finalUserContent,
                images = userMsgImages
            )
            if (persistUserMessage) {
                chatRepository.addMessage(userMsg)
                refreshMessages()
            }

            val allMsgs = chatRepository.getMessages(sessionId)
                .filterNot { it.id == alternativeTargetMessageId }
            ragMemoryMutationMutex.withLock {
                ChatBarApp.instance.ragRepository.pruneChatMemory(
                    sessionId = sessionId,
                    liveMessageIds = allMsgs.mapTo(mutableSetOf()) { it.id }
                )
            }
            val effectiveContextWindowSize = effectiveContextWindowSize(currentSession, appSettings)
            var contextMsgs = TimelineArchiveBoundaryPolicy.expandDirectContextToWholeTurns(
                allMsgs,
                contextWindowManager.getRecentMessages(allMsgs, effectiveContextWindowSize)
            )
            if (currentSession.longTermMemoryEnabled) {
                val boundaryView = runCatching {
                    longTermMemoryService.promptView(sessionId)
                }.getOrNull()
                contextMsgs = TimelineArchiveBoundaryPolicy.expandDirectContextAfterArchive(
                    allMessages = allMsgs,
                    directContext = contextMsgs,
                    pendingSourceTurnIds = boundaryView?.pendingSourceTurnIds.orEmpty()
                )
            }
            val renderedContextMsgs = contextMsgs.map {
                PlaceholderRenderer.renderMessage(it, activePlayerNameOrNull, charCard.name)
            }
            val currentRetrievalUserContent = when {
                persistUserMessage -> finalUserContent
                alternativeTargetMessageId != null -> contextMsgs.lastOrNull {
                    it.role == MessageRole.USER
                }?.displayContent ?: content
                else -> content
            }.let(renderSessionText)
            val retrievalCredentialMsgs = buildRagRetrievalCredentialMessages(
                contextMsgs = renderedContextMsgs,
                currentUserMessageId = userMsg.id,
                currentUserContent = currentRetrievalUserContent
            )
            val activeContextMessageIds = contextMsgs.map { it.id }.toSet()
            val indexedDocumentCount = maxOf(
                charCard.ragIndexDone,
                charCard.customDocuments.count { it.ragChunkCount > 0 }
            )
            val ragSourcePlan = RagSourcePlan.create(
                documentCount = charCard.customDocuments.size,
                indexedDocumentCount = indexedDocumentCount,
                messageGroupCount = contextWindowManager.messageGroupCount(allMsgs),
                contextWindowSize = effectiveContextWindowSize
            )
            val headPreparation = if (currentSession.longTermMemoryEnabled && persistUserMessage) {
                async {
                    longTermMemoryService.prepareHeadBeforePrompt(sessionId, modelConfig)
                }
            } else {
                null
            }

            try {
                // 4. RAG 检索
                val ragDebugLogs = mutableListOf<String>()
                val ragResults = if (appSettings.ragInjectionMode.equals("OFF", ignoreCase = true)) {
                    ragDebugLogs.add("RAG 检索跳过：RAG 注入强度为关闭。")
                    emptyList()
                } else if (!ragSourcePlan.shouldRetrieve) {
                    ragDebugLogs.add("RAG 检索跳过：无已索引文档，且没有消息滑出保留上下文窗口。")
                    emptyList()
                } else if (embeddingConfig != null) {
                    if (ragSourcePlan.includeDocuments && charCard.ragIndexStatus != "COMPLETE") {
                        ragDebugLogs.add("角色卡 RAG 索引未完成：${charCard.ragIndexStatus} ${charCard.ragIndexDone}/${charCard.ragIndexTotal}。${charCard.ragIndexMessage.orEmpty()}")
                    }
                    ragDebugLogs.add("开始 RAG 检索。来源: documents=${ragSourcePlan.includeDocuments}, memory=${ragSourcePlan.includeMemory}; Embedding配置: [${embeddingConfig.displayName}], 目标模型: ${embeddingConfig.modelName}")
                    try {
                        val retrievalChunks = ChatBarApp.instance.ragRepository.getChunksForRetrieval(
                            characterId = charCard.id.takeIf { ragSourcePlan.includeDocuments },
                            sessionId = sessionId.takeIf { ragSourcePlan.includeMemory }
                        )
                        val currentEmbeddingKey = ragManager.embeddingKey(embeddingConfig)
                        val allDocChunks = retrievalChunks.filter { it.sourceType == ChunkSourceType.DOCUMENT }
                        val legacyDocChunks = allDocChunks.filter { it.metadata["embeddingKey"].isNullOrBlank() }
                        val mismatchedDocChunks = allDocChunks.filter {
                            val key = it.metadata["embeddingKey"]
                            !key.isNullOrBlank() && key != currentEmbeddingKey
                        }
                        val docChunks = allDocChunks.filter { it.metadata["embeddingKey"] == currentEmbeddingKey }
                        val allMemChunks = retrievalChunks.filter { it.sourceType == ChunkSourceType.CHAT_MEMORY }
                        val staleAutomaticChunks = allMemChunks.filter(ChatMemoryIndexPolicy::needsAutomaticRebuild)
                        val memChunks = allMemChunks.filter { chunk ->
                            !ChatMemoryIndexPolicy.needsAutomaticRebuild(chunk) &&
                                chunk.metadata["indexMode"] != "memory_node" &&
                                chunk.messageIds().none { it in activeContextMessageIds }
                        }
                        val searchableMemChunks = memChunks
                        val filteredMemCount = allMemChunks.size - staleAutomaticChunks.size - memChunks.size
                        ragDebugLogs.add("RAG split retrieval: document candidates=${docChunks.size}; ignored legacy document chunks=${legacyDocChunks.size}; ignored embedding-mismatch document chunks=${mismatchedDocChunks.size}; chat_memory candidates=${allMemChunks.size}; active context messages=${activeContextMessageIds.size}; eligible chat_memory after context filter=${memChunks.size}.")
                        if (legacyDocChunks.isNotEmpty() || mismatchedDocChunks.isNotEmpty()) {
                            ragDebugLogs.add("Document RAG index uses a different or unknown embedding model. Rebuild the character card RAG index before judging recall quality.")
                        }
                        if (filteredMemCount > 0) {
                            ragDebugLogs.add("Filtered $filteredMemCount chat_memory chunks because they overlap messages already sent in the normal context window.")
                        }
                        if (staleAutomaticChunks.isNotEmpty()) {
                            ragDebugLogs.add("Ignored ${staleAutomaticChunks.size} stale automatic memory chunks. Rebuild them from the RAG library page.")
                        }
                        
                        ragDebugLogs.add("数据库向量块查询完成。知识库文档块数: ${docChunks.size}, 对话记忆块数: ${memChunks.size} (已排除角色设定块)")
                        
                        if (docChunks.isEmpty() && memChunks.isEmpty()) {
                            ragDebugLogs.add("警告：数据库中未检索到任何知识库文档或对话记忆块。")
                            emptyList()
                        } else {
                            val retrievalModelConfig = modelResolver.retrievalModel(appSettings) ?: modelConfig
                            ragDebugLogs.add(
                                "Retrieval planner model: ${retrievalModelConfig.displayName}" +
                                    if (retrievalModelConfig.id == modelConfig.id) " (fallback to chat model)" else ""
                            )
                            val retrievalPlanResult = retrievalPlanner.plan(
                                currentUserContent = currentRetrievalUserContent,
                                contextMessages = retrievalCredentialMsgs,
                                characterName = charCard.name,
                                modelConfig = retrievalModelConfig
                            )
                            val retrievalPlan = retrievalPlanResult.plan
                            if (retrievalPlan != null) {
                                ragDebugLogs.add(retrievalPlan.toDebugLog())
                            } else {
                                ragDebugLogs.add(
                                    buildString {
                                        append("Retrieval planner failed. Fallback to local mixed retrieval query.")
                                        retrievalPlanResult.failureReason?.let { append(" reason=$it") }
                                    }
                                )
                            }
                            ragDebugLogs.add(
                                buildString {
                                    append("Raw planner response preview (${retrievalPlanResult.rawResponsePreview.length} chars):\n")
                                    append(retrievalPlanResult.rawResponsePreview.ifBlank { "<empty>" })
                                }
                            )

                            if (retrievalPlan != null &&
                                !retrievalPlan.shouldRecall
                            ) {
                                ragDebugLogs.add("Retrieval planner skipped RAG: no topic/query/entity returned.")
                                emptyList()
                            } else {
                            val ragQuery = retrievalPlan?.toRagQuery(
                                currentRetrievalUserContent,
                                retrievalCredentialMsgs
                            ) ?: buildRagQuery(currentRetrievalUserContent, retrievalCredentialMsgs)
                            val queryEmbedding = ChatBarApp.instance.embeddingService.getEmbedding(ragQuery, embeddingConfig)
                            ragDebugLogs.add("RAG query text (${ragQuery.length} chars):\n${ragQuery.take(1200)}")
                            ragDebugLogs.add("RAG retrieval credentials: last_assistant=${retrievalCredentialMsgs.size}, current_user=1.")
                            ragDebugLogs.add("查询文本 Embedding 计算完成。维度: ${queryEmbedding.size}")
                            
                            val rankedDocChunks = rankChunksByMultiRoute(
                                chunks = docChunks,
                                queryEmbedding = queryEmbedding,
                                ragQuery = ragQuery,
                                routeLimit = routeCandidateLimit(appSettings.docRagTopK, docChunks.size)
                            )

                            val rankedMemChunks = rankChunksByMultiRoute(
                                chunks = searchableMemChunks,
                                queryEmbedding = queryEmbedding,
                                ragQuery = ragQuery,
                                routeLimit = routeCandidateLimit(appSettings.memoryRagTopK, searchableMemChunks.size)
                            )

                            val mismatchedSourceLabelCount = docChunks.count { it.hasMismatchedSourceLabel() }
                            if (mismatchedSourceLabelCount > 0) {
                                ragDebugLogs.add("Document chunk source integrity warning: mismatched content source vs metadata source=$mismatchedSourceLabelCount/${docChunks.size}. Rebuild index if this stays non-zero.")
                            }
                            
                            val topDocCandidates = rankedDocChunks.take(routeCandidateLimit(appSettings.docRagTopK, rankedDocChunks.size))
                            val eligibleDocCandidates = topDocCandidates.filter {
                                it.vectorScore >= appSettings.docRagSimilarityThreshold ||
                                    it.lexicalScore >= DOCUMENT_LEXICAL_ACCEPT_THRESHOLD
                            }
                            val topDocChunks = eligibleDocCandidates.withSourceDiversity(appSettings.docRagTopK)
                            if (topDocCandidates.isNotEmpty() && topDocChunks.isEmpty()) {
                                ragDebugLogs.add(
                                    "Document RAG topK candidates all below threshold ${appSettings.docRagSimilarityThreshold}. Best scores: " +
                                        topDocCandidates.take(5).joinToString(", ") { "%.4f".format(it.combinedScore) }
                                )
                            }
                            val topMemChunks = rankedMemChunks.filter {
                                it.vectorScore >= appSettings.memoryRagSimilarityThreshold ||
                                    it.lexicalScore >= MEMORY_LEXICAL_ACCEPT_THRESHOLD
                            }.take(appSettings.memoryRagTopK)
                            if (rankedDocChunks.isNotEmpty()) {
                                ragDebugLogs.add(
                                    "Document multi-route top scores: " +
                                        rankedDocChunks.take(10).joinToString("\n") { rank ->
                                            val chunk = rank.chunk
                                            val metaSource = chunk.metadata["sourceLabel"]
                                                ?: chunk.metadata["fileName"]
                                                ?: chunk.id
                                            val contentSource = chunk.contentSourceLabel() ?: "no-content-source"
                                            "rrf=${"%.4f".format(rank.rrfScore)} combined=${"%.4f".format(rank.combinedScore)} vector=${"%.4f".format(rank.vectorScore)}@${rank.vectorRank ?: "-"} lexical=${"%.4f".format(rank.lexicalScore)}@${rank.lexicalRank ?: "-"} | meta=$metaSource | content=$contentSource"
                                        }
                                )
                                val topSources = rankedDocChunks.take(20)
                                    .map { it.chunk.metadata["sourceLabel"] ?: it.chunk.metadata["fileName"] ?: "unknown" }
                                    .groupingBy { it.substringBefore(" > ") }
                                    .eachCount()
                                ragDebugLogs.add("Document top20 source distribution: $topSources")
                                val finalSources = topDocChunks
                                    .map { it.chunk.sourceDiversityKey() }
                                    .groupingBy { it }
                                    .eachCount()
                                ragDebugLogs.add("Document final source distribution after local rerank: $finalSources")
                            }
                            if (rankedMemChunks.isNotEmpty()) {
                                ragDebugLogs.add(
                                    "Chat memory multi-route top scores: " +
                                        rankedMemChunks.take(10).joinToString("\n") { rank ->
                                            "rrf=${"%.4f".format(rank.rrfScore)} combined=${"%.4f".format(rank.combinedScore)} vector=${"%.4f".format(rank.vectorScore)}@${rank.vectorRank ?: "-"} lexical=${"%.4f".format(rank.lexicalScore)}@${rank.lexicalRank ?: "-"} | messageIds=${rank.chunk.messageIds()}"
                                        }
                                )
                            }
                            
                            ragDebugLogs.add("知识库文档召回完成。阈值: ${appSettings.docRagSimilarityThreshold}, Top-K: ${appSettings.docRagTopK}。召回块数: ${topDocChunks.size}")
                            ragDebugLogs.add("对话记忆召回完成。阈值: ${appSettings.memoryRagSimilarityThreshold}, Top-K: ${appSettings.memoryRagTopK}。召回块数: ${topMemChunks.size}")
                            
                            val finalCards = (topDocChunks.map { it.chunk } +
                                topMemChunks.map { it.chunk })
                                .distinctBy { it.id }
                                .map { RetrievedKnowledgeCard.fromChunk(it) }
                            if (finalCards.isNotEmpty()) {
                                ragDebugLogs.add("--- 最终召回的文本块详情 ---")
                                finalCards.forEachIndexed { i, card ->
                                    ragDebugLogs.add("[召回 ${i + 1} | 类型: ${card.type} | 来源: ${card.sourceLabel} | ID: ${card.sourceId}]\n${card.content}")
                                }
                            }
                            finalCards
                            }
                        }
                    } catch (e: Exception) {
                        ragDebugLogs.add("错误：RAG 检索过程中抛出异常: ${e.message}")
                        emptyList()
                    }
                } else {
                    ragDebugLogs.add("RAG 检索未启用：未配置全局 Embedding 模型。")
                    emptyList()
                }

                // 5. 组装 System Prompt
                val activeFormatId = currentSession.formatCardId
                    ?: appSettings.defaultFormatCardId
                val activeFormatCard = activeFormatId?.let { formatCardRepository.getById(it) }

                val (wbPrompt, wbOutlets, wbTimed) = buildWorldBookPrompt(charCard, currentSession, allMsgs, currentSession.timedWorldInfo)
                if (wbTimed != currentSession.timedWorldInfo) {
                    val updatedSession = currentSession.copy(timedWorldInfo = wbTimed)
                    chatRepository.updateSession(updatedSession)
                    _session.value = updatedSession
                }

                // 当前输入不属于历史；完整上一轮作为末尾热区，其余消息留在稳定缓存之后。
                val regenTargetUserMsg = if (alternativeTargetMessageId != null) {
                    contextMsgs.lastOrNull { it.role == MessageRole.USER }
                } else null
                val latestMessageId = when {
                    persistUserMessage -> userMsg.id
                    regenTargetUserMsg != null -> regenTargetUserMsg.id
                    else -> null
                }
                val promptMessageGroups = contextWindowManager.getPromptMessageGroups(
                    contextMessages = contextMsgs,
                    latestMessageId = latestMessageId
                )
                fun assemblePromptLayers(memory: MemoryPromptView?) =
                    promptAssembler.assembleCachePromptLayers(
                        characterCard = charCard,
                        playerSetting = activePlayerSetting,
                        playerName = activePlayerName.takeIf { it.isNotBlank() },
                        supplementarySetting = currentSession.supplementarySetting?.takeIf { it.isNotBlank() },
                        formatCard = activeFormatCard,
                        ragResults = ragResults,
                        ragInjectionMode = appSettings.ragInjectionMode,
                        replyLength = currentSession.replyLength?.takeIf { it.isNotBlank() },
                        replyLanguage = currentSession.replyLanguage?.takeIf { it.isNotBlank() },
                        memoryArchive = memory?.archive,
                        memoryHeadAndTimeline = memory?.headAndTimeline,
                        worldBookPrompt = wbPrompt,
                        worldBookOutlets = wbOutlets,
                        hasHistoryMessages = promptMessageGroups.historyMessages.isNotEmpty(),
                        hasPreviousTurn = promptMessageGroups.previousTurnMessages.isNotEmpty()
                    )
                headPreparation?.await()
                val memoryView = if (currentSession.longTermMemoryEnabled) {
                    runCatching {
                        longTermMemoryService.promptView(sessionId)
                    }.getOrNull()
                } else {
                    null
                }
                val promptLayers = assemblePromptLayers(memoryView)
                fun timelinePrefix(message: ChatMessage?): String {
                    val displayT = message?.sourceTurnId
                        ?.let { memoryView?.displayTBySourceTurnId?.get(it) }
                    return displayT?.let { "[T$it]\n" }.orEmpty()
                }
                val promptSystemDebug = listOf(
                    promptLayers.stableSystemPrompt,
                    promptLayers.dynamicSystemPrompt,
                    promptLayers.tailSystemPrompt
                ).filter(String::isNotBlank).joinToString("\n\n")
                val promptCacheKey = promptLayers.stableSystemPrompt
                    .takeIf { promptLayers.stablePrefixCacheable && it.isNotBlank() }
                    ?.let(PromptCacheKeyFactory::cacheKey)

                val apiMessages = mutableListOf<ChatApiMessage>()

                suspend fun addContextMessage(
                    msg: ChatMessage,
                    stripAssistantStatus: Boolean = false
                ) {
                    val role = msg.role.name.lowercase()
                    val sourceText = if (stripAssistantStatus && msg.role == MessageRole.ASSISTANT) {
                        stripRoleplayStatusSegments(msg.displayContent)
                    } else {
                        msg.displayContent
                    }
                    val text = timelinePrefix(msg) + renderSessionText(sourceText)
                    if (msg.images.isNotEmpty() &&
                        modelConfig.isMultimodal &&
                        msg.role == MessageRole.USER
                    ) {
                        try {
                            val base64 = encodeImageToBase64(msg.images.first())
                            apiMessages.add(
                                ChatApiMessage.withImage(
                                    role = role,
                                    text = text,
                                    imageBase64 = base64
                                )
                            )
                        } catch (e: Exception) {
                            if (text.isNotBlank()) {
                                apiMessages.add(ChatApiMessage.text(role, text))
                            }
                        }
                    } else if (text.isNotBlank()) {
                        apiMessages.add(ChatApiMessage.text(role, text))
                    }
                }

                // 1. 固定设定在最前，再放较早聊天记录。
                promptLayers.stableSystemPrompt.takeIf(String::isNotBlank)?.let { stablePrompt ->
                    apiMessages.add(ChatApiMessage.text("system", stablePrompt))
                }
                for (msg in promptMessageGroups.historyMessages) {
                    addContextMessage(
                        msg = msg,
                        stripAssistantStatus = appSettings.excludeAssistantStatusFromHistory
                    )
                }

                // 2. 长期记忆、RAG、世界书等动态资料置于较早历史与完整上一轮之间。
                promptLayers.dynamicSystemPrompt.takeIf(String::isNotBlank)?.let { dynamicPrompt ->
                    apiMessages.add(ChatApiMessage.text("system", dynamicPrompt))
                }
                promptLayers.tailSystemPrompt.takeIf(String::isNotBlank)?.let { tailPrompt ->
                    apiMessages.add(ChatApiMessage.text("system", tailPrompt))
                }
                val replyLength = currentSession.replyLength
                    ?.takeIf { it.isNotBlank() }
                    ?: "300字短篇"
                apiMessages.add(
                    ChatApiMessage.text(
                        role = "system",
                        content = PromptTemplates.replyTailSystemPrompt(
                            replyLength = replyLength,
                            roleplaySpeakerFormatEnabled = _assistantSegmentedBubblesEnabled.value,
                            characterNames = charCard.characters.map { it.name }
                        )
                    )
                )
                for (msg in promptMessageGroups.previousTurnMessages) {
                    addContextMessage(
                        msg = msg,
                        stripAssistantStatus = appSettings.excludeAssistantStatusFromHistory
                    )
                }

                // 3. 本次用户输入（始终放在最底部）
                val currentUserContent: String?
                val currentUserImages: List<String>
                val shouldAddUserPrompt: Boolean = when {
                    persistUserMessage -> {
                        val persistedUser = allMsgs.firstOrNull { it.id == userMsg.id }
                        currentUserContent = timelinePrefix(persistedUser) + renderSessionText(finalUserContent)
                        currentUserImages = userMsgImages
                        true
                    }
                    regenTargetUserMsg != null -> {
                        currentUserContent = timelinePrefix(regenTargetUserMsg) +
                            renderSessionText(regenTargetUserMsg.displayContent)
                        currentUserImages = regenTargetUserMsg.images
                        true
                    }
                    content.isNotBlank() -> {
                        val latestStoryMessage = allMsgs.lastOrNull { it.role != MessageRole.SYSTEM }
                        currentUserContent = timelinePrefix(latestStoryMessage) + renderSessionText(content)
                        currentUserImages = emptyList()
                        true
                    }
                    else -> {
                        currentUserContent = null
                        currentUserImages = emptyList()
                        false
                    }
                }
                if (shouldAddUserPrompt && currentUserContent != null) {
                    val userPromptText = currentUserContent
                    if (currentUserImages.isNotEmpty() && modelConfig.isMultimodal) {
                        try {
                            val base64 = encodeImageToBase64(currentUserImages.first())
                            apiMessages.add(
                                ChatApiMessage.withImage(
                                    role = "user",
                                    text = userPromptText,
                                    imageBase64 = base64
                                )
                            )
                        } catch (e: Exception) {
                            if (userPromptText.isNotBlank()) {
                                apiMessages.add(ChatApiMessage.text("user", userPromptText))
                            }
                        }
                    } else if (userPromptText.isNotBlank()) {
                        apiMessages.add(ChatApiMessage.text("user", userPromptText))
                    }
                }

                // 8. 开启流式响应
                var accumulatedText = ""
                var accumulatedReasoning = ""
                val assistantMsgId = alternativeTargetMessageId ?: java.util.UUID.randomUUID().toString()
                val streamStartedAt = System.currentTimeMillis()
                val regenerationTarget = alternativeTargetMessageId
                    ?.let { chatRepository.getMessage(it, sessionId) }
                val streamCreatedAt = regenerationTarget?.createdAt ?: streamStartedAt
                val streamOrderKey = regenerationTarget?.orderKey ?: streamCreatedAt.toMessageOrderKey()

                val ctx = ChatBarApp.instance
                _streamingMessage.value = ChatMessage(
                    id = assistantMsgId,
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT,
                    content = "...",
                    createdAt = streamCreatedAt,
                    updatedAt = streamStartedAt,
                    orderKey = streamOrderKey
                )
                StreamingNotificationManager.update(ctx, "正在连接流式响应...", sessionId)

                streamingChatService.streamChat(
                    sessionId = sessionId,
                    messages = apiMessages,
                    modelConfig = modelConfig,
                    systemPrompt = promptSystemDebug,
                    ragChunks = ragDebugLogs,
                    promptCacheKey = promptCacheKey
                ).collect { event ->
                    if (ChatBarApp.instance.streamingStopRequested.value) {
                        throw CancellationException("用户停止生成")
                    }
                    when (event) {
                        is StreamEvent.Usage -> Unit
                        is StreamEvent.ReasoningDelta -> {
                            accumulatedReasoning += event.text
                            _streamingMessage.value = ChatMessage(
                                id = assistantMsgId,
                                sessionId = sessionId,
                                role = MessageRole.ASSISTANT,
                                content = renderSessionText(accumulatedText),
                                reasoningContent = accumulatedReasoning.takeIf { it.isNotEmpty() }
                                    ?.let(renderSessionText),
                                createdAt = streamCreatedAt,
                                updatedAt = System.currentTimeMillis(),
                                orderKey = streamOrderKey
                            )
                        }
                        is StreamEvent.Delta -> {
                            accumulatedText += event.text
                            val renderedAccumulatedText = renderSessionText(accumulatedText)
                            _streamingMessage.value = ChatMessage(
                                id = assistantMsgId,
                                sessionId = sessionId,
                                role = MessageRole.ASSISTANT,
                                content = renderedAccumulatedText,
                                reasoningContent = accumulatedReasoning.takeIf { it.isNotEmpty() }
                                    ?.let(renderSessionText),
                                createdAt = streamCreatedAt,
                                updatedAt = System.currentTimeMillis(),
                                orderKey = streamOrderKey
                            )
                            StreamingNotificationManager.update(ctx, renderedAccumulatedText, sessionId)
                        }
                        is StreamEvent.Error -> {
                            throw Exception(event.message)
                        }
                        is StreamEvent.Done -> {
                            ChatBarApp.instance.streamingStopRequested.value = false
                            // 流生成完毕，保存到数据库
                            val assistantMsg = ChatMessage(
                                id = assistantMsgId,
                                sessionId = sessionId,
                                role = MessageRole.ASSISTANT,
                                content = accumulatedText,
                                reasoningContent = accumulatedReasoning.takeIf { it.isNotEmpty() },
                                createdAt = streamCreatedAt,
                                updatedAt = System.currentTimeMillis(),
                                orderKey = streamOrderKey
                            )
                            var persistedAssistantMessage = assistantMsg
                            if (alternativeTargetMessageId != null) {
                                val old = chatRepository.getMessage(alternativeTargetMessageId, sessionId)
                                if (old != null) {
                                    val existingAlternatives = old.alternatives.takeIf { it.isNotEmpty() }
                                        ?: listOf(old.content)
                                    val updatedAlternatives = (existingAlternatives + accumulatedText)
                                        .takeLast(5)
                                    persistedAssistantMessage = old.copy(
                                            content = updatedAlternatives.last(),
                                            alternatives = updatedAlternatives,
                                            currentAlternativeIndex = updatedAlternatives.lastIndex,
                                            reasoningContent = accumulatedReasoning.takeIf { it.isNotEmpty() },
                                            formatRepairNotice = null,
                                            updatedAt = System.currentTimeMillis()
                                    )
                                    chatRepository.updateMessage(persistedAssistantMessage)
                                } else {
                                    chatRepository.addMessage(assistantMsg)
                                }
                            } else {
                                chatRepository.addMessage(assistantMsg)
                            }

                            replaceMessagesFromRepository()
                            _streamingMessage.value = null
                            if (appSettings.automaticFormatCheckEnabled) {
                                persistedAssistantMessage = performMessageFormatRepair(
                                    message = persistedAssistantMessage,
                                    automatic = true
                                )
                            }
                            if (persistedAssistantMessage.displayContent.isNotBlank()) {
                                StreamingNotificationManager.showComplete(
                                    ctx,
                                    renderSessionText(persistedAssistantMessage.displayContent)
                                )
                            }

                            updateLongTermMemoryAfterReply(
                                session = currentSession,
                                modelConfig = modelConfig,
                                contextWindowSize = effectiveContextWindowSize
                            )
                            ChatBarApp.instance.momentScheduler.kick("chat-reply")
                            
                            try {
                                replaceMessagesFromRepository()
                            } catch (_: Exception) {}
                            _isResponding.value = false

                            // 对话存入 RAG 记忆库（后台异步）
                            if (embeddingConfig != null) {
                                try {
                                    ragMemoryMutationMutex.withLock {
                                        indexMessagesLeavingContextWindow(
                                            contextWindowSize = effectiveContextWindowSize,
                                            embeddingConfig = embeddingConfig
                                        )
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                ChatBarApp.instance.streamingStopRequested.value = false
                _isResponding.value = false
                if (e is BackgroundGenerationProtectionCancellationException) {
                    addSystemMessage("后台生成已中止：${e.reason}")
                } else if (e !is CancellationException) {
                    try {
                        if (alternativeTargetMessageId == null) {
                            val errorAssistantMsg = ChatMessage.create(
                                sessionId = sessionId,
                                role = MessageRole.ASSISTANT,
                                content = "错误: ${e.message}"
                            )
                            chatRepository.addMessage(errorAssistantMsg)
                            try {
                                replaceMessagesFromRepository()
                            } catch (_: Exception) {}
                        } else {
                            addSystemMessage("错误: ${e.message}")
                        }
                    } catch (_: Exception) {}
                }
                _streamingMessage.value = null
            } finally {
                ChatBarApp.instance.streamingStopRequested.value = false
            }
            } catch (e: Exception) {
                ChatBarApp.instance.streamingStopRequested.value = false
                _isResponding.value = false
                if (e !is CancellationException) {
                    try {
                        if (alternativeTargetMessageId == null) {
                            val errorAssistantMsg = ChatMessage.create(
                                sessionId = sessionId,
                                role = MessageRole.ASSISTANT,
                                content = "閿欒: ${e.message}"
                            )
                            chatRepository.addMessage(errorAssistantMsg)
                            try {
                                replaceMessagesFromRepository()
                            } catch (_: Exception) {}
                        } else {
                            addSystemMessage("閿欒: ${e.message}")
                        }
                    } catch (_: Exception) {}
                }
                _streamingMessage.value = null
            } finally {
                protectionLossHandle?.dispose()
                ChatBarApp.instance.streamingStopRequested.value = false
                stopStreamingForegroundWork()
            }
        }
        responseJob = job
        job.invokeOnCompletion {
            if (responseJob == job) responseJob = null
        }
    }

    /**
     * 删除特定消息
     */
    private fun updateLongTermMemoryAfterReply(
        session: ChatSession,
        modelConfig: ModelConfig,
        contextWindowSize: Int
    ) {
        if (!session.longTermMemoryEnabled) return
        ChatBarApp.instance.applicationScope.launch {
            AiBackgroundWorkManager.run(sessionId) {
                val latestSession = chatRepository.getSession(sessionId) ?: return@run
                if (!latestSession.longTermMemoryEnabled) return@run
                longTermMemoryService.updateArchiveAfterReply(sessionId, modelConfig, contextWindowSize)
                _session.value = chatRepository.getSession(sessionId)
                runCatching { loadLongTermMemoryUiState() }
                    .onSuccess { _longTermMemoryUiState.value = it }
                drainMemoryCompressionEvents()
            }
        }
        ChatBarApp.instance.applicationScope.launch {
            AiBackgroundWorkManager.run(sessionId) {
                val latestSession = chatRepository.getSession(sessionId) ?: return@run
                if (!latestSession.longTermMemoryEnabled) return@run
                longTermMemoryService.updateHeadAfterReply(sessionId, modelConfig)
                _session.value = chatRepository.getSession(sessionId)
                runCatching { loadLongTermMemoryUiState() }
                    .onSuccess { _longTermMemoryUiState.value = it }
            }
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            val currentMessages = chatRepository.getMessages(sessionId)
            val deletedMessage = currentMessages.firstOrNull { it.id == messageId }

            deletedMessage
                ?.images
                ?.forEach { deleteDisposableChatImage(it) }
            _messages.value = _messages.value.filter { it.id != messageId }
            chatRepository.deleteMessage(messageId, sessionId)
            refreshMessages()

            _isDeletingMemory.value = true
            yield()
            try {
                ragMemoryMutationMutex.withLock {
                    if (deletedMessage == null) {
                        ragManager.deleteMemoryForMessage(messageId)
                    } else {
                        refreshMemoryAfterMessageDeletion(deletedMessage)
                    }
                }
            } finally {
                _isDeletingMemory.value = false
            }
        }
    }

    fun deleteImage(messageId: String, imagePath: String) {
        viewModelScope.launch {
            val message = chatRepository.getMessage(messageId, sessionId) ?: return@launch
            if (imagePath !in message.images) return@launch
            val remaining = message.images.filterNot { it == imagePath }
            deleteDisposableChatImage(imagePath)
            if (remaining.isEmpty() && message.content.isBlank()) {
                chatRepository.deleteMessage(messageId, sessionId)
                ragMemoryMutationMutex.withLock {
                    refreshMemoryAfterMessageDeletion(message)
                }
            } else {
                chatRepository.updateMessage(
                    message.copy(
                        images = remaining,
                        generatedImageMetadata = message.generatedImageMetadata
                            .filter { it.imagePath in remaining }
                    )
                )
            }
            refreshMessages()
        }
    }

    /**
     */
    fun editMessage(messageId: String, content: String, imagePaths: List<String>) {
        viewModelScope.launch {
            val oldMessage = chatRepository.getMessage(messageId, sessionId) ?: return@launch
            if (content.isBlank() && imagePaths.isEmpty()) return@launch

            applyMessageEdit(
                oldMessage = oldMessage,
                content = content,
                imagePaths = imagePaths,
                deleteRemovedImages = true
            )
        }
    }

    private fun NovelAiPromptPlan.toGeneratedImageMetadata(
        imagePath: String,
        imageSize: NovelAiImageSize
    ): GeneratedImageMetadata = GeneratedImageMetadata(
        imagePath = imagePath,
        baseCaption = baseCaption,
        characterPrompts = characterCaptions.map {
            GeneratedImageCharacterPrompt(
                prompt = it.prompt,
                centerX = it.center.x,
                centerY = it.center.y
            )
        },
        negativePrompt = effectiveNegativePrompt,
        sizePreset = sizePreset.name,
        width = imageSize.width,
        height = imageSize.height
    )

    fun editMessageSegment(
        messageId: String,
        start: Int,
        endExclusive: Int,
        replacement: String
    ) {
        viewModelScope.launch {
            val oldMessage = chatRepository.getMessage(messageId, sessionId) ?: return@launch
            val editOutcome = editRoleplayMessageSegment(
                message = oldMessage,
                start = start,
                endExclusive = endExclusive,
                replacement = replacement
            )
            val updatedMessage = editOutcome.message
            if (updatedMessage == null) {
                deleteMessage(messageId)
                return@launch
            }

            applyMessageEdit(
                oldMessage = oldMessage,
                content = updatedMessage.content,
                imagePaths = updatedMessage.images,
                deleteRemovedImages = false,
                deleteMemoryWhenTextBlank = editOutcome.deleteMemoryForMessage
            )
        }
    }

    private suspend fun applyMessageEdit(
        oldMessage: ChatMessage,
        content: String,
        imagePaths: List<String>,
        deleteRemovedImages: Boolean,
        deleteMemoryWhenTextBlank: Boolean = false
    ) {
        val updatedMessage = oldMessage.copy(
            content = content,
            images = imagePaths,
            generatedImageMetadata = oldMessage.generatedImageMetadata
                .filter { it.imagePath in imagePaths },
            alternatives = emptyList(),
            currentAlternativeIndex = 0,
            formatRepairNotice = null,
            updatedAt = System.currentTimeMillis()
        )

        if (deleteRemovedImages) {
            oldMessage.images.filterNot(imagePaths::contains).forEach { deleteDisposableChatImage(it) }
        }

        chatRepository.updateMessage(updatedMessage)
        refreshMessages()

        if (deleteMemoryWhenTextBlank) {
            ragManager.deleteMemoryForMessage(oldMessage.id)
        }

        refreshMemoryAfterMessageEdit(updatedMessage)
    }

    private suspend fun refreshMemoryAfterMessageEdit(updatedMessage: ChatMessage) {
        val messageId = updatedMessage.id
        val repository = ChatBarApp.instance.ragRepository
        val allMessages = chatRepository.getMessages(sessionId)
        val renderContext = placeholderRenderContext()
        val renderedMessages = allMessages.renderWith(renderContext)
        val turn = ChatMemoryIndexPolicy.buildTurns(renderedMessages)
            .firstOrNull { messageId in it.messageIds }
        if (turn == null) {
            ragManager.deleteMemoryForMessage(messageId)
            return
        }
        val activeIds = contextWindowManager
            .getRecentMessages(allMessages, effectiveContextWindowSize())
            .mapTo(mutableSetOf()) { it.id }
        if (turn.messageIds.any { it in activeIds }) {
            repository.deleteSupersededAutomaticChatMemory(
                sessionId = sessionId,
                sourceTurnId = turn.sourceTurnId,
                messageIds = turn.messageIds,
                keepChunkId = null
            )
            return
        }
        val embeddingConfig = modelResolver.embeddingModel(settingsRepository.getAppSettings())
        if (embeddingConfig != null) {
            try {
                AiBackgroundWorkManager.run(sessionId) {
                    ragManager.indexTimelineTurnMemory(
                        turn,
                        sessionId,
                        embeddingConfig
                    )
                }
            } catch (_: Exception) {
                repository.deleteSupersededAutomaticChatMemory(
                    sessionId = sessionId,
                    sourceTurnId = turn.sourceTurnId,
                    messageIds = turn.messageIds,
                    keepChunkId = null
                )
            }
        } else {
            repository.deleteSupersededAutomaticChatMemory(
                sessionId = sessionId,
                sourceTurnId = turn.sourceTurnId,
                messageIds = turn.messageIds,
                keepChunkId = null
            )
        }
    }

    private suspend fun refreshMemoryAfterMessageDeletion(deletedMessage: ChatMessage) {
        val remainingTurnMessage = deletedMessage.sourceTurnId?.let { sourceTurnId ->
            chatRepository.getMessages(sessionId).firstOrNull { it.sourceTurnId == sourceTurnId }
        }
        if (remainingTurnMessage != null) {
            refreshMemoryAfterMessageEdit(remainingTurnMessage)
            return
        }
        val sourceTurnId = deletedMessage.sourceTurnId
        if (sourceTurnId == null) {
            ragManager.deleteMemoryForMessage(deletedMessage.id)
        } else {
            ChatBarApp.instance.ragRepository.deleteSupersededAutomaticChatMemory(
                sessionId = sessionId,
                sourceTurnId = sourceTurnId,
                messageIds = setOf(deletedMessage.id),
                keepChunkId = null
            )
        }
    }

    fun switchAssistantAlternative(messageId: String, direction: Int) {
        viewModelScope.launch {
            val message = _messages.value.find { it.id == messageId }
                ?: chatRepository.getMessage(messageId, sessionId)
                ?: return@launch
            if (message.role != MessageRole.ASSISTANT || message.alternatives.size <= 1) return@launch
            val isRecent = contextWindowManager
                .getRecentMessages(_messages.value, effectiveContextWindowSize())
                .any { it.id == messageId }
            if (!isRecent) return@launch
            val nextIndex = (message.currentAlternativeIndex + direction)
                .coerceIn(0, message.alternatives.lastIndex)
            if (nextIndex == message.currentAlternativeIndex) return@launch
            val updated = message.copy(
                content = message.alternatives[nextIndex],
                currentAlternativeIndex = nextIndex,
                updatedAt = System.currentTimeMillis()
            )
            _messages.value = _messages.value.map { if (it.id == messageId) updated else it }
            chatRepository.updateMessage(updated)
        }
    }

    private suspend fun isMessageInActiveContextWindow(messageId: String): Boolean {
        val contextWindowSize = effectiveContextWindowSize()
        val messages = chatRepository.getMessages(sessionId)
        return contextWindowManager.getRecentMessages(messages, contextWindowSize).any { it.id == messageId }
    }

    private suspend fun indexMessagesLeavingContextWindow(
        contextWindowSize: Int,
        embeddingConfig: EmbeddingConfig
    ) {
        val allMessages = chatRepository.getMessages(sessionId)
        ChatBarApp.instance.ragRepository.pruneChatMemory(
            sessionId = sessionId,
            liveMessageIds = allMessages.mapTo(mutableSetOf()) { it.id }
        )
        val activeMessages = contextWindowManager.getRecentMessages(allMessages, contextWindowSize)
        if (activeMessages.size == allMessages.size) return
        val activeIds = activeMessages.map { it.id }.toSet()
        val currentAutomaticChunks = ChatBarApp.instance.ragRepository
            .getAllChunksForSession(sessionId)
            .filterNot(ChatMemoryIndexPolicy::needsAutomaticRebuild)
            .filter { it.metadata["indexMode"] == ChatMemoryIndexPolicy.INDEX_MODE }
            .associateBy { it.id }
        val renderContext = placeholderRenderContext()
        val turnsToIndex = ChatMemoryIndexPolicy.buildIndexableTurns(
            messages = allMessages.renderWith(renderContext),
            activeMessageIds = activeIds
        )
            .filter { turn ->
                val existing = currentAutomaticChunks[chatMemoryChunkId(sessionId, turn.identityKey)]
                existing == null ||
                    existing.messageIds() != turn.messageIds ||
                    existing.metadata["sourceHash"] != ragManager.hashContent(
                        ChatMemoryIndexPolicy.contentForIndex(turn)
                    )
            }
            .takeLast(2)
        for (turn in turnsToIndex) {
            ragManager.indexTimelineTurnMemory(
                turn = turn,
                sessionId = sessionId,
                embeddingConfig = embeddingConfig
            )
        }
    }

    fun clearHistoryAndMemory() {
        viewModelScope.launch {
            // 1. 删除所有聊天消息
            val msgs = chatRepository.getMessages(sessionId)
            msgs.forEach { message ->
                message.images.forEach { deleteDisposableChatImage(it) }
                chatRepository.deleteMessage(message.id, sessionId)
            }
            
            // 2. 清空 RAG 向量库对应的 CHAT_MEMORY 类型
            ChatBarApp.instance.ragRepository.deleteChunksBySource(ChunkSourceType.CHAT_MEMORY, sessionId)
            longTermMemoryService.clear(sessionId)
            
            // 3. 重置 session 预览信息
            chatRepository.getSession(sessionId)?.let { session ->
                val resetSession = session.copy(
                    longTermMemory = "",
                    longTermMemoryUpdatedThroughMessageId = null,
                    nextTimelineTurn = 1,
                    timelineTombstones = emptySet(),
                    memoryHeadCommitId = null,
                    memoryUpdateStatus = MemoryUpdateStatus.IDLE,
                    memoryUpdateError = null,
                    lastMessagePreview = null,
                    lastMessageTime = null
                )
                chatRepository.updateSession(resetSession)
                _session.value = resetSession
            }

            _characterCard.value?.greeting?.takeIf { it.isNotBlank() }?.let { greeting ->
                chatRepository.addMessage(
                    ChatMessage.create(
                        sessionId = sessionId,
                        role = MessageRole.ASSISTANT,
                        content = greeting
                    )
                )
            }
             
            loadSessionData()
        }
    }

    /** 重新生成指定 AI 文本回复，保留其消息 ID 与时间轴位置。 */
    fun regenerateResponse(messageId: String) {
        if (_isResponding.value || _isArchived.value) return
        if (blockChatDuringMemoryBackfill()) return

        viewModelScope.launch {
            val currentMessages = chatRepository.getMessages(sessionId)
            val selectedMessage = currentMessages.firstOrNull { it.id == messageId } ?: return@launch
            val targetMessage = when {
                selectedMessage.role == MessageRole.ASSISTANT && selectedMessage.displayContent.isNotBlank() -> {
                    selectedMessage
                }
                selectedMessage.isRetryableGenerationError() -> {
                    val targetId = regenerationTargetAssistantMessageId(currentMessages, selectedMessage.id)
                        ?: return@launch
                    currentMessages.firstOrNull { it.id == targetId } ?: return@launch
                }
                else -> return@launch
            }

            _isResponding.value = true
            val retryErrorMessageId = selectedMessage.id.takeIf { selectedMessage.id != targetMessage.id }
            val generationMessages = currentMessages.filterNot { it.id == retryErrorMessageId }
            _messages.value = generationMessages.filterNot { it.id == targetMessage.id }
            _streamingMessage.value = null

            if (!isMessageInActiveContextWindow(targetMessage.id)) {
                _streamingMessage.value = null
                _isResponding.value = false
                _messages.value = currentMessages
                addSystemMessage("This reply is already in long-term memory. Multi-reply regeneration is disabled.")
                return@launch
            }

            retryErrorMessageId?.let { errorMessageId ->
                chatRepository.deleteMessage(errorMessageId, sessionId)
            }

            sendMessageInternal(
                content = "",
                imagePaths = emptyList(),
                persistUserMessage = false,
                alternativeTargetMessageId = targetMessage.id,
                respondingAlreadyStarted = true
            )
        }
    }

    private fun blockChatDuringMemoryBackfill(): Boolean {
        if (_session.value?.longTermMemoryEnabled == true && _longTermMemoryUiState.value.loading) {
            viewModelScope.launch {
                _memoryCompressionEvents.emit("正在读取长期记忆，请稍后再试")
            }
            return true
        }
        if (_longTermMemoryUiState.value.memoryState?.backfill?.status != MemoryBackfillStatus.RUNNING) {
            return false
        }
        viewModelScope.launch {
            _memoryCompressionEvents.emit("长期记忆正在补录；请先暂停补录再继续聊天")
        }
        return true
    }

    /**
     * 保存会话特定的配置参数
     */
    fun updateSessionConfig(
        modelId: String?,
        imageModelId: String?,
        formatCardId: String?,
        replyLength: String?,
        replyLanguage: String?,
        supplementarySetting: String?,
        playerName: String?,
        playerSetting: String?,
        chatBackground: String?,
        longTermMemoryEnabled: Boolean,
        longTermMemory: String,
        extraWorldBookIds: List<String> = _session.value?.extraWorldBookIds ?: emptyList()
    ) {
        viewModelScope.launch {
            _session.value?.let { s ->
                if (s.longTermMemoryEnabled != longTermMemoryEnabled) {
                    longTermMemoryService.setEnabled(sessionId, longTermMemoryEnabled)
                }
                val base = chatRepository.getSession(sessionId) ?: s
                val updated = base.copy(
                    modelId = modelId,
                    imageModelId = imageModelId,
                    formatCardId = formatCardId,
                    replyLength = replyLength,
                    replyLanguage = replyLanguage?.takeIf { it.isNotBlank() },
                    supplementarySetting = supplementarySetting?.takeIf { it.isNotBlank() },
                    playerName = playerName?.takeIf { it.isNotBlank() },
                    playerSetting = playerSetting?.takeIf { it.isNotBlank() },
                    chatBackground = chatBackground?.takeIf { it.isNotBlank() },
                    longTermMemoryEnabled = longTermMemoryEnabled,
                    longTermMemory = base.longTermMemory,
                    extraWorldBookIds = extraWorldBookIds.distinct()
                )
                chatRepository.updateSession(updated)
                _session.value = updated
            }
        }
    }

    /**
     * 创建当前对话的存档
     */
    fun createSaveSlot(name: String, description: String?) {
        viewModelScope.launch {
            val curSession = _session.value ?: return@launch
            val messagesList = _messages.value
            val packaged = packageSaveSlotImages(curSession, messagesList)
            
            // 获取当前的向量记忆库快照
            val vectorChunks = ChatBarApp.instance.ragRepository.getAllChunksForSession(sessionId)
            val memorySnapshot = longTermMemoryService.snapshot(sessionId)

            val saveSlot = SaveSlot.create(
                sessionId = sessionId,
                name = name,
                description = description,
                messages = packaged.messages,
                vectorChunks = vectorChunks
            )
            // 覆盖当前的设定值
            val activePlayerSetting = curSession.playerSetting
            val activePlayerName = curSession.playerName
            val activeSuppSetting = curSession.supplementarySetting
            
            val finalizedSlot = saveSlot.copy(
                playerSetting = activePlayerSetting,
                playerName = activePlayerName,
                supplementarySetting = activeSuppSetting,
                modelId = curSession.modelId,
                imageModelId = curSession.imageModelId,
                formatCardId = curSession.formatCardId,
                replyLength = curSession.replyLength,
                replyLanguage = curSession.replyLanguage,
                roleplayStyle = curSession.roleplayStyle,
                chatBackground = packaged.chatBackground,
                longTermMemoryEnabled = curSession.longTermMemoryEnabled,
                longTermMemory = curSession.longTermMemory,
                longTermMemoryUpdatedThroughMessageId = curSession.longTermMemoryUpdatedThroughMessageId,
                nextSourceTurnOrder = curSession.nextSourceTurnOrder,
                sourceTurnTombstones = curSession.sourceTurnTombstones,
                nextTimelineTurn = curSession.nextTimelineTurn,
                timelineTombstones = curSession.timelineTombstones,
                memoryLimitChars = curSession.memoryLimitChars,
                memorySnapshot = memorySnapshot,
                contextWindowSize = curSession.contextWindowSize,
                extraWorldBookIds = curSession.extraWorldBookIds,
                timedWorldInfo = curSession.timedWorldInfo,
                imageResources = packaged.resources
            )

            saveSlotRepository.save(finalizedSlot)
            refreshSaveSlots()
        }
    }

    /**
     * 读取存档并覆盖当前对话状态
     */
    fun loadSaveSlot(summary: SaveSlotSummary) {
        viewModelScope.launch {
            val curSession = _session.value ?: return@launch
            val slot = saveSlotRepository.getById(summary.id) ?: error("存档不存在")
            val materializedSlot = materializeSaveSlotImages(slot)
            val preservedImages = materializedSlot.messages
                .flatMap { it.images }
                .toSet() + listOfNotNull(materializedSlot.chatBackground)
            val currentMsgs = chatRepository.getMessages(sessionId)
            val currentChunks = ChatBarApp.instance.ragRepository.getAllChunksForSession(sessionId)
            longTermMemoryService.ensureMigrated(sessionId)
            val currentMemorySnapshot = longTermMemoryService.snapshot(sessionId)
            val currentImages = currentMsgs.flatMap { it.images }.toSet() +
                listOfNotNull(curSession.chatBackground)
            val latest = materializedSlot.messages.lastOrNull()
            val restoredSession = curSession.copy(
                playerSetting = materializedSlot.playerSetting,
                playerName = materializedSlot.playerName,
                supplementarySetting = materializedSlot.supplementarySetting,
                modelId = materializedSlot.modelId,
                imageModelId = materializedSlot.imageModelId,
                formatCardId = materializedSlot.formatCardId,
                replyLength = materializedSlot.replyLength,
                replyLanguage = materializedSlot.replyLanguage,
                roleplayStyle = materializedSlot.roleplayStyle,
                chatBackground = materializedSlot.chatBackground,
                longTermMemoryEnabled = materializedSlot.longTermMemoryEnabled,
                longTermMemory = materializedSlot.longTermMemory,
                longTermMemoryUpdatedThroughMessageId = materializedSlot.longTermMemoryUpdatedThroughMessageId,
                nextSourceTurnOrder = materializedSlot.nextSourceTurnOrder,
                sourceTurnTombstones = materializedSlot.sourceTurnTombstones,
                nextTimelineTurn = materializedSlot.nextTimelineTurn,
                timelineTombstones = materializedSlot.timelineTombstones,
                memoryLimitChars = materializedSlot.memoryLimitChars,
                contextWindowSize = materializedSlot.contextWindowSize ?: curSession.contextWindowSize,
                extraWorldBookIds = materializedSlot.extraWorldBookIds,
                timedWorldInfo = materializedSlot.timedWorldInfo,
                lastMessagePreview = latest?.saveSlotPreviewText(),
                lastMessageTime = latest?.createdAt,
                lastMessageRole = latest?.role
            )
            val updatedChunks = materializedSlot.vectorChunks.map { it.copy(sourceId = sessionId) }
            try {
                chatRepository.replaceMessagesForSession(sessionId, materializedSlot.messages)
                ChatBarApp.instance.ragRepository.deleteChunksBySource(
                    ChunkSourceType.CHAT_MEMORY,
                    sessionId
                )
                ChatBarApp.instance.ragRepository.saveChunks(updatedChunks)
                chatRepository.updateSession(restoredSession)
                longTermMemoryService.loadSnapshot(sessionId, materializedSlot.memorySnapshot)
            } catch (error: Throwable) {
                chatRepository.replaceMessagesForSession(sessionId, currentMsgs)
                ChatBarApp.instance.ragRepository.deleteChunksBySource(
                    ChunkSourceType.CHAT_MEMORY,
                    sessionId
                )
                ChatBarApp.instance.ragRepository.saveChunks(currentChunks)
                chatRepository.updateSession(curSession)
                runCatching { longTermMemoryService.loadSnapshot(sessionId, currentMemorySnapshot) }
                preservedImages.filterNot { it in currentImages }.forEach { deleteDisposableChatImage(it) }
                throw error
            }

            currentImages.filterNot { it in preservedImages }.forEach { deleteDisposableChatImage(it) }
            loadSessionData()
        }
    }

    /**
     * 删除一个存档
     */
    fun deleteSaveSlot(slotId: String) {
        viewModelScope.launch {
            saveSlotRepository.delete(slotId)
            refreshSaveSlots()
        }
    }

    suspend fun exportSaveSlotJson(slotId: String, output: OutputStream) = withContext(Dispatchers.IO) {
        val slot = saveSlotRepository.getById(slotId) ?: error("存档不存在")
        val packaged = packageSaveSlotImageRefs(
            chatBackground = slot.chatBackground,
            messages = slot.messages,
            existingResources = slot.imageResources
        )
        val portable = slot.copy(
            chatBackground = packaged.chatBackground,
            messages = packaged.messages,
            imageResources = packaged.resources
        )
        SaveSlotJsonTransfer.write(portable, output)
    }

    suspend fun importSaveSlotJson(input: InputStream): SaveSlot {
        val decoded = withContext(Dispatchers.IO) {
            SaveSlotJsonTransfer.read(input)
        }
        validateSaveSlotImport(decoded)
        val currentNames = saveSlotRepository.getBySessionId(sessionId).map { it.name }
        val requestedName = decoded.name.ifBlank { "导入存档" }
        val importedName = if (currentNames.any { NamePolicy.isSame(it, requestedName) }) {
            NamePolicy.nextCopyName(requestedName, currentNames)
        } else {
            NamePolicy.normalize(requestedName)
        }
        val imported = decoded.copy(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            name = importedName,
            messages = decoded.messages.map { it.copy(sessionId = sessionId) },
            vectorChunks = decoded.vectorChunks.map { it.copy(sourceId = sessionId) },
            createdAt = System.currentTimeMillis()
        )
        saveSlotRepository.save(imported)
        _availableSaveSlots.value = saveSlotRepository.getBySessionId(sessionId)
        return imported
    }

    private data class PackagedSaveSlotImages(
        val chatBackground: String?,
        val messages: List<ChatMessage>,
        val resources: Map<String, SaveSlotImageResource>
    )

    private suspend fun packageSaveSlotImages(
        session: ChatSession,
        messages: List<ChatMessage>
    ): PackagedSaveSlotImages = packageSaveSlotImageRefs(
        chatBackground = session.chatBackground,
        messages = messages
    )

    private suspend fun packageSaveSlotImageRefs(
        chatBackground: String?,
        messages: List<ChatMessage>,
        existingResources: Map<String, SaveSlotImageResource> = emptyMap()
    ): PackagedSaveSlotImages = withContext(Dispatchers.IO) {
        val resources = linkedMapOf<String, SaveSlotImageResource>().apply { putAll(existingResources) }
        val resourceIdsByPath = linkedMapOf<String, String>()

        fun packageImage(path: String?, preferredId: String): String? {
            val sourcePath = path?.takeIf(String::isNotBlank) ?: return null
            resourceIdsByPath[sourcePath]?.let { return it }
            val file = File(sourcePath)
            if (!file.isFile) return sourcePath
            val resourceId = uniqueResourceId(preferredId, resources.keys)
            resources[resourceId] = SaveSlotImageResource(
                fileName = file.name.ifBlank { "$resourceId.jpg" },
                data = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            )
            resourceIdsByPath[sourcePath] = resourceId
            return resourceId
        }

        PackagedSaveSlotImages(
            chatBackground = packageImage(chatBackground, "chat-background"),
            messages = messages.mapIndexed { messageIndex, message ->
                message.copy(
                    images = message.images.mapIndexed { imageIndex, path ->
                        packageImage(path, "message-$messageIndex-image-$imageIndex") ?: path
                    }
                )
            },
            resources = resources
        )
    }

    private suspend fun materializeSaveSlotImages(slot: SaveSlot): SaveSlot = withContext(Dispatchers.IO) {
        if (slot.imageResources.isEmpty()) return@withContext slot
        val createdFiles = mutableListOf<File>()
        try {
            val pathsByResourceId = slot.imageResources.mapValues { (resourceId, image) ->
                val file = createSaveSlotImageFile(slot.id, resourceId, image.fileName)
                createdFiles += file
                file.writeBytes(Base64.decode(image.data, Base64.DEFAULT))
                file.absolutePath
            }
            slot.copy(
                chatBackground = slot.chatBackground?.let { pathsByResourceId[it] ?: it },
                messages = slot.messages.map { message ->
                    message.copy(images = message.images.map { pathsByResourceId[it] ?: it })
                }
            )
        } catch (error: Throwable) {
            createdFiles.forEach { it.delete() }
            throw error
        }
    }

    private fun createSaveSlotImageFile(slotId: String, resourceId: String, fileName: String): File {
        val directory = File(ChatBarApp.instance.filesDir, "images/save_slots/${safeFileSegment(slotId)}")
            .also(File::mkdirs)
        val extension = File(fileName).extension
            .lowercase(Locale.ROOT)
            .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
            ?: "jpg"
        return File(directory, "${safeFileSegment(resourceId)}_${UUID.randomUUID()}.$extension")
    }

    private fun validateSaveSlotImport(slot: SaveSlot) {
        require(slot.schemaVersion in 1..4) { "不支持的存档 schemaVersion：${slot.schemaVersion}" }
        require(slot.name.isNotBlank()) { "存档名称不能为空" }
        require(slot.messages.all { it.id.isNotBlank() }) { "存档包含空消息 ID" }
        require(slot.messages.map { it.id }.distinct().size == slot.messages.size) { "存档包含重复消息 ID" }
        require(slot.imageResources.all { (id, image) -> id.isNotBlank() && image.fileName.isNotBlank() && image.data.isNotBlank() }) {
            "图片资源 ID、文件名和数据不能为空"
        }
    }

    private fun uniqueResourceId(preferredId: String, usedIds: Set<String>): String {
        val base = preferredId.ifBlank { "image" }
        if (base !in usedIds) return base
        var index = 2
        while ("$base-$index" in usedIds) index++
        return "$base-$index"
    }

    private fun deleteDisposableChatImage(path: String): Boolean {
        if (novelAiImageStorage.deleteIfOwned(path)) return true
        val file = File(path)
        return runCatching {
            val imagesRoot = File(ChatBarApp.instance.filesDir, "images").canonicalFile
            val saveSlotRoot = File(imagesRoot, "save_slots").canonicalFile
            val canonicalFile = file.canonicalFile
            val parent = canonicalFile.parentFile?.canonicalFile
            val owned = canonicalFile.path.startsWith(saveSlotRoot.path + File.separator) ||
                (parent == imagesRoot && canonicalFile.name.startsWith("chat_img_"))
            owned && (!file.exists() || file.delete())
        }.getOrDefault(false)
    }

    private fun safeFileSegment(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "image" }

    private fun ChatMessage.saveSlotPreviewText(): String =
        displayContent.takeIf { it.isNotBlank() }?.take(100)
            ?: if (images.isNotEmpty()) "[图片]" else ""

    /**
     * 重建当前角色卡的 RAG 索引
     * @return 状态信息，供 UI 展示
     */
    suspend fun rebuildRagIndex(): String = withContext(Dispatchers.IO) {
        val loadedCard = _characterCard.value ?: return@withContext "错误：未加载角色卡"
        val charCard = ChatBarApp.instance.presetCatalogService.repairPresetCharacterResources(loadedCard)
        _characterCard.value = charCard
        if (charCard.customDocuments.isEmpty()) {
            ChatBarApp.instance.ragRepository.deleteChunksBySource(ChunkSourceType.DOCUMENT, charCard.id)
            return@withContext "无参考文档，已跳过文档 RAG。"
        }
        val appSettings = settingsRepository.getAppSettings()
        val embeddingConfig = modelResolver.embeddingModel(appSettings)
            ?: return@withContext "错误：当前配置层级没有可用向量模型"
        
        AiBackgroundWorkManager.run(sessionId) {
        val logs = StringBuilder()
        logs.appendLine("开始重建 RAG 索引...")
        
        // 仅重建参考文档索引。角色设定直接进入系统提示词。
        charCard.customDocuments.forEach { doc ->
            try {
                logs.append("正在索引文档 [${doc.fileName}]... ")
                val file = File(doc.filePath)
                if (file.exists()) {
                    val content = file.readText()
                    ragManager.indexDocument(doc, content, charCard.id, embeddingConfig)
                    logs.appendLine("成功")
                } else {
                    logs.appendLine("失败: 本地文件不存在")
                }
            } catch (e: Exception) {
                logs.appendLine("失败: ${e.message}")
            }
        }
        
        logs.appendLine("重建完成。")
        logs.toString()
        }
    }

    private suspend fun addSystemMessage(text: String) {
        val sysMsg = ChatMessage.create(
            sessionId = sessionId,
            role = MessageRole.SYSTEM,
            content = text
        )
        chatRepository.addMessage(sysMsg)
        _messages.value = _messages.value + sysMsg
    }

    fun replaceCharacterCardAvatarFromImage(imagePath: String, onResult: (Boolean, String) -> Unit) {
        replaceCharacterCardImage(imagePath, CharacterCardImageTarget.AVATAR, onResult)
    }

    fun replaceCharacterCardBackgroundFromImage(imagePath: String, onResult: (Boolean, String) -> Unit) {
        replaceCharacterCardImage(imagePath, CharacterCardImageTarget.BACKGROUND, onResult)
    }

    private fun replaceCharacterCardImage(
        imagePath: String,
        target: CharacterCardImageTarget,
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val loadedCard = _characterCard.value
                    ?: throw IllegalStateException("当前会话没有可更新的角色卡")
                val currentCard = characterRepository.getById(loadedCard.id)
                    ?: throw IllegalStateException("当前会话没有可更新的角色卡")
                val persisted = CharacterCardImageUpdater.replace(
                    context = ChatBarApp.instance,
                    characterRepository = characterRepository,
                    cardId = currentCard.id,
                    sourcePath = imagePath,
                    target = target
                )
                _characterCard.value = persisted
                if (target == CharacterCardImageTarget.BACKGROUND) {
                    syncSessionBackgroundOverride(
                        previousCardBackground = currentCard.chatBackground,
                        newCardBackground = persisted.chatBackground.orEmpty()
                    )
                }
                onResult(
                    true,
                    when (target) {
                        CharacterCardImageTarget.AVATAR -> "已替换角色卡头像"
                        CharacterCardImageTarget.BACKGROUND -> "已替换角色卡背景"
                    }
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                onResult(false, error.message ?: "替换图片失败")
            }
        }
    }

    private suspend fun syncSessionBackgroundOverride(
        previousCardBackground: String?,
        newCardBackground: String
    ) {
        val currentSession = _session.value ?: return
        val nextBackground = CharacterCardImagePolicy.sessionBackgroundOverrideAfterCardBackgroundChange(
            sessionBackground = currentSession.chatBackground,
            previousCardBackground = previousCardBackground,
            newCardBackground = newCardBackground
        )
        if (nextBackground != currentSession.chatBackground) {
            val updatedSession = currentSession.copy(chatBackground = nextBackground)
            chatRepository.updateSession(updatedSession)
            _session.value = updatedSession
        }
    }

    fun copyUriToLocalFile(uri: Uri, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val context = ChatBarApp.instance
                val contentResolver = context.contentResolver
                val filesDir = context.filesDir
                val imagesDir = File(filesDir, "images")
                if (!imagesDir.exists()) imagesDir.mkdirs()
                
                val mimeType = contentResolver.getType(uri)
                val extension = when (mimeType) {
                    "image/png" -> "png"
                    "image/gif" -> "gif"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }
                
                val localFile = File(imagesDir, "chat_img_${System.currentTimeMillis()}.$extension")
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        localFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                }
                onSuccess(localFile.absolutePath)
            } catch (e: Exception) {
                addSystemMessage("拷贝图片文件失败: ${e.message}")
            }
        }
    }

    private suspend fun encodeImageToBase64(path: String): String = withContext(Dispatchers.IO) {
        ImageFileEncoder.encodeToJpegBase64(path)
    }
}

private fun VectorChunk.messageIds(): Set<String> {
    val metadataIds = metadata["messageIds"]
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    return (metadataIds + listOfNotNull(messageId)).toSet()
}

private const val DOCUMENT_LEXICAL_ACCEPT_THRESHOLD = 0.24f
private const val MEMORY_LEXICAL_ACCEPT_THRESHOLD = 0.18f
private const val RRF_K = 60f
private const val MULTIMODAL_PROMPT_TOKEN_RESERVE = 1024

private data class DocumentRank(
    val chunk: VectorChunk,
    val vectorScore: Float,
    val lexicalScore: Float,
    val combinedScore: Float,
    val vectorRank: Int? = null,
    val lexicalRank: Int? = null,
    val rrfScore: Float = 0f
)

private fun routeCandidateLimit(topK: Int, totalSize: Int): Int {
    if (totalSize <= 0) return 0
    return maxOf(topK * 6, 30).coerceAtMost(totalSize)
}

private fun rankChunksByMultiRoute(
    chunks: List<VectorChunk>,
    queryEmbedding: List<Float>,
    ragQuery: String,
    routeLimit: Int
): List<DocumentRank> {
    if (chunks.isEmpty() || routeLimit <= 0) return emptyList()

    val vectorScores = chunks.associateWith { chunk ->
        ChatBarApp.instance.vectorSearchEngine.cosineSimilarity(queryEmbedding, chunk.embedding)
    }
    val lexicalScores = chunks.associateWith { chunk -> chunk.lexicalScore(ragQuery) }

    val vectorRanks = vectorScores.entries
        .sortedByDescending { it.value }
        .take(routeLimit)
        .mapIndexed { index, entry -> entry.key.id to index + 1 }
        .toMap()

    val lexicalRanks = lexicalScores.entries
        .filter { it.value > 0f }
        .sortedByDescending { it.value }
        .take(routeLimit)
        .mapIndexed { index, entry -> entry.key.id to index + 1 }
        .toMap()

    val chunksById = chunks.associateBy { it.id }
    val candidateIds = (vectorRanks.keys + lexicalRanks.keys).distinct()
    return candidateIds.mapNotNull { id ->
        val chunk = chunksById[id] ?: return@mapNotNull null
        val vectorRank = vectorRanks[id]
        val lexicalRank = lexicalRanks[id]
        val vectorScore = vectorScores[chunk] ?: 0f
        val lexicalScore = lexicalScores[chunk] ?: 0f
        DocumentRank(
            chunk = chunk,
            vectorScore = vectorScore,
            lexicalScore = lexicalScore,
            combinedScore = vectorScore + lexicalScore,
            vectorRank = vectorRank,
            lexicalRank = lexicalRank,
            rrfScore = vectorRank.rrfScore() + lexicalRank.rrfScore()
        )
    }.sortedWith(
        compareByDescending<DocumentRank> { it.rrfScore }
            .thenByDescending { it.combinedScore }
    )
}

private fun Int?.rrfScore(): Float {
    return this?.let { 1f / (RRF_K + it) } ?: 0f
}

private fun List<DocumentRank>.withSourceDiversity(topK: Int): List<DocumentRank> {
    if (topK <= 0 || isEmpty()) return emptyList()
    val sourceCap = maxOf(1, (topK + 1) / 2)
    val selected = mutableListOf<DocumentRank>()
    val sourceCounts = mutableMapOf<String, Int>()

    for (rank in this) {
        val source = rank.chunk.sourceDiversityKey()
        val count = sourceCounts[source] ?: 0
        if (count < sourceCap) {
            selected += rank
            sourceCounts[source] = count + 1
            if (selected.size >= topK) return selected
        }
    }

    for (rank in this) {
        if (rank !in selected) {
            selected += rank
            if (selected.size >= topK) break
        }
    }
    return selected
}

private fun VectorChunk.sourceDiversityKey(): String {
    return metadata["sourceLabel"]
        ?.substringBefore(" > ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: metadata["fileName"]?.trim()?.takeIf { it.isNotBlank() }
        ?: metadata["originalDocId"]?.trim()?.takeIf { it.isNotBlank() }
        ?: sourceId
}

internal fun buildRagRetrievalCredentialMessages(
    contextMsgs: List<ChatMessage>,
    currentUserMessageId: String? = null,
    currentUserContent: String = ""
): List<ChatMessage> {
    val lastAssistant = contextMsgs.asReversed().firstOrNull { msg ->
        msg.role == MessageRole.ASSISTANT &&
            msg.id != currentUserMessageId &&
            msg.displayContent.isNotBlank() &&
            msg.displayContent != currentUserContent
    }
    return listOfNotNull(lastAssistant)
}

internal fun buildRagQuery(currentUserContent: String, contextMsgs: List<ChatMessage>): String {
    val lastAssistant = contextMsgs
        .lastOrNull { it.role == MessageRole.ASSISTANT && it.displayContent.isNotBlank() }

    return buildString {
        appendLine("Current user message:")
        appendLine(currentUserContent.trim().take(800))
        if (lastAssistant != null) {
            appendLine()
            appendLine("Last assistant reply:")
            val text = lastAssistant.displayContent.replace(Regex("\\s+"), " ").trim().take(600)
            if (text.isNotBlank()) {
                appendLine("assistant: $text")
            }
        }
    }.trim().take(2400)
}

private fun RetrievalPlan.toRagQuery(
    currentUserContent: String,
    contextMsgs: List<ChatMessage>
): String {
    val plannedTopics = topic
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    val plannedQueries = queries
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    val plannedEntities = entities
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    return buildString {
        if (plannedTopics.isNotEmpty()) {
            appendLine("Topic: ${plannedTopics.joinToString(", ")}")
        }
        if (plannedEntities.isNotEmpty()) {
            appendLine("Entities: ${plannedEntities.joinToString(", ")}")
        }
        if (plannedQueries.isNotEmpty()) {
            appendLine("Queries:")
            plannedQueries.take(8).forEach { appendLine(it) }
        }
        if (isBlank()) {
            appendLine(buildRagQuery(currentUserContent, contextMsgs))
        }
    }.trim().take(3200)
}

private fun RetrievalPlan.toDebugLog(): String {
    return buildString {
        appendLine("--- Retrieval Planner ---")
        appendLine("topic=$topic")
        appendLine("queries=$queries")
        appendLine("entities=$entities")
        appendLine("should_recall=$shouldRecall")
    }.trim()
}

private fun VectorChunk.contentSourceLabel(): String? {
    val firstLine = content.lineSequence().firstOrNull()?.trim().orEmpty()
    return firstLine
        .removePrefix("【来源】")
        .takeIf { it != firstLine && it.isNotBlank() }
}

private fun VectorChunk.hasMismatchedSourceLabel(): Boolean {
    val contentSource = contentSourceLabel() ?: return false
    val contentFile = contentSource.substringBefore(" > ").trim()
    val metadataFile = metadata["fileName"]?.trim()
    val metadataSource = metadata["sourceLabel"]?.substringBefore(" > ")?.trim()
    return listOfNotNull(metadataFile, metadataSource).any { it.isNotBlank() && it != contentFile }
}

private fun VectorChunk.lexicalScore(query: String): Float {
    val tokens = query.searchTokens()
    if (tokens.isEmpty()) return 0f

    val haystack = buildString {
        append(content)
        append('\n')
        metadata.values.forEach {
            append(it)
            append('\n')
        }
    }.lowercase(Locale.ROOT)

    var score = 0f
    for (token in tokens) {
        if (haystack.contains(token)) {
            score += when {
                token.length >= 5 -> 0.34f
                token.length == 4 -> 0.26f
                token.length == 3 -> 0.16f
                else -> 0.08f
            }
        }
    }
    return score.coerceAtMost(0.72f)
}

private fun String.searchTokens(): Set<String> {
    val normalized = lowercase(Locale.ROOT)
    val tokens = linkedSetOf<String>()

    Regex("[a-z0-9_!！?？-]{2,}").findAll(normalized).forEach { match ->
        tokens.add(match.value)
    }

    Regex("\\p{IsHan}+").findAll(normalized).forEach { match ->
        val run = match.value
        val maxGram = minOf(6, run.length)
        for (size in 2..maxGram) {
            for (start in 0..run.length - size) {
                val token = run.substring(start, start + size)
                if (token !in RAG_LEXICAL_STOPWORDS) {
                    tokens.add(token)
                }
            }
        }
    }

    return tokens.take(240).toSet()
}

private val RAG_LEXICAL_STOPWORDS = setOf(
    "当前", "用户", "消息", "最近", "上下", "文中", "什么", "还有", "值得", "注意",
    "一下", "一个", "这个", "那个", "哪些", "怎么", "如何", "我们", "你们",
    "他们", "她们", "关于", "提到", "讨论", "对话", "内容", "相关"
)
