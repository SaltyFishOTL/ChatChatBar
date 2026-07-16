package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole

/** A T is archivable only when every story message belonging to it left direct context. */
object TimelineArchiveBoundaryPolicy {
    fun expandDirectContextToWholeTurns(
        allMessages: List<ChatMessage>,
        recentMessages: List<ChatMessage>
    ): List<ChatMessage> {
        val recentIds = recentMessages.mapTo(mutableSetOf()) { it.id }
        val recentSourceTurns = recentMessages.mapNotNullTo(mutableSetOf()) { it.sourceTurnId }
        val recentLegacyTurns = recentMessages
            .filter { it.sourceTurnId == null }
            .mapNotNullTo(mutableSetOf()) { it.timelineTurn }
        return allMessages.filter { message ->
            message.id in recentIds ||
                message.sourceTurnId?.let { it in recentSourceTurns } == true ||
                (message.sourceTurnId == null &&
                    message.timelineTurn?.let { it in recentLegacyTurns } == true)
        }
    }

    /**
     * Long-term memory injects summaries only. Pending source turns remain in normal context
     * until their Episode commits, even when that temporarily exceeds target context size.
     */
    fun expandDirectContextAfterArchive(
        allMessages: List<ChatMessage>,
        directContext: List<ChatMessage>,
        pendingSourceTurnIds: Set<String>
    ): List<ChatMessage> {
        val directIds = directContext.mapTo(mutableSetOf()) { it.id }
        return allMessages.filter { message ->
            message.id in directIds ||
                (message.role != MessageRole.SYSTEM && message.sourceTurnId in pendingSourceTurnIds)
        }
    }

}
