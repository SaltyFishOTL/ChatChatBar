package com.example.chatbar.domain.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        assertEquals(0x7f987474.toInt(), pixels.single())
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

        assertEquals(0xffe70c0c.toInt(), pixels.single())
    }
}
