package com.example.chatbar.domain.deletion

import android.util.Log
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.domain.backup.LocalDataMaintenance
import com.example.chatbar.domain.image.NovelAiImageStorage
import com.example.chatbar.domain.rag.ChatMemoryIndexPolicy
import com.example.chatbar.domain.rag.RagRepository
import com.example.chatbar.domain.rag.isAutomaticReplyMemory
import com.example.chatbar.domain.rag.isChatMemoryForSession
import com.example.chatbar.domain.voice.FishAudioStorage
import com.example.chatbar.domain.voice.VoicePlaybackController
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class ReplyRemovalCoordinator(
    private val storage: JsonFileStorage,
    private val chat: ChatRepository,
    private val voices: VoiceMessageRepository,
    private val audio: FishAudioStorage,
    private val images: NovelAiImageStorage,
    private val playback: VoicePlaybackController,
    private val rag: RagRepository = RagRepository(storage)
) {
    private val mutex = Mutex()

    suspend fun remove(expected: ChatMessage, replacement: ChatMessage?, versionId: String?): String? = mutex.withLock {
        require((replacement == null) == (versionId == null))
        LocalDataMaintenance.access {
            var removal: ReplyRemoval? = null
            check(voices.prepareReplyRemoval(expected, versionId) { collectAudioPaths ->
                chat.commitReplyRemoval(expected, replacement) {
                    val pending = ReplyRemoval(
                        id = UUID.randomUUID().toString(), sessionId = expected.sessionId, messageId = expected.id,
                        versionId = versionId, role = expected.role, sourceTurnId = expected.sourceTurnId,
                        sourceTurnOrder = expected.sourceTurnOrder, timelineTurn = expected.timelineTurn,
                        images = if (replacement == null) {
                            (expected.images + expected.generatedImageMetadata.map { it.imagePath }).distinct()
                        } else emptyList(),
                        audioPaths = collectAudioPaths(),
                        invalidateAutomaticMemory = replacement != null &&
                            expected.displayContent != replacement.displayContent &&
                            ChatMemoryIndexPolicy.contributesToIndex(expected)
                    )
                    storage.saveEntityUncached(STORE, pending.id, pending, ReplyRemoval.serializer())
                    removal = pending
                }
            }) { "消息已变化，请重新操作" }
            try {
                finish(checkNotNull(removal))
                null
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                "回复已删除，附件或语音文件清理失败，将在重新打开会话时重试"
            }
        }
    }

    suspend fun retryMessage(sessionId: String, messageId: String, versionId: String?) = mutex.withLock {
        LocalDataMaintenance.access {
            val matching = pending().filter { it.sessionId == sessionId && it.messageId == messageId }
            var committed = false
            matching.forEach { removal ->
                if (finish(removal) && (removal.versionId == null || removal.versionId == versionId)) {
                    committed = true
                }
            }
            committed
        }
    }

    suspend fun resumePending(sessionId: String? = null) = mutex.withLock {
        LocalDataMaintenance.access {
            val removals = try {
                pending()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Log.w("ReplyRemoval", "Local cleanup could not be read")
                return@access
            }
            removals.filter { sessionId == null || it.sessionId == sessionId }.forEach { removal ->
                try {
                    finish(removal)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    Log.w("ReplyRemoval", "Local cleanup is still pending")
                }
            }
        }
    }

    /** A new save slot must not capture voice records from a half-completed reply removal. */
    suspend fun <T> withSettledSession(sessionId: String, capture: suspend () -> T): T = mutex.withLock {
        LocalDataMaintenance.access {
            pending().filter { it.sessionId == sessionId }.forEach { finish(it) }
            capture()
        }
    }

    /** The same journal that makes invalidation retryable also keeps pending stale chunks out of recall. */
    suspend fun getChunksForRetrieval(characterId: String?, sessionId: String?) = mutex.withLock {
        val chunks = rag.getChunksForRetrieval(characterId, sessionId)
        if (sessionId == null) return@withLock chunks
        val invalidations = try {
            pending().filter { it.sessionId == sessionId && it.invalidateAutomaticMemory }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withLock chunks.filterNot {
                it.isChatMemoryForSession(sessionId) && ChatMemoryIndexPolicy.isAutomaticChunk(it)
            }
        }
        chunks.filterNot { chunk ->
            invalidations.any { chunk.isAutomaticReplyMemory(sessionId, it.sourceTurnId, it.messageId) }
        }
    }

    private suspend fun pending(): List<ReplyRemoval> {
        val removals = mutableListOf<ReplyRemoval>()
        storage.forEachUncached(STORE, ReplyRemoval.serializer(), requireCompleteRead = true) { removals += it }
        return removals
    }

    private suspend fun finish(removal: ReplyRemoval) = withContext(Dispatchers.IO) {
        val committed = voices.finishReplyRemoval(
            removal, { cleanup ->
                chat.finishReplyRemoval(removal) {
                    if (removal.invalidateAutomaticMemory) {
                        rag.invalidateReplyMemory(removal.sessionId, removal.sourceTurnId, removal.messageId)
                        // Media retries must leave memory rebuilt from the surviving reply intact.
                        storage.saveEntityUncached(
                            STORE, removal.id, removal.copy(invalidateAutomaticMemory = false), ReplyRemoval.serializer()
                        )
                    }
                    cleanup()
                }
            },
            { ids -> if (playback.state.value.currentVoiceId in ids) playback.stop() },
            audio::deleteIfOwned
        )
        if (!committed) voices.deleteUnownedReplyAudio(removal.audioPaths, audio::deleteIfOwned)
        if (removal.images.isNotEmpty()) {
            chat.deleteUnreferencedChatImages(removal.images) { path ->
                check(images.deleteChatAttachmentIfOwned(path)) { "无法清理已删除回复的图片文件" }
            }
        }
        storage.deleteEntityUncached(STORE, removal.id, requireSuccessfulDelete = true)
        committed
    }

    companion object {
        const val STORE = "pending_reply_removals"

        /** Run under the exclusive data lease. The local recovery journal is not portable. */
        fun requireSettledForBackup(filesDir: File) {
            val directory = File(filesDir, "entities/$STORE")
            if (Files.notExists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return
            val entries = checkNotNull(directory.listFiles()) { "无法核对本地回复清理状态" }
            check(entries.none { it.extension == "json" }) { "仍有回复附件待清理，请重新打开会话后再试备份或恢复" }
        }
    }
}
