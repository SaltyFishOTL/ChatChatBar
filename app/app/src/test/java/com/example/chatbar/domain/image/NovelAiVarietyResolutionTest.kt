package com.example.chatbar.domain.image

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class NovelAiVarietyResolutionTest {
    @Test fun pngSigmaIsNormalizedOnceAndEnhanceUsesTheNewCanvas() {
        val source = """{"model":"nai-diffusion-4-5-full","prompt":"scene","uc":"negative","steps":23,"sampler":"k_euler_ancestral","seed":42,"width":512,"height":768,"skip_cfg_above_sigma":37.0}"""
        val metadata = requireNotNull(NovelAiPngMetadataReader.parseStudioComment(source, "/synthetic.png"))
        val imported = NovelAiStudioDraft().applyImportedMetadata(metadata, NovelAiStudioMetadataSelection())
        assertTrue(imported.activeSettings.varietyPlus)
        val expectedBase = 37.0 / sqrt(512.0 * 768 / (832 * 1216))
        assertEquals(expectedBase, requireNotNull(imported.activeSettings.varietyPlusSigma), 0.00001)
        val stored = Json.decodeFromString(NovelAiStudioDraft.serializer(),
            Json.encodeToString(NovelAiStudioDraft.serializer(), imported))
        val restored = NovelAiStudioDraft().applyHistoryRecipe(stored.toRecipe(), 42, NovelAiHistoryApplyMode.FULL)
        val initial = parameters(restored.activeSettings, restored.activeSettings.imageSize())
        assertEquals(37.0, initial.getValue("skip_cfg_above_sigma").jsonPrimitive.double, 0.00001)
        val enhance = NovelAiPngMetadataReader.parseEnhanceComment(source, "/synthetic.png", null)
        val bigger = NovelAiImageSize(1024, 1024, "synthetic")
        val resized = parameters(enhance.settings, bigger, NovelAiEnhanceRequestOptions(false, enhance.parameters))
        assertEquals(expectedBase * sqrt(1024.0 * 1024 / (832 * 1216)),
            resized.getValue("skip_cfg_above_sigma").jsonPrimitive.double, 0.00001)
        assertEquals(JsonNull, parameters(enhance.settings.copy(varietyPlus = false), bigger,
            NovelAiEnhanceRequestOptions(false, enhance.parameters)).getValue("skip_cfg_above_sigma"))
    }

    @Test fun v5DoesNotSendImportedV45SigmaEvenDuringEnhance() {
        val settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL,
            varietyPlus = true, varietyPlusSigma = 37.0)
        val replay = NovelAiEnhanceRequestOptions(false, buildJsonObject { put("skip_cfg_above_sigma", 99) })
        assertFalse(parameters(settings, settings.imageSize(), replay).containsKey("skip_cfg_above_sigma"))
    }

    @Test fun varietyPlusScalesWithTheActualRequestCanvas() {
        for ((width, height) in listOf(832 to 1216, 512 to 768, 1024 to 1024, 1536 to 1024)) {
            val settings = NovelAiGenerationSettings(varietyPlus = true, customWidth = width, customHeight = height)
            val parameters = Json.parseToJsonElement(NovelAiImageService().buildRequestBody(
                NovelAiPromptPlan("synthetic scene", emptyList()), settings.imageSize(), settings
            )).jsonObject.getValue("parameters").jsonObject
            val expected = 58 * sqrt((width / 8).toDouble() * (height / 8) / (104 * 152))
            assertEquals(expected, parameters.getValue("skip_cfg_above_sigma").jsonPrimitive.double, 0.00001)
        }
    }

    private fun parameters(settings: NovelAiGenerationSettings, size: NovelAiImageSize,
                           enhance: NovelAiEnhanceRequestOptions? = null): JsonObject =
        Json.parseToJsonElement(NovelAiImageService().buildRequestBody(
            NovelAiPromptPlan("synthetic scene", emptyList()), size, settings,
            if (enhance == null) NovelAiPreparedImageGuidance.NONE else NovelAiPreparedImageGuidance(
                NovelAiGenerationAction.IMAGE_TO_IMAGE, imageBase64 = "synthetic"), enhance
        )).jsonObject.getValue("parameters").jsonObject
}
