package com.example.chatbar.domain.image

import org.junit.Assert.assertEquals
import org.junit.Test

class NovelAiImageCostEstimatorTest {
    @Test
    fun normalV45UsesCurrentPixelPrice() {
        val cost = NovelAiImageCostEstimator.estimate(
            NovelAiGenerationSettings(model = NovelAiImageModel.V4_5_FULL),
            account = null
        )

        assertEquals(NovelAiGenerationChargeKind.ANLAS, cost.kind)
        assertEquals(20, cost.anlas)
    }

    @Test
    fun activeOpusV5UsesAllowanceForEligibleSingleImage() {
        val cost = NovelAiImageCostEstimator.estimate(
            NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL),
            account = opus(v5Percent = 50.0)
        )

        assertEquals(NovelAiGenerationChargeKind.V5_ALLOWANCE, cost.kind)
        assertEquals(0, cost.anlas)
    }

    @Test
    fun exhaustedV5AllowanceFallsBackToAnlas() {
        val cost = NovelAiImageCostEstimator.estimate(
            NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL),
            account = opus(v5Percent = 0.0, exhausted = true)
        )

        assertEquals(NovelAiGenerationChargeKind.ANLAS, cost.kind)
        assertEquals(30, cost.anlas)
    }

    @Test
    fun opusBatchChargesEverySample() {
        val cost = NovelAiImageCostEstimator.estimate(
            NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, count = 3),
            account = opus(v5Percent = 50.0)
        )

        assertEquals(NovelAiGenerationChargeKind.ANLAS, cost.kind)
        assertEquals(90, cost.anlas)
    }

    @Test
    fun higherStepsDisableFreeSample() {
        val cost = NovelAiImageCostEstimator.estimate(
            NovelAiGenerationSettings(model = NovelAiImageModel.V4_5_FULL, steps = 29),
            account = opus()
        )

        assertEquals(NovelAiGenerationChargeKind.ANLAS, cost.kind)
        assertEquals(20, cost.anlas)
    }

    @Test
    fun imageToImageDisablesOpusFreeSample() {
        val asset = NovelAiStudioAssetRef(path = "base.png", sha256 = "hash", width = 832, height = 1216)
        val cost = NovelAiImageCostEstimator.estimate(
            settings = NovelAiGenerationSettings(model = NovelAiImageModel.V4_5_FULL),
            account = opus(),
            imageGuidance = NovelAiImageGuidanceDraft(
                action = NovelAiGenerationAction.IMAGE_TO_IMAGE,
                baseImage = asset
            )
        )

        assertEquals(NovelAiGenerationChargeKind.ANLAS, cost.kind)
        assertEquals(20, cost.anlas)
    }

    @Test
    fun focusedInpaintingOnLargeSourceUsesOpusFreeSample() {
        val asset = NovelAiStudioAssetRef(path = "base.png", sha256 = "hash", width = 1024, height = 1536)
        val mask = NovelAiStudioAssetRef(path = "mask.png", sha256 = "mask", width = 1024, height = 1536)
        val cost = NovelAiImageCostEstimator.estimate(
            settings = NovelAiGenerationSettings(
                model = NovelAiImageModel.V4_5_FULL,
                sizeTier = NovelAiSizeTier.LARGE
            ),
            account = opus(),
            imageGuidance = NovelAiImageGuidanceDraft(
                action = NovelAiGenerationAction.INPAINT,
                baseImage = asset,
                maskImage = mask
            )
        )

        assertEquals(NovelAiGenerationChargeKind.FREE, cost.kind)
        assertEquals(0, cost.anlas)
    }

    @Test
    fun focusedInpaintingWithoutOpusPricesTheFocusedRequest() {
        val cost = NovelAiImageCostEstimator.estimate(
            settings = NovelAiGenerationSettings(
                model = NovelAiImageModel.V4_5_FULL,
                sizeTier = NovelAiSizeTier.WALLPAPER
            ),
            account = null,
            imageGuidance = NovelAiImageGuidanceDraft(action = NovelAiGenerationAction.INPAINT)
        )

        assertEquals(NovelAiGenerationChargeKind.ANLAS, cost.kind)
        assertEquals(20, cost.anlas)
    }

    @Test
    fun preciseCostScalesPerReferenceAndOutput() {
        val asset = NovelAiStudioAssetRef(path = "ref.png", sha256 = "hash", width = 1024, height = 1024)
        val precise = NovelAiImageGuidanceDraft(
            referenceMode = NovelAiReferenceMode.PRECISE,
            preciseReference = NovelAiPreciseReferenceDraft(asset = asset)
        )
        val cost = NovelAiImageCostEstimator.estimate(
            settings = NovelAiGenerationSettings(count = 2),
            account = null,
            imageGuidance = precise
        )

        assertEquals(50, cost.anlas)
        assertEquals(0, cost.encodingAnlas)
    }

    @Test
    fun uncachedVibeEncodingCostIsIncludedInDisplayedTotal() {
        val cost = NovelAiImageCostEstimator.estimate(
            settings = NovelAiGenerationSettings(count = 2),
            account = null,
            vibeCacheMisses = 2
        )

        assertEquals(44, cost.anlas)
        assertEquals(4, cost.encodingAnlas)
    }

    private fun opus(
        v5Percent: Double? = null,
        exhausted: Boolean = false
    ) = NovelAiAccountUsage(
        anlas = 10_000,
        tier = 3,
        active = true,
        v5AllowancePercent = v5Percent,
        v5AllowanceExhausted = exhausted
    )
}
