package com.example.chatbar.domain.deletion

import android.util.Log
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.VectorChunk
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.ChatRagSourceRevision
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.domain.backup.LocalDataMaintenance
import com.example.chatbar.domain.image.NovelAiImageStorage
import com.example.chatbar.domain.rag.ChatMemoryIndexPolicy
import com.example.chatbar.domain.rag.actionChoiceSourceIds
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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class ReplyRemovalCoordinator(
    private val storage: JsonFileStorage,
    private val chat: ChatRepository,
    private val voices: VoiceMessageRepository,
    private val audio: FishAudioStorage,
    private val images: NovelAiImageStorage,
    private val playback: VoicePlaybackController,
    private val rag: RagRepository = RagRepository(storage),
    private val onTimelineRemoval: (String) -> Unit = { _ -> }
) {
    private val mutex = Mutex()
    private val cleanupMutex = Mutex()
    private val cleanupRequested = Channel<Unit>(Channel.CONFLATED)
    private val _recoveredSessions = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val recoveredSessions = _recoveredSessions.asSharedFlow()

    data class RemovalCommit(val replacement: ChatMessage?)

    suspend fun runCleanup() {
        cleanupRequested.trySend(Unit)
        for (request in cleanupRequested) {
            var retryDelay = 1_000L
            while (!resumePending()) {
                withTimeoutOrNull(retryDelay) { cleanupRequested.receive() }
                retryDelay = (retryDelay * 2).coerceAtMost(60_000L)
            }
        }
    }

    suspend fun remove(expected: ChatMessage, replacement: ChatMessage?, versionId: String?): RemovalCommit =
        checkNotNull(remove(expected, replacement, versionId, onlyIfUnvoiced = false))

    /** An attachment action can remove the empty row only while it has no stored recording. */
    suspend fun removeIfUnvoiced(expected: ChatMessage): RemovalCommit? =
        remove(expected, replacement = null, versionId = null, onlyIfUnvoiced = true)

    /** A retained reply can lose an attachment or a text segment without deleting its voice version. */
    suspend fun commitRetainedReply(
        expected: ChatMessage,
        replacement: ChatMessage,
        paths: List<String>
    ): ChatMessage = mutex.withLock {
        LocalDataMaintenance.access {
            val updated = replacement.copy(updatedAt = System.currentTimeMillis(), orderKey = expected.orderKey)
            val contentChanged = expected.displayContent != updated.displayContent ||
                expected.displayActionOptionsFormat != updated.displayActionOptionsFormat
            var pending: ReplyRemoval? = null
            var committed = false
            try {
                if (!chat.commitReplyRemoval(expected, updated, ragSourceChanged = contentChanged,
                        onCommitted = { committed = true }) {
                    val removal = ReplyRemoval(
                        id = UUID.randomUUID().toString(), sessionId = expected.sessionId, messageId = expected.id,
                        versionId = null, role = expected.role, sourceTurnId = expected.sourceTurnId,
                        sourceTurnOrder = expected.sourceTurnOrder, timelineTurn = expected.timelineTurn,
                        images = paths, audioPaths = emptyList(), invalidateAutomaticMemory = contentChanged,
                        retainsReply = true,
                        previousContentProof = if (contentChanged) retainedReplyContentProof(expected) else null,
                        expectedMessageProof = messageProof(expected),
                        replacement = updated
                    )
                    writeRemoval(removal)
                    pending = removal
                }) throw ReplyRemovalConflict()
                publishTimelineSettled(checkNotNull(pending))
                updated
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!committed) throw error
                logCleanupFailure(error)
                updated
            } finally {
                if (pending != null) cleanupRequested.trySend(Unit)
            }
        }
    }

    private suspend fun remove(
        expected: ChatMessage,
        replacement: ChatMessage?,
        versionId: String?,
        onlyIfUnvoiced: Boolean
    ): RemovalCommit? = mutex.withLock {
        require((replacement == null) == (versionId == null))
        LocalDataMaintenance.access {
            val updated = replacement?.copy(updatedAt = System.currentTimeMillis(), orderKey = expected.orderKey)
            var pending: ReplyRemoval? = null
            var messageCommitted = false
            try {
                val prepared = voices.prepareReplyRemoval(
                    expected, versionId, playback::removeQueuedVoices, onlyIfUnvoiced
                ) { collectMedia ->
                    chat.commitReplyRemoval(expected, updated, onCommitted = { messageCommitted = true }) {
                        val media = collectMedia()
                        val removal = ReplyRemoval(
                            id = UUID.randomUUID().toString(), sessionId = expected.sessionId, messageId = expected.id,
                            versionId = versionId, role = expected.role, sourceTurnId = expected.sourceTurnId,
                            sourceTurnOrder = expected.sourceTurnOrder, timelineTurn = expected.timelineTurn,
                            images = if (replacement == null) {
                                (expected.images + expected.generatedImageMetadata.map { it.imagePath }).distinct()
                            } else emptyList(),
                            audioPaths = media.audioPaths,
                            invalidateAutomaticMemory = (ChatMemoryIndexPolicy.contributesToIndex(expected) ||
                                expected.role == MessageRole.ASSISTANT && (
                                    expected.displayContent.isNotBlank() || expected.alternatives.any(String::isNotBlank)
                                )) &&
                                (replacement == null || expected.displayContent != replacement.displayContent ||
                                    expected.displayActionOptionsFormat != replacement.displayActionOptionsFormat ||
                                    expected.alternatives != replacement.alternatives),
                            legacyVoiceAnchorWitnessId = media.legacyAnchorWitnessId,
                            legacyVoiceContentHashes = media.legacyVoiceContentHashes,
                            expectedMessageProof = messageProof(expected),
                            replacement = updated,
                            onlyIfUnvoiced = onlyIfUnvoiced
                        )
                        writeRemoval(removal)
                        pending = removal
                    }
                }
                if (!prepared) {
                    if (onlyIfUnvoiced && pending == null &&
                        chat.getMessageForDeletion(expected.id, expected.sessionId) == expected) {
                        return@access null
                    }
                    throw ReplyRemovalConflict()
                }
                publishTimelineSettled(checkNotNull(pending))
                RemovalCommit(updated)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!messageCommitted) throw error
                logCleanupFailure(error)
                RemovalCommit(updated)
            } finally {
                if (pending != null) cleanupRequested.trySend(Unit)
            }
        }
    }

    private suspend fun publishTimelineSettled(removal: ReplyRemoval): ReplyRemoval {
        val settled = removal.copy(expectedMessageProof = null, replacement = null, timelineSettled = true)
        writeRemoval(settled)
        if (!removal.retainsReply && removal.versionId == null) onTimelineRemoval(removal.sessionId)
        return settled
    }

    private suspend fun recoverTimeline(removal: ReplyRemoval): ReplyRemoval {
        if (removal.timelineSettled) return removal
        val expectedProof = removal.expectedMessageProof
        val current = chat.getMessageForDeletion(removal.messageId, removal.sessionId)
        if (current != null && expectedProof != null && messageProof(current) == expectedProof) {
            check((removal.retainsReply || removal.versionId != null) == (removal.replacement != null)) {
                "删除日志缺少待写入的消息"
            }
            var refreshed = removal
            val committed = if (removal.retainsReply) {
                chat.commitReplyRemoval(current, removal.replacement,
                    ragSourceChanged = removal.invalidateAutomaticMemory) {}
            } else {
                voices.prepareReplyRemoval(current, removal.versionId, playback::removeQueuedVoices,
                    onlyIfUnvoiced = removal.onlyIfUnvoiced) { collectMedia ->
                    chat.commitReplyRemoval(current, removal.replacement) {
                        val media = collectMedia()
                        refreshed = removal.copy(
                            audioPaths = (removal.audioPaths + media.audioPaths).distinct(),
                            legacyVoiceAnchorWitnessId = media.legacyAnchorWitnessId ?: removal.legacyVoiceAnchorWitnessId,
                            legacyVoiceContentHashes = removal.legacyVoiceContentHashes + media.legacyVoiceContentHashes
                        )
                        writeRemoval(refreshed)
                    }
                }
            }
            if (committed) return publishTimelineSettled(refreshed).also {
                _recoveredSessions.tryEmit(removal.sessionId)
            }
            if (removal.onlyIfUnvoiced) {
                val updated = current.copy(
                    images = current.images.filterNot(removal.images::contains),
                    generatedImageMetadata = current.generatedImageMetadata.filterNot { it.imagePath in removal.images },
                    updatedAt = System.currentTimeMillis()
                )
                val detachImage = removal.copy(retainsReply = true, onlyIfUnvoiced = false, replacement = updated)
                if (chat.commitReplyRemoval(current, updated, ragSourceChanged = false) { writeRemoval(detachImage) }) {
                    return publishTimelineSettled(detachImage).also { _recoveredSessions.tryEmit(removal.sessionId) }
                }
            }
        }
        if (chat.settleReplyRemovalTimeline(removal)) return publishTimelineSettled(removal).also {
            if (expectedProof != null) _recoveredSessions.tryEmit(removal.sessionId)
        }
        // A live reply with different contents or restored ownership supersedes the old intent.
        return removal.copy(expectedMessageProof = null, replacement = null, timelineSettled = true).also {
            writeRemoval(it)
        }
    }

    /** Startup publishes the light timeline before chat becomes available; heavy cleanup stays queued. */
    suspend fun recoverPendingTimelines() {
        forEachPending(null) { removal ->
            mutex.withLock { readRemoval(removal.id)?.let { recoverTimeline(it) } }
        }
    }

    suspend fun resumePending(sessionId: String? = null): Boolean =
        cleanupMutex.withLock { forEachPending(sessionId, ::finish) }

    private suspend fun forEachPending(
        sessionId: String?,
        process: suspend (ReplyRemoval) -> Unit
    ): Boolean = try {
        var settled = true
        for (id in storage.listEntityIdsUncached(STORE)) {
            try {
                LocalDataMaintenance.access {
                    val removal = readRemoval(id) ?: return@access
                    if (sessionId == null || removal.sessionId == sessionId) process(removal)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logCleanupFailure(error)
                settled = false
            }
        }
        settled
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        logCleanupFailure(error)
        false
    }

    /** Archives and session copies capture only after pending reply cleanup is settled. */
    suspend fun <T> withSettledSession(sessionId: String, capture: suspend () -> T): T =
        cleanupMutex.withLock {
            LocalDataMaintenance.access {
                var captured: Result<T>? = null
                while (captured == null) {
                    if (!forEachPending(sessionId, ::finish)) throw IOException("会话附件清理尚未完成")
                    captured = mutex.withLock {
                        if (pending().none { it.sessionId == sessionId }) Result.success(capture()) else null
                    }
                }
                captured.getOrThrow()
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
        keepCurrent: ChatMemoryProjection? = null,
        cleanupSuperseded: Boolean = true
    ): Boolean = mutex.withLock {
        chat.withCurrentRagSources(sessionId, revision, sourceTurnId, sourceMessages) {
            // A user edit can still own a generated chunk's stable ID.
            val automaticChunks = chunks.filterNot { chunk ->
                rag.getChunkById(chunk.id)?.let(ChatMemoryIndexPolicy::isManualChunk) == true
            }
            if (automaticChunks.isNotEmpty()) {
                val invalidations = pendingMemoryInvalidations(sessionId)
                val proof = ReplyMemorySources(sessionId)
                rag.saveChunks(automaticChunks.map { proof.authorize(it, invalidations) })
            }
            if (cleanupSuperseded) {
                rag.deleteSupersededAutomaticChatMemory(
                    sessionId, sourceTurnId, messageIds, automaticChunks.mapTo(mutableSetOf()) { it.id }, keepCurrent
                )
            }
        }
    }

    /** Read the pending journal before replacing messages or archived vectors. */
    internal suspend fun <T> withRestoredReplyMemory(
        sessionId: String,
        renderMessage: (ChatMessage) -> ChatMessage,
        restore: suspend (prepareChunk: suspend (VectorChunk) -> VectorChunk?) -> T
    ): T = mutex.withLock {
        // Resolve any durable intent before a readback can put the same snapshot back under its ID.
        pending().filter { it.sessionId == sessionId && !it.timelineSettled }.forEach { recoverTimeline(it) }
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

    private data class ReplyMemorySource(
        val sourceTurnId: String?,
        val messageIds: Set<String>,
        val actionSources: Set<String>
    )

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
                val messages = read(source)
                val messageIds = messages.mapTo(mutableSetOf()) { it.id }
                val evidence = actionEvidence(source, messages) + messages
                projections[source] = ChatMemoryIndexPolicy.buildTurns(evidence.map(renderMessage))
                    .singleOrNull { turn -> turn.messageIds.any { it in messageIds } }
                    ?.let(::ChatMemoryProjection)
            }
            return projections[source]?.matches(chunk) == true
        }

        private suspend fun proofFor(chunk: VectorChunk): String? {
            val source = sourceOf(chunk)
            if (source !in proofs) {
                val messages = read(source)
                proofs[source] = (actionEvidence(source, messages) + messages).filter(ChatMemoryIndexPolicy::contributesToIndex)
                    .takeIf { it.isNotEmpty() }?.let(::replyMemorySourceProof)
            }
            return proofs[source]
        }

        private fun sourceOf(chunk: VectorChunk): ReplyMemorySource {
            val sourceTurnId = chunk.metadata["sourceTurnId"]?.takeIf(String::isNotBlank)
            val ids = if (sourceTurnId == null) chunk.metadataMessageIds() + listOfNotNull(chunk.messageId) else emptySet()
            return ReplyMemorySource(sourceTurnId, ids, chunk.actionChoiceSourceIds())
        }

        private suspend fun actionEvidence(source: ReplyMemorySource, messages: List<ChatMessage>): List<ChatMessage> {
            if (source.actionSources.isEmpty()) return emptyList()
            val order = messages.firstOrNull()?.sourceTurnOrder?.takeIf { it > 1 } ?: return emptyList()
            return chat.getMessagesForSourceTurnOrder(sessionId, order - 1)
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

    private suspend fun readRemoval(id: String): ReplyRemoval? =
        storage.loadEntity(STORE, id, ReplyRemoval.serializer(), requireReadable = true)

    private suspend fun writeRemoval(removal: ReplyRemoval) =
        storage.saveEntityUncached(STORE, removal.id, removal, ReplyRemoval.serializer())

    private fun messageProof(message: ChatMessage): String =
        MessageDigest.getInstance("SHA-256")
            .digest(storage.json.encodeToString(ChatMessage.serializer(), message).toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun retainedReplyContentProof(message: ChatMessage): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("${message.displayActionOptionsFormat}:".toByteArray())
        return digest.digest(message.displayContent.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private suspend fun finish(initial: ReplyRemoval): Unit = withContext(Dispatchers.IO) {
        var removal = mutex.withLock { readRemoval(initial.id)?.let { recoverTimeline(it) } }
            ?: return@withContext
        var failure: Exception? = null
        suspend fun settle(action: suspend () -> Unit) {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val prior = failure
                if (prior == null) failure = error else prior.addSuppressed(error)
            }
        }
        if (removal.invalidateAutomaticMemory) settle {
            invalidateRemovedMemory(removal)
            removal = removal.copy(invalidateAutomaticMemory = false).also { writeRemoval(it) }
        }
        if (!removal.retainsReply && !removal.voiceRecordsSettled) settle {
            voices.finishReplyRemoval(
                removal,
                { cleanup -> chat.finishReplyRemoval(removal, cleanup) },
                playback::removeQueuedVoices,
                checkpointAudio = { paths ->
                    if (paths != removal.audioPaths) {
                        removal = removal.copy(audioPaths = paths).also { writeRemoval(it) }
                    }
                },
                withMutationGuard = { change -> mutex.withLock { change() } }
            )
            removal = removal.copy(voiceRecordsSettled = true).also { writeRemoval(it) }
        }
        if (removal.voiceRecordsSettled && removal.audioPaths.isNotEmpty()) settle {
            voices.deleteUnownedReplyAudio(removal.audioPaths, audio::deleteIfOwned)
            removal = removal.copy(audioPaths = emptyList()).also { writeRemoval(it) }
        }
        if (removal.images.isNotEmpty()) settle {
            chat.deleteUnreferencedChatImages(removal.images) { path ->
                check(images.deleteChatAttachmentIfOwned(path)) { "无法清理已删除回复的图片文件" }
            }
            removal = removal.copy(images = emptyList()).also { writeRemoval(it) }
        }
        failure?.let { throw it }
        storage.deleteEntityUncached(STORE, removal.id, requireSuccessfulDelete = true)
    }

    private suspend fun invalidateRemovedMemory(removal: ReplyRemoval) {
        rag.scanReplyMemory(removal.sessionId, removal.sourceTurnId, removal.messageId) { chunk ->
            mutex.withLock {
                chat.finishReplyRemoval(removal) {
                    val current = if (removal.retainsReply) chat.getMessageForDeletion(
                        removal.messageId, removal.sessionId
                    ) else null
                    if (current != null && retainedReplyContentProof(current) == removal.previousContentProof) {
                        return@finishReplyRemoval
                    }
                    if (!ReplyMemorySources(removal.sessionId).isCurrent(chunk, listOf(removal))) {
                        rag.deleteReplyMemoryIfUnchanged(chunk)
                    }
                }
            }
        }
    }

    private fun logCleanupFailure(error: Exception) {
        val stack = error.stackTrace.take(12).joinToString("\n")
        Log.w("ReplyRemoval", "Pending cleanup will retry: ${error.javaClass.name}\n$stack")
    }

    companion object {
        const val STORE = ReplyRemoval.STORE

        /** Run under the exclusive data lease. The local recovery journal is not portable. */
        fun requireSettledForBackup(filesDir: File) {
            val directory = File(filesDir, "entities/$STORE")
            if (Files.notExists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return
            val entries = checkNotNull(directory.listFiles()) { "无法核对本地回复清理状态" }
            check(entries.none { it.extension == "json" }) { "仍有回复附件待清理，请重新打开会话后再试备份或恢复" }
        }
    }
}

internal class ReplyRemovalConflict : IllegalStateException("消息已变化，删除已取消")
