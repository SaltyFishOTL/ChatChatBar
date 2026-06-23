package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.FormatCard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 格式卡片仓库
 */
class FormatCardRepository(private val storage: JsonFileStorage) {

    companion object {
        private const val ENTITY_TYPE = "format_cards"
    }

    private val _formatCards = MutableStateFlow<List<FormatCard>>(emptyList())
    val formatCards: Flow<List<FormatCard>> = _formatCards.asStateFlow()

    private var initialized = false

    suspend fun initialize() {
        if (initialized) return
        refreshCache()
        initialized = true
    }

    private suspend fun refreshCache() {
        _formatCards.value = storage.loadAll(ENTITY_TYPE, FormatCard.serializer())
            .sortedWith(compareByDescending<FormatCard> { it.isDefault }.thenBy { it.name })
    }

    suspend fun getAll(): List<FormatCard> {
        initialize()
        return _formatCards.value
    }

    suspend fun getById(id: String): FormatCard? {
        return storage.loadEntity(ENTITY_TYPE, id, FormatCard.serializer())
    }

    suspend fun getDefault(): FormatCard? {
        return getAll().firstOrNull { it.isDefault }
    }

    suspend fun save(card: FormatCard) {
        // 如果设为默认，取消其他默认
        if (card.isDefault) {
            getAll().filter { it.isDefault && it.id != card.id }.forEach { existing ->
                storage.saveEntity(
                    ENTITY_TYPE,
                    existing.id,
                    existing.copy(isDefault = false),
                    FormatCard.serializer()
                )
            }
        }
        storage.saveEntity(ENTITY_TYPE, card.id, card, FormatCard.serializer())
        refreshCache()
    }

    suspend fun delete(id: String) {
        storage.deleteEntity<FormatCard>(ENTITY_TYPE, id)
        refreshCache()
    }

    suspend fun setDefault(id: String) {
        getById(id)?.let { card ->
            save(card.copy(isDefault = true))
        }
    }
}
