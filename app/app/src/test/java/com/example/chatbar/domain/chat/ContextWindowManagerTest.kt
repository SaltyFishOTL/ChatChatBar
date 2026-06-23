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
