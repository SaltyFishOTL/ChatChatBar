package com.example.chatbar.ui.worldbook

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.local.entity.WorldBookPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WorldBookEditViewModel(private val worldBookId: String?) : ViewModel() {
    private val repository = ChatBarApp.instance.worldBookRepository

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

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            if (worldBookId != null) {
                repository.getById(worldBookId)?.let { book ->
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
            }
        }
    }

    fun save(onSuccess: () -> Unit) {
        if (name.isBlank()) return
        _isSaving.value = true
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val budget = tokenBudget.toIntOrNull()
            val book = _worldBook.value?.copy(
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
                id = worldBookId ?: java.util.UUID.randomUUID().toString(),
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
            _worldBook.value = book
            _isSaving.value = false
            onSuccess()
        }
    }

    fun addEntry(entry: WorldBookEntry) {
        entries.add(entry)
    }

    fun updateEntry(index: Int, entry: WorldBookEntry) {
        if (index in entries.indices) entries[index] = entry
    }

    fun deleteEntry(index: Int) {
        if (index in entries.indices) entries.removeAt(index)
    }

    fun toggleEntry(index: Int) {
        if (index in entries.indices) {
            val e = entries[index]
            entries[index] = e.copy(enabled = !e.enabled)
        }
    }
}
