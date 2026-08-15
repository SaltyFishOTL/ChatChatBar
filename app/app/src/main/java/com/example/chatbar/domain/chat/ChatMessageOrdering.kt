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
        val insertIndex = if (anchorIndex < 0) {
            ordered.size
        } else {
            var nextIndex = anchorIndex + 1
            while (nextIndex < ordered.size && ordered[nextIndex].generatedFromMessageId == anchorMessageId) {
                nextIndex++
            }
            nextIndex
        }
        val orderKey = availableOrderKey(ordered, insertIndex)
        val inserted = ordered.toMutableList().apply {
            add(
                insertIndex,
                imageMessage.copy(
                    generatedFromMessageId = anchorMessageId,
                    orderKey = orderKey ?: imageMessage.orderKey
                )
            )
        }

        if (orderKey != null) return inserted
        return normalize(inserted)
    }

    private fun availableOrderKey(messages: List<ChatMessage>, insertIndex: Int): Long? {
        val previous = messages.getOrNull(insertIndex - 1)?.orderKey
            ?: return messages.firstOrNull()?.orderKey?.minus(1) ?: MESSAGE_ORDER_STEP
        if (previous == Long.MAX_VALUE) return null
        val candidate = previous + 1
        val next = messages.getOrNull(insertIndex)?.orderKey
        return candidate.takeIf { next == null || it < next }
    }

    private fun normalize(messages: List<ChatMessage>): List<ChatMessage> =
        messages.mapIndexed { index, message ->
            message.copy(orderKey = (index + 1L) * MESSAGE_ORDER_STEP)
        }
}
