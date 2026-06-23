package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.SaveSlot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * 存档槽位仓库
 */
class SaveSlotRepository(private val storage: JsonFileStorage) {

    companion object {
        private const val ENTITY_TYPE = "save_slots"
    }

    private val _saveSlots = MutableStateFlow<List<SaveSlot>>(emptyList())
    val saveSlots: Flow<List<SaveSlot>> = _saveSlots.asStateFlow()

    private var initialized = false

    suspend fun initialize() {
        if (initialized) return
        refreshCache()
        initialized = true
    }

    private suspend fun refreshCache() {
        _saveSlots.value = storage.loadAll(ENTITY_TYPE, SaveSlot.serializer())
            .sortedByDescending { it.createdAt }
    }

    suspend fun getAll(): List<SaveSlot> {
        initialize()
        return _saveSlots.value
    }

    /** 获取某会话的所有存档 */
    suspend fun getBySessionId(sessionId: String): List<SaveSlot> {
        return getAll().filter { it.sessionId == sessionId }
    }

    fun observeBySessionId(sessionId: String): Flow<List<SaveSlot>> {
        return _saveSlots.map { list ->
            list.filter { it.sessionId == sessionId }
                .sortedByDescending { it.createdAt }
        }
    }

    suspend fun getById(id: String): SaveSlot? {
        return storage.loadEntity(ENTITY_TYPE, id, SaveSlot.serializer())
    }

    suspend fun save(slot: SaveSlot) {
        storage.saveEntity(ENTITY_TYPE, slot.id, slot, SaveSlot.serializer())
        refreshCache()
    }

    suspend fun delete(id: String) {
        storage.deleteEntity<SaveSlot>(ENTITY_TYPE, id)
        refreshCache()
    }

    /** 删除某会话的所有存档 */
    suspend fun deleteBySessionId(sessionId: String) {
        storage.deleteWhere(ENTITY_TYPE, SaveSlot.serializer()) { it.sessionId == sessionId }
        _saveSlots.value = _saveSlots.value.filterNot { it.sessionId == sessionId }
    }
}
