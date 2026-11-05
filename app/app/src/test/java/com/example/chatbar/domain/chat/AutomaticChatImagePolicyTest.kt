package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatSession
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticChatImagePolicyTest {
    @Test
    fun `tool calls need validated body evidence before automatic images`() {
        val complete = ChatReplyCompletion("tool_calls", toolReplyCompleted = true)
        assertNull(AutomaticChatImagePolicy.skipReason(complete, "完整正文"))
        assertNotNull(AutomaticChatImagePolicy.skipReason(complete.copy(toolReplyCompleted = false), "正文"))
        assertNotNull(AutomaticChatImagePolicy.skipReason(complete.copy(transportFailed = true), "正文"))
        assertNotNull(AutomaticChatImagePolicy.skipReason(complete.copy(refused = true), "正文"))
        assertNotNull(AutomaticChatImagePolicy.skipReason(complete.copy(finishReason = "length"), "部分正文"))
    }

    @Test
    fun `only explicit normal completion passes transport gate`() {
        assertNull(AutomaticChatImagePolicy.skipReason(ChatReplyCompletion("stop"), "故事正文"))
        listOf(null, "", "length", "content_filter", "tool_calls", "unknown").forEach { reason ->
            assertNotNull(AutomaticChatImagePolicy.skipReason(ChatReplyCompletion(reason), "已有部分正文"))
        }
        assertNotNull(AutomaticChatImagePolicy.skipReason(null, "故事正文"))
        assertNotNull(AutomaticChatImagePolicy.skipReason(ChatReplyCompletion("stop", refused = true), "正文"))
        assertNotNull(AutomaticChatImagePolicy.skipReason(ChatReplyCompletion("stop", transportFailed = true), "正文"))
        assertNotNull(AutomaticChatImagePolicy.skipReason(ChatReplyCompletion("stop"), "  "))
    }

    @Test
    fun `semantic verdict requires complete related non refusal reply`() {
        assertNull(AutomaticChatImageJudge.parseSkipReason(
            """{"complete":true,"continuesStory":true,"refused":false}"""
        ))
        listOf(
            """{"complete":false,"continuesStory":true,"refused":false}""",
            """{"complete":true,"continuesStory":false,"refused":false}""",
            """{"complete":true,"continuesStory":true,"refused":true}"""
        ).forEach { assertNotNull(AutomaticChatImageJudge.parseSkipReason(it)) }
        listOf("", "yes", "{}", "null", """{"complete":true}""").forEach {
            assertTrue(runCatching { AutomaticChatImageJudge.parseSkipReason(it) }.isFailure)
        }
    }

    @Test
    fun `semantic verdict accepts code wrappers including inline json prefix`() {
        val verdict = """{"complete":true,"continuesStory":true,"refused":false}"""
        listOf(
            "```json\n$verdict\n```",
            "```\n$verdict\n```",
            "`json$verdict",
            "`json$verdict`",
            "  ```JSON$verdict```  "
        ).forEach { assertNull(AutomaticChatImageJudge.parseSkipReason(it)) }
        assertNotNull(AutomaticChatImageJudge.parseSkipReason(
            "```json\n" + """{"complete":true,"continuesStory":true,"refused":true}""" + "\n```"
        ))
    }

    @Test
    fun `code wrappers do not permit incomplete ambiguous or prose verdicts`() {
        val verdict = """{"complete":true,"continuesStory":true,"refused":false}"""
        listOf(
            "```json{} ```",
            "`json{\"complete\":true",
            "```json$verdict$verdict```",
            "Cannot judge. $verdict",
            "```json$verdict``` trailing prose"
        ).forEach {
            assertTrue(runCatching { AutomaticChatImageJudge.parseSkipReason(it) }.isFailure)
        }
    }

    @Test
    fun `legacy session defaults off and new setting survives serialization`() {
        val session = Json.decodeFromString<ChatSession>(
            """{"id":"s","characterCardId":"c","title":"story","createdAt":1,"updatedAt":1}"""
        )
        assertFalse(session.automaticImageGenerationEnabled)
        val restored = Json.decodeFromString<ChatSession>(
            Json.encodeToString(ChatSession.serializer(), session.copy(automaticImageGenerationEnabled = true))
        )
        assertTrue(restored.automaticImageGenerationEnabled)
    }
}
