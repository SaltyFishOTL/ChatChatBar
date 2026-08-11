package com.example.chatbar.domain.image

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class GifPatchRetentionTest {
    @Test
    fun perturbationAmplitudeExceedsPaletteQuantizationNoise() {
        val width = 64
        val height = 64
        val gradient = buildGradientPixels(width, height)
        val target = gradient.copyOf()
        transformFullImageAdversarialPatchPixels(
            target,
            width = width,
            height = height,
            operation = FullImagePatchOperation.Apply
        )
        val perturbationRms = patchRms(target, width, height)
        val quantized = quantizeToPalette(target, buildPalette(target, colorCount = 48))
        val quantizationNoise = meanPixelError(quantized, target)

        assertTrue("贴片单通道均幅应为正值", perturbationRms > 0)
        assertTrue(
            "贴片幅度应明显高于调色板量化噪声：perturbationRms=$perturbationRms noise=$quantizationNoise",
            quantizationNoise < perturbationRms * 0.6
        )
    }

    @Test
    fun diffusionStyleFeedbackIsPointlessBecauseNearestColorMappingIsLocallyOptimal() {
        // 直接把量化结果写回目标再量化不会带来任何变化：
        // 最近色映射是最优的，残余反馈被快照吸收，这正是 GIF 贴片不需要"补偿循环"的原因。
        val width = 8
        val height = 8
        val target = buildGradientPixels(width, height)
        val palette = buildPalette(target, colorCount = 16)
        val direct = quantizeToPalette(target, palette)
        val reQuantized = quantizeToPalette(direct, palette)

        assertTrue(reQuantized.contentEquals(direct))
    }

    private fun buildGradientPixels(width: Int, height: Int): IntArray =
        IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val gray = 0x20 + (y * 3) % 0xc0 + (x / 4) % 3
            (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray
        }

    private fun patchRms(patched: IntArray, width: Int, height: Int): Double {
        val original = buildGradientPixels(width, height)
        var sum = 0.0
        for (i in original.indices) {
            val dr = ((patched[i] shr 16) and 0xff) - ((original[i] shr 16) and 0xff)
            val dg = ((patched[i] shr 8) and 0xff) - ((original[i] shr 8) and 0xff)
            val db = (patched[i] and 0xff) - (original[i] and 0xff)
            sum += (dr * dr + dg * dg + db * db) / 3.0
        }
        return Math.sqrt(sum / original.size)
    }

    private fun meanPixelError(quantized: IntArray, target: IntArray): Double {
        var total = 0.0
        for (i in target.indices) {
            val c = quantized[i]
            val t = target[i]
            total += (
                abs(((c shr 16) and 0xff) - ((t shr 16) and 0xff)) +
                    abs(((c shr 8) and 0xff) - ((t shr 8) and 0xff)) +
                    abs((c and 0xff) - (t and 0xff))
                ) / 3.0
        }
        return total / target.size
    }

    private fun buildPalette(pixels: IntArray, colorCount: Int): IntArray {
        val cubeCounts = IntArray(8 * 8 * 8)
        val cubeSumR = LongArray(8 * 8 * 8)
        val cubeSumG = LongArray(8 * 8 * 8)
        val cubeSumB = LongArray(8 * 8 * 8)
        for (color in pixels) {
            if (color ushr 24 == 0) continue
            val r = (color shr 16) and 0xff
            val g = (color shr 8) and 0xff
            val b = color and 0xff
            val bin = (r ushr 5) * 64 + (g ushr 5) * 8 + (b ushr 5)
            cubeCounts[bin]++
            cubeSumR[bin] += r.toLong()
            cubeSumG[bin] += g.toLong()
            cubeSumB[bin] += b.toLong()
        }
        val ordered = (0 until 8 * 8 * 8)
            .filter { cubeCounts[it] > 0 }
            .sortedByDescending { cubeCounts[it] }
        val palette = IntArray(ordered.size.coerceAtMost(colorCount))
        for (i in palette.indices) {
            val bin = ordered[i]
            val count = cubeCounts[bin]
            palette[i] = (0xff shl 24) or
                ((cubeSumR[bin] / count).toInt() shl 16) or
                ((cubeSumG[bin] / count).toInt() shl 8) or
                (cubeSumB[bin] / count).toInt()
        }
        return palette
    }

    private fun quantizeToPalette(pixels: IntArray, palette: IntArray): IntArray =
        IntArray(pixels.size) { index ->
            val color = pixels[index]
            if (color ushr 24 == 0) return@IntArray color
            var bestIndex = 0
            var bestDistance = Int.MAX_VALUE
            for (p in palette.indices) {
                val candidate = palette[p]
                val dr = ((color shr 16) and 0xff) - ((candidate shr 16) and 0xff)
                val dg = ((color shr 8) and 0xff) - ((candidate shr 8) and 0xff)
                val db = (color and 0xff) - (candidate and 0xff)
                val distance = dr * dr + dg * dg + db * db
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestIndex = p
                }
            }
            palette[bestIndex]
        }
}
