package com.example.chatbar.domain.image

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NovelAiHighActionPricingTest {
    private val models = listOf(NovelAiImageModel.V4_5_FULL, NovelAiImageModel.V5_FULL)
    private val actions = listOf(NovelAiGenerationAction.IMAGE_TO_IMAGE, NovelAiGenerationAction.INPAINT)
    private fun settings(model: NovelAiImageModel, steps: Int = 28, count: Int = 1,
                         width: Int = 1024, height: Int = 1024) =
        NovelAiGenerationSettings(model = model, effort = NovelAiEffort.HIGH,
            steps = steps, count = count, customWidth = width, customHeight = height)
    private fun guidance(action: NovelAiGenerationAction, strength: Float) =
        NovelAiImageGuidanceDraft(action = action, imageToImageStrength = strength, inpaintStrength = strength)
    private val opus = NovelAiAccountUsage(1000, 3, true, 50.0, false)

    @Test fun highUsesActionStrengthAndMatchesTheActualSerializedField() {
        for (model in models) for (action in actions) {
            val high = settings(model)
            val strength = if (action == NovelAiGenerationAction.INPAINT) 0.6f else 0.5f
            val draft = guidance(action, strength).copy(imageToImageStrength = 0.5f)
            val wire = Json.parseToJsonElement(NovelAiImageService().buildRequestBody(
                NovelAiPromptPlan("synthetic scene", emptyList()), high.imageSize(), high,
                NovelAiPreparedImageGuidance(action, imageBase64 = "synthetic", maskBase64 = "synthetic",
                    imageToImageStrength = draft.imageToImageStrength, inpaintStrength = draft.inpaintStrength)))
                .jsonObject.getValue("parameters").jsonObject
            val field = if (action == NovelAiGenerationAction.INPAINT) "inpaintImg2ImgStrength" else "strength"
            assertEquals(strength, wire.getValue(field).jsonPrimitive.float)
            val expected = if (model == NovelAiImageModel.V5_FULL) {
                if (action == NovelAiGenerationAction.INPAINT) 18 else 15
            } else if (action == NovelAiGenerationAction.INPAINT) 12 else 10
            assertEquals(expected, NovelAiImageCostEstimator.estimate(high, null, draft).anlas)
        }
    }

    @Test fun highStrengthRoundingMinimumAndBatchFollowPerSamplePricing() {
        for (model in models) for (action in actions) {
            val fixtures = if (model == NovelAiImageModel.V5_FULL) {
                listOf(0f to 2, 0.2f to 6, 0.5f to 15, 0.6f to 18, 1f to 30)
            } else listOf(0f to 2, 0.2f to 4, 0.5f to 10, 0.6f to 12, 1f to 20)
            for ((strength, expected) in fixtures) for (count in listOf(1, 2, 4)) {
                assertEquals(expected * count, NovelAiImageCostEstimator.estimate(
                    settings(model, count = count), null, guidance(action, strength)).anlas)
            }
            // V5: ceil(17 * 1.5 * .35)=9; rounding the V5 price to26 first incorrectly yields10.
            assertEquals(if (model == NovelAiImageModel.V5_FULL) 9 else 6,
                NovelAiImageCostEstimator.estimate(settings(model, steps = 23), null, guidance(action, 0.35f)).anlas)
        }
    }

    @Test fun textGenerationAndHighAreaPoliciesRemainUnchanged() {
        for (model in models) {
            val high = settings(model)
            assertEquals(if (model == NovelAiImageModel.V5_FULL) 30 else 20,
                NovelAiImageCostEstimator.estimate(high, null).anlas)
            assertEquals(if (model == NovelAiImageModel.V5_FULL) 45 else 30,
                NovelAiImageCostEstimator.estimate(settings(model, width = 2048, height = 1536), null,
                    guidance(NovelAiGenerationAction.IMAGE_TO_IMAGE, 0.5f)).anlas)
            // Focused Inpaint uses its existing 1024² request, not the larger source profile.
            assertEquals(if (model == NovelAiImageModel.V5_FULL) 15 else 10,
                NovelAiImageCostEstimator.estimate(settings(model, width = 2048, height = 1536), null,
                    guidance(NovelAiGenerationAction.INPAINT, 0.5f)).anlas)
            assertEquals(if (model == NovelAiImageModel.V5_FULL) 3 else 2,
                NovelAiImageCostEstimator.estimate(settings(model, width = 64, height = 64), null).anlas)
            assertEquals(2, NovelAiImageCostEstimator.estimate(settings(model, width = 64, height = 64), null,
                guidance(NovelAiGenerationAction.IMAGE_TO_IMAGE, 0.5f)).anlas)
        }
    }

    @Test fun opusEligibilityAndAllowanceStaySeparateFromTheStrengthDiscount() {
        for (model in models) {
            val high = settings(model)
            val expected = if (model == NovelAiImageModel.V5_FULL) 15 else 10
            // Ordinary Img2Img remains paid; a smaller strength must not make it a free sample.
            assertEquals(expected, NovelAiImageCostEstimator.estimate(high, opus,
                guidance(NovelAiGenerationAction.IMAGE_TO_IMAGE, 0.5f)).anlas)
            val focused = guidance(NovelAiGenerationAction.INPAINT, 0.5f)
            val free = NovelAiImageCostEstimator.estimate(high, opus, focused)
            assertEquals(if (model == NovelAiImageModel.V5_FULL) NovelAiGenerationChargeKind.V5_ALLOWANCE
                else NovelAiGenerationChargeKind.FREE, free.kind)
            assertEquals(0, free.anlas)
            assertEquals(expected, NovelAiImageCostEstimator.estimate(high, opus.copy(active = false), focused).anlas)
            assertEquals(expected * 2, NovelAiImageCostEstimator.estimate(high.copy(count = 2), opus, focused).anlas)
            if (model == NovelAiImageModel.V5_FULL) {
                assertEquals(expected, NovelAiImageCostEstimator.estimate(high,
                    opus.copy(v5AllowancePercent = null), focused).anlas)
                assertEquals(expected, NovelAiImageCostEstimator.estimate(high,
                    opus.copy(v5AllowanceExhausted = true), focused).anlas)
            }
        }
    }

    @Test fun referenceAndEncodingFeesAreAddedAfterTheImageStrengthPrice() {
        val asset = NovelAiStudioAssetRef(path = "synthetic.png", sha256 = "synthetic-hash", width = 1024, height = 1024)
        val high = settings(NovelAiImageModel.V4_5_FULL, count = 2)
        val precise = guidance(NovelAiGenerationAction.IMAGE_TO_IMAGE, 0.5f).copy(
            referenceMode = NovelAiReferenceMode.PRECISE,
            preciseReference = NovelAiPreciseReferenceDraft(asset = asset))
        assertEquals(30, NovelAiImageCostEstimator.estimate(high, null, precise).anlas)
        val vibes = precise.copy(referenceMode = NovelAiReferenceMode.VIBE,
            vibes = List(5) { NovelAiVibeReferenceDraft(asset = asset) })
        val cost = NovelAiImageCostEstimator.estimate(high, null, vibes, vibeCacheMisses = 2)
        assertEquals(28, cost.anlas)
        assertEquals(4, cost.extraVibeAnlas)
        assertEquals(4, cost.encodingAnlas)
    }
}
