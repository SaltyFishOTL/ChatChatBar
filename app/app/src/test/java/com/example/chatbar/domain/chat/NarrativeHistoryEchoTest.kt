package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatNarrativeMode
import com.example.chatbar.domain.prompt.PromptTemplates
import org.junit.Assert.assertEquals
import org.junit.Test

class NarrativeHistoryEchoTest {
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
}
