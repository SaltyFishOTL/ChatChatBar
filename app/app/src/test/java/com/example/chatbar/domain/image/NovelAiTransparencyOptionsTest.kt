package com.example.chatbar.domain.image

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NovelAiTransparencyOptionsTest {
    @Test fun transparencyIsOptInAndV5OnlyAcrossAllActions() {
        for (model in NovelAiImageModel.entries) for (enabled in listOf(false, true)) {
            for (action in NovelAiGenerationAction.entries) {
                val plan = NovelAiPromptPlan("scene", listOf(
                    NovelAiCharacterCaption("person", DesignedCharacterCenter(0.5f, 0.5f), "role negative")
                ), negativePrompt = "base negative")
                val settings = NovelAiGenerationSettings(model = model, transparentBackground = enabled)
                val body = request(plan, settings, action)
                val parameters = body.getValue("parameters").jsonObject
                val expected = if (model == NovelAiImageModel.V5_FULL && enabled) "scene, transparent background" else "scene"
                assertEquals(expected, body.getValue("input").jsonPrimitive.content)
                assertEquals(expected, parameters.getValue("v4_prompt").jsonObject
                    .getValue("caption").jsonObject.getValue("base_caption").jsonPrimitive.content)
                assertEquals("base negative", parameters.getValue("negative_prompt").jsonPrimitive.content)
                if (model == NovelAiImageModel.V5_FULL) {
                    assertEquals(enabled, parameters.getValue("tag_hint_transparent_background").jsonPrimitive.boolean)
                    assertTrue(parameters.getValue("straight_alpha").jsonPrimitive.boolean)
                } else {
                    assertFalse(parameters.containsKey("tag_hint_transparent_background"))
                    assertFalse(parameters.containsKey("straight_alpha"))
                }
                assertEquals("scene", plan.baseCaption)
                assertEquals("role negative", plan.characterCaptions.single().negativePrompt)
            }
        }
    }

    @Test fun suffixPrecedesExplicitAndAutomaticTextAndDoesNotChangeLaterChunks() {
        val settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, transparentBackground = true)
        val cases = mapOf(
            "scene, teXt: Hello|later" to "scene, transparent background, teXt: Hello|later",
            "scene ||red|blue||, teXt: Hello|later" to "scene ||red|blue||, transparent background, teXt: Hello|later",
            "scene, transparent background|later" to "scene, transparent background|later",
            "scene \"Hello\"|later" to "scene \"Hello\", transparent background, teXt: Hello|later"
        )
        for ((source, expected) in cases) {
            assertEquals(expected, request(NovelAiPromptPlan(source, emptyList()), settings)
                .getValue("input").jsonPrimitive.content)
        }
    }

    @Test fun optionSurvivesCardDraftRecipeHistoryAndPngImportWithoutDuplicateSuffix() {
        val settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, transparentBackground = true)
        val card = NovelAiCharacterImageSettings(transparentBackground = true)
        assertTrue(card.applyTo(settings.copy(transparentBackground = false)).transparentBackground)
        assertTrue(Json.decodeFromString(NovelAiCharacterImageSettings.serializer(),
            Json.encodeToString(NovelAiCharacterImageSettings.serializer(), card)).transparentBackground)
        val draft = NovelAiStudioDraft(basePrompt = "scene").withActiveSettings(settings)
        val stored = Json.decodeFromString(NovelAiStudioDraft.serializer(),
            Json.encodeToString(NovelAiStudioDraft.serializer(), draft))
        val restored = NovelAiStudioDraft().applyHistoryRecipe(stored.toRecipe(), 42, NovelAiHistoryApplyMode.FULL)
        assertTrue(restored.activeSettings.transparentBackground)
        assertFalse(Json.decodeFromString(NovelAiGenerationSettings.serializer(), "{}").transparentBackground)
        val body = request(NovelAiPromptPlan("scene", emptyList()), settings)
        val comment = JsonObject(body.getValue("parameters").jsonObject + mapOf("model" to body.getValue("model")))
        val imported = requireNotNull(NovelAiPngMetadataReader.parseStudioComment(comment.toString(), "/synthetic.png"))
        val merged = NovelAiStudioDraft().applyImportedMetadata(imported, NovelAiStudioMetadataSelection())
        assertTrue(merged.activeSettings.transparentBackground)
        assertEquals("scene, transparent background", request(NovelAiPromptPlan(merged.basePrompt, emptyList()), merged.activeSettings)
            .getValue("input").jsonPrimitive.content)
        val enhance = NovelAiPngMetadataReader.parseEnhanceComment(comment.toString(), "/synthetic.png", null)
        assertTrue(enhance.settings.transparentBackground)
    }

    private fun request(plan: NovelAiPromptPlan, settings: NovelAiGenerationSettings,
                        action: NovelAiGenerationAction = NovelAiGenerationAction.TEXT_TO_IMAGE): JsonObject =
        Json.parseToJsonElement(NovelAiImageService().buildRequestBody(
            plan, settings.imageSize(), settings,
            NovelAiPreparedImageGuidance(action = action, imageBase64 = "synthetic", maskBase64 = "synthetic")
        )).jsonObject
}
