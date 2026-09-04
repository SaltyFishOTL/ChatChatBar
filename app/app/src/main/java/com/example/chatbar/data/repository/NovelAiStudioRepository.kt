package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry
import com.example.chatbar.domain.image.NovelAiGenerationHistoryImage
import com.example.chatbar.domain.image.NovelAiHistoryApplyMode
import com.example.chatbar.domain.image.NovelAiHistoryImageDeleteResult
import com.example.chatbar.domain.image.NovelAiHistoryImageSelection
import com.example.chatbar.domain.image.NovelAiHistoryDeletionPolicy
import com.example.chatbar.domain.image.NovelAiImageStorage
import com.example.chatbar.domain.image.NovelAiImageUseTarget
import com.example.chatbar.domain.image.NovelAiStudioDraft
import com.example.chatbar.domain.image.NovelAiStudioUndoDraft
import com.example.chatbar.domain.image.NovelAiGuidanceEditorCheckpoint
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiPromptPlan
import com.example.chatbar.domain.image.applyDesignedPromptPlan
import com.example.chatbar.domain.image.applyReversePromptPlan
import com.example.chatbar.domain.image.applyHistoryRecipe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NovelAiStudioRepository(
    private val storage: JsonFileStorage,
    private val imageStorage: NovelAiImageStorage
) {
    private val _draft = MutableStateFlow<NovelAiStudioDraft?>(null)
    val draft: StateFlow<NovelAiStudioDraft?> = _draft.asStateFlow()
    private val _pendingGuidanceEditorTarget = MutableStateFlow<NovelAiImageUseTarget?>(null)
    val pendingGuidanceEditorTarget: StateFlow<NovelAiImageUseTarget?> =
        _pendingGuidanceEditorTarget.asStateFlow()
    private val draftMutex = Mutex()
    private val draftStateLock = Any()
    private val undoMutex = Mutex()
    private val historyMutex = Mutex()

    suspend fun initialize() {
        storage.loadAll(HISTORY_ENTITY, NovelAiGenerationHistoryEntry.serializer())
    }

    suspend fun loadDraft(): NovelAiStudioDraft {
        synchronized(draftStateLock) {
            _draft.value?.let { return it }
        }
        return draftMutex.withLock {
            synchronized(draftStateLock) {
                _draft.value?.let { return@withLock it }
            }
            val loaded = storage.loadSingleton(DRAFT_ENTITY, NovelAiStudioDraft.serializer())
                ?: NovelAiStudioDraft(followDefaultNovelAiImageModel = true)
            synchronized(draftStateLock) {
                _draft.value ?: loaded.also { _draft.value = it }
            }
        }
    }

    fun stageDraft(
        resetPromptEditors: Boolean = false,
        transform: (NovelAiStudioDraft) -> NovelAiStudioDraft
    ): NovelAiStudioDraft = synchronized(draftStateLock) {
        val current = checkNotNull(_draft.value) { "NovelAI Studio draft is not loaded" }
        val transformed = transform(current)
        if (transformed == current) return@synchronized current
        val revision = current.contentRevision + 1L
        transformed.copy(
            contentRevision = revision,
            promptContentRevision = if (resetPromptEditors) {
                revision
            } else {
                current.promptContentRevision
            },
            updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1L)
        ).also { _draft.value = it }
    }

    suspend fun flushLatestDraft(): NovelAiStudioDraft {
        if (_draft.value == null) loadDraft()
        return draftMutex.withLock {
            while (true) {
                val snapshot = synchronized(draftStateLock) {
                    checkNotNull(_draft.value) { "NovelAI Studio draft is not loaded" }
                }
                storage.saveSingleton(DRAFT_ENTITY, snapshot, NovelAiStudioDraft.serializer())
                val latest = synchronized(draftStateLock) {
                    checkNotNull(_draft.value)
                }
                if (latest.contentRevision == snapshot.contentRevision) return@withLock latest
            }
            error("unreachable")
        }
    }

    suspend fun updateDraft(
        resetPromptEditors: Boolean = false,
        transform: (NovelAiStudioDraft) -> NovelAiStudioDraft
    ): NovelAiStudioDraft {
        val before = synchronized(draftStateLock) {
            checkNotNull(_draft.value) { "NovelAI Studio draft is not loaded" }
        }
        val next = stageDraft(resetPromptEditors, transform)
        return try {
            flushLatestDraft()
            next
        } catch (error: Throwable) {
            synchronized(draftStateLock) {
                if (_draft.value?.contentRevision == next.contentRevision) {
                    _draft.value = before
                }
            }
            throw error
        }
    }

    suspend fun applyDesignedPrompt(
        plan: NovelAiPromptPlan,
        targetImageModel: NovelAiImageModel
    ): NovelAiStudioDraft = updateDraft(resetPromptEditors = true) { current ->
        current.applyDesignedPromptPlan(plan, targetImageModel)
    }

    suspend fun applyReversePrompt(
        plan: NovelAiPromptPlan
    ): NovelAiStudioDraft = updateDraft(resetPromptEditors = true) { current ->
        current.applyReversePromptPlan(plan)
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
            updateDraft(resetPromptEditors = true) { latest ->
                latest.applyHistoryRecipe(entry.recipe, image.seed, mode)
            }.also {
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

    suspend fun saveHistory(entry: NovelAiGenerationHistoryEntry) = historyMutex.withLock {
        storage.saveEntity(HISTORY_ENTITY, entry.id, entry, NovelAiGenerationHistoryEntry.serializer())
    }

    suspend fun deleteHistory(id: String) = historyMutex.withLock {
        storage.deleteEntity<NovelAiGenerationHistoryEntry>(HISTORY_ENTITY, id)
    }

    suspend fun clearHistory() = historyMutex.withLock {
        storage.deleteAll<NovelAiGenerationHistoryEntry>(HISTORY_ENTITY)
    }

    suspend fun deleteHistoryImages(
        selections: List<NovelAiHistoryImageSelection>
    ): NovelAiHistoryImageDeleteResult = historyMutex.withLock {
        val uniqueSelections = selections.distinctBy { it.entryId to it.imagePath }
        require(uniqueSelections.isNotEmpty()) { "未选择历史图片" }
        val originals = storage.loadAll(HISTORY_ENTITY, NovelAiGenerationHistoryEntry.serializer())
            .associateBy(NovelAiGenerationHistoryEntry::id)
        val mutations = NovelAiHistoryDeletionPolicy.apply(originals.values.toList(), uniqueSelections)

        val staged = mutableListOf<com.example.chatbar.domain.image.NovelAiStagedImageDeletion>()
        try {
            withContext(Dispatchers.IO) {
                uniqueSelections.forEach { selection ->
                    imageStorage.stageImageDelete(selection.imagePath)?.let(staged::add)
                }
            }
            mutations.forEach { (entryId, remaining) ->
                if (remaining == null) {
                    storage.deleteEntity<NovelAiGenerationHistoryEntry>(HISTORY_ENTITY, entryId)
                } else {
                    storage.saveEntity(
                        HISTORY_ENTITY,
                        entryId,
                        remaining,
                        NovelAiGenerationHistoryEntry.serializer()
                    )
                }
            }
        } catch (error: Throwable) {
            val rollbackFailures = mutableListOf<Throwable>()
            mutations.keys.forEach { entryId ->
                originals[entryId]?.let { original ->
                    runCatching {
                        storage.saveEntity(
                            HISTORY_ENTITY,
                            entryId,
                            original,
                            NovelAiGenerationHistoryEntry.serializer()
                        )
                    }.exceptionOrNull()?.let(rollbackFailures::add)
                }
            }
            withContext(Dispatchers.IO) {
                staged.asReversed().forEach { deletion ->
                    if (!imageStorage.restoreStagedImage(deletion)) {
                        rollbackFailures += IllegalStateException("图片恢复失败：${deletion.original.name}")
                    }
                }
            }
            rollbackFailures.forEach(error::addSuppressed)
            throw error
        }

        val cleanupFailures = withContext(Dispatchers.IO) {
            staged.count { !imageStorage.commitStagedImageDelete(it) }
        }
        NovelAiHistoryImageDeleteResult(
            deletedCount = uniqueSelections.size,
            cleanupFailureCount = cleanupFailures
        )
    }

    companion object {
        private const val DRAFT_ENTITY = "novelai_studio_draft"
        private const val UNDO_ENTITY = "novelai_studio_history_undo"
        private const val GUIDANCE_CHECKPOINT_ENTITY = "novelai_studio_guidance_checkpoint"
        private const val HISTORY_ENTITY = "novelai_generation_history"
    }
}
