package com.example.chatbar.ui.imageprompt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry
import com.example.chatbar.domain.image.NovelAiGenerationHistoryImage
import com.example.chatbar.domain.image.NovelAiHistoryApplyMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NovelAiHistoryUiState(
    val entries: List<NovelAiGenerationHistoryEntry> = emptyList(),
    val searchQuery: String = "",
    val dateFilter: NovelAiHistoryDateFilter? = null,
    val filteredImages: List<NovelAiHistoryImageItem> = emptyList(),
    val applied: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null
)

class NovelAiHistoryViewModel : ViewModel() {
    private val app = ChatBarApp.instance
    private val repository = app.novelAiStudioRepository
    private val storage = app.novelAiImageStorage

    private val _uiState = MutableStateFlow(NovelAiHistoryUiState())
    val uiState: StateFlow<NovelAiHistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.initialize()
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

    fun consumeApplied() = _uiState.update { it.copy(applied = false) }

    private fun NovelAiHistoryUiState.withFilters(
        entries: List<NovelAiGenerationHistoryEntry> = this.entries,
        searchQuery: String = this.searchQuery,
        dateFilter: NovelAiHistoryDateFilter? = this.dateFilter
    ): NovelAiHistoryUiState = copy(
        entries = entries,
        searchQuery = searchQuery,
        dateFilter = dateFilter,
        filteredImages = NovelAiHistoryFilterPolicy.filter(entries, searchQuery, dateFilter)
    )

}
