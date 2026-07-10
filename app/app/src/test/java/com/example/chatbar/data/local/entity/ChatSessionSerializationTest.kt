package com.example.chatbar.data.local.entity

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSessionSerializationTest {
    @Test
    fun interimCheckpointFieldIsIgnoredWhenLoadingExistingSession() {
        val session = Json { ignoreUnknownKeys = true }.decodeFromString(
            ChatSession.serializer(),
            """
            {
              "id":"session",
              "characterCardId":"card",
              "title":"会话",
              "promptCacheCheckpoint":{"memorySnapshot":"旧快照"},
              "createdAt":1,
              "updatedAt":1
            }
            """.trimIndent()
        )

        assertEquals("session", session.id)
        assertEquals("会话", session.title)
    }
}
