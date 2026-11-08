package com.example.chatbar.ui.format

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.card.FormatCardAutoFillDraft
import com.example.chatbar.domain.card.FormatCardAutoFillPolicy
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import com.example.chatbar.data.local.entity.EditorDraft
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.data.local.entity.FormatCardUserToolType
import com.example.chatbar.domain.card.FormatCardUserToolPolicy
import com.example.chatbar.domain.card.FormatCardUserToolValidation
import com.example.chatbar.domain.card.NamePolicy
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FormatCardAutoFillUiState(
    val isGenerating: Boolean = false,
    val status: String = "",
    val error: String? = null,
    val validationIssue: String? = null,
    val rawText: String = "",
    val repairText: String = "",
    val draft: FormatCardAutoFillDraft? = null,
    val sourceCharacterName: String = ""
)

/**
 * 格式卡片编辑器 ViewModel
 */
class FormatCardEditViewModel(
    private val formatCardId: String?,
    routeDraftId: String
) : ViewModel() {
    private val repository = ChatBarApp.instance.formatCardRepository
    private val draftRepository = ChatBarApp.instance.editorDraftRepository
    private val draftSessionId = routeDraftId.ifBlank { UUID.randomUUID().toString() }
    private var baseCard: FormatCard? = null
    private var loadedDraft: EditorDraft? = null
    private var draftJob: Job? = null
    private var aiJob: Job? = null
    val autoFillProgress = com.example.chatbar.domain.chat.AiStreamProgress()
    private val _autoFillState = MutableStateFlow(FormatCardAutoFillUiState())
    val autoFillState: StateFlow<FormatCardAutoFillUiState> = _autoFillState.asStateFlow()

    var showAutoFill by mutableStateOf(false)
        private set
    var autoFillCharacters by mutableStateOf<List<CharacterCard>>(emptyList())
        private set
    var autoFillModels by mutableStateOf<List<ModelConfig>>(emptyList())
        private set
    var autoFillDefaultModelName by mutableStateOf("")
        private set
    var autoFillOptionsLoading by mutableStateOf(false)
        private set
    var autoFillOptionsError by mutableStateOf<String?>(null)
        private set
    var autoFillCharacterId by mutableStateOf<String?>(null)
        private set
    var autoFillModelId by mutableStateOf<String?>(null)
        private set
    var autoFillRequest by mutableStateOf("")
        private set

    private val _formatCard = MutableStateFlow<FormatCard?>(null)
    val formatCard: StateFlow<FormatCard?> = _formatCard.asStateFlow()

    var name by mutableStateOf("")
    var content by mutableStateOf("")
    var userTools by mutableStateOf<List<FormatCardUserToolConfig>>(emptyList())
        private set
    var isDefault by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)
    var draftSavedAt by mutableStateOf<Long?>(null)
        private set
    var draftReady by mutableStateOf(false)
        private set
    var hasLocalChanges by mutableStateOf(false)
        private set
    var hasUnsavedDraftChanges by mutableStateOf(false)
        private set
    var restoreDraft by mutableStateOf<EditorDraft?>(null)
        private set
    var restoreConflict by mutableStateOf(false)
        private set
    var saveConflict by mutableStateOf(false)
    var sourceDeleted by mutableStateOf(false)
        private set

    val canSave: Boolean
        get() = name.isNotBlank() &&
            content.isNotBlank() &&
            FormatCardUserToolPolicy.firstValidationError(userTools) == null

    init {
        loadFormatCard()
        viewModelScope.launch {
            ChatBarApp.instance.effectiveModelResolver.observeConfiguration().collect { configuration ->
                autoFillModels = configuration.chatModels
                autoFillDefaultModelName = configuration.defaultChatModel?.let {
                    it.displayName.ifBlank { it.modelName }
                }.orEmpty()
            }
        }
    }

    val canAutoFill: Boolean
        get() = draftReady && restoreDraft == null && baseCard == null &&
            FormatCardAutoFillPolicy.canFill(formatCardId, content, userTools)

    fun openAutoFill() {
        if (!canAutoFill) return
        showAutoFill = true
        refreshAutoFillOptions()
    }

    fun closeAutoFill() {
        if (!_autoFillState.value.isGenerating) showAutoFill = false
    }

    fun refreshAutoFillOptions() {
        if (autoFillOptionsLoading) return
        autoFillOptionsLoading = true
        autoFillOptionsError = null
        viewModelScope.launch {
            try {
                val app = ChatBarApp.instance
                autoFillCharacters = app.characterRepository.getAll()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                autoFillOptionsError = error.message ?: "读取角色卡与模型失败"
            } finally {
                autoFillOptionsLoading = false
            }
        }
    }

    fun selectAutoFillCharacter(id: String) {
        if (_autoFillState.value.isGenerating || autoFillCharacterId == id) return
        autoFillCharacterId = id
        autoFillProgress.clear()
        _autoFillState.value = FormatCardAutoFillUiState()
    }

    fun selectAutoFillModel(id: String?) {
        if (_autoFillState.value.isGenerating || autoFillModelId == id) return
        autoFillModelId = id
        autoFillProgress.clear()
        _autoFillState.value = FormatCardAutoFillUiState()
    }

    fun updateAutoFillRequest(value: String) {
        if (_autoFillState.value.isGenerating || autoFillRequest == value) return
        autoFillRequest = value
        autoFillProgress.clear()
        _autoFillState.value = FormatCardAutoFillUiState()
    }

    fun generateAutoFill() {
        if (_autoFillState.value.isGenerating || aiJob?.isActive == true) return
        if (!canAutoFill) {
            _autoFillState.update { it.copy(error = "仅可填充正文和用户工具均为空的新格式卡") }
            return
        }
        val characterId = autoFillCharacterId
        if (characterId == null) {
            _autoFillState.update { it.copy(error = "请选择一张已保存角色卡") }
            return
        }
        val request = autoFillRequest
        val requestedName = name
        val modelId = autoFillModelId
        _autoFillState.value = FormatCardAutoFillUiState(isGenerating = true, status = "正在准备生成")
        autoFillProgress.clear()
        aiJob = viewModelScope.launch(autoFillProgress) {
            try {
                val app = ChatBarApp.instance
                val character = app.characterRepository.getById(characterId)
                    ?: error("所选角色卡已删除，请重新选择")
                _autoFillState.update { it.copy(sourceCharacterName = character.name) }
                val result = AiBackgroundWorkManager.run(draftSessionId) {
                    app.formatCardAutoFillService.generateStreaming(
                        character = character,
                        request = request,
                        requestedName = requestedName,
                        modelId = modelId,
                        onStatus = { value -> _autoFillState.update { it.copy(status = value) } },
                        onRawText = { value -> _autoFillState.update { it.copy(rawText = value) } },
                        onRepairText = { value -> _autoFillState.update { it.copy(repairText = value) } },
                        onValidationIssue = { value -> _autoFillState.update { it.copy(validationIssue = value) } }
                    )
                }
                _autoFillState.update { it.copy(draft = result) }
            } catch (cancelled: CancellationException) {
                _autoFillState.update { it.copy(status = "已取消生成", draft = null) }
                throw cancelled
            } catch (error: Exception) {
                _autoFillState.update {
                    it.copy(error = error.message ?: "格式卡生成失败", status = "生成失败，可重新生成", draft = null)
                }
            } finally {
                _autoFillState.update { it.copy(isGenerating = false) }
            }
        }
    }

    fun cancelAutoFill() {
        if (_autoFillState.value.isGenerating) {
            _autoFillState.update { it.copy(status = "正在取消生成") }
            aiJob?.cancel()
        }
    }

    fun applyAutoFill() {
        val state = _autoFillState.value
        if (state.isGenerating) return
        val candidate = state.draft ?: return
        if (!canAutoFill) {
            _autoFillState.update { it.copy(error = "当前正文或工具已变化，未应用候选") }
            return
        }
        val filled = FormatCardAutoFillPolicy.prepareApply(formatCardId, name, content, userTools, candidate)
        name = filled.name
        content = filled.content
        userTools = filled.userTools
        saveError = null
        scheduleDraftSave()
        showAutoFill = false
        _autoFillState.value = FormatCardAutoFillUiState()
    }

    private fun loadFormatCard() {
        viewModelScope.launch {
            val draft = draftRepository.getForTarget(com.example.chatbar.data.local.entity.EditorDraftType.FORMAT_CARD, formatCardId)
            if (formatCardId != null) {
                val card = repository.getById(formatCardId)
                baseCard = card
                _formatCard.value = card
                if (card != null) applyCard(card) else sourceDeleted = draft != null
                if (draft != null) {
                    if (card == null) {
                        loadedDraft = draft.copy(targetId = null)
                        draft.formatPayload?.let(::applyCard)
                        refreshChangeState()
                    } else {
                        restoreDraft = draft
                        restoreConflict = draftRepository.isChanged(card, draft)
                    }
                }
            } else {
                val newDraft = draft ?: draftRepository.getLatestNew(com.example.chatbar.data.local.entity.EditorDraftType.FORMAT_CARD)
                if (newDraft?.formatPayload != null) {
                    loadedDraft = newDraft
                    applyCard(newDraft.formatPayload)
                    refreshChangeState()
                }
            }
            draftReady = true
        }
    }

    fun restoreDraft() {
        restoreDraft?.let { draft ->
            loadedDraft = draft
            draft.formatPayload?.let(::applyCard)
            refreshChangeState()
            hasUnsavedDraftChanges = false
        }
        restoreDraft = null
        restoreConflict = false
    }

    fun keepOriginal() {
        restoreDraft = null
        restoreConflict = false
    }

    fun discardDraft(onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            draftJob?.cancelAndJoin()
            draftJob = null
            loadedDraft?.id?.let { draftRepository.delete(it) }
            draftRepository.deleteForTarget(com.example.chatbar.data.local.entity.EditorDraftType.FORMAT_CARD, formatCardId)
            hasLocalChanges = false
            hasUnsavedDraftChanges = false
            loadedDraft = null
            restoreDraft = null
            restoreConflict = false
            onDone?.invoke()
        }
    }

    /**
     * 保存格式卡片
     */
    fun saveFormatCard(onSuccess: () -> Unit, forceOverwrite: Boolean = false, saveAsNew: Boolean = false) {
        if (!canSave) return

        viewModelScope.launch {
            draftJob?.cancelAndJoin()
            draftJob = null
            name = NamePolicy.normalize(name)
            val targetId = if (saveAsNew || sourceDeleted) null else formatCardId
            if (!forceOverwrite && targetId != null && loadedDraft != null && draftRepository.isChanged(repository.getById(targetId), loadedDraft!!)) {
                saveConflict = true
                return@launch
            }
            val all = repository.getAll()
            if (targetId == null && all.any { NamePolicy.isSame(it.name, name) }) {
                name = NamePolicy.nextCopyName(name, all.map { it.name })
            }
            val conflict = all.firstOrNull { it.id != targetId && NamePolicy.isSame(it.name, name) }
            if (conflict != null) {
                saveError = "名称与“${conflict.name}”冲突"
                return@launch
            }
            saveError = null
            val card = targetId?.let { repository.getById(it) }?.copy(
                name = name,
                content = content,
                userTools = userTools,
                isDefault = isDefault
            ) ?: FormatCard(
                id = targetId ?: UUID.randomUUID().toString(),
                name = name,
                content = content,
                userTools = userTools,
                isDefault = isDefault,
                createdAt = _formatCard.value?.createdAt ?: System.currentTimeMillis()
            )

            repository.save(card)
            loadedDraft?.id?.let { draftRepository.delete(it) }
            draftRepository.deleteForTarget(com.example.chatbar.data.local.entity.EditorDraftType.FORMAT_CARD, formatCardId)
            baseCard = card
            _formatCard.value = card
            hasLocalChanges = false
            hasUnsavedDraftChanges = false
            onSuccess()
        }
    }

    fun scheduleDraftSave() {
        if (!draftReady || restoreDraft != null) return
        refreshChangeState()
        if (!hasLocalChanges) {
            hasUnsavedDraftChanges = false
            draftJob?.cancel()
            return
        }
        hasUnsavedDraftChanges = true
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(600)
            saveDraftNow()
        }
    }

    fun addUserTool(type: FormatCardUserToolType) {
        val tool = when (type) {
            FormatCardUserToolType.RANDOM_NUMBER -> FormatCardUserToolConfig.randomNumber()
            FormatCardUserToolType.STRONG_PROMPT_SUFFIX -> FormatCardUserToolConfig.strongPromptSuffix()
        }
        userTools = userTools + tool
        scheduleDraftSave()
    }

    fun updateUserTool(index: Int, transform: (FormatCardUserToolConfig) -> FormatCardUserToolConfig) {
        if (index !in userTools.indices) return
        userTools = userTools.toMutableList().also { tools ->
            tools[index] = transform(tools[index])
        }
        scheduleDraftSave()
    }

    fun moveUserTool(index: Int, offset: Int) {
        val target = index + offset
        if (index !in userTools.indices || target !in userTools.indices) return
        userTools = userTools.toMutableList().also { tools ->
            val moved = tools.removeAt(index)
            tools.add(target, moved)
        }
        scheduleDraftSave()
    }

    fun deleteUserTool(index: Int) {
        if (index !in userTools.indices) return
        userTools = userTools.toMutableList().also { it.removeAt(index) }
        scheduleDraftSave()
    }

    fun userToolValidation(index: Int): FormatCardUserToolValidation =
        userTools.getOrNull(index)
            ?.let(FormatCardUserToolPolicy::validate)
            ?: FormatCardUserToolValidation()

    fun saveDraftAndExit(onDone: () -> Unit) {
        viewModelScope.launch {
            draftJob?.cancelAndJoin()
            draftJob = null
            if (hasUnsavedDraftChanges) saveDraftNow()
            onDone()
        }
    }

    private suspend fun saveDraftNow() {
        if (!draftReady || restoreDraft != null) return
        val payload = currentPayload()
        val draft = draftRepository.formatDraft(
            targetId = if (sourceDeleted) null else formatCardId,
            draftSessionId = draftSessionId,
            payload = payload,
            base = baseCard
        )
        loadedDraft = draftRepository.save(draft)
        draftSavedAt = loadedDraft?.updatedAt
        hasUnsavedDraftChanges = false
    }

    private fun currentPayload(): FormatCard =
        _formatCard.value?.copy(
            name = name,
            content = content,
            userTools = userTools,
            isDefault = isDefault
        ) ?: FormatCard(
            id = UUID.randomUUID().toString(),
            name = name,
            content = content,
            userTools = userTools,
            isDefault = isDefault,
            createdAt = System.currentTimeMillis()
        )

    private fun applyCard(card: FormatCard) {
        _formatCard.value = card
        name = card.name
        content = card.content
        userTools = card.userTools
        isDefault = card.isDefault
    }

    private fun refreshChangeState() {
        val base = baseCard
        hasLocalChanges = if (base == null) {
            sourceDeleted || name.isNotBlank() || content.isNotBlank() || userTools.isNotEmpty() || isDefault
        } else {
            currentPayload() != base
        }
    }
}
