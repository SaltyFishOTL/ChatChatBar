package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class CharacterSpeakerNamePolicyTest {
    @Test
    fun normalizeUnique_trimsAndSuffixesDuplicatesDeterministically() {
        val result = CharacterSpeakerNamePolicy.normalizeUnique(
            listOf(
                character("1", " Alice "),
                character("2", "alice"),
                character("3", "Alice (2)"),
                character("4", "  ")
            )
        )

        assertEquals(listOf("Alice", "alice (2)", "Alice (2) (2)", ""), result.map { it.name })
        assertEquals(result, CharacterSpeakerNamePolicy.normalizeUnique(result))
    }

    @Test
    fun duplicateNames_ignoresBlankAndMatchesCaseInsensitively() {
        val duplicates = CharacterSpeakerNamePolicy.duplicateNames(
            listOf(character("1", "灯"), character("2", "  "), character("3", " 灯 "))
        )

        assertEquals(listOf("灯"), duplicates)
    }

    private fun character(id: String, name: String): CharacterInfo = CharacterInfo(id = id, name = name)
}
