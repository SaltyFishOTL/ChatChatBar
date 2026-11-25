package com.example.chatbar.domain.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAiImageGuidanceTest {
    private val asset = NovelAiStudioAssetRef(path = "owned.png", sha256 = "abc", width = 1024, height = 1024)

    @Test
    fun `v5 uses precise reference only with high text and image generation`() {
        val guidance = NovelAiImageGuidanceDraft(
            referenceMode = NovelAiReferenceMode.PRECISE,
            preciseReference = NovelAiPreciseReferenceDraft(asset = asset)
        )

        val high = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL)
        val medium = high.copy(effort = NovelAiEffort.MEDIUM)
        val inpaint = guidance.copy(action = NovelAiGenerationAction.INPAINT)
        val img2img = guidance.copy(action = NovelAiGenerationAction.IMAGE_TO_IMAGE)
        assertEquals(NovelAiReferenceMode.PRECISE, guidance.effectiveReferenceMode(high))
        assertEquals(NovelAiReferenceMode.PRECISE, img2img.effectiveReferenceMode(high))
        assertEquals(NovelAiReferenceMode.NONE, guidance.effectiveReferenceMode(medium))
        assertEquals(NovelAiReferenceMode.NONE, inpaint.effectiveReferenceMode(high))
        assertEquals(NovelAiReferenceMode.PRECISE, inpaint.effectiveReferenceMode(NovelAiGenerationSettings()))
        assertEquals(NovelAiReferenceMode.NONE, guidance.copy(referenceMode = NovelAiReferenceMode.VIBE).effectiveReferenceMode(high))
    }

    @Test
    fun `focused inpaint requires focus region but allows empty mask`() {
        val emptyMask = asset.copy(path = "mask.png", containsPaint = false)
        val guidance = NovelAiImageGuidanceDraft(
            action = NovelAiGenerationAction.INPAINT,
            baseImage = asset,
            maskImage = emptyMask
        )

        assertEquals("请使用聚焦工具框选重绘区域", guidance.validationError(NovelAiGenerationSettings()))
        assertNull(
            guidance.copy(
                focusedInpaintRegion = NovelAiFocusedInpaintRegion(
                    x = 0.25f,
                    y = 0.25f,
                    width = 0.5f,
                    height = 0.5f
                )
            ).validationError(NovelAiGenerationSettings())
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
    fun `vibe validation follows official sixteen image limit`() {
        val sixteen = List(16) { index ->
            NovelAiVibeReferenceDraft(asset = asset.copy(path = "vibe-$index.png"))
        }
        val guidance = NovelAiImageGuidanceDraft(
            referenceMode = NovelAiReferenceMode.VIBE,
            vibes = sixteen
        )

        assertNull(guidance.validationError(NovelAiGenerationSettings()))
        assertEquals(
            "氛围参考最多 16 张",
            guidance.copy(vibes = sixteen + NovelAiVibeReferenceDraft(asset = asset)).validationError(
                NovelAiGenerationSettings()
            )
        )
    }

    @Test
    fun `precise reference selects nearest supported large canvas`() {
        assertEquals(
            NovelAiImageSize(1024, 1536, "Large Portrait"),
            NovelAiPreciseReferenceWirePolicy.targetSize(800, 1400)
        )
        assertEquals(
            NovelAiImageSize(1472, 1472, "Large Square"),
            NovelAiPreciseReferenceWirePolicy.targetSize(900, 1000)
        )
        assertEquals(
            NovelAiImageSize(1536, 1024, "Large Landscape"),
            NovelAiPreciseReferenceWirePolicy.targetSize(1600, 900)
        )
    }

    @Test
    fun `v5 reference chooses aspect buckets across orientations`() {
        val model = NovelAiImageModel.V5_FULL
        val sizes = listOf(832 to 1216, 1024 to 1024, 1216 to 832, 256 to 4096)
        for ((width, height) in sizes) {
            val actual = NovelAiPreciseReferenceWirePolicy.targetSize(width * 2, height * 2, model)
            assertEquals(width, actual.width)
            assertEquals(height, actual.height)
        }
    }

    @Test
    fun `shared guidance loads base precise and vibe sources independently`() {
        val baseAsset = asset.copy(path = "fitted.png", sha256 = "fitted")
        val referenceAsset = asset.copy(path = "natural.png", sha256 = "natural")

        val shared = NovelAiImageGuidanceDraft().withSharedImageSources(baseAsset, referenceAsset)

        assertEquals(NovelAiGenerationAction.IMAGE_TO_IMAGE, shared.action)
        assertEquals(baseAsset, shared.baseImage)
        assertEquals(referenceAsset, shared.preciseReference.asset)
        assertEquals(referenceAsset, shared.vibes.single().asset)

        val withoutPrecise = shared.withoutImageSource(NovelAiImageUseTarget.PRECISE_REFERENCE)
        assertNull(withoutPrecise.preciseReference.asset)
        assertEquals(baseAsset, withoutPrecise.baseImage)
        assertEquals(referenceAsset, withoutPrecise.vibes.single().asset)

        val withoutVibe = shared.withoutImageSource(NovelAiImageUseTarget.VIBE_REFERENCE)
        assertTrue(withoutVibe.vibes.isEmpty())
        assertEquals(baseAsset, withoutVibe.baseImage)
        assertEquals(referenceAsset, withoutVibe.preciseReference.asset)

        val withoutBase = shared.withoutImageSource(NovelAiImageUseTarget.IMAGE_TO_IMAGE)
        assertNull(withoutBase.baseImage)
        assertEquals(referenceAsset, withoutBase.preciseReference.asset)
        assertEquals(referenceAsset, withoutBase.vibes.single().asset)
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

    @Test
    fun `history marks only used v5 reference source as missing`() {
        val guidance = NovelAiImageGuidanceDraft(
            referenceMode = NovelAiReferenceMode.PRECISE,
            preciseReference = NovelAiPreciseReferenceDraft(asset = asset, strength = 0.75f)
        )
        val settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL)
        val draft = NovelAiStudioDraft(imageGuidance = guidance)
        val recipe = draft.toRecipe(settings)
        assertEquals(0.75f, recipe.recordedImageGuidance.preciseReference.v5Strength)
        assertNull(recipe.recordedImageGuidance.preciseReference.asset)
        assertTrue(recipe.recordedImageGuidance.hasMissingHistorySource())
        val pausedRecipe = draft.toRecipe(settings.copy(effort = NovelAiEffort.MEDIUM))
        assertFalse(pausedRecipe.recordedImageGuidance.hasMissingHistorySource())
        val legacy = recipe.copy(imageGuidance = guidance.copy(
            preciseReference = guidance.preciseReference.copy(asset = null)
        ))
        assertFalse(legacy.recordedImageGuidance.hasMissingHistorySource())
    }
}
