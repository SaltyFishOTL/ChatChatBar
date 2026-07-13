package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageFormatRepairNotice
import com.example.chatbar.data.local.entity.MessageFormatRepairNoticeKind
import com.example.chatbar.data.local.entity.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFormatRepairPolicyTest {
    @Test
    fun progressiveOverlay_replacesByUnicodeCodePoint() {
        assertEquals("修😀尾", MessageFormatRepairPolicy.progressiveOverlay("原😀尾", "修😀"))
        assertEquals("完整更长", MessageFormatRepairPolicy.progressiveOverlay("原文", "完整更长"))
        assertEquals("原文", MessageFormatRepairPolicy.progressiveOverlay("原文", ""))
    }

    @Test
    fun lengthAnomaly_usesExclusiveHalfAndDoubleBounds() {
        assertFalse(MessageFormatRepairPolicy.isLengthAnomalous("1234", "12"))
        assertFalse(MessageFormatRepairPolicy.isLengthAnomalous("1234", "12345678"))
        assertTrue(MessageFormatRepairPolicy.isLengthAnomalous("1234", "1"))
        assertTrue(MessageFormatRepairPolicy.isLengthAnomalous("1234", "123456789"))
    }

    @Test
    fun replacementAndRestore_preserveOtherAlternatives() {
        val notice = MessageFormatRepairNotice(
            kind = MessageFormatRepairNoticeKind.STOPPED,
            targetContent = "fixed-b",
            originalContent = "b"
        )
        val updated = MessageFormatRepairPolicy.replaceCurrentDisplayContent(
            message = message().copy(
                content = "b",
                alternatives = listOf("a", "b", "c"),
                currentAlternativeIndex = 1
            ),
            replacement = "fixed-b",
            notice = notice,
            updatedAt = 9
        )

        assertEquals(listOf("a", "fixed-b", "c"), updated.alternatives)
        assertEquals("fixed-b", updated.content)
        assertEquals(notice, MessageFormatRepairPolicy.applicableNotice(updated))

        val restored = MessageFormatRepairPolicy.restoreOriginal(updated, updatedAt = 10)
        assertEquals(listOf("a", "b", "c"), restored?.alternatives)
        assertEquals("b", restored?.content)
        assertNull(restored?.formatRepairNotice)
    }

    @Test
    fun noticeIsHiddenWhenAnotherAlternativeIsSelected() {
        val message = message().copy(
            content = "fixed",
            alternatives = listOf("fixed", "other"),
            currentAlternativeIndex = 1,
            formatRepairNotice = MessageFormatRepairNotice(
                kind = MessageFormatRepairNoticeKind.LENGTH_ANOMALY,
                targetContent = "fixed",
                originalContent = "original"
            )
        )

        assertNull(MessageFormatRepairPolicy.applicableNotice(message))
    }

    private fun message() = ChatMessage(
        id = "m",
        sessionId = "s",
        role = MessageRole.ASSISTANT,
        content = "text",
        createdAt = 1,
        updatedAt = 1
    )
}
