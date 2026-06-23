package com.example.chatbar.domain.chat

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
