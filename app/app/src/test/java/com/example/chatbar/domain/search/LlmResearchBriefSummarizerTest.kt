package com.example.chatbar.domain.search

import com.example.chatbar.domain.chat.StreamingChatService
import org.junit.Assert.assertEquals
import org.junit.Test

class LlmResearchBriefSummarizerTest {
    private val summarizer = LlmResearchBriefSummarizer(StreamingChatService())

    @Test
    fun `parseSummary accepts wiki brief schema`() {
        val raw = """
            ```json
            {
              "facts": ["[S1] 角色来自某作品"],
              "notes": ["[S1] 维基没有详细服装设定，不要补成事实"]
            }
            ```
        """.trimIndent()

        val draft = summarizer.parseSummary(raw)

        requireNotNull(draft)
        assertEquals(listOf("[S1] 角色来自某作品"), draft.facts)
        assertEquals(listOf("[S1] 维基没有详细服装设定，不要补成事实"), draft.notes)
    }
}
