package com.example.chatbar.ui.kit

import androidx.compose.ui.graphics.toArgb
import com.example.chatbar.domain.appearance.DefaultThemeColorHsv
import com.example.chatbar.domain.appearance.ThemeColorHsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBarThemeTest {
    @Test
    fun defaultSeed_preservesLegacyPrimaryColors() {
        val light = chatBarColors(darkTheme = false, themeColor = DefaultThemeColorHsv)
        val dark = chatBarColors(darkTheme = true, themeColor = DefaultThemeColorHsv)

        assertEquals(0xFF2F8E7B.toInt(), light.primary.toArgb())
        assertEquals(0xFF62CBB5.toInt(), dark.primary.toArgb())
        assertEquals(0xFFFCFDFC.toInt(), light.background.toArgb())
        assertEquals(0xFF0F1110.toInt(), dark.background.toArgb())
    }

    @Test
    fun changedSeed_updatesTintedRolesButKeepsStatusColors() {
        val baseline = chatBarColors(false, DefaultThemeColorHsv)
        val changed = chatBarColors(
            darkTheme = false,
            themeColor = ThemeColorHsv(hueDegrees = 20f, saturation = 0.9f, value = 0.6f)
        )

        assertNotEquals(baseline.primary.toArgb(), changed.primary.toArgb())
        assertNotEquals(baseline.accent.toArgb(), changed.accent.toArgb())
        assertNotEquals(baseline.border.toArgb(), changed.border.toArgb())
        assertEquals(baseline.success.toArgb(), changed.success.toArgb())
        assertEquals(baseline.warning.toArgb(), changed.warning.toArgb())
        assertEquals(baseline.destructive.toArgb(), changed.destructive.toArgb())
        assertEquals(baseline.dim.toArgb(), changed.dim.toArgb())
    }

    @Test
    fun extremeSeeds_keepRequiredContrastInBothModes() {
        val seeds = listOf(
            ThemeColorHsv(0f, 1f, 1f),
            ThemeColorHsv(240f, 1f, 0f),
            ThemeColorHsv(120f, 0f, 1f)
        )

        seeds.forEach { seed ->
            listOf(false, true).forEach { darkTheme ->
                val colors = chatBarColors(darkTheme, seed)
                assertTrue(
                    "primary/background seed=$seed dark=$darkTheme ratio=${contrastRatio(colors.primary, colors.background)}",
                    contrastRatio(colors.primary, colors.background) >= 3f
                )
                assertTrue(
                    "primary/card seed=$seed dark=$darkTheme ratio=${contrastRatio(colors.primary, colors.card)}",
                    contrastRatio(colors.primary, colors.card) >= 3f
                )
                assertTrue(
                    "primaryForeground seed=$seed dark=$darkTheme ratio=${contrastRatio(colors.primaryForeground, colors.primary)}",
                    contrastRatio(colors.primaryForeground, colors.primary) >= 4.5f
                )
                assertTrue(
                    "accentForeground seed=$seed dark=$darkTheme ratio=${contrastRatio(colors.accentForeground, colors.accent)}",
                    contrastRatio(colors.accentForeground, colors.accent) >= 4.5f
                )
            }
        }
    }
}
