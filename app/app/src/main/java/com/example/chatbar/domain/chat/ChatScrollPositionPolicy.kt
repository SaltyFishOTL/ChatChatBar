package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatScrollPosition

object ChatScrollPositionPolicy {
    fun capture(
        sessionId: String,
        messageIds: List<String>,
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
        transientMessageId: String? = null,
        capturedAt: Long = System.currentTimeMillis()
    ): ChatScrollPosition? {
        if (messageIds.isEmpty() && transientMessageId == null) return null
        val normalizedIndex = firstVisibleItemIndex.coerceAtLeast(0)
        val fallbackIndex = normalizedIndex.coerceAtMost(messageIds.lastIndex.coerceAtLeast(0))
        val anchorMessageId = messageIds.getOrNull(normalizedIndex)
            ?: transientMessageId
            ?: messageIds.lastOrNull()
        return ChatScrollPosition(
            sessionId = sessionId,
            anchorMessageId = anchorMessageId,
            fallbackMessageIndex = fallbackIndex,
            scrollOffset = firstVisibleItemScrollOffset.coerceAtLeast(0),
            capturedAt = capturedAt
        )
    }

    fun restoreMessageIndex(
        position: ChatScrollPosition,
        messageIds: List<String>
    ): Int? {
        if (messageIds.isEmpty()) return null
        val anchorIndex = position.anchorMessageId?.let(messageIds::indexOf) ?: -1
        return if (anchorIndex >= 0) {
            anchorIndex
        } else {
            position.fallbackMessageIndex.coerceIn(0, messageIds.lastIndex)
        }
    }
}
