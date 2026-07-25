package com.example.chatbar.domain.appearance

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val DEFAULT_THEME_HUE_DEGREES = 168f
const val DEFAULT_THEME_SATURATION = 95f / 142f
const val DEFAULT_THEME_VALUE = 142f / 255f
const val MAX_THEME_COLOR_HISTORY_SIZE = 5

@Serializable
data class ThemeColorHsv(
    val hueDegrees: Float = DEFAULT_THEME_HUE_DEGREES,
    val saturation: Float = DEFAULT_THEME_SATURATION,
    val value: Float = DEFAULT_THEME_VALUE
) {
    fun normalized(): ThemeColorHsv {
        val normalizedHue = if (hueDegrees.isFinite()) {
            ((hueDegrees % 360f) + 360f) % 360f
        } else {
            DEFAULT_THEME_HUE_DEGREES
        }
        return ThemeColorHsv(
            hueDegrees = normalizedHue,
            saturation = saturation.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                ?: DEFAULT_THEME_SATURATION,
            value = value.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                ?: DEFAULT_THEME_VALUE
        )
    }

    fun rgbKey(): Int = toOpaqueArgb() and 0x00FFFFFF

    fun toOpaqueArgb(): Int {
        val normalized = normalized()
        val chroma = normalized.value * normalized.saturation
        val hueSector = normalized.hueDegrees / 60f
        val x = chroma * (1f - abs((hueSector % 2f) - 1f))
        val (redPrime, greenPrime, bluePrime) = when {
            hueSector < 1f -> Triple(chroma, x, 0f)
            hueSector < 2f -> Triple(x, chroma, 0f)
            hueSector < 3f -> Triple(0f, chroma, x)
            hueSector < 4f -> Triple(0f, x, chroma)
            hueSector < 5f -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        val match = normalized.value - chroma
        val red = ((redPrime + match) * 255f).roundToInt().coerceIn(0, 255)
        val green = ((greenPrime + match) * 255f).roundToInt().coerceIn(0, 255)
        val blue = ((bluePrime + match) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    companion object {
        fun fromRgb(red: Float, green: Float, blue: Float): ThemeColorHsv {
            val safeRed = red.coerceIn(0f, 1f)
            val safeGreen = green.coerceIn(0f, 1f)
            val safeBlue = blue.coerceIn(0f, 1f)
            val maximum = max(safeRed, max(safeGreen, safeBlue))
            val minimum = min(safeRed, min(safeGreen, safeBlue))
            val delta = maximum - minimum
            val hue = when {
                delta == 0f -> 0f
                maximum == safeRed -> 60f * (((safeGreen - safeBlue) / delta) % 6f)
                maximum == safeGreen -> 60f * (((safeBlue - safeRed) / delta) + 2f)
                else -> 60f * (((safeRed - safeGreen) / delta) + 4f)
            }
            return ThemeColorHsv(
                hueDegrees = if (hue < 0f) hue + 360f else hue,
                saturation = if (maximum == 0f) 0f else delta / maximum,
                value = maximum
            )
        }
    }
}

val DefaultThemeColorHsv = ThemeColorHsv()

object ThemeColorHistoryPolicy {
    fun normalize(
        current: ThemeColorHsv,
        history: List<ThemeColorHsv>
    ): List<ThemeColorHsv> {
        val currentKey = current.normalized().rgbKey()
        val defaultKey = DefaultThemeColorHsv.rgbKey()
        return history
            .asSequence()
            .map(ThemeColorHsv::normalized)
            .filter { it.rgbKey() != currentKey && it.rgbKey() != defaultKey }
            .distinctBy(ThemeColorHsv::rgbKey)
            .take(MAX_THEME_COLOR_HISTORY_SIZE)
            .toList()
    }

    fun update(
        current: ThemeColorHsv,
        next: ThemeColorHsv,
        history: List<ThemeColorHsv>
    ): List<ThemeColorHsv> {
        val normalizedCurrent = current.normalized()
        val normalizedNext = next.normalized()
        if (normalizedCurrent.rgbKey() == normalizedNext.rgbKey()) {
            return normalize(normalizedNext, history)
        }
        return normalize(normalizedNext, listOf(normalizedCurrent) + history)
    }
}
