package com.example.chatbar.domain.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullImageAdversarialPatchTest {
    @Test
    fun applyPreservesAlphaAndChangesRgb() {
        val pixels = intArrayOf(0x7f808080)

        transformFullImageAdversarialPatchPixels(
            pixels,
            width = 1,
            height = 1,
            operation = FullImagePatchOperation.Apply
        )

        assertEquals(0x7f, pixels.single() ushr 24)
        assertEquals(0x7fad6565.toInt(), pixels.single())
    }

    @Test
    fun restoreReversesUnclippedPatchPixels() {
        val original = intArrayOf(
            0xff808080.toInt(),
            0xff607090.toInt(),
            0x408090a0
        )
        val pixels = original.copyOf()

        transformFullImageAdversarialPatchPixels(
            pixels,
            width = 3,
            height = 1,
            operation = FullImagePatchOperation.Apply
        )
        transformFullImageAdversarialPatchPixels(
            pixels,
            width = 3,
            height = 1,
            operation = FullImagePatchOperation.Restore
        )

        assertArrayEquals(original, pixels)
    }

    @Test
    fun restoreClampsChannelsAfterLossyOrClippedInput() {
        val pixels = intArrayOf(0xffff0000.toInt())

        transformFullImageAdversarialPatchPixels(
            pixels,
            width = 1,
            height = 1,
            operation = FullImagePatchOperation.Restore
        )

        assertEquals(0xffd21b1b.toInt(), pixels.single())
    }

    @Test
    fun frameIndexChangesPerturbationPattern() {
        val width = 64
        val height = 64
        val first = IntArray(width * height) { 0xff808080.toInt() }
        val second = first.copyOf()

        transformFullImageAdversarialPatchPixels(
            first,
            width = width,
            height = height,
            operation = FullImagePatchOperation.Apply,
            frameIndex = 0
        )
        transformFullImageAdversarialPatchPixels(
            second,
            width = width,
            height = height,
            operation = FullImagePatchOperation.Apply,
            frameIndex = 1
        )

        var differs = false
        for (index in first.indices) {
            if (first[index] != second[index]) {
                differs = true
                break
            }
        }
        assertTrue("不同帧号的贴片应当产生不同图案", differs)
    }
}
