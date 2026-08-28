package com.example.chatbar.ui.imageprompt

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry
import com.example.chatbar.domain.image.NovelAiGenerationHistoryImage
import com.example.chatbar.domain.image.NovelAiHistoryApplyMode
import com.example.chatbar.domain.image.NovelAiGenerationAction
import com.example.chatbar.domain.image.NovelAiGalleryConflictDecision
import com.example.chatbar.domain.image.NovelAiGalleryExportExecution
import com.example.chatbar.domain.image.NovelAiGalleryExportPlan
import com.example.chatbar.domain.image.NovelAiGalleryExportSource
import com.example.chatbar.domain.image.NovelAiHistoryGalleryExporter
import com.example.chatbar.domain.image.NovelAiHistoryImageSelection
import com.example.chatbar.domain.image.NovelAiImageGuidanceDraft
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiImageUseTarget
import com.example.chatbar.domain.image.NovelAiReferenceMode
import com.example.chatbar.domain.image.NovelAiStudioAssetRef
import com.example.chatbar.domain.image.NovelAiVibeReferenceDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NovelAiHistoryUiState(
    val entries: List<NovelAiGenerationHistoryEntry> = emptyList(),
    val searchQuery: String = "",
    val dateFilter: NovelAiHistoryDateFilter? = null,
    val filteredImages: List<NovelAiHistoryImageItem> = emptyList(),
    val studioModel: NovelAiImageModel = NovelAiImageModel.V4_5_FULL,
    val selectedImageKeys: List<String> = emptyList(),
    val batchTitle: String = "",
    val exportPlan: NovelAiGalleryExportPlan? = null,
    val exportConflictIndex: Int = 0,
    val exportDecisions: Map<String, NovelAiGalleryConflictDecision> = emptyMap(),
    val exportAuthorization: IntentSender? = null,
    val notice: String? = null,
    val applied: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null
) {
    val selectionMode: Boolean get() = selectedImageKeys.isNotEmpty()
    val currentExportConflict get() = exportPlan?.conflicts?.getOrNull(exportConflictIndex)
}

class NovelAiHistoryViewModel : ViewModel() {
    private val app = ChatBarApp.instance
    private val repository = app.novelAiStudioRepository
    private val storage = app.novelAiImageStorage
    private val guidanceAssets = app.novelAiStudioAssetStorage
    private val galleryExporter = NovelAiHistoryGalleryExporter(app)

    private val _uiState = MutableStateFlow(NovelAiHistoryUiState())
    val uiState: StateFlow<NovelAiHistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.initialize()
            val draft = repository.loadDraft()
            _uiState.update { it.copy(studioModel = draft.selectedModel) }
            repository.history.collect { entries ->
                _uiState.update { state -> state.withFilters(entries = entries) }
            }
        }
    }

    fun updateSearchQuery(query: String) = _uiState.update { state ->
        state.withFilters(searchQuery = query)
    }

    fun applyDateFilter(filter: NovelAiHistoryDateFilter?) = _uiState.update { state ->
        state.withFilters(dateFilter = filter)
    }

    fun startSelection(item: NovelAiHistoryImageItem) {
        val state = _uiState.value
        if (state.busy || state.exportPlan != null) return
        _uiState.update {
            it.copy(
                selectedImageKeys = NovelAiHistorySelectionPolicy.add(it.selectedImageKeys, item.key)
            )
        }
    }

    fun toggleSelection(item: NovelAiHistoryImageItem) {
        val state = _uiState.value
        if (state.busy || state.exportPlan != null) return
        _uiState.update {
            val selected = NovelAiHistorySelectionPolicy.toggle(it.selectedImageKeys, item.key)
            if (selected.isEmpty()) it.resetBatchSelection() else it.copy(selectedImageKeys = selected)
        }
    }

    fun clearSelection() = _uiState.update { state ->
        if (state.busy || state.exportPlan != null) state else state.resetBatchSelection()
    }

    fun updateBatchTitle(title: String) = _uiState.update { state ->
        state.copy(batchTitle = title)
    }

    fun prepareBatchExport() {
        val snapshot = _uiState.value
        if (snapshot.busy || snapshot.exportPlan != null || snapshot.selectedImageKeys.isEmpty()) return
        val selectedItems = snapshot.selectedItems()
        if (selectedItems.size != snapshot.selectedImageKeys.size) {
            _uiState.update { it.copy(error = "部分历史图片已发生变化，请重新选择") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null, notice = null) }
            runCatching {
                galleryExporter.prepare(
                    sources = selectedItems.mapIndexed { index, item ->
                        NovelAiGalleryExportSource(
                            key = item.key,
                            path = item.image.path,
                            createdAt = item.entry.createdAt,
                            selectionIndex = index + 1
                        )
                    },
                    customTitle = snapshot.batchTitle
                )
            }.onSuccess { plan ->
                if (plan.conflicts.isEmpty()) {
                    executeBatchExport(plan, emptyMap())
                } else {
                    _uiState.update {
                        it.copy(
                            busy = false,
                            exportPlan = plan,
                            exportConflictIndex = 0,
                            exportDecisions = emptyMap()
                        )
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(busy = false, error = "批量保存准备失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun resolveExportConflict(
        decision: NovelAiGalleryConflictDecision?,
        applyToRemaining: Boolean
    ) {
        val state = _uiState.value
        val plan = state.exportPlan ?: return
        val current = state.currentExportConflict ?: return
        if (decision == null) {
            _uiState.update {
                it.copy(
                    exportPlan = null,
                    exportConflictIndex = 0,
                    exportDecisions = emptyMap(),
                    notice = "已停止批量保存"
                )
            }
            return
        }
        val decisions = state.exportDecisions.toMutableMap().apply {
            put(current.key, decision)
            if (applyToRemaining) {
                plan.conflicts.drop(state.exportConflictIndex + 1).forEach { conflict ->
                    put(conflict.key, decision)
                }
            }
        }
        val nextIndex = plan.conflicts.indexOfFirst { it.key !in decisions }
        if (nextIndex < 0) {
            executeBatchExport(plan, decisions)
        } else {
            _uiState.update {
                it.copy(exportConflictIndex = nextIndex, exportDecisions = decisions)
            }
        }
    }

    fun markExportAuthorizationLaunched() = _uiState.update {
        it.copy(exportAuthorization = null)
    }

    fun resumeBatchExportAfterAuthorization(granted: Boolean) {
        val state = _uiState.value
        val plan = state.exportPlan ?: return
        if (!granted) {
            _uiState.update {
                it.copy(
                    exportPlan = null,
                    exportConflictIndex = 0,
                    exportDecisions = emptyMap(),
                    error = "未授予图库覆盖权限，批量保存已停止"
                )
            }
            return
        }
        executeBatchExport(plan, state.exportDecisions, authorizationGranted = true)
    }

    fun deleteSelectedImages() {
        val snapshot = _uiState.value
        if (snapshot.busy || snapshot.selectedImageKeys.isEmpty()) return
        val selectedItems = snapshot.selectedItems()
        if (selectedItems.size != snapshot.selectedImageKeys.size) {
            _uiState.update { it.copy(error = "部分历史图片已发生变化，请重新选择") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null, notice = null) }
            runCatching {
                repository.deleteHistoryImages(
                    selectedItems.map { item ->
                        NovelAiHistoryImageSelection(item.entry.id, item.image.path)
                    }
                )
            }.onSuccess { result ->
                _uiState.update {
                    it.resetBatchSelection().copy(
                        busy = false,
                        notice = buildString {
                            append("已删除 ${result.deletedCount} 张历史图片")
                            if (result.cleanupFailureCount > 0) {
                                append("；${result.cleanupFailureCount} 个暂存文件稍后清理")
                            }
                        }
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(busy = false, error = error.message ?: "批量删除失败")
                }
            }
        }
    }

    fun apply(entry: NovelAiGenerationHistoryEntry, image: NovelAiGenerationHistoryImage, mode: NovelAiHistoryApplyMode) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            runCatching {
                repository.applyHistory(entry, image, mode)
            }.onSuccess {
                _uiState.update { it.copy(busy = false, applied = true) }
            }.onFailure { error ->
                _uiState.update { it.copy(busy = false, error = "应用历史失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    fun useImage(item: NovelAiHistoryImageItem, target: NovelAiImageUseTarget) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            runCatching {
                val current = repository.loadDraft()
                if (current.selectedModel == NovelAiImageModel.V5_FULL &&
                    target in setOf(NovelAiImageUseTarget.PRECISE_REFERENCE, NovelAiImageUseTarget.VIBE_REFERENCE)
                ) error("V5 Full 暂不支持精确参考或氛围参考")
                if (target == NovelAiImageUseTarget.VIBE_REFERENCE &&
                    current.imageGuidance.vibes.size >= NovelAiImageGuidanceDraft.MAX_VIBES
                ) error("氛围参考已满；请回工作室管理")
                val fit = target == NovelAiImageUseTarget.IMAGE_TO_IMAGE || target == NovelAiImageUseTarget.INPAINT
                val asset = withContext(Dispatchers.IO) {
                    guidanceAssets.copyExisting(item.image.path, current.activeSettings.sizeTier, fit)
                }
                val mask = if (target == NovelAiImageUseTarget.INPAINT) {
                    withContext(Dispatchers.IO) { guidanceAssets.createEmptyMask(asset.width, asset.height) }
                } else null
                val guidance = when (target) {
                    NovelAiImageUseTarget.IMAGE_TO_IMAGE -> current.imageGuidance.copy(
                        action = NovelAiGenerationAction.IMAGE_TO_IMAGE,
                        baseImage = asset,
                        maskImage = null
                    )
                    NovelAiImageUseTarget.INPAINT -> current.imageGuidance.copy(
                        action = NovelAiGenerationAction.INPAINT,
                        baseImage = asset,
                        maskImage = mask
                    )
                    NovelAiImageUseTarget.PRECISE_REFERENCE -> current.imageGuidance.copy(
                        referenceMode = NovelAiReferenceMode.PRECISE,
                        preciseReference = current.imageGuidance.preciseReference.copy(asset = asset)
                    )
                    NovelAiImageUseTarget.VIBE_REFERENCE -> current.imageGuidance.copy(
                        referenceMode = NovelAiReferenceMode.VIBE,
                        vibes = current.imageGuidance.vibes + NovelAiVibeReferenceDraft(asset = asset)
                    )
                }
                repository.saveUndoDraft(current)
                repository.saveDraft(current.copy(imageGuidance = guidance))
                repository.clearGuidanceCheckpoint()
                repository.requestGuidanceEditor(target)
            }.onSuccess {
                _uiState.update { it.copy(busy = false, applied = true) }
            }.onFailure { error ->
                _uiState.update { it.copy(busy = false, error = "用作图像引导失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    fun delete(entry: NovelAiGenerationHistoryEntry) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            runCatching {
                val staged = storage.stageSessionDelete(entry.id)
                try {
                    repository.deleteHistory(entry.id)
                } catch (error: Throwable) {
                    if (staged != null) check(storage.restoreStagedSession(staged)) { "历史删除失败，且图片目录恢复失败" }
                    throw error
                }
                if (staged != null) check(storage.commitStagedSessionDelete(staged)) { "历史已删除，但暂存图片清理失败" }
            }.onSuccess {
                _uiState.update { it.copy(busy = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(busy = false, error = error.message ?: "删除失败") }
            }
        }
    }

    fun clearAll() {
        if (_uiState.value.busy) return
        val entries = _uiState.value.entries
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            runCatching {
                val staged = mutableListOf<com.example.chatbar.domain.image.NovelAiStagedSessionDeletion>()
                try {
                    entries.forEach { entry -> storage.stageSessionDelete(entry.id)?.let(staged::add) }
                    repository.clearHistory()
                } catch (error: Throwable) {
                    staged.asReversed().forEach { deletion -> storage.restoreStagedSession(deletion) }
                    throw error
                }
                val failures = staged.count { !storage.commitStagedSessionDelete(it) }
                check(failures == 0) { "记录已清空，$failures 个暂存图片目录清理失败" }
            }.onSuccess {
                _uiState.update { it.copy(busy = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(busy = false, error = error.message ?: "清空失败") }
            }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun reportError(message: String) = _uiState.update { it.copy(error = message) }

    fun consumeNotice() = _uiState.update { it.copy(notice = null) }

    fun consumeApplied() = _uiState.update { it.copy(applied = false) }

    private fun NovelAiHistoryUiState.withFilters(
        entries: List<NovelAiGenerationHistoryEntry> = this.entries,
        searchQuery: String = this.searchQuery,
        dateFilter: NovelAiHistoryDateFilter? = this.dateFilter
    ): NovelAiHistoryUiState {
        val filtered = NovelAiHistoryFilterPolicy.filter(entries, searchQuery, dateFilter)
        val availableKeys = entries.asSequence()
            .flatMap { entry -> entry.images.asSequence().map { image -> "${entry.id}\u0000${image.path}" } }
            .toSet()
        val retainedSelection = NovelAiHistorySelectionPolicy.retain(selectedImageKeys, availableKeys)
        return copy(
            entries = entries,
            searchQuery = searchQuery,
            dateFilter = dateFilter,
            filteredImages = filtered,
            selectedImageKeys = retainedSelection,
            batchTitle = batchTitle.takeIf { retainedSelection.isNotEmpty() }.orEmpty()
        )
    }

    private fun NovelAiHistoryUiState.selectedItems(): List<NovelAiHistoryImageItem> {
        val byKey = entries.asSequence().flatMap { entry ->
            entry.images.asSequence().mapIndexed { index, image ->
                NovelAiHistoryImageItem(entry, image, index)
            }
        }.associateBy(NovelAiHistoryImageItem::key)
        return selectedImageKeys.mapNotNull(byKey::get)
    }

    private fun NovelAiHistoryUiState.resetBatchSelection(): NovelAiHistoryUiState = copy(
        selectedImageKeys = emptyList(),
        batchTitle = "",
        exportPlan = null,
        exportConflictIndex = 0,
        exportDecisions = emptyMap(),
        exportAuthorization = null
    )

    private fun executeBatchExport(
        plan: NovelAiGalleryExportPlan,
        decisions: Map<String, NovelAiGalleryConflictDecision>,
        authorizationGranted: Boolean = false
    ) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    busy = true,
                    exportPlan = plan,
                    exportDecisions = decisions,
                    error = null,
                    notice = null
                )
            }
            runCatching {
                galleryExporter.execute(plan, decisions, authorizationGranted)
            }.onSuccess { execution ->
                when (execution) {
                    is NovelAiGalleryExportExecution.AuthorizationRequired -> {
                        _uiState.update {
                            it.copy(busy = false, exportAuthorization = execution.intentSender)
                        }
                    }
                    is NovelAiGalleryExportExecution.Completed -> {
                        val result = execution.result
                        _uiState.update {
                            it.resetBatchSelection().copy(
                                busy = false,
                                notice = buildString {
                                    append("已保存 ${result.savedCount} 张")
                                    if (result.skippedCount > 0) append("，跳过 ${result.skippedCount} 张")
                                    if (result.remainingDuplicateCount > 0) {
                                        append("；图库仍有 ${result.remainingDuplicateCount} 个旧同名副本")
                                    }
                                }
                            )
                        }
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        busy = false,
                        exportPlan = null,
                        exportConflictIndex = 0,
                        exportDecisions = emptyMap(),
                        error = "批量保存失败：${error.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

}
