package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.SaveSlot
import com.example.chatbar.data.local.entity.SaveSlotImageResource
import com.example.chatbar.data.local.entity.toSummary
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveSlotSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun oldSaveSlotPayloadDecodesWithPortableDefaults() {
        val raw = """
            {
              "id": "slot-1",
              "sessionId": "session-1",
              "name": "旧存档",
              "createdAt": 123
            }
        """.trimIndent()

        val slot = json.decodeFromString(SaveSlot.serializer(), raw)

        assertEquals(4, slot.schemaVersion)
        assertEquals(1, slot.nextTimelineTurn)
        assertEquals(1, slot.nextSourceTurnOrder)
        assertTrue(slot.sourceTurnTombstones.isEmpty())
        assertEquals(2000, slot.memoryLimitChars)
        assertTrue(slot.timelineTombstones.isEmpty())
        assertEquals(emptyList<String>(), slot.extraWorldBookIds)
        assertEquals(emptyMap<String, SaveSlotImageResource>(), slot.imageResources)
        assertTrue(slot.timedWorldInfo.isEmpty())
    }

    @Test
    fun saveSlotRoundTripsSettingsMemoryAndImages() {
        val message = ChatMessage(
            id = "message-1",
            sessionId = "session-1",
            role = MessageRole.USER,
            content = "带图消息",
            images = listOf("message-0-image-0"),
            createdAt = 10,
            updatedAt = 10
        )
        val slot = SaveSlot(
            id = "slot-1",
            sessionId = "session-1",
            name = "完整存档",
            modelId = "model-1",
            formatCardId = "format-1",
            longTermMemory = "长期记忆",
            longTermMemoryUpdatedThroughMessageId = "message-1",
            extraWorldBookIds = listOf("world-1"),
            chatBackground = "chat-background",
            messages = listOf(message),
            imageResources = mapOf(
                "chat-background" to SaveSlotImageResource("background.png", "YmFzZTY0"),
                "message-0-image-0" to SaveSlotImageResource("image.png", "aW1n")
            ),
            createdAt = 20
        )

        val decoded = json.decodeFromString(
            SaveSlot.serializer(),
            json.encodeToString(SaveSlot.serializer(), slot)
        )

        assertEquals(slot, decoded)
    }

    @Test
    fun saveSlotSummaryDoesNotRetainMessagePayloads() {
        val slot = SaveSlot(
            id = "slot-1",
            sessionId = "session-1",
            name = "长记录",
            messages = List(3) { index -> message(index, "x".repeat(10_000)) },
            createdAt = 20
        )

        val summary = slot.toSummary()

        assertEquals("slot-1", summary.id)
        assertEquals(3, summary.messageCount)
    }

    @Test
    fun largeSaveSlotStreamsWithoutIntermediateJsonString() {
        val content = "长消息".repeat(512)
        val slot = SaveSlot(
            id = "slot-large",
            sessionId = "session-1",
            name = "超长记录",
            messages = List(2_000) { index -> message(index, content) },
            createdAt = 20
        )
        val output = ByteArrayOutputStream()

        SaveSlotJsonTransfer.write(slot, output)
        val decoded = SaveSlotJsonTransfer.read(ByteArrayInputStream(output.toByteArray()))

        assertEquals(2_000, decoded.messages.size)
        assertEquals(content, decoded.messages.last().content)
    }

    private fun message(index: Int, content: String) = ChatMessage(
        id = "message-$index",
        sessionId = "session-1",
        role = MessageRole.USER,
        content = content,
        createdAt = index.toLong(),
        updatedAt = index.toLong()
    )
}
