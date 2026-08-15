package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatScrollPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatScrollPositionPolicyTest {
    @Test
    fun captureAnchorsVisibleMessageAndOffset() {
        val position = ChatScrollPositionPolicy.capture(
            sessionId = "session",
            messageIds = listOf("first", "visible", "last"),
            firstVisibleItemIndex = 1,
            firstVisibleItemScrollOffset = 48,
            capturedAt = 100
        )

        assertEquals("visible", position?.anchorMessageId)
        assertEquals(1, position?.fallbackMessageIndex)
        assertEquals(48, position?.scrollOffset)
        assertEquals(100L, position?.capturedAt)
    }

    @Test
    fun restoreTracksAnchorWhenEarlierMessagesChange() {
        val position = ChatScrollPosition(
            sessionId = "session",
            anchorMessageId = "visible",
            fallbackMessageIndex = 1,
            scrollOffset = 48
        )

        val index = ChatScrollPositionPolicy.restoreMessageIndex(
            position,
            listOf("inserted", "first", "visible", "last")
        )

        assertEquals(2, index)
    }

    @Test
    fun restoreFallsBackAndClampsWhenAnchorWasDeleted() {
        val position = ChatScrollPosition(
            sessionId = "session",
            anchorMessageId = "deleted",
            fallbackMessageIndex = 20
        )

        val index = ChatScrollPositionPolicy.restoreMessageIndex(
            position,
            listOf("first", "last")
        )

        assertEquals(1, index)
    }

    @Test
    fun captureKeepsStreamingMessageAnchorAfterPersistence() {
        val position = ChatScrollPositionPolicy.capture(
            sessionId = "session",
            messageIds = listOf("first", "streaming"),
            firstVisibleItemIndex = 1,
            firstVisibleItemScrollOffset = 12
        )

        assertEquals("streaming", position?.anchorMessageId)
        assertEquals(12, position?.scrollOffset)
        assertEquals(
            1,
            ChatScrollPositionPolicy.restoreMessageIndex(
                position!!,
                listOf("first", "streaming")
            )
        )
    }

    @Test
    fun emptyConversationHasNoPositionToPersist() {
        assertNull(
            ChatScrollPositionPolicy.capture(
                sessionId = "session",
                messageIds = emptyList(),
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0
            )
        )
    }
}
