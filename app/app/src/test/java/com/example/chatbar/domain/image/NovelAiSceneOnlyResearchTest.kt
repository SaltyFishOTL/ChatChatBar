package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.ModelConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NovelAiSceneOnlyResearchTest {
    @Test
    fun `scene only planning never calls tag search`() = runTest {
        var searchCalls = 0
        val service = NovelAiTagResearchService(
            planner = object : NovelAiTagSearchPlanner {
                override suspend fun decide(
                    taskInput: String,
                    characterPrompts: List<Pair<String, String>>,
                    imageBase64s: List<String>,
                    model: ModelConfig,
                    playerName: String?,
                    botName: String,
                    onRawText: (String) -> Unit
                ) = NovelAiTagSearchDecisionResult(
                    decision = NovelAiTagSearchDecision(
                        action = "search",
                        queries = listOf("blue hair"),
                        sceneDescription = "蓝发少女站在雨夜街口。"
                    )
                )
            },
            searchClient = object : NovelAiTagSearchClient {
                override suspend fun search(query: String): NovelAiTagSearchOutcome {
                    searchCalls++
                    return NovelAiTagSearchOutcome(query, emptyList())
                }
            }
        )

        val result = service.planSceneOnly(
            taskInput = "雨夜少女",
            characterPrompts = emptyList(),
            imageBase64s = emptyList(),
            model = ModelConfig(
                id = "model",
                displayName = "Model",
                baseUrl = "https://example.test/v1",
                apiKey = "key",
                modelName = "model",
                createdAt = 1L
            )
        )

        assertEquals("蓝发少女站在雨夜街口。", result.sceneDescription)
        assertEquals(0, searchCalls)
    }
}
