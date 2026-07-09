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
import com.example.chatbar.domain.chat.ImageUnderstandingResult
import com.example.chatbar.domain.chat.LongTermMemoryUpdatePolicy
import com.example.chatbar.domain.chat.PlaceholderRenderer
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.chat.editRoleplayMessageSegment
import com.example.chatbar.domain.image.NovelAiImageEvent
import com.example.chatbar.domain.image.ImageFileEncoder
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.domain.image.NovelAiImageSize
import com.example.chatbar.domain.image.NovelAiImageSizePolicy
import com.example.chatbar.domain.image.NovelAiPromptPlan
import com.example.chatbar.domain.rag.RetrievedKnowledgeCard
import com.example.chatbar.domain.rag.RetrievalPlan
import com.example.chatbar.domain.rag.RagSourcePlan
import com.example.chatbar.domain.worldbook.WorldBookEngine
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import com.example.chatbar.domain.service.StreamingNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.UUID

enum class ImageGenerationPhase {
    DESIGNING,
    GENERATING,
    STREAMING,
    SAVING,
    CANCELLED,
    FAILED
}

data class ImageGenerationState(
    val anchorMessageId: String,
    val phase: ImageGenerationPhase,
    val previewImage: ByteArray? = null,
    val promptDraft: String = "",
    val progress: Float = 0f,
    val error: String? = null
)

val ImageGenerationState.isTerminal: Boolean
    get() = phase == ImageGenerationPhase.FAILED || phase == ImageGenerationPhase.CANCELLED

val ImageGenerationState.isCancellable: Boolean
    get() = when (phase) {
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
    private val streamingChatService = ChatBarApp.instance.streamingChatService
    private val imageUnderstandingService = ChatBarApp.instance.imageUnderstandingService
    private val modelResolver = ChatBarApp.instance.effectiveModelResolver
    private val novelAiCredentials = ChatBarApp.instance.novelAiCredentialStore
    private val novelAiPromptDesigner = ChatBarApp.instance.novelAiPromptDesigner
    private val novelAiImageService = ChatBarApp.instance.novelAiImageService
    private val novelAiImageStorage = ChatBarApp.instance.novelAiImageStorage
    private val saveSlotJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

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

    private val _isResponding = MutableStateFlow(false)
    val isResponding: StateFlow<Boolean> = _isResponding.asStateFlow()

    private val _streamingMessage = MutableStateFlow<ChatMessage?>(null)
    val streamingMessage: StateFlow<ChatMessage?> = _streamingMessage.asStateFlow()

    private val _isDeletingMemory = MutableStateFlow(false)
    val isDeletingMemory: StateFlow<Boolean> = _isDeletingMemory.asStateFlow()

    private val _chatBubbleFontScale = MutableStateFlow(1.0f)
    val chatBubbleFontScale: StateFlow<Float> = _chatBubbleFontScale.asStateFlow()

    private val _contextWindowSize = MutableStateFlow(20)
    val contextWindowSize: StateFlow<Int> = _contextWindowSize.asStateFlow()
    val novelAiConfigured: StateFlow<Boolean> = novelAiCredentials.configured

    private val _imageGeneration = MutableStateFlow<ImageGenerationState?>(null)
    val imageGeneration: StateFlow<ImageGenerationState?> = _imageGeneration.asStateFlow()

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

    private val _effectiveDefaultFormatCardId = MutableStateFlow<String?>(null)
    val effectiveDefaultFormatCardId: StateFlow<String?> = _effectiveDefaultFormatCardId.asStateFlow()

    private val _availableSaveSlots = MutableStateFlow<List<SaveSlot>>(emptyList())
    val availableSaveSlots: StateFlow<List<SaveSlot>> = _availableSaveSlots.asStateFlow()

    private var draftTouched = false
    private var draftSaveSequence = 0
    private var responseJob: Job? = null
    private var imageGenerationJob: Job? = null

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
            val settings = settingsRepository.getAppSettings()
            _contextWindowSize.value = settings.defaultContextWindowSize.coerceAtLeast(1)
            _chatBubbleFontScale.value = settings.chatBubbleFontScale
            if (s != null) {
                val card = characterRepository.getById(s.characterCardId)
                _characterCard.value = card
                _isArchived.value = card == null
                refreshMessages()
                refreshSaveSlots()
            }
        }
    }

    fun refreshConfigurations() {
        viewModelScope.launch {
            val settings = settingsRepository.getAppSettings()
            val availableModels = modelResolver.availableChatModels(settings)
            _availableModels.value = availableModels
            _effectiveDefaultModelId.value = modelResolver.resolveChatModel(null, settings)?.id
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
                _messages.value = chatRepository.getMessages(sessionId)
            } catch (_: Exception) {}
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

    fun generateNovelAiImage(anchorMessageId: String) {
        val active = _imageGeneration.value
        if (active != null && !active.isTerminal) return
        _imageGeneration.value = ImageGenerationState(anchorMessageId, ImageGenerationPhase.DESIGNING)
        val job = ChatBarApp.instance.applicationScope.launch {
            val token = novelAiCredentials.load()
            val currentSession = chatRepository.getSession(sessionId)
            val card = currentSession?.let { characterRepository.getById(it.characterCardId) }
            val settings = settingsRepository.getAppSettings()
            val model = currentSession?.let { modelResolver.resolveChatModel(it.modelId, settings) }
            val imageRatioError = NovelAiImageSizePolicy.validationError(settings.novelAiImageAspectRatio)
            val globalPlayerName = settingsRepository.getPlayerSetting()
                .playerName
                .takeIf { it.isNotBlank() }
            val playerName = currentSession?.playerName?.takeIf { it.isNotBlank() } ?: globalPlayerName
            if (token == null || card == null || model == null || model.apiKey.isBlank()) {
                val missing = mutableListOf<String>()
                if (token == null) missing.add("NovelAI Token")
                if (card == null) missing.add("角色卡")
                if (model == null || model.apiKey.isBlank()) missing.add("当前对话模型/API Key")
                _imageGeneration.value = ImageGenerationState(
                    anchorMessageId,
                    ImageGenerationPhase.FAILED,
                    error = "缺少配置：${missing.joinToString("、")} 不可用，无法生图"
                )
                return@launch
            }
            if (imageRatioError != null) {
                _imageGeneration.value = ImageGenerationState(
                    anchorMessageId,
                    ImageGenerationPhase.FAILED,
                    error = imageRatioError
                )
                return@launch
            }
            try {
                AiBackgroundWorkManager.run(sessionId) {
                val prompt = novelAiPromptDesigner.design(
                    chatRepository.getMessages(sessionId),
                    anchorMessageId,
                    card,
                    model,
                    playerName = playerName,
                    sessionId = sessionId
                ) { draft ->
                    val current = _imageGeneration.value
                    if (current?.anchorMessageId == anchorMessageId &&
                        current.phase == ImageGenerationPhase.DESIGNING
                    ) {
                        _imageGeneration.value = current.copy(promptDraft = draft)
                    }
                }
                val imageSize = NovelAiImageSizePolicy.resolve(settings.novelAiImageAspectRatio, prompt.sizePreset)
                val seed = novelAiImageService.newSeed()
                val requestBody = novelAiImageService.buildRequestBody(prompt, seed, imageSize)
                com.example.chatbar.utils.DebugLogManager.recordCompleted(
                    sessionId = sessionId,
                    modelName = "NovelAI Diffusion V4.5 Full",
                    apiUrl = "https://image.novelai.net/ai/generate-image-stream",
                    requestBodyJson = requestBody,
                    rawAiOutput = debugPrompt(prompt, imageSize)
                )
                _imageGeneration.value = ImageGenerationState(
                    anchorMessageId,
                    ImageGenerationPhase.GENERATING,
                    promptDraft = _imageGeneration.value?.promptDraft.orEmpty()
                )
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
                                    _imageGeneration.value = ImageGenerationState(
                                        anchorMessageId,
                                        ImageGenerationPhase.STREAMING,
                                        previewImage = event.image,
                                        promptDraft = _imageGeneration.value?.promptDraft.orEmpty(),
                                        progress = event.progress
                                    )
                                }
                                is NovelAiImageEvent.Final -> {
                                    _imageGeneration.value = ImageGenerationState(
                                        anchorMessageId,
                                        ImageGenerationPhase.SAVING,
                                        previewImage = event.image,
                                        promptDraft = _imageGeneration.value?.promptDraft.orEmpty(),
                                        progress = 1f
                                    )
                                    val path = withContext(Dispatchers.IO) {
                                        novelAiImageStorage.save(sessionId, event.image)
                                    }
                                    chatRepository.addMessageAfter(
                                        ChatMessage.create(
                                            sessionId = sessionId,
                                            role = MessageRole.ASSISTANT,
                                            content = "",
                                            images = listOf(path),
                                            generatedFromMessageId = anchorMessageId
                                        ),
                                        anchorMessageId
                                    )
                                    refreshMessages()
                                    _imageGeneration.value = null
                                }
                                is NovelAiImageEvent.Error -> {
                                    if (isRetryableError(event.message) && attempt < 3) {
                                        retry = true
                                        _imageGeneration.value = ImageGenerationState(
                                            anchorMessageId,
                                            ImageGenerationPhase.GENERATING,
                                            promptDraft = _imageGeneration.value?.promptDraft.orEmpty()
                                        )
                                    } else {
                                        _imageGeneration.value = ImageGenerationState(
                                            anchorMessageId,
                                            ImageGenerationPhase.FAILED,
                                            promptDraft = _imageGeneration.value?.promptDraft.orEmpty(),
                                            error = event.message
                                        )
                                    }
                                }
                            }
                        }
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        if (isRetryableError(error.message) && attempt < 3) {
                            retry = true
                            _imageGeneration.value = ImageGenerationState(
                                anchorMessageId,
                                ImageGenerationPhase.GENERATING,
                                promptDraft = _imageGeneration.value?.promptDraft.orEmpty()
                            )
                        } else {
                        _imageGeneration.value = ImageGenerationState(
                            anchorMessageId,
                            ImageGenerationPhase.FAILED,
                            previewImage = _imageGeneration.value?.previewImage,
                            promptDraft = _imageGeneration.value?.promptDraft.orEmpty(),
                            progress = _imageGeneration.value?.progress ?: 0f,
                            error = "生图失败 (网络/连接错误, 第${attempt}次尝试): ${error.message ?: "未知错误"}"
                        )
                        }
                    }
                }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _imageGeneration.value = ImageGenerationState(
                    anchorMessageId,
                    ImageGenerationPhase.FAILED,
                    previewImage = _imageGeneration.value?.previewImage,
                    promptDraft = _imageGeneration.value?.promptDraft.orEmpty(),
                    progress = _imageGeneration.value?.progress ?: 0f,
                    error = "生图失败 (提示设计阶段): ${error.message ?: "未知错误"}"
                )
            }
        }
        imageGenerationJob = job
        job.invokeOnCompletion {
            if (imageGenerationJob == job) imageGenerationJob = null
        }
    }

    private fun debugPrompt(prompt: NovelAiPromptPlan, imageSize: NovelAiImageSize): String = buildString {
        appendLine("Size: ${imageSize.label} (${imageSize.width}x${imageSize.height})")
        appendLine("Base: ${prompt.baseCaption}")
        prompt.designed?.characters?.forEach { selected ->
            appendLine("Selected ${selected.name}: ${selected.effectiveCaption}")
        }
        prompt.characterCaptions.forEachIndexed { index, caption ->
            appendLine(
                "Character ${index + 1} @ (${caption.center.x}, ${caption.center.y}): ${caption.prompt}"
            )
        }
    }

    private fun isRetryableError(message: String?): Boolean {
        val msg = message?.lowercase(Locale.ROOT) ?: return false
        return listOf("connection", "timeout", "closed", "eof", "reset", "refused", "unreachable", "network", "socket")
            .any { msg.contains(it) }
    }

    fun dismissNovelAiImageGeneration() {
        _imageGeneration.value = null
    }

    fun cancelNovelAiImageGeneration() {
        val current = _imageGeneration.value?.takeIf { it.isCancellable } ?: return
        _imageGeneration.value = current.copy(
            phase = ImageGenerationPhase.CANCELLED,
            error = null
        )
        imageGenerationJob?.cancel(CancellationException("用户停止生图"))
        imageGenerationJob = null
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
        val isBlank = content.isBlank() && imagePaths.isEmpty()
        val effectiveContent = if (isBlank) "continue" else content
        if (!isBlank && _draftInput.value.isNotEmpty()) updateDraftInput("")
        sendMessageInternal(content = effectiveContent, imagePaths = imagePaths, persistUserMessage = !isBlank)
        return true
    }

    fun cancelResponseGeneration() {
        if (!_isResponding.value) return
        ChatBarApp.instance.streamingStopRequested.value = true
        responseJob?.cancel(CancellationException("用户停止生成"))
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
            try {
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
            val effectiveContextWindowSize = effectiveContextWindowSize(currentSession, appSettings)
            val contextMsgs = contextWindowManager.getRecentMessages(allMsgs, effectiveContextWindowSize)
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
                messageCount = allMsgs.size,
                contextWindowSize = effectiveContextWindowSize
            )

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
                        val memChunks = allMemChunks.filter { chunk ->
                            chunk.messageIds().none { it in activeContextMessageIds }
                        }
                        val filteredMemCount = allMemChunks.size - memChunks.size
                        ragDebugLogs.add("RAG split retrieval: document candidates=${docChunks.size}; ignored legacy document chunks=${legacyDocChunks.size}; ignored embedding-mismatch document chunks=${mismatchedDocChunks.size}; chat_memory candidates=${allMemChunks.size}; active context messages=${activeContextMessageIds.size}; eligible chat_memory after context filter=${memChunks.size}.")
                        if (legacyDocChunks.isNotEmpty() || mismatchedDocChunks.isNotEmpty()) {
                            ragDebugLogs.add("Document RAG index uses a different or unknown embedding model. Rebuild the character card RAG index before judging recall quality.")
                        }
                        if (filteredMemCount > 0) {
                            ragDebugLogs.add("Filtered $filteredMemCount chat_memory chunks because they overlap messages already sent in the normal context window.")
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
                                chunks = memChunks,
                                queryEmbedding = queryEmbedding,
                                ragQuery = ragQuery,
                                routeLimit = routeCandidateLimit(appSettings.memoryRagTopK, memChunks.size)
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
                            
                            val finalCards = (topDocChunks.map { it.chunk } + topMemChunks.map { it.chunk })
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

                val (wbPrompt, wbOutlets, wbTimed) = buildWorldBookPrompt(charCard, currentSession, messages.value, currentSession.timedWorldInfo)
                if (wbTimed != currentSession.timedWorldInfo) {
                    val updatedSession = currentSession.copy(timedWorldInfo = wbTimed)
                    chatRepository.updateSession(updatedSession)
                    _session.value = updatedSession
                }
                var systemPrompt = promptAssembler.assembleSystemPrompt(
                    characterCard = charCard,
                    playerSetting = activePlayerSetting,
                    playerName = activePlayerName.takeIf { it.isNotBlank() },
                    supplementarySetting = currentSession.supplementarySetting?.takeIf { it.isNotBlank() },
                    formatCard = activeFormatCard,
                    ragResults = ragResults,
                    ragInjectionMode = appSettings.ragInjectionMode,
                    replyLength = currentSession.replyLength?.takeIf { it.isNotBlank() },
                    replyLanguage = currentSession.replyLanguage?.takeIf { it.isNotBlank() },
                    longTermMemory = currentSession.longTermMemory.takeIf {
                        currentSession.longTermMemoryEnabled && it.isNotBlank()
                    },
                    worldBookPrompt = wbPrompt,
                    worldBookOutlets = wbOutlets
                )

                // 6. 截取最近消息构建上下文
                // 7. 构建发送给接口的 API 消息格式
                val characterImageRefs = charCard.characters.mapNotNull { character ->
                    character.appearanceImage
                        ?.takeIf { it.isNotBlank() && File(it).exists() }
                        ?.let { character.name to it }
                }
                val characterImageBase64s = characterImageRefs.mapNotNull { (characterName, imagePath) ->
                    runCatching { characterName to encodeImageToBase64(imagePath) }.getOrNull()
                }
                val characterImageUnderstanding = if (characterImageBase64s.isNotEmpty()) {
                    imageUnderstandingService.prepare(
                        imageBase64s = characterImageBase64s.map { it.second },
                        generationModel = modelConfig,
                        requireUnderstanding = false
                    )
                } else {
                    ImageUnderstandingResult()
                }
                if (characterImageUnderstanding.descriptions.isNotEmpty()) {
                    val descriptions = characterImageBase64s.mapIndexedNotNull { index, (characterName, _) ->
                        characterImageUnderstanding.descriptions.getOrNull(index)
                            ?.substringAfter(": ", characterImageUnderstanding.descriptions[index])
                            ?.let { "$characterName: $it" }
                    }
                    if (descriptions.isNotEmpty()) {
                        systemPrompt += "\n\n【角色外观图片描述】\n" + descriptions.joinToString("\n")
                    }
                }

                val apiMessages = mutableListOf<ChatApiMessage>()
                val implicitInstruction = "（严格遵循格式要求、字数要求进行回复）"

                // 重新生成时，找到被重新生成回复所对应的那条用户消息，需将其移至最底部
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

                suspend fun addContextMessage(msg: ChatMessage) {
                    val role = msg.role.name.lowercase()
                    val text = renderSessionText(msg.displayContent)
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

                // 1. 过往消息（放在最上方，排除上一条消息和当前/重新生成触发的用户消息）
                for (msg in promptMessageGroups.historyMessages) {
                    addContextMessage(msg)
                }

                // 2. System prompt（中间位置），随后补上一条消息提升过渡权重
                apiMessages.add(ChatApiMessage.text("system", systemPrompt))
                if (characterImageUnderstanding.directImageBase64s.isNotEmpty()) {
                        val imagePrompt = buildString {
                            appendLine("以下图片来自当前角色卡的人物外观设定。请把它们视为 System Prompt 中角色设定的一部分。")
                            characterImageBase64s.forEachIndexed { index, (characterName, _) ->
                                appendLine("图片 ${index + 1}: $characterName")
                            }
                        }.trim()
                    apiMessages.add(
                        ChatApiMessage.withImages(
                            "user",
                            imagePrompt,
                            characterImageUnderstanding.directImageBase64s
                        )
                    )
                }
                val previousContextMessage = promptMessageGroups.previousMessage
                if (previousContextMessage != null) {
                    addContextMessage(previousContextMessage)
                }

                // 3. 本次用户输入（始终放在最底部，追加隐式指令）
                val currentUserContent: String?
                val currentUserImages: List<String>
                val shouldAddUserPrompt: Boolean = when {
                    persistUserMessage -> {
                        currentUserContent = renderSessionText(finalUserContent)
                        currentUserImages = userMsgImages
                        true
                    }
                    regenTargetUserMsg != null -> {
                        currentUserContent = renderSessionText(regenTargetUserMsg.displayContent)
                        currentUserImages = regenTargetUserMsg.images
                        true
                    }
                    content.isNotBlank() -> {
                        currentUserContent = renderSessionText(content)
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
                    val userPromptText = currentUserContent + implicitInstruction
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

                val ctx = ChatBarApp.instance
                _streamingMessage.value = ChatMessage(
                    id = assistantMsgId,
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT,
                    content = "...",
                    createdAt = streamStartedAt,
                    updatedAt = streamStartedAt
                )
                StreamingNotificationManager.update(ctx, "正在连接流式响应...", sessionId)

                streamingChatService.streamChat(
                    sessionId = sessionId,
                    messages = apiMessages,
                    modelConfig = modelConfig,
                    systemPrompt = systemPrompt,
                    ragChunks = ragDebugLogs
                ).collect { event ->
                    if (ChatBarApp.instance.streamingStopRequested.value) {
                        throw CancellationException("用户停止生成")
                    }
                    when (event) {
                        is StreamEvent.ReasoningDelta -> {
                            accumulatedReasoning += event.text
                            _streamingMessage.value = ChatMessage(
                                id = assistantMsgId,
                                sessionId = sessionId,
                                role = MessageRole.ASSISTANT,
                                content = renderSessionText(accumulatedText),
                                reasoningContent = accumulatedReasoning.takeIf { it.isNotEmpty() }
                                    ?.let(renderSessionText),
                                createdAt = streamStartedAt,
                                updatedAt = System.currentTimeMillis()
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
                                createdAt = streamStartedAt,
                                updatedAt = System.currentTimeMillis()
                            )
                            StreamingNotificationManager.update(ctx, renderedAccumulatedText, sessionId)
                        }
                        is StreamEvent.Error -> {
                            throw Exception(event.message)
                        }
                        is StreamEvent.Done -> {
                            ChatBarApp.instance.streamingStopRequested.value = false
                            if (accumulatedText.isNotBlank()) {
                                StreamingNotificationManager.showComplete(ctx, renderSessionText(accumulatedText))
                            }
                            // 流生成完毕，保存到数据库
                            val assistantMsg = ChatMessage(
                                id = assistantMsgId,
                                sessionId = sessionId,
                                role = MessageRole.ASSISTANT,
                                content = accumulatedText,
                                reasoningContent = accumulatedReasoning.takeIf { it.isNotEmpty() },
                                createdAt = streamStartedAt,
                                updatedAt = System.currentTimeMillis()
                            )
                            if (alternativeTargetMessageId != null) {
                                val old = chatRepository.getMessage(alternativeTargetMessageId, sessionId)
                                if (old != null) {
                                    val existingAlternatives = old.alternatives.takeIf { it.isNotEmpty() }
                                        ?: listOf(old.content)
                                    val updatedAlternatives = (existingAlternatives + accumulatedText)
                                        .takeLast(5)
                                    chatRepository.updateMessage(
                                        old.copy(
                                            content = updatedAlternatives.last(),
                                            alternatives = updatedAlternatives,
                                            currentAlternativeIndex = updatedAlternatives.lastIndex,
                                            reasoningContent = accumulatedReasoning.takeIf { it.isNotEmpty() }
                                        )
                                    )
                                } else {
                                    chatRepository.addMessage(assistantMsg)
                                }
                            } else {
                                chatRepository.addMessage(assistantMsg)
                            }

                            updateLongTermMemoryAfterReply(
                                session = currentSession,
                                modelConfig = modelConfig
                            )
                            ChatBarApp.instance.momentScheduler.kick("chat-reply")
                            
                            try {
                                _messages.value = chatRepository.getMessages(sessionId)
                            } catch (_: Exception) {}
                            _streamingMessage.value = null
                            _isResponding.value = false

                            // 对话存入 RAG 记忆库（后台异步）
                            if (embeddingConfig != null) {
                                try {
                                    indexMessagesLeavingContextWindow(
                                        contextWindowSize = effectiveContextWindowSize,
                                        embeddingConfig = embeddingConfig
                                    )
                                } catch (_: Exception) {}
                            }
                        }
                    }
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
                                content = "错误: ${e.message}"
                            )
                            chatRepository.addMessage(errorAssistantMsg)
                            try {
                                _messages.value = chatRepository.getMessages(sessionId)
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
                                _messages.value = chatRepository.getMessages(sessionId)
                            } catch (_: Exception) {}
                        } else {
                            addSystemMessage("閿欒: ${e.message}")
                        }
                    } catch (_: Exception) {}
                }
                _streamingMessage.value = null
            } finally {
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
        modelConfig: ModelConfig
    ) {
        if (!session.longTermMemoryEnabled) return
        ChatBarApp.instance.applicationScope.launch {
            try {
                AiBackgroundWorkManager.run(sessionId) {
                val latestSession = chatRepository.getSession(sessionId) ?: return@run
                if (!latestSession.longTermMemoryEnabled) return@run
                val candidate = LongTermMemoryUpdatePolicy.nextCandidate(
                    messages = chatRepository.getMessages(sessionId),
                    updatedThroughMessageId = latestSession.longTermMemoryUpdatedThroughMessageId
                ) ?: return@run
                val prompt = PromptTemplates.longTermMemoryUpdatePrompt(
                    currentMemory = latestSession.longTermMemory,
                    userContent = candidate.userContent,
                    assistantContent = candidate.assistantContent
                )
                val updatedMemory = streamingChatService.completeText(
                    messages = listOf(ChatApiMessage.text("user", prompt)),
                    modelConfig = modelConfig,
                    maxTokens = 10000,
                    thinkingBudget = 512
                ).trim()
                val current = chatRepository.getSession(sessionId) ?: return@run
                if (current.longTermMemoryEnabled) {
                    val updatedSession = current.copy(
                        longTermMemory = updatedMemory.ifBlank { current.longTermMemory },
                        longTermMemoryUpdatedThroughMessageId = candidate.assistantMessageId
                    )
                    chatRepository.updateSession(updatedSession)
                    _session.value = updatedSession
                }
                }
            } catch (_: Exception) {
                // Memory update is best-effort and must not block chat completion.
            }
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            val contextWindowSize = effectiveContextWindowSize()
            val currentMessages = chatRepository.getMessages(sessionId)
            val isInActiveContext = currentMessages
                .takeLast(contextWindowSize)
                .any { it.id == messageId }

            currentMessages.firstOrNull { it.id == messageId }
                ?.images
                ?.forEach { deleteDisposableChatImage(it) }
            _messages.value = _messages.value.filter { it.id != messageId }
            chatRepository.deleteMessage(messageId, sessionId)
            refreshMessages()
            if (isInActiveContext) {
                return@launch
            }

            _isDeletingMemory.value = true
            yield()
            try {
                ragManager.deleteMemoryForMessage(messageId)
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
            } else {
                chatRepository.updateMessage(message.copy(images = remaining))
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
            alternatives = emptyList(),
            currentAlternativeIndex = 0,
            updatedAt = System.currentTimeMillis()
        )

        if (deleteRemovedImages) {
            oldMessage.images.filterNot(imagePaths::contains).forEach { deleteDisposableChatImage(it) }
        }

        chatRepository.updateMessage(updatedMessage)
        refreshMessages()

        if (deleteMemoryWhenTextBlank) {
            ragManager.deleteMemoryForMessage(oldMessage.id)
            return
        }

        refreshMemoryAfterMessageEdit(updatedMessage)
    }

    private suspend fun refreshMemoryAfterMessageEdit(updatedMessage: ChatMessage) {
        val messageId = updatedMessage.id
        val isInActiveContext = chatRepository.getMessages(sessionId)
            .takeLast(effectiveContextWindowSize())
            .any { it.id == messageId }
        if (isInActiveContext) {
            return
        }

        val memoryChunks = ChatBarApp.instance.ragRepository.getChunksByMessageId(messageId)
        if (memoryChunks.isNotEmpty()) {
            val embeddingConfig = modelResolver.embeddingModel(settingsRepository.getAppSettings())
            if (embeddingConfig != null) {
                try {
                    val allMessages = chatRepository.getMessages(sessionId)
                    val messageIndex = allMessages.indexOfFirst { it.id == updatedMessage.id }
                    val contextStart = (messageIndex - 4).coerceAtLeast(0)
                    val contextMessages = if (messageIndex >= 0) {
                        allMessages.subList(contextStart, messageIndex)
                    } else {
                        emptyList()
                    }
                    val renderContext = placeholderRenderContext()
                    AiBackgroundWorkManager.run(sessionId) {
                        ragManager.indexSingleMessageMemory(
                            updatedMessage.renderWith(renderContext),
                            contextMessages.renderWith(renderContext),
                            sessionId,
                            embeddingConfig
                        )
                    }
                } catch (_: Exception) {
                    ragManager.deleteMemoryForMessage(messageId)
                }
            } else {
                ragManager.deleteMemoryForMessage(messageId)
            }
        }
    }

    fun switchAssistantAlternative(messageId: String, direction: Int) {
        viewModelScope.launch {
            val message = _messages.value.find { it.id == messageId }
                ?: chatRepository.getMessage(messageId, sessionId)
                ?: return@launch
            if (message.role != MessageRole.ASSISTANT || message.alternatives.size <= 1) return@launch
            val isRecent = _messages.value.takeLast(effectiveContextWindowSize()).any { it.id == messageId }
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
        return messages.takeLast(contextWindowSize).any { it.id == messageId }
    }

    private suspend fun indexMessagesLeavingContextWindow(
        contextWindowSize: Int,
        embeddingConfig: EmbeddingConfig
    ) {
        val allMessages = chatRepository.getMessages(sessionId)
        if (allMessages.size <= contextWindowSize) return
        val activeIds = allMessages.takeLast(contextWindowSize).map { it.id }.toSet()
        val existingMemoryIds = ChatBarApp.instance.ragRepository
            .getAllChunksForSession(sessionId)
            .flatMap { it.messageIds() }
            .toSet()
        val toIndex = allMessages
            .filter { it.role != MessageRole.SYSTEM }
            .filter { it.id !in activeIds }
            .filter { it.id !in existingMemoryIds }
            .takeLast(4)

        val renderContext = placeholderRenderContext()
        for (message in toIndex) {
            val messageIndex = allMessages.indexOfFirst { it.id == message.id }
            val contextStart = (messageIndex - 4).coerceAtLeast(0)
            val contextMessages = allMessages.subList(contextStart, messageIndex)
            ragManager.indexSingleMessageMemory(
                message = message.renderWith(renderContext),
                contextMessages = contextMessages.renderWith(renderContext),
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
            
            // 3. 重置 session 预览信息
            chatRepository.getSession(sessionId)?.let { session ->
                val resetSession = session.copy(
                    longTermMemory = "",
                    longTermMemoryUpdatedThroughMessageId = null,
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

    /**
     * 重新生成上一条 AI 的回复
     */
    fun regenerateLastResponse() {
        if (_isResponding.value || _isArchived.value) return

        viewModelScope.launch {
            val currentMessages = chatRepository.getMessages(sessionId)
            val lastMsg = currentMessages.lastOrNull() ?: return@launch
            if (lastMsg.role != MessageRole.ASSISTANT) return@launch

            _isResponding.value = true
            _messages.value = currentMessages.filterNot { it.id == lastMsg.id }
            _streamingMessage.value = null

            if (!isMessageInActiveContextWindow(lastMsg.id)) {
                _streamingMessage.value = null
                _isResponding.value = false
                _messages.value = currentMessages
                addSystemMessage("This reply is already in long-term memory. Multi-reply regeneration is disabled.")
                return@launch
            }

            sendMessageInternal(
                content = "",
                imagePaths = emptyList(),
                persistUserMessage = false,
                alternativeTargetMessageId = lastMsg.id,
                respondingAlreadyStarted = true
            )
            return@launch

            // 先删除可见消息并立即进入重新生成；RAG 记忆清理放后台，避免阻塞 UI。
            chatRepository.deleteMessage(lastMsg.id, sessionId)

            // 查找上一条用户消息作为触发词重新发消息
            val lastUserMsg = chatRepository.getMessages(sessionId).lastOrNull { it.role == MessageRole.USER }
            if (lastUserMsg != null) {
                refreshMessages()

                ChatBarApp.instance.applicationScope.launch {
                    ragManager.deleteMemoryForMessage(lastMsg.id)
                }

                sendMessageInternal(
                    content = lastUserMsg.content,
                    imagePaths = lastUserMsg.images,
                    persistUserMessage = false
                )
            } else {
                refreshMessages()
                ChatBarApp.instance.applicationScope.launch {
                    ragManager.deleteMemoryForMessage(lastMsg.id)
                }
            }
        }
    }

    /**
     * 保存会话特定的配置参数
     */
    fun updateSessionConfig(
        modelId: String?,
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
                val updated = s.copy(
                    modelId = modelId,
                    formatCardId = formatCardId,
                    replyLength = replyLength,
                    replyLanguage = replyLanguage?.takeIf { it.isNotBlank() },
                    supplementarySetting = supplementarySetting?.takeIf { it.isNotBlank() },
                    playerName = playerName?.takeIf { it.isNotBlank() },
                    playerSetting = playerSetting?.takeIf { it.isNotBlank() },
                    chatBackground = chatBackground?.takeIf { it.isNotBlank() },
                    longTermMemoryEnabled = longTermMemoryEnabled,
                    longTermMemory = longTermMemory,
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
            val messagesList = chatRepository.getMessages(sessionId)
            val packaged = packageSaveSlotImages(curSession, messagesList)
            
            // 获取当前的向量记忆库快照
            val vectorChunks = ChatBarApp.instance.ragRepository.getAllChunksForSession(sessionId)

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
                formatCardId = curSession.formatCardId,
                replyLength = curSession.replyLength,
                replyLanguage = curSession.replyLanguage,
                roleplayStyle = curSession.roleplayStyle,
                chatBackground = packaged.chatBackground,
                longTermMemoryEnabled = curSession.longTermMemoryEnabled,
                longTermMemory = curSession.longTermMemory,
                longTermMemoryUpdatedThroughMessageId = curSession.longTermMemoryUpdatedThroughMessageId,
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
    fun loadSaveSlot(slot: SaveSlot) {
        viewModelScope.launch {
            val curSession = _session.value ?: return@launch
            val materializedSlot = materializeSaveSlotImages(slot)
            val preservedImages = materializedSlot.messages
                .flatMap { it.images }
                .toSet() + listOfNotNull(materializedSlot.chatBackground)
            
            // 1. 清空当前对话所有的消息和向量块
            val currentMsgs = chatRepository.getMessages(sessionId)
            currentMsgs.forEach { message ->
                message.images
                    .filterNot { it in preservedImages }
                    .forEach { deleteDisposableChatImage(it) }
                chatRepository.deleteMessage(message.id, sessionId)
            }
            curSession.chatBackground
                ?.takeIf { it !in preservedImages }
                ?.let { deleteDisposableChatImage(it) }
            ChatBarApp.instance.ragRepository.deleteChunksBySource(ChunkSourceType.CHAT_MEMORY, sessionId)

            // 2. 写入存档里的消息
            materializedSlot.messages.forEach { msg ->
                chatRepository.addMessage(msg.copy(sessionId = sessionId))
            }

            // 3. 写入存档里的记忆向量块
            val updatedChunks = materializedSlot.vectorChunks.map { it.copy(sourceId = sessionId) }
            ChatBarApp.instance.ragRepository.saveChunks(updatedChunks)

            // 4. 恢复设定覆盖
            val latest = materializedSlot.messages.lastOrNull()
            val restoredSession = curSession.copy(
                playerSetting = materializedSlot.playerSetting,
                playerName = materializedSlot.playerName,
                supplementarySetting = materializedSlot.supplementarySetting,
                modelId = materializedSlot.modelId,
                formatCardId = materializedSlot.formatCardId,
                replyLength = materializedSlot.replyLength,
                replyLanguage = materializedSlot.replyLanguage,
                roleplayStyle = materializedSlot.roleplayStyle,
                chatBackground = materializedSlot.chatBackground,
                longTermMemoryEnabled = materializedSlot.longTermMemoryEnabled,
                longTermMemory = materializedSlot.longTermMemory,
                longTermMemoryUpdatedThroughMessageId = materializedSlot.longTermMemoryUpdatedThroughMessageId,
                contextWindowSize = materializedSlot.contextWindowSize ?: curSession.contextWindowSize,
                extraWorldBookIds = materializedSlot.extraWorldBookIds,
                timedWorldInfo = materializedSlot.timedWorldInfo,
                lastMessagePreview = latest?.saveSlotPreviewText(),
                lastMessageTime = latest?.createdAt,
                lastMessageRole = latest?.role
            )
            chatRepository.updateSession(restoredSession)
            
            // 5. 重新加载界面数据
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

    suspend fun exportSaveSlotJson(slotId: String): String = withContext(Dispatchers.IO) {
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
        saveSlotJson.encodeToString(SaveSlot.serializer(), portable)
    }

    suspend fun importSaveSlotJson(rawJson: String): SaveSlot {
        val decoded = withContext(Dispatchers.IO) {
            saveSlotJson.decodeFromString(SaveSlot.serializer(), rawJson)
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
        require(slot.schemaVersion in 1..2) { "不支持的存档 schemaVersion：${slot.schemaVersion}" }
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
