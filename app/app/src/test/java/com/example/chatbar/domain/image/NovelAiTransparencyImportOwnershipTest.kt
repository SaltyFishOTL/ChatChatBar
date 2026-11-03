package com.example.chatbar.domain.image

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NovelAiTransparencyImportOwnershipTest {
    private val settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, transparentBackground = true)
    private val manage = NovelAiStudioMetadataSelection(transparentSuffix = NovelAiTransparentSuffixImport.MANAGE_WITH_SWITCH)

    @Test fun explicitlyManagedSuffixCanBeTurnedOffAfterFullPngRoundTrip() {
        val original = NovelAiStudioDraft(basePrompt = "scene").withActiveSettings(settings)
        val sent = request(original)
        val comment = JsonObject(sent.getValue("parameters").jsonObject + ("model" to sent.getValue("model")))
        val metadata = requireNotNull(NovelAiPngMetadataReader.parseStudioComment(comment.toString(), "/synthetic.png"))
        assertTrue(metadata.hasAmbiguousTransparentSuffix)
        val imported = NovelAiStudioDraft().applyImportedMetadata(metadata, manage)
        assertEquals("scene", imported.basePrompt)
        assertEquals("scene, transparent background", metadata.positivePrompt)
        assertEquals("scene, transparent background", request(imported).getValue("input").jsonPrimitive.content)
        val off = imported.withActiveSettings(imported.activeSettings.copy(transparentBackground = false))
        val body = request(off)
        assertEquals("scene", body.getValue("input").jsonPrimitive.content)
        val params = body.getValue("parameters").jsonObject
        assertEquals("scene", params.getValue("v4_prompt").jsonObject.getValue("caption").jsonObject
            .getValue("base_caption").jsonPrimitive.content)
        assertFalse(params.getValue("tag_hint_transparent_background").jsonPrimitive.boolean)
    }

    @Test fun ambiguousHandwrittenSuffixDefaultsToVerbatimRetention() {
        val source = metadata("scene, transparent background")
        val imported = NovelAiStudioDraft().applyImportedMetadata(source, NovelAiStudioMetadataSelection())
        val off = imported.withActiveSettings(imported.activeSettings.copy(transparentBackground = false))
        assertEquals(source.positivePrompt, off.basePrompt)
        assertEquals(source.positivePrompt, request(off).getValue("input").jsonPrimitive.content)
        assertEquals(source.positivePrompt, NovelAiStudioDraft().applyImportedMetadata(
            source.copy(settings = source.settings.copy(transparentBackground = false)), manage).basePrompt)
    }

    @Test fun removalIsRestrictedToOneExactFirstChunkSuffixBeforeText() {
        val source = "{transparent background}, has alpha, transparent background, text: transparent background|later, transparent background"
        val expected = "{transparent background}, has alpha, text: transparent background|later, transparent background"
        val imported = NovelAiStudioDraft().applyImportedMetadata(metadata(source), manage)
        assertEquals(expected, imported.basePrompt)
        assertEquals("role, transparent background", imported.characters.single().prompt)
        for (prompt in listOf("scene|later, transparent background", "scene, {transparent background}",
            "scene, alpha transparency", "text: transparent background", "scene, ||transparent background||")) {
            val item = metadata(prompt)
            assertFalse(item.hasAmbiguousTransparentSuffix)
            assertEquals(prompt, NovelAiStudioDraft().applyImportedMetadata(item, manage).basePrompt)
        }
        assertEquals("text: literal", NovelAiV5TextPromptPolicy.withoutTransparentBackgroundSuffix("transparent background, text: literal"))
        assertEquals("||cat|dog||", NovelAiV5TextPromptPolicy.withoutTransparentBackgroundSuffix("||cat|dog||, transparent background"))
    }

    @Test fun positiveAndSettingsSelectionRemainIndependent() {
        val original = NovelAiStudioDraft(basePrompt = "existing").withActiveSettings(settings.copy(transparentBackground = false))
        val source = metadata("scene, transparent background")
        val positiveOnly = original.applyImportedMetadata(source, manage.copy(generationSettings = false))
        assertEquals("scene", positiveOnly.basePrompt)
        assertFalse(positiveOnly.activeSettings.transparentBackground)
        assertEquals("scene", request(positiveOnly).getValue("input").jsonPrimitive.content)
        val settingsOnly = original.applyImportedMetadata(source, manage.copy(positivePrompt = false))
        assertEquals("existing", settingsOnly.basePrompt)
        assertTrue(settingsOnly.activeSettings.transparentBackground)
        val suspended = original.applyImportedMetadata(source, manage.copy(generationSettings = false))
            .withActiveSettings(NovelAiGenerationSettings(model = NovelAiImageModel.V4_5_FULL))
        assertEquals("scene", request(suspended).getValue("input").jsonPrimitive.content)
    }

    @Test fun historyRecipeUsesKnownLogicalTextButEnhanceKeepsResolvedCaption() {
        for (effort in NovelAiEffort.entries) {
            val logical = NovelAiStudioDraft(basePrompt = "scene").withActiveSettings(settings.copy(effort = effort))
            val restored = NovelAiStudioDraft().applyHistoryRecipe(logical.toRecipe(), 12, NovelAiHistoryApplyMode.FULL)
                .withActiveSettings(logical.activeSettings.copy(transparentBackground = false))
            assertEquals("scene", request(restored).getValue("input").jsonPrimitive.content)
            val comment = """{"model":"${logical.activeSettings.requestModelId}","width":1024,"height":1024,
                "v4_prompt":{"caption":{"base_caption":"unresolved, transparent background","char_captions":[]}},
                "actual_prompts":{"prompt":{"base_caption":"chosen， caption, transparent background","char_captions":[]}},
                "tag_hint_transparent_background":true}"""
            val enhance = NovelAiPngMetadataReader.parseEnhanceComment(comment, "/synthetic.png", null)
            val body = Json.parseToJsonElement(NovelAiImageService().buildRequestBody(enhance.prompt,
                enhance.settings.imageSize(), enhance.settings,
                NovelAiPreparedImageGuidance(NovelAiGenerationAction.IMAGE_TO_IMAGE, imageBase64 = "synthetic"),
                NovelAiEnhanceRequestOptions(false, enhance.parameters))).jsonObject
            assertEquals("chosen， caption, transparent background", body.getValue("input").jsonPrimitive.content)
        }
    }

    private fun metadata(prompt: String) = NovelAiStudioPngMetadata("/synthetic.png", prompt,
        settings = NovelAiImportedGenerationSettings(model = NovelAiImageModel.V5_FULL, transparentBackground = true),
        characters = listOf(NovelAiImportedCharacterPrompt("role, transparent background")), hasCharacterPrompts = true,
        width = 1024, height = 1024)

    private fun request(draft: NovelAiStudioDraft) = Json.parseToJsonElement(NovelAiImageService().buildRequestBody(
        draft.toPromptPlan(), draft.activeSettings.imageSize(), draft.activeSettings)).jsonObject
}
