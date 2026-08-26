package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.SaveSlot
import com.example.chatbar.data.local.entity.SaveSlotImageResource
import com.example.chatbar.data.local.entity.SaveSlotAudioResource
import com.example.chatbar.data.local.entity.FishAudioVoiceBinding
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
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
        assertEquals(null, slot.audiobookModeEnabled)
        assertEquals(null, slot.voiceLanguage)
        assertEquals(300, slot.replyLength)
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
            audiobookModeEnabled = true,
            voiceLanguage = "英语",
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
    fun oldSaveSlotStringReplyLengthMigratesAndNewSlotsUseSchemaV7() {
        val oldSlot = json.decodeFromString(
            SaveSlot.serializer(),
            """
            {
              "id":"slot-old-length",
              "sessionId":"session-1",
              "name":"旧字数",
              "replyLength":"500字中篇",
              "createdAt":123
            }
            """.trimIndent()
        )
        val newSlot = SaveSlot.create("session-1", "新存档")

        assertEquals(500, oldSlot.replyLength)
        assertEquals(7, newSlot.schemaVersion)
        assertEquals(300, newSlot.replyLength)
    }

    @Test
    fun saveSlotV7RoundTripsEmbeddedVoiceAudio() {
        val voice = GeneratedVoiceMessage.create(
            sessionId = "session-1",
            messageId = "message-1",
            messageVersionId = "version-1",
            anchorId = "anchor-1",
            sourceOrder = 10,
            sourceSegmentKind = "DIALOGUE",
            sourceSpeakerName = "林雾",
            sourceText = "你好",
            synthesisText = "Hello",
            taggedText = "[happy]Hello",
            characterId = "character-1",
            characterName = "林雾",
            voice = FishAudioVoiceBinding(
                referenceId = "reference-1",
                title = "温柔女声",
                visibility = "private"
            ),
            fishModelId = "s2.1-pro-free",
            audioPath = "voice-resource",
            durationMs = 1_200,
            byteLength = 3
        )
        val slot = SaveSlot.create("session-1", "语音存档").copy(
            voiceMessages = listOf(voice),
            audioResources = mapOf(
                "voice-resource" to SaveSlotAudioResource("voice.mp3", "bXAz")
            )
        )

        val decoded = SaveSlotJsonTransfer.read(
            ByteArrayInputStream(
                ByteArrayOutputStream().also { SaveSlotJsonTransfer.write(slot, it) }.toByteArray()
            )
        )

        assertEquals(7, decoded.schemaVersion)
        assertEquals(voice, decoded.voiceMessages.single())
        assertEquals("Hello", decoded.voiceMessages.single().effectiveSynthesisText)
        assertEquals("bXAz", decoded.audioResources.getValue("voice-resource").data)
    }

    @Test
    fun oldGeneratedVoicePayloadUsesSourceTextAsSynthesisText() {
        val voice = json.decodeFromString(
            GeneratedVoiceMessage.serializer(),
            """
            {
              "id":"voice-1",
              "sessionId":"session-1",
              "messageId":"message-1",
              "sourceOrder":1,
              "sourceSegmentKind":"DIALOGUE",
              "sourceSpeakerName":"林雾",
              "sourceText":"你好",
              "taggedText":"[happy]你好",
              "characterId":"character-1",
              "characterName":"林雾",
              "voice":{"referenceId":"reference-1","title":"音色"},
              "fishModelId":"s2.1-pro-free",
              "audioPath":"voice.mp3",
              "durationMs":1000,
              "byteLength":10,
              "createdAt":1,
              "updatedAt":1
            }
            """.trimIndent()
        )

        assertEquals(null, voice.synthesisText)
        assertEquals(null, voice.messageVersionId)
        assertEquals("你好", voice.effectiveSynthesisText)
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
