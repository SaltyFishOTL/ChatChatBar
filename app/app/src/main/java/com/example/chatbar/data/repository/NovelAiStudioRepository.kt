package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry
import com.example.chatbar.domain.image.NovelAiGenerationHistoryImage
import com.example.chatbar.domain.image.NovelAiHistoryApplyMode
import com.example.chatbar.domain.image.NovelAiImageUseTarget
import com.example.chatbar.domain.image.NovelAiStudioDraft
import com.example.chatbar.domain.image.NovelAiStudioUndoDraft
import com.example.chatbar.domain.image.NovelAiGuidanceEditorCheckpoint
import com.example.chatbar.domain.image.applyHistoryRecipe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NovelAiStudioRepository(private val storage: JsonFileStorage) {
    private val _draft = MutableStateFlow<NovelAiStudioDraft?>(null)
    val draft: StateFlow<NovelAiStudioDraft?> = _draft.asStateFlow()
    private val _pendingGuidanceEditorTarget = MutableStateFlow<NovelAiImageUseTarget?>(null)
    val pendingGuidanceEditorTarget: StateFlow<NovelAiImageUseTarget?> =
        _pendingGuidanceEditorTarget.asStateFlow()
    private val draftMutex = Mutex()
    private val undoMutex = Mutex()

    suspend fun initialize() {
        storage.loadAll(HISTORY_ENTITY, NovelAiGenerationHistoryEntry.serializer())
    }

    suspend fun loadDraft(): NovelAiStudioDraft = draftMutex.withLock {
        (storage.loadSingleton(DRAFT_ENTITY, NovelAiStudioDraft.serializer()) ?: NovelAiStudioDraft())
            .also { _draft.value = it }
    }

    suspend fun saveDraft(draft: NovelAiStudioDraft) = draftMutex.withLock {
        storage.saveSingleton(DRAFT_ENTITY, draft.copy(updatedAt = System.currentTimeMillis()), NovelAiStudioDraft.serializer())
        _draft.value = draft
    }

    suspend fun saveUndoDraft(draft: NovelAiStudioDraft) = undoMutex.withLock {
        storage.saveSingleton(UNDO_ENTITY, NovelAiStudioUndoDraft(draft), NovelAiStudioUndoDraft.serializer())
    }

    suspend fun loadUndoDraft(): NovelAiStudioDraft? = undoMutex.withLock {
        storage.loadSingleton(UNDO_ENTITY, NovelAiStudioUndoDraft.serializer())?.draft
    }

    suspend fun clearUndoDraft() = undoMutex.withLock {
        storage.saveSingleton(UNDO_ENTITY, NovelAiStudioUndoDraft(), NovelAiStudioUndoDraft.serializer())
    }

    suspend fun saveGuidanceCheckpoint(guidance: com.example.chatbar.domain.image.NovelAiImageGuidanceDraft) {
        storage.saveSingleton(
            GUIDANCE_CHECKPOINT_ENTITY,
            NovelAiGuidanceEditorCheckpoint(guidance),
            NovelAiGuidanceEditorCheckpoint.serializer()
        )
    }

    suspend fun loadGuidanceCheckpoint(): com.example.chatbar.domain.image.NovelAiImageGuidanceDraft? =
        storage.loadSingleton(GUIDANCE_CHECKPOINT_ENTITY, NovelAiGuidanceEditorCheckpoint.serializer())?.guidance

    suspend fun clearGuidanceCheckpoint() {
        storage.saveSingleton(
            GUIDANCE_CHECKPOINT_ENTITY,
            NovelAiGuidanceEditorCheckpoint(),
            NovelAiGuidanceEditorCheckpoint.serializer()
        )
    }

    fun requestGuidanceEditor(target: NovelAiImageUseTarget) {
        _pendingGuidanceEditorTarget.value = target
    }

    fun consumeGuidanceEditorRequest() {
        _pendingGuidanceEditorTarget.value = null
    }

    suspend fun applyHistory(
        entry: NovelAiGenerationHistoryEntry,
        image: NovelAiGenerationHistoryImage,
        mode: NovelAiHistoryApplyMode
    ): NovelAiStudioDraft {
        val current = loadDraft()
        saveUndoDraft(current)
        return try {
            current.applyHistoryRecipe(entry.recipe, image.seed, mode).also {
                saveDraft(it)
                clearGuidanceCheckpoint()
            }
        } catch (error: Throwable) {
            clearUndoDraft()
            throw error
        }
    }

    val history: Flow<List<NovelAiGenerationHistoryEntry>> =
        storage.observeAll(HISTORY_ENTITY, NovelAiGenerationHistoryEntry.serializer())
            .map { entries -> entries.sortedByDescending(NovelAiGenerationHistoryEntry::createdAt) }

    suspend fun saveHistory(entry: NovelAiGenerationHistoryEntry) {
        storage.saveEntity(HISTORY_ENTITY, entry.id, entry, NovelAiGenerationHistoryEntry.serializer())
    }

    suspend fun deleteHistory(id: String) {
        storage.deleteEntity<NovelAiGenerationHistoryEntry>(HISTORY_ENTITY, id)
    }

    suspend fun clearHistory() {
        storage.deleteAll<NovelAiGenerationHistoryEntry>(HISTORY_ENTITY)
    }

    companion object {
        private const val DRAFT_ENTITY = "novelai_studio_draft"
        private const val UNDO_ENTITY = "novelai_studio_history_undo"
        private const val GUIDANCE_CHECKPOINT_ENTITY = "novelai_studio_guidance_checkpoint"
        private const val HISTORY_ENTITY = "novelai_generation_history"
    }
}
