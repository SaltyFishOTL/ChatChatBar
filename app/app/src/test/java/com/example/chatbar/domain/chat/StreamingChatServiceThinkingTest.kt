package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.OutputTokenParameter
import com.example.chatbar.data.local.entity.ParamValue
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
    fun `default chat and auxiliary requests omit output limits`() {
        for (stream in listOf(false, true)) {
            for (isolated in listOf(false, true)) {
                val body = Json.parseToJsonElement(StreamingChatService().buildRequestBody(
                    listOf(ChatApiMessage.text("user", "task")), dynamicModel(), stream,
                    isolatedTaskParameters = isolated
                )).jsonObject
                assertFalse("max_tokens" in body)
                assertFalse("max_completion_tokens" in body)
            }
        }
    }

    @Test
    fun `unlimited task omits both output aliases without mutating model settings`() {
        for (stream in listOf(false, true)) {
            for (parameter in OutputTokenParameter.entries) {
                val model = dynamicModel().copy(
                    maxOutputTokens = 8,
                    outputTokenParameter = parameter,
                    customParams = mapOf(
                        "max_tokens" to ParamValue.NumberValue(8.0),
                        "max_completion_tokens" to ParamValue.NumberValue(2000.0),
                        "temperature" to ParamValue.NumberValue(0.4)
                    )
                )
                val body = Json.parseToJsonElement(StreamingChatService().buildRequestBody(
                    listOf(ChatApiMessage.text("user", "task")),
                    model.withoutOutputTokenLimit(), stream, disableThinking = true
                )).jsonObject
                assertFalse("max_tokens" in body)
                assertFalse("max_completion_tokens" in body)
                assertEquals("none", body.getValue("reasoning_effort").jsonPrimitive.content)
                assertEquals("0.4", body.getValue("temperature").jsonPrimitive.content)
                assertEquals(8, model.maxOutputTokens)
                assertTrue("max_tokens" in model.customParams)
                assertTrue("max_completion_tokens" in model.customParams)
            }
        }
    }

    private fun dynamicModel() = ModelConfig(
        id = "custom", displayName = "Custom", baseUrl = "https://example.test/v1",
        apiKey = "key", modelName = "arbitrary-user-model", createdAt = 0
    )

    @Test
    fun `task budgets become low for models without legacy controls`() {
        for (stream in listOf(false, true)) {
            for (model in listOf(
                dynamicModel(),
                dynamicModel().copy(reasoningEffort = "high"),
                dynamicModel().copy(customParams = mapOf("reasoning_effort" to ParamValue.StringValue("high")))
            )) {
                val body = Json.parseToJsonElement(StreamingChatService().buildRequestBody(
                    listOf(ChatApiMessage.text("user", "task")), model, stream,
                    thinkingBudget = 512, maxThinkingTokens = 512
                )).jsonObject
                assertEquals("low", body.getValue("reasoning_effort").jsonPrimitive.content)
                assertTrue(ThinkingRequestPolicy.legacyKeys.none { it in body })
            }
        }
    }

    @Test
    fun `both off call conventions override budgets and effort with none`() {
        for (disable in listOf(false, true)) {
            val body = Json.parseToJsonElement(StreamingChatService().buildRequestBody(
                listOf(ChatApiMessage.text("user", "task")),
                dynamicModel().copy(reasoningEffort = "high"), true,
                disableThinking = disable,
                enableThinkingOverride = if (disable) null else false,
                thinkingBudget = 64, reasoningEffortOverride = "low",
                isolatedTaskParameters = true
            )).jsonObject
            assertEquals("none", body.getValue("reasoning_effort").jsonPrimitive.content)
            assertTrue(ThinkingRequestPolicy.legacyKeys.none { it in body })
        }
    }

    @Test
    fun `regular chat keeps configured effort without task override`() {
        val body = Json.parseToJsonElement(StreamingChatService().buildRequestBody(
            listOf(ChatApiMessage.text("user", "chat")),
            dynamicModel().copy(reasoningEffort = "high"), true
        )).jsonObject
        assertEquals("high", body.getValue("reasoning_effort").jsonPrimitive.content)
        assertTrue(ThinkingRequestPolicy.legacyKeys.none { it in body })
    }

    @Test
    fun `legacy controls survive isolated task overrides without effort injection`() {
        val body = Json.parseToJsonElement(StreamingChatService().buildRequestBody(
            listOf(ChatApiMessage.text("user", "task")),
            dynamicModel().copy(enableThinking = true, reasoningEffort = "high"), true,
            thinkingBudget = 256, isolatedTaskParameters = true
        )).jsonObject
        assertEquals("256", body.getValue("thinking_budget").jsonPrimitive.content)
        assertEquals(true, body.getValue("enable_thinking").jsonPrimitive.boolean)
        assertFalse("reasoning_effort" in body)
    }

    @Test
    fun `image description preserves configured effort`() {
        val model = dynamicModel().copy(
            customParams = mapOf("reasoning_effort" to ParamValue.StringValue("high"))
        ).forImageDescriptionRequest()
        val body = Json.parseToJsonElement(StreamingChatService().buildRequestBody(
            listOf(ChatApiMessage.text("user", "image")), model, false
        )).jsonObject
        assertEquals("high", body.getValue("reasoning_effort").jsonPrimitive.content)
        assertTrue(ThinkingRequestPolicy.legacyKeys.none { it in body })
    }

    @Test
    fun `requests without task overrides preserve selected model configured budget`() {
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
                stream = true
            )
        ).jsonObject
        val designBody = Json.parseToJsonElement(
            service.buildRequestBody(
                messages = listOf(ChatApiMessage.text("user", "prompt")),
                modelConfig = model,
                stream = true
            )
        ).jsonObject

        assertEquals("1024", planningBody.getValue("thinking_budget").jsonPrimitive.content)
        assertEquals("1024", designBody.getValue("thinking_budget").jsonPrimitive.content)
        assertEquals(true, planningBody.getValue("enable_thinking").jsonPrimitive.boolean)
        assertEquals(true, designBody.getValue("enable_thinking").jsonPrimitive.boolean)
    }

    @Test
    fun `memory compression planner preserves configured sampling in non json request`() {
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
                disableThinking = shouldDisableMemoryThinking(model),
                responseFormatJson = false
            )
        ).jsonObject

        assertFalse(body.containsKey("max_tokens"))
        assertFalse(body.containsKey("max_completion_tokens"))
        assertFalse(body.containsKey("enable_thinking"))
        assertEquals("0.8", body.getValue("temperature").jsonPrimitive.content)
        assertEquals("512", body.getValue("thinking_budget").jsonPrimitive.content)
        assertFalse(body.containsKey("response_format"))
    }

    @Test
    fun `memory compression planner inherits configured thinking`() {
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
                disableThinking = shouldDisableMemoryThinking(model),
                responseFormatJson = false
            )
        ).jsonObject

        assertEquals(true, body.getValue("enable_thinking").jsonPrimitive.boolean)
        assertEquals("high", body.getValue("reasoning_effort").jsonPrimitive.content)
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
    fun `isolated memory request strips roleplay params and configured output limits`() {
        val model = ModelConfig(
            id = "model", displayName = "Model", baseUrl = "https://example.com/v1",
            apiKey = "key", modelName = "model-name",
            customParams = mapOf(
                "temperature" to ParamValue.NumberValue(0.8),
                "stop" to ParamValue.StringValue("END"),
                "thinking_budget" to ParamValue.NumberValue(512.0),
                "max_completion_tokens" to ParamValue.NumberValue(999.0)
            ),
            maxOutputTokens = 512,
            supportsJsonMode = true,
            createdAt = 0
        )
        val body = Json.parseToJsonElement(
            StreamingChatService().buildRequestBody(
                listOf(ChatApiMessage.text("user", "memory")), model, false,
                disableThinking = true,
                isolatedTaskParameters = true,
                responseFormatJson = true
            )
        ).jsonObject
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("stop"))
        assertFalse(body.containsKey("thinking_budget"))
        assertFalse(body.containsKey("max_tokens"))
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
