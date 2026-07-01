package com.example.chatbar.ui.components

import org.junit.Assert.assertEquals
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
}
