package com.example.chatbar.domain.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorTest {
    @Test
    fun defaultColor_matchesLegacyPrimary() {
        assertEquals(0xFF2F8E7B.toInt(), DefaultThemeColorHsv.toOpaqueArgb())
    }

    @Test
    fun normalization_wrapsHueAndClampsComponents() {
        val normalized = ThemeColorHsv(
            hueDegrees = -30f,
            saturation = 2f,
            value = -1f
        ).normalized()

        assertEquals(330f, normalized.hueDegrees)
        assertEquals(1f, normalized.saturation)
        assertEquals(0f, normalized.value)

        val nonFinite = ThemeColorHsv(
            hueDegrees = Float.NaN,
            saturation = Float.POSITIVE_INFINITY,
            value = Float.NEGATIVE_INFINITY
        ).normalized()
        assertEquals(DefaultThemeColorHsv, nonFinite)
    }

    @Test
    fun history_recordsOnlyReplacedColorsNewestFirst() {
        val first = color(10f)
        val second = color(80f)
        val third = color(160f)

        val afterSecond = ThemeColorHistoryPolicy.update(first, second, emptyList())
        val afterThird = ThemeColorHistoryPolicy.update(second, third, afterSecond)

        assertEquals(listOf(second.rgbKey(), first.rgbKey()), afterThird.map { it.rgbKey() })
    }

    @Test
    fun history_removesSelectedColorAndDeduplicatesByRgb() {
        val first = color(10f)
        val second = color(80f)
        val duplicateFirst = first.copy(hueDegrees = first.hueDegrees + 360f)

        val updated = ThemeColorHistoryPolicy.update(
            current = second,
            next = first,
            history = listOf(duplicateFirst, color(140f), second)
        )

        assertEquals(second.rgbKey(), updated.first().rgbKey())
        assertTrue(updated.none { it.rgbKey() == first.rgbKey() })
        assertEquals(updated.map { it.rgbKey() }.distinct(), updated.map { it.rgbKey() })
    }

    @Test
    fun history_excludesDefaultAndKeepsAtMostFive() {
        val next = color(300f)
        val history = (0..8).map { color(it * 30f) } + DefaultThemeColorHsv

        val updated = ThemeColorHistoryPolicy.update(
            current = DefaultThemeColorHsv,
            next = next,
            history = history
        )

        assertTrue(updated.size <= MAX_THEME_COLOR_HISTORY_SIZE)
        assertTrue(updated.none { it.rgbKey() == DefaultThemeColorHsv.rgbKey() })
        assertTrue(updated.none { it.rgbKey() == next.rgbKey() })
    }

    @Test
    fun applyingSameRgbDoesNotAddCurrentColor() {
        val current = color(120f)
        val history = listOf(color(20f), color(40f))

        val updated = ThemeColorHistoryPolicy.update(
            current = current,
            next = current.copy(hueDegrees = current.hueDegrees + 360f),
            history = history
        )

        assertEquals(history.map { it.rgbKey() }, updated.map { it.rgbKey() })
    }

    private fun color(hue: Float) = ThemeColorHsv(
        hueDegrees = hue,
        saturation = 0.75f,
        value = 0.65f
    )
}
