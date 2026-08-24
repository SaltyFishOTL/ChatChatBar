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

    @Test fun `metadata not shown to memory model does not change fingerprint`() {
        val changedMetadata = base.mapIndexed { index, message ->
            message.copy(
                id = "replacement-$index",
                updatedAt = 999L + index,
                orderKey = (index + 10L) * 1_000_000L,
                sourceTurnOrder = 99,
                images = listOf("moved-$index.png")
            )
        }
        assertEquals(fingerprint(base), fingerprint(changedMetadata))
    }

    @Test fun `content alternative order blank-image presence and deletion change fingerprint`() {
        assertNotEquals(fingerprint(base), fingerprint(base.map { if (it.id == "a") it.copy(content = "changed") else it }))
        assertNotEquals(fingerprint(base), fingerprint(base.map { if (it.id == "a") it.copy(alternatives = listOf("alt")) else it }))
        assertNotEquals(fingerprint(base), fingerprint(listOf(base[1].copy(orderKey = 1), base[0].copy(orderKey = 2))))
        assertNotEquals(fingerprint(base), fingerprint(base.dropLast(1)))

        val blankAssistant = base.map { if (it.id == "a") it.copy(content = "") else it }
        val blankWithImage = blankAssistant.map {
            if (it.id == "a") it.copy(images = listOf("x.png")) else it
        }
        assertNotEquals(
            fingerprint(blankAssistant),
            fingerprint(blankWithImage)
        )
        assertEquals(
            fingerprint(blankWithImage),
            fingerprint(blankWithImage.map {
                if (it.id == "a") it.copy(images = listOf("moved.png", "second.png")) else it
            })
        )
    }

    @Test fun `previous semantic fingerprint remains available for safe migration`() {
        assertNotEquals(
            fingerprint(base),
            MemorySourceFingerprint.previousSemantic("s0", base, session)
        )
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
