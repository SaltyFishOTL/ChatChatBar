package com.example.chatbar.domain.image

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class NovelAiSamplingAlignmentTest {
    @Test fun explicitResetUsesModelDefaultsAndPreservesOtherSettings() {
        for (model in NovelAiImageModel.entries) {
            val before = NovelAiGenerationSettings(model = model, steps = 41, guidance = 9f,
                cfgRescale = 0.5f, sampler = NovelAiSampler.DPM_PLUS_PLUS_2M,
                varietyPlus = true, seed = 123, count = 3, customWidth = 768, customHeight = 512,
                useCharacterPositions = true)
            val expected = before.copy(steps = 23, guidance = if (model == NovelAiImageModel.V5_FULL) 7f else 5f,
                cfgRescale = 0f, sampler = NovelAiSampler.EULER_ANCESTRAL)
            assertEquals(expected, before.withDefaultSampling())
            val profile = NovelAiCharacterImageSettings(steps = 42, guidance = 9f, cfgRescale = 0.8f,
                sampler = NovelAiSampler.DPM_PLUS_PLUS_2M, varietyPlus = true)
            assertEquals(profile.copy(steps = expected.steps, guidance = expected.guidance,
                cfgRescale = 0f, sampler = expected.sampler), profile.withDefaultSampling(model))
            assertEquals(before, Json.decodeFromString(NovelAiGenerationSettings.serializer(),
                Json.encodeToString(NovelAiGenerationSettings.serializer(), before)))
        }
    }

    @Test fun legacyMissingValuesKeepTheirPreviousMeaning() {
        val legacy = Json.decodeFromString(NovelAiGenerationSettings.serializer(), "{}")
        assertEquals(28, legacy.steps)
        assertEquals(6f, legacy.guidance)
        val profile = Json.decodeFromString(NovelAiCharacterImageSettings.serializer(), "{}")
        assertEquals(28, profile.steps)
        assertEquals(8f, profile.guidance)
    }

    @Test fun modernModelsOfferTheSixOfficialSamplers() {
        val expected = setOf(
            NovelAiSampler.EULER_ANCESTRAL, NovelAiSampler.EULER,
            NovelAiSampler.DPM_PLUS_PLUS_2S_ANCESTRAL, NovelAiSampler.DPM_PLUS_PLUS_2M_SDE,
            NovelAiSampler.DPM_PLUS_PLUS_2M, NovelAiSampler.DPM_PLUS_PLUS_SDE
        )
        for (model in NovelAiImageModel.entries) assertEquals(expected, model.samplers.toSet())
    }

    @Test fun v45Dpmpp2mSdeReachesProductionRequest() {
        val settings = NovelAiGenerationSettings(sampler = NovelAiSampler.DPM_PLUS_PLUS_2M_SDE)
        val body = Json.parseToJsonElement(NovelAiImageService().buildRequestBody(
            NovelAiPromptPlan("synthetic scene", emptyList()), settings.imageSize(), settings
        )).jsonObject
        assertEquals("k_dpmpp_2m_sde", body.getValue("parameters").jsonObject
            .getValue("sampler").jsonPrimitive.content)
    }
}
