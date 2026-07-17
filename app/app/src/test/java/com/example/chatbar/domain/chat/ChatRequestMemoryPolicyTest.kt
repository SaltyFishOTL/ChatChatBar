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
    private val worldBookAndRag = "【世界书】\n世界设定\n\n【RAG｜召回资料】\n召回内容"
    private val head = "【HEAD｜当前状态】\n当前状态"

    @Test
    fun `serialized request keeps world book rag archive head order`() {
        val dynamicMessages = ChatRequestMemoryPolicy.orderedDynamicMessages(
            worldBookAndRag = worldBookAndRag,
            archive = archive,
            headAndTimeline = head
        )
        val messages = buildList {
            add(ChatApiMessage.text("system", "固定设定"))
            add(ChatApiMessage.text("assistant", "旧消息"))
            addAll(dynamicMessages)
            add(ChatApiMessage.text("user", "继续"))
        }

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
        val serialized = body.getValue("messages").jsonArray.map { item ->
            item.jsonObject.getValue("content").jsonPrimitive.content
        }
        val worldBookAndRagIndex = serialized.indexOf(worldBookAndRag)
        val archiveIndex = serialized.indexOf(archive)
        val headIndex = serialized.indexOf(head)

        assertEquals(listOf(worldBookAndRag, archive, head), dynamicMessages.map { it.content.jsonPrimitive.content })
        assertTrue(worldBookAndRagIndex >= 0)
        assertTrue(archiveIndex >= 0)
        assertTrue(headIndex >= 0)
        assertTrue(worldBookAndRagIndex < archiveIndex)
        assertTrue(archiveIndex < headIndex)
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
