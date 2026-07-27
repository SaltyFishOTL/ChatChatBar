package com.example.chatbar.data.local.entity

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(1L, session.nextTimelineTurn)
        assertTrue(session.timelineTombstones.isEmpty())
        assertEquals(2000, session.memoryLimitChars)
        assertNull(session.memoryHeadCommitId)
        assertEquals(MemoryUpdateStatus.IDLE, session.memoryUpdateStatus)
        assertNull(session.audiobookModeEnabled)
        assertNull(session.voiceLanguage)
    }

    @Test
    fun voiceLanguageRoundTripsWhenConfigured() {
        val json = Json { encodeDefaults = true }
        val session = ChatSession.create("card", "会话").copy(voiceLanguage = "日语")

        val decoded = json.decodeFromString(
            ChatSession.serializer(),
            json.encodeToString(ChatSession.serializer(), session)
        )

        assertEquals("日语", decoded.voiceLanguage)
    }
}
