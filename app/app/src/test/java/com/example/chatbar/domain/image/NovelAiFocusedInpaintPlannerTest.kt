package com.example.chatbar.domain.image

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAiFocusedInpaintPlannerTest {
    @Test
    fun explicitSelectionBecomesExactFocusedCrop() {
        val selection = NovelAiPixelBounds(800, 600, 1200, 1000)
        val plan = NovelAiFocusedInpaintPlanner.plan(2000, 1600, selection, 96)

        assertEquals(selection, plan.crop)
        assertEquals(NovelAiPixelBounds(96, 96, 304, 304), plan.innerMaskBounds)
        assertValidRequest(plan.requestSize)
    }

    @Test
    fun edgeSelectionKeepsExactSourceCoordinates() {
        val selection = NovelAiPixelBounds(0, 0, 300, 400)
        val plan = NovelAiFocusedInpaintPlanner.plan(1400, 2100, selection, 96)

        assertEquals(0, plan.crop.left)
        assertEquals(0, plan.crop.top)
        assertEquals(selection, plan.crop)
        assertTrue(plan.crop.right <= plan.sourceWidth)
        assertTrue(plan.crop.bottom <= plan.sourceHeight)
        assertValidRequest(plan.requestSize)
    }

    @Test
    fun maximumOfficialSquareUpscalesToOneMegapixelRequest() {
        val selection = NovelAiPixelBounds(0, 0, 768, 768)
        val plan = NovelAiFocusedInpaintPlanner.plan(1024, 1024, selection, 96)

        assertEquals(selection, plan.crop)
        assertEquals(1024, plan.requestSize.width)
        assertEquals(1024, plan.requestSize.height)
    }

    @Test
    fun requestAspectTracksFocusedCrop() {
        val selection = NovelAiPixelBounds(200, 100, 500, 700)
        val plan = NovelAiFocusedInpaintPlanner.plan(1000, 1400, selection, 96)
        val cropAspect = plan.crop.width.toDouble() / plan.crop.height
        val requestAspect = plan.requestSize.width.toDouble() / plan.requestSize.height

        assertTrue(abs(cropAspect - requestAspect) < 0.05)
        assertValidRequest(plan.requestSize)
    }

    private fun assertValidRequest(size: NovelAiImageSize) {
        assertEquals(0, size.width % 64)
        assertEquals(0, size.height % 64)
        assertTrue(size.width >= 64)
        assertTrue(size.height >= 64)
        assertTrue(size.width.toLong() * size.height <= NovelAiFocusedInpaintPlanner.MAX_REQUEST_PIXELS)
    }
}
