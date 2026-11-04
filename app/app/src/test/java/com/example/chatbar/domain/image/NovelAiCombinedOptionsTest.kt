package com.example.chatbar.domain.image

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NovelAiCombinedOptionsTest {
    private val plan = NovelAiPromptPlan("scene, teXt: Hello|later", listOf(
        NovelAiCharacterCaption("person", DesignedCharacterCenter(0.3f, 0.7f), "saved role negative")
    ), negativePrompt = "saved custom negative")
    private val saved = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, steps = 41,
        guidance = 8.5f, cfgRescale = 0.6f, sampler = NovelAiSampler.DPM_PLUS_PLUS_2M_SDE,
        varietyPlus = true, seed = 123)

    @Test fun highMediumAndTransparencyMatrixUsesTheProductionSerializerForEveryAction() {
        for (effort in NovelAiEffort.entries) for (transparent in listOf(false, true)) {
            val settings = saved.copy(effort = effort, transparentBackground = transparent)
            for (action in NovelAiGenerationAction.entries) {
                val body = request(settings, action)
                val medium = effort == NovelAiEffort.MEDIUM
                assertEquals((if (medium) "nai-diffusion-5-full-medium" else "nai-diffusion-5-full") +
                    (if (action == NovelAiGenerationAction.INPAINT) "-inpainting" else ""), body.getValue("model").jsonPrimitive.content)
                val parameters = body.getValue("parameters").jsonObject
                assertEquals(if (medium) 14 else 41, parameters.getValue("steps").jsonPrimitive.int)
                assertEquals(if (medium) "k_euler_ancestral" else "k_dpmpp_2m_sde", parameters.getValue("sampler").jsonPrimitive.content)
                assertEquals(if (medium) 0f else 0.6f, parameters.getValue("cfg_rescale").jsonPrimitive.float)
                assertEquals(8.5f, parameters.getValue("scale").jsonPrimitive.float)
                assertEquals(transparent, parameters.getValue("tag_hint_transparent_background").jsonPrimitive.boolean)
                assertTrue(parameters.getValue("straight_alpha").jsonPrimitive.boolean)
                assertFalse(parameters.containsKey("skip_cfg_above_sigma"))
                val expectedBase = if (transparent) "scene, transparent background, teXt: Hello|later" else plan.baseCaption
                assertEquals(expectedBase, body.getValue("input").jsonPrimitive.content)
                assertEquals(expectedBase, parameters.getValue("v4_prompt").jsonObject.getValue("caption").jsonObject
                    .getValue("base_caption").jsonPrimitive.content)
                val negative = parameters.getValue("negative_prompt").jsonPrimitive.content
                if (medium) {
                    assertEquals(2, parameters.getValue("tag_hint_uc_preset").jsonPrimitive.int)
                    assertTrue(negative.startsWith("nsfw, "))
                    assertFalse(negative.contains("saved custom negative"))
                } else assertEquals(plan.negativePrompt, negative)
                val negativeCaption = parameters.getValue("v4_negative_prompt").jsonObject.getValue("caption").jsonObject
                assertEquals(negative, negativeCaption.getValue("base_caption").jsonPrimitive.content)
                assertEquals(if (medium) "" else "saved role negative", negativeCaption.getValue("char_captions").jsonArray
                    .single().jsonObject.getValue("char_caption").jsonPrimitive.content)
            }
            assertEquals(41, settings.steps)
            assertEquals(0.6f, settings.cfgRescale)
            assertEquals("saved custom negative", plan.negativePrompt)
        }
    }

    @Test fun combinedCardAndPngSettingsSurviveEnhanceWithoutReinjectionOrFixedValueOverrides() {
        val profile = NovelAiCharacterImageSettings(effort = NovelAiEffort.MEDIUM, transparentBackground = true,
            steps = 41, cfgRescale = 0.6f, sampler = NovelAiSampler.DPM_PLUS_PLUS_2M_SDE)
        val decoded = Json.decodeFromString(NovelAiCharacterImageSettings.serializer(),
            Json.encodeToString(NovelAiCharacterImageSettings.serializer(), profile))
        val settings = decoded.applyTo(saved)
        assertTrue(settings.isMediumEffort)
        assertTrue(settings.transparentBackground)
        val reset = decoded.withDefaultSampling(NovelAiImageModel.V5_FULL)
        assertEquals(23, reset.steps)
        assertEquals(7f, reset.guidance)
        assertEquals(0f, reset.cfgRescale)
        assertEquals(NovelAiSampler.EULER_ANCESTRAL, reset.sampler)
        assertTrue(decoded.withDefaultSampling(NovelAiImageModel.V5_FULL).transparentBackground)
        assertEquals(NovelAiEffort.MEDIUM, decoded.withDefaultSampling(NovelAiImageModel.V5_FULL).effort)
        val body = request(settings)
        val comment = JsonObject(body.getValue("parameters").jsonObject + mapOf("model" to body.getValue("model")))
        val metadata = requireNotNull(NovelAiPngMetadataReader.parseStudioComment(comment.toString(), "/synthetic.png"))
        val imported = NovelAiStudioDraft().applyImportedMetadata(metadata, NovelAiStudioMetadataSelection())
        assertTrue(imported.activeSettings.isMediumEffort)
        assertTrue(imported.activeSettings.transparentBackground)
        val source = NovelAiPngMetadataReader.parseEnhanceComment(comment.toString(), "/synthetic.png", null)
        val replay = JsonObject(source.parameters + buildJsonObject {
            put("steps", 50); put("sampler", "k_euler"); put("cfg_rescale", 0.8)
            put("skip_cfg_above_sigma", 99)
        })
        val enhanced = Json.parseToJsonElement(NovelAiImageService().buildRequestBody(
            source.prompt, settings.imageSize(), source.settings,
            NovelAiPreparedImageGuidance(NovelAiGenerationAction.IMAGE_TO_IMAGE, imageBase64 = "synthetic"),
            NovelAiEnhanceRequestOptions(false, replay)
        )).jsonObject
        assertEquals(body.getValue("input"), enhanced.getValue("input"))
        val parameters = enhanced.getValue("parameters").jsonObject
        assertEquals(14, parameters.getValue("steps").jsonPrimitive.int)
        assertEquals("k_euler_ancestral", parameters.getValue("sampler").jsonPrimitive.content)
        assertEquals(0f, parameters.getValue("cfg_rescale").jsonPrimitive.float)
        assertTrue(parameters.getValue("tag_hint_transparent_background").jsonPrimitive.boolean)
        assertFalse(parameters.containsKey("skip_cfg_above_sigma"))
    }

    @Test fun v45SuspendsBothOptionsAndSwitchingBackToHighKeepsCustomValuesAndNegatives() {
        val medium = saved.copy(effort = NovelAiEffort.MEDIUM, transparentBackground = true)
        val suspended = medium.copy(model = NovelAiImageModel.V4_5_FULL)
        val v45 = request(suspended).getValue("parameters").jsonObject
        assertEquals(41, v45.getValue("steps").jsonPrimitive.int)
        assertEquals(plan.negativePrompt, v45.getValue("negative_prompt").jsonPrimitive.content)
        assertFalse(v45.containsKey("tag_hint_transparent_background"))
        assertEquals(plan.baseCaption, request(suspended).getValue("input").jsonPrimitive.content)
        val high = suspended.copy(model = NovelAiImageModel.V5_FULL, effort = NovelAiEffort.HIGH)
        val restored = request(high).getValue("parameters").jsonObject
        assertEquals(41, restored.getValue("steps").jsonPrimitive.int)
        assertEquals(0.6f, restored.getValue("cfg_rescale").jsonPrimitive.float)
        assertEquals("k_dpmpp_2m_sde", restored.getValue("sampler").jsonPrimitive.content)
        assertEquals(plan.negativePrompt, restored.getValue("negative_prompt").jsonPrimitive.content)
        assertTrue(high.transparentBackground)
        assertEquals(saved, medium.copy(effort = NovelAiEffort.HIGH, transparentBackground = false))
    }

    private fun request(settings: NovelAiGenerationSettings,
                        action: NovelAiGenerationAction = NovelAiGenerationAction.TEXT_TO_IMAGE): JsonObject =
        Json.parseToJsonElement(NovelAiImageService().buildRequestBody(plan, settings.imageSize(), settings,
            NovelAiPreparedImageGuidance(action, imageBase64 = "synthetic", maskBase64 = "synthetic"))).jsonObject
}
