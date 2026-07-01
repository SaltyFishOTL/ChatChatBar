package com.example.chatbar.domain.card

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.WorldBookRepository
import kotlinx.serialization.Serializable

class WorldBookMigration(
    private val storage: JsonFileStorage,
    private val characterRepository: CharacterRepository,
    private val worldBookRepository: WorldBookRepository
) {
    suspend fun run() {
        characterRepository.initialize()
        worldBookRepository.initialize()
        var migrated = 0
        characterRepository.getAll().forEach { card ->
            val ids = mutableListOf<String>()
            ids += card.worldBookIds
            card.boundWorldBookId?.takeIf { it.isNotBlank() }?.let { ids += it }
            card.characterBook?.let { embedded ->
                val existing = worldBookRepository.getById(embedded.id)
                if (existing == null) {
                    worldBookRepository.save(
                        embedded.copy(
                            name = embedded.name.ifBlank { "${card.name} 世界书" },
                            sourcePresetKey = card.sourcePresetKey,
                            sourcePresetVersion = card.sourcePresetVersion,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
                ids += embedded.id
            }
            val normalizedIds = ids.filter { it.isNotBlank() }.distinct()
            if (normalizedIds != card.worldBookIds || card.characterBook != null || card.boundWorldBookId != null) {
                characterRepository.save(
                    card.copy(
                        worldBookIds = normalizedIds,
                        characterBook = null,
                        boundWorldBookId = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                migrated++
            }
        }
        storage.saveSingleton(
            STATE_TYPE,
            WorldBookMigrationState(lastRunAt = System.currentTimeMillis(), migratedCharacters = migrated),
            WorldBookMigrationState.serializer()
        )
    }

    private companion object {
        const val STATE_TYPE = "world_book_migration_state"
    }
}

@Serializable
private data class WorldBookMigrationState(
    val lastRunAt: Long,
    val migratedCharacters: Int
)
