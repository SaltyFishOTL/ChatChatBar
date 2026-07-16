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

    @Test
    fun sourceTurnGrouping_countsAllRepliesInOneTAsOneContextGroup() {
        val messages = (0L..2L).flatMap { turn ->
            listOf(
                turnMessage("user-$turn", MessageRole.USER, "u$turn", turn),
                turnMessage("assistant-$turn", MessageRole.ASSISTANT, "a$turn", turn),
                turnMessage("append-$turn", MessageRole.ASSISTANT, "append$turn", turn)
            )
        }

        val grouped = ChatContextGroupPolicy.groups(messages)
        val recent = manager.getRecentMessages(messages, windowSize = 2)

        assertEquals(3, grouped.size)
        assertEquals(listOf(3, 3, 3), grouped.map { it.messages.size })
        assertTrue(grouped.all { it.isCompleteTurn })
        assertEquals(messages.map { it.id }, recent.map { it.id })
    }

    @Test
    fun sourceTurnGrouping_withTwentyFourTurnsAndWindowFifteenArchivesOnlyT0ThroughT7() {
        val messages = (0L..23L).flatMap { turn ->
            buildList {
                if (turn > 0) add(turnMessage("user-$turn", MessageRole.USER, "u$turn", turn))
                add(turnMessage("assistant-$turn", MessageRole.ASSISTANT, "a$turn", turn))
                if (turn % 2L == 0L) {
                    add(turnMessage("append-$turn", MessageRole.ASSISTANT, "append$turn", turn))
                }
            }
        }

        val archived = manager.getMessagesToArchive(messages, windowSize = 15)

        assertEquals((0L..7L).map { "source-$it" }.toSet(), archived.mapNotNull { it.sourceTurnId }.toSet())
        assertTrue(archived.all { it.sourceTurnOrder in 0L..7L })
    }

    @Test
    fun adjacentExchangeGrouping_remainsIndependentFromSourceTurns() {
        val messages = listOf(
            turnMessage("user", MessageRole.USER, "u", 0),
            turnMessage("assistant", MessageRole.ASSISTANT, "a", 0),
            turnMessage("append", MessageRole.ASSISTANT, "append", 0)
        )

        val grouped = ChatAdjacentExchangeGroupPolicy.groups(messages)

        assertEquals(listOf(listOf("u", "a"), listOf("append")), grouped.map { group ->
            group.messages.map { it.content }
        })
    }

    @Test
    fun sourceTurnGrouping_systemMessageInsideTurnDoesNotSplitT() {
        val messages = listOf(
            message("prefix-system", MessageRole.SYSTEM, "prefix"),
            turnMessage("user", MessageRole.USER, "u", 1),
            message("system", MessageRole.SYSTEM, "system"),
            turnMessage("assistant", MessageRole.ASSISTANT, "a", 1)
        )

        val grouped = ChatContextGroupPolicy.groups(messages)

        assertEquals(1, grouped.size)
        assertEquals(listOf("prefix", "u", "system", "a"), grouped.single().messages.map { it.content })
        assertTrue(grouped.single().isCompleteTurn)
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

    private fun turnMessage(
        id: String,
        role: MessageRole,
        content: String,
        turn: Long
    ): ChatMessage = ChatMessage(
        id = id,
        sessionId = "session",
        role = role,
        content = content,
        createdAt = turn,
        updatedAt = turn,
        orderKey = turn * 10 + when (role) {
            MessageRole.USER -> 0
            MessageRole.ASSISTANT -> if (id.startsWith("append")) 2 else 1
            MessageRole.SYSTEM -> 3
        },
        sourceTurnId = "source-$turn",
        sourceTurnOrder = turn
    )
}
