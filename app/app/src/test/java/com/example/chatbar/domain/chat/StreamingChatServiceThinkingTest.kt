package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.OutputTokenParameter
import com.example.chatbar.data.local.entity.ParamValue
import com.example.chatbar.domain.image.NOVEL_AI_PROMPT_DESIGN_THINKING_BUDGET
import com.example.chatbar.domain.image.NOVEL_AI_SCENE_PLANNING_THINKING_BUDGET
import com.example.chatbar.domain.memory.MEMORY_COMPRESSION_PLANNER_MAX_TOKENS
import com.example.chatbar.domain.memory.forMemoryCompressionPlanner
import com.example.chatbar.domain.memory.shouldDisableMemoryThinking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingChatServiceThinkingTest {
    @Test
    fun `NovelAI task budgets override selected model configured budget`() {
        val model = ModelConfig(
            id = "model",
            displayName = "Model",
            baseUrl = "https://example.com/v1",
            apiKey = "key",
            modelName = "model-name",
            customParams = mapOf(
                "enable_thinking" to ParamValue.BooleanValue(true),
                "thinking_budget" to ParamValue.NumberValue(1_024.0)
            ),
            createdAt = 0
        )
        val service = StreamingChatService()

        val planningBody = Json.parseToJsonElement(
            service.buildRequestBody(
                messages = listOf(ChatApiMessage.text("user", "scene")),
                modelConfig = model,
                stream = true,
                thinkingBudget = NOVEL_AI_SCENE_PLANNING_THINKING_BUDGET
            )
        ).jsonObject
        val designBody = Json.parseToJsonElement(
            service.buildRequestBody(
                messages = listOf(ChatApiMessage.text("user", "prompt")),
                modelConfig = model,
                stream = true,
                thinkingBudget = NOVEL_AI_PROMPT_DESIGN_THINKING_BUDGET
            )
        ).jsonObject

        assertEquals("256", planningBody.getValue("thinking_budget").jsonPrimitive.content)
        assertEquals("512", designBody.getValue("thinking_budget").jsonPrimitive.content)
        assertEquals(true, planningBody.getValue("enable_thinking").jsonPrimitive.boolean)
        assertEquals(true, designBody.getValue("enable_thinking").jsonPrimitive.boolean)
    }

    @Test
    fun `memory compression planner uses isolated 128 token non json request`() {
        val model = ModelConfig(
            id = "model",
            displayName = "Model",
            baseUrl = "https://example.com/v1",
            apiKey = "key",
            modelName = "model-name",
            customParams = mapOf(
                "temperature" to ParamValue.NumberValue(0.8),
                "thinking_budget" to ParamValue.NumberValue(512.0)
            ),
            supportsDisableThinking = true,
            createdAt = 0
        )

        val body = Json.parseToJsonElement(
            StreamingChatService().buildRequestBody(
                messages = listOf(ChatApiMessage.text("user", "plan")),
                modelConfig = model,
                stream = false,
                maxTokens = MEMORY_COMPRESSION_PLANNER_MAX_TOKENS,
                disableThinking = shouldDisableMemoryThinking(model),
                isolatedTaskParameters = true,
                responseFormatJson = false
            )
        ).jsonObject

        assertEquals("128", body.getValue("max_tokens").jsonPrimitive.content)
        assertEquals(false, body.getValue("enable_thinking").jsonPrimitive.boolean)
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("thinking_budget"))
        assertFalse(body.containsKey("response_format"))
    }

    @Test
    fun `memory compression planner never inherits configured thinking`() {
        val model = ModelConfig(
            id = "model",
            displayName = "Model",
            baseUrl = "https://example.com/v1",
            apiKey = "key",
            modelName = "model-name",
            reasoningEffort = "high",
            enableThinking = true,
            supportsDisableThinking = false,
            createdAt = 0
        ).forMemoryCompressionPlanner()

        val body = Json.parseToJsonElement(
            StreamingChatService().buildRequestBody(
                messages = listOf(ChatApiMessage.text("user", "plan")),
                modelConfig = model,
                stream = false,
                maxTokens = MEMORY_COMPRESSION_PLANNER_MAX_TOKENS,
                disableThinking = shouldDisableMemoryThinking(model),
                isolatedTaskParameters = true,
                responseFormatJson = false
            )
        ).jsonObject

        assertFalse(body.containsKey("enable_thinking"))
        assertFalse(body.containsKey("reasoning_effort"))
    }

    @Test
    fun `disable thinking removes reasoning parameters and forces false`() {
        val model = ModelConfig(
            id = "model",
            displayName = "Model",
            baseUrl = "https://example.com/v1",
            apiKey = "key",
            modelName = "model-name",
            customParams = mapOf(
                "enable_thinking" to ParamValue.BooleanValue(true),
                "thinking_budget" to ParamValue.NumberValue(400.0),
                "max_thinking_tokens" to ParamValue.NumberValue(500.0),
                "reasoning_effort" to ParamValue.StringValue("high"),
                "temperature" to ParamValue.NumberValue(0.4)
            ),
            reasoningEffort = "medium",
            enableThinking = true,
            createdAt = 0L
        )

        val body = Json.parseToJsonElement(
            StreamingChatService().buildRequestBody(
                messages = listOf(ChatApiMessage.text("user", "hello")),
                modelConfig = model,
                stream = true,
                disableThinking = true
            )
        ).jsonObject

        assertEquals(false, body.getValue("enable_thinking").jsonPrimitive.boolean)
        assertFalse(body.containsKey("thinking_budget"))
        assertFalse(body.containsKey("max_thinking_tokens"))
        assertFalse(body.containsKey("reasoning_effort"))
        assertEquals("0.4", body.getValue("temperature").jsonPrimitive.content)
    }

    @Test
    fun `isolated memory request strips roleplay params and uses one token field`() {
        val model = ModelConfig(
            id = "model", displayName = "Model", baseUrl = "https://example.com/v1",
            apiKey = "key", modelName = "model-name",
            customParams = mapOf(
                "temperature" to ParamValue.NumberValue(0.8),
                "stop" to ParamValue.StringValue("END"),
                "thinking_budget" to ParamValue.NumberValue(512.0),
                "max_completion_tokens" to ParamValue.NumberValue(999.0)
            ),
            supportsJsonMode = true,
            createdAt = 0
        )
        val body = Json.parseToJsonElement(
            StreamingChatService().buildRequestBody(
                listOf(ChatApiMessage.text("user", "memory")), model, false,
                maxTokens = 1200,
                disableThinking = true,
                isolatedTaskParameters = true,
                responseFormatJson = true
            )
        ).jsonObject
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("stop"))
        assertFalse(body.containsKey("thinking_budget"))
        assertTrue(body.containsKey("max_tokens"))
        assertFalse(body.containsKey("max_completion_tokens"))
        assertEquals("json_object", body.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `explicit dynamic limit removes static aliases and emits selected token field`() {
        val model = ModelConfig(
            id = "model",
            displayName = "Model",
            baseUrl = "https://example.com/v1",
            apiKey = "key",
            modelName = "model-name",
            customParams = mapOf(
                "max_tokens" to ParamValue.NumberValue(40_960.0),
                "max_completion_tokens" to ParamValue.NumberValue(30_000.0),
                "temperature" to ParamValue.NumberValue(0.7)
            ),
            maxOutputTokens = 20_000,
            outputTokenParameter = OutputTokenParameter.MAX_COMPLETION_TOKENS,
            createdAt = 0
        )

        val body = Json.parseToJsonElement(
            StreamingChatService().buildRequestBody(
                messages = listOf(ChatApiMessage.text("user", "hello")),
                modelConfig = model,
                stream = true,
                maxTokens = 1_824
            )
        ).jsonObject

        assertFalse(body.containsKey("max_tokens"))
        assertEquals("1824", body.getValue("max_completion_tokens").jsonPrimitive.content)
        assertEquals("0.7", body.getValue("temperature").jsonPrimitive.content)
    }

    @Test
    fun `custom static token field remains when no explicit limit exists`() {
        val model = ModelConfig(
            id = "model",
            displayName = "Model",
            baseUrl = "https://example.com/v1",
            apiKey = "key",
            modelName = "model-name",
            customParams = mapOf(
                "max_tokens" to ParamValue.NumberValue(1_500.0)
            ),
            createdAt = 0
        )

        val body = Json.parseToJsonElement(
            StreamingChatService().buildRequestBody(
                messages = listOf(ChatApiMessage.text("user", "auxiliary")),
                modelConfig = model,
                stream = true
            )
        ).jsonObject

        assertEquals("1500", body.getValue("max_tokens").jsonPrimitive.content)
        assertFalse(body.containsKey("max_completion_tokens"))
    }
}
