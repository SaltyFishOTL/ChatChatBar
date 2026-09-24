package com.example.chatbar.domain.image

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAiCharacterPositionTest {
    @Test
    fun `old drafts remain automatic and new positions survive history and reordering`() {
        val old = Json.decodeFromString<NovelAiStudioDraft>(
            """{"characters":[{"id":"old","prompt":"person"}],"v5Settings":{"model":"V5_FULL"}}"""
        )
        assertFalse(old.activeSettings.useCharacterPositions)
        assertFalse(old.v5Settings.useCharacterPositions)
        assertNull(old.characters.single().center)
        val draft = old.copy(updatedAt = 123L, characters = listOf(
            NovelAiCharacterPromptDraft(id = "left", prompt = "left person", center = DesignedCharacterCenter(0.1f, 0.3f)),
            NovelAiCharacterPromptDraft(id = "right", prompt = "right person", center = DesignedCharacterCenter(0.9f, 0.7f))
        )).withActiveSettings(NovelAiGenerationSettings(useCharacterPositions = true))
        val restored = Json.decodeFromString<NovelAiStudioDraft>(Json.encodeToString(draft))
        assertEquals(draft, restored)
        val recipe = Json.decodeFromString<NovelAiGenerationRecipe>(Json.encodeToString(draft.toRecipe()))
        for (mode in listOf(NovelAiHistoryApplyMode.FULL, NovelAiHistoryApplyMode.NEW_SEED)) {
            val applied = old.applyHistoryRecipe(recipe, 12L, mode)
            assertTrue(applied.activeSettings.useCharacterPositions)
            assertEquals(draft.characters, applied.characters)
        }
        val reordered = draft.copy(characters = draft.characters.reversed()).toPromptPlan()
        assertEquals(DesignedCharacterCenter(0.9f, 0.7f), reordered.characterCaptions.first().center)
        assertFalse(old.applyHistoryRecipe(recipe, 12L, NovelAiHistoryApplyMode.SEED_ONLY).activeSettings.useCharacterPositions)
    }

    @Test
    fun `manual requests enable every coordinate flag and share positive negative centers`() {
        for (model in NovelAiImageModel.entries) {
            val draft = NovelAiStudioDraft(characters = listOf(
                NovelAiCharacterPromptDraft(prompt = "person", negativePrompt = "bad", center = DesignedCharacterCenter(0.24f, 0.76f))
            )).withActiveSettings(NovelAiGenerationSettings(model = model, useCharacterPositions = true))
            val parameters = Json.parseToJsonElement(NovelAiImageService().buildRequestBody(
                prompt = draft.toPromptPlan(), imageSize = draft.activeSettings.imageSize(), settings = draft.activeSettings
            )).jsonObject.getValue("parameters").jsonObject
            assertEquals("true", parameters.getValue("use_coords").jsonPrimitive.content)
            val expected = if (model == NovelAiImageModel.V4_5_FULL) "0.3" to "0.7" else "0.24" to "0.76"
            for (key in listOf("v4_prompt", "v4_negative_prompt")) {
                val block = parameters.getValue(key).jsonObject
                assertEquals("true", block.getValue("use_coords").jsonPrimitive.content)
                val center = block.getValue("caption").jsonObject.getValue("char_captions").jsonArray
                    .single().jsonObject.getValue("centers").jsonArray.single().jsonObject
                assertEquals(expected.first, center.getValue("x").jsonPrimitive.content)
                assertEquals(expected.second, center.getValue("y").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `automatic and empty requests never enable positioning`() {
        val service = NovelAiImageService()
        val character = NovelAiCharacterCaption("person", DesignedCharacterCenter(0.3f, 0.7f))
        for ((captions, enabled) in listOf(listOf(character) to false, emptyList<NovelAiCharacterCaption>() to true)) {
            val parameters = Json.parseToJsonElement(service.buildRequestBody(
                NovelAiPromptPlan("scene", captions), NovelAiImageSize(1024, 1024, "square"),
                NovelAiGenerationSettings(useCharacterPositions = enabled)
            )).jsonObject.getValue("parameters").jsonObject
            assertEquals("false", parameters.getValue("use_coords").jsonPrimitive.content)
            assertEquals("false", parameters.getValue("v4_prompt").jsonObject.getValue("use_coords").jsonPrimitive.content)
            assertEquals("false", parameters.getValue("v4_negative_prompt").jsonObject.getValue("use_coords").jsonPrimitive.content)
        }
    }

    @Test
    fun `coordinates clamp and v45 uses grid centers while v5 retains free positions`() {
        assertEquals(DesignedCharacterCenter(0.1f, 0.9f), NovelAiCharacterPositionPolicy.normalize(
            DesignedCharacterCenter(-1f, 2f), NovelAiImageModel.V4_5_FULL))
        assertEquals(DesignedCharacterCenter(0f, 1f), NovelAiCharacterPositionPolicy.normalize(
            DesignedCharacterCenter(-1f, 2f), NovelAiImageModel.V5_FULL))
    }

    @Test
    fun `png import restores positions and respects unchecked selections`() {
        val metadata = requireNotNull(NovelAiPngMetadataReader.parseStudioComment("""
            {"width":1024,"height":1024,"v4_prompt":{"use_coords":true,"caption":{
              "base_caption":"scene","char_captions":[{"char_caption":"person","centers":[{"x":0.3,"y":0.7}]}]}}}
        """.trimIndent(), "image.png"))
        val original = NovelAiStudioDraft()
        val imported = original.applyImportedMetadata(metadata, NovelAiStudioMetadataSelection())
        assertTrue(imported.activeSettings.useCharacterPositions)
        assertEquals(DesignedCharacterCenter(0.3f, 0.7f), imported.characters.single().center)
        val skipped = original.applyImportedMetadata(metadata,
            NovelAiStudioMetadataSelection(characterPrompts = false, generationSettings = false))
        assertTrue(skipped.characters.isEmpty())
        assertFalse(skipped.activeSettings.useCharacterPositions)
    }
}
