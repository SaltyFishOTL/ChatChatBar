package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.FishAudioVoiceBinding
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.local.entity.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceMessageVersionPolicyTest {
    @Test
    fun `switching versions shows only voices owned by selected version`() {
        val first = message(currentIndex = 0)
        val second = first.copy(
            content = first.alternatives[1],
            currentAlternativeIndex = 1,
            currentAlternativeVersionId = "v2"
        )
        val voices = listOf(
            voice(id = "voice-1", versionId = "v1", sourceText = "第一句", anchorId = "a1"),
            voice(id = "voice-2", versionId = "v2", sourceText = "第二句", anchorId = "a2")
        )

        assertEquals(
            listOf("voice-1"),
            VoiceMessageVersionPolicy.visibleVoices(first, voices).map { it.id }
        )
        assertEquals(
            listOf("voice-2"),
            VoiceMessageVersionPolicy.visibleVoices(second, voices).map { it.id }
        )
        assertEquals(
            listOf("voice-1"),
            VoiceMessageVersionPolicy.visibleVoices(first, voices).map { it.id }
        )
    }

    @Test
    fun `anchor reconciliation changes only matching message version`() {
        val voices = listOf(
            voice(id = "voice-1", versionId = "v1", sourceText = "第一句", anchorId = "a1"),
            voice(id = "voice-2", versionId = "v2", sourceText = "第二句", anchorId = "a2")
        )

        val updated = VoiceMessageVersionPolicy.applyAnchorReplacements(
            voices = voices,
            messageId = "message-1",
            messageVersionId = "v2",
            replacements = mapOf("a2" to null),
            updatedAt = 20
        )

        assertEquals("a1", updated[0].anchorId)
        assertNull(updated[1].anchorId)
        assertEquals(1, updated[0].updatedAt)
        assertEquals(20, updated[1].updatedAt)
    }

    @Test
    fun `legacy voice is inferred from source text when old anchor was orphaned`() {
        val message = message(currentIndex = 1)
        val legacyVoice = voice(
            id = "legacy",
            versionId = null,
            sourceText = "第一句",
            anchorId = null
        )
        val legacyState = VoiceAnchorPolicy.initialState(
            messageId = message.id,
            content = message.alternatives[1],
            includeNarration = true
        )

        assertEquals(
            "v1",
            VoiceMessageVersionPolicy.inferLegacyVersionId(
                message,
                legacyVoice,
                legacyState
            )
        )
    }

    @Test
    fun `legacy matching anchor binds voice to state version`() {
        val message = message(
            currentIndex = 0,
            alternatives = listOf(
                "<n=\"甲\"/>[相同]()\n[版本一]()",
                "<n=\"甲\"/>[相同]()\n[版本二]()"
            )
        )
        val legacyState = VoiceAnchorPolicy.initialState(
            messageId = message.id,
            content = message.alternatives[1],
            includeNarration = true
        )
        val legacyVoice = voice(
            id = "legacy",
            versionId = null,
            sourceText = "相同",
            anchorId = legacyState.anchors.first().id
        )

        assertEquals(
            "v2",
            VoiceMessageVersionPolicy.inferLegacyVersionId(
                message,
                legacyVoice,
                legacyState
            )
        )
    }

    private fun message(
        currentIndex: Int,
        alternatives: List<String> = listOf(
            "<n=\"甲\"/>[第一句]()",
            "<n=\"甲\"/>[第二句]()"
        )
    ): ChatMessage = ChatMessage(
        id = "message-1",
        sessionId = "session-1",
        role = MessageRole.ASSISTANT,
        content = alternatives[currentIndex],
        alternatives = alternatives,
        alternativeVersionIds = listOf("v1", "v2"),
        currentAlternativeIndex = currentIndex,
        currentAlternativeVersionId = if (currentIndex == 0) "v1" else "v2",
        createdAt = 1,
        updatedAt = 1
    )

    private fun voice(
        id: String,
        versionId: String?,
        sourceText: String,
        anchorId: String?
    ): GeneratedVoiceMessage = GeneratedVoiceMessage(
        id = id,
        sessionId = "session-1",
        messageId = "message-1",
        messageVersionId = versionId,
        anchorId = anchorId,
        sourceOrder = 0,
        sourceSegmentKind = "DIALOGUE",
        sourceSpeakerName = "甲",
        sourceText = sourceText,
        taggedText = sourceText,
        characterId = "character-1",
        characterName = "甲",
        voice = FishAudioVoiceBinding("reference-1", "音色"),
        fishModelId = "s2.1-pro-free",
        audioPath = "$id.mp3",
        durationMs = 1_000,
        byteLength = 10,
        createdAt = 1,
        updatedAt = 1
    )
}
