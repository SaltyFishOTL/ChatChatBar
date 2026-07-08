package com.example.chatbar.ui.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenshotSelectionTest {
    @Test
    fun toggleSelection_addsRemovesAndIgnoresUnselectable() {
        val selectable = setOf("m1", "m2")

        val added = toggleChatScreenshotSelection(emptySet(), "m1", selectable)
        assertEquals(listOf("m1"), added.toList())

        val removed = toggleChatScreenshotSelection(added, "m1", selectable)
        assertTrue(removed.isEmpty())

        val ignored = toggleChatScreenshotSelection(setOf("m1"), "missing", selectable)
        assertEquals(listOf("m1"), ignored.toList())
    }

    @Test
    fun cleanSelection_removesDeletedAndSystemMessages() {
        val messages = listOf(
            message("user", MessageRole.USER),
            message("assistant", MessageRole.ASSISTANT),
            message("system", MessageRole.SYSTEM)
        )

        val cleaned = cleanChatScreenshotSelection(
            setOf("missing", "system", "assistant", "user"),
            messages
        )

        assertEquals(listOf("assistant", "user"), cleaned.toList())
    }

    @Test
    fun orderedMessages_followTimelineNotClickOrder() {
        val messages = listOf(
            message("first", MessageRole.USER),
            message("system", MessageRole.SYSTEM),
            message("second", MessageRole.ASSISTANT)
        )

        val ordered = orderedChatScreenshotMessages(messages, setOf("second", "first"))

        assertEquals(listOf("first", "second"), ordered.map { it.id })
    }

    @Test
    fun fileName_sanitizesUnsafeCharacters() {
        val fileName = buildChatScreenshotFileName("A/B:*? C", 1_718_123_456_000L)

        assertTrue(fileName.startsWith("ChatBar_A_B____C_"))
        assertTrue(fileName.endsWith(".png"))
        assertFalse(fileName.contains('/'))
        assertFalse(fileName.contains(':'))
    }

    private fun message(id: String, role: MessageRole): ChatMessage =
        ChatMessage(
            id = id,
            sessionId = "session",
            role = role,
            content = id,
            createdAt = 1,
            updatedAt = 1
        )
}
