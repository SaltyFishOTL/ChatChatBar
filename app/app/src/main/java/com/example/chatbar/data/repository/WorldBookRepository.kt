package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.WorldBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class WorldBookRepository(private val storage: JsonFileStorage) {

    companion object {
        private const val ENTITY_TYPE = "world_books"
    }

    private val _worldBooks = MutableStateFlow<List<WorldBook>>(emptyList())
    val worldBooks: Flow<List<WorldBook>> = _worldBooks.asStateFlow()

    private var initialized = false

    suspend fun initialize() {
        if (initialized) return
        refreshCache()
        initialized = true
    }

    private suspend fun refreshCache() {
        _worldBooks.value = storage.loadAll(ENTITY_TYPE, WorldBook.serializer())
            .sortedBy { it.name }
    }

    suspend fun getAll(): List<WorldBook> {
        initialize()
        return _worldBooks.value
    }

    suspend fun getById(id: String): WorldBook? {
        return storage.loadEntity(ENTITY_TYPE, id, WorldBook.serializer())
    }

    suspend fun save(book: WorldBook) {
        storage.saveEntity(ENTITY_TYPE, book.id, book.copy(updatedAt = System.currentTimeMillis()), WorldBook.serializer())
        refreshCache()
    }

    suspend fun delete(id: String) {
        storage.deleteEntity<WorldBook>(ENTITY_TYPE, id)
        refreshCache()
    }
}
