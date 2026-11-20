package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatNarrativeMode
import com.example.chatbar.domain.prompt.PromptTemplates
import org.junit.Assert.assertEquals
import org.junit.Test

class NarrativeHistoryEchoTest {
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
            val enabled = NarrativeHistoryEcho.appliesToRequest(mode, format)
            assertEquals(raw, NarrativeHistoryEcho.replyBody(raw, enabled = enabled))
            assertEquals(raw, NarrativeHistoryEcho.replyBody(raw, streaming = true, enabled = enabled))
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
            val lead = "她说‘the visitors’ plan"
            val prefix = "$lead\n$closing\n仍在这里。"
            val incoming = "$opening\n$prefix"
            assertEquals(prefix, NarrativeHistoryEcho.completedBody(incoming, enabled = true, streaming = true))
            assertEquals(prefix, NarrativeHistoryEcho.completedBody(incoming, enabled = true, interrupted = true))
            assertEquals(incoming, NarrativeHistoryEcho.completedBody(incoming, enabled = true))
            val completed = "$prefix’然后转身。"
            assertEquals(completed, NarrativeHistoryEcho.completedBody("$opening\n$completed\n$closing", enabled = true))

            // The same terminal-looking bytes could be the real outer close at this point.
            val ambiguous = "$opening\n$lead\n$closing"
            assertEquals(lead, NarrativeHistoryEcho.completedBody(ambiguous, enabled = true, streaming = true))
        }
    }

    @Test fun finishedPluralQuoteStillAllowsARealOuterClose() {
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        val body = "她重复‘the visitors’ and walked away."
        val wrapped = "$opening\n$body\n$closing"
        val standaloneExample = "$wrapped\n她在解释刚才的示例。"
        for ((streaming, interrupted) in listOf(true to false, false to true, false to false)) {
            assertEquals(body, NarrativeHistoryEcho.completedBody(wrapped, true, interrupted, streaming = streaming))
            assertEquals(standaloneExample,
                NarrativeHistoryEcho.completedBody(standaloneExample, true, interrupted, streaming = streaming))
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
