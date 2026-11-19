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
}
