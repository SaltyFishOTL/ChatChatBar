package com.example.chatbar.domain.deletion

import android.util.Log
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.MomentRepository
import com.example.chatbar.data.repository.SaveSlotRepository
import com.example.chatbar.domain.rag.RagRepository
import com.example.chatbar.domain.image.NovelAiImageStorage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

class DeletionCoordinator(
    private val storage: JsonFileStorage,
    private val characterRepository: CharacterRepository,
    private val chatRepository: ChatRepository,
    private val saveSlotRepository: SaveSlotRepository,
    private val ragRepository: RagRepository,
    private val momentRepository: MomentRepository,
    private val imageStorage: NovelAiImageStorage
) {
    private val cleanupMutex = Mutex()

    suspend fun deleteCharacter(id: String) {
        val card = characterRepository.getById(id) ?: return
        enqueue(PendingDeletion.forCharacter(card))
        characterRepository.delete(id)
        drainPending()
    }

    suspend fun deleteSession(id: String) {
        if (chatRepository.getSession(id) == null) return
        enqueue(PendingDeletion.forSession(id))
        chatRepository.deleteSessionRecord(id)
        drainPending()
    }

    suspend fun resumePending() {
        drainPending()
    }

    private suspend fun enqueue(task: PendingDeletion) {
        storage.saveEntity(TASK_TYPE, task.id, task, PendingDeletion.serializer())
    }

    private suspend fun drainPending() = cleanupMutex.withLock {
        storage.loadAll(TASK_TYPE, PendingDeletion.serializer())
            .sortedBy { it.createdAt }
            .forEach { task ->
                try {
                    process(task)
                    storage.deleteEntity<PendingDeletion>(TASK_TYPE, task.id)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    Log.e(TAG, "Pending deletion cleanup failed: ${task.id}", error)
                }
            }
    }

    private suspend fun process(task: PendingDeletion) {
        when (task.type) {
            PendingDeletionType.CHARACTER -> {
                characterRepository.delete(task.ownerId)
                task.filePaths.forEach(::deleteOwnedFile)
                ragRepository.deleteChunksBySource(ChunkSourceType.DOCUMENT, task.ownerId)
                momentRepository.deleteForCharacter(task.ownerId).mapNotNull { it.imagePath }.forEach { path ->
                    check(imageStorage.deleteIfOwned(path)) { "无法清理朋友圈图片: $path" }
                }
            }

            PendingDeletionType.SESSION -> {
                chatRepository.deleteSessionRecord(task.ownerId)
                chatRepository.deleteMessagesForSession(task.ownerId)
                saveSlotRepository.deleteBySessionId(task.ownerId)
                ragRepository.deleteChunksBySource(ChunkSourceType.CHAT_MEMORY, task.ownerId)
                check(imageStorage.deleteSession(task.ownerId)) { "无法清理会话生成图片: ${task.ownerId}" }
            }
        }
    }

    private fun deleteOwnedFile(path: String) {
        val file = File(path)
        check(!file.exists() || file.delete()) { "无法删除资源文件：$path" }
    }

    private companion object {
        const val TAG = "DeletionCoordinator"
        const val TASK_TYPE = "pending_deletions"
    }
}

@Serializable
private data class PendingDeletion(
    val id: String,
    val type: PendingDeletionType,
    val ownerId: String,
    val filePaths: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun forCharacter(card: CharacterCard) = PendingDeletion(
            id = "character_${card.id}",
            type = PendingDeletionType.CHARACTER,
            ownerId = card.id,
            filePaths = buildList {
                card.avatar?.let(::add)
                card.chatBackground?.let(::add)
                card.characters.mapNotNullTo(this) { it.appearanceImage }
                card.customDocuments.mapTo(this) { it.filePath }
            }.filter(String::isNotBlank).distinct()
        )

        fun forSession(id: String) = PendingDeletion(
            id = "session_$id",
            type = PendingDeletionType.SESSION,
            ownerId = id
        )
    }
}

@Serializable
private enum class PendingDeletionType {
    CHARACTER,
    SESSION
}
