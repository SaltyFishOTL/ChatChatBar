package com.example.chatbar.domain.search

import com.example.chatbar.domain.chat.StreamingChatService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `parseSummary accepts brief with unescaped inner quotes`() {
        val raw = """
            {
              "facts": [
                "身份：高中生、乐队鼓手",
                "被桃香邀请担任鼓手，后通过交流关系缓和，称呼仁菜为"NI~NA"。",
                "1.游戏账号昵称为"Pleia"，取自昴星团（Pleiades），谐音"Player"。"
              ],
              "notes": [
                "称呼细节：对仁菜的称呼从全名变为"NI~NA"（仁菜菜），体现关系亲疏变化。"
              ]
            }
        """.trimIndent()

        val draft = summarizer.parseSummary(raw)

        requireNotNull(draft)
        assertEquals(3, draft.facts.size)
        assertEquals("被桃香邀请担任鼓手，后通过交流关系缓和，称呼仁菜为\"NI~NA\"。", draft.facts[1])
        assertEquals("1.游戏账号昵称为\"Pleia\"，取自昴星团（Pleiades），谐音\"Player\"。", draft.facts[2])
        assertEquals("称呼细节：对仁菜的称呼从全名变为\"NI~NA\"（仁菜菜），体现关系亲疏变化。", draft.notes.single())
    }

    @Test
    fun `parseSummary recovers facts from truncated json`() {
        val raw = """
            {"facts":["姓名：安和昴","别名：486","关键经历：因家族期待被安排就读艺能学校","备注：名字取自奶奶成名作"]
        """.trimIndent()

        val draft = summarizer.parseSummary(raw)

        requireNotNull(draft)
        assertEquals(4, draft.facts.size)
        assertEquals("姓名：安和昴", draft.facts[0])
        assertEquals("备注：名字取自奶奶成名作", draft.facts[3])
    }

    @Test
    fun `raw fallback brief keeps full ai output as facts`() {
        val brief = summarizer.rawOutputFallbackBrief(
            rawResponse = "资料原文片段一\n\n资料原文片段二",
            plan = CharacterResearchPlan(
                needSearch = true,
                queries = listOf(CharacterResearchQuery("q1")),
                reason = "Need facts"
            ),
            sources = listOf(
                ResearchSource(
                    sourceId = "S1",
                    title = "t",
                    url = "https://example.com",
                    sourceType = "wiki",
                    query = "q1",
                    excerpt = "e"
                )
            )
        )

        requireNotNull(brief)
        assertEquals(listOf("资料原文片段一\n资料原文片段二"), brief.facts)
        assertTrue(brief.notes.isNotEmpty())
        assertEquals(listOf("q1"), brief.queries)
    }

    @Test
    fun `raw fallback brief returns null when ai output is blank`() {
        val plan = CharacterResearchPlan(
            needSearch = true,
            queries = listOf(CharacterResearchQuery("q1"))
        )

        assertNull(summarizer.rawOutputFallbackBrief("   \n ", plan, emptyList()))
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
