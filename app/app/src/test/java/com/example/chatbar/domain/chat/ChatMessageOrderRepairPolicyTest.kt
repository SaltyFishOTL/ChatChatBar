package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MESSAGE_ORDER_STEP
import com.example.chatbar.data.local.entity.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageOrderRepairPolicyTest {
    @Test
    fun `repair restores anchored image before later source turn`() {
        val original = listOf(
            message("user-1", MessageRole.USER, 10, 1, "turn-1", 0),
            message("assistant-1", MessageRole.ASSISTANT, 20, 2, "turn-1", 0),
            message("user-2", MessageRole.USER, 30, 3, "turn-2", 1),
            message("assistant-2", MessageRole.ASSISTANT, 40, 4, "turn-2", 1),
            message(
                "image-1",
                MessageRole.ASSISTANT,
                50,
                5,
                "turn-1",
                0,
                generatedFromMessageId = "assistant-1"
            )
        )

        val plan = ChatMessageOrderRepairPolicy.plan(original)

        assertEquals(
            listOf("user-1", "assistant-1", "image-1", "user-2", "assistant-2"),
            plan.repairedMessages.map(ChatMessage::id)
        )
        assertEquals(3, plan.moves.size)
        assertEquals(1, plan.anchoredMessageCount)
        assertTrue(plan.orphanedAnchorMessageIds.isEmpty())
        assertTrue(plan.cyclicAnchorMessageIds.isEmpty())
        val originalById = original.associateBy(ChatMessage::id)
        plan.repairedMessages.forEach { repaired ->
            assertEquals(repaired.copy(orderKey = originalById.getValue(repaired.id).orderKey), originalById.getValue(repaired.id))
        }
    }

    @Test
    fun `repair keeps chained generated images beside their anchors`() {
        val plan = ChatMessageOrderRepairPolicy.plan(
            listOf(
                message("anchor", MessageRole.ASSISTANT, 10, 1, "turn-1", 0),
                message("later", MessageRole.USER, 20, 2, "turn-2", 1),
                message("image-2", MessageRole.ASSISTANT, 40, 3, "turn-1", 0, "image-1"),
                message("image-1", MessageRole.ASSISTANT, 30, 4, "turn-1", 0, "anchor")
            )
        )

        assertEquals(
            listOf("anchor", "image-1", "image-2", "later"),
            plan.repairedMessages.map(ChatMessage::id)
        )
    }

    @Test
    fun `already correct timeline keeps every order key`() {
        val messages = listOf(
            message("anchor", MessageRole.ASSISTANT, 10, 7, "turn-1", 0),
            message("image", MessageRole.ASSISTANT, 20, 8, "turn-1", 0, "anchor"),
            message("later", MessageRole.USER, 30, 9, "turn-2", 1)
        )

        val plan = ChatMessageOrderRepairPolicy.plan(messages)

        assertFalse(plan.requiresRepair)
        assertEquals(messages.map(ChatMessage::orderKey), plan.repairedMessages.map(ChatMessage::orderKey))
    }

    @Test
    fun `orphaned anchor is reported and handled conservatively`() {
        val plan = ChatMessageOrderRepairPolicy.plan(
            listOf(
                message("first", MessageRole.USER, 10, 1, null, null),
                message("orphan", MessageRole.ASSISTANT, 20, 2, null, null, "missing")
            )
        )

        assertEquals(listOf("orphan"), plan.orphanedAnchorMessageIds)
        assertEquals(listOf("first", "orphan"), plan.repairedMessages.map(ChatMessage::id))
    }

    @Test
    fun `cyclic anchors are reported and fall back to source time order`() {
        val plan = ChatMessageOrderRepairPolicy.plan(
            listOf(
                message("later", MessageRole.USER, 30, 1, "turn-2", 1),
                message("cycle-a", MessageRole.ASSISTANT, 10, 2, "turn-1", 0, "cycle-b"),
                message("cycle-b", MessageRole.ASSISTANT, 20, 3, "turn-1", 0, "cycle-a")
            )
        )

        assertEquals(listOf("cycle-a", "cycle-b"), plan.cyclicAnchorMessageIds)
        assertEquals(listOf("cycle-a", "cycle-b", "later"), plan.repairedMessages.map(ChatMessage::id))
    }

    private fun message(
        id: String,
        role: MessageRole,
        createdAt: Long,
        order: Long,
        sourceTurnId: String?,
        sourceTurnOrder: Long?,
        generatedFromMessageId: String? = null
    ) = ChatMessage(
        id = id,
        sessionId = "session",
        role = role,
        content = id,
        images = if (id.startsWith("image")) listOf("/$id.png") else emptyList(),
        generatedFromMessageId = generatedFromMessageId,
        createdAt = createdAt,
        updatedAt = createdAt + 1,
        orderKey = order * MESSAGE_ORDER_STEP,
        sourceTurnId = sourceTurnId,
        sourceTurnOrder = sourceTurnOrder
    )
}
