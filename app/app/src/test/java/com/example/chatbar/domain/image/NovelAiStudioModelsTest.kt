package com.example.chatbar.domain.image

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAiStudioModelsTest {
    @Test
    fun `size matrix and wallpaper square normalization match studio contract`() {
        assertEquals(512 to 768, NovelAiGenerationSettings(sizeTier = NovelAiSizeTier.SMALL).imageSize().let { it.width to it.height })
        assertEquals(
            1472 to 1472,
            NovelAiGenerationSettings(sizeTier = NovelAiSizeTier.LARGE, aspectRatio = NovelAiAspectRatio.SQUARE)
                .imageSize().let { it.width to it.height }
        )
        val wallpaper = NovelAiGenerationSettings(
            sizeTier = NovelAiSizeTier.WALLPAPER,
            aspectRatio = NovelAiAspectRatio.SQUARE
        ).normalized()
        assertEquals(NovelAiAspectRatio.PORTRAIT, wallpaper.aspectRatio)
        assertEquals(1088 to 1920, wallpaper.imageSize().let { it.width to it.height })
    }

    @Test
    fun `model settings remain independent while switching`() {
        val draft = NovelAiStudioDraft(
            v45Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V4_5_FULL, steps = 28),
            v5Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, steps = 40)
        )
        assertEquals(28, draft.activeSettings.steps)
        assertEquals(40, draft.copy(selectedModel = NovelAiImageModel.V5_FULL).activeSettings.steps)
    }

    @Test
    fun `legacy runtime settings keep original parameters with selected model`() {
        val settings = NovelAiGenerationSettings.legacy(
            seed = 123,
            count = 2,
            model = NovelAiImageModel.V5_FULL
        )

        assertEquals(NovelAiImageModel.V5_FULL, settings.model)
        assertEquals(2, settings.count)
        assertEquals(28, settings.steps)
        assertEquals(8f, settings.guidance)
        assertEquals(NovelAiSampler.EULER_ANCESTRAL, settings.sampler)
        assertEquals(123L, settings.seed)
    }

    @Test
    fun `character card prompt sources do not overwrite handwritten character prompts`() {
        val handwritten = NovelAiCharacterPromptDraft(prompt = "handwritten character")
        val draft = NovelAiStudioDraft(
            stylePrompt = "manual style",
            characters = listOf(handwritten)
        ).importCharacterCardPromptSources(
            cardId = "card-1",
            cardStylePrompt = "card style",
            sources = listOf(NovelAiCharacterPromptSource("Alice", "alice prompt"))
        )

        assertEquals(listOf(handwritten), draft.characters)
        assertEquals("card style", draft.stylePrompt)
        assertEquals("card-1", draft.importedCharacterCardId)
        assertEquals(
            listOf("Alice" to "alice prompt"),
            draft.importedCharacterPromptSources.map { it.name to it.prompt }
        )
    }

    @Test
    fun `validation blocks overflow characters without truncating draft`() {
        val characters = List(7) { NovelAiCharacterPromptDraft(prompt = "character-$it") }
        val draft = NovelAiStudioDraft(characters = characters)
        assertTrue(draft.activeSettings.validationError(draft.characters.size).orEmpty().contains("最多支持 6"))
        assertEquals(7, draft.characters.size)
        assertNull(
            draft.copy(selectedModel = NovelAiImageModel.V5_FULL)
                .activeSettings.validationError(draft.characters.size)
        )
    }

    @Test
    fun `positive copy keeps empty groups and indents multiline characters`() {
        val text = NovelAiStudioDraft(
            stylePrompt = "",
            basePrompt = "scene",
            characters = listOf(NovelAiCharacterPromptDraft(prompt = "girl\nblack hair"))
        ).copyPositivePrompt()
        assertEquals("\n\nscene\n\n- girl\n  black hair", text)
    }

    @Test
    fun `old empty payload decodes with first run defaults`() {
        val draft = Json { ignoreUnknownKeys = true }.decodeFromString(NovelAiStudioDraft.serializer(), "{}")
        assertEquals(NovelAiImageModel.V4_5_FULL, draft.selectedModel)
        assertEquals(28, draft.activeSettings.steps)
        assertEquals(6f, draft.activeSettings.guidance)
        assertEquals(NovelAiSeedMode.RANDOM, draft.activeSettings.seedMode)
    }

    @Test
    fun `history apply modes restore exact scope`() {
        val current = NovelAiStudioDraft(
            stylePrompt = "current style",
            basePrompt = "current base",
            selectedModel = NovelAiImageModel.V4_5_FULL
        )
        val recipe = NovelAiGenerationRecipe(
            stylePrompt = "history style",
            basePrompt = "history base",
            settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, steps = 40, count = 3)
        )
        val full = current.applyHistoryRecipe(recipe, 99L, NovelAiHistoryApplyMode.FULL)
        assertEquals("history base", full.basePrompt)
        assertEquals(NovelAiImageModel.V5_FULL, full.selectedModel)
        assertEquals(NovelAiSeedMode.FIXED, full.activeSettings.seedMode)
        assertEquals(99L, full.activeSettings.seed)

        val newSeed = current.applyHistoryRecipe(recipe, 99L, NovelAiHistoryApplyMode.NEW_SEED)
        assertEquals(NovelAiSeedMode.RANDOM, newSeed.activeSettings.seedMode)

        val seedOnly = current.applyHistoryRecipe(recipe, 99L, NovelAiHistoryApplyMode.SEED_ONLY)
        assertEquals("current base", seedOnly.basePrompt)
        assertEquals(NovelAiImageModel.V4_5_FULL, seedOnly.selectedModel)
        assertEquals(99L, seedOnly.activeSettings.seed)
    }

    @Test
    fun `batch history records consecutive seeds`() {
        assertEquals(
            listOf(10L, 11L, 12L, 13L),
            novelAiHistoryImages(listOf("a", "b", "c", "d"), 10L).map { it.seed }
        )
    }
}
