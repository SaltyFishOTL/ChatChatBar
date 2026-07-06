package com.example.chatbar.domain.image

import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class NovelAiImageSizePreset(
    val displayName: String,
    val width: Int,
    val height: Int
) {
    PORTRAIT("Normal Portrait", 832, 1216),
    SQUARE("Normal Square", 1024, 1024),
    HORIZONTAL("Normal Horizontal", 1216, 832);

    val imageSize: NovelAiImageSize get() = NovelAiImageSize(width, height, displayName)

    companion object {
        fun from(value: String?): NovelAiImageSizePreset {
            val normalized = value.orEmpty()
                .trim()
                .replace("-", "_")
                .replace(" ", "_")
                .uppercase()
            return when (normalized) {
                "SQUARE",
                "NORMAL_SQUARE" -> SQUARE
                "HORIZONTAL",
                "LANDSCAPE",
                "NORMAL_HORIZONTAL",
                "NORMAL_LANDSCAPE" -> HORIZONTAL
                else -> PORTRAIT
            }
        }
    }
}

data class NovelAiImageSize(
    val width: Int,
    val height: Int,
    val label: String
)

object NovelAiImageSizePolicy {
    private const val NORMAL_AREA = 1024 * 1024
    private const val STEP = 64
    private const val MIN_SIDE = 512
    private const val MAX_SIDE = 1536

    fun resolve(setting: String, designedPreset: NovelAiImageSizePreset): NovelAiImageSize {
        return parseUserRatio(setting) ?: designedPreset.imageSize
    }

    fun validationError(input: String): String? {
        if (input.isBlank()) return null
        return if (parseUserRatio(input) == null) {
            "比例格式无效，请输入 1:1、16:9 或 832x1216。"
        } else {
            null
        }
    }

    fun parseUserRatio(input: String): NovelAiImageSize? {
        val normalized = input.trim()
            .lowercase()
            .replace("：", ":")
            .replace("×", "x")
            .replace("*", "x")
        if (normalized.isBlank()) return null
        val parts = when {
            ":" in normalized -> normalized.split(":")
            "/" in normalized -> normalized.split("/")
            "x" in normalized -> normalized.split("x")
            else -> return null
        }.map(String::trim)
        if (parts.size != 2) return null
        val ratioWidth = parts[0].toFloatOrNull() ?: return null
        val ratioHeight = parts[1].toFloatOrNull() ?: return null
        if (ratioWidth <= 0f || ratioHeight <= 0f) return null

        val ratio = ratioWidth / ratioHeight
        var width = roundToStep(sqrt(NORMAL_AREA * ratio))
        var height = roundToStep(sqrt(NORMAL_AREA / ratio))
        width = width.coerceIn(MIN_SIDE, MAX_SIDE)
        height = height.coerceIn(MIN_SIDE, MAX_SIDE)
        while (width * height > NORMAL_AREA && (width > MIN_SIDE || height > MIN_SIDE)) {
            if (width >= height && width > MIN_SIDE) {
                width -= STEP
            } else if (height > MIN_SIDE) {
                height -= STEP
            } else {
                break
            }
        }
        return NovelAiImageSize(width, height, "Custom ${trimNumber(ratioWidth)}:${trimNumber(ratioHeight)}")
    }

    private fun roundToStep(value: Float): Int =
        (value / STEP).roundToInt().coerceAtLeast(1) * STEP

    private fun trimNumber(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else value.toString()
}
