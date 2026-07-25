package com.example.chatbar.data.local.entity

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatScrollPositionSerializationTest {
    @Test
    fun missingOptionalFieldsUseCompatibleDefaults() {
        val position = Json.decodeFromString(
            ChatScrollPosition.serializer(),
            """{"sessionId":"session"}"""
        )

        assertEquals("session", position.sessionId)
        assertNull(position.anchorMessageId)
        assertEquals(0, position.fallbackMessageIndex)
        assertEquals(0, position.scrollOffset)
        assertEquals(0L, position.capturedAt)
    }

    @Test
    fun newPayloadRoundTrips() {
        val expected = ChatScrollPosition(
            sessionId = "session",
            anchorMessageId = "message",
            fallbackMessageIndex = 4,
            scrollOffset = 72,
            capturedAt = 123
        )

        val encoded = Json.encodeToString(ChatScrollPosition.serializer(), expected)
        val decoded = Json.decodeFromString(ChatScrollPosition.serializer(), encoded)

        assertEquals(expected, decoded)
    }
}
