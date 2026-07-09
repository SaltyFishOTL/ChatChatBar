package com.example.chatbar.ui.components

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.chat.RoleplaySegmentKind
import com.example.chatbar.domain.chat.editRoleplayMessageSegment
import com.example.chatbar.domain.chat.parseRoleplayTextSegments
import com.example.chatbar.domain.chat.replaceRoleplaySegmentContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChatBubbleMarkdownTest {
    @Test
    fun roleplayMarkdown_stripsHiddenComments() {
        val result = sanitizeRoleplayMarkdown("before <!-- hidden --> after")

        assertEquals("before  after", result)
    }

    @Test
    fun roleplayMarkdown_stripsNestedHiddenComments() {
        val result = sanitizeRoleplayMarkdown("before <!-- outer <!-- inner --> tail --> after")

        assertEquals("before  after", result)
    }

    @Test
    fun roleplayMarkdown_stripsStandaloneHiddenCommentLines() {
        val result = sanitizeRoleplayMarkdown("before\n<!-- outer\n<!-- inner -->\ntail\n-->\nafter")

        assertEquals("before  \nafter", result)
    }

    @Test
    fun roleplayMarkdown_stripsMultipleHiddenComments() {
        val result = sanitizeRoleplayMarkdown("a <!-- one --> b <!-- two --> c")

        assertEquals("a  b  c", result)
    }

    @Test
    fun roleplayMarkdown_keepsUnclosedHiddenCommentText() {
        val result = sanitizeRoleplayMarkdown("before <!-- hidden")

        assertEquals("before <!-- hidden", result)
    }

    @Test
    fun roleplayContent_stripsHiddenCommentsBeforeSplittingSegments() {
        val marker = "\u0060\u0060\u0060"
        val result = parseRoleplayContent("before\n<!--\n$marker\nsecret\n$marker\n-->\nafter")

        assertEquals(1, result.size)
        assertEquals("before\nafter", (result[0] as RoleplayContentSegment.Markdown).text)
    }

    @Test
    fun roleplaySegments_splitNarrationDialogueThoughtAndRanges() {
        val content = "旁白 [对白](say) 『**心声**』 结尾"

        val result = parseRoleplayTextSegments(content)

        assertEquals(
            listOf(
                RoleplaySegmentKind.NARRATION,
                RoleplaySegmentKind.DIALOGUE,
                RoleplaySegmentKind.THOUGHT,
                RoleplaySegmentKind.NARRATION
            ),
            result.map { it.kind }
        )
        val dialogue = result[1]
        assertEquals("[对白](say)", content.substring(dialogue.start, dialogue.endExclusive))
        val thought = result[2]
        assertEquals("『**心声**』", content.substring(thought.start, thought.endExclusive))
    }

    @Test
    fun roleplaySegments_ignoreMarkdownImagesAsDialogue() {
        val content = "旁白 ![图](file.png) [对白]()"

        val result = parseRoleplayTextSegments(content)

        assertEquals(listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.DIALOGUE), result.map { it.kind })
        assertEquals("旁白 ![图](file.png) ", result[0].rawText)
    }

    @Test
    fun roleplaySegments_plainBracketHeaderDoesNotCaptureFollowingDialogue() {
        val content = "[正文]\n[\"对白\"]()后续"

        val result = parseRoleplayTextSegments(content)

        assertEquals(
            listOf(
                RoleplaySegmentKind.NARRATION,
                RoleplaySegmentKind.DIALOGUE,
                RoleplaySegmentKind.NARRATION
            ),
            result.map { it.kind }
        )
        assertEquals("[正文]\n", result[0].rawText)
        assertEquals("[\"对白\"]()", result[1].rawText)
    }

    @Test
    fun roleplaySegments_statusHasPrecedenceOverInnerMarkers() {
        val marker = "\u0060\u0060\u0060"
        val content = "前\n$marker\n[不拆]()\n『**也不拆**』\n$marker\n后"

        val result = parseRoleplayTextSegments(content)

        assertEquals(listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.STATUS, RoleplaySegmentKind.NARRATION), result.map { it.kind })
        assertEquals("[不拆]()\n『**也不拆**』", result[1].displayText)
    }

    @Test
    fun roleplaySegments_dashStatusHasPrecedenceOverInnerMarkers() {
        val content = "前\n---\n[不拆]()\n『**也不拆**』\n---\n后"

        val result = parseRoleplayTextSegments(content)

        assertEquals(listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.STATUS, RoleplaySegmentKind.NARRATION), result.map { it.kind })
        assertEquals("[不拆]()\n『**也不拆**』", result[1].displayText)
    }

    @Test
    fun roleplaySegments_onlyStrictThoughtMarkersBecomeThought() {
        val loose = parseRoleplayTextSegments("『*不是心理*』")
        val strict = parseRoleplayTextSegments("『**心理**』")

        assertEquals(listOf(RoleplaySegmentKind.NARRATION), loose.map { it.kind })
        assertEquals(listOf(RoleplaySegmentKind.THOUGHT), strict.map { it.kind })
    }

    @Test
    fun roleplaySegments_longDashWrappedTextStaysSingleNarration() {
        val dash = "\u2014".repeat(4)
        val content = "前 ${dash}[选项]()『**不拆**』${dash} 后"

        val result = parseRoleplayTextSegments(content)

        assertEquals(
            listOf(
                RoleplaySegmentKind.NARRATION,
                RoleplaySegmentKind.NARRATION,
                RoleplaySegmentKind.NARRATION
            ),
            result.map { it.kind }
        )
        assertEquals("${dash}[选项]()『**不拆**』${dash}", result[1].rawText)
    }

    @Test
    fun roleplaySegments_asciiDashWrappedTextUsesSixDashThreshold() {
        val dash = "-".repeat(6)
        val content = "前 ${dash}[选项]()${dash} 后"

        val result = parseRoleplayTextSegments(content)

        assertEquals(
            listOf(
                RoleplaySegmentKind.NARRATION,
                RoleplaySegmentKind.NARRATION,
                RoleplaySegmentKind.NARRATION
            ),
            result.map { it.kind }
        )
        assertEquals("${dash}[选项]()${dash}", result[1].rawText)
    }

    @Test
    fun roleplaySegments_multilineLongDashWrappedTextStaysSingleNarration() {
        val dash = "\u2014".repeat(8)
        val content = "前\n$dash\n[选项A]()\n『**不拆**』\n$dash\n后"

        val result = parseRoleplayTextSegments(content)

        assertEquals(
            listOf(
                RoleplaySegmentKind.NARRATION,
                RoleplaySegmentKind.NARRATION,
                RoleplaySegmentKind.NARRATION
            ),
            result.map { it.kind }
        )
        assertEquals("$dash\n[选项A]()\n『**不拆**』\n$dash", result[1].rawText)
    }

    @Test
    fun roleplaySegments_actualOptionBlockShapeDoesNotSplitInnerLinks() {
        val dash = "\u2014".repeat(22)
        val content = "[正文]\n[\"对白\"]()正文\n\n$dash\n**【行动选项】**\n\n1.**[选项一]()**\n\n2.**[选项二]()**\n$dash"

        val result = parseRoleplayTextSegments(content)

        assertEquals(
            listOf(
                RoleplaySegmentKind.NARRATION,
                RoleplaySegmentKind.DIALOGUE,
                RoleplaySegmentKind.NARRATION,
                RoleplaySegmentKind.NARRATION
            ),
            result.map { it.kind }
        )
        assertEquals("[正文]\n", result[0].rawText)
        assertEquals("[\"对白\"]()", result[1].rawText)
        assertEquals("$dash\n**【行动选项】**\n\n1.**[选项一]()**\n\n2.**[选项二]()**\n$dash", result[3].rawText)
    }

    @Test
    fun roleplaySegments_openingLongDashLineWithoutCloseTakesRestAsSingleNarration() {
        val dash = "\u2014".repeat(22)
        val content = "前文\n$dash\n**【行动选项】**\n1.**[选项一]()**\n『**不拆**』"

        val result = parseRoleplayTextSegments(content)

        assertEquals(
            listOf(
                RoleplaySegmentKind.NARRATION,
                RoleplaySegmentKind.NARRATION
            ),
            result.map { it.kind }
        )
        assertEquals("前文\n", result[0].rawText)
        assertEquals("$dash\n**【行动选项】**\n1.**[选项一]()**\n『**不拆**』", result[1].rawText)
    }

    @Test
    fun roleplaySegments_malformedMarkersKeepPredictedStyle() {
        val result = parseRoleplayTextSegments("旁白 [半截对白")

        assertEquals(listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.DIALOGUE), result.map { it.kind })
        assertEquals("[半截对白", result[1].rawText)
    }

    @Test
    fun roleplaySegmentReplacement_deletesAndCleansBlankLines() {
        val content = "前\n\n[删掉]()\n\n后"
        val segment = parseRoleplayTextSegments(content).first { it.kind == RoleplaySegmentKind.DIALOGUE }

        val result = replaceRoleplaySegmentContent(content, segment.start, segment.endExclusive, "")

        assertEquals("前\n\n后", result)
    }

    @Test
    fun roleplaySegmentReplacement_mergesAdjacentNarrationAfterDeletion() {
        val content = "前[删掉]()后"
        val segment = parseRoleplayTextSegments(content).first { it.kind == RoleplaySegmentKind.DIALOGUE }

        val result = replaceRoleplaySegmentContent(content, segment.start, segment.endExclusive, "")
        val reparsed = parseRoleplayTextSegments(result)

        assertEquals("前后", result)
        assertEquals(listOf(RoleplaySegmentKind.NARRATION), reparsed.map { it.kind })
    }

    @Test
    fun roleplaySegmentEdit_updatesSelectedAlternativeAndClearsAlternatives() {
        val message = message(
            content = "原文",
            alternatives = listOf("前 [旧]() 后", "别的"),
            currentAlternativeIndex = 0
        )
        val segment = parseRoleplayTextSegments(message.displayContent).first { it.kind == RoleplaySegmentKind.DIALOGUE }

        val outcome = editRoleplayMessageSegment(message, segment.start, segment.endExclusive, "[新]()", updatedAt = 9)
        val updated = requireNotNull(outcome.message)

        assertEquals("前 [新]() 后", updated.content)
        assertEquals(emptyList<String>(), updated.alternatives)
        assertEquals(0, updated.currentAlternativeIndex)
        assertEquals(9, updated.updatedAt)
        assertEquals(false, outcome.deleteMemoryForMessage)
    }

    @Test
    fun roleplaySegmentEdit_emptyTextWithImagesKeepsMessageAndRequestsMemoryDelete() {
        val message = message(content = "[删掉]()", images = listOf("image.png"))
        val segment = parseRoleplayTextSegments(message.displayContent).first()

        val outcome = editRoleplayMessageSegment(message, segment.start, segment.endExclusive, "", updatedAt = 9)

        assertNotNull(outcome.message)
        assertEquals("", outcome.message?.content)
        assertEquals(listOf("image.png"), outcome.message?.images)
        assertEquals(true, outcome.deleteMemoryForMessage)
    }

    @Test
    fun roleplaySegmentEdit_emptyTextWithoutImagesDeletesMessage() {
        val message = message(content = "[删掉]()")
        val segment = parseRoleplayTextSegments(message.displayContent).first()

        val outcome = editRoleplayMessageSegment(message, segment.start, segment.endExclusive, "")

        assertNull(outcome.message)
        assertEquals(false, outcome.deleteMemoryForMessage)
    }

    private fun message(
        content: String,
        images: List<String> = emptyList(),
        alternatives: List<String> = emptyList(),
        currentAlternativeIndex: Int = 0
    ): ChatMessage = ChatMessage(
        id = "message",
        sessionId = "session",
        role = MessageRole.ASSISTANT,
        content = content,
        images = images,
        alternatives = alternatives,
        currentAlternativeIndex = currentAlternativeIndex,
        createdAt = 1,
        updatedAt = 1
    )
}
