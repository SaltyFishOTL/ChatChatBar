package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatDraft
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDraftSerializationTest {
    @Test
    fun `draft round trips by session id`() {
        val draft = ChatDraft(
            sessionId = "session-1",
            content = "half typed message",
            updatedAt = 200
        )

        val encoded = Json.encodeToString(ChatDraft.serializer(), draft)
        val decoded = Json.decodeFromString(ChatDraft.serializer(), encoded)

        assertEquals("session-1", decoded.sessionId)
        assertEquals("half typed message", decoded.content)
        assertEquals(200, decoded.updatedAt)
    }
}
