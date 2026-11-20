package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageFormatRepairNotice
import com.example.chatbar.data.local.entity.MessageFormatRepairNoticeKind
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.prompt.PromptTemplates
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
    fun progressiveOverlay_hidesPossibleUnchangedMarker() {
        val marker = PromptTemplates.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER

        assertEquals("原文", MessageFormatRepairPolicy.progressiveOverlay("原文", "[[CHAT"))
        assertEquals("原文", MessageFormatRepairPolicy.progressiveOverlay("原文", "\n$marker"))
        assertEquals("修文", MessageFormatRepairPolicy.progressiveOverlay("原文", "修"))
    }

    @Test
    fun unchangedResult_requiresExactTrimmedMarker() {
        val marker = PromptTemplates.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER

        assertTrue(MessageFormatRepairPolicy.isUnchangedResult(marker))
        assertTrue(MessageFormatRepairPolicy.isUnchangedResult("\n$marker \n"))
        assertFalse(MessageFormatRepairPolicy.isUnchangedResult("$marker\n额外内容"))
        assertFalse(MessageFormatRepairPolicy.isUnchangedResult("原文"))
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
        assertEquals(true, restored?.formatRepairNotice?.restored)
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
        assertEquals(notice.copy(versionId = MessageAlternativeVersionPolicy.activeVersionId(updated)), MessageFormatRepairPolicy.applicableNotice(updated))

        val restored = MessageFormatRepairPolicy.restoreOriginal(updated, updatedAt = 10)
        assertEquals(listOf("a", "b", "c"), restored?.alternatives)
        assertEquals("b", restored?.content)
        assertEquals(true, restored?.formatRepairNotice?.restored)
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

    @Test fun repairWriterPersistsStableVersionIdentityBeforeMessageIdChanges() {
        for (mode in listOf(null, com.example.chatbar.data.local.entity.ChatNarrativeMode.ADVANCE, com.example.chatbar.data.local.entity.ChatNarrativeMode.FOCUS)) for (multi in listOf(false, true)) {
            val original = message().copy(content = "before", alternatives = if (multi) listOf("other", "before") else emptyList(),
                currentAlternativeIndex = if (multi) 1 else 0, narrativeMode = mode)
            val repaired = MessageFormatRepairPolicy.replaceCurrentDisplayContent(original, "after",
                MessageFormatRepairPolicy.completedRepairNotice("before", "after"))
            val identity = MessageAlternativeVersionPolicy.activeVersionId(original)
            assertEquals(original.narrativeMode, repaired.narrativeMode)
            assertEquals(original.alternativeNarrativeModes, repaired.alternativeNarrativeModes)
            assertEquals(identity, repaired.currentAlternativeVersionId)
            assertEquals(if (multi) 2 else 0, repaired.alternativeVersionIds.size)
            val copied = repaired.copy(id = "copied-message")
            assertEquals(identity, MessageAlternativeVersionPolicy.activeVersionId(copied))
            val undone = requireNotNull(MessageFormatRepairPolicy.restoreOriginal(copied))
            assertEquals("before", undone.displayContent)
            assertEquals("after", requireNotNull(MessageFormatRepairPolicy.redoRepair(undone)).displayContent)
        }
    }

    @Test fun unchangedSuccessClearsNoticesWithoutLocalRecovery() {
        for (kind in MessageFormatRepairNoticeKind.values()) {
            val original = message().copy(formatRepairNotice = MessageFormatRepairNotice(kind,
                targetContent = message().content, errorMessage = "previous failure"))
            assertNull(MessageFormatRepairPolicy.retainedNoticeAfterUnchangedSuccess(original))
        }
    }

    @Test fun unchangedSuccessKeepsAppliedAndStoppedUndoPairs() {
        for (kind in listOf(MessageFormatRepairNoticeKind.APPLIED, MessageFormatRepairNoticeKind.STOPPED)) {
            val repaired = MessageFormatRepairPolicy.replaceCurrentDisplayContent(message(), "after",
                MessageFormatRepairNotice(kind, "after", originalContent = message().content))
            val kept = MessageFormatRepairPolicy.retainedNoticeAfterUnchangedSuccess(repaired)
            assertEquals(repaired.formatRepairNotice, kept)
            assertEquals(message().content, MessageFormatRepairPolicy.restoreOriginal(repaired.copy(formatRepairNotice = kept))?.displayContent)
        }
    }

    @Test fun unchangedSuccessKeepsRestoredLocalRedoPair() {
        val repaired = MessageFormatRepairPolicy.replaceCurrentDisplayContent(message(), "after",
            MessageFormatRepairPolicy.completedRepairNotice(message().content, "after"))
        val restored = requireNotNull(MessageFormatRepairPolicy.restoreOriginal(repaired))
        val kept = MessageFormatRepairPolicy.retainedNoticeAfterUnchangedSuccess(restored)
        assertEquals(restored.formatRepairNotice, kept)
        assertEquals("after", MessageFormatRepairPolicy.redoRepair(restored.copy(formatRepairNotice = kept))?.displayContent)
    }

    @Test fun unchangedSuccessKeepsValidInactiveRedoWithoutDisplayingItOnTheSibling() {
        val original = MessageAlternativeVersionPolicy.append(message(), "active before", "repair-owner")
        val repaired = MessageFormatRepairPolicy.replaceCurrentDisplayContent(original, "fixed",
            MessageFormatRepairPolicy.completedRepairNotice("active before", "fixed"))
        val undone = requireNotNull(MessageFormatRepairPolicy.restoreOriginal(repaired))
        val sibling = MessageAlternativeVersionPolicy.select(undone, 0)
        assertNull(MessageFormatRepairPolicy.applicableNotice(sibling))
        val kept = MessageFormatRepairPolicy.retainedNoticeAfterUnchangedSuccess(sibling)
        assertEquals(undone.formatRepairNotice, kept)
        val reselected = MessageAlternativeVersionPolicy.select(sibling.copy(formatRepairNotice = kept), 1)
        assertEquals("fixed", MessageFormatRepairPolicy.redoRepair(reselected)?.displayContent)
    }

    @Test fun unchangedSuccessRejectsStaleVersionAndEditedText() {
        val repaired = MessageFormatRepairPolicy.replaceCurrentDisplayContent(message(), "after",
            MessageFormatRepairPolicy.completedRepairNotice(message().content, "after"))
        assertNull(MessageFormatRepairPolicy.retainedNoticeAfterUnchangedSuccess(repaired.copy(content = "edited")))
        assertNull(MessageFormatRepairPolicy.retainedNoticeAfterUnchangedSuccess(repaired.copy(
            formatRepairNotice = repaired.formatRepairNotice!!.copy(versionId = "another-version"))))
    }

    @Test fun unchangedSuccessClearsLegacyErrorWhileKeepingItsRecoverableText() {
        val repaired = MessageFormatRepairPolicy.replaceCurrentDisplayContent(message(), "after",
            MessageFormatRepairNotice(MessageFormatRepairNoticeKind.ERROR, "after", originalContent = message().content,
                errorMessage = "legacy partial repair error"))
        val kept = requireNotNull(MessageFormatRepairPolicy.retainedNoticeAfterUnchangedSuccess(repaired))
        assertEquals(MessageFormatRepairNoticeKind.APPLIED, kept.kind)
        assertNull(kept.errorMessage)
        assertEquals(message().content, MessageFormatRepairPolicy.restoreOriginal(repaired.copy(formatRepairNotice = kept))?.displayContent)
    }

    @Test fun failedRetryKeepsRestoredPairAndLocalRedoClearsAttemptError() {
        val repaired = MessageFormatRepairPolicy.replaceCurrentDisplayContent(message(), "cached repaired",
            MessageFormatRepairPolicy.completedRepairNotice(message().content, "cached repaired"))
        val restored = requireNotNull(MessageFormatRepairPolicy.restoreOriginal(repaired))
        val notice = MessageFormatRepairPolicy.failedAttemptNotice(restored, "retry failed")
        assertEquals(restored.formatRepairNotice!!.copy(errorMessage = "retry failed"), notice)
        val failed = restored.copy(formatRepairNotice = notice)
        val redone = requireNotNull(MessageFormatRepairPolicy.redoRepair(failed))
        assertEquals("cached repaired", redone.displayContent)
        assertNull(redone.formatRepairNotice?.errorMessage)
        assertEquals(restored.currentAlternativeVersionId, redone.currentAlternativeVersionId)
    }

    @Test fun failedRetryKeepsAppliedUndoPairAndRestoreClearsAttemptError() {
        val repaired = MessageFormatRepairPolicy.replaceCurrentDisplayContent(message(), "cached repaired",
            MessageFormatRepairPolicy.completedRepairNotice(message().content, "cached repaired"))
        val failed = repaired.copy(formatRepairNotice = MessageFormatRepairPolicy.failedAttemptNotice(repaired, "retry failed"))
        val restored = requireNotNull(MessageFormatRepairPolicy.restoreOriginal(failed))
        assertEquals(message().content, restored.displayContent)
        assertNull(restored.formatRepairNotice?.errorMessage)
        assertEquals("cached repaired", MessageFormatRepairPolicy.redoRepair(restored)?.displayContent)
    }

    @Test fun failedRetryNeverKeepsStaleVersionOrEditedRecoveryPair() {
        val repaired = MessageFormatRepairPolicy.replaceCurrentDisplayContent(message(), "cached repaired",
            MessageFormatRepairPolicy.completedRepairNotice(message().content, "cached repaired"))
        for (changed in listOf(repaired.copy(content = "manual edit"), repaired.copy(
            formatRepairNotice = repaired.formatRepairNotice!!.copy(versionId = "another-version")))) {
            val notice = MessageFormatRepairPolicy.failedAttemptNotice(changed, "retry failed")
            assertEquals(MessageFormatRepairNoticeKind.ERROR, notice.kind)
            assertNull(notice.originalContent)
            assertFalse(notice.restored)
            assertEquals(changed.displayContent, notice.targetContent)
            assertEquals(MessageAlternativeVersionPolicy.activeVersionId(changed), notice.versionId)
        }
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
