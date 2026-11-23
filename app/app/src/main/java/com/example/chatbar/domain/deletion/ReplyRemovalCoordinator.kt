package com.example.chatbar.domain.deletion

import android.util.Log
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.VectorChunk
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.ChatRagSourceRevision
import com.example.chatbar.data.repository.ReplyAudioFileCleanupException
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.domain.backup.LocalDataMaintenance
import com.example.chatbar.domain.image.NovelAiImageStorage
import com.example.chatbar.domain.rag.ChatMemoryIndexPolicy
import com.example.chatbar.domain.rag.ChatMemoryProjection
import com.example.chatbar.domain.rag.RagRepository
import com.example.chatbar.domain.rag.currentReplySourceProof
import com.example.chatbar.domain.rag.isAutomaticReplyMemory
import com.example.chatbar.domain.rag.isChatMemoryForSession
import com.example.chatbar.domain.rag.isCurrentForReplyRemoval
import com.example.chatbar.domain.rag.metadataMessageIds
import com.example.chatbar.domain.rag.withCurrentReplyRemovals
import com.example.chatbar.domain.voice.FishAudioStorage
import com.example.chatbar.domain.voice.VoicePlaybackController
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
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

    data class RemovalCommit(val cleanupError: String?)

    suspend fun remove(expected: ChatMessage, replacement: ChatMessage?, versionId: String?): String? =
        checkNotNull(remove(expected, replacement, versionId, onlyIfUnvoiced = false)) {
            "消息已变化，请重新操作"
        }.cleanupError

    /** An attachment action can remove the empty row only while it has no stored recording. */
    suspend fun removeIfUnvoiced(expected: ChatMessage): RemovalCommit? =
        remove(expected, replacement = null, versionId = null, onlyIfUnvoiced = true)

    private suspend fun remove(
        expected: ChatMessage,
        replacement: ChatMessage?,
        versionId: String?,
        onlyIfUnvoiced: Boolean
    ): RemovalCommit? = mutex.withLock {
        require((replacement == null) == (versionId == null))
        LocalDataMaintenance.access {
            var removal: ReplyRemoval? = null
            var messageCommitted = false
            try {
                val prepared = voices.prepareReplyRemoval(
                    expected, versionId, playback::removeQueuedVoices, onlyIfUnvoiced
                ) { collectMedia ->
                    chat.commitReplyRemoval(expected, replacement) {
                        val media = collectMedia()
                        val pending = ReplyRemoval(
                            id = UUID.randomUUID().toString(), sessionId = expected.sessionId, messageId = expected.id,
                            versionId = versionId, role = expected.role, sourceTurnId = expected.sourceTurnId,
                            sourceTurnOrder = expected.sourceTurnOrder, timelineTurn = expected.timelineTurn,
                            images = if (replacement == null) {
                                (expected.images + expected.generatedImageMetadata.map { it.imagePath }).distinct()
                            } else emptyList(),
                            audioPaths = media.audioPaths,
                            invalidateAutomaticMemory = ChatMemoryIndexPolicy.contributesToIndex(expected) &&
                                (replacement == null || expected.displayContent != replacement.displayContent),
                            legacyVoiceAnchorWitnessId = media.legacyAnchorWitnessId
                        )
                        storage.saveEntityUncached(STORE, pending.id, pending, ReplyRemoval.serializer())
                        removal = pending
                    }.also { messageCommitted = it }
                }
                if (!prepared) return@access null
                finish(checkNotNull(removal))
                RemovalCommit(cleanupError = null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ReplyAudioFileCleanupException) {
                RemovalCommit(error.message)
            } catch (error: Exception) {
                if (!messageCommitted) throw error
                RemovalCommit("回复已删除，相关索引或附件清理尚未完成，将在重新打开会话时重试")
            }
        }
    }

    suspend fun retryMessage(sessionId: String, messageId: String, versionId: String?) = mutex.withLock {
        LocalDataMaintenance.access {
            val matching = pending().filter {
                it.sessionId == sessionId && it.messageId == messageId &&
                    (it.versionId == null || it.versionId == versionId)
            }
            var committed = false
            matching.forEach { removal ->
                if (finish(removal)) committed = true
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

    /** Archives and session copies capture only after pending reply cleanup is settled. */
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
            pendingMemoryInvalidations(sessionId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withLock chunks.filterNot {
                it.isChatMemoryForSession(sessionId) && ChatMemoryIndexPolicy.isAutomaticChunk(it)
            }
        }
        if (invalidations.isEmpty()) return@withLock chunks
        chat.withStableMessageTimeline {
            val proof = ReplyMemorySources(sessionId)
            chunks.filter { chunk ->
                val affected = invalidations.filter { it.affects(chunk) }
                affected.isEmpty() || proof.isCurrent(chunk, affected)
            }
        }
    }

    /** Recheck selected automatic hits after the retrieval planner and query embedding suspend. */
    internal suspend fun selectCurrentRecallIds(
        sessionId: String,
        candidates: List<VectorChunk>,
        limit: Int,
        renderMessage: (ChatMessage) -> ChatMessage
    ): Set<String> = mutex.withLock {
        chat.withStableMessageTimeline {
            val sources = ReplyMemorySources(sessionId)
            buildSet {
                for (chunk in candidates) {
                    if (size >= limit) break
                    if (!ChatMemoryIndexPolicy.isAutomaticChunk(chunk) ||
                        sources.matchesCurrentContents(chunk, renderMessage) && rag.getChunkById(chunk.id) == chunk) {
                        add(chunk.id)
                    }
                }
            }
        }
    }

    internal suspend fun withCurrentRagSources(
        sessionId: String, revision: ChatRagSourceRevision, update: suspend () -> Unit
    ): Boolean = mutex.withLock { chat.withCurrentRagSources(sessionId, revision, publish = update) }

    internal suspend fun <T> withStoredReplyMemory(update: suspend () -> T): T = mutex.withLock {
        LocalDataMaintenance.access { update() }
    }

    /** Embeddings are computed before admission; only an unchanged source can update disk. */
    internal suspend fun publishReplyMemory(
        sessionId: String,
        revision: ChatRagSourceRevision,
        sourceTurnId: String?,
        messageIds: Set<String>,
        chunks: List<VectorChunk>,
        sourceMessages: List<ChatMessage>? = null,
        keepCurrent: ChatMemoryProjection? = null
    ): Boolean = mutex.withLock {
        chat.withCurrentRagSources(sessionId, revision, sourceTurnId, sourceMessages) {
            if (chunks.isNotEmpty()) {
                val invalidations = pendingMemoryInvalidations(sessionId)
                val proof = ReplyMemorySources(sessionId)
                rag.saveChunks(chunks.map { proof.authorize(it, invalidations) })
            }
            rag.deleteSupersededAutomaticChatMemory(
                sessionId, sourceTurnId, messageIds, chunks.mapTo(mutableSetOf()) { it.id }, keepCurrent
            )
        }
    }

    /** Read the pending journal before replacing messages or archived vectors. */
    internal suspend fun <T> withRestoredReplyMemory(
        sessionId: String,
        renderMessage: (ChatMessage) -> ChatMessage,
        restore: suspend (prepareChunk: suspend (VectorChunk) -> VectorChunk?) -> T
    ): T = mutex.withLock {
        val invalidations = pendingMemoryInvalidations(sessionId)
        val sources = ReplyMemorySources(sessionId)
        restore { chunk ->
            val normalized = chunk.copy(sourceType = ChunkSourceType.CHAT_MEMORY, sourceId = sessionId)
            val affected = invalidations.filter { it.affects(normalized) }
            when {
                affected.isEmpty() -> normalized.withCurrentReplyRemovals(emptyList(), null)
                sources.matchesCurrentContents(normalized, renderMessage) -> sources.authorize(normalized, affected)
                ChatMemoryIndexPolicy.needsAutomaticRebuild(normalized) ->
                    normalized.withCurrentReplyRemovals(emptyList(), null)
                else -> null
            }
        }
    }

    private suspend fun pendingMemoryInvalidations(sessionId: String): List<ReplyRemoval> =
        pending().filter { it.sessionId == sessionId && it.invalidateAutomaticMemory }

    private fun ReplyRemoval.affects(chunk: VectorChunk): Boolean =
        chunk.isAutomaticReplyMemory(sessionId, sourceTurnId, messageId)

    private data class ReplyMemorySource(val sourceTurnId: String?, val messageIds: Set<String>)

    /** A repair, further deletion or restore can change ownership again before an earlier retry. */
    private inner class ReplyMemorySources(private val sessionId: String) {
        private val proofs = mutableMapOf<ReplyMemorySource, String?>()
        private val projections = mutableMapOf<ReplyMemorySource, ChatMemoryProjection?>()

        suspend fun authorize(chunk: VectorChunk, invalidations: List<ReplyRemoval>): VectorChunk {
            val ids = invalidations.filter { it.affects(chunk) }.map(ReplyRemoval::id)
            return chunk.withCurrentReplyRemovals(ids, if (ids.isEmpty()) null else proofFor(chunk))
        }

        suspend fun isCurrent(chunk: VectorChunk, invalidations: List<ReplyRemoval>): Boolean {
            val marked = chunk.currentReplySourceProof ?: return false
            return invalidations.all { chunk.isCurrentForReplyRemoval(it.id) } && proofFor(chunk) == marked
        }

        suspend fun matchesCurrentContents(chunk: VectorChunk, renderMessage: (ChatMessage) -> ChatMessage): Boolean {
            if (ChatMemoryIndexPolicy.needsAutomaticRebuild(chunk)) return false
            val source = sourceOf(chunk)
            if (source !in projections) {
                projections[source] = ChatMemoryIndexPolicy.buildTurns(read(source).map(renderMessage))
                    .singleOrNull()?.let(::ChatMemoryProjection)
            }
            return projections[source]?.matches(chunk) == true
        }

        private suspend fun proofFor(chunk: VectorChunk): String? {
            val source = sourceOf(chunk)
            if (source !in proofs) {
                proofs[source] = read(source).filter(ChatMemoryIndexPolicy::contributesToIndex)
                    .takeIf { it.isNotEmpty() }?.let(::replyMemorySourceProof)
            }
            return proofs[source]
        }

        private fun sourceOf(chunk: VectorChunk): ReplyMemorySource {
            val sourceTurnId = chunk.metadata["sourceTurnId"]?.takeIf(String::isNotBlank)
            val ids = if (sourceTurnId == null) chunk.metadataMessageIds() + listOfNotNull(chunk.messageId) else emptySet()
            return ReplyMemorySource(sourceTurnId, ids)
        }

        private suspend fun read(source: ReplyMemorySource): List<ChatMessage> {
            source.sourceTurnId?.let { return chat.getMessagesForSourceTurn(sessionId, it) }
            val messages = source.messageIds.mapNotNull { chat.getMessage(it, sessionId) }
            if (messages.size != source.messageIds.size) return emptyList()
            val assignedTurn = messages.map(ChatMessage::sourceTurnId).distinct().singleOrNull()
            return assignedTurn?.let { chat.getMessagesForSourceTurn(sessionId, it) }
                ?: messages.sortedWith(ChatMessage.TimelineComparator)
        }
    }

    private fun replyMemorySourceProof(messages: List<ChatMessage>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray()
            digest.update("${bytes.size}:".toByteArray())
            digest.update(bytes)
        }
        messages.forEach { message ->
            add(message.id)
            add(message.role.name)
            add(message.displayContent)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun pending(): List<ReplyRemoval> {
        val removals = mutableListOf<ReplyRemoval>()
        storage.forEachUncached(STORE, ReplyRemoval.serializer(), requireCompleteRead = true) { removals += it }
        return removals
    }

    private suspend fun finish(removal: ReplyRemoval) = withContext(Dispatchers.IO) {
        var memoryError: Exception? = null
        val committed = voices.finishReplyRemoval(
            removal, { cleanup ->
                chat.finishReplyRemoval(removal) {
                    if (removal.invalidateAutomaticMemory) {
                        try {
                            val proof = ReplyMemorySources(removal.sessionId)
                            val current = rag.getAllChunksForSession(removal.sessionId).filter { chunk ->
                                removal.affects(chunk) && proof.isCurrent(chunk, listOf(removal))
                            }.mapTo(mutableSetOf(), VectorChunk::id)
                            rag.invalidateReplyMemory(removal.sessionId, removal.sourceTurnId, removal.messageId, current)
                            storage.saveEntityUncached(
                                STORE, removal.id, removal.copy(invalidateAutomaticMemory = false), ReplyRemoval.serializer()
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            memoryError = error
                        }
                    }
                    cleanup()
                }
            },
            playback::removeQueuedVoices,
            audio::deleteIfOwned
        )
        if (!committed) voices.deleteUnownedReplyAudio(removal.audioPaths, audio::deleteIfOwned)
        if (removal.images.isNotEmpty()) {
            chat.deleteUnreferencedChatImages(removal.images) { path ->
                check(images.deleteChatAttachmentIfOwned(path)) { "无法清理已删除回复的图片文件" }
            }
        }
        memoryError?.let { throw it }
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
