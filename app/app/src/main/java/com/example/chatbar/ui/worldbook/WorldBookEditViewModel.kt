package com.example.chatbar.ui.worldbook

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.EditorDraft
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.local.entity.WorldBookPosition
import com.example.chatbar.domain.card.NamePolicy
import com.example.chatbar.domain.draft.WorldBookEntryModalState
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WorldBookEditViewModel(
    private val worldBookId: String?,
    routeDraftId: String
) : ViewModel() {
    private val repository = ChatBarApp.instance.worldBookRepository
    private val draftRepository = ChatBarApp.instance.editorDraftRepository
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val draftSessionId = routeDraftId.ifBlank { UUID.randomUUID().toString() }
    private var baseBook: WorldBook? = null
    private var loadedDraft: EditorDraft? = null
    private var draftJob: Job? = null

    private val _worldBook = MutableStateFlow<WorldBook?>(null)
    val worldBook: StateFlow<WorldBook?> = _worldBook.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    var name by mutableStateOf("")
    var description by mutableStateOf("")
    var scanDepth by mutableStateOf(10)
    var tokenBudget by mutableStateOf("")
    var recursiveScanning by mutableStateOf(false)
    var caseSensitive by mutableStateOf(false)
    var matchWholeWords by mutableStateOf(false)
    val entries = mutableStateListOf<WorldBookEntry>()
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
    var entryModalState by mutableStateOf<WorldBookEntryModalState?>(null)
        private set

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val draft = draftRepository.getForTarget(EditorDraftType.WORLD_BOOK, worldBookId)
            if (worldBookId != null) {
                val book = repository.getById(worldBookId)
                baseBook = book
                if (book != null) {
                    applyBook(book)
                } else {
                    sourceDeleted = draft != null
                }
                if (draft != null) {
                    if (book == null) {
                        loadedDraft = draft.copy(targetId = null)
                        draft.worldBookPayload?.let(::applyBook)
                        restoreOpenModal(draft.openModalState)
                        refreshChangeState()
                    } else {
                        restoreDraft = draft
                        restoreConflict = draftRepository.isChanged(book, draft)
                    }
                }
            } else {
                val newDraft = draft ?: draftRepository.getLatestNew(EditorDraftType.WORLD_BOOK)
                if (newDraft?.worldBookPayload != null) {
                    loadedDraft = newDraft
                    applyBook(newDraft.worldBookPayload)
                    restoreOpenModal(newDraft.openModalState)
                    refreshChangeState()
                }
            }
            draftReady = true
        }
    }

    fun restoreDraft() {
        restoreDraft?.let { draft ->
            loadedDraft = draft
            draft.worldBookPayload?.let(::applyBook)
            restoreOpenModal(draft.openModalState)
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
            draftRepository.deleteForTarget(EditorDraftType.WORLD_BOOK, worldBookId)
            loadedDraft = null
            hasLocalChanges = false
            hasUnsavedDraftChanges = false
            entryModalState = null
            restoreDraft = null
            restoreConflict = false
            onDone?.invoke()
        }
    }

    fun save(onSuccess: () -> Unit, forceOverwrite: Boolean = false, saveAsNew: Boolean = false) {
        if (name.isBlank()) return
        _isSaving.value = true
        viewModelScope.launch {
            draftJob?.cancelAndJoin()
            draftJob = null
            val targetId = if (saveAsNew || sourceDeleted) null else worldBookId
            if (!forceOverwrite && targetId != null && loadedDraft != null && draftRepository.isChanged(repository.getById(targetId), loadedDraft!!)) {
                saveConflict = true
                _isSaving.value = false
                return@launch
            }
            name = NamePolicy.normalize(name)
            val all = repository.getAll()
            if (targetId == null && all.any { NamePolicy.isSame(it.name, name) }) {
                name = NamePolicy.nextCopyName(name, all.map { it.name })
            }
            val conflict = all.firstOrNull { it.id != targetId && NamePolicy.isSame(it.name, name) }
            if (conflict != null) {
                _isSaving.value = false
                return@launch
            }
            val now = System.currentTimeMillis()
            val budget = tokenBudget.toIntOrNull()
            val book = targetId?.let { repository.getById(it) }?.copy(
                name = name,
                description = description,
                entries = entries.toList(),
                scanDepth = scanDepth,
                tokenBudget = budget,
                recursiveScanning = recursiveScanning,
                caseSensitive = caseSensitive,
                matchWholeWords = matchWholeWords,
                updatedAt = now
            ) ?: WorldBook(
                id = targetId ?: UUID.randomUUID().toString(),
                name = name,
                description = description,
                entries = entries.toList(),
                scanDepth = scanDepth,
                tokenBudget = budget,
                recursiveScanning = recursiveScanning,
                caseSensitive = caseSensitive,
                matchWholeWords = matchWholeWords,
                createdAt = now,
                updatedAt = now
            )
            repository.save(book)
            loadedDraft?.id?.let { draftRepository.delete(it) }
            draftRepository.deleteForTarget(EditorDraftType.WORLD_BOOK, worldBookId)
            _worldBook.value = book
            baseBook = book
            hasLocalChanges = false
            hasUnsavedDraftChanges = false
            _isSaving.value = false
            onSuccess()
        }
    }

    fun addEntry(entry: WorldBookEntry) {
        entries.add(entry)
        scheduleDraftSave()
    }

    fun updateEntry(index: Int, entry: WorldBookEntry) {
        if (index in entries.indices) {
            entries[index] = entry
            scheduleDraftSave()
        }
    }

    fun deleteEntry(index: Int) {
        if (index in entries.indices) {
            entries.removeAt(index)
            scheduleDraftSave()
        }
    }

    fun toggleEntry(index: Int) {
        if (index in entries.indices) {
            val e = entries[index]
            entries[index] = e.copy(enabled = !e.enabled)
            scheduleDraftSave()
        }
    }

    fun openEntryDialog(index: Int?) {
        entryModalState = WorldBookEntryModalState.from(index, index?.let { entries.getOrNull(it) })
        scheduleDraftSave()
    }

    fun updateEntryDialog(state: WorldBookEntryModalState) {
        entryModalState = state
        scheduleDraftSave()
    }

    fun dismissEntryDialog() {
        entryModalState = null
        scheduleDraftSave()
    }

    fun saveEntryDialog() {
        val state = entryModalState ?: return
        val entry = WorldBookEntry(id = state.originalEntryId ?: UUID.randomUUID().toString()).copy(
            name = state.name,
            keys = state.keys.split(",").map { it.trim() }.filter { it.isNotBlank() },
            secondaryKeys = state.secondary.split(",").map { it.trim() }.filter { it.isNotBlank() },
            selective = state.secondary.isNotBlank(),
            selectiveLogic = state.logic,
            content = state.content,
            insertionOrder = state.order.toIntOrNull() ?: 100,
            position = state.position,
            enabled = state.enabled,
            constant = state.constant,
            useRegex = state.useRegex,
            matchWholeWords = state.wholeWords,
            caseSensitive = state.caseSensitive,
            ignoreBudget = state.ignoreBudget,
            excludeRecursion = state.excludeRecursion,
            preventRecursion = state.preventRecursion,
            delayUntilRecursion = state.delayUntilRecursion,
            probability = state.probability.toIntOrNull()?.coerceIn(0, 100) ?: 100,
            group = state.group,
            groupWeight = state.groupWeight.toIntOrNull()?.coerceAtLeast(0) ?: 100,
            scanDepth = state.scanDepth.toIntOrNull()?.coerceAtLeast(0),
            sticky = state.sticky.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            cooldown = state.cooldown.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            delay = state.delay.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            outletName = state.outlet
        )
        state.editingIndex?.let { updateEntry(it, entry) } ?: addEntry(entry)
        entryModalState = null
        scheduleDraftSave()
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
        val draft = draftRepository.worldBookDraft(
            targetId = if (sourceDeleted) null else worldBookId,
            draftSessionId = draftSessionId,
            payload = currentPayload(),
            base = baseBook,
            openModalState = entryModalState?.let { json.encodeToString(it) }
        )
        loadedDraft = draftRepository.save(draft)
        draftSavedAt = loadedDraft?.updatedAt
        hasUnsavedDraftChanges = false
    }

    private fun currentPayload(): WorldBook {
        val now = System.currentTimeMillis()
        return _worldBook.value?.copy(
            name = name,
            description = description,
            entries = entries.toList(),
            scanDepth = scanDepth,
            tokenBudget = tokenBudget.toIntOrNull(),
            recursiveScanning = recursiveScanning,
            caseSensitive = caseSensitive,
            matchWholeWords = matchWholeWords,
            updatedAt = now
        ) ?: WorldBook(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            entries = entries.toList(),
            scanDepth = scanDepth,
            tokenBudget = tokenBudget.toIntOrNull(),
            recursiveScanning = recursiveScanning,
            caseSensitive = caseSensitive,
            matchWholeWords = matchWholeWords,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun applyBook(book: WorldBook) {
        _worldBook.value = book
        name = book.name
        description = book.description
        scanDepth = book.scanDepth
        tokenBudget = book.tokenBudget?.toString() ?: ""
        recursiveScanning = book.recursiveScanning
        caseSensitive = book.caseSensitive
        matchWholeWords = book.matchWholeWords
        entries.clear()
        entries.addAll(book.entries)
    }

    private fun refreshChangeState() {
        val base = baseBook
        hasLocalChanges = if (base == null) {
            sourceDeleted || name.isNotBlank() || description.isNotBlank() || entries.isNotEmpty() ||
                scanDepth != 10 || tokenBudget.isNotBlank() || recursiveScanning || caseSensitive || matchWholeWords
        } else {
            currentPayload().copy(
                id = base.id,
                createdAt = base.createdAt,
                updatedAt = base.updatedAt
            ) != base
        }
    }

    private fun restoreOpenModal(raw: String?) {
        entryModalState = raw?.let {
            runCatching { json.decodeFromString(WorldBookEntryModalState.serializer(), it) }.getOrNull()
        }
    }
}
