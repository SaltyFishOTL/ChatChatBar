package com.example.chatbar.ui.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.chat.roleplayLegacyTextBlockId
import com.example.chatbar.domain.chat.roleplayTextBlockId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenshotSelectionTest {
    @Test
    fun toggleSelection_addsRemovesAndIgnoresUnselectable() {
        val selectable = setOf("m1::text::legacy", "m2::text::0")

        val added = toggleChatScreenshotSelection(emptySet(), "m1::text::legacy", selectable)
        assertEquals(listOf("m1::text::legacy"), added.toList())

        val removed = toggleChatScreenshotSelection(added, "m1::text::legacy", selectable)
        assertTrue(removed.isEmpty())

        val ignored = toggleChatScreenshotSelection(setOf("m1::text::legacy"), "missing", selectable)
        assertEquals(listOf("m1::text::legacy"), ignored.toList())
    }

    @Test
    fun cleanSelection_removesDeletedAndSystemMessages() {
        val messages = listOf(
            message("user", MessageRole.USER),
            message("assistant", MessageRole.ASSISTANT),
            message("system", MessageRole.SYSTEM)
        )

        val cleaned = cleanChatScreenshotSelection(
            setOf(
                "missing",
                roleplayLegacyTextBlockId("system"),
                roleplayTextBlockId("assistant", 0),
                roleplayLegacyTextBlockId("user")
            ),
            messages
        )

        assertEquals(
            listOf(roleplayTextBlockId("assistant", 0), roleplayLegacyTextBlockId("user")),
            cleaned.toList()
        )
    }

    @Test
    fun orderedMessages_followTimelineNotClickOrder() {
        val messages = listOf(
            message("first", MessageRole.USER),
            message("system", MessageRole.SYSTEM),
            message("second", MessageRole.ASSISTANT)
        )

        val ordered = orderedChatScreenshotMessages(
            messages,
            setOf(roleplayTextBlockId("second", 0), roleplayLegacyTextBlockId("first"))
        )

        assertEquals(listOf("first", "second"), ordered.map { it.id })
    }

    @Test
    fun latestRegenerableAssistantMessage_skipsTrailingImageOnlyMessages() {
        val messages = listOf(
            message("assistant-text", MessageRole.ASSISTANT, content = "[继续]()"),
            message("assistant-image", MessageRole.ASSISTANT, content = "", images = listOf("image.png"))
        )

        assertEquals("assistant-text", latestRegenerableAssistantMessageId(messages))
    }

    @Test
    fun latestRegenerableAssistantMessage_returnsNullWhenLastNonImageIsUser() {
        val messages = listOf(
            message("assistant-text", MessageRole.ASSISTANT, content = "[继续]()"),
            message("user", MessageRole.USER, content = "下一句"),
            message("assistant-image", MessageRole.ASSISTANT, content = "", images = listOf("image.png"))
        )

        assertEquals(null, latestRegenerableAssistantMessageId(messages))
    }

    @Test
    fun fileName_sanitizesUnsafeCharacters() {
        val fileName = buildChatScreenshotFileName("A/B:*? C", 1_718_123_456_000L)

        assertTrue(fileName.startsWith("ChatBar_A_B____C_"))
        assertTrue(fileName.endsWith(".png"))
        assertFalse(fileName.contains('/'))
        assertFalse(fileName.contains(':'))
    }

    private fun message(
        id: String,
        role: MessageRole,
        content: String = id,
        images: List<String> = emptyList()
    ): ChatMessage =
        ChatMessage(
            id = id,
            sessionId = "session",
            role = role,
            content = content,
            images = images,
            createdAt = 1,
            updatedAt = 1
        )
}
