package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole

data class LongTermMemoryUpdateCandidate(
    val userContent: String,
    val assistantContent: String,
    val assistantMessageId: String
)

object LongTermMemoryUpdatePolicy {
    fun nextCandidate(
        messages: List<ChatMessage>,
        updatedThroughMessageId: String?
    ): LongTermMemoryUpdateCandidate? {
        val conversationMessages = messages.filter { it.role != MessageRole.SYSTEM }
        if (updatedThroughMessageId == null) {
            return latestStableCandidateIndex(conversationMessages)?.let {
                conversationMessages.toCandidate(it)
            }
        }

        val updatedThroughIndex = conversationMessages.indexOfFirst {
            it.id == updatedThroughMessageId
        }
        if (updatedThroughIndex < 0) {
            return latestStableCandidateIndex(conversationMessages)?.let {
                conversationMessages.toCandidate(it)
            }
        }

        val candidateRange = (updatedThroughIndex + 1) until conversationMessages.lastIndex
        val candidateIndex = candidateRange.firstOrNull { index ->
            val message = conversationMessages[index]
            message.role == MessageRole.ASSISTANT &&
                message.displayContent.isNotBlank() &&
                conversationMessages.getOrNull(index - 1)?.role == MessageRole.USER
        } ?: return null

        return conversationMessages.toCandidate(candidateIndex)
    }

    private fun latestStableCandidateIndex(messages: List<ChatMessage>): Int? =
        (messages.lastIndex - 1 downTo 1).firstOrNull { index ->
            val message = messages[index]
            message.role == MessageRole.ASSISTANT &&
                message.displayContent.isNotBlank() &&
                messages[index - 1].role == MessageRole.USER
        }

    private fun List<ChatMessage>.toCandidate(index: Int): LongTermMemoryUpdateCandidate {
        val userMessage = this[index - 1]
        val assistantMessage = this[index]
        return LongTermMemoryUpdateCandidate(
            userContent = userMessage.displayContent,
            assistantContent = assistantMessage.displayContent,
            assistantMessageId = assistantMessage.id
        )
    }
}
