package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageFormatRepairNotice
import com.example.chatbar.data.local.entity.MessageFormatRepairNoticeKind
import com.example.chatbar.data.local.entity.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageFormatRepairPolicyTest {
    @Test
    fun progressiveOverlay_replacesByUnicodeCodePoint() {
        assertEquals("修😀尾", MessageFormatRepairPolicy.progressiveOverlay("原😀尾", "修😀"))
        assertEquals("完整更长", MessageFormatRepairPolicy.progressiveOverlay("原文", "完整更长"))
        assertEquals("原文", MessageFormatRepairPolicy.progressiveOverlay("原文", ""))
    }

    @Test
    fun completedRepairNotice_keepsOriginalForNormalLengthRepair() {
        val original = "原始回复"
        val repaired = "错误回复"
        val notice = MessageFormatRepairPolicy.completedRepairNotice(original, repaired)

        assertEquals(MessageFormatRepairNoticeKind.APPLIED, notice.kind)
        assertEquals(original, notice.originalContent)
        assertEquals(repaired, notice.targetContent)

        val updated = MessageFormatRepairPolicy.replaceCurrentDisplayContent(
            message = message().copy(content = original),
            replacement = repaired,
            notice = notice,
            updatedAt = 9
        )
        val restored = MessageFormatRepairPolicy.restoreOriginal(updated, updatedAt = 10)

        assertEquals(original, restored?.displayContent)
        assertNull(restored?.formatRepairNotice)
    }

    @Test
    fun completedRepairNotice_acceptsLargeLengthChangeAndKeepsOriginal() {
        val notice = MessageFormatRepairPolicy.completedRepairNotice("原始回复很长", "坏")

        assertEquals(MessageFormatRepairNoticeKind.APPLIED, notice.kind)
        assertEquals("原始回复很长", notice.originalContent)
        assertEquals("坏", notice.targetContent)
    }

    @Test
    fun recoverableNotice_keepsRollbackPointAcrossNoOpRepair() {
        val notice = MessageFormatRepairPolicy.completedRepairNotice("原始回复", "修复回复")
        val repaired = message().copy(
            content = "修复回复",
            formatRepairNotice = notice
        )

        assertEquals(notice, MessageFormatRepairPolicy.recoverableNotice(repaired))

        val failed = repaired.copy(
            formatRepairNotice = MessageFormatRepairNotice(
                kind = MessageFormatRepairNoticeKind.ERROR,
                targetContent = repaired.displayContent
            )
        )
        assertNull(MessageFormatRepairPolicy.recoverableNotice(failed))
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
