package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.FishAudioVoiceBinding
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CharacterFishAudioSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `old character JSON defaults to no Fish voice`() {
        val character = json.decodeFromString(
            CharacterInfo.serializer(),
            """{"id":"c1","name":"旧人物"}"""
        )

        assertNull(character.fishAudioVoice)
    }

    @Test
    fun `character package v8 preserves private voice snapshot`() {
        val binding = FishAudioVoiceBinding(
            referenceId = "voice-1",
            title = "私有音色",
            authorId = "author-1",
            authorName = "作者",
            sampleAudio = "https://example.test/sample.mp3",
            visibility = "private",
            languages = listOf("zh"),
            tags = listOf("soft")
        )
        val packageData = CharacterCardPackage(
            card = PackagedCharacterCard(
                name = "角色卡",
                characters = listOf(
                    PackagedCharacter(name = "人物", fishAudioVoice = binding)
                )
            )
        )

        val decoded = json.decodeFromString(
            CharacterCardPackage.serializer(),
            json.encodeToString(CharacterCardPackage.serializer(), packageData)
        )

        assertEquals(8, decoded.schemaVersion)
        assertEquals(binding, decoded.card.characters.single().fishAudioVoice)
    }
}
