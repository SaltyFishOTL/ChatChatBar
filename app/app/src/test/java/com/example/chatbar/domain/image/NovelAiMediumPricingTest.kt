package com.example.chatbar.domain.image

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class NovelAiMediumPricingTest {
    private fun settings(width: Int = 1024, height: Int = 1024, steps: Int = 50, count: Int = 1) =
        NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, effort = NovelAiEffort.MEDIUM,
            customWidth = width, customHeight = height, steps = steps, count = count)

    // Literal fixtures independently evaluated from official module 643656, not High-at-14.
    @Test fun mediumUsesFractional23StepPriceAndRoundsEachSampleBeforeBatch() {
        for (steps in listOf(14, 41, 50)) {
            assertEquals(15, NovelAiImageCostEstimator.estimate(settings(steps = steps), null).anlas)
        }
        for ((width, height, expected) in listOf(
            Triple(64, 64, 2), Triple(64, 1984, 2), Triple(512, 512, 4),
            Triple(1024, 1024, 15), Triple(2048, 1536, 44)
        )) {
            for (count in listOf(1, 2, 4)) {
                assertEquals("${width}x$height / $count", expected * count,
                    NovelAiImageCostEstimator.estimate(settings(width, height, count = count), null).anlas)
            }
        }
    }

    @Test fun ordinaryImg2imgAndFocusedInpaintUseTheSerializedStrength() {
        for ((strength, expected) in listOf(0f to 2, 0.2f to 3, 0.5f to 8, 0.6f to 9, 1f to 15)) {
            val image = NovelAiImageGuidanceDraft(action = NovelAiGenerationAction.IMAGE_TO_IMAGE,
                imageToImageStrength = strength)
            assertEquals(expected, NovelAiImageCostEstimator.estimate(settings(), null, image).anlas)
            val focused = image.copy(action = NovelAiGenerationAction.INPAINT, inpaintStrength = strength)
            // Focused request area remains 1024² even when the source profile is larger.
            assertEquals(expected, NovelAiImageCostEstimator.estimate(settings(2048, 1536), null, focused).anlas)
        }
    }

    @Test fun enhanceSharesMediumPriceAtItsOutputCanvasAndDecimalStrength() {
        val source = NovelAiEnhanceSource(NovelAiPromptPlan("scene", emptyList()), settings(), JsonObject(emptyMap()))
        for ((strength, expected) in listOf(0f to 2, 0.2f to 3, 0.5f to 8, 0.6f to 9, 1f to 15)) {
            assertEquals(expected, NovelAiPostProcessPolicy.enhanceCost(source, 1024, 1024,
                NovelAiEnhanceOptions(strength = strength), null).anlas)
        }
        assertEquals(8, NovelAiPostProcessPolicy.enhanceCost(source, 512, 512,
            NovelAiEnhanceOptions(scale = NovelAiEnhanceScale.DOUBLE), null).anlas)
        assertEquals(8, NovelAiPostProcessPolicy.enhanceCost(source, 832, 1216,
            NovelAiEnhanceOptions(), null).anlas)
        assertEquals(22, NovelAiPostProcessPolicy.enhanceCost(source, 1024, 1024,
            NovelAiEnhanceOptions(scale = NovelAiEnhanceScale.MAX), null).anlas)
    }

    @Test fun allowanceStillUsesEffectiveStepsAndExistingEligibilityBoundaries() {
        val opus = NovelAiAccountUsage(1000, 3, true, 50.0, false)
        assertEquals(NovelAiGenerationChargeKind.V5_ALLOWANCE, NovelAiImageCostEstimator.estimate(settings(), opus).kind)
        assertEquals(0, NovelAiImageCostEstimator.estimate(settings(), opus).anlas)
        for (account in listOf(null, opus.copy(active = false), opus.copy(tier = 2),
            opus.copy(v5AllowancePercent = null), opus.copy(v5AllowanceExhausted = true))) {
            assertEquals(15, NovelAiImageCostEstimator.estimate(settings(), account).anlas)
        }
        assertEquals(30, NovelAiImageCostEstimator.estimate(settings(count = 2), opus).anlas)
        assertEquals(16, NovelAiImageCostEstimator.estimate(settings(1024, 1088), opus).anlas)
        assertEquals(8, NovelAiImageCostEstimator.estimate(settings(), opus,
            NovelAiImageGuidanceDraft(action = NovelAiGenerationAction.IMAGE_TO_IMAGE)).anlas)
        val source = NovelAiEnhanceSource(NovelAiPromptPlan("scene", emptyList()), settings(), JsonObject(emptyMap()))
        assertEquals(NovelAiGenerationChargeKind.V5_ALLOWANCE,
            NovelAiPostProcessPolicy.enhanceCost(source, 1024, 1024, NovelAiEnhanceOptions(), opus).kind)
        assertEquals(8, NovelAiPostProcessPolicy.enhanceCost(source, 1024, 1024,
            NovelAiEnhanceOptions(), opus.copy(v5AllowanceExhausted = true)).anlas)
    }

    @Test fun highAndV45KeepTheirPreviousRoundingAndImg2imgEstimates() {
        for ((model, expected) in listOf(NovelAiImageModel.V5_FULL to 30, NovelAiImageModel.V4_5_FULL to 20)) {
            val high = settings(steps = 28).copy(model = model, effort = NovelAiEffort.HIGH)
            assertEquals(expected, NovelAiImageCostEstimator.estimate(high, null).anlas)
            assertEquals(expected, NovelAiImageCostEstimator.estimate(high, null,
                NovelAiImageGuidanceDraft(action = NovelAiGenerationAction.IMAGE_TO_IMAGE)).anlas)
            val source = NovelAiEnhanceSource(NovelAiPromptPlan("scene", emptyList()), high, JsonObject(emptyMap()))
            assertEquals(if (model == NovelAiImageModel.V5_FULL) 18 else 12,
                NovelAiPostProcessPolicy.enhanceCost(source, 1024, 1024, NovelAiEnhanceOptions(strength = 0.6f), null).anlas)
        }
    }
}
