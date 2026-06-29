package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import org.junit.Test

class LongTermMemoryUpdatePolicyTest {
    @Test
    fun skipsLatestAssistantReplyUntilFollowedByNextMessage() {
        val messages = listOf(
            message("u1", MessageRole.USER, "user one", 1),
            message("a1", MessageRole.ASSISTANT, "assistant one", 2),
            message("u2", MessageRole.USER, "user two", 3),
            message("a2", MessageRole.ASSISTANT, "assistant two", 4)
        )

        val candidate = LongTermMemoryUpdatePolicy.nextCandidate(messages, "a1")

        assertNull(candidate)
    }

    @Test
    fun updatesOldestUnprocessedAssistantReplyThatHasFollowUpContext() {
        val messages = listOf(
            message("u1", MessageRole.USER, "user one", 1),
            message("a1", MessageRole.ASSISTANT, "assistant one", 2),
            message("u2", MessageRole.USER, "user two", 3),
            message("a2", MessageRole.ASSISTANT, "assistant two", 4),
            message("u3", MessageRole.USER, "user three", 5),
            message("a3", MessageRole.ASSISTANT, "assistant three", 6)
        )

        val candidate = LongTermMemoryUpdatePolicy.nextCandidate(messages, "a1")

        assertEquals("user two", candidate?.userContent)
        assertEquals("assistant two", candidate?.assistantContent)
        assertEquals("a2", candidate?.assistantMessageId)
    }

    @Test
    fun existingSessionWithoutProgressStartsAtLatestStableAssistantReply() {
        val messages = listOf(
            message("u1", MessageRole.USER, "user one", 1),
            message("a1", MessageRole.ASSISTANT, "assistant one", 2),
            message("u2", MessageRole.USER, "user two", 3),
            message("a2", MessageRole.ASSISTANT, "assistant two", 4),
            message("u3", MessageRole.USER, "user three", 5),
            message("a3", MessageRole.ASSISTANT, "assistant three", 6)
        )

        val candidate = LongTermMemoryUpdatePolicy.nextCandidate(messages, null)

        assertEquals("assistant two", candidate?.assistantContent)
        assertEquals("a2", candidate?.assistantMessageId)
    }

    private fun message(
        id: String,
        role: MessageRole,
        content: String,
        createdAt: Long
    ): ChatMessage = ChatMessage(
        id = id,
        sessionId = "session",
        role = role,
        content = content,
        createdAt = createdAt,
        updatedAt = createdAt
    )
}
