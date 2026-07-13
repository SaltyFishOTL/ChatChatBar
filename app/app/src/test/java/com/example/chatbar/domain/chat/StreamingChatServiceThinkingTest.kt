package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ParamValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StreamingChatServiceThinkingTest {
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
}
