package com.example.chatbar.domain.prompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFormatRepairPromptTest {
    @Test
    fun `omits empty optional rule blocks`() {
        val prompt = PromptTemplates.messageFormatRepairUserPrompt(
            formatCard = " ",
            segmentedBubbleFormat = null,
            message = "正文"
        )

        assertFalse(prompt.contains("\n\n"))
        assertTrue(prompt.trimEnd().endsWith("正文"))
    }

    @Test
    fun `includes only supplied rules and message`() {
        val prompt = PromptTemplates.messageFormatRepairUserPrompt(
            formatCard = "格式卡内容",
            segmentedBubbleFormat = "角色名规则",
            message = "待修复正文"
        )

        val formatIndex = prompt.indexOf("格式卡内容")
        val segmentedIndex = prompt.indexOf("角色名规则")
        val messageIndex = prompt.indexOf("待修复正文")

        assertTrue(formatIndex >= 0)
        assertTrue(segmentedIndex > formatIndex)
        assertTrue(messageIndex > segmentedIndex)
        assertTrue(prompt.trimEnd().endsWith("待修复正文"))
    }
}
