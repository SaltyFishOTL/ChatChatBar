package com.example.chatbar.ui.chat

import android.net.Uri
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.image.NovelAiImageEvent
import com.example.chatbar.domain.image.NovelAiPromptPlan
import com.example.chatbar.domain.rag.RetrievedKnowledgeCard
import com.example.chatbar.domain.rag.RetrievalPlan
import com.example.chatbar.domain.rag.RagSourcePlan
import com.example.chatbar.domain.worldbook.WorldBookEngine
import com.example.chatbar.domain.service.StreamingForegroundService
import com.example.chatbar.domain.service.StreamingNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

enum class ImageGenerationPhase {
    DESIGNING,
    GENERATING,
    STREAMING,
    SAVING,
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

/**
 * 聊天会话 ViewModel
 */
class ChatViewModel(private val sessionId: String) : ViewModel() {

    private val chatRepository = ChatBarApp.instance.chatRepository
    private val characterRepository = ChatBarApp.instance.characterRepository
    private val modelRepository = ChatBarApp.instance.modelRepository
    private val formatCardRepository = ChatBarApp.instance.formatCardRepository
    private val saveSlotRepository = ChatBarApp.instance.saveSlotRepository
    private val settingsRepository = ChatBarApp.instance.settingsRepository
    private val ragManager = ChatBarApp.instance.ragManager
    private val retrievalPlanner = ChatBarApp.instance.retrievalPlanner
    private val promptAssembler = ChatBarApp.instance.promptAssembler
    private val contextWindowManager = ChatBarApp.instance.contextWindowManager
    private val streamingChatService = ChatBarApp.instance.streamingChatService
    private val modelResolver = ChatBarApp.instance.effectiveModelResolver
    private val novelAiCredentials = ChatBarApp.instance.novelAiCredentialStore
    private val novelAiPromptDesigner = ChatBarApp.instance.novelAiPromptDesigner
    private val novelAiImageService = ChatBarApp.instance.novelAiImageService
    private val novelAiImageStorage = ChatBarApp.instance.novelAiImageStorage

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

    private val _availableSaveSlots = MutableStateFlow<List<SaveSlot>>(emptyList())
    val availableSaveSlots: StateFlow<List<SaveSlot>> = _availableSaveSlots.asStateFlow()

    init {
        loadSessionData()
        refreshConfigurations()
        observeSessionChanges()
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

    fun loadSessionData() {
        viewModelScope.launch {
            val s = chatRepository.getSession(sessionId)
            _session.value = s
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
            _availableModels.value = modelResolver.availableChatModels(settings)
            val status = modelResolver.status(settings)
            _modelConfigurationErrors.value = status.errors
            _isModelUsable.value = status.isUsable
            _availableFormats.value = formatCardRepository.getAll()
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

    fun generateNovelAiImage(anchorMessageId: String) {
        val active = _imageGeneration.value
        if (active != null && active.phase != ImageGenerationPhase.FAILED) return
        viewModelScope.launch {
            val token = novelAiCredentials.load()
            val currentSession = chatRepository.getSession(sessionId)
            val card = currentSession?.let { characterRepository.getById(it.characterCardId) }
            val settings = settingsRepository.getAppSettings()
            val model = currentSession?.let { modelResolver.resolveChatModel(it.modelId, settings) }
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
            try {
                _imageGeneration.value = ImageGenerationState(anchorMessageId, ImageGenerationPhase.DESIGNING)
                val prompt = novelAiPromptDesigner.design(
                    chatRepository.getMessages(sessionId),
                    anchorMessageId,
                    card,
                    model,
                    sessionId = sessionId
                ) { draft ->
                    val current = _imageGeneration.value
                    if (current?.anchorMessageId == anchorMessageId &&
                        current.phase == ImageGenerationPhase.DESIGNING
                    ) {
                        _imageGeneration.value = current.copy(promptDraft = draft)
                    }
                }
                val seed = novelAiImageService.newSeed()
                val requestBody = novelAiImageService.buildRequestBody(prompt, seed)
                com.example.chatbar.utils.DebugLogManager.recordCompleted(
                    sessionId = sessionId,
                    modelName = "NovelAI Diffusion V4.5 Full",
                    apiUrl = "https://image.novelai.net/ai/generate-image-stream",
                    requestBodyJson = requestBody,
                    rawAiOutput = debugPrompt(prompt)
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
                        novelAiImageService.generate(token, prompt, currentSeed).collect { event ->
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
                                    chatRepository.addMessage(
                                        ChatMessage.create(
                                            sessionId = sessionId,
                                            role = MessageRole.ASSISTANT,
                                            content = "",
                                            images = listOf(path)
                                        )
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
    }

    private fun debugPrompt(prompt: NovelAiPromptPlan): String = buildString {
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
        messages: List<ChatMessage>,
        previousTimed: Map<String, com.example.chatbar.data.local.entity.TimedEffectState>
    ): Triple<String?, Map<String, String>, Map<String, com.example.chatbar.data.local.entity.TimedEffectState>> {
        val engine = ChatBarApp.instance.worldBookEngine
        val worldBooks = mutableListOf<com.example.chatbar.data.local.entity.WorldBook>()

        card.characterBook?.let { worldBooks += it }
        card.boundWorldBookId?.let { id ->
            ChatBarApp.instance.worldBookRepository.getById(id)?.let { worldBooks += it }
        }

        if (worldBooks.isEmpty()) return Triple(null, emptyMap(), emptyMap())

        val tokens = mutableSetOf(card.name.lowercase())
        card.characters.mapTo(tokens) { it.name.lowercase() }
        if (card.editMode == com.example.chatbar.data.local.entity.CharacterEditMode.FREEFORM) {
            Regex("【角色名称】\\s*\\n?\\s*(\\S+)").findAll(card.freeformCharacterText)
                .mapTo(tokens) { it.groupValues[1].lowercase().trim() }
        }

        val timedStates = previousTimed.mapValues { (_, v) ->
            WorldBookEngine.TimedState(v.entryId, v.stickyUntil, v.cooldownUntil)
        }
        val bookTimedStates = worldBooks.associate { it.id to timedStates }
        val activated = engine.evaluateAll(worldBooks, messages.takeLast(20),
            messageCount = messages.size, characterTokens = tokens, timedStates = bookTimedStates)
        val before = activated.filter { it.entry.position == com.example.chatbar.data.local.entity.WorldBookPosition.BEFORE_CHAR }
        val after = activated.filter { it.entry.position == com.example.chatbar.data.local.entity.WorldBookPosition.AFTER_CHAR }
        val allEntries = before + after
        val outlets = engine.collectOutlets(activated)

        val prompt = if (allEntries.isEmpty()) null else {
            val playerName = ChatBarApp.instance.settingsRepository.getPlayerSetting().playerName.takeIf { it.isNotBlank() }
            engine.buildWorldBookPrompt(allEntries, card.name, playerName)
        }

        // Compute new timed states from activated entries
        val activatedIds = activated.map { it.entry.id }.toSet()
        val entryMap = worldBooks.flatMap { it.entries }.associateBy { it.id }
        val newTimed = engine.computeTimedStates(timedStates, activatedIds, entryMap, messages.size)
            .mapValues { (_, v) -> com.example.chatbar.data.local.entity.TimedEffectState(v.entryId, v.stickyUntil, v.cooldownUntil) }

        return Triple(prompt, outlets, newTimed)
    }

    /**
     * 发送文本及图片
     */
    fun sendMessage(content: String, imagePaths: List<String> = emptyList()) {
        if (_isArchived.value || !_isModelUsable.value) return
        val isBlank = content.isBlank() && imagePaths.isEmpty()
        val effectiveContent = if (isBlank) "continue" else content
        sendMessageInternal(content = effectiveContent, imagePaths = imagePaths, persistUserMessage = !isBlank)
    }

    private fun sendMessageInternal(
        content: String,
        imagePaths: List<String> = emptyList(),
        persistUserMessage: Boolean,
        alternativeTargetMessageId: String? = null,
        respondingAlreadyStarted: Boolean = false
    ) {
        if (_isResponding.value && !respondingAlreadyStarted) return

        viewModelScope.launch {
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
            val embeddingConfig = modelResolver.embeddingModel(appSettings)

            // 2. 多模态图片处理 (如果是纯文本模型但附带了图片，则先调用视觉模型生成图片描述)
            var finalUserContent = content
            val userMsgImages = mutableListOf<String>()

            if (imagePaths.isNotEmpty()) {
                userMsgImages.addAll(imagePaths)
                
                if (!modelConfig.isMultimodal) {
                    // 纯文本模型且有图片，需要调用视觉模型转描述
                    val visionModelConfig = modelResolver.auxiliaryChatModel(modelConfig.visionModelId, appSettings)
                    
                    if (visionModelConfig != null && visionModelConfig.isMultimodal) {
                        addSystemMessage("正在使用视觉模型 [${visionModelConfig.displayName}] 解析图片...")
                        try {
                            val base64 = encodeImageToBase64(imagePaths.first())
                            val description = streamingChatService.describeImage(base64, visionModelConfig)
                            finalUserContent += "\n[用户附图描述: $description]"
                        } catch (e: Exception) {
                            addSystemMessage("图片解析失败: ${e.message}。将作为无图消息发送。")
                        }
                    } else {
                        addSystemMessage("当前模型不支持多模态，且未配置关联的视觉模型，图片描述功能失效。")
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
                                currentUserContent = content,
                                contextMessages = contextMsgs,
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
                            val ragQuery = retrievalPlan?.toRagQuery(content, contextMsgs) ?: buildRagQuery(content, contextMsgs)
                            val queryEmbedding = ChatBarApp.instance.embeddingService.getEmbedding(ragQuery, embeddingConfig)
                            ragDebugLogs.add("RAG query text (${ragQuery.length} chars):\n${ragQuery.take(1200)}")
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
                val playerSettingObj = settingsRepository.getPlayerSetting()
                val activePlayerSetting = currentSession.playerSetting?.takeIf { it.isNotBlank() }
                    ?: playerSettingObj.globalPersona
                val activePlayerName = currentSession.playerName?.takeIf { it.isNotBlank() }
                    ?: playerSettingObj.playerName
                val activeFormatId = currentSession.formatCardId ?: appSettings.defaultFormatCardId
                val activeFormatCard = activeFormatId?.let { formatCardRepository.getById(it) }

                val (wbPrompt, wbOutlets, wbTimed) = buildWorldBookPrompt(charCard, messages.value, currentSession.timedWorldInfo)
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
                if (characterImageRefs.isNotEmpty() && !modelConfig.isMultimodal) {
                    val visionModelConfig = modelResolver.auxiliaryChatModel(modelConfig.visionModelId, appSettings)
                    if (visionModelConfig != null && visionModelConfig.isMultimodal) {
                        val descriptions = characterImageRefs.mapNotNull { (characterName, imagePath) ->
                            runCatching {
                                "$characterName: " + streamingChatService.describeImage(
                                    encodeImageToBase64(imagePath),
                                    visionModelConfig
                                )
                            }.getOrNull()
                        }
                        if (descriptions.isNotEmpty()) {
                            systemPrompt += "\n\n【角色外观图片描述】\n" + descriptions.joinToString("\n")
                        }
                    }
                }

                val apiMessages = mutableListOf<ChatApiMessage>()
                val implicitInstruction = "（严格遵循格式要求、字数要求进行回复）"

                // 重新生成时，找到被重新生成回复所对应的那条用户消息，需将其移至最底部
                val regenTargetUserMsg = if (alternativeTargetMessageId != null) {
                    contextMsgs.lastOrNull { it.role == MessageRole.USER }
                } else null

                // 1. 过往消息（放在最上方，排除当前/重新生成触发的用户消息）
                val historyMsgs = when {
                    persistUserMessage -> contextMsgs.filterNot { it.id == userMsg.id }
                    regenTargetUserMsg != null -> contextMsgs.filterNot { it.id == regenTargetUserMsg.id }
                    else -> contextMsgs
                }
                for (msg in historyMsgs) {
                    val role = msg.role.name.lowercase()
                    val text = msg.displayContent
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

                // 2. System prompt（中间位置）
                apiMessages.add(ChatApiMessage.text("system", systemPrompt))
                if (characterImageRefs.isNotEmpty() && modelConfig.isMultimodal) {
                    val imageBase64s = characterImageRefs.mapNotNull { (_, imagePath) ->
                        runCatching { encodeImageToBase64(imagePath) }.getOrNull()
                    }
                    if (imageBase64s.isNotEmpty()) {
                        val imagePrompt = buildString {
                            appendLine("以下图片来自当前角色卡的人物外观设定。请把它们视为 System Prompt 中角色设定的一部分。")
                            characterImageRefs.forEachIndexed { index, (characterName, _) ->
                                appendLine("图片 ${index + 1}: $characterName")
                            }
                        }.trim()
                        apiMessages.add(ChatApiMessage.withImages("user", imagePrompt, imageBase64s))
                    }
                }

                // 3. 本次用户输入（始终放在最底部，追加隐式指令）
                val currentUserContent: String?
                val currentUserImages: List<String>
                val shouldAddUserPrompt: Boolean = when {
                    persistUserMessage -> {
                        currentUserContent = finalUserContent
                        currentUserImages = userMsgImages
                        true
                    }
                    regenTargetUserMsg != null -> {
                        currentUserContent = regenTargetUserMsg.displayContent
                        currentUserImages = regenTargetUserMsg.images
                        true
                    }
                    content.isNotBlank() -> {
                        currentUserContent = content
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

                val ctx = ChatBarApp.instance
                StreamingNotificationManager.show(ctx, sessionId)
                ctx.startForegroundService(Intent(ctx, StreamingForegroundService::class.java).apply {
                    putExtra("sessionId", sessionId)
                })
                checkBatteryOptimization()

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
                                content = accumulatedText,
                                reasoningContent = accumulatedReasoning.takeIf { it.isNotEmpty() },
                                createdAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                        is StreamEvent.Delta -> {
                            accumulatedText += event.text
                            _streamingMessage.value = ChatMessage(
                                id = assistantMsgId,
                                sessionId = sessionId,
                                role = MessageRole.ASSISTANT,
                                content = accumulatedText,
                                reasoningContent = accumulatedReasoning.takeIf { it.isNotEmpty() },
                                createdAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis()
                            )
                            StreamingNotificationManager.update(ctx, accumulatedText, sessionId)
                        }
                        is StreamEvent.Error -> {
                            throw Exception(event.message)
                        }
                        is StreamEvent.Done -> {
                            ctx.stopService(Intent(ctx, StreamingForegroundService::class.java))
                            StreamingNotificationManager.cancel(ctx)
                            ChatBarApp.instance.streamingStopRequested.value = false
                            if (accumulatedText.isNotBlank()) {
                                StreamingNotificationManager.showComplete(ctx, accumulatedText)
                            }
                            // 流生成完毕，保存到数据库
                            val assistantMsg = ChatMessage(
                                id = assistantMsgId,
                                sessionId = sessionId,
                                role = MessageRole.ASSISTANT,
                                content = accumulatedText,
                                reasoningContent = accumulatedReasoning.takeIf { it.isNotEmpty() },
                                createdAt = System.currentTimeMillis(),
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
                try {
                    ChatBarApp.instance.stopService(Intent(ChatBarApp.instance, StreamingForegroundService::class.java))
                } catch (_: Exception) {}
                try {
                    StreamingNotificationManager.cancel(ChatBarApp.instance)
                } catch (_: Exception) {}
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
                try {
                    ChatBarApp.instance.stopService(Intent(ChatBarApp.instance, StreamingForegroundService::class.java))
                } catch (_: Exception) {}
                try {
                    StreamingNotificationManager.cancel(ChatBarApp.instance)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * 删除特定消息
     */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            val contextWindowSize = effectiveContextWindowSize()
            val currentMessages = chatRepository.getMessages(sessionId)
            val isInActiveContext = currentMessages
                .takeLast(contextWindowSize)
                .any { it.id == messageId }

            currentMessages.firstOrNull { it.id == messageId }
                ?.images
                ?.forEach(novelAiImageStorage::deleteIfOwned)
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
            novelAiImageStorage.deleteIfOwned(imagePath)
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

            val updatedMessage = oldMessage.copy(
                content = content,
                images = imagePaths,
                alternatives = emptyList(),
                currentAlternativeIndex = 0,
                updatedAt = System.currentTimeMillis()
            )

            oldMessage.images.filterNot(imagePaths::contains).forEach(novelAiImageStorage::deleteIfOwned)

            chatRepository.updateMessage(updatedMessage)
            refreshMessages()

            val isInActiveContext = chatRepository.getMessages(sessionId)
                .takeLast(effectiveContextWindowSize())
                .any { it.id == messageId }
            if (isInActiveContext) {
                return@launch
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
                        ragManager.indexSingleMessageMemory(updatedMessage, contextMessages, sessionId, embeddingConfig)
                    } catch (_: Exception) {
                        ragManager.deleteMemoryForMessage(messageId)
                    }
                } else {
                    ragManager.deleteMemoryForMessage(messageId)
                }
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

        for (message in toIndex) {
            val messageIndex = allMessages.indexOfFirst { it.id == message.id }
            val contextStart = (messageIndex - 4).coerceAtLeast(0)
            val contextMessages = allMessages.subList(contextStart, messageIndex)
            ragManager.indexSingleMessageMemory(
                message = message,
                contextMessages = contextMessages,
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
                message.images.forEach(novelAiImageStorage::deleteIfOwned)
                chatRepository.deleteMessage(message.id, sessionId)
            }
            
            // 2. 清空 RAG 向量库对应的 CHAT_MEMORY 类型
            ChatBarApp.instance.ragRepository.deleteChunksBySource(ChunkSourceType.CHAT_MEMORY, sessionId)
            
            // 3. 重置 session 预览信息
            _session.value?.let { session ->
                chatRepository.updateSession(session.copy(
                    lastMessagePreview = null,
                    lastMessageTime = null
                ))
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
        chatBackground: String?
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
                    chatBackground = chatBackground?.takeIf { it.isNotBlank() }
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
            
            // 获取当前的向量记忆库快照
            val vectorChunks = ChatBarApp.instance.ragRepository.getAllChunksForSession(sessionId)

            val saveSlot = SaveSlot.create(
                sessionId = sessionId,
                name = name,
                description = description,
                messages = messagesList,
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
                chatBackground = curSession.chatBackground,
                contextWindowSize = curSession.contextWindowSize
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
            
            // 1. 清空当前对话所有的消息和向量块
            val currentMsgs = chatRepository.getMessages(sessionId)
            currentMsgs.forEach { chatRepository.deleteMessage(it.id, sessionId) }
            ChatBarApp.instance.ragRepository.deleteChunksBySource(ChunkSourceType.CHAT_MEMORY, sessionId)

            // 2. 写入存档里的消息
            slot.messages.forEach { msg ->
                chatRepository.addMessage(msg.copy(sessionId = sessionId))
            }

            // 3. 写入存档里的记忆向量块
            val updatedChunks = slot.vectorChunks.map { it.copy(sourceId = sessionId) }
            ChatBarApp.instance.ragRepository.saveChunks(updatedChunks)

            // 4. 恢复设定覆盖
            val restoredSession = curSession.copy(
                playerSetting = slot.playerSetting,
                playerName = slot.playerName,
                supplementarySetting = slot.supplementarySetting,
                modelId = slot.modelId,
                formatCardId = slot.formatCardId,
                replyLength = slot.replyLength,
                replyLanguage = slot.replyLanguage,
                roleplayStyle = slot.roleplayStyle,
                chatBackground = slot.chatBackground,
                contextWindowSize = slot.contextWindowSize ?: curSession.contextWindowSize,
                lastMessagePreview = slot.messages.lastOrNull()?.content?.take(100),
                lastMessageTime = slot.messages.lastOrNull()?.createdAt
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

    /**
     * 重建当前角色卡的 RAG 索引
     * @return 状态信息，供 UI 展示
     */
    suspend fun rebuildRagIndex(): String = withContext(Dispatchers.IO) {
        val charCard = _characterCard.value ?: return@withContext "错误：未加载角色卡"
        if (charCard.customDocuments.isEmpty()) {
            ChatBarApp.instance.ragRepository.deleteChunksBySource(ChunkSourceType.DOCUMENT, charCard.id)
            return@withContext "无参考文档，已跳过文档 RAG。"
        }
        val appSettings = settingsRepository.getAppSettings()
        val embeddingConfig = modelResolver.embeddingModel(appSettings)
            ?: return@withContext "错误：当前配置层级没有可用向量模型"
        
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

    private suspend fun addSystemMessage(text: String) {
        val sysMsg = ChatMessage.create(
            sessionId = sessionId,
            role = MessageRole.SYSTEM,
            content = text
        )
        chatRepository.addMessage(sysMsg)
        _messages.value = _messages.value + sysMsg
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
        val file = File(path)
        if (!file.exists()) throw IllegalArgumentException("图片文件不存在: $path")
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
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

private fun buildRagQuery(currentUserContent: String, contextMsgs: List<ChatMessage>): String {
    val recentUserContext = contextMsgs
        .dropLastWhile { it.role == MessageRole.USER && it.content == currentUserContent }
        .takeLast(6)
        .filter { it.role == MessageRole.USER }

    return buildString {
        appendLine("Current user message:")
        appendLine(currentUserContent.trim().take(800))
        if (recentUserContext.isNotEmpty()) {
            appendLine()
            appendLine("Recent user context:")
            recentUserContext.forEach { msg ->
                val text = msg.displayContent.replace(Regex("\\s+"), " ").trim().take(300)
                if (text.isNotBlank()) {
                    appendLine("user: $text")
                }
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
