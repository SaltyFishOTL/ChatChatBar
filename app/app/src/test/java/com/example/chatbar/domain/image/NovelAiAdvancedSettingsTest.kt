package com.example.chatbar.domain.image

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NovelAiAdvancedSettingsTest {
    @Test fun cardOverridesOnlyGenerationParametersAndRoundTrips() {
        val profile = NovelAiCharacterImageSettings(steps = 41, guidance = 7.2f, cfgRescale = 0.3f,
            sampler = NovelAiSampler.DPM_PLUS_PLUS_2M, varietyPlus = true)
        val original = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL,
            count = 3, seedMode = NovelAiSeedMode.FIXED, seed = 123,
            customWidth = 1024, customHeight = 1024, useCharacterPositions = true)
        val result = profile.applyTo(original)
        assertEquals(original.copy(steps = 41, guidance = 7.2f, cfgRescale = 0.3f,
            sampler = NovelAiSampler.DPM_PLUS_PLUS_2M, varietyPlus = true), result)
        assertEquals(profile, Json.decodeFromString(NovelAiCharacterImageSettings.serializer(),
            Json.encodeToString(NovelAiCharacterImageSettings.serializer(), profile)))
    }

    @Test fun unsupportedLegacySamplerIsNormalizedForEveryModel() {
        for (model in NovelAiImageModel.entries) {
            val settings = NovelAiGenerationSettings(model = model, sampler = NovelAiSampler.DDIM)
            assertNotNull(settings.validationError(0))
            assertEquals(NovelAiSampler.EULER_ANCESTRAL, settings.normalized().sampler)
            assertNull(settings.normalized().validationError(0))
        }
    }

    @Test fun varietyPlusRequestIsGatedByModelAndSurvivesStoredSettings() {
        for (model in NovelAiImageModel.entries) for (enabled in listOf(false, true)) {
            val settings = NovelAiGenerationSettings(model = model, varietyPlus = enabled)
            val body = Json.parseToJsonElement(NovelAiImageService().buildRequestBody(
                prompt = NovelAiPromptPlan("scene", emptyList()),
                imageSize = NovelAiImageSize(832, 1216, "test"), settings = settings)).jsonObject
            val parameters = body.getValue("parameters").jsonObject
            if (model == NovelAiImageModel.V5_FULL) assertFalse(parameters.containsKey("skip_cfg_above_sigma"))
            else assertEquals(if (enabled) "58" else "null", parameters.getValue("skip_cfg_above_sigma").jsonPrimitive.content)
            assertEquals(enabled, settings.normalized().varietyPlus)
        }
    }
}
