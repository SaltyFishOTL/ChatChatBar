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

        assertFalse(prompt.contains("【格式卡】"))
        assertFalse(prompt.contains("【分段气泡格式】"))
        assertTrue(prompt.contains("【待修复消息】\n正文"))
    }

    @Test
    fun `includes only supplied rules and message`() {
        val prompt = PromptTemplates.messageFormatRepairUserPrompt(
            formatCard = "格式卡内容",
            segmentedBubbleFormat = "角色名规则",
            message = "待修复正文"
        )

        assertTrue(prompt.contains("【格式卡】\n格式卡内容"))
        assertTrue(prompt.contains("【分段气泡格式】\n角色名规则"))
        assertTrue(prompt.endsWith("【待修复消息】\n待修复正文"))
    }
}
