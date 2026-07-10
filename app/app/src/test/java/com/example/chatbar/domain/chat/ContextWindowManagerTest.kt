package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Test

class ContextWindowManagerTest {
    private val manager = ContextWindowManager()

    @Test
    fun getRecentMessages_whenOverWindow_returnsLatestMessages() {
        val messages = messages(5)

        val recent = manager.getRecentMessages(messages, windowSize = 2)

        assertEquals(listOf("3", "4"), recent.map { it.content })
    }

    @Test
    fun getMessagesToArchive_whenOverWindow_returnsOlderMessages() {
        val messages = messages(5)

        val archived = manager.getMessagesToArchive(messages, windowSize = 2)

        assertEquals(listOf("0", "1", "2"), archived.map { it.content })
    }

    @Test
    fun shouldRefreshSystemPrompt_onlyWhenMessageCountExceedsWindow() {
        assertFalse(manager.shouldRefreshSystemPrompt(messageCount = 2, windowSize = 2))
        assertTrue(manager.shouldRefreshSystemPrompt(messageCount = 3, windowSize = 2))
    }

    @Test
    fun getCacheableHistoryMessages_keepsEntireWindowExceptCurrentUserMessage() {
        val messages = messages(5)

        val history = manager.getCacheableHistoryMessages(messages, latestMessageId = "message-4")

        assertEquals(listOf("0", "1", "2", "3"), history.map { it.content })
    }

    @Test
    fun getCacheableHistoryMessages_keepsEntireWindowWhenCurrentMessageIsSynthetic() {
        val messages = messages(5)

        val history = manager.getCacheableHistoryMessages(messages, latestMessageId = null)

        assertEquals(listOf("0", "1", "2", "3", "4"), history.map { it.content })
    }

    @Test
    fun getPromptMessageGroups_whenLatestInContext_movesPreviousAfterSystemPrompt() {
        val messages = messages(5)

        val groups = manager.getPromptMessageGroups(messages, latestMessageId = "message-4")

        assertEquals(listOf("0", "1", "2"), groups.historyMessages.map { it.content })
        assertEquals("3", groups.previousMessage?.content)
    }

    @Test
    fun getPromptMessageGroups_whenLatestIsSynthetic_movesLastContextMessageAfterSystemPrompt() {
        val messages = messages(5)

        val groups = manager.getPromptMessageGroups(messages, latestMessageId = null)

        assertEquals(listOf("0", "1", "2", "3"), groups.historyMessages.map { it.content })
        assertEquals("4", groups.previousMessage?.content)
    }

    @Test
    fun getPromptMessageGroups_whenLatestHasNoPrevious_keepsHistoryEmpty() {
        val messages = messages(1)

        val groups = manager.getPromptMessageGroups(messages, latestMessageId = "message-0")

        assertEquals(emptyList<ChatMessage>(), groups.historyMessages)
        assertNull(groups.previousMessage)
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
}
