package com.example.chatbar.domain.rag

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ParamValue
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamingChatService
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class RetrievalPlannerRequestPolicyTest {
    @Test
    fun supportedModel_disablesThinkingAndDropsInheritedReasoningParameters() {
        val body = requestBody(
            model = model(
                supportsDisableThinking = true,
                reasoningEffort = "high",
                enableThinking = true
            )
        )

        assertEquals(false, body.getValue("enable_thinking").jsonPrimitive.boolean)
        assertFalse(body.containsKey("reasoning_effort"))
        assertFalse(body.containsKey("thinking_budget"))
    }

    @Test
    fun unsupportedModel_doesNotInheritOrInventThinkingParameters() {
        val body = requestBody(
            model = model(
                supportsDisableThinking = false,
                reasoningEffort = "high",
                enableThinking = true
            )
        )

        assertFalse(body.containsKey("enable_thinking"))
        assertFalse(body.containsKey("reasoning_effort"))
        assertFalse(body.containsKey("thinking_budget"))
    }

    private fun requestBody(model: ModelConfig) = model.forRetrievalPlannerRequest().let { requestModel ->
        Json.parseToJsonElement(
            StreamingChatService().buildRequestBody(
                messages = listOf(ChatApiMessage.text("user", "plan")),
                modelConfig = requestModel,
                stream = false,
                disableThinking = shouldExplicitlyDisableRetrievalPlannerThinking(requestModel),
                isolatedTaskParameters = true
            )
        ).jsonObject
    }

    private fun model(
        supportsDisableThinking: Boolean,
        reasoningEffort: String?,
        enableThinking: Boolean?
    ) = ModelConfig(
        id = "retrieval-model",
        displayName = "Retrieval Model",
        baseUrl = "https://example.com/v1",
        apiKey = "key",
        modelName = "model",
        customParams = mapOf(
            "thinking_budget" to ParamValue.NumberValue(1024.0),
            "temperature" to ParamValue.NumberValue(0.8)
        ),
        reasoningEffort = reasoningEffort,
        enableThinking = enableThinking,
        supportsDisableThinking = supportsDisableThinking,
        createdAt = 0L
    )
}
