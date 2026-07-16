package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.SourceTurnTombstone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineTurnPolicyTest {
    @Test
    fun migratesPrologueAndCompleteUserAssistantTurnsWithoutChangingIdentityOrOrder() {
        val original = listOf(
            message("prologue", MessageRole.ASSISTANT, 1),
            message("user-1", MessageRole.USER, 2),
            message("assistant-1", MessageRole.ASSISTANT, 3),
            message("user-2", MessageRole.USER, 4),
            message("assistant-2", MessageRole.ASSISTANT, 5)
        )

        val result = TimelineTurnPolicy.migrate(original, initialNextTurn = 1)

        assertEquals(listOf(0L, 1L, 1L, 2L, 2L), result.messages.map { it.timelineTurn })
        assertEquals(listOf(0L, 1L, 1L, 2L, 2L), result.messages.map { it.sourceTurnOrder })
        assertEquals(3, result.messages.map { it.sourceTurnId }.distinct().size)
        assertEquals(original.map { it.id }, result.messages.map { it.id })
        assertEquals(original.map { it.orderKey }, result.messages.map { it.orderKey })
        assertEquals(3L, result.nextTimelineTurn)
    }

    @Test
    fun assistantRegenerationAndAppendReuseSourceTurn() {
        val user = message("user", MessageRole.USER, 1).copy(
            sourceTurnId = "stable-turn",
            sourceTurnOrder = 7
        )
        val regeneration = message("regen", MessageRole.ASSISTANT, 2)
            .copy(generatedFromMessageId = user.id)

        val assignment = TimelineTurnPolicy.nextForAppend(
            regeneration,
            listOf(user),
            nextSourceTurnOrder = 8,
            tombstones = emptyList(),
            newSourceTurnId = "must-not-be-used"
        )

        assertEquals(SourceTurnAssignment("stable-turn", 7), assignment)
    }

    @Test
    fun deletedT0TombstonePreventsTurnReuse() {
        val next = TimelineTurnPolicy.nextForAppend(
            message("new-user", MessageRole.USER, 1),
            existingMessages = emptyList(),
            nextSourceTurnOrder = 1,
            tombstones = listOf(SourceTurnTombstone("deleted", 0)),
            newSourceTurnId = "new"
        )

        assertEquals(SourceTurnAssignment("new", 1), next)
    }

    @Test
    fun systemMessagesHaveNoStoryTurn() {
        assertNull(
            TimelineTurnPolicy.nextForAppend(
                message("system", MessageRole.SYSTEM, 1),
                existingMessages = emptyList(),
                nextSourceTurnOrder = 1,
                tombstones = emptyList(),
                newSourceTurnId = "unused"
            )
        )
    }

    private fun message(id: String, role: MessageRole, orderKey: Long) = ChatMessage(
        id = id,
        sessionId = "session",
        role = role,
        content = id,
        orderKey = orderKey,
        createdAt = orderKey.toLong(),
        updatedAt = orderKey.toLong()
    )
}
