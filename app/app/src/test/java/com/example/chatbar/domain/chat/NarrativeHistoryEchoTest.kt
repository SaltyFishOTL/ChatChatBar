package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatNarrativeMode
import com.example.chatbar.domain.prompt.PromptTemplates
import org.junit.Assert.assertEquals
import org.junit.Test

class NarrativeHistoryEchoTest {

    @Test
    fun appendOnlyNarrationPreviewMatchesAuthoritativeCleanupThroughSyntaxTransitions() {
        val opening = "<历史回复 模式=\"聚焦\">"
        val closing = "</历史回复>"
        val cleanup = NarrativeHistoryEcho.forRequest(ChatNarrativeMode.FOCUS, null).apply {
            recordHistoryProjection("Earlier story.", "$opening\nEarlier story.\n$closing")
            recordNarrativePrompt(PromptTemplates.narrativeModeSystemPrompt(ChatNarrativeMode.FOCUS), null)
        }
        for (raw in listOf(
            "The lamp shines brightly.",
            "灯光很亮，晚风吹来。",
            "$opening\nThe lamp shines brightly.",
            "$opening\nThe lamp shines brightly.\n$closing",
            "$opening\nThe lamp shines brightly.\n```xml\n$closing\n```\n$closing",
            "$opening\nThe lamp shines brightly.\n“Literal $closing”",
        )) {
            val preview = cleanup.streamingPreview()
            for (length in 1..raw.length) {
                val prefix = raw.substring(0, length)
                assertEquals(prefix, cleanup.completedBody(prefix, streaming = true), preview.appendedBody(prefix, length - 1))
            }
        }
        val toolPart = "$opening\nThe lamp shines brightly."
        val fallback = "Ordinary fallback text."
        val preview = cleanup.streamingPreview()
        assertEquals(fallback, preview.appendedBody(fallback, previousLength = 0))
        assertEquals("The lamp shines brightly.", preview.appendedBody(toolPart, previousLength = 0))
        val joined = "$toolPart\n$closing"
        val lengths = listOf(toolPart.length, closing.length)
        assertEquals(cleanup.completedBody(joined, partLengths = lengths, streaming = true),
            preview.appendedBody(joined, toolPart.length, lengths))
    }

    @Test
    fun aRequestedCaptionedClosingTagStaysLiteralInsideARealOuterWrapper() {
        val opening = "<历史回复 模式=\"聚焦\">"
        val closing = "</历史回复>"
        val request = "Please show the literal closing tag only on its own line:\n$closing"
        val caption = "The closing tag example:"
        val requestedBody = "$caption\n$closing"
        val framed = "$opening\n$requestedBody"
        fun cleanup(currentUser: String) = NarrativeHistoryEcho.forRequest(ChatNarrativeMode.FOCUS, null).apply {
            recordHistoryProjection("Earlier story.", "$opening\nEarlier story.\n$closing")
            recordNarrativePrompt("", request)
            recordCurrentLiteralTags(currentUser)
        }

        val authored = cleanup(request)
        assertEquals(requestedBody, authored.completedBody(framed, streaming = true))
        assertEquals(requestedBody, authored.completedBody(framed, interrupted = true))
        val partial = closing.dropLast(2)
        assertEquals("$caption\n$partial", authored.completedBody("$opening\n$caption\n$partial", interrupted = true))
        val complete = "$framed\n$closing"
        assertEquals(requestedBody, authored.completedBody(complete))
        assertEquals(requestedBody, authored.completedBody(complete,
            partLengths = listOf(opening.length, requestedBody.length, closing.length)))
        assertEquals("The lamp is lit.", authored.completedBody("$opening\nThe lamp is lit.\n$closing"))

        for (source in listOf("Continue the scene.", "```xml\n$request\n```", "Do not output these history tags:\n$request")) {
            assertEquals(caption, cleanup(source).completedBody(framed, interrupted = true))
        }
    }

    @Test
    fun balancedBackticksInsideIsolatedCommentsDoNotChangeTheSurroundingLiterals() {
        val opening = "<历史回复 模式=\"聚焦\">"
        val closing = "</历史回复>"
        val notes = List(96) { "<!-- `sample $it with $closing` -->" }
        val code = "```text\n<!-- `this comment is inside a literal fence` -->\n```"
        val body = (listOf("The lamp shines.") + notes + code + "She puts the letter away.").joinToString("\n\n")
        val raw = "$opening\n$body\n$closing"
        assertEquals(notes, NarrativeHistoryEcho.hiddenHtmlCommentRanges(raw).map(raw::substring))
        assertEquals(body, NarrativeHistoryEcho.replyBody(raw))
    }

    @Test
    fun classifiesManyCommentsAndRescansWhenAHiddenFenceCouldChangeCodeBoundaries() {
        val notes = List(64) { "<!-- note $it contains </历史回复> -->" }
        val plain = notes.joinToString("\n", prefix = "Story starts.\n", postfix = "\nStory ends.")
        fun hidden(text: String) = NarrativeHistoryEcho.hiddenHtmlCommentRanges(text).map(text::substring)
        assertEquals(notes, hidden(plain))

        val hiddenFence = "<!-- hidden fence\n```\n-->"
        val literalCode = "```text\n<!-- this is code -->\n```"
        val visible = "Story starts.\n$hiddenFence\n$plain\n$literalCode\nStory ends."
        assertEquals(listOf(hiddenFence) + notes, hidden(visible))
    }

    @Test
    fun keepsIsolatedMultilineCommentsAndReclassifiesMarkupThatCanChangeMarkdown() {
        val opening = "<历史回复 模式=\"聚焦\">"
        val closing = "</历史回复>"
        val notes = List(96) { "<!-- note $it\nsecond line with $closing -->" }
        val hiddenFence = "<!-- note\n~~~\n-->"
        val literalCode = "```text\n<!-- literal code -->\n```"
        val body = (listOf("Her `notebook` lay open.") + notes + hiddenFence +
            listOf(literalCode, "She reads ‘the notes’ carefully.")).joinToString("\n\n")
        val raw = "$opening\n$body\n$closing"
        fun hidden(text: String) = NarrativeHistoryEcho.hiddenHtmlCommentRanges(text).map(text::substring)
        assertEquals(notes + hiddenFence, hidden(raw))
        for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
            assertEquals(body, NarrativeHistoryEcho.replyBody(raw, streaming = streaming, interrupted = interrupted))
        }
    }

    @Test
    fun keepsAStandaloneLiteralOpeningOnlyWhenTheCurrentUserExplicitlyRequestsIt() {
        val opening = "<历史回复 模式=\"聚焦\">"
        val advanceOpening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        val request = "Please show the literal opening tag only on its own line, then explain it:\n$opening"
        val ordinaryExample = "This tag is used in history."
        val literalReply = "$opening\n$ordinaryExample"
        val transport = "$opening\n$ordinaryExample\n$closing"
        fun cleanup() = NarrativeHistoryEcho.forRequest(ChatNarrativeMode.FOCUS, null).apply {
            recordHistoryProjection("Earlier story.", "$opening\nEarlier story.\n$closing")
            recordHistoryProjection("Earlier summary.", "$advanceOpening\nEarlier summary.\n$closing")
            recordNarrativePrompt("", request)
        }
        for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
            fun NarrativeHistoryEcho.RequestCleanup.clean(text: String, lengths: List<Int> = emptyList()) =
                completedBody(text, interrupted, lengths, streaming)
            val authored = cleanup().apply { recordCurrentLiteralTags(request) }
            assertEquals(literalReply, authored.clean(literalReply))
            assertEquals(ordinaryExample, authored.clean(transport))
            assertEquals(
                ordinaryExample,
                authored.clean(transport, listOf(opening.length, ordinaryExample.length, closing.length)),
            )
            assertEquals(ordinaryExample, authored.clean("$advanceOpening\n$ordinaryExample"))
            assertEquals(ordinaryExample, cleanup().clean(literalReply))
            val unrelated = listOf(
                "This is an example of an opening tag:\n$opening",
                "```xml\n$request\n```",
                "Do not output these history tags:\n$request",
            )
            for (source in unrelated) {
                val incidental = cleanup().apply { recordCurrentLiteralTags(source) }
                assertEquals(ordinaryExample, incidental.clean(literalReply))
            }
        }
    }

    @Test
    fun carriesGenuineCommentsOutsideTheOuterWrapperInEveryReplyState() {
        val wrappers = listOf(
            "<历史回复 模式=\"聚焦\">" to "</历史回复>",
            "<历史回复 模式=\"推进\">" to "</历史回复>",
            "<聚焦模式=\"沉浸\">" to "</聚焦模式>",
            "<聚焦模式=\"沉浸\">" to "</聚焦模式=\"沉浸\">",
        )
        val note = "<!-- 旁注 -->"
        val prose = "她推门。"
        for ((open, close) in wrappers) {
            val examples = listOf(
                "$note\n$open\n$prose\n$close" to "$note\n$prose",
                "$open\n$prose\n$close\n$note" to "$prose\n$note",
                "$note\n$open\n$prose\n$close$note" to "$note\n$prose\n$note",
                "$open\n$prose$close $note" to "$prose $note",
            )
            for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
                for ((raw, expected) in examples) {
                    assertEquals(expected, NarrativeHistoryEcho.replyBody(raw, streaming, interrupted = interrupted))
                    val literalExample = "$raw\n以上只是示例。"
                    assertEquals(literalExample, NarrativeHistoryEcho.replyBody(literalExample, streaming, interrupted = interrupted))
                }
            }
        }
    }

    @Test
    fun pairedPlainActionOptionsStayOutsideTheEchoInPreviewStopAndDone() {
        val story = "门开了。"
        val tails = listOf(
            "---\n1. 进门\n2. 等候\n---",
            "---\n**【行动选项】**\n\n1.**进门**\n\n2.**等候**\n\n3.**敲门**\n---",
        )
        for (mode in ChatNarrativeMode.entries) {
            val cleanup = requestWithNarrativeHistory(mode, null)
            val opening = "<历史回复 模式=\"${mode.shortLabel}\">"
            val closing = "</历史回复>"
            for (newLine in listOf("\n", "\r\n")) for (tail in tails) {
                val options = tail.replace("\n", newLine)
                val wrapped = "$opening$newLine$story$newLine$closing"
                val parts = listOf(wrapped, options)
                for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
                    assertEquals("$story$newLine$newLine$options", cleanup.completedBody(
                        "$wrapped$newLine$newLine$options", streaming = streaming, interrupted = interrupted,
                    ))
                    assertEquals("$story\n$options", cleanup.completedBody(
                        parts.joinToString("\n"), streaming = streaming, interrupted = interrupted,
                        partLengths = parts.map(String::length),
                    ))
                }
            }
        }
    }

    @Test
    fun pendingOptionClosersKeepTheEchoOutOfPreviewAndStopAcrossModes() {
        val story = "门开了。"
        val dashes = "—".repeat(22)
        val label = "（横线为选项包裹格式，必须完整输出）"
        val annotatedHeading = "$dashes$label\n**【行动选项】**"
        val layouts = listOf(
            "---\n1. 进门\n2. 等候" to "---",
            "$annotatedHeading\n\n1.**进门** <!-- 旁注 -->\n\n2.**等候**" to "$label$dashes",
        )
        for (echoMode in ChatNarrativeMode.entries) {
            val requestMode = ChatNarrativeMode.entries.first { it != echoMode }
            val cleanup = requestWithNarrativeHistory(requestMode, null)
            val wrapped = PromptTemplates.narrativeModeHistoryReply(story, echoMode)
            for ((body, closer) in layouts) {
                val drafts = listOf("$body  ", "$body\n${closer.take(1)}", "$body\n${closer.dropLast(1)}", "$body\n3.")
                for (draft in drafts) for (lineBreak in listOf("\n", "\r\n")) {
                    val suffix = "\n\n$draft".replace("\n", lineBreak)
                    val reply = wrapped.replace("\n", lineBreak) + suffix
                    assertEquals(story + suffix, cleanup.completedBody(reply, streaming = true))
                    assertEquals(story + suffix, cleanup.completedBody(reply, interrupted = true))
                    assertEquals(reply, cleanup.completedBody(reply))
                }
                val complete = "\n\n$body\n$closer"
                assertEquals(story + complete, cleanup.completedBody(wrapped + complete))
            }
            val partialFirstChoice = "\n\n$annotatedHeading\n\n1."
            assertEquals(story + partialFirstChoice, cleanup.completedBody(wrapped + partialFirstChoice, streaming = true))
        }
    }

    @Test
    fun activeAnnotatedActionCardKeepsEveryReceivedOpeningPrefix() {
        val story = "门开了。"
        val dashes = "—".repeat(22)
        val label = "（横线为选项包裹格式，必须完整输出）"
        val heading = "**【行动选项】**"
        val opening = "$dashes$label"
        val template = "$opening\n$heading\n\n1.**进门**\n\n2.**等候**\n\n$label$dashes"
        val illustrativeCards = listOf(
            null,
            "正文之后允许补充叙事。",
            "以下是禁止输出的反例。\n$template",
            "她引述：“\n$template\n”",
            "```markdown\n$template\n```",
        )
        for (mode in ChatNarrativeMode.entries) for (lineBreak in listOf("\n", "\r\n")) {
            val wrapped = PromptTemplates.narrativeModeHistoryReply(story, mode).replace("\n", lineBreak)
            val active = requestWithNarrativeHistory(mode, template.replace("\n", lineBreak))
            val prefixes = (1..opening.length).map(opening::take) +
                (0..heading.length).map { "$opening\n\n${heading.take(it)}" }
            for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
                for (prefix in prefixes) {
                    val suffix = "\n\n$prefix".replace("\n", lineBreak)
                    assertEquals(prefix, story + suffix, active.completedBody(
                        wrapped + suffix, streaming = streaming, interrupted = interrupted,
                    ))
                }
                val complete = "\n\n$template".replace("\n", lineBreak)
                assertEquals(story + complete, active.completedBody(
                    wrapped + complete, streaming = streaming, interrupted = interrupted,
                ))
                for (literal in listOf("————另一位叙述者", "————\n这行仍然是故事。", "$opening\n另一段叙事。")) {
                    val reply = wrapped + "\n\n$literal".replace("\n", lineBreak)
                    assertEquals(reply, active.completedBody(reply, streaming = streaming, interrupted = interrupted))
                }
                val incomplete = wrapped + lineBreak + lineBreak + "————"
                for (card in illustrativeCards) {
                    val unproven = requestWithNarrativeHistory(mode, card)
                    assertEquals(card, incomplete, unproven.completedBody(
                        incomplete, streaming = streaming, interrupted = interrupted,
                    ))
                }
            }
        }
    }

    @Test
    fun explicitMandatoryFencedTemplatesKeepTheAuthoredPair() {
        val mode = ChatNarrativeMode.FOCUS
        val story = "门开了。"
        val wrapped = PromptTemplates.narrativeModeHistoryReply(story, mode)
        val sample = "```xml\n$wrapped\n```"
        val label = "请严格按照以下固定模板作答，务必逐字保留首尾标签，不要输出代码围栏："
        for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
            val declared = requestWithNarrativeHistory(mode, "$label\n$sample")
            assertEquals(wrapped, declared.completedBody(wrapped, streaming = streaming, interrupted = interrupted))
            for (card in listOf(
                "“$label”\n$sample",
                "> $label\n\n$sample",
                "<!-- $label -->\n$sample",
                "以下是禁止输出的反例。\n$label\n$sample",
                "$label\n$sample\n请勿在本轮输出以下任何历史标签。",
            )) {
                val illustrative = requestWithNarrativeHistory(mode, card)
                assertEquals(card, story, illustrative.completedBody(
                    wrapped, streaming = streaming, interrupted = interrupted,
                ))
            }
        }
    }

    @Test
    fun visibleProhibitionKeepsAnUnfencedExampleFromDeclaringOutput() {
        val mode = ChatNarrativeMode.ADVANCE
        val story = "列车抵达了。"
        val wrapped = PromptTemplates.narrativeModeHistoryReply(story, mode)
        val sample = PromptTemplates.narrativeModeHistoryReply("示例正文", mode)
        val prohibition = "请勿在本轮输出以下任何历史标签"
        for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
            for (card in listOf("$prohibition：\n$sample", "$sample\n$prohibition。")) {
                val illustrative = requestWithNarrativeHistory(mode, card)
                assertEquals(card, story, illustrative.completedBody(
                    wrapped, streaming = streaming, interrupted = interrupted,
                ))
            }
            for (card in listOf("“$prohibition：”\n$sample", "<!-- $prohibition： -->\n$sample")) {
                val declared = requestWithNarrativeHistory(mode, card)
                assertEquals(card, wrapped, declared.completedBody(
                    wrapped, streaming = streaming, interrupted = interrupted,
                ))
            }
        }
    }

    @Test
    fun leadingEnglishContractionsKeepTheOuterCloseVisibleAndRealQuotesLiteral() {
        val close = "</历史回复>"
        for (mode in ChatNarrativeMode.entries) {
            val cleanup = requestWithNarrativeHistory(mode, null)
            val stories = listOf(
                "'Twas a cold night.",
                "‘Tis near dawn.",
                "’Twere better so.",
                "'Twas cold. She said 'copy the literal\n$close\nfrom the card.'",
                "‘Twas a quotation containing\n$close\nthis tag.’\nShe waited.",
                "'The quoted tag was\n$close\nprinted on the sign.'\nShe waited.",
            )
            for (story in stories) {
                val wrapped = PromptTemplates.narrativeModeHistoryReply(story, mode)
                for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
                    assertEquals(story, cleanup.completedBody(wrapped, streaming = streaming, interrupted = interrupted))
                }
            }
        }
    }

    @Test
    fun literalDashBlocksAndAnOwnedTemplateKeepTheirWrapper() {
        val mode = ChatNarrativeMode.FOCUS
        val opening = "<历史回复 模式=\"聚焦\">"
        val closing = "</历史回复>"
        val pair = "$opening\n标签示例\n$closing"
        val options = "---\n1. 进门\n2. 等候\n---"
        val literals = listOf(
            "---\n这些是示例后的说明。\n---",
            "---\n1.2 meters\n2.5 seconds\n---",
            "---",
            "---\n1. 进门",
            "---\n1. 进门\n2. 等候\n--\n",
            "---\n1. 进门\n随后解释示例。\n2. 等候\n---",
            options.lineSequence().joinToString("\n") { "> $it" },
            options.lineSequence().joinToString("\n") { "    $it" },
            "- 示例\n" + options.lineSequence().joinToString("\n") { "  $it" },
            "```markdown\n$options\n```",
            "她引用：“\n$options\n”",
        )
        val cleanup = requestWithNarrativeHistory(mode, null)
        val declared = requestWithNarrativeHistory(mode, "本轮输出模板：$opening{正文}$closing")
        for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
            for (literal in literals) {
                val raw = "$pair\n\n$literal"
                assertEquals(raw, cleanup.completedBody(raw, streaming = streaming, interrupted = interrupted))
            }
            for (ownedOptions in listOf(options, options.removeSuffix("---"))) {
                val owned = "$pair\n\n$ownedOptions"
                assertEquals(owned, declared.completedBody(owned, streaming = streaming, interrupted = interrupted))
            }
        }
    }

    @Test
    fun aSplitTrailingCommentOpenerDoesNotRestoreTheWrapperInPreviewOrStop() {
        val open = "<历史回复 模式=\"聚焦\">"
        val close = "</历史回复>"
        val prose = "她推门。"
        for (tail in listOf("<", "<!", "<!-", "<!--")) {
            val raw = "$open\n$prose\n$close\n$tail"
            assertEquals("$prose\n$tail", NarrativeHistoryEcho.replyBody(raw, streaming = true))
            assertEquals("$prose\n$tail", NarrativeHistoryEcho.replyBody(raw, interrupted = true))
        }
        val visibleTail = "$open\n$prose\n$close\n<!标签示例"
        assertEquals(visibleTail, NarrativeHistoryEcho.replyBody(visibleTail, streaming = true))
        assertEquals("$open\n$prose\n$close\n<!", NarrativeHistoryEcho.replyBody("$open\n$prose\n$close\n<!"))
    }

    @Test
    fun closedCommentInsideProseQuotesCannotEndTheQuoteOrTheWrapper() {
        val body = "“他说 <!-- 注释内有右引号 ” 和 </历史回复> --> 继续引用。”"
        val raw = "<历史回复 模式=\"聚焦\">\n$body\n</历史回复>"
        assertEquals(body, NarrativeHistoryEcho.replyBody(raw, streaming = true))
        assertEquals(body, NarrativeHistoryEcho.replyBody(raw, interrupted = true))
        assertEquals(body, NarrativeHistoryEcho.replyBody(raw))
    }

    @Test
    fun genuineBlockquoteCommentsDoNotStartProseQuotes() {
        val open = "<历史回复 模式=\"聚焦\">"
        val close = "</历史回复>"
        val body = "> <!-- 旁注 \" -->\n\n她推门。"
        val raw = "$open\n$body\n$close"
        for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
            assertEquals(body, NarrativeHistoryEcho.replyBody(raw, streaming, interrupted = interrupted))
        }
    }

    @Test
    fun pairedHistoryWrappersIgnoreClosedHtmlCommentsInEveryReplyState() {
        val closing = "</历史回复>"
        for (mode in ChatNarrativeMode.entries) {
            val opening = "<历史回复 模式=\"${mode.shortLabel}\">"
            val bodies = listOf(
                "他打开门。\n<!-- $closing -->\n他走了进去。",
                "他打开门。 <!-- \" $opening $closing -->\n他走了进去。",
                "他打开门。\r<!--\r```text\r$closing\r-->\r他走了进去。",
                "他打开门。\n<!-- <!-- $closing --> $closing -->\n他走了进去。",
                "他打开门。\n<!--\n```\n-->\n<!-- $closing -->\n他走了进去。",
                "他打开门。\n<!--\n```\n-->\n```\n<!-- $closing\n```\n他走了进去。",
            )
            for (body in bodies) {
                val wrapped = "$opening\n$body\n$closing"
                for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
                    assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped, streaming = streaming, interrupted = interrupted))
                }
                assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped.dropLast(1), streaming = true))
                assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped.dropLast(1), interrupted = true))
            }
        }
    }

    @Test
    fun lineLeadingHtmlCommentsKeepAnAdjacentOuterCloseVisibleToTheParser() {
        val wrappers = listOf(
            "<历史回复 模式=\"聚焦\">" to "</历史回复>",
            "<历史回复 模式=\"推进\">" to "</历史回复>",
            "<聚焦模式=\"沉浸\">" to "</聚焦模式>",
        )
        for ((opening, closing) in wrappers) {
            val bodies = listOf(
                "<!-- note -->正文",
                "<!-- $closing -->正文",
                "<!-- note --><!-- $closing -->正文",
                "<!--\n    $closing -->正文",
                "<!--\n> $closing -->正文",
            )
            for (body in bodies) {
                val wrapped = "$opening\n$body$closing"
                for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
                    assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped, streaming = streaming, interrupted = interrupted))
                }
                assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped.dropLast(1), streaming = true))
                assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped.dropLast(1), interrupted = true))
            }
        }
    }

    @Test
    fun leadingHtmlCommentDoesNotHideTheStartOfAMultilineCodeSpan() {
        val opening = "<聚焦模式=\"沉浸\">"
        val closing = "</聚焦模式>"
        val body = "<!-- note -->`标签示例\n$closing\n结束`\n正文"
        val wrapped = "$opening\n$body\n$closing"
        assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped, streaming = true))
        assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped, interrupted = true))
        assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped))
    }

    @Test
    fun aStandaloneCloseAfterACommentDoesNotBecomeAQuoteContinuation() {
        val opening = "<聚焦模式=\"沉浸\">"
        val closing = "</聚焦模式>"
        for (note in listOf("<!-- note -->", "<!-- $closing -->", "<!-- note --><!-- $closing -->")) {
            val body = "> 引文\n$note"
            val wrapped = "$opening\n$body$closing"
            assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped))
            assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped, streaming = true))
            assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped, interrupted = true))
            assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped.dropLast(1), streaming = true))
            assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped.dropLast(1), interrupted = true))
        }
    }

    @Test
    fun aRealObjectReplacementCharacterContinuesTheQuoteAfterAComment() {
        val opening = "<聚焦模式=\"沉浸\">"
        val closing = "</聚焦模式>"
        val body = "> 引文\n<!-- note -->\uFFFC$closing"
        val wrapped = "$opening\n$body\n$closing"
        assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped))
        assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped, streaming = true))
        assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped, interrupted = true))
    }

    @Test
    fun commentOnlyLinesDoNotTurnFollowingIndentedCodeIntoProse() {
        val opening = "<历史回复 模式=\"聚焦\">"
        val closing = "</历史回复>"
        for (note in listOf("<!-- note -->", "<!-- one --><!-- two -->", "<!--\n    note -->")) {
            val body = "$note\n    val marker = \""
            val wrapped = "$opening\n$body\n$closing"
            assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped, streaming = true))
            assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped, interrupted = true))
            assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped))
        }
    }

    @Test
    fun provisionalCommentKeepsLiteralClosingWhileItsOwnEndIsIncomplete() {
        val opening = "<历史回复 模式=\"聚焦\">"
        val closing = "</历史回复>"
        for (tail in listOf(closing, closing.take(5), "$closing --")) {
            val body = "正文。\n<!-- $tail"
            for ((streaming, interrupted) in listOf(true to false, false to true)) {
                assertEquals(body, NarrativeHistoryEcho.replyBody(
                    "$opening\n$body", streaming = streaming, interrupted = interrupted,
                ))
            }
        }
    }

    @Test
    fun quotedEscapedAndCodeCommentOpenersDoNotHideTheOuterClose() {
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        val literalOpeners = listOf(
            "她写下「<!--」后离开。", "正文 `<!--`。", "\\<!-- 是示例。",
            "> <!--", "```text\n<!--\n```", "正文。\n\n    <!--",
        )
        for (body in literalOpeners) {
            val wrapped = "$opening\n$body\n$closing"
            for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
                assertEquals(body, NarrativeHistoryEcho.replyBody(wrapped, streaming = streaming, interrupted = interrupted))
            }
        }
        val example = "$opening\n<!-- $closing -->\n正文。\n$closing\n这个示例仍需保留。"
        assertEquals(example, NarrativeHistoryEcho.replyBody(example))
        assertEquals("普通 <!-- $closing --> 正文。", NarrativeHistoryEcho.replyBody("普通 <!-- $closing --> 正文。"))
    }

    @Test
    fun indentedCodeQuotesCannotHideMultipartOuterClosings() {
        val echoes = listOf(
            "<聚焦模式=\"沉浸\">" to "</聚焦模式>",
            "<历史回复 模式=\"推进\">" to "</历史回复>",
        )
        for (code in listOf("    val mark = \"", "\tval mark = \"", "- 说明\r\n  > 示例\r\n  >\r\n  >     val mark = \"")) {
            for (quoteLiteralClosing in listOf(false, true)) {
                val bodies = echoes.map { (_, closing) ->
                    "正文。\n\n$code" + if (quoteLiteralClosing) "\n\n她说 \"示例 $closing 仍是正文\"。" else ""
                }
                val parts = echoes.zip(bodies).map { (echo, body) -> "${echo.first}\n$body\n${echo.second}" }
                val expected = bodies.joinToString("\n")
                for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
                    assertEquals(expected, NarrativeHistoryEcho.completedBody(
                        parts.joinToString("\n"), enabled = true, interrupted = interrupted,
                        partLengths = parts.map(String::length), streaming = streaming,
                    ))
                }
                val partial = parts.dropLast(1) + parts.last().dropLast(1)
                assertEquals(expected, NarrativeHistoryEcho.completedBody(
                    partial.joinToString("\n"), enabled = true, interrupted = true,
                    partLengths = partial.map(String::length),
                ))
            }
        }
    }

    @Test
    fun fourSpaceParagraphContinuationsStillOpenProseQuotes() {
        for (mode in ChatNarrativeMode.entries) {
            val opening = "<历史回复 模式=\"${mode.shortLabel}\">"
            val closing = "</历史回复>"
            val body = "正文。\n    她说 \"原样引用\n$closing"
            for ((streaming, interrupted) in listOf(true to false, false to true)) {
                assertEquals(body, NarrativeHistoryEcho.completedBody(
                    "$opening\n$body", enabled = true, interrupted = interrupted, streaming = streaming,
                ))
            }
            val complete = "$body 后还有内容\"。"
            assertEquals(complete, NarrativeHistoryEcho.replyBody("$opening\n$complete\n$closing"))
        }
    }

    @Test fun markdownQuotesAreLiteralDuringPreviewCompletionAndStop() {
        val closing = "</历史回复>"
        for (mode in ChatNarrativeMode.entries) {
            val opening = "<历史回复 模式=\"${mode.shortLabel}\">"
            val body = "段落。\n> > $closing\n\n继续正文。"
            assertEquals(body, NarrativeHistoryEcho.replyBody("$opening\n$body\n$closing", streaming = true))
            assertEquals(body, NarrativeHistoryEcho.replyBody("$opening\n$body\n$closing"))
            assertEquals("> $closing", NarrativeHistoryEcho.replyBody("$opening\n> $closing"))
            assertEquals("> </历史回", NarrativeHistoryEcho.replyBody("$opening\n> </历史回", interrupted = true))
            val nested = "- > $closing\n\n继续正文。"
            assertEquals(nested, NarrativeHistoryEcho.replyBody("$opening\n$nested\n$closing"))
            assertEquals("1. > </历史回", NarrativeHistoryEcho.replyBody("$opening\n1. > </历史回", interrupted = true))
            assertEquals("纯正文。", NarrativeHistoryEcho.replyBody("$opening\n纯正文。\n$closing"))
        }
    }

    @Test fun proseOnLazyMarkdownQuoteLinesKeepsLiteralMarkers() {
        val opening = "<历史回复 模式=\"聚焦\">"
        val closing = "</历史回复>"
        val partial = closing.take(5)
        for ((quote, indent) in listOf("> 引用第一行" to "", "- > 引用第一行" to "  ")) {
            val body = "$quote\n${indent}字面标签 $closing"
            assertEquals(body, NarrativeHistoryEcho.replyBody("$opening\n$body"))
            assertEquals(body, NarrativeHistoryEcho.replyBody("$opening\n$body\n$closing", streaming = true))
            assertEquals(body, NarrativeHistoryEcho.replyBody("$opening\n$body\n$closing"))
            val interruptedBody = "$quote\n${indent}字面标签 $partial"
            assertEquals(interruptedBody, NarrativeHistoryEcho.replyBody("$opening\n$interruptedBody", interrupted = true))

            val first = "$opening\n$body\n$closing"
            val second = "$opening\n另一段正文。\n$closing"
            assertEquals("$body\n另一段正文。", NarrativeHistoryEcho.completedBody(
                "$first\n$second", enabled = true, partLengths = listOf(first.length, second.length),
            ))
        }
    }

    @Test fun standaloneOuterMarkerEndsReplyImmediatelyAfterQuote() {
        val opening = "<历史回复 模式=\"聚焦\">"
        val closing = "</历史回复>"
        val quote = "> 引用第一行\n引用的续行。"
        assertEquals(quote, NarrativeHistoryEcho.replyBody("$opening\n$quote\n  $closing"))
        val partial = closing.take(5)
        assertEquals(quote, NarrativeHistoryEcho.replyBody("$opening\n$quote\n$partial", streaming = true))
        assertEquals(quote, NarrativeHistoryEcho.replyBody("$opening\n$quote\n$partial", interrupted = true))
        assertEquals("$quote\n\n正文。", NarrativeHistoryEcho.replyBody("$opening\n$quote\n\n正文。$closing"))
    }

    @Test fun lazyQuoteClosingExampleRemainsLiteralInAJoinedToolReply() {
        val open = "<历史回复 模式=\"聚焦\">"
        val close = "</历史回复>"
        val quote = "> 下面是要展示的标签。\n$close\n"
        val story = "讲完后，她收好书。"
        val parts = listOf("$open\n$quote", "$story\n$close\n<!-- 编辑备注 -->")
        for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
            assertEquals(
                "$quote\n$story\n<!-- 编辑备注 -->",
                requestWithNarrativeHistory(ChatNarrativeMode.FOCUS, null).completedBody(
                    parts.joinToString("\n"), streaming = streaming, interrupted = interrupted,
                    partLengths = parts.map(String::length),
                ),
            )
        }
    }

    @Test fun interruptedTrailingMarkerStaysHiddenOnSave() {
        val opening = "<历史回复 模式=\"聚焦\">"
        val closing = "</历史回复>"
        for (length in 1 until closing.length) {
            val raw = "$opening\n正文\n${closing.take(length)}"
            assertEquals("正文", NarrativeHistoryEcho.replyBody(raw, streaming = true))
            assertEquals("正文", NarrativeHistoryEcho.replyBody(raw, interrupted = true))
        }
        assertEquals("2 <", NarrativeHistoryEcho.replyBody("$opening\n2 <", interrupted = true))
    }

    @Test fun declaredOrDisabledReplyFormatKeepsIdenticalText() {
        val opening = "<历史回复 模式=\"推进\">"
        val raw = "$opening\n格式卡正文\n</历史回复>"
        for ((mode, format) in listOf(null to null, ChatNarrativeMode.ADVANCE to raw)) {
            val cleanup = requestWithNarrativeHistory(mode, format)
            assertEquals(raw, cleanup.completedBody(raw))
            assertEquals(raw, cleanup.completedBody(raw, streaming = true))
        }
    }

    @Test fun quotedAndBareCarriageReturnCodeTagsStayInsideTheStory() {
        val opening = "<历史回复 模式=\"聚焦\">"
        val closing = "</历史回复>"
        val quoted = "她写下「$closing」和\"$closing\"，然后又继续。"
        val code = "示例：\r```text\r$closing\r```\r正文。"
        assertEquals(quoted, NarrativeHistoryEcho.replyBody("$opening\n$quoted\n$closing"))
        assertEquals(code, NarrativeHistoryEcho.replyBody("$opening\r$code\r$closing"))
    }

    @Test fun pairedProseQuotesKeepLiteralTagsAcrossLines() {
        val closing = "</历史回复>"
        for (mode in ChatNarrativeMode.entries) {
            val opening = "<历史回复 模式=\"${mode.shortLabel}\">"
            val body = "她念道：“示例的结束标签是\n$closing\n就这样写。”\n旁白继续。"
            val wrapped = "$opening\n$body\n$closing"
            assertEquals(body, NarrativeHistoryEcho.completedBody(wrapped, enabled = true))
            assertEquals(body, NarrativeHistoryEcho.completedBody(wrapped, enabled = true, streaming = true))
        }
    }

    @Test fun halfWidthRoleplayQuotesKeepLiteralClosingTags() {
        val closing = "</历史回复>"
        for (mode in ChatNarrativeMode.entries) {
            val body = "她在纸条上写了 ｢$closing｣。\n然后划掉了那行。"
            val wrapped = PromptTemplates.narrativeModeHistoryReply(body, mode)
            assertEquals(body, NarrativeHistoryEcho.completedBody(wrapped, enabled = true))
            assertEquals(body, NarrativeHistoryEcho.completedBody(wrapped, enabled = true, streaming = true))
            assertEquals(body, NarrativeHistoryEcho.completedBody(wrapped, enabled = true, interrupted = true))
        }
    }

    @Test fun roleplaySpeakerBracketsKeepTheLiteralCloseAcrossPreviewStopAndCompletion() {
        val closing = "</历史回复>"
        for (mode in ChatNarrativeMode.entries) {
            val opening = "<历史回复 模式=\"${mode.shortLabel}\">"
            val cleanup = requestWithNarrativeHistory(mode, null)
            for ((lead, end) in listOf("<n=\"琳\"/>[" to "]()", "＜ｎ＝“琳”／＞［" to "］（）")) {
                val draft = "${lead}抄下这行：\n$closing"
                val body = "$draft\n这是纸上的标签。$end\n门开了。"
                val wrapped = "$opening\n$body\n$closing"
                for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
                    assertEquals(body, cleanup.completedBody(wrapped, interrupted = interrupted, streaming = streaming))
                }
                assertEquals(draft, cleanup.completedBody("$opening\n$draft", streaming = true))
                assertEquals(draft, cleanup.completedBody("$opening\n$draft", interrupted = true))
            }
            val bracketField = "门上的标牌开头是 [位置"
            val wrappedField = "$opening\n$bracketField\n$closing"
            assertEquals(bracketField, cleanup.completedBody(wrappedField, streaming = true))
            assertEquals(bracketField, cleanup.completedBody(wrappedField, interrupted = true))
        }
    }

    @Test fun explicitReplySamplePreservesItsTagsButAnIllustrationDoesNot() {
        val mode = ChatNarrativeMode.FOCUS
        val story = "门开了。"
        val wrapped = PromptTemplates.narrativeModeHistoryReply(story, mode)
        val sample = "回复样张：\n```xml\n$wrapped\n```"
        val instruction = "每次回复必须逐字保留以下首尾标签，正文写在两者之间。不要输出代码围栏。"
        for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
            val declared = requestWithNarrativeHistory(mode, "$instruction\n$sample")
            assertEquals(wrapped, declared.completedBody(wrapped, interrupted = interrupted, streaming = streaming))
            for (card in listOf(
                sample,
                "<!-- $instruction -->\n$sample",
                "旁注引述了规则：$instruction\n$sample",
                "$instruction\n$sample\n不得输出上面的标签。",
            )) {
                val illustrative = requestWithNarrativeHistory(mode, card)
                assertEquals(story, illustrative.completedBody(wrapped, interrupted = interrupted, streaming = streaming))
            }
        }
    }

    @Test fun declaredTextStatusFollowsTheStoryWithoutRestoringTheEchoOrDroppingTheFence() {
        val mode = ChatNarrativeMode.ADVANCE
        val closing = "</历史回复>"
        val code = "```kotlin\nval bracket = \"[地点]\"  \n```"
        val story = "灯亮了。\n\n$code"
        val wrapped = PromptTemplates.narrativeModeHistoryReply(story, mode)
        val panel = "```text\n[地点]：车站  \n[注记]：$closing\n```"
        val template = panel.replace("车站", "{地点}")
        val raw = "$wrapped\n\n$panel"
        val labels = listOf("每次回复末尾必须输出以下状态栏：", "每次回复以一个状态栏结束。\n回复样张：")
        val preview = requestWithNarrativeHistory(mode, "${labels.first()}\n$template")
        for (pending in listOf(panel.take(4), panel.substringBeforeLast("```"))) {
            assertEquals("$story\n\n$pending", preview.completedBody("$wrapped\n\n$pending", streaming = true))
            assertEquals("$story\n\n$pending", preview.completedBody("$wrapped\n\n$pending", interrupted = true))
        }
        for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
            for (label in labels) {
                val declared = requestWithNarrativeHistory(mode, "$label\n$template")
                assertEquals("$story\n\n$panel", declared.completedBody(raw, interrupted = interrupted, streaming = streaming))
            }
            for (card in listOf(
                "文字代码示例：\n$template",
                "以下是禁止输出的反例。\n${labels.first()}\n$template",
                "${labels.first()}\n${template.replace("```text", "```status")}",
            )) {
                val illustrative = requestWithNarrativeHistory(mode, card)
                assertEquals(raw, illustrative.completedBody(raw, interrupted = interrupted, streaming = streaming))
                assertEquals("$story\n\n$panel", illustrative.completedBody(
                    PromptTemplates.narrativeModeHistoryReply("$story\n\n$panel", mode),
                    interrupted = interrupted, streaming = streaming,
                ))
            }
        }
    }

    @Test fun formatCardMentionsDoNotOptOutOfEchoCleanup() {
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        val raw = "$opening\n普通正文\n$closing"
        val mentions = listOf(
            "不要输出 `$opening`，那是历史标记。",
            "不要输出 $opening，那是历史标记。",
            "```xml\n$raw\n```",
            "<!--\n$raw\n-->",
            "> 示例原文：\n$raw",
        )
        for (format in mentions) {
            val cleanup = requestWithNarrativeHistory(ChatNarrativeMode.ADVANCE, format)
            assertEquals("普通正文", cleanup.completedBody(raw))
        }
        val declaration = "请按以下格式输出：\n$raw"
        val cleanup = requestWithNarrativeHistory(ChatNarrativeMode.ADVANCE, declaration)
        assertEquals(raw, cleanup.completedBody(raw))
    }

    @Test fun sameLineFormatCardLabelsDeclareOnlyAnExplicitOutputTemplate() {
        val pair = "<历史回复 模式=\"推进\">{正文}</历史回复>"
        for (declaration in listOf(
            "本轮输出模板：$pair",
            "输出格式 : $pair",
            "请按以下格式输出：$pair <!-- 内部注释 -->",
            pair,
            "请按以下格式输出：\n$pair",
        )) {
            assertEquals(declaration, pair,
                requestWithNarrativeHistory(ChatNarrativeMode.ADVANCE, declaration).completedBody(pair))
        }

        for (mention in listOf(
            "下面只是格式示例：$pair",
            "本轮输出模板：$pair（仅为示例，请勿输出）",
            "她说：“本轮输出模板：$pair”",
            "输出模板：'$pair'",
            "> 本轮输出模板：$pair",
            "    本轮输出模板：$pair",
            "~~~~text\n本轮输出模板：$pair\n~~~~",
            "<!-- 本轮输出模板：$pair -->",
        )) {
            assertEquals(mention, "{正文}",
                requestWithNarrativeHistory(ChatNarrativeMode.ADVANCE, mention).completedBody(pair))
        }
    }

    @Test
    fun precedingFormatLabelCanDeclareAnAffirmedTemplateAcrossComments() {
        val mode = ChatNarrativeMode.FOCUS
        val story = "门开了。"
        val reply = PromptTemplates.narrativeModeHistoryReply(story, mode)
        val label = "本轮输出模板："
        val affirmation = "（标签是固定输出的一部分）"
        val note = "<!-- 模板说明 -->"
        val template = "$reply$affirmation"
        val declarations = listOf(
            "$label\n$template",
            "$label\n$note\n$template",
            "$label\n$note$template",
            "$label\n<!-- 多行\n说明 -->\n$template",
        )
        val examples = listOf(
            "$note\n$template",
            "下面只是格式示例：\n$note\n$template",
            "以下是禁止输出的反例。\n$label\n$note\n$template",
            "$label\n$note\n$template\n不得输出上面的标签。",
            "> $label\n\n$template",
            "~~~xml\n$label\n$note\n$template\n~~~",
        )
        for (lineBreak in listOf("\n", "\r\n")) {
            val raw = reply.replace("\n", lineBreak)
            for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
                for (declaration in declarations) {
                    val cleanup = requestWithNarrativeHistory(mode, declaration.replace("\n", lineBreak))
                    assertEquals(declaration, raw, cleanup.completedBody(
                        raw, streaming = streaming, interrupted = interrupted,
                    ))
                }
                for (example in examples) {
                    val cleanup = requestWithNarrativeHistory(mode, example.replace("\n", lineBreak))
                    assertEquals(example, story, cleanup.completedBody(
                        raw, streaming = streaming, interrupted = interrupted,
                    ))
                }
            }
        }
    }

    @Test
    fun terminalEchoCleanupKeepsWhitespaceInsideLiteralCode() {
        val close = "</历史回复>"
        val unfinishedClose = close.dropLast(1)
        for (mode in ChatNarrativeMode.entries) {
            val label = mode.shortLabel
            val open = "<历史回复 模式=\"$label\">"
            val cleanup = requestWithNarrativeHistory(mode, null)
            for (lineBreak in listOf("\n", "\r\n")) {
                val code = "    $close  "
                val fenced = listOf("~~~xml", close, "~~~  ").joinToString(lineBreak)
                for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
                    for (literal in listOf(code, fenced)) {
                        val reply = "$open$lineBreak$literal$lineBreak$close"
                        assertEquals(literal, cleanup.completedBody(reply, streaming = streaming, interrupted = interrupted))
                    }
                    val prose = "门开了。"
                    val reply = "$open$lineBreak$prose  $lineBreak$close"
                    assertEquals(prose, cleanup.completedBody(reply, streaming = streaming, interrupted = interrupted))
                }
                val unfinished = "$open$lineBreak$code$lineBreak$unfinishedClose"
                assertEquals(code, cleanup.completedBody(unfinished, streaming = true))
                assertEquals(code, cleanup.completedBody(unfinished, interrupted = true))
            }
        }
    }

    @Test fun labelledFormatTemplateRequiresTheVisibleBalancedClose() {
        val inner = "<历史回复 模式=\"聚焦\">{正文}</历史回复>"
        val template = "本轮输出模板：<历史回复 模式=\"推进\">$inner</历史回复>"
        val raw = template.substringAfter('：')
        fun clean(card: String) = requestWithNarrativeHistory(ChatNarrativeMode.ADVANCE, card).completedBody(raw)

        assertEquals(raw, clean("$template <!-- 备注 -->"))
        assertEquals(inner, clean("$template（此处说明）"))
        assertEquals(inner, clean(template.removeSuffix("</历史回复>")))
        assertEquals(inner, clean(template.removeSuffix("</历史回复>") + "<!-- </历史回复> -->"))
        val multiline = "本轮输出模板：<历史回复 模式=\"推进\">\n$inner\n</历史回复>"
        val cleanup = requestWithNarrativeHistory(ChatNarrativeMode.ADVANCE, multiline)
        assertEquals("{正文}", cleanup.completedBody(inner))
    }

    @Test fun bareDeclarationRequiresALineWithNoVisibleSuffix() {
        val raw = "<历史回复 模式=\"推进\">正文</历史回复>"
        for (format in listOf(raw, "$raw \t", "$raw <!-- 注释 -->", "$raw <!-- 注\n释 -->", "$raw\n附加说明")) {
            val cleanup = requestWithNarrativeHistory(ChatNarrativeMode.ADVANCE, format)
            assertEquals(format, raw, cleanup.completedBody(raw))
        }
        val mentions = listOf("$raw 只是历史标记示例，请勿使用。", "$raw <!-- 注释 --> 仅示例。")
        for (format in mentions) {
            val cleanup = requestWithNarrativeHistory(ChatNarrativeMode.ADVANCE, format)
            assertEquals(format, "正文", cleanup.completedBody(raw))
        }
    }

    @Test fun declaredOpenersStayLiteralUntilAnotherOpenerIsIdentified() {
        val advance = "<历史回复 模式=\"推进\">"
        val focus = "<历史回复 模式=\"聚焦\">"
        val close = "</历史回复>"
        val declared = "$advance 格式正文 $close"
        val cleanup = requestWithNarrativeHistory(ChatNarrativeMode.ADVANCE, "本轮输出模板：$advance{正文}$close")
        val shared = "<历史回复 模式=\""
        for (partial in listOf(shared, "${advance.dropLast(1)}")) {
            assertEquals(partial, cleanup.completedBody(partial, streaming = true))
            assertEquals(partial, cleanup.completedBody(partial, interrupted = true))
        }
        assertEquals("", cleanup.completedBody(focus.dropLast(1), streaming = true))
        val legacy = "<聚焦模式=\"沉浸\">甲</聚焦模式>"
        val focusBody = "${focus}乙$close"
        val independentParts = listOf(declared, legacy, focusBody)
        val joinedTemplate = listOf(advance, focusBody, close)
        for ((streaming, interrupted) in listOf(false to false, true to false, false to true)) {
            assertEquals(joinedTemplate.joinToString("\n"), cleanup.completedBody(
                joinedTemplate.joinToString("\n"),
                streaming = streaming,
                interrupted = interrupted,
                partLengths = joinedTemplate.map(String::length),
            ))
            assertEquals("$declared\n甲\n乙", cleanup.completedBody(
                independentParts.joinToString("\n"),
                streaming = streaming,
                interrupted = interrupted,
                partLengths = independentParts.map(String::length),
            ))
        }
    }

    @Test fun listMembershipProtectsLiteralTagsAndEndsAtAnUnindentedClose() {
        val open = "<历史回复 模式=\"推进\">"
        val close = "</历史回复>"
        for ((marker, indent) in listOf("1." to "   ", "1)" to "   ", "-" to "  ")) {
            for (lineBreak in listOf("\n", "\r\n", "\r")) {
                val story = listOf("$marker 请解释结束标签：", "$indent$close", "${indent}这是列表中的例子。")
                    .joinToString(lineBreak)
                for ((streaming, interrupted) in listOf(false to false, true to false, false to true)) {
                    assertEquals(story, NarrativeHistoryEcho.completedBody(
                        "$open$lineBreak$story$lineBreak$close", true, interrupted, streaming = streaming,
                    ))
                }
                val partial = "$marker 示例：$lineBreak$indent</历史回"
                assertEquals(partial, NarrativeHistoryEcho.replyBody("$open$lineBreak$partial", streaming = true))
            }
        }
        val prose = "1. 列表正文。\n续写普通段落。"
        assertEquals(prose, NarrativeHistoryEcho.replyBody("$open\n$prose\n$close"))
    }

    @Test fun laterWordApostropheDoesNotHideTheEndOfAPossessiveQuote() {
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        val body = "‘James’ book says they’re using\n$closing\nas an example’."
        val raw = "$opening\n$body\n$closing"
        assertEquals(body, NarrativeHistoryEcho.completedBody(raw, true, streaming = true))
        assertEquals(body, NarrativeHistoryEcho.completedBody(raw, true, interrupted = true))
        assertEquals(body, NarrativeHistoryEcho.completedBody(raw, true))
    }

    @Test fun asciiProseQuotesKeepLiteralClosingTags() {
        for ((opening, closing) in listOf(
            "<聚焦模式=\"沉浸\">" to "</聚焦模式>",
            "<历史回复 模式=\"推进\">" to "</历史回复>",
        )) {
            val body = "He said: \"Type \\\"the label\\\" $closing to finish.\"\nThen she continued."
            val wrapped = "$opening\n$body\n$closing"
            assertEquals(body, NarrativeHistoryEcho.completedBody(wrapped, enabled = true, streaming = true))
            assertEquals(body, NarrativeHistoryEcho.completedBody(wrapped, enabled = true, interrupted = true))
            val contraction = "Don't stop now."
            assertEquals(contraction, NarrativeHistoryEcho.replyBody("$opening\n$contraction\n$closing"))
        }
    }

    @Test fun provisionalProseQuotesStayLiteralWhenTheReplyIsStopped() {
        val opening = "<历史回复 模式=\"聚焦\">"
        val closing = "</历史回复>"
        for (beginning in listOf("她说：\"", "她说：“", "她说：「")) {
            val body = "${beginning}Type $closing to finish."
            val raw = "$opening\n$body"
            assertEquals(body, NarrativeHistoryEcho.completedBody(raw, enabled = true, streaming = true))
            assertEquals(body, NarrativeHistoryEcho.completedBody(raw, enabled = true, interrupted = true))
        }
    }

    @Test fun measurementMarkDoesNotStartAnOpenProseQuote() {
        val body = "She fitted a 6\" pipe."
        val raw = "<历史回复 模式=\"推进\">\n$body\n</历史回复>"
        assertEquals(body, NarrativeHistoryEcho.completedBody(raw, enabled = true, streaming = true))
        assertEquals(body, NarrativeHistoryEcho.completedBody(raw, enabled = true, interrupted = true))
    }

    @Test fun curlyPossessiveDoesNotEndTheOuterProseQuote() {
        val closing = "</历史回复>"
        for (gap in listOf(" ", "\n", "\t  ")) {
            val body = "‘James’${gap}book uses\n$closing\nas an example’."
            val raw = "<历史回复 模式=\"推进\">\n$body\n$closing"
            assertEquals(body, NarrativeHistoryEcho.completedBody(raw, enabled = true))
            assertEquals(body, NarrativeHistoryEcho.completedBody(raw, enabled = true, streaming = true))
            assertEquals(body, NarrativeHistoryEcho.completedBody(raw, enabled = true, interrupted = true))
        }
    }

    @Test fun unfinishedCurlyPossessiveKeepsInteriorLiterallyQuotedTag() {
        for ((opening, closing) in listOf(
            "<历史回复 模式=\"聚焦\">" to "</历史回复>",
            "<历史回复 模式=\"推进\">" to "</历史回复>",
            "<聚焦模式=\"沉浸\">" to "</聚焦模式>",
        )) {
            for (breakLine in listOf("\n", "\r\n", "\r")) for (speech in listOf("她说", "她說：")) {
                val lead = "${speech}‘the visitors’ plan"
                val prefix = "$lead$breakLine$closing$breakLine" + "仍在这里。"
                val incoming = "$opening$breakLine$prefix"
                assertEquals(prefix, NarrativeHistoryEcho.completedBody(incoming, enabled = true, streaming = true))
                assertEquals(prefix, NarrativeHistoryEcho.completedBody(incoming, enabled = true, interrupted = true))
                assertEquals(incoming, NarrativeHistoryEcho.completedBody(incoming, enabled = true))
                val completed = "$prefix’然后转身。"
                assertEquals(completed,
                    NarrativeHistoryEcho.completedBody("$opening$breakLine$completed$breakLine$closing", enabled = true))

                // These bytes alone still look like a real terminal outer close.
                val ambiguous = "$opening$breakLine$lead$breakLine$closing"
                assertEquals(lead, NarrativeHistoryEcho.completedBody(ambiguous, enabled = true, streaming = true))
                val twoLiteralClosers = "$prefix$breakLine$closing"
                assertEquals(twoLiteralClosers, NarrativeHistoryEcho.completedBody(
                    "$opening$breakLine$twoLiteralClosers", enabled = true, interrupted = true,
                ))
            }
        }
    }

    @Test fun separateParagraphDoesNotReinterpretAnExampleAsAnUnclosedCurlyQuote() {
        val opening = "<历史回复 模式=\"聚焦\">"
        val closing = "</历史回复>"
        val heading = "She titled it ‘Visitors’ before leaving"
        for (breakLine in listOf("\n", "\r\n", "\r")) for (blank in listOf("", " \t")) {
            val example = "$opening$breakLine$heading$breakLine$closing$breakLine$blank$breakLine" +
                "This is a markup example."
            for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
                assertEquals(example, NarrativeHistoryEcho.completedBody(example, true, interrupted, streaming = streaming))
            }
        }
    }

    @Test fun finishedPluralQuoteStillAllowsARealOuterClose() {
        val closedQuotes = listOf(
            "She titled it ‘Visitors’ before leaving" to "This is a markup example.",
            "She repeated ‘the visitors’ aloud" to "This is a markup example.",
            "She repeated ‘the visitors’ yesterday" to "This is a markup example.",
            "她重复‘the visitors’ and walked away." to "这是一段标签示例。",
            "I checked whether ‘the visitors’ arrived" to "This first pair is a markup example.",
            "I checked whether ‘the visitors’ arrived" to "这是一段标签示例。",
            "她说‘the visitors’ arrived" to "This first pair is a markup example.",
            "我确认‘the visitors’ arrived" to "这是一段标签示例。",
        )
        for (mode in ChatNarrativeMode.entries) for ((body, commentary) in closedQuotes) {
            val wrapped = PromptTemplates.narrativeModeHistoryReply(body, mode)
            val standaloneExample = "$wrapped\n$commentary"
            for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
                assertEquals(body, NarrativeHistoryEcho.completedBody(wrapped, true, interrupted, streaming = streaming))
                assertEquals(standaloneExample,
                    NarrativeHistoryEcho.completedBody(standaloneExample, true, interrupted, streaming = streaming))
            }
        }
    }

    @Test fun onlyCompletedToolOutputCanJoinSeparateEchoParts() {
        val first = "<历史回复 模式=\"聚焦\">\n甲\n</历史回复>"
        val second = "<历史回复 模式=\"推进\">\n乙\n</历史回复>"
        val raw = "$first\n$second"
        assertEquals("甲\n乙", NarrativeHistoryEcho.completedBody(raw, enabled = true,
            partLengths = listOf(first.length, second.length)))
        assertEquals(raw, NarrativeHistoryEcho.completedBody(raw, enabled = true))
        val boundaries = listOf(first.length, second.length)
        assertEquals("甲\n乙", NarrativeHistoryEcho.completedBody(
            raw, enabled = true, partLengths = boundaries, streaming = true,
        ))
        assertEquals("甲\n乙", NarrativeHistoryEcho.completedBody(
            raw, enabled = true, partLengths = boundaries, interrupted = true,
        ))
        assertEquals("甲\n普通正文", NarrativeHistoryEcho.completedBody("$first\n普通正文", enabled = true,
            partLengths = listOf(first.length, "普通正文".length)))
    }

    @Test
    fun stripsTheExactHistoryWrapperForBothModes() {
        for (mode in ChatNarrativeMode.entries) {
            val wrapped = PromptTemplates.narrativeModeHistoryReply("门开了。\n\n灯亮了。", mode)
            assertEquals("门开了。\n\n灯亮了。", NarrativeHistoryEcho.replyBody(wrapped))
        }
    }

    @Test
    fun streamedPrefixesAndSuffixesNeverBecomeReplyText() {
        val opening = "<历史回复 模式=\"聚焦\">"
        val closing = "</历史回复>"
        for (length in 1..opening.length) {
            assertEquals("", NarrativeHistoryEcho.replyBody(opening.take(length), streaming = true))
        }
        for (length in 1..closing.length) {
            val raw = "$opening\n门开了。\n${closing.take(length)}"
            assertEquals("门开了。", NarrativeHistoryEcho.replyBody(raw, streaming = true))
        }
        assertEquals("门开了。", NarrativeHistoryEcho.replyBody("$opening\n门开了。"))
        assertEquals("门开了。", NarrativeHistoryEcho.replyBody("$opening\n门开了。\n</历史回复"))
        assertEquals("2", NarrativeHistoryEcho.replyBody("$opening\n2 <", streaming = true))
        assertEquals("2 <", NarrativeHistoryEcho.replyBody("$opening\n2 <"))
    }

    @Test
    fun handlesPreviouslyReportedImmersiveSpellingAndPreservesOtherText() {
        assertEquals("门开了。", NarrativeHistoryEcho.replyBody("<聚焦模式=\"沉浸\">\n门开了。\n</聚焦模式=\"沉浸\">"))
        val prose = "他说这个示例用 <历史回复 模式=\"聚焦\">，但保留正文。"
        val example = "```text\n<历史回复 模式=\"聚焦\">\n示例\n</历史回复>\n```"
        val unknownTag = "<历史回复 模式=\"自定义\">\n正文\n</历史回复>"
        for (raw in listOf(prose, example, unknownTag)) {
            assertEquals(raw, NarrativeHistoryEcho.replyBody(raw))
            assertEquals(raw, NarrativeHistoryEcho.replyBody(raw, streaming = true))
        }
    }

    @Test
    fun xmlCdataKeepsLiteralTagsWhileRemovingTheOuterEcho() {
        val closing = "</历史回复>"
        for (mode in ChatNarrativeMode.entries) {
            val cleanup = requestWithNarrativeHistory(mode, null)
            val opening = "<历史回复 模式=\"${mode.shortLabel}\">"
            val xmlStart = "<example><![CDATA[\n$closing\n<!-- literal opener\n```text\n\"literal quote"
            val xml = "$xmlStart\n]]></example>"
            val parts = listOf("$opening\n$xmlStart", "]]></example>\n$closing")
            for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
                assertEquals(xml, cleanup.completedBody(
                    "$opening\n$xml\n$closing", streaming = streaming, interrupted = interrupted,
                ))
                assertEquals(xml, cleanup.completedBody(
                    parts.joinToString("\n"), streaming = streaming, interrupted = interrupted,
                    partLengths = parts.map(String::length),
                ))
            }

            val partialTag = closing.dropLast(1)
            val interruptedXml = "$xmlStart\n$partialTag"
            assertEquals(interruptedXml, cleanup.completedBody("$opening\n$interruptedXml", streaming = true))
            assertEquals(interruptedXml, cleanup.completedBody("$opening\n$interruptedXml", interrupted = true))
            assertEquals(xml, cleanup.completedBody("$opening\n$xml\n$partialTag", interrupted = true))
            val escapedMarker = "字面标记 \\<![CDATA[\n正文。"
            assertEquals(escapedMarker, cleanup.completedBody("$opening\n$escapedMarker\n$closing", streaming = true))
        }
    }

    @Test
    fun aNestedExampleKeepsItsClosingWithOrWithoutAnOuterClosing() {
        val opening = "<历史回复 模式=\"聚焦\">"
        for (streaming in listOf(false, true)) {
            for (mode in listOf("推进", "自定义")) {
                val example = "示例：<历史回复 模式=\"$mode\">内容</历史回复>"
                assertEquals(example, NarrativeHistoryEcho.replyBody("$opening\n$example", streaming))
                assertEquals(example, NarrativeHistoryEcho.replyBody("$opening\n$example\n</历史回复>", streaming))
            }
            val code = "~~~text\n</历史回复>"
            assertEquals(code, NarrativeHistoryEcho.replyBody("$opening\n$code", streaming))
        }
    }

    @Test
    fun preservesALeadingExampleWhenProseFollowsItsClosingTag() {
        for (mode in ChatNarrativeMode.entries) {
            val example = PromptTemplates.narrativeModeHistoryReply("标签里是一段示例。", mode)
            val raw = " $example\n\n这里开始解释。"
            assertEquals("标签里是一段示例。", NarrativeHistoryEcho.replyBody(example, streaming = true))
            assertEquals(raw, NarrativeHistoryEcho.replyBody(raw, streaming = true))
            assertEquals(raw, NarrativeHistoryEcho.replyBody(raw))
            assertEquals("$raw\n</历史回复>", NarrativeHistoryEcho.replyBody("$raw\n</历史回复>"))
        }
    }

    @Test
    fun inlineCodeDoesNotAffectPairingForEitherMode() {
        for (mode in ChatNarrativeMode.entries) {
            val opener = "<历史回复 模式=\"${mode.shortLabel}\">"
            for (story in listOf(
                "开头标记是 `$opener`，正文继续。",
                "写成 ``例：`$opener` 或\n</历史回复>`` 后接正文。",
                "转义的开头 \\` 保留，之后的 `$opener` 是代码。",
                "在 `</历史回复>` 后继续叙述。"
            )) {
                val raw = PromptTemplates.narrativeModeHistoryReply(story, mode)
                assertEquals(story, NarrativeHistoryEcho.replyBody(raw, streaming = true))
                assertEquals(story, NarrativeHistoryEcho.replyBody(raw))
            }
            val differentParagraph = "$opener\n未闭合的 `\n\n$opener\n</历史回复>\n一个 ` 示例"
            val expected = differentParagraph.substringAfter('\n')
            assertEquals(expected, NarrativeHistoryEcho.replyBody(differentParagraph))
        }
    }

    @Test
    fun preservesIndentationThatMakesATagALiteralExample() {
        val wrapped = PromptTemplates.narrativeModeHistoryReply("示例", ChatNarrativeMode.FOCUS)
        for (indent in listOf("    ", "\t")) {
            val literal = wrapped.lineSequence().joinToString("\n") { indent + it }
            assertEquals(literal, NarrativeHistoryEcho.replyBody(literal))
            val opening = "<历史回复 模式=\"推进\">"
            val body = "这是一段示例。\n\n$indent</历史回复>"
            assertEquals(body, NarrativeHistoryEcho.replyBody("$opening\n$body"))
            assertEquals(body, NarrativeHistoryEcho.replyBody("$opening\n$body\n</历史回复>"))
        }
    }

    @Test
    fun escapedWrapperTokensStayLiteralEvenWhenTheRealOuterClosingIsMissing() {
        for (mode in ChatNarrativeMode.entries) {
            val opening = "<历史回复 模式=\"${mode.shortLabel}\">"
            val closing = "</历史回复>"
            for (streaming in listOf(false, true)) {
                val story = "写作 \\$opening 和 \\$closing，再继续。"
                assertEquals(story, NarrativeHistoryEcho.replyBody("$opening\n$story\n$closing", streaming))
                for (literal in listOf(closing, closing.dropLast(1))) {
                    val tail = "她输入了 \\$literal"
                    assertEquals(tail, NarrativeHistoryEcho.replyBody("$opening\n$tail", streaming))
                }
                val beforeClosing = "两个反斜杠\\\\"
                assertEquals(beforeClosing, NarrativeHistoryEcho.replyBody("$opening\n$beforeClosing$closing", streaming))
            }
        }
    }

    @Test
    fun quotedFencesProtectLiteralTagsOnlyInsideTheirQuote() {
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        for (streaming in listOf(false, true)) {
            for (marker in listOf("~~~", "```")) {
                val example = "> > ${marker}text\r\n> > $closing\r\n> > $marker\r\n外面的正文。"
                assertEquals(example, NarrativeHistoryEcho.replyBody("$opening\r\n$example\r\n$closing", streaming))
            }
            val unclosedQuote = "> ~~~text\n> $closing"
            assertEquals(unclosedQuote, NarrativeHistoryEcho.replyBody("$opening\n$unclosedQuote\n$closing", streaming))
            val topLevelCode = "~~~text\n> ~~~text\n$closing\n~~~"
            assertEquals(topLevelCode, NarrativeHistoryEcho.replyBody("$opening\n$topLevelCode\n$closing", streaming))
        }
    }

    @Test
    fun quotesWithIndentedCodeAndListFencesDoNotCloseTheOuterWrapper() {
        val opening = "<历史回复 模式=\"聚焦\">"
        val closing = "</历史回复>"
        for (streaming in listOf(false, true)) {
            for (story in listOf(
                ">     $closing\n引用外的正文。",
                "> >     $closing\n引用外的正文。",
                "- ~~~text\n  $closing\n  ~~~\n列表外的正文。",
                "2. ~~~text\n   $closing\n   ~~~\n列表外的正文。",
                "- 代码示例\n\n  ~~~\n  $closing\n\n列表外的正文。",
                "- > ~~~\n  > $closing\n  > ~~~",
                "- ~~~text\n  $closing\n> ~~~text\n> $closing\n> ~~~"
            )) {
                assertEquals(story, NarrativeHistoryEcho.replyBody("$opening\n$story\n$closing", streaming))
            }
            val paragraph = "她站在墙边。\n2. ~~~"
            assertEquals(paragraph, NarrativeHistoryEcho.replyBody("$opening\n$paragraph\n   $closing", streaming))
        }
    }

    @Test
    fun theRealClosingTagIsOutsideTheCodeUnderAnEmptyListMarker() {
        val closing = "</历史回复>"
        val stories = listOf(
            "-   \n  ~~~text\n  $closing",
            "    普通代码\n-\n  ~~~text\n  $closing",
            "-\n \t~~~text\n  $closing\n  ~~~",
            "> 说明文字\n+\n  ~~~text\n  $closing\n  ~~~",
            "> 说明文字\n接着说明。\n> +\n>   ~~~text\n> $closing\n>   ~~~"
        )
        for (mode in ChatNarrativeMode.entries) {
            val opening = "<历史回复 模式=\"${mode.shortLabel}\">"
            for (streaming in listOf(false, true)) {
                for (story in stories) {
                    assertEquals(story, NarrativeHistoryEcho.replyBody("$opening\n$story\n$closing", streaming))
                }
            }
        }
    }
}

/** Provide the canonical projected forms used by the lexical fixtures. */
internal fun requestWithNarrativeHistory(
    mode: ChatNarrativeMode?, formatCard: String?,
): NarrativeHistoryEcho.RequestCleanup = NarrativeHistoryEcho.forRequest(mode, formatCard).apply {
    val body = "旧故事。"
    for (historyMode in ChatNarrativeMode.entries) {
        recordHistoryProjection(body, PromptTemplates.narrativeModeHistoryReply(body, historyMode))
    }
    val legacy = "<聚焦模式=\"沉浸\">\n$body\n</聚焦模式>"
    recordHistoryProjection(legacy, legacy)
}
