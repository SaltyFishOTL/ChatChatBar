package com.example.chatbar.domain.search

import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.PromptTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterResearchPlannerTest {
    private val planner = CharacterResearchPlanner(StreamingChatService())

    @Test
    fun `research prompt templates render editable placeholders`() {
        val plannerSystem = PromptTemplates.characterResearchPlannerSystemPrompt(maxQueries = 3)
        val plannerUser = PromptTemplates.characterResearchPlannerUserPrompt(
            userInput = "design a canon role",
            currentCardSummary = "名称：demo"
        )
        val source = PromptTemplates.characterResearchBriefSource(
            sourceId = "S1",
            title = "Source",
            excerpt = "source excerpt"
        )
        val briefUser = PromptTemplates.characterResearchBriefUserPrompt(
            request = "rewrite",
            currentCardSummary = "名称：demo",
            sources = source
        )

        assertTrue(plannerSystem.contains("最多 3 个查询"))
        assertFalse(plannerSystem.contains("{{maxQueries}}"))
        assertTrue(plannerUser.contains("design a canon role"))
        assertTrue(plannerUser.contains("名称：demo"))
        assertTrue(briefUser.contains("[S1] Source"))
        assertTrue(briefUser.contains("检索目标"))
        assertFalse(briefUser.contains("{{sources}}"))
    }

    @Test
    fun `parsePlan accepts fenced json sorts and caps queries`() {
        val raw = """
            planning:
            ```json
            {
              "needSearch": true,
              "reason": "Need canon and setting facts",
              "queries": [
                {
                  "query": "secondary query",
                  "priority": 3
                },
                {
                  "query": "primary query",
                  "priority": 1
                },
                {
                  "query": "overflow query",
                  "priority": 2
                },
                {
                  "query": "   ",
                  "priority": 1
                }
              ]
            }
            ```
        """.trimIndent()

        val plan = planner.parsePlan(raw, maxQueries = 2)

        requireNotNull(plan)
        assertTrue(plan.needSearch)
        assertEquals(
            listOf("primary query", "overflow query"),
            plan.queries.map { it.query }
        )
        assertEquals(listOf(1, 2), plan.queries.map { it.priority })
    }

    @Test
    fun `parsePlan returns null for bad json`() {
        assertNull(planner.parsePlan("not json", maxQueries = 4))
    }

    @Test
    fun `parsePlan accepts string query array`() {
        val raw = """
            {
              "needSearch": true,
              "reason": "Need canon",
              "queries": ["丰川祥子", "Ave Mujica"]
            }
        """.trimIndent()

        val plan = planner.parsePlan(raw, maxQueries = 2)

        requireNotNull(plan)
        assertTrue(plan.needSearch)
        assertEquals(listOf("丰川祥子", "Ave Mujica"), plan.queries.map { it.query })
    }

    @Test
    fun `parsePlan accepts single query field`() {
        val raw = """{"need_search": "需要", "query": "丰川祥子", "purpose": "补外貌"}"""

        val plan = planner.parsePlan(raw, maxQueries = 2)

        requireNotNull(plan)
        assertTrue(plan.needSearch)
        assertEquals(listOf("丰川祥子"), plan.queries.map { it.query })
        assertEquals("补外貌", plan.reason)
    }

    @Test
    fun `parsePlan disables search when all queries are blank`() {
        val raw = """
            {
              "needSearch": true,
              "reason": "wanted",
              "queries": [
                {"query": " ", "priority": 1}
              ]
            }
        """.trimIndent()

        val plan = planner.parsePlan(raw, maxQueries = 4)

        requireNotNull(plan)
        assertFalse(plan.needSearch)
        assertTrue(plan.queries.isEmpty())
    }
}
