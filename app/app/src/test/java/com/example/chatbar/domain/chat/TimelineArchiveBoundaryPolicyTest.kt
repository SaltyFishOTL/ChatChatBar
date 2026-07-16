package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineArchiveBoundaryPolicyTest {
    @Test
    fun directContextExpandsPartialAppendBackToWholeTurn() {
        val t0User = message("u0", MessageRole.USER, 0)
        val t0Assistant = message("a0", MessageRole.ASSISTANT, 0)
        val t0Append = message("a0-extra", MessageRole.ASSISTANT, 0)
        val t1User = message("u1", MessageRole.USER, 1)
        val all = listOf(t0User, t0Assistant, t0Append, t1User)

        val expanded = TimelineArchiveBoundaryPolicy.expandDirectContextToWholeTurns(
            all,
            recentMessages = listOf(t0Append, t1User)
        )

        assertEquals(all.map { it.id }, expanded.map { it.id })
    }

    @Test
    fun onlyPendingSourceTurnsRemainOutsideNormalContextWindow() {
        val t0 = message("u0", MessageRole.USER, 0)
        val t1 = message("u1", MessageRole.USER, 1)
        val t2 = message("u2", MessageRole.USER, 2)
        val t3 = message("u3", MessageRole.USER, 3)
        val all = listOf(t0, t1, t2, t3)

        val expanded = TimelineArchiveBoundaryPolicy.expandDirectContextAfterArchive(
            allMessages = all,
            directContext = listOf(t3),
            pendingSourceTurnIds = setOf("s2")
        )

        assertEquals(listOf("u2", "u3"), expanded.map { it.id })
    }

    @Test
    fun gapWithoutPendingDoesNotKeepOldRawTurnsInChatContext() {
        val system = message("system", MessageRole.SYSTEM, 0)
        val t0 = message("u0", MessageRole.USER, 0)
        val t1 = message("u1", MessageRole.USER, 1)
        val all = listOf(system, t0, t1)

        val expanded = TimelineArchiveBoundaryPolicy.expandDirectContextAfterArchive(
            allMessages = all,
            directContext = listOf(t1),
            pendingSourceTurnIds = emptySet()
        )

        assertEquals(listOf("u1"), expanded.map { it.id })
    }

    private fun message(id: String, role: MessageRole, turn: Long) = ChatMessage(
        id = id,
        sessionId = "session",
        role = role,
        content = id,
        timelineTurn = turn,
        sourceTurnId = if (role == MessageRole.SYSTEM) null else "s$turn",
        sourceTurnOrder = if (role == MessageRole.SYSTEM) null else turn,
        createdAt = turn,
        updatedAt = turn
    )
}
