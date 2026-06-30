package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MESSAGE_ORDER_STEP

object ChatMessageOrdering {
    fun insertGeneratedImageAfter(
        messages: List<ChatMessage>,
        imageMessage: ChatMessage,
        anchorMessageId: String
    ): List<ChatMessage> {
        val ordered = messages.sortedWith(ChatMessage.TimelineComparator)
        val anchorIndex = ordered.indexOfFirst { it.id == anchorMessageId }
        if (anchorIndex < 0) {
            return normalize(ordered + imageMessage)
        }

        var insertIndex = anchorIndex + 1
        while (insertIndex < ordered.size && ordered[insertIndex].generatedFromMessageId == anchorMessageId) {
            insertIndex++
        }

        return normalize(
            ordered.toMutableList().apply {
                add(insertIndex, imageMessage.copy(generatedFromMessageId = anchorMessageId))
            }
        )
    }

    private fun normalize(messages: List<ChatMessage>): List<ChatMessage> =
        messages.mapIndexed { index, message ->
            message.copy(orderKey = (index + 1L) * MESSAGE_ORDER_STEP)
        }
}
