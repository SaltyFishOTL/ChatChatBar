package com.example.chatbar.domain.image

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NovelAiMediumEffortTest {
    private val plan = NovelAiPromptPlan("synthetic scene", listOf(
        NovelAiCharacterCaption("person", DesignedCharacterCenter(0.3f, 0.7f), "saved role negative")
    ), negativePrompt = "saved custom negative")

    @Test fun mediumUsesItsOwnModelAndFixedWireWithoutChangingSavedValues() {
        val saved = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, effort = NovelAiEffort.MEDIUM,
            steps = 41, guidance = 8.5f, cfgRescale = 0.6f, sampler = NovelAiSampler.DPM_PLUS_PLUS_2M,
            varietyPlus = true, seed = 123, useCharacterPositions = true)
        for (action in NovelAiGenerationAction.entries) {
            val body = request(saved, action)
            assertEquals("nai-diffusion-5-full-medium" + if (action == NovelAiGenerationAction.INPAINT) "-inpainting" else "",
                body.getValue("model").jsonPrimitive.content)
            val parameters = body.getValue("parameters").jsonObject
            assertEquals(14, parameters.getValue("steps").jsonPrimitive.int)
            assertEquals("k_euler_ancestral", parameters.getValue("sampler").jsonPrimitive.content)
            assertEquals(0f, parameters.getValue("cfg_rescale").jsonPrimitive.float)
            assertEquals(8.5f, parameters.getValue("scale").jsonPrimitive.float)
            assertEquals(123L, parameters.getValue("seed").jsonPrimitive.long)
            assertEquals(2, parameters.getValue("tag_hint_uc_preset").jsonPrimitive.int)
            assertFalse(parameters.containsKey("skip_cfg_above_sigma"))
            val negative = parameters.getValue("negative_prompt").jsonPrimitive.content
            assertTrue(negative.startsWith("nsfw, "))
            assertFalse(negative.contains("saved custom negative"))
            val caption = parameters.getValue("v4_negative_prompt").jsonObject.getValue("caption").jsonObject
            assertEquals(negative, caption.getValue("base_caption").jsonPrimitive.content)
            assertEquals("", caption.getValue("char_captions").jsonArray.single().jsonObject
                .getValue("char_caption").jsonPrimitive.content)
            assertTrue(parameters.getValue("use_coords").jsonPrimitive.boolean)
        }
        assertEquals(41, saved.steps)
        assertEquals(0.6f, saved.cfgRescale)
        assertEquals(NovelAiSampler.DPM_PLUS_PLUS_2M, saved.sampler)
        assertEquals("saved custom negative", plan.negativePrompt)
        assertEquals("saved role negative", plan.characterCaptions.single().negativePrompt)
        val high = request(saved.copy(effort = NovelAiEffort.HIGH)).getValue("parameters").jsonObject
        assertEquals(41, high.getValue("steps").jsonPrimitive.int)
        assertEquals(0.6f, high.getValue("cfg_rescale").jsonPrimitive.float)
        assertEquals("k_dpmpp_2m", high.getValue("sampler").jsonPrimitive.content)
        assertEquals(plan.negativePrompt, high.getValue("negative_prompt").jsonPrimitive.content)
    }

    @Test fun v45SuspendsMediumAndLegacySettingsDecodeAsHigh() {
        val settings = NovelAiGenerationSettings(effort = NovelAiEffort.MEDIUM, steps = 42, varietyPlus = true)
        val body = request(settings)
        assertEquals("nai-diffusion-4-5-full", body.getValue("model").jsonPrimitive.content)
        val parameters = body.getValue("parameters").jsonObject
        assertEquals(42, parameters.getValue("steps").jsonPrimitive.int)
        assertEquals(58, parameters.getValue("skip_cfg_above_sigma").jsonPrimitive.int)
        assertEquals(plan.negativePrompt, parameters.getValue("negative_prompt").jsonPrimitive.content)
        assertEquals(NovelAiEffort.HIGH, Json.decodeFromString(NovelAiGenerationSettings.serializer(), "{}").effort)
        assertEquals(NovelAiEffort.HIGH, Json.decodeFromString(NovelAiCharacterImageSettings.serializer(), "{}").effort)
    }

    @Test fun enhanceReplayCannotOverrideMediumContract() {
        val settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, effort = NovelAiEffort.MEDIUM)
        val parameters = request(settings, NovelAiGenerationAction.IMAGE_TO_IMAGE, NovelAiEnhanceRequestOptions(false,
            buildJsonObject {
                put("steps", 50); put("sampler", "k_euler"); put("cfg_rescale", 0.8)
                put("negative_prompt", "replayed custom"); put("v4_negative_prompt", buildJsonObject {})
            })).getValue("parameters").jsonObject
        assertEquals(14, parameters.getValue("steps").jsonPrimitive.int)
        assertEquals("k_euler_ancestral", parameters.getValue("sampler").jsonPrimitive.content)
        assertEquals(0f, parameters.getValue("cfg_rescale").jsonPrimitive.float)
        assertFalse(parameters.getValue("negative_prompt").jsonPrimitive.content.contains("custom"))
        assertTrue(parameters.getValue("v4_negative_prompt").jsonObject.containsKey("caption"))
    }

    @Test fun pngIdentityAndExplicitModelPrecedenceSurviveImportAndEnhance() {
        for (source in listOf("nai-diffusion-5-full-medium", "nai-diffusion-5-full-medium-inpainting",
            "NovelAI Diffusion V5 93F4BD30", "NovelAI Diffusion V5 70AB5786")) {
            val comment = """{"prompt":"scene","uc":"old","steps":14,"sampler":"k_euler_ancestral","seed":42,"width":832,"height":1216}"""
            val metadata = requireNotNull(NovelAiPngMetadataReader.parseStudioComment(comment, "/synthetic.png", source))
            assertEquals(NovelAiEffort.MEDIUM, metadata.settings.effort)
            val draft = NovelAiStudioDraft().applyImportedMetadata(metadata, NovelAiStudioMetadataSelection())
            assertTrue(draft.activeSettings.isMediumEffort)
            val restored = NovelAiStudioDraft().applyHistoryRecipe(draft.toRecipe(), 42, NovelAiHistoryApplyMode.FULL)
            assertTrue(restored.activeSettings.isMediumEffort)
            if (!source.endsWith("inpainting")) {
                assertTrue(NovelAiPngMetadataReader.parseEnhanceComment(comment, "/synthetic.png", source).settings.isMediumEffort)
            }
            val explicitHigh = comment.dropLast(1) + """, "model":"nai-diffusion-5-full"}"""
            assertEquals(NovelAiEffort.HIGH,
                NovelAiPngMetadataReader.parseStudioComment(explicitHigh, "/synthetic.png", source)?.settings?.effort)
        }
    }

    @Test fun cardAndStoredDraftKeepEffortAndCostUsesEffectiveSteps() {
        val profile = NovelAiCharacterImageSettings(effort = NovelAiEffort.MEDIUM, steps = 50, guidance = 9f)
        val settings = profile.applyTo(NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL))
        assertTrue(settings.isMediumEffort)
        assertEquals(profile, Json.decodeFromString(NovelAiCharacterImageSettings.serializer(),
            Json.encodeToString(NovelAiCharacterImageSettings.serializer(), profile)))
        val draft = NovelAiStudioDraft().withActiveSettings(settings)
        assertEquals(draft, Json.decodeFromString(NovelAiStudioDraft.serializer(),
            Json.encodeToString(NovelAiStudioDraft.serializer(), draft)))
        assertEquals(NovelAiImageCostEstimator.estimate(settings.copy(steps = 14), null),
            NovelAiImageCostEstimator.estimate(settings, null))
    }

    private fun request(settings: NovelAiGenerationSettings,
                        action: NovelAiGenerationAction = NovelAiGenerationAction.TEXT_TO_IMAGE,
                        enhance: NovelAiEnhanceRequestOptions? = null): JsonObject =
        Json.parseToJsonElement(NovelAiImageService().buildRequestBody(plan, settings.imageSize(), settings,
            NovelAiPreparedImageGuidance(action, imageBase64 = "synthetic", maskBase64 = "synthetic"), enhance)).jsonObject
}
