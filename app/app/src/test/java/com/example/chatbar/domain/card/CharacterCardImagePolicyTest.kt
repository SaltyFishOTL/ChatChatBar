package com.example.chatbar.domain.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CharacterCardImagePolicyTest {
    @Test
    fun nullSessionBackgroundKeepsInheritingCardBackground() {
        val result = CharacterCardImagePolicy.sessionBackgroundOverrideAfterCardBackgroundChange(
            sessionBackground = null,
            previousCardBackground = "old.png",
            newCardBackground = "new.png"
        )

        assertNull(result)
    }

    @Test
    fun matchingSessionBackgroundMovesWithCardBackground() {
        val result = CharacterCardImagePolicy.sessionBackgroundOverrideAfterCardBackgroundChange(
            sessionBackground = "old.png",
            previousCardBackground = "old.png",
            newCardBackground = "new.png"
        )

        assertEquals("new.png", result)
    }

    @Test
    fun customSessionBackgroundIsPreserved() {
        val result = CharacterCardImagePolicy.sessionBackgroundOverrideAfterCardBackgroundChange(
            sessionBackground = "custom.png",
            previousCardBackground = "old.png",
            newCardBackground = "new.png"
        )

        assertEquals("custom.png", result)
    }
}
