package com.example.chatbar.domain.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAiImageGuidanceTest {
    private val asset = NovelAiStudioAssetRef(path = "owned.png", sha256 = "abc", width = 1024, height = 1024)

    @Test
    fun `v5 pauses references without deleting v45 configuration`() {
        val guidance = NovelAiImageGuidanceDraft(
            referenceMode = NovelAiReferenceMode.PRECISE,
            preciseReference = NovelAiPreciseReferenceDraft(asset = asset)
        )

        assertEquals(NovelAiReferenceMode.NONE, guidance.effectiveReferenceMode(NovelAiImageModel.V5_FULL))
        assertEquals(NovelAiReferenceMode.PRECISE, guidance.effectiveReferenceMode(NovelAiImageModel.V4_5_FULL))
        assertEquals(asset, guidance.preciseReference.asset)
    }

    @Test
    fun `focused inpaint requires focus region but allows empty mask`() {
        val emptyMask = asset.copy(path = "mask.png", containsPaint = false)
        val guidance = NovelAiImageGuidanceDraft(
            action = NovelAiGenerationAction.INPAINT,
            baseImage = asset,
            maskImage = emptyMask
        )

        assertEquals("请使用聚焦工具框选重绘区域", guidance.validationError(NovelAiImageModel.V4_5_FULL))
        assertNull(
            guidance.copy(
                focusedInpaintRegion = NovelAiFocusedInpaintRegion(
                    x = 0.25f,
                    y = 0.25f,
                    width = 0.5f,
                    height = 0.5f
                )
            ).validationError(NovelAiImageModel.V4_5_FULL)
        )
    }

    @Test
    fun `normalized vibe strengths preserve order and cap total`() {
        val guidance = NovelAiImageGuidanceDraft(
            referenceMode = NovelAiReferenceMode.VIBE,
            vibes = listOf(
                NovelAiVibeReferenceDraft(asset = asset, strength = 0.8f),
                NovelAiVibeReferenceDraft(asset = asset.copy(path = "second.png"), strength = 0.4f)
            )
        )

        val strengths = guidance.effectiveVibeStrengths()
        assertEquals(2, strengths.size)
        assertEquals(1f, strengths.sum(), 0.0001f)
        assertTrue(strengths[0] > strengths[1])
    }

    @Test
    fun `history recipe marks unowned base source as missing`() {
        val draft = NovelAiStudioDraft(
            imageGuidance = NovelAiImageGuidanceDraft(
                action = NovelAiGenerationAction.IMAGE_TO_IMAGE,
                baseImage = asset
            )
        )

        val stored = draft.toRecipe().imageGuidance
        assertEquals(NovelAiGenerationAction.IMAGE_TO_IMAGE, stored.action)
        assertNull(stored.baseImage)
        assertTrue(stored.hasMissingHistorySource())
        assertFalse(NovelAiImageGuidanceDraft().hasMissingHistorySource())
    }
}
