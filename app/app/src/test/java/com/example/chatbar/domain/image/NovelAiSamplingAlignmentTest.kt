package com.example.chatbar.domain.image

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class NovelAiSamplingAlignmentTest {
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
