package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.FishAudioVoiceBinding
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePlaybackQueuePolicyTest {
    @Test
    fun `selected voice and later visible voices form playback sequence`() {
        val voices = listOf(voice("first"), voice("second"), voice("third"))

        val sequence = VoicePlaybackQueuePolicy.sequenceFrom(
            selected = voices[1],
            orderedVisibleVoices = voices
        )

        assertEquals(listOf("second", "third"), sequence.map { it.id })
    }

    @Test
    fun `selected voice plays alone when absent from visible sequence`() {
        val selected = voice("selected")

        val sequence = VoicePlaybackQueuePolicy.sequenceFrom(
            selected = selected,
            orderedVisibleVoices = listOf(voice("other"))
        )

        assertEquals(listOf("selected"), sequence.map { it.id })
    }

    private fun voice(id: String) = GeneratedVoiceMessage(
        id = id,
        sessionId = "session",
        messageId = "message",
        sourceOrder = 0L,
        sourceSegmentKind = "DIALOGUE",
        sourceSpeakerName = "角色",
        sourceText = id,
        taggedText = id,
        characterId = "character",
        characterName = "角色",
        voice = FishAudioVoiceBinding(referenceId = "voice", title = "测试音色"),
        fishModelId = "s2-pro",
        audioPath = "$id.mp3",
        durationMs = 1_000L,
        byteLength = 1L,
        createdAt = 1L,
        updatedAt = 1L
    )
}
