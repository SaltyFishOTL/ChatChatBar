package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.SpeakerTagRename
import com.example.chatbar.data.local.entity.SpeakerTagRenameTask
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SpeakerTagHistoryService(
    private val characterRepository: CharacterRepository,
    private val chatRepository: ChatRepository
) {
    private val mutex = Mutex()

    fun createTask(
        characterCardId: String,
        expectedCardUpdatedAt: Long,
        renames: List<SpeakerTagRename>
    ): SpeakerTagRenameTask? {
        val effective = renames.filter { rename ->
            rename.oldName.isNotBlank() &&
                rename.newName.isNotBlank() &&
                !rename.oldName.trim().equals(rename.newName.trim(), ignoreCase = false)
        }
        if (effective.isEmpty()) return null
        return SpeakerTagRenameTask(
            id = UUID.randomUUID().toString(),
            characterCardId = characterCardId,
            expectedCardUpdatedAt = expectedCardUpdatedAt,
            renames = effective,
            createdAt = System.currentTimeMillis()
        )
    }

    suspend fun execute(characterCardId: String, taskId: String): Int = mutex.withLock {
        val card = characterRepository.getById(characterCardId) ?: return@withLock 0
        val task = card.pendingSpeakerRenameTasks.firstOrNull { it.id == taskId } ?: return@withLock 0
        try {
            val updated = chatRepository.rewriteSpeakerTagsForCharacterCard(
                characterCardId = characterCardId,
                renames = task.renames
            )
            val latestCard = characterRepository.getById(characterCardId) ?: card
            characterRepository.save(
                latestCard.copy(
                    pendingSpeakerRenameTasks = latestCard.pendingSpeakerRenameTasks.filterNot { it.id == taskId }
                )
            )
            updated
        } catch (error: Throwable) {
            val latestCard = characterRepository.getById(characterCardId) ?: card
            characterRepository.save(
                latestCard.copy(
                    pendingSpeakerRenameTasks = latestCard.pendingSpeakerRenameTasks.map { pending ->
                        if (pending.id == taskId) {
                            pending.copy(lastError = error.message ?: error::class.java.simpleName)
                        } else {
                            pending
                        }
                    }
                )
            )
            throw error
        }
    }

    suspend fun resumePending() {
        characterRepository.initialize()
        chatRepository.initialize()
        characterRepository.getAll()
            .forEach { card ->
                for (task in card.pendingSpeakerRenameTasks.sortedBy(SpeakerTagRenameTask::createdAt)) {
                    if (runCatching { execute(card.id, task.id) }.isFailure) break
                }
            }
    }

}
