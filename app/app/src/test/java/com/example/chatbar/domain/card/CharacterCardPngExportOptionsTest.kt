package com.example.chatbar.domain.card

import org.junit.Assert.assertEquals
import org.junit.Test

class CharacterCardPngExportOptionsTest {
    @Test
    fun normalized_clampsEachExportControlIndependently() {
        val normalized = CharacterCardPngExportOptions(
            gradientHeight = -1f,
            gradientStrength = 2f,
            logoScale = -1f,
            titleScale = 2f,
            cropCenterX = -1f,
            cropCenterY = 2f,
            cropZoom = 20f
        ).normalized()

        assertEquals(0.25f, normalized.gradientHeight, 0f)
        assertEquals(0.9f, normalized.gradientStrength, 0f)
        assertEquals(0.07f, normalized.logoScale, 0f)
        assertEquals(0.08f, normalized.titleScale, 0f)
        assertEquals(0f, normalized.cropCenterX, 0f)
        assertEquals(1f, normalized.cropCenterY, 0f)
        assertEquals(6f, normalized.cropZoom, 0f)
    }
}
