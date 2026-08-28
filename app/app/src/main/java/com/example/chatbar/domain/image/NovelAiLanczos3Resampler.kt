package com.example.chatbar.domain.image

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin

/** CPU Lanczos3 resampling matching the high-quality resize used by NovelAI's web editor. */
internal object NovelAiLanczos3Resampler {
    fun resize(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        require(targetWidth > 0 && targetHeight > 0) { "缩放目标尺寸无效" }
        if (source.width == targetWidth && source.height == targetHeight) return source
        val sourcePixels = IntArray(source.width * source.height)
        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        val outputPixels = if (targetWidth.toLong() * source.height <= source.width.toLong() * targetHeight) {
            val horizontal = resizeHorizontal(sourcePixels, source.width, source.height, targetWidth)
            resizeVertical(horizontal, targetWidth, source.height, targetHeight)
        } else {
            val vertical = resizeVertical(sourcePixels, source.width, source.height, targetHeight)
            resizeHorizontal(vertical, source.width, targetHeight, targetWidth)
        }
        return Bitmap.createBitmap(outputPixels, targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    }

    private fun resizeHorizontal(
        source: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int
    ): IntArray {
        val contributors = contributors(sourceWidth, targetWidth)
        val output = IntArray(targetWidth * sourceHeight)
        for (y in 0 until sourceHeight) {
            val sourceRow = y * sourceWidth
            val outputRow = y * targetWidth
            for (x in 0 until targetWidth) {
                output[outputRow + x] = sample(source, sourceRow, 1, contributors[x])
            }
        }
        return output
    }

    private fun resizeVertical(
        source: IntArray,
        width: Int,
        sourceHeight: Int,
        targetHeight: Int
    ): IntArray {
        val contributors = contributors(sourceHeight, targetHeight)
        val output = IntArray(width * targetHeight)
        for (y in 0 until targetHeight) {
            val contributor = contributors[y]
            for (x in 0 until width) {
                output[y * width + x] = sample(source, x, width, contributor)
            }
        }
        return output
    }

    private fun sample(
        source: IntArray,
        start: Int,
        stride: Int,
        contributor: Contributor
    ): Int {
        var alpha = 0f
        var red = 0f
        var green = 0f
        var blue = 0f
        contributor.indices.indices.forEach { index ->
            val pixel = source[start + contributor.indices[index] * stride]
            val weight = contributor.weights[index]
            alpha += (pixel ushr 24 and 0xff) * weight
            red += (pixel ushr 16 and 0xff) * weight
            green += (pixel ushr 8 and 0xff) * weight
            blue += (pixel and 0xff) * weight
        }
        fun clamp(value: Float): Int = value.toInt().coerceIn(0, 255)
        return (clamp(alpha + 0.5f) shl 24) or
            (clamp(red + 0.5f) shl 16) or
            (clamp(green + 0.5f) shl 8) or
            clamp(blue + 0.5f)
    }

    private fun contributors(sourceSize: Int, targetSize: Int): Array<Contributor> {
        val scale = targetSize.toDouble() / sourceSize
        val filterScale = minOf(1.0, scale)
        val support = LOBES / filterScale
        return Array(targetSize) { target ->
            val center = (target + 0.5) / scale - 0.5
            val first = ceil(center - support).toInt()
            val last = floor(center + support).toInt()
            val rawIndices = IntArray(last - first + 1)
            val rawWeights = FloatArray(rawIndices.size)
            var total = 0.0
            rawIndices.indices.forEach { offset ->
                val source = first + offset
                val distance = (center - source) * filterScale
                val weight = lanczos(distance) * filterScale
                rawIndices[offset] = source.coerceIn(0, sourceSize - 1)
                rawWeights[offset] = weight.toFloat()
                total += weight
            }
            if (abs(total) > 1e-12) {
                rawWeights.indices.forEach { rawWeights[it] = (rawWeights[it] / total).toFloat() }
            }
            Contributor(rawIndices, rawWeights)
        }
    }

    private fun lanczos(value: Double): Double {
        val absolute = abs(value)
        if (absolute < 1e-12) return 1.0
        if (absolute >= LOBES) return 0.0
        val radians = PI * value
        return sin(radians) / radians * sin(radians / LOBES) / (radians / LOBES)
    }

    private data class Contributor(val indices: IntArray, val weights: FloatArray)

    private const val LOBES = 3.0
}
