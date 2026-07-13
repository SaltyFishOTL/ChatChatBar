package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.SaveSlot
import com.example.chatbar.data.local.entity.SaveSlotSummary
import com.example.chatbar.data.local.entity.toSummary
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

    private val _saveSlots = MutableStateFlow<List<SaveSlotSummary>>(emptyList())
    val saveSlots: Flow<List<SaveSlotSummary>> = _saveSlots.asStateFlow()

    private var initialized = false

    suspend fun initialize() {
        if (initialized) return
        refreshCache()
        initialized = true
    }

    private suspend fun refreshCache() {
        _saveSlots.value = storage.loadAllMapped(ENTITY_TYPE, SaveSlot.serializer(), SaveSlot::toSummary)
            .sortedByDescending { it.createdAt }
    }

    suspend fun getAll(): List<SaveSlotSummary> {
        initialize()
        return _saveSlots.value
    }

    /** 获取某会话的所有存档 */
    suspend fun getBySessionId(sessionId: String): List<SaveSlotSummary> {
        return getAll().filter { it.sessionId == sessionId }
    }

    fun observeBySessionId(sessionId: String): Flow<List<SaveSlotSummary>> {
        return _saveSlots.map { list ->
            list.filter { it.sessionId == sessionId }
                .sortedByDescending { it.createdAt }
        }
    }

    suspend fun getById(id: String): SaveSlot? {
        return storage.loadEntity(ENTITY_TYPE, id, SaveSlot.serializer())
    }

    suspend fun save(slot: SaveSlot) {
        storage.saveEntityUncached(ENTITY_TYPE, slot.id, slot, SaveSlot.serializer())
        _saveSlots.value = (_saveSlots.value.filterNot { it.id == slot.id } + slot.toSummary())
            .sortedByDescending { it.createdAt }
    }

    suspend fun delete(id: String) {
        storage.deleteEntityUncached(ENTITY_TYPE, id)
        _saveSlots.value = _saveSlots.value.filterNot { it.id == id }
    }

    /** 删除某会话的所有存档 */
    suspend fun deleteBySessionId(sessionId: String) {
        storage.deleteWhere(ENTITY_TYPE, SaveSlot.serializer()) { it.sessionId == sessionId }
        _saveSlots.value = _saveSlots.value.filterNot { it.sessionId == sessionId }
    }
}
