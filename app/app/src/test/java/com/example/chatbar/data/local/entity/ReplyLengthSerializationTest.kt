package com.example.chatbar.data.local.entity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyLengthSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun legacyStringValuesDecodeToBoundedAbsoluteCharacterCounts() {
        val cases = listOf(
            "\"500字中篇\"" to 500,
            "\"50字内\"" to 50,
            "\"长篇\"" to DEFAULT_REPLY_LENGTH_CHARS,
            "\"\"" to DEFAULT_REPLY_LENGTH_CHARS,
            "null" to DEFAULT_REPLY_LENGTH_CHARS,
            "\"-20字\"" to DEFAULT_REPLY_LENGTH_CHARS,
            "\"50000字\"" to MAX_REPLY_LENGTH_CHARS
        )

        cases.forEach { (rawReplyLength, expected) ->
            val session = json.decodeFromString(
                ChatSession.serializer(),
                sessionJson(rawReplyLength)
            )
            assertEquals(rawReplyLength, expected, session.replyLength)
        }
    }

    @Test
    fun numericReplyLengthRoundTripsAsJsonNumber() {
        val session = ChatSession.create("card", "会话").copy(replyLength = 800)
        val encoded = json.encodeToString(ChatSession.serializer(), session)
        val encodedValue = Json.parseToJsonElement(encoded)
            .jsonObject
            .getValue("replyLength")
            .jsonPrimitive

        assertEquals(800, encodedValue.int)
        assertEquals(
            session,
            json.decodeFromString(ChatSession.serializer(), encoded)
        )
    }

    private fun sessionJson(rawReplyLength: String): String =
        """
        {
          "id":"session",
          "characterCardId":"card",
          "title":"会话",
          "replyLength":$rawReplyLength,
          "createdAt":1,
          "updatedAt":1
        }
        """.trimIndent()
}
