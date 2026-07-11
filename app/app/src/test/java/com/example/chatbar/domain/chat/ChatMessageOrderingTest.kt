package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.GeneratedImageCharacterPrompt
import com.example.chatbar.data.local.entity.GeneratedImageMetadata
import com.example.chatbar.data.local.entity.MESSAGE_ORDER_STEP
import com.example.chatbar.data.local.entity.MessageRole
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatMessageOrderingTest {
    @Test
    fun `generated image inserts after past anchor instead of bottom`() {
        val messages = listOf(
            message("user-1", MessageRole.USER, "old", orderKey = 1 * MESSAGE_ORDER_STEP),
            message("assistant-1", MessageRole.ASSISTANT, "anchor", orderKey = 2 * MESSAGE_ORDER_STEP),
            message("user-2", MessageRole.USER, "later", orderKey = 3 * MESSAGE_ORDER_STEP),
            message("assistant-2", MessageRole.ASSISTANT, "latest", orderKey = 4 * MESSAGE_ORDER_STEP)
        )
        val image = message("image-1", MessageRole.ASSISTANT, "", images = listOf("/tmp/image.png"))

        val result = ChatMessageOrdering.insertGeneratedImageAfter(messages, image, "assistant-1")

        assertEquals(
            listOf("user-1", "assistant-1", "image-1", "user-2", "assistant-2"),
            result.sortedWith(ChatMessage.TimelineComparator).map { it.id }
        )
        assertEquals("assistant-1", result.first { it.id == "image-1" }.generatedFromMessageId)
    }

    @Test
    fun `multiple generated images append under same anchor`() {
        val firstImage = message(
            "image-1",
            MessageRole.ASSISTANT,
            "",
            images = listOf("/tmp/1.png"),
            generatedFromMessageId = "assistant-1",
            orderKey = 3 * MESSAGE_ORDER_STEP
        )
        val messages = listOf(
            message("assistant-1", MessageRole.ASSISTANT, "anchor", orderKey = 2 * MESSAGE_ORDER_STEP),
            firstImage,
            message("user-2", MessageRole.USER, "later", orderKey = 4 * MESSAGE_ORDER_STEP)
        )
        val secondImage = message("image-2", MessageRole.ASSISTANT, "", images = listOf("/tmp/2.png"))

        val result = ChatMessageOrdering.insertGeneratedImageAfter(messages, secondImage, "assistant-1")

        assertEquals(
            listOf("assistant-1", "image-1", "image-2", "user-2"),
            result.sortedWith(ChatMessage.TimelineComparator).map { it.id }
        )
    }

    @Test
    fun `old message json decodes with timeline defaults`() {
        val json = """
            {
              "id": "old",
              "sessionId": "session",
              "role": "ASSISTANT",
              "content": "hello",
              "images": [],
              "alternatives": [],
              "currentAlternativeIndex": 0,
              "reasoningContent": null,
              "createdAt": 42,
              "updatedAt": 43
            }
        """.trimIndent()

        val decoded = Json.decodeFromString(ChatMessage.serializer(), json)

        assertNull(decoded.generatedFromMessageId)
        assertEquals(42 * MESSAGE_ORDER_STEP, decoded.orderKey)
        assertEquals(emptyList<GeneratedImageMetadata>(), decoded.generatedImageMetadata)
    }

    @Test
    fun `generated image metadata survives json round trip`() {
        val metadata = GeneratedImageMetadata(
            imagePath = "/tmp/image.png",
            baseCaption = "1girl, outdoors",
            characterPrompts = listOf(GeneratedImageCharacterPrompt("alice", 0.25f, 0.5f)),
            negativePrompt = "lowres",
            sizePreset = "PORTRAIT",
            width = 832,
            height = 1216
        )
        val original = message(
            "image-1",
            MessageRole.ASSISTANT,
            "",
            images = listOf(metadata.imagePath)
        ).copy(generatedImageMetadata = listOf(metadata))

        val encoded = Json.encodeToString(ChatMessage.serializer(), original)
        val decoded = Json.decodeFromString(ChatMessage.serializer(), encoded)

        assertEquals(listOf(metadata), decoded.generatedImageMetadata)
    }

    private fun message(
        id: String,
        role: MessageRole,
        content: String,
        images: List<String> = emptyList(),
        generatedFromMessageId: String? = null,
        orderKey: Long = 0L
    ) = ChatMessage(
        id = id,
        sessionId = "session",
        role = role,
        content = content,
        images = images,
        generatedFromMessageId = generatedFromMessageId,
        createdAt = id.filter(Char::isDigit).toLongOrNull() ?: 1L,
        updatedAt = 1L,
        orderKey = orderKey
    )
}
