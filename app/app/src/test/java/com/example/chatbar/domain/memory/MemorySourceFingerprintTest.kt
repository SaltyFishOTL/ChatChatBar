package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MemorySourceFingerprintTest {
    private val session = ChatSession.create("card", "title")
    private val base = listOf(
        message("u", MessageRole.USER, "hello", 1),
        message("a", MessageRole.ASSISTANT, "world", 2)
    )

    @Test fun `updatedAt and normalized order keys do not change fingerprint`() {
        val changedMetadata = base.mapIndexed { index, message ->
            message.copy(updatedAt = 999L + index, orderKey = (index + 10L) * 1_000_000L)
        }
        assertEquals(fingerprint(base), fingerprint(changedMetadata))
    }

    @Test fun `content alternative order and images change fingerprint`() {
        assertNotEquals(fingerprint(base), fingerprint(base.map { if (it.id == "a") it.copy(content = "changed") else it }))
        assertNotEquals(fingerprint(base), fingerprint(base.map { if (it.id == "a") it.copy(alternatives = listOf("alt")) else it }))
        assertNotEquals(fingerprint(base), fingerprint(listOf(base[1].copy(orderKey = 1), base[0].copy(orderKey = 2))))
        assertNotEquals(fingerprint(base), fingerprint(base.map { if (it.id == "a") it.copy(images = listOf("x.png")) else it }))
        assertNotEquals(fingerprint(base), fingerprint(base.dropLast(1)))
    }

    private fun fingerprint(messages: List<ChatMessage>) =
        MemorySourceFingerprint.semantic("s0", messages, session)

    private fun message(id: String, role: MessageRole, content: String, order: Long) = ChatMessage(
        id = id,
        sessionId = session.id,
        role = role,
        content = content,
        createdAt = order,
        updatedAt = order,
        orderKey = order,
        sourceTurnId = "s0",
        sourceTurnOrder = 0
    )
}
