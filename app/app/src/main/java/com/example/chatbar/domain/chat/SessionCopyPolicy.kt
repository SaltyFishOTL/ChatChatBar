package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MemoryUpdateStatus
import com.example.chatbar.data.local.entity.VectorChunk
import com.example.chatbar.domain.rag.ChatMemoryIndexPolicy
import java.util.UUID

/** Copy identities without changing source-turn evidence or equal-time message ordering. */
class SessionCopyPolicy(targetSessionId: String, messageIds: Set<String>) {
    private val prefix = targetSessionId
    private val messageIds = messageIds.sorted().mapIndexed { index, id ->
        id to "$targetSessionId-${index.toString().padStart(10, '0')}"
    }.toMap()

    fun messageId(id: String): String = messageIds[id]
        ?: UUID.nameUUIDFromBytes("$prefix\u0000$id".toByteArray(Charsets.UTF_8)).toString()

    fun message(message: ChatMessage, sessionId: String): ChatMessage =
        MessageAlternativeVersionPolicy.normalize(message).copy(
            id = messageId(message.id),
            sessionId = sessionId,
            generatedFromMessageId = message.generatedFromMessageId?.let(::messageId)
        )

    fun chunk(chunk: VectorChunk): VectorChunk = chunk.copy(
        messageId = chunk.messageId?.let(::messageId),
        metadata = chunk.metadata.mapValues { (key, value) ->
            if (key == "messageIds" || key == ChatMemoryIndexPolicy.ACTION_CHOICE_SOURCE_IDS) {
                value.split(',').filter(String::isNotBlank).joinToString(",", transform = ::messageId)
            } else value
        }
    )

    fun session(source: ChatSession, id: String, name: String, now: Long): ChatSession = source.copy(
        id = id,
        displayTitleOverride = name,
        createdAt = now,
        updatedAt = now,
        memoryStateRevision = 0,
        memoryHeadCommitId = null,
        memoryUpdateStatus = MemoryUpdateStatus.IDLE,
        memoryUpdateError = null,
        memoryArchiveStatus = MemoryUpdateStatus.IDLE,
        memoryArchiveError = null,
        memoryHeadStatus = MemoryUpdateStatus.IDLE,
        memoryHeadError = null,
        longTermMemoryUpdatedThroughMessageId = source.longTermMemoryUpdatedThroughMessageId?.let(::messageId)
    )

    companion object {
        fun copyName(source: ChatSession, sessions: List<ChatSession>): String {
            val names = sessions.map(SessionDisplayTitlePolicy::resolve).toSet()
            val base = SessionDisplayTitlePolicy.resolve(source)
            var number = 1
            while (true) {
                val suffix = if (number == 1) "（副本）" else "（副本 $number）"
                val candidate = base.take(SessionDisplayTitlePolicy.MAX_LENGTH - suffix.length) + suffix
                if (candidate !in names) return candidate
                number++
            }
        }
    }
}
