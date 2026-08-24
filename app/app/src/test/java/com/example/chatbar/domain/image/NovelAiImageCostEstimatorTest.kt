package com.example.chatbar.domain.image

import kotlin.test.Test
import kotlin.test.assertEquals

class NovelAiImageCostEstimatorTest {
    @Test
    fun normalV45UsesCurrentPixelPrice() {
        val cost = NovelAiImageCostEstimator.estimate(
            NovelAiGenerationSettings(model = NovelAiImageModel.V4_5_FULL),
            account = null
        )

        assertEquals(NovelAiGenerationChargeKind.ANLAS, cost.kind)
        assertEquals(3, cost.anlas)
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
        assertEquals(5, cost.anlas)
    }

    @Test
    fun opusBatchChargesAllSamplesAfterFreeFirstSample() {
        val cost = NovelAiImageCostEstimator.estimate(
            NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, count = 3),
            account = opus(v5Percent = 50.0)
        )

        assertEquals(NovelAiGenerationChargeKind.ANLAS, cost.kind)
        assertEquals(10, cost.anlas)
    }

    @Test
    fun higherStepsDisableFreeSample() {
        val cost = NovelAiImageCostEstimator.estimate(
            NovelAiGenerationSettings(model = NovelAiImageModel.V4_5_FULL, steps = 29),
            account = opus()
        )

        assertEquals(NovelAiGenerationChargeKind.ANLAS, cost.kind)
        assertEquals(3, cost.anlas)
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
