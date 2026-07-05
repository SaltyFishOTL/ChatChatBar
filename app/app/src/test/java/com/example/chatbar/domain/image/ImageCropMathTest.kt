package com.example.chatbar.domain.image

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageCropMathTest {
    @Test fun wideImageDefaultsToCenteredSquareCrop() {
        val rect = imageCropFractionRect(
            sourceWidth = 4000f,
            sourceHeight = 2000f,
            frameWidth = 1000f,
            frameHeight = 1000f,
            userScale = 1f,
            offset = ImageCropOffset(0f, 0f)
        )

        assertEquals(0.25f, rect.left, 0.0001f)
        assertEquals(0f, rect.top, 0.0001f)
        assertEquals(0.75f, rect.right, 0.0001f)
        assertEquals(1f, rect.bottom, 0.0001f)
    }

    @Test fun panIsClampedToKeepFrameCovered() {
        val clamped = clampCropOffset(
            offset = ImageCropOffset(900f, 200f),
            sourceWidth = 4000f,
            sourceHeight = 2000f,
            frameWidth = 1000f,
            frameHeight = 1000f,
            userScale = 1f
        )

        assertEquals(500f, clamped.x, 0.0001f)
        assertEquals(0f, clamped.y, 0.0001f)
    }

    @Test fun clampedEdgeCropNeverLeavesSourceBounds() {
        val clamped = clampCropOffset(
            offset = ImageCropOffset(1000f, 1000f),
            sourceWidth = 1000f,
            sourceHeight = 1000f,
            frameWidth = 900f,
            frameHeight = 1600f,
            userScale = 1f
        )
        val rect = imageCropFractionRect(
            sourceWidth = 1000f,
            sourceHeight = 1000f,
            frameWidth = 900f,
            frameHeight = 1600f,
            userScale = 1f,
            offset = clamped
        )

        assertEquals(350f, clamped.x, 0.0001f)
        assertEquals(0f, clamped.y, 0.0001f)
        assertEquals(0f, rect.left, 0.0001f)
        assertEquals(0f, rect.top, 0.0001f)
        assertEquals(0.5625f, rect.right, 0.0001f)
        assertEquals(1f, rect.bottom, 0.0001f)
    }

    @Test fun zoomNarrowsCropAroundCenter() {
        val rect = imageCropFractionRect(
            sourceWidth = 1000f,
            sourceHeight = 1000f,
            frameWidth = 500f,
            frameHeight = 500f,
            userScale = 2f,
            offset = ImageCropOffset(0f, 0f)
        )

        assertEquals(0.25f, rect.left, 0.0001f)
        assertEquals(0.25f, rect.top, 0.0001f)
        assertEquals(0.75f, rect.right, 0.0001f)
        assertEquals(0.75f, rect.bottom, 0.0001f)
    }
}
