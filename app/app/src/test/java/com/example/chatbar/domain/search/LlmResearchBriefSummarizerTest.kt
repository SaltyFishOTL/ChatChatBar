package com.example.chatbar.domain.search

import com.example.chatbar.domain.chat.StreamingChatService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `summary prompt keeps ten source blocks without template truncation`() {
        val sources = (1..10).map { index ->
            ResearchSource(
                sourceId = "S$index",
                title = "Source $index",
                url = "https://example.com/source-$index",
                sourceType = "wiki",
                query = "q$index",
                excerpt = "fact $index " + "detail ".repeat(700) + "TAIL_$index"
            )
        }

        val prompt = summarizer.summaryUserPrompt(
            request = "request",
            plan = CharacterResearchPlan(
                needSearch = true,
                queries = sources.map { CharacterResearchQuery(it.query) },
                reason = "Need facts"
            ),
            sources = sources
        )

        assertTrue((1..10).all { index -> prompt.contains("Source $index") })
        assertTrue(prompt.contains("S10"))
        assertTrue(prompt.contains("fact 10"))
        assertTrue(prompt.contains("TAIL_10"))
    }
}
