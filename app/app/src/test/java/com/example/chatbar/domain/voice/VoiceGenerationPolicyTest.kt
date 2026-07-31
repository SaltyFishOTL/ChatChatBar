package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.FishAudioVoiceBinding
import com.example.chatbar.domain.chat.RoleplaySegmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceGenerationPolicyTest {
    @Test
    fun `audiobook mode skips AI tag generation`() {
        assertFalse(VoiceGenerationPolicy.shouldGenerateAiTags(audiobookEnabled = true))
        assertTrue(VoiceGenerationPolicy.shouldGenerateAiTags(audiobookEnabled = false))
    }

    @Test
    fun `session audiobook override wins over global setting`() {
        assertTrue(
            VoiceGenerationPolicy.audiobookEnabled(
                session(audiobookModeEnabled = true),
                AppSettings(audiobookModeEnabled = false)
            )
        )
        assertFalse(
            VoiceGenerationPolicy.audiobookEnabled(
                session(audiobookModeEnabled = false),
                AppSettings(audiobookModeEnabled = true)
            )
        )
        assertTrue(
            VoiceGenerationPolicy.audiobookEnabled(
                session(audiobookModeEnabled = null),
                AppSettings(audiobookModeEnabled = true)
            )
        )
    }

    @Test
    fun `segmented audiobook includes narration while standard mode excludes it`() {
        val content = "雨落下来。\n<n=\"甲\"/>[走吧]()"

        val standard = VoiceGenerationPolicy.generationSegments(
            content,
            audiobookEnabled = false,
            segmentedBubblesEnabled = true
        )
        val audiobook = VoiceGenerationPolicy.generationSegments(
            content,
            audiobookEnabled = true,
            segmentedBubblesEnabled = true
        )

        assertEquals(listOf(RoleplaySegmentKind.DIALOGUE), standard.map { it.kind })
        assertEquals(
            listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.DIALOGUE),
            audiobook.map { it.kind }
        )
    }

    @Test
    fun `non segmented audiobook creates one whole message target`() {
        val segments = VoiceGenerationPolicy.generationSegments(
            "雨落下来。\n<n=\"甲\"/>[走吧]()",
            audiobookEnabled = true,
            segmentedBubblesEnabled = false
        )

        assertEquals(1, segments.size)
        assertEquals(VoiceSourceScope.WHOLE_MESSAGE, segments.single().sourceScope)
        assertEquals("雨落下来。\n走吧", segments.single().spokenText)
    }

    @Test
    fun `single character card automatically supplies narrator voice`() {
        val character = character("one", "甲")
        val card = card(character)
        val narration = narration()

        assertFalse(VoiceGenerationPolicy.requiresNarratorSelection(card, listOf(narration)))
        assertSame(character, VoiceGenerationPolicy.resolveCharacter(card, narration, null))
    }

    @Test
    fun `multi character narration requires selected bound character`() {
        val first = character("one", "甲")
        val second = character("two", "乙")
        val card = card(first, second)
        val narration = narration()

        assertTrue(VoiceGenerationPolicy.requiresNarratorSelection(card, listOf(narration)))
        assertNull(VoiceGenerationPolicy.resolveCharacter(card, narration, null))
        assertSame(second, VoiceGenerationPolicy.resolveCharacter(card, narration, second.id))
    }

    @Test
    fun `dialogue keeps speaker based voice in multi character card`() {
        val first = character("one", "甲")
        val second = character("two", "乙")
        val card = card(first, second)
        val dialogue = narration().copy(
            kind = RoleplaySegmentKind.DIALOGUE,
            speakerName = "乙"
        )

        assertSame(second, VoiceGenerationPolicy.resolveCharacter(card, dialogue, first.id))
    }

    @Test
    fun `regeneration resolves latest binding for original character identity`() {
        val current = character("one", "甲").copy(
            fishAudioVoice = FishAudioVoiceBinding(
                referenceId = "voice-current",
                title = "当前音色"
            )
        )

        val resolved = VoiceGenerationPolicy.resolveRegenerationCharacter(
            card = card(current),
            characterId = "one"
        )

        assertSame(current, resolved)
        assertEquals("voice-current", resolved?.fishAudioVoice?.referenceId)
    }

    @Test
    fun `regeneration never falls back when current character binding is unavailable`() {
        val unbound = character("one", "甲").copy(fishAudioVoice = null)

        assertNull(
            VoiceGenerationPolicy.resolveRegenerationCharacter(
                card = card(unbound, character("two", "乙")),
                characterId = "one"
            )
        )
        assertNull(
            VoiceGenerationPolicy.resolveRegenerationCharacter(
                card = card(character("two", "乙")),
                characterId = "one"
            )
        )
    }

    @Test
    fun `voice generation uses current supported Fish model`() {
        assertEquals(
            FishAudioTtsModels.S1,
            VoiceGenerationPolicy.resolveFishModelId(FishAudioTtsModels.S1)
        )
        assertEquals(
            FishAudioTtsModels.S2_1_PRO_FREE,
            VoiceGenerationPolicy.resolveFishModelId("stale-model")
        )
    }

    private fun narration() = CurrentVoiceSegment(
        segmentIndex = 0,
        kind = RoleplaySegmentKind.NARRATION,
        speakerName = null,
        spokenText = "旁白",
        start = 0,
        endExclusive = 2
    )

    private fun character(id: String, name: String) = CharacterInfo(
        id = id,
        name = name,
        fishAudioVoice = FishAudioVoiceBinding(
            referenceId = "voice-$id",
            title = "$name 音色"
        )
    )

    private fun card(vararg characters: CharacterInfo) = CharacterCard(
        id = "card",
        name = "角色卡",
        characters = characters.toList(),
        createdAt = 1,
        updatedAt = 1
    )

    private fun session(audiobookModeEnabled: Boolean?) = ChatSession(
        id = "session",
        characterCardId = "card",
        title = "会话",
        audiobookModeEnabled = audiobookModeEnabled,
        createdAt = 1,
        updatedAt = 1
    )
}
