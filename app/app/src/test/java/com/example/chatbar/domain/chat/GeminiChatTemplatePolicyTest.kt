package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiChatTemplatePolicyTest {
    private val model = ModelConfig(
        id = "fixture",
        displayName = "fixture",
        modelName = "models/gemini-3.5-flash-lite",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/",
        apiKey = "",
        createdAt = 0L
    )
    private val messages = listOf(
        ChatApiMessage.text("system", "core"),
        ChatApiMessage.text("assistant", "opening"),
        ChatApiMessage.text("user", "earlier input"),
        ChatApiMessage.text("assistant", "earlier answer"),
        ChatApiMessage.text("assistant", "earlier continuation"),
        ChatApiMessage.withImage("user", "current input", "image-fixture"),
        ChatApiMessage.text("system", "post-history requirement"),
        ChatApiMessage.text("assistant", "reply prefix")
    )

    @Test
    fun `Gemini chat sends valid outer roles with original prefix tail and image retained`() {
        for (stream in listOf(false, true)) {
            val body = request(model, stream)
            val sent = body.getValue("messages").jsonArray
            assertEquals("gemini-3.5-flash-lite", body.getValue("model").jsonPrimitive.content)
            assertEquals(listOf("system", "user", "assistant", "user"), sent.roles())
            assertEquals(messages[0].content, sent[0].jsonObject["content"])
            val opening = sent[1].jsonObject.getValue("content").jsonPrimitive.content
            assertEquals(messages[1].content, Json.parseToJsonElement(opening.substringBefore("\n\n"))
                .jsonArray.single().jsonObject["content"])
            assertEquals("earlier input", opening.substringAfter("\n\n"))
            assertEquals(JsonPrimitive("earlier answer\n\nearlier continuation"), sent[2].jsonObject["content"])

            val parts = sent.last().jsonObject.getValue("content").jsonArray
            assertEquals(messages[5].content, JsonArray(parts.dropLast(1)))
            val tail = Json.parseToJsonElement(parts.last().jsonObject.getValue("text").jsonPrimitive.content).jsonArray
            assertEquals(listOf("system", "assistant"), tail.roles())
            assertEquals(messages.takeLast(2).map { it.content }, tail.map { it.jsonObject["content"] })
        }
    }

    @Test
    fun `legacy and compatible Google URLs use the same chat endpoint`() {
        val base = "https://generativelanguage.googleapis.com/v1beta/openai"
        assertEquals("$base/chat/completions", model.chatCompletionsUrl())
        assertEquals("$base/chat/completions", model.copy(baseUrl = "$base/").chatCompletionsUrl())
    }

    @Test
    fun `other providers keep their endpoint model ID and roles`() {
        val proxy = model.copy(baseUrl = "https://proxy.example/v1beta")
        val body = request(proxy, stream = true)
        assertEquals("https://proxy.example/v1beta/chat/completions", proxy.chatCompletionsUrl())
        assertEquals(proxy.modelName, body.getValue("model").jsonPrimitive.content)
        assertEquals(messages.map { it.role }, body.getValue("messages").jsonArray.roles())
    }

    @Test
    fun `experimental transport retains its protocol user and tool descriptions`() {
        val config = model.copy(baseUrl = "https://proxy.example/v1")
        val standard = request(config, stream = true, experimental = true)
        val gemini = request(model, stream = true, experimental = true)
        assertEquals(listOf("system", "user"), gemini.getValue("messages").jsonArray.roles())
        assertEquals(standard["messages"], gemini["messages"])
        assertEquals(standard["tools"], gemini["tools"])
    }

    private fun request(config: ModelConfig, stream: Boolean, experimental: Boolean = false) =
        Json.parseToJsonElement(
            StreamingChatService().buildRequestBody(messages, config, stream, experimentalApiMode = experimental)
        ).jsonObject

    private fun JsonArray.roles() = map { it.jsonObject.getValue("role").jsonPrimitive.content }
}
