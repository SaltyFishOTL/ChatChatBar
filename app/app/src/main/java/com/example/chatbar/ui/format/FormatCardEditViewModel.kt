package com.example.chatbar.ui.format

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.EditorDraft
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.domain.card.NamePolicy
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    private val _formatCard = MutableStateFlow<FormatCard?>(null)
    val formatCard: StateFlow<FormatCard?> = _formatCard.asStateFlow()

    var name by mutableStateOf("")
    var content by mutableStateOf("")
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

    init {
        loadFormatCard()
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
        if (name.isBlank() || content.isBlank()) return

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
                isDefault = isDefault
            ) ?: FormatCard(
                id = targetId ?: UUID.randomUUID().toString(),
                name = name,
                content = content,
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
            isDefault = isDefault
        ) ?: FormatCard(
            id = UUID.randomUUID().toString(),
            name = name,
            content = content,
            isDefault = isDefault,
            createdAt = System.currentTimeMillis()
        )

    private fun applyCard(card: FormatCard) {
        _formatCard.value = card
        name = card.name
        content = card.content
        isDefault = card.isDefault
    }

    private fun refreshChangeState() {
        val base = baseCard
        hasLocalChanges = if (base == null) {
            sourceDeleted || name.isNotBlank() || content.isNotBlank() || isDefault
        } else {
            currentPayload() != base
        }
    }
}
