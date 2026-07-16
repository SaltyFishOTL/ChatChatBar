package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestMemoryPolicyTest {
    private val archive = "【ARCHIVE｜历史档案】\n塞尔达与用户抵达便利店。"

    @Test
    fun `archive remains a dedicated system message in serialized request`() {
        val messages = listOfNotNull(
            ChatApiMessage.text("system", "固定设定"),
            ChatApiMessage.text("assistant", "旧消息"),
            ChatRequestMemoryPolicy.archiveMessage(archive),
            ChatApiMessage.text("system", "【HEAD｜当前状态】\n当前状态"),
            ChatApiMessage.text("user", "继续")
        )

        ChatRequestMemoryPolicy.requireArchiveIncluded(messages, archive)
        val model = ModelConfig(
            id = "model",
            displayName = "Model",
            baseUrl = "https://example.com/v1",
            apiKey = "key",
            modelName = "model-name",
            createdAt = 0L
        )
        val body = Json.parseToJsonElement(
            StreamingChatService().buildRequestBody(
                messages = messages,
                modelConfig = model,
                stream = true
            )
        ).jsonObject
        val archiveMessage = body.getValue("messages").jsonArray.single { item ->
            item.jsonObject["content"]?.jsonPrimitive?.content?.contains("【ARCHIVE｜历史档案】") == true
        }.jsonObject

        assertEquals("system", archiveMessage.getValue("role").jsonPrimitive.content)
        assertEquals(archive, archiveMessage.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `expected archive missing from head-only request is rejected`() {
        val messages = listOf(ChatApiMessage.text("system", "【HEAD｜当前状态】\n当前状态"))

        val failure = runCatching {
            ChatRequestMemoryPolicy.requireArchiveIncluded(messages, archive)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message?.contains("Archive未写入最终请求") == true)
    }
}
