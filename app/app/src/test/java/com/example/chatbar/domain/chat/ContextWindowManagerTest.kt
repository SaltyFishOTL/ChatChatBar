package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class ContextWindowManagerTest {
    private val manager = ContextWindowManager()

    @Test
    fun getRecentMessages_whenOverWindow_returnsLatestGroupsWithoutSplittingPair() {
        val messages = conversationGroups(3)

        val recent = manager.getRecentMessages(messages, windowSize = 1)

        assertEquals(listOf("u1", "a1", "u2", "a2"), recent.map { it.content })
    }

    @Test
    fun getMessagesToArchive_whenOverWindow_returnsOlderCompleteGroups() {
        val messages = conversationGroups(4)

        val archived = manager.getMessagesToArchive(messages, windowSize = 2)

        assertEquals(listOf("u0", "a0"), archived.map { it.content })
    }

    @Test
    fun shouldRefreshSystemPrompt_onlyWhenMessageGroupCountExceedsWindow() {
        assertFalse(manager.shouldRefreshSystemPrompt(conversationGroups(2), windowSize = 2))
        assertTrue(manager.shouldRefreshSystemPrompt(conversationGroups(4), windowSize = 2))
    }

    @Test
    fun getRecentMessages_incompleteCurrentUserCountsAsOneGroup() {
        val messages = conversationGroups(2) + message("current-user", MessageRole.USER, "u2")

        val recent = manager.getRecentMessages(messages, windowSize = 2)

        assertEquals(listOf("u0", "a0", "u1", "a1", "u2"), recent.map { it.content })
        assertEquals(1, manager.messageGroupCount(messages))
    }

    @Test
    fun openingAssistantCountsAsStandaloneHistoryGroup() {
        val messages = listOf(
            message("greeting", MessageRole.ASSISTANT, "greeting"),
            message("user-1", MessageRole.USER, "u1"),
            message("assistant-1", MessageRole.ASSISTANT, "a1"),
            message("current-user", MessageRole.USER, "u2")
        )

        val recent = manager.getRecentMessages(messages, windowSize = 1)

        assertEquals(listOf("greeting", "u1", "a1", "u2"), recent.map { it.content })
        assertEquals(1, manager.messageGroupCount(messages))
    }

    @Test
    fun getPromptMessageGroups_whenLatestInContext_movesPreviousAfterSystemPrompt() {
        val messages = messages(5)

        val groups = manager.getPromptMessageGroups(messages, latestMessageId = "message-4")

        assertEquals(listOf("0", "1", "2"), groups.historyMessages.map { it.content })
        assertEquals(listOf("3"), groups.previousTurnMessages.map { it.content })
    }

    @Test
    fun getPromptMessageGroups_whenLatestIsSynthetic_movesLastContextMessageAfterSystemPrompt() {
        val messages = messages(5)

        val groups = manager.getPromptMessageGroups(messages, latestMessageId = null)

        assertEquals(listOf("0", "1", "2", "3"), groups.historyMessages.map { it.content })
        assertEquals(listOf("4"), groups.previousTurnMessages.map { it.content })
    }

    @Test
    fun getPromptMessageGroups_whenLatestHasNoPrevious_keepsHistoryEmpty() {
        val messages = messages(1)

        val groups = manager.getPromptMessageGroups(messages, latestMessageId = "message-0")

        assertEquals(emptyList<ChatMessage>(), groups.historyMessages)
        assertTrue(groups.previousTurnMessages.isEmpty())
    }

    @Test
    fun getPromptMessageGroups_movesCompletePreviousTurnAfterSystemPrompt() {
        val messages = conversationGroups(3) + message("current-user", MessageRole.USER, "u3")

        val groups = manager.getPromptMessageGroups(messages, latestMessageId = "current-user")

        assertEquals(listOf("u0", "a0", "u1", "a1"), groups.historyMessages.map { it.content })
        assertEquals(listOf("u2", "a2"), groups.previousTurnMessages.map { it.content })
    }

    @Test
    fun groupPolicy_preservesOpeningAssistantAndConsecutiveUsers() {
        val messages = listOf(
            message("greeting", MessageRole.ASSISTANT, "greeting"),
            message("user-0", MessageRole.USER, "u0"),
            message("user-1", MessageRole.USER, "u1"),
            message("assistant-1", MessageRole.ASSISTANT, "a1")
        )

        val groups = ChatContextGroupPolicy.groups(messages)

        assertEquals(
            listOf(listOf("greeting"), listOf("u0"), listOf("u1", "a1")),
            groups.map { group -> group.messages.map { it.content } }
        )
        assertFalse(groups[0].isCompleteTurn)
        assertFalse(groups[1].isCompleteTurn)
        assertTrue(groups[2].isCompleteTurn)
    }

    private fun messages(count: Int): List<ChatMessage> =
        (0 until count).map { index ->
            ChatMessage(
                id = "message-$index",
                sessionId = "session",
                role = MessageRole.USER,
                content = index.toString(),
                createdAt = index.toLong(),
                updatedAt = index.toLong()
            )
        }

    private fun conversationGroups(count: Int): List<ChatMessage> =
        (0 until count).flatMap { index ->
            listOf(
                message("user-$index", MessageRole.USER, "u$index"),
                message("assistant-$index", MessageRole.ASSISTANT, "a$index")
            )
        }

    private fun message(id: String, role: MessageRole, content: String): ChatMessage =
        ChatMessage(
            id = id,
            sessionId = "session",
            role = role,
            content = content,
            createdAt = id.hashCode().toLong(),
            updatedAt = id.hashCode().toLong()
        )
}
