package com.example.chatbar.domain.card

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.CharacterRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

class CharacterSpeakerMigration(
    private val storage: JsonFileStorage,
    private val characters: CharacterRepository
) {
    private val mutex = Mutex()

    suspend fun run() = mutex.withLock {
        characters.initialize()
        var migratedCards = 0
        characters.getAll().forEach { card ->
            val normalized = CharacterSpeakerNamePolicy.normalizeUnique(card.characters)
            if (normalized != card.characters) {
                characters.save(card.copy(characters = normalized))
                migratedCards++
            }
        }
        storage.saveSingleton(
            STATE_TYPE,
            CharacterSpeakerMigrationState(
                lastRunAt = System.currentTimeMillis(),
                migratedCards = migratedCards
            ),
            CharacterSpeakerMigrationState.serializer()
        )
    }

    private companion object {
        const val STATE_TYPE = "character_speaker_migration_state"
    }
}

@Serializable
private data class CharacterSpeakerMigrationState(
    val lastRunAt: Long,
    val migratedCards: Int
)
