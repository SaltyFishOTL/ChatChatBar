package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ParamValue
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.AiTaskMessageAssembler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiliconFlowChatTemplatePolicyTest {
    private val model = ModelConfig(
        id = "fixture", displayName = "fixture", modelName = "deepseek-ai/DeepSeek-V3.2",
        baseUrl = "https://api.siliconflow.cn/v1", apiKey = "", createdAt = 0L,
        customParams = mapOf("enable_thinking" to ParamValue.BooleanValue(true),
            "thinking_budget" to ParamValue.NumberValue(1024.0))
    )
    private val messages = listOf(
        ChatApiMessage.text("system", "core"),
        ChatApiMessage.text("assistant", "history"),
        ChatApiMessage.text("user", "current input"),
        ChatApiMessage.text("system", "post history"),
        ChatApiMessage.text("system", "mode requirement"),
        ChatApiMessage.text("assistant", "reply prefix:")
    )

    @Test
    fun `ordinary request ends with user and preserves every logical tail role and text`() {
        for (stream in listOf(false, true)) {
            val body = request(messages, stream = stream)
            val sent = body.getValue("messages").jsonArray
            assertEquals(listOf("system", "assistant", "user"), sent.roles())
            assertEquals(messages.first().content, sent.first().jsonObject["content"])
            val content = sent.last().jsonObject.getValue("content").jsonPrimitive.content
            assertTrue(content.startsWith("current input\n\n"))
            val tail = Json.parseToJsonElement(content.substringAfter("current input\n\n")).jsonArray
            assertEquals(messages.drop(3).map { it.role }, tail.roles())
            assertEquals(messages.drop(3).map { it.content }, tail.map { it.jsonObject["content"] })
            assertEquals(JsonPrimitive(true), body["enable_thinking"])
            assertEquals(JsonPrimitive(1024), body["thinking_budget"])
            assertFalse(body.containsKey("prefix"))
            assertFalse(body.containsKey("tools"))
            assertEquals(body, SiliconFlowChatTemplatePolicy.adaptRequest(body, model))
        }
    }

    @Test
    fun `all memory envelopes retain both confirmations without a native assistant tail`() {
        for (kind in listOf(AiTaskKind.MEMORY_EPISODE, AiTaskKind.MEMORY_HEAD,
            AiTaskKind.MEMORY_COMPRESSION, AiTaskKind.MEMORY_COMPRESSION_PLAN)) {
            val logical = AiTaskMessageAssembler.assemble(messages.take(1) + messages[2], AiTaskContext(kind))
            assertEquals("assistant", logical.last().role)
            val sent = request(logical).getValue("messages").jsonArray
            assertEquals(listOf("system", "assistant", "user", "assistant", "user"), sent.roles())
            val input = sent.last().jsonObject.getValue("content").jsonPrimitive.content
            val tail = Json.parseToJsonElement(input.substringAfter("current input\n\n")).jsonArray
            assertEquals(logical.takeLast(2).map { it.content }, tail.map { it.jsonObject["content"] })
            assertEquals(listOf("assistant", "assistant"), tail.roles())
        }
    }

    @Test
    fun `experimental request keeps tools and simulated prefix and canonicalizes text only content`() {
        val textParts = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "core one") })
            add(buildJsonObject { put("type", "text"); put("text", "core two") })
        }
        val logical = listOf(ChatApiMessage("system", textParts)) + messages.drop(1)
        val body = request(logical, experimental = true)
        val sent = body.getValue("messages").jsonArray
        assertEquals(listOf("system", "user"), sent.roles())
        assertEquals(JsonPrimitive("core one\n\ncore two"), sent.first().jsonObject["content"])
        assertTrue(sent.all { it.jsonObject.getValue("content") is JsonPrimitive })
        val context = body.getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "context" }
            .getValue("description").jsonPrimitive.content
        val restored = Json.parseToJsonElement(context + "\"}]").jsonArray
        assertEquals(logical.drop(1).map { it.role }, restored.roles())
        assertEquals(logical.drop(1).map { it.content }, restored.map { it.jsonObject["content"] })
        assertFalse(body.containsKey("prefix"))
        assertEquals(body, SiliconFlowChatTemplatePolicy.adaptRequest(body, model))
    }

    @Test
    fun `user only requests are unchanged and images remain native`() {
        val plain = request(messages.take(1) + messages[2])
        assertEquals(listOf("system", "user"), plain.getValue("messages").jsonArray.roles())
        assertEquals(messages[2].content, plain.getValue("messages").jsonArray.last().jsonObject["content"])
        val user = ChatApiMessage.withImage("user", "image input", "fixture-image")
        val body = request(messages.take(1) + user + messages.last())
        val parts = body.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonArray
        assertEquals(user.content.jsonArray, JsonArray(parts.dropLast(1)))
        val quoted = Json.parseToJsonElement(parts.last().jsonObject.getValue("text").jsonPrimitive.content).jsonArray
        assertEquals(messages.last().content, quoted.single().jsonObject["content"])
    }

    @Test
    fun `assistant only input keeps its text in a nonempty user instead of dropping it`() {
        val body = request(messages.take(2))
        val sent = body.getValue("messages").jsonArray
        assertEquals(listOf("system", "user"), sent.roles())
        val quoted = Json.parseToJsonElement(sent.last().jsonObject.getValue("content").jsonPrimitive.content).jsonArray
        assertEquals(messages[1].content, quoted.single().jsonObject["content"])
        assertEquals("assistant", quoted.single().jsonObject.getValue("role").jsonPrimitive.content)
    }

    @Test
    fun `provider and model scope do not change other endpoints or models`() {
        for (config in listOf(
            model.copy(baseUrl = "https://api.deepseek.com/v1"),
            model.copy(baseUrl = "https://api.deepseek.com/beta"),
            model.copy(baseUrl = "https://api.siliconflow.cn.other.test/v1"),
            model.copy(modelName = "other/model")
        )) {
            assertEquals(messages.map { it.role }, request(messages, config).getValue("messages").jsonArray.roles())
        }
        for (host in listOf("api.siliconflow.cn", "api.siliconflow.com")) {
            for (name in listOf("deepseek-ai/DeepSeek-V3.2", "Pro/deepseek-ai/DeepSeek-V3.2", "deepseek-ai/DeepSeek-V3.2-Exp")) {
                val config = model.copy(baseUrl = "https://$host/v1", modelName = name)
                assertEquals("user", request(messages, config).getValue("messages").jsonArray.roles().last())
            }
        }
    }

    private fun request(
        source: List<ChatApiMessage>, config: ModelConfig = model, stream: Boolean = true, experimental: Boolean = false
    ): JsonObject = Json.parseToJsonElement(StreamingChatService().buildRequestBody(
        source, config, stream, experimentalApiMode = experimental
    )).jsonObject

    private fun JsonArray.roles(): List<String> = map { it.jsonObject.getValue("role").jsonPrimitive.content }
}
