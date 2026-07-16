package com.example.chatbar.data.repository

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageFormatRepairNotice
import com.example.chatbar.data.local.entity.MessageFormatRepairNoticeKind
import com.example.chatbar.data.local.entity.MessageRole
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatMessageSerializationTest {
    @Test
    fun oldPayload_defaultsFormatRepairNoticeToNull() {
        val decoded = Json.decodeFromString(
            ChatMessage.serializer(),
            """{"id":"m","sessionId":"s","role":"ASSISTANT","content":"text","createdAt":1,"updatedAt":1}"""
        )

        assertNull(decoded.formatRepairNotice)
        assertNull(decoded.timelineTurn)
    }

    @Test
    fun formatRepairNotices_roundTrip() {
        MessageFormatRepairNoticeKind.entries.forEach { kind ->
            val message = ChatMessage(
                id = "m-$kind",
                sessionId = "s",
                role = MessageRole.ASSISTANT,
                content = "fixed",
                formatRepairNotice = MessageFormatRepairNotice(
                    kind = kind,
                    targetContent = "fixed",
                    originalContent = "original",
                    errorMessage = "error"
                ),
                createdAt = 1,
                updatedAt = 2
            )

            val decoded = Json.decodeFromString(
                ChatMessage.serializer(),
                Json.encodeToString(ChatMessage.serializer(), message)
            )

            assertEquals(message, decoded)
        }
    }

    @Test
    fun timelineTurn_roundTripsWithoutChangingOrderKey() {
        val message = ChatMessage(
            id = "timeline",
            sessionId = "s",
            role = MessageRole.USER,
            content = "turn",
            timelineTurn = 42,
            orderKey = 12,
            createdAt = 1,
            updatedAt = 1
        )

        val decoded = Json.decodeFromString(
            ChatMessage.serializer(),
            Json.encodeToString(ChatMessage.serializer(), message)
        )

        assertEquals(42L, decoded.timelineTurn)
        assertEquals(12L, decoded.orderKey)
    }
}
