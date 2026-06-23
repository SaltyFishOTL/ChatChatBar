package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 角色卡片仓库
 */
class CharacterRepository(private val storage: JsonFileStorage) {

    companion object {
        private const val ENTITY_TYPE = "character_cards"
    }

    private val _characters = MutableStateFlow<List<CharacterCard>>(emptyList())
    val characters: Flow<List<CharacterCard>> = _characters.asStateFlow()

    private var initialized = false

    /** 初始化 - 从磁盘加载全部角色卡 */
    suspend fun initialize() {
        if (initialized) return
        refreshCache()
        initialized = true
    }

    private suspend fun refreshCache() {
        _characters.value = storage.loadAll(ENTITY_TYPE, CharacterCard.serializer())
            .sortedByDescending { it.updatedAt }
    }

    suspend fun getAll(): List<CharacterCard> {
        initialize()
        return _characters.value
    }

    suspend fun getById(id: String): CharacterCard? {
        return storage.loadEntity(ENTITY_TYPE, id, CharacterCard.serializer())
    }

    suspend fun save(card: CharacterCard) {
        storage.saveEntity(ENTITY_TYPE, card.id, card, CharacterCard.serializer())
        refreshCache()
    }

    suspend fun update(card: CharacterCard) {
        val updated = card.copy(updatedAt = System.currentTimeMillis())
        save(updated)
    }

    suspend fun delete(id: String) {
        storage.deleteEntity<CharacterCard>(ENTITY_TYPE, id)
        _characters.value = _characters.value.filterNot { it.id == id }
    }

    suspend fun exists(id: String): Boolean {
        return storage.exists(ENTITY_TYPE, id)
    }

    /** 搜索角色卡（名称模糊匹配） */
    suspend fun search(query: String): List<CharacterCard> {
        return getAll().filter { card ->
            card.name.contains(query, ignoreCase = true) ||
                card.characters.any { it.name.contains(query, ignoreCase = true) }
        }
    }
}
