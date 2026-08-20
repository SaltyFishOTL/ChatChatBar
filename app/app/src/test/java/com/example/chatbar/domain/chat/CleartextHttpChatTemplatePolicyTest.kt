package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class CleartextHttpChatTemplatePolicyTest {
    private val messages = listOf(
        ChatApiMessage.text("system", "固定设定"),
        ChatApiMessage.text("user", "较早用户消息"),
        ChatApiMessage.text("assistant", "较早助手消息"),
        ChatApiMessage.text("system", "动态资料"),
        ChatApiMessage.text("system", "尾部规则"),
        ChatApiMessage.text("user", "当前用户消息")
    )

    @Test
    fun `enabled cleartext http keeps first system and rewrites later systems`() {
        val serialized = serializedMessages(
            allowCleartextHttp = true,
            baseUrl = "http://127.0.0.1:8080/v1"
        )

        assertEquals(
            listOf("system", "user", "assistant", "assistant", "assistant", "user"),
            serialized.map { it.first }
        )
        assertEquals(messages.map { it.content.jsonPrimitive.content }, serialized.map { it.second })
    }

    @Test
    fun `cleartext http merges trailing requirements into current user`() {
        val requirements = "格式要求"
        val source = listOf(
            ChatApiMessage.text("system", "$requirements\n\n固定设定"),
            ChatApiMessage.text("user", "当前用户消息"),
            ChatApiMessage.text("system", requirements)
        )

        val adapted = CleartextHttpChatTemplatePolicy.adaptMessages(
            messages = source,
            allowCleartextHttp = true,
            baseUrl = "http://127.0.0.1:8080/v1"
        )

        assertEquals(listOf("system", "user"), adapted.map { it.role })
        assertEquals(source.first().content, adapted.first().content)
        assertEquals("当前用户消息\n\n格式要求", adapted.last().content.jsonPrimitive.content)
    }

    @Test
    fun `cleartext http appends trailing requirements to multimodal user content`() {
        val source = listOf(
            ChatApiMessage.text("system", "固定设定"),
            ChatApiMessage.withImage("user", "当前用户消息", "image-base64"),
            ChatApiMessage.text("system", "格式要求")
        )

        val adapted = CleartextHttpChatTemplatePolicy.adaptMessages(
            messages = source,
            allowCleartextHttp = true,
            baseUrl = "http://127.0.0.1:8080/v1"
        )

        assertEquals(listOf("system", "user"), adapted.map { it.role })
        val contentParts = adapted.last().content.jsonArray
        assertEquals(3, contentParts.size)
        assertEquals("当前用户消息", contentParts[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("image_url", contentParts[1].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("格式要求", contentParts[2].jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `cleartext http rewrites unmergeable trailing system as user`() {
        val source = listOf(
            ChatApiMessage.text("system", "固定设定"),
            ChatApiMessage.text("assistant", "已有助手消息"),
            ChatApiMessage.text("system", "尾部要求")
        )

        val adapted = CleartextHttpChatTemplatePolicy.adaptMessages(
            messages = source,
            allowCleartextHttp = true,
            baseUrl = "http://127.0.0.1:8080/v1"
        )

        assertEquals(listOf("system", "assistant", "user"), adapted.map { it.role })
        assertEquals("尾部要求", adapted.last().content.jsonPrimitive.content)
    }

    @Test
    fun `https keeps original roles when cleartext mode is enabled`() {
        val serialized = serializedMessages(
            allowCleartextHttp = true,
            baseUrl = "https://example.com/v1"
        )

        assertEquals(messages.map(ChatApiMessage::role), serialized.map { it.first })
    }

    @Test
    fun `disabled cleartext mode keeps original roles for http model`() {
        val serialized = serializedMessages(
            allowCleartextHttp = false,
            baseUrl = "http://127.0.0.1:8080/v1"
        )

        assertEquals(messages.map(ChatApiMessage::role), serialized.map { it.first })
    }

    private fun serializedMessages(
        allowCleartextHttp: Boolean,
        baseUrl: String
    ): List<Pair<String, String>> {
        val model = ModelConfig(
            id = "model",
            displayName = "Model",
            baseUrl = baseUrl,
            apiKey = "key",
            modelName = "model-name",
            createdAt = 0L
        )
        val body = Json.parseToJsonElement(
            StreamingChatService { allowCleartextHttp }.buildRequestBody(
                messages = messages,
                modelConfig = model,
                stream = true
            )
        ).jsonObject

        return body.getValue("messages").jsonArray.map { element ->
            val message = element.jsonObject
            message.getValue("role").jsonPrimitive.content to
                message.getValue("content").jsonPrimitive.content
        }
    }
}
