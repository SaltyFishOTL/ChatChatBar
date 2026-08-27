package com.example.chatbar.ui.imageprompt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.image.NovelAiDesignConversation
import com.example.chatbar.domain.image.NovelAiDesignReply
import com.example.chatbar.domain.image.NovelAiDesignResearchSnapshot
import com.example.chatbar.domain.image.NovelAiDesignTurn
import com.example.chatbar.domain.image.NovelAiDesignTurnStatus
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiStudioDraft
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NovelAiDesignUiState(
    val initialized: Boolean = false,
    val conversation: NovelAiDesignConversation? = null,
    val composingNew: Boolean = true,
    val input: String = "",
    val draft: NovelAiStudioDraft = NovelAiStudioDraft(),
    val models: List<ModelConfig> = emptyList(),
    val selectedDesignModelId: String? = null,
    val modelError: String? = null,
    val generatingTurnId: String? = null,
    val progressText: String = "",
    val reasoningText: String = "",
    val applyingReplyKey: String? = null,
    val appliedReplyKey: String? = null,
    val notice: String? = null,
    val error: String? = null
) {
    val isGenerating: Boolean get() = generatingTurnId != null
    val canSend: Boolean get() = initialized && !isGenerating && modelError == null &&
        selectedDesignModelId != null && input.isNotBlank() &&
        conversation?.hasBlockingTurn != true
}

class NovelAiDesignViewModel : ViewModel() {
    private val app = ChatBarApp.instance
    private val conversationRepository = app.novelAiDesignConversationRepository
    private val studioRepository = app.novelAiStudioRepository
    private val settingsRepository = app.settingsRepository
    private val modelResolver = app.effectiveModelResolver
    private val promptDesigner = app.novelAiPromptDesigner

    private val _uiState = MutableStateFlow(NovelAiDesignUiState())
    val uiState: StateFlow<NovelAiDesignUiState> = _uiState.asStateFlow()

    private var designJob: Job? = null
    private var settingsSaveJob: Job? = null
    private val settingsSaveSequence = AtomicLong()
    private var knownCurrentConversationId: String? = null
    private var legacyInputInitialized = false

    init {
        viewModelScope.launch {
            settingsRepository.initialize()
            studioRepository.initialize()
            conversationRepository.initialize()
            val draft = studioRepository.loadDraft()
            val current = conversationRepository.currentConversation()
            knownCurrentConversationId = current?.id
            _uiState.update {
                it.copy(
                    initialized = true,
                    conversation = current,
                    composingNew = current == null,
                    draft = draft,
                    input = if (current == null) draft.imageDescription else ""
                )
            }
            legacyInputInitialized = true
            observeConversations()
            observeDesignConfiguration()
        }
    }

    private fun observeConversations() {
        viewModelScope.launch {
            combine(
                conversationRepository.conversations,
                conversationRepository.currentConversationId
            ) { conversations, currentId ->
                currentId to conversations.firstOrNull { it.id == currentId }
            }.collect { (currentId, current) ->
                val pointerChanged = knownCurrentConversationId != currentId
                knownCurrentConversationId = currentId
                _uiState.update { state ->
                    if (state.composingNew && !pointerChanged) state else state.copy(
                        conversation = current,
                        composingNew = current == null,
                        input = if (pointerChanged) "" else state.input
                    )
                }
            }
        }
    }

    private fun observeDesignConfiguration() {
        viewModelScope.launch {
            combine(studioRepository.draft, settingsRepository.appSettings) { draft, settings ->
                draft to settings
            }.collect { (storedDraft, settings) ->
                val draft = storedDraft ?: return@collect
                val models = modelResolver.availableChatModels(settings)
                val defaultModel = modelResolver.defaultImageModel(settings)
                val selectedId = draft.aiDesignModelId ?: defaultModel?.id
                val selectedModel = models.firstOrNull { it.id == selectedId }
                val modelError = when {
                    draft.aiDesignModelId != null && selectedModel == null -> "已选择的 Prompt 设计模型不可用，请在设置中重新选择"
                    selectedModel == null -> "未配置可用的生图辅助模型"
                    !selectedModel.hasConfiguredAuthentication(settings) -> "Prompt 设计模型/API Key 未配置"
                    else -> null
                }
                _uiState.update { state ->
                    state.copy(
                        draft = draft,
                        models = models,
                        selectedDesignModelId = selectedId,
                        modelError = modelError,
                        input = if (!legacyInputInitialized && state.conversation == null) {
                            draft.imageDescription
                        } else {
                            state.input
                        }
                    )
                }
            }
        }
    }

    fun updateInput(value: String) = _uiState.update { it.copy(input = value, error = null) }

    fun startNewConversation() {
        if (_uiState.value.isGenerating) return
        _uiState.update {
            it.copy(
                composingNew = true,
                conversation = null,
                input = "",
                progressText = "",
                reasoningText = "",
                error = null
            )
        }
    }

    fun sendMessage() {
        val state = _uiState.value
        val text = state.input.trim()
        if (!state.canSend || text.isBlank() || designJob?.isActive == true) return
        val model = state.models.firstOrNull { it.id == state.selectedDesignModelId }
        if (model == null) {
            _uiState.update { it.copy(error = state.modelError ?: "Prompt 设计模型不可用") }
            return
        }
        designJob = viewModelScope.launch {
            var persistedTurn: Pair<String, String>? = null
            try {
                val pair = if (state.composingNew || state.conversation == null) {
                    conversationRepository.createCurrentConversation(
                        userText = text,
                        designModelId = model.id,
                        targetImageModel = novelAiDesignTargetModel(state.draft),
                        naturalLanguageMode = state.draft.aiDesignNaturalLanguageMode
                    )
                } else {
                    val conversation = state.conversation
                    conversation to conversationRepository.appendPendingTurn(
                        conversationId = conversation.id,
                        userText = text,
                        designModelId = model.id,
                        targetImageModel = novelAiDesignTargetModel(state.draft),
                        naturalLanguageMode = state.draft.aiDesignNaturalLanguageMode
                    )
                }
                val conversation = pair.first
                val turn = pair.second
                persistedTurn = conversation.id to turn.id
                _uiState.update {
                    it.copy(
                        composingNew = false,
                        conversation = conversation,
                        input = "",
                        generatingTurnId = turn.id,
                        progressText = "正在准备 AI 设计…",
                        reasoningText = "",
                        error = null
                    )
                }
                if (state.composingNew && state.draft.imageDescription.isNotBlank()) {
                    try {
                        studioRepository.updateDraft { draft -> draft.copy(imageDescription = "") }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        // 会话已持久化；旧输入清理失败不应阻断本轮设计。
                    }
                }
                runTurn(conversation.id, turn.id, model, state.draft)
            } catch (error: CancellationException) {
                persistedTurn?.let { (conversationId, turnId) ->
                    withContext(NonCancellable) {
                        conversationRepository.failTurn(
                            conversationId = conversationId,
                            turnId = turnId,
                            error = "已停止生成，可重试",
                            cancelled = true
                        )
                    }
                }
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(error = "保存 AI 设计会话失败：${error.message ?: "未知错误"}")
                }
            } finally {
                val turnId = persistedTurn?.second
                _uiState.update { current ->
                    if (turnId != null && current.generatingTurnId == turnId) {
                        current.copy(generatingTurnId = null, progressText = "", reasoningText = "")
                    } else {
                        current
                    }
                }
                designJob = null
            }
        }
    }

    fun retryTurn(turnId: String) {
        val state = _uiState.value
        val conversation = state.conversation ?: return
        val turn = conversation.turns.firstOrNull { it.id == turnId } ?: return
        if (state.isGenerating || turn.status == NovelAiDesignTurnStatus.COMPLETED) return
        val model = state.models.firstOrNull { it.id == state.selectedDesignModelId }
        if (model == null) {
            _uiState.update { it.copy(error = state.modelError ?: "Prompt 设计模型不可用") }
            return
        }
        val replacesExistingReply = turn.reply != null
        launchExistingTurn(
            state = state,
            conversation = conversation,
            turn = turn,
            model = model,
            targetImageModel = if (replacesExistingReply) {
                turn.targetImageModel
            } else {
                novelAiDesignTargetModel(state.draft)
            },
            naturalLanguageMode = if (replacesExistingReply) {
                turn.naturalLanguageMode
            } else {
                state.draft.aiDesignNaturalLanguageMode
            },
            progressMessage = if (replacesExistingReply) {
                "正在重试重新生成…"
            } else {
                "正在重试 AI 设计…"
            }
        )
    }

    fun regenerateTurn(turnId: String) {
        val state = _uiState.value
        val conversation = state.conversation ?: return
        val turn = conversation.turns.firstOrNull { it.id == turnId } ?: return
        if (state.isGenerating || conversation.latestRegeneratableTurnId != turnId) return
        val model = state.models.firstOrNull { it.id == state.selectedDesignModelId }
        if (model == null) {
            _uiState.update { it.copy(error = state.modelError ?: "Prompt 设计模型不可用") }
            return
        }
        launchExistingTurn(
            state = state,
            conversation = conversation,
            turn = turn,
            model = model,
            targetImageModel = turn.targetImageModel,
            naturalLanguageMode = turn.naturalLanguageMode,
            progressMessage = "正在重新生成 AI 设计…"
        )
    }

    private fun launchExistingTurn(
        state: NovelAiDesignUiState,
        conversation: NovelAiDesignConversation,
        turn: NovelAiDesignTurn,
        model: ModelConfig,
        targetImageModel: NovelAiImageModel,
        naturalLanguageMode: Boolean,
        progressMessage: String
    ) {
        if (designJob?.isActive == true) return
        designJob = viewModelScope.launch {
            var markedPending = false
            try {
                conversationRepository.markTurnPending(
                    conversationId = conversation.id,
                    turnId = turn.id,
                    designModelId = model.id,
                    targetImageModel = targetImageModel,
                    naturalLanguageMode = naturalLanguageMode
                )
                markedPending = true
                _uiState.update {
                    it.copy(
                        generatingTurnId = turn.id,
                        progressText = progressMessage,
                        reasoningText = "",
                        appliedReplyKey = it.appliedReplyKey?.takeUnless { key ->
                            key == "${conversation.id}:${turn.id}"
                        },
                        error = null
                    )
                }
                runTurn(conversation.id, turn.id, model, state.draft)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (markedPending) {
                    conversationRepository.failTurn(
                        conversationId = conversation.id,
                        turnId = turn.id,
                        error = error.message ?: "AI 设计重试失败"
                    )
                }
                _uiState.update { it.copy(error = error.message ?: "AI 设计重试失败") }
            } finally {
                _uiState.update { current ->
                    if (current.generatingTurnId == turn.id) {
                        current.copy(generatingTurnId = null, progressText = "", reasoningText = "")
                    } else {
                        current
                    }
                }
                designJob = null
            }
        }
    }

    private suspend fun runTurn(
        conversationId: String,
        turnId: String,
        model: ModelConfig,
        draft: NovelAiStudioDraft
    ) {
        try {
            val conversation = conversationRepository.conversations.value
                .firstOrNull { it.id == conversationId }
                ?: error("AI 设计会话不存在")
            val turnIndex = conversation.turns.indexOfFirst { it.id == turnId }
            val turn = conversation.turns.getOrNull(turnIndex) ?: error("AI 设计轮次不存在")
            val replacesExistingReply = turn.reply != null
            val previousReply = conversation.turns
                .take(turnIndex)
                .asReversed()
                .firstNotNullOfOrNull(NovelAiDesignTurn::reply)
            val characterPrompt = draft.characters.joinToString("\n\n") { it.prompt }
            val cardPrompts = draft.importedCharacterPromptSources.map { it.name to it.prompt }
            val playerName = settingsRepository.getPlayerSetting().playerName
            val designResult = if (previousReply == null) {
                promptDesigner.designForPromptToolDetailed(
                    imageDescription = turn.userText,
                    characterPrompt = characterPrompt,
                    characterImagePrompts = cardPrompts,
                    finalPromptRequirement = draft.extraRequirement,
                    model = model,
                    playerName = playerName,
                    targetImageModel = turn.targetImageModel,
                    naturalLanguageMode = turn.naturalLanguageMode,
                    onContentDelta = { text -> _uiState.update { it.copy(progressText = text) } },
                    onReasoningDelta = { text -> _uiState.update { it.copy(reasoningText = text) } }
                )
            } else {
                val plan = promptDesigner.reviseForPromptTool(
                    previousPlan = previousReply.plan,
                    modificationRequest = turn.userText,
                    characterPrompt = characterPrompt,
                    characterImagePrompts = cardPrompts,
                    initialResearch = conversation.initialResearch ?: NovelAiDesignResearchSnapshot(),
                    finalPromptRequirement = draft.extraRequirement,
                    model = model,
                    playerName = playerName,
                    targetImageModel = turn.targetImageModel,
                    naturalLanguageMode = turn.naturalLanguageMode,
                    onContentDelta = { text -> _uiState.update { it.copy(progressText = text) } },
                    onReasoningDelta = { text -> _uiState.update { it.copy(reasoningText = text) } }
                )
                com.example.chatbar.domain.image.NovelAiPromptToolDesignResult(
                    plan = plan,
                    research = conversation.initialResearch ?: NovelAiDesignResearchSnapshot()
                )
            }
            val reply = NovelAiDesignReply(
                plan = designResult.plan,
                targetImageModel = turn.targetImageModel,
                designModelId = model.id,
                naturalLanguageMode = turn.naturalLanguageMode
            )
            conversationRepository.completeTurn(
                conversationId = conversationId,
                turnId = turnId,
                reply = reply,
                initialResearch = if (previousReply == null) designResult.research else null,
                replaceInitialResearch = previousReply == null && replacesExistingReply
            )
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                conversationRepository.failTurn(
                    conversationId = conversationId,
                    turnId = turnId,
                    error = "已停止生成，可重试",
                    cancelled = true
                )
            }
            throw error
        } catch (error: Throwable) {
            conversationRepository.failTurn(
                conversationId = conversationId,
                turnId = turnId,
                error = persistedFailureMessage(error)
            )
        } finally {
            _uiState.update { state ->
                if (state.generatingTurnId == turnId) {
                    state.copy(
                        generatingTurnId = null,
                        progressText = "",
                        reasoningText = ""
                    )
                } else {
                    state
                }
            }
            designJob = null
        }
    }

    fun cancelGeneration() {
        designJob?.cancel(CancellationException("用户停止 AI 设计"))
    }

    fun applyReply(turnId: String) {
        val state = _uiState.value
        val reply = state.conversation?.turns?.firstOrNull { it.id == turnId }?.reply ?: return
        val key = "${state.conversation.id}:$turnId"
        if (state.applyingReplyKey != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(applyingReplyKey = key, error = null) }
            runCatching {
                studioRepository.applyDesignedPrompt(reply.plan, reply.targetImageModel)
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        applyingReplyKey = null,
                        appliedReplyKey = key,
                        notice = "已应用到生图工作室"
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        applyingReplyKey = null,
                        error = "应用失败：${error.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    fun selectDesignModel(model: ModelConfig) {
        _uiState.update {
            it.copy(
                selectedDesignModelId = model.id,
                draft = it.draft.copy(aiDesignModelId = model.id),
                modelError = null
            )
        }
        app.applicationScope.launch {
            studioRepository.updateDraft { draft -> draft.copy(aiDesignModelId = model.id) }
        }
    }

    fun updateExtraRequirement(value: String) {
        _uiState.update { it.copy(draft = it.draft.copy(extraRequirement = value)) }
        val sequence = settingsSaveSequence.incrementAndGet()
        settingsSaveJob?.cancel()
        settingsSaveJob = viewModelScope.launch {
            delay(SETTINGS_SAVE_DEBOUNCE_MS)
            if (sequence == settingsSaveSequence.get()) saveExtraRequirement(value)
        }
    }

    fun setNaturalLanguageMode(enabled: Boolean) {
        _uiState.update {
            it.copy(draft = it.draft.copy(aiDesignNaturalLanguageMode = enabled))
        }
        app.applicationScope.launch {
            studioRepository.updateDraft { draft ->
                draft.copy(aiDesignNaturalLanguageMode = enabled)
            }
        }
    }

    fun persistSettingsNow() {
        settingsSaveJob?.cancel()
        val value = _uiState.value.draft.extraRequirement
        val sequence = settingsSaveSequence.incrementAndGet()
        app.applicationScope.launch {
            if (sequence == settingsSaveSequence.get()) saveExtraRequirement(value)
        }
    }

    private suspend fun saveExtraRequirement(value: String) {
        studioRepository.updateDraft { draft -> draft.copy(extraRequirement = value) }
    }

    private fun persistedFailureMessage(error: Throwable): String = error.message
        .orEmpty()
        .substringBefore("，原始内容:")
        .trim()
        .take(300)
        .ifBlank { "AI 设计失败" }

    fun consumeNotice() = _uiState.update { it.copy(notice = null) }
    fun dismissError() = _uiState.update { it.copy(error = null) }

    override fun onCleared() {
        persistSettingsNow()
        super.onCleared()
    }

    companion object {
        private const val SETTINGS_SAVE_DEBOUNCE_MS = 450L
    }
}

internal fun novelAiDesignTargetModel(draft: NovelAiStudioDraft): NovelAiImageModel =
    if (draft.aiDesignNaturalLanguageMode) NovelAiImageModel.V5_FULL else draft.selectedModel

data class NovelAiDesignHistoryUiState(
    val initialized: Boolean = false,
    val conversations: List<NovelAiDesignConversation> = emptyList(),
    val selectingId: String? = null,
    val selected: Boolean = false,
    val error: String? = null
)

class NovelAiDesignHistoryViewModel : ViewModel() {
    private val repository = ChatBarApp.instance.novelAiDesignConversationRepository
    private val _uiState = MutableStateFlow(NovelAiDesignHistoryUiState())
    val uiState: StateFlow<NovelAiDesignHistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.initialize()
            combine(repository.conversations, repository.currentConversationId) { _, _ ->
                repository.history()
            }.collect { history ->
                _uiState.update { it.copy(initialized = true, conversations = history) }
            }
        }
    }

    fun selectConversation(id: String) {
        if (_uiState.value.selectingId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(selectingId = id, error = null) }
            runCatching { repository.switchCurrent(id) }
                .onSuccess { _uiState.update { it.copy(selectingId = null, selected = true) } }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            selectingId = null,
                            error = error.message ?: "切换会话失败"
                        )
                    }
                }
        }
    }

    fun consumeSelected() = _uiState.update { it.copy(selected = false) }
}
