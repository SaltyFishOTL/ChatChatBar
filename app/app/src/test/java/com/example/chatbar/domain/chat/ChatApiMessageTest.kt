package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ParamValue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ChatApiMessageTest {
    @Test
    fun `image-only content omits empty text part`() {
        val content = ChatApiMessage.withImage(
            role = "user",
            text = "",
            imageBase64 = "abc"
        ).content.jsonArray

        assertEquals(1, content.size)
        assertEquals("image_url", content.first().jsonObject.getValue("type").jsonPrimitive.content)
        assertFalse(content.any {
            it.jsonObject["type"]?.jsonPrimitive?.content == "text"
        })
    }

    @Test
    fun `multimodal content keeps nonblank text before images`() {
        val content = ChatApiMessage.withImages(
            role = "user",
            text = "describe this",
            imageBase64s = listOf("abc", "def")
        ).content.jsonArray

        assertEquals(3, content.size)
        assertEquals("text", content.first().jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            "describe this",
            content.first().jsonObject.getValue("text").jsonPrimitive.content
        )
    }

    @Test
    fun `image description request disables thinking params`() {
        val model = modelConfig(
            customParams = mapOf(
                "enable_thinking" to ParamValue.BooleanValue(true),
                "thinking_budget" to ParamValue.NumberValue(1024.0),
                "max_thinking_tokens" to ParamValue.NumberValue(128.0),
                "reasoning_effort" to ParamValue.StringValue("high"),
                "temperature" to ParamValue.NumberValue(0.7)
            ),
            enableThinking = true,
            reasoningEffort = "high"
        )

        val sanitized = model.forImageDescriptionRequest()

        assertEquals(ParamValue.BooleanValue(false), sanitized.customParams["enable_thinking"])
        assertFalse(sanitized.customParams.containsKey("thinking_budget"))
        assertFalse(sanitized.customParams.containsKey("max_thinking_tokens"))
        assertFalse(sanitized.customParams.containsKey("reasoning_effort"))
        assertEquals(ParamValue.NumberValue(0.7), sanitized.customParams["temperature"])
        assertEquals(false, sanitized.enableThinking)
        assertNull(sanitized.reasoningEffort)
    }

    @Test
    fun `image description request does not add thinking flag`() {
        val sanitized = modelConfig().forImageDescriptionRequest()

        assertFalse(sanitized.customParams.containsKey("enable_thinking"))
        assertNull(sanitized.enableThinking)
        assertNull(sanitized.reasoningEffort)
    }

    private fun modelConfig(
        customParams: Map<String, ParamValue> = emptyMap(),
        enableThinking: Boolean? = null,
        reasoningEffort: String? = null
    ) = ModelConfig(
        id = "model-id",
        displayName = "Vision",
        baseUrl = "https://example.test/v1",
        apiKey = "key",
        modelName = "vision-model",
        isMultimodal = true,
        customParams = customParams,
        enableThinking = enableThinking,
        reasoningEffort = reasoningEffort,
        createdAt = 0L
    )
}
