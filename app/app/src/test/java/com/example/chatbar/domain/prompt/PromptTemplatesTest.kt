package com.example.chatbar.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptTemplatesTest {
    @Test
    fun replyLengthTailSystemPrompt_repeatsConfiguredLengthAtPromptTail() {
        assertEquals(
            "严格按照格式要求输出【500字】篇幅的回复。",
            PromptTemplates.replyLengthTailSystemPrompt("500字")
        )
    }
}
