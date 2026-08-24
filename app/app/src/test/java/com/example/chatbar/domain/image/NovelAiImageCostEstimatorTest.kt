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
