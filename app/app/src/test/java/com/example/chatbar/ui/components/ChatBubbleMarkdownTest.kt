package com.example.chatbar.ui.components

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.SpeakerTagRename
import com.example.chatbar.domain.chat.RoleplaySegmentKind
import com.example.chatbar.domain.chat.editRoleplayMessageSegment
import com.example.chatbar.domain.chat.parseRoleplayTextSegments
import com.example.chatbar.domain.chat.replaceRoleplaySegmentContent
import com.example.chatbar.domain.chat.renameRoleplaySpeakerMarkers
import com.example.chatbar.domain.chat.stripRoleplayStatusSegments
import com.example.chatbar.domain.chat.stripRoleplaySpeakerMarkers
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
    fun roleplaySegments_attachSpeakerPrefixToDialogueAndThought() {
        val content = "前<n=\"爱音\"/>\n[你好]() <n=\"灯\"/> 『**好紧张**』"

        val result = parseRoleplayTextSegments(content)

        assertEquals(
            listOf(
                RoleplaySegmentKind.NARRATION,
                RoleplaySegmentKind.DIALOGUE,
                RoleplaySegmentKind.THOUGHT
            ),
            result.map { it.kind }
        )
        assertEquals("爱音", result[1].speakerName)
        assertEquals("<n=\"爱音\"/>\n[你好]()", content.substring(result[1].start, result[1].endExclusive))
        assertEquals("[你好]()", result[1].displayText)
        assertEquals("灯", result[2].speakerName)
        assertEquals("『**好紧张**』", result[2].displayText)
    }

    @Test
    fun roleplaySegments_ignoreSpeakerPrefixInsideStatusAndHiddenComments() {
        val marker = "\u0060\u0060\u0060"
        val content = "$marker\n<n=\"状态角色\"/>[不拆]()\n$marker\n<!-- <n=\"隐藏角色\"/> -->[对白]()"

        val result = parseRoleplayTextSegments(content)

        assertEquals(RoleplaySegmentKind.STATUS, result[0].kind)
        assertEquals(null, result[0].speakerName)
        val dialogue = result.last { it.kind == RoleplaySegmentKind.DIALOGUE }
        assertEquals(null, dialogue.speakerName)
        assertEquals("[对白]()", content.substring(dialogue.start, dialogue.endExclusive))
    }

    @Test
    fun roleplaySegments_emptySpeakerPrefixStaysEditableButIsUnassigned() {
        val content = "<n=\"   \"/> [对白]()"

        val segment = parseRoleplayTextSegments(content).single()

        assertEquals("", segment.speakerName)
        assertEquals(content, content.substring(segment.start, segment.endExclusive))
    }

    @Test
    fun roleplaySpeakerMarkers_stripFromRenderedMarkdown() {
        assertEquals("[对白]() 『**内心**』", stripRoleplaySpeakerMarkers("<n=\"爱音\"/>[对白]() <n=\"爱音\"/>『**内心**』"))
    }

    @Test
    fun roleplayStatusSegments_stripCodeFenceAndDashWrappedOptions() {
        val marker = "\u0060\u0060\u0060"
        val content = "开头\n$marker\n状态栏\n$marker\n中段\n---\n选项 A\n---\n结尾"

        val result = stripRoleplayStatusSegments(content)

        assertEquals("开头\n\n中段\n\n结尾", result)
    }

    @Test
    fun roleplaySpeakerMarkers_renameOnlyMatchingTags() {
        val content = "<n=\" Alice \"/>[A]() <n=\"Bob\"/>[B]()"

        val renamed = renameRoleplaySpeakerMarkers(
            content,
            listOf(SpeakerTagRename("character", "alice", "Alicia"))
        )

        assertEquals("<n=\"Alicia\"/>[A]() <n=\"Bob\"/>[B]()", renamed)
    }

    @Test
    fun roleplaySpeakerResolution_matchesIgnoringCaseAndFallsBackExplicitly() {
        val avatars = listOf(
            ChatBubbleCharacterAvatar("Alice", "/avatar/alice.png"),
            ChatBubbleCharacterAvatar("Bob", "")
        )

        val matched = resolveRoleplaySpeaker(" alice ", avatars)
        val matchedWithoutAvatar = resolveRoleplaySpeaker("bob", avatars)
        val unknown = resolveRoleplaySpeaker("临时NPC", avatars)
        val missing = resolveRoleplaySpeaker(
            speakerName = "",
            characterAvatars = avatars
        )
        val legacy = resolveRoleplaySpeaker(
            speakerName = null,
            characterAvatars = avatars,
            legacyAvatarPath = "/avatar/card.png",
            legacyAvatarFallbackName = "Card"
        )

        assertEquals("Alice", matched.displayName)
        assertEquals("/avatar/alice.png", matched.avatarPath)
        assertEquals("Bob", matchedWithoutAvatar.displayName)
        assertEquals(null, matchedWithoutAvatar.avatarPath)
        assertEquals("临时NPC", unknown.displayName)
        assertEquals("临时NPC", unknown.avatarFallbackName)
        assertEquals("未标注", missing.displayName)
        assertEquals("?", missing.avatarFallbackName)
        assertNull(legacy.displayName)
        assertEquals("/avatar/card.png", legacy.avatarPath)
        assertEquals("Card", legacy.avatarFallbackName)
    }

    @Test
    fun roleplaySpeakerResolution_doesNotGuessBetweenDuplicateNames() {
        val avatars = listOf(
            ChatBubbleCharacterAvatar("爱音", "/one.png"),
            ChatBubbleCharacterAvatar(" 爱音 ", "/two.png")
        )

        val result = resolveRoleplaySpeaker("爱音", avatars)

        assertEquals("爱音", result.displayName)
        assertEquals(null, result.avatarPath)
    }

    @Test
    fun roleplaySpeakerHeaders_groupAdjacentDialogueAndThoughtByName() {
        val segments = parseRoleplayTextSegments(
            "<n=\"爱音\"/>[一]() <n=\"爱音\"/>『**二**』 <n=\"灯\"/>[三]()"
        )

        assertEquals(setOf(0, 2), roleplaySpeakerHeaderIndexes(segments))
    }

    @Test
    fun roleplaySpeakerHeaders_groupAdjacentLegacyUnmarkedSegments() {
        val segments = parseRoleplayTextSegments("[一]() 『**二**』 <n=\"\"/>[三]()")

        assertEquals(setOf(0, 2), roleplaySpeakerHeaderIndexes(segments))
    }

    @Test
    fun roleplaySpeakerHeaders_narrationBreaksSameNameGroup() {
        val segments = parseRoleplayTextSegments(
            "<n=\"爱音\"/>[一]()旁白<n=\"爱音\"/>[二]()"
        )

        assertEquals(setOf(0, 2), roleplaySpeakerHeaderIndexes(segments))
    }

    @Test
    fun roleplaySpeakerHeaders_filteredMiddleStartsWithHeaderAndKeepsOriginalBoundaries() {
        val segments = parseRoleplayTextSegments(
            "<n=\"爱音\"/>[一]() <n=\"爱音\"/>[二]()旁白<n=\"爱音\"/>[三]()"
        )

        assertEquals(setOf(1, 3), roleplaySpeakerHeaderIndexes(segments, setOf(1, 3)))
    }

    @Test
    fun roleplaySegments_ignoreMarkdownImagesAsDialogue() {
        val content = "旁白 ![图](file.png) [对白]()"

        val result = parseRoleplayTextSegments(content)

        assertEquals(listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.DIALOGUE), result.map { it.kind })
        assertEquals("旁白 ![图](file.png) ", result[0].rawText)
    }

    @Test
    fun roleplaySegments_plainBracketDialogueDoesNotNeedParentheses() {
        val content = "[正文]\n[\"对白\"]()后续"

        val result = parseRoleplayTextSegments(content)

        assertEquals(
            listOf(
                RoleplaySegmentKind.DIALOGUE,
                RoleplaySegmentKind.DIALOGUE,
                RoleplaySegmentKind.NARRATION
            ),
            result.map { it.kind }
        )
        assertEquals("[正文]", result[0].rawText)
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
    fun roleplaySegments_allThoughtQuoteMarkersBecomeThought() {
        val loose = parseRoleplayTextSegments("『*不是心理*』")
        val strict = parseRoleplayTextSegments("『**心理**』")

        assertEquals(listOf(RoleplaySegmentKind.THOUGHT), loose.map { it.kind })
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
                RoleplaySegmentKind.DIALOGUE,
                RoleplaySegmentKind.DIALOGUE,
                RoleplaySegmentKind.NARRATION,
                RoleplaySegmentKind.NARRATION
            ),
            result.map { it.kind }
        )
        assertEquals("[正文]", result[0].rawText)
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
    fun roleplaySegmentReplacement_deletesSpeakerPrefixWithSegment() {
        val content = "前\n<n=\"爱音\"/>\n[删掉]()\n后"
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
