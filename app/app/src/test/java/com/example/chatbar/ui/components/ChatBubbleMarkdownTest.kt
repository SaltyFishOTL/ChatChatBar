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
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBubbleMarkdownTest {
    @Test
    fun roleplayMarkdown_keepsLiteralCommentSyntaxInCodeAndHidesOnlyTheRealNote() {
        val literal = "`<!-- </历史回复> -->`"
        val body = "请键入 $literal 作为示例。"
        val withHiddenNote = "$body<!-- 旁注 -->"
        assertEquals(body, sanitizeRoleplayMarkdown(withHiddenNote))
        assertEquals(body, (parseRoleplayContent(withHiddenNote).single() as RoleplayContentSegment.Markdown).text)
    }

    @Test
    fun roleplayMarkdown_preservesTheBoundaryBetweenHiddenNoteAndIndentedLiteral() {
        val literal = "    <!-- literal -->"
        val body = "正文\n<!-- genuine -->\n$literal"
        val expected = "正文\n\n$literal"
        val segmented = parseRoleplayTextSegments(body).single().displayText
        val legacy = (parseRoleplayContent(body).single() as RoleplayContentSegment.Markdown).text
        assertEquals(expected, sanitizeRoleplayMarkdown(segmented, forColoring = true))
        assertEquals(expected, sanitizeRoleplayMarkdown(legacy, forColoring = true))
    }

    @Test
    fun roleplayMarkdown_doesNotTurnBracketsInALiteralCodeCommentIntoDialogue() {
        val markers = "[sample] 『sample』 <n=\"Code\"/>"
        val inline = "请键入 `<!-- $markers -->` 后继续。"
        val indented = "    <!--\n    $markers\n    -->"
        val fenced = "~~~xml\n<!--\n$markers\n-->\n~~~"
        for (body in listOf(inline, indented, fenced)) {
            val segment = parseRoleplayTextSegments(body).single()
            assertEquals(RoleplaySegmentKind.NARRATION, segment.kind)
            assertEquals(body, sanitizeRoleplayMarkdown(segment.displayText, forColoring = true))
            val legacyInput = stripRoleplaySpeakerMarkers(body)
            val legacy = (parseRoleplayContent(legacyInput).single() as RoleplayContentSegment.Markdown).text
            assertEquals(body, sanitizeRoleplayMarkdown(legacy, forColoring = true))
        }

        val mixed = parseRoleplayTextSegments("$inline [对白]")
        assertEquals(listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.DIALOGUE), mixed.map { it.kind })
        assertEquals("$inline ", mixed.first().displayText)
        assertNull(mixed.last().speakerName)
    }

    @Test
    fun roleplayMarkdown_keepsTripleBacktickInlineCodeAndFollowingProse() {
        for (literal in listOf("```<!-- </历史回复> -->```", "```</历史回复>```")) {
            val body = "她展示了 $literal，随后继续。\n第二行。"
            val segmented = parseRoleplayTextSegments(body).single()
            assertEquals(RoleplaySegmentKind.NARRATION, segmented.kind)
            assertEquals(body, segmented.displayText)
            val legacy = parseRoleplayContent(body).single()
            assertEquals(body, (legacy as RoleplayContentSegment.Markdown).text)
            assertTrue(sanitizeRoleplayMarkdown(segmented.displayText).contains(literal))
        }
    }

    @Test
    fun roleplayMarkdown_keepsBacktickFencesInsideTildeCodeLiteral() {
        val marker = "\u0060\u0060\u0060"
        val body = "开场\n~~~~text\n${marker}xml\n</历史回复>\n$marker\n~~~~\n落幕"
        val segmented = parseRoleplayTextSegments(body).single()
        assertEquals(RoleplaySegmentKind.NARRATION, segmented.kind)
        assertEquals(body, segmented.displayText)
        val legacy = parseRoleplayContent(body).single() as RoleplayContentSegment.Markdown
        assertEquals(body, legacy.text)
        assertEquals(body, stripRoleplayStatusSegments(body))

        val status = "$marker\n实际状态\n$marker"
        val withStatus = "$body\n$status"
        val segmentedWithStatus = parseRoleplayTextSegments(withStatus)
        assertEquals(listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.STATUS),
            segmentedWithStatus.map { it.kind })
        assertEquals("实际状态", segmentedWithStatus.last().displayText)
        val legacyWithStatus = parseRoleplayContent(withStatus)
        assertEquals("实际状态", (legacyWithStatus.last() as RoleplayContentSegment.Status).text)
        assertEquals(body, stripRoleplayStatusSegments(withStatus))
    }

    @Test
    fun roleplayMarkdown_keepsTypedBacktickCodeAndRecognizesRealStatus() {
        val marker = "```"
        val literal = "<n=\"Mina\">[sample] 『literal』"
        for (language in listOf("kotlin", "xml", "XML extra-info")) {
            val code = "$marker$language\n<!-- literal -->\n$literal  \n$marker"
            val segment = parseRoleplayTextSegments(code).single()
            assertEquals(RoleplaySegmentKind.NARRATION, segment.kind)
            assertEquals(code, segment.rawText)
            assertEquals(code, sanitizeRoleplayMarkdown(segment.displayText, forColoring = true))

            val legacyInput = stripRoleplaySpeakerMarkers(code)
            val legacy = parseRoleplayContent(legacyInput).single() as RoleplayContentSegment.Markdown
            assertEquals(code, sanitizeRoleplayMarkdown(legacy.text, forColoring = true))

            for (header in listOf("", "text", "status", "text（状态）")) {
                val withStatus = "$code\n$marker$header\n实际状态\n$marker"
                val segments = parseRoleplayTextSegments(withStatus)
                assertEquals(listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.STATUS),
                    segments.map { it.kind })
                assertEquals("实际状态", segments.last().displayText)
                val legacyStatus = parseRoleplayContent(stripRoleplaySpeakerMarkers(withStatus)).last()
                assertEquals("实际状态", (legacyStatus as RoleplayContentSegment.Status).text)
                assertEquals(code, stripRoleplayStatusSegments(withStatus))
            }
        }
    }

    @Test
    fun roleplayMarkdown_keepsNestedShortTextFencesBesideLegacyStatus() {
        val literal = "</历史回复><n=\"Mina\"/>[literal]  "
        val label = "（三个点为状态包裹格式，必须完整输出）"
        val status = "```text$label\n生命：10\n$label```"
        for (code in listOf(
            "> > ```text\n> > $literal\n> > ```",
            "- ```\n  $literal\n  ```",
            "> > ```Text example\n> > $literal\n> > ```",
            "- ```text$label\n  $literal\n  $label```",
        )) {
            val raw = "$code\n\n$status"
            val segments = parseRoleplayTextSegments(raw)
            assertEquals(listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.STATUS), segments.map { it.kind })
            assertEquals(code, sanitizeRoleplayMarkdown(segments.first().displayText, forColoring = true).trimEnd())

            val legacy = parseRoleplayContent(stripRoleplaySpeakerMarkers(raw))
            assertEquals(code,
                sanitizeRoleplayMarkdown((legacy.first() as RoleplayContentSegment.Markdown).text).trimEnd())
            assertEquals("生命：10\n$label", (legacy.last() as RoleplayContentSegment.Status).text)
        }
    }

    @Test
    fun roleplayMarkdown_keepsLongBareAndTextFencesAsLiteralCode() {
        for ((marker, label) in listOf("````" to "", "`````" to "text")) {
            val code = "$marker$label\n```\n<!-- literal -->\n<n=\"Mina\"/>[sample]  \n```\n$marker"
            val status = "```text\n真实状态\n```"
            val input = "$code\n\n$status"
            val segments = parseRoleplayTextSegments(input)
            assertEquals(listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.STATUS), segments.map { it.kind })
            assertEquals(code, sanitizeRoleplayMarkdown(segments.first().displayText, forColoring = true).trimEnd())
            val legacy = parseRoleplayContent(stripRoleplaySpeakerMarkers(input)).first() as RoleplayContentSegment.Markdown
            assertEquals(code, sanitizeRoleplayMarkdown(legacy.text, forColoring = true).trimEnd())
            assertEquals(code, stripRoleplayStatusSegments(input))
        }
    }

    @Test
    fun roleplayMarkdown_endsLegacyStatusBeforeAdjacentTypedCode() {
        val label = "（三个点为状态包裹格式，必须完整输出）"
        val status = "```text$label\n生命：10\n$label```"
        val code = "```kotlin\n<!-- literal -->\nval sample = \"[code]\"  \n```"
        val raw = "开场。\n\n$status\n\n$code\n\n落幕。"
        val segments = parseRoleplayTextSegments(raw)
        assertEquals(listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.STATUS, RoleplaySegmentKind.NARRATION),
            segments.map { it.kind })
        assertEquals(status, raw.substring(segments[1].start, segments[1].endExclusive))
        assertEquals("生命：10\n$label", segments[1].displayText)
        assertTrue(sanitizeRoleplayMarkdown(segments[2].displayText, forColoring = true).contains(code))

        val legacy = parseRoleplayContent(stripRoleplaySpeakerMarkers(raw))
        assertEquals(segments[1].displayText, (legacy[1] as RoleplayContentSegment.Status).text)
        assertTrue(sanitizeRoleplayMarkdown((legacy[2] as RoleplayContentSegment.Markdown).text).contains(code))
    }

    @Test
    fun roleplayMarkdown_keepsDashFenceExamplesInsideTildeCode() {
        val code = "~~~text\n---\n<历史回复 模式=\"聚焦\">示例</历史回复>\n---\n~~~"
        val segments = parseRoleplayTextSegments(code)
        assertEquals(listOf(RoleplaySegmentKind.NARRATION), segments.map { it.kind })
        assertEquals(code, segments.single().rawText)
        assertEquals(code, (parseRoleplayContent(code).single() as RoleplayContentSegment.Markdown).text)
        assertEquals(code, stripRoleplayStatusSegments(code))

        val realStatus = "\n---\n实际状态\n---"
        val withStatus = code + realStatus
        assertEquals(listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.STATUS),
            parseRoleplayTextSegments(withStatus).map { it.kind })
        assertEquals("实际状态", (parseRoleplayContent(withStatus).last() as RoleplayContentSegment.Status).text)
        assertEquals(code, stripRoleplayStatusSegments(withStatus))
    }

    @Test
    fun roleplayMarkdown_keepsLiteralMarkersWithoutCommentSyntax() {
        val markers = "[sample] 『sample』 <n=\"Code\"/>"
        for (code in listOf("`$markers`", "    $markers", "~~~text\n$markers\n~~~")) {
            val segments = parseRoleplayTextSegments(code)
            assertEquals(listOf(RoleplaySegmentKind.NARRATION), segments.map { it.kind })
            val segment = segments.single()
            assertEquals(code, segment.rawText)
            val legacy = parseRoleplayContent(stripRoleplaySpeakerMarkers(code)).single() as RoleplayContentSegment.Markdown
            assertEquals(code, legacy.text)
            assertEquals(code, sanitizeRoleplayMarkdown(segment.displayText, forColoring = true))
            assertEquals(code, sanitizeRoleplayMarkdown(legacy.text, forColoring = true))
        }

        val prefix = "示例 `$markers` "
        val withDialogue = "$prefix<n=\"Alice\"/>[真实对白]()"
        val segments = parseRoleplayTextSegments(withDialogue)
        assertEquals(listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.DIALOGUE), segments.map { it.kind })
        assertEquals(prefix, segments.first().rawText)
        assertEquals("Alice", segments.last().speakerName)
    }

    @Test
    fun roleplayMarkdown_keepsLongDashExamplesAndWhitespaceWhenStatusIsRemoved() {
        val code = "~~~text\n------\n[sample]\n------\n\n\n  indented  \n~~~"
        val marker = "\u0060\u0060\u0060"
        val withStatus = "$code\n${marker}text\n实际状态\n$marker"
        val segments = parseRoleplayTextSegments(withStatus)
        assertEquals(listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.STATUS), segments.map { it.kind })
        assertEquals("$code\n", segments.first().rawText)
        assertEquals(code, stripRoleplayStatusSegments(withStatus))
    }

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
    fun roleplayMarkdown_normalizesFullAndMixedWidthDialogueMarkers() {
        assertEquals(
            "[全角] [混合]",
            sanitizeRoleplayMarkdown("［全角］（） [混合]（低声)")
        )
        assertEquals(
            "\u200B全角\u200B [混合](低声)",
            sanitizeRoleplayMarkdown("［全角］（） [混合]（低声)", forColoring = true)
        )
    }

    @Test
    fun roleplayMarkdown_hidesEmptyDialogueMarkerBracketsWhenRendering() {
        assertEquals(
            "\u200B对白\u200B",
            sanitizeRoleplayMarkdown("[对白]()", forColoring = true)
        )
    }

    @Test
    fun roleplayMarkdown_rendersDialogueWithoutParentheses() {
        val content = "[半角] ［全角］ [混合］"

        assertEquals(
            "[半角] [全角] [混合]",
            sanitizeRoleplayMarkdown(content)
        )
        assertEquals(
            "\u200B半角\u200B \u200B全角\u200B \u200B混合\u200B",
            sanitizeRoleplayMarkdown(content, forColoring = true)
        )
    }

    @Test
    fun roleplayMarkdown_keepsFullWidthImageMarkerUntouched() {
        val image = "！［图片］（image.png） ![半角图片] ！［全角图片］"

        assertEquals(image, sanitizeRoleplayMarkdown(image))
        assertEquals(image, sanitizeRoleplayMarkdown(image, forColoring = true))
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
    fun roleplayStatusSegments_defaultExpansionFollowsFenceType() {
        val marker = "\u0060\u0060\u0060"
        val content = "$marker\n代码围栏\n$marker\n---\n横线围栏\n---"

        val statuses = parseRoleplayTextSegments(content)
            .filter { segment -> segment.kind == RoleplaySegmentKind.STATUS }

        assertEquals(2, statuses.size)
        assertEquals(false, statuses[0].statusDefaultExpanded)
        assertEquals(true, statuses[1].statusDefaultExpanded)
        val legacyStatuses = parseRoleplayContent(content)
            .filterIsInstance<RoleplayContentSegment.Status>()
        assertEquals(listOf(false, true), legacyStatuses.map { it.defaultExpanded })
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
    fun roleplaySegments_deletingTextBeforeOptionBlockKeepsInnerLinksAsNarration() {
        val dash = "\u2014".repeat(22)
        val content = "[要删除的正文]\n\n$dash\n**【行动选项】**\n\n1.**[选项一]()**\n\n2.**[选项二]()**\n$dash"
        val preceding = parseRoleplayTextSegments(content).first()

        val updated = replaceRoleplaySegmentContent(content, preceding.start, preceding.endExclusive, "")
        val reparsed = parseRoleplayTextSegments(updated)

        assertEquals(listOf(RoleplaySegmentKind.NARRATION), reparsed.map { it.kind })
        assertEquals("$dash\n**【行动选项】**\n\n1.**[选项一]()**\n\n2.**[选项二]()**\n$dash", reparsed.single().rawText)
    }

    @Test
    fun roleplaySegments_optionFenceAppendedToPreviousThoughtProtectsInnerLinks() {
        val dash = "\u2014".repeat(22)
        val content = "<n=\"高松灯\"/>『**和碰自己时完全不一样…』$dash\n" +
            "**【行动选项】**\n\n" +
            "1. **[选项一]()**\n\n" +
            "2. **[选项二]()**\n\n" +
            "$dash\n```\n状态栏\n```"

        val result = parseRoleplayTextSegments(content)
        val optionSegment = result.first { segment -> segment.rawText.startsWith(dash) }

        assertEquals(RoleplaySegmentKind.NARRATION, optionSegment.kind)
        assertTrue(optionSegment.rawText.contains("[选项一]()"))
        assertTrue(optionSegment.rawText.contains("[选项二]()"))
        assertEquals(0, result.count { segment ->
            segment.kind == RoleplaySegmentKind.DIALOGUE && segment.rawText.contains("选项")
        })
    }

    @Test
    fun roleplayStatusSegments_stripOptionFenceAppendedToPreviousThought() {
        val dash = "\u2014".repeat(22)
        val content = "保留『**心理文本…』$dash\n**【行动选项】**\n1. **[选项一]()**\n$dash\n```\n状态栏\n```"

        assertEquals("保留『**心理文本…』", stripRoleplayStatusSegments(content))
    }

    @Test
    fun roleplayStatusSegments_stripLongDashWrappedOptions() {
        val dash = "\u2014".repeat(22)
        val content = "保留正文\n\n$dash\n**【行动选项】**\n1.**[选项一]()**\n$dash"

        assertEquals("保留正文", stripRoleplayStatusSegments(content))
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
    fun roleplaySegmentEdit_updatesSelectedAlternativeAndPreservesOtherVersions() {
        val message = message(
            content = "原文",
            alternatives = listOf("前 [旧]() 后", "别的"),
            alternativeVersionIds = listOf("v1", "v2"),
            currentAlternativeIndex = 0,
            currentAlternativeVersionId = "v1"
        )
        val segment = parseRoleplayTextSegments(message.displayContent).first { it.kind == RoleplaySegmentKind.DIALOGUE }

        val outcome = editRoleplayMessageSegment(message, segment.start, segment.endExclusive, "[新]()", updatedAt = 9)
        val updated = requireNotNull(outcome.message)

        assertEquals("前 [新]() 后", updated.content)
        assertEquals(listOf("前 [新]() 后", "别的"), updated.alternatives)
        assertEquals(listOf("v1", "v2"), updated.alternativeVersionIds)
        assertEquals(0, updated.currentAlternativeIndex)
        assertEquals("v1", updated.currentAlternativeVersionId)
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
        alternativeVersionIds: List<String> = emptyList(),
        currentAlternativeIndex: Int = 0,
        currentAlternativeVersionId: String? = null
    ): ChatMessage = ChatMessage(
        id = "message",
        sessionId = "session",
        role = MessageRole.ASSISTANT,
        content = content,
        images = images,
        alternatives = alternatives,
        alternativeVersionIds = alternativeVersionIds,
        currentAlternativeIndex = currentAlternativeIndex,
        currentAlternativeVersionId = currentAlternativeVersionId,
        createdAt = 1,
        updatedAt = 1
    )
}
