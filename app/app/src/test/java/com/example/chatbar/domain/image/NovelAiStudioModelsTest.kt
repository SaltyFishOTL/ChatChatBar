package com.example.chatbar.domain.image

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(0f, settings.cfgRescale)
        assertEquals(NovelAiSampler.EULER_ANCESTRAL, settings.sampler)
        assertEquals(123L, settings.seed)
    }

    @Test
    fun `character card prompt sources do not overwrite handwritten character prompts`() {
        val handwritten = NovelAiCharacterPromptDraft(prompt = "handwritten character")
        val sources = List(7) { index ->
            NovelAiCharacterPromptSource("Character $index", "reference prompt $index")
        }
        val draft = NovelAiStudioDraft(
            stylePrompt = "manual style",
            characters = listOf(handwritten)
        ).importCharacterCardPromptSources(
            cardId = "card-1",
            cardStylePrompt = "card style",
            sources = sources
        )

        assertEquals(listOf(handwritten), draft.characters)
        assertEquals("card style", draft.stylePrompt)
        assertEquals("card-1", draft.importedCharacterCardId)
        assertEquals(sources, draft.importedCharacterPromptSources)
        assertNull(draft.activeSettings.validationError(draft.characters.size))
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
        assertEquals(0f, draft.activeSettings.cfgRescale)
        assertEquals(NovelAiSeedMode.RANDOM, draft.activeSettings.seedMode)
        assertNull(draft.aiDesignModelId)
    }

    @Test
    fun `old persisted settings decode with CFG Rescale disabled`() {
        val draft = Json { ignoreUnknownKeys = true }.decodeFromString(
            NovelAiStudioDraft.serializer(),
            """{"selectedModel":"V4_5_FULL","v45Settings":{"guidance":7.0,"steps":32}}"""
        )

        assertEquals(7f, draft.activeSettings.guidance)
        assertEquals(32, draft.activeSettings.steps)
        assertEquals(0f, draft.activeSettings.cfgRescale)
    }

    @Test
    fun `CFG Rescale validates official range`() {
        assertNull(NovelAiGenerationSettings(cfgRescale = 1f).validationError(0))
        assertTrue(
            NovelAiGenerationSettings(cfgRescale = 1.05f)
                .validationError(0)
                .orEmpty()
                .contains("CFG Rescale")
        )
    }

    @Test
    fun `history apply modes restore exact scope`() {
        val current = NovelAiStudioDraft(
            stylePrompt = "current style",
            basePrompt = "current base",
            naturalLanguageMode = true,
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
        assertTrue(full.naturalLanguageMode)

        val newSeed = current.applyHistoryRecipe(recipe, 99L, NovelAiHistoryApplyMode.NEW_SEED)
        assertEquals(NovelAiSeedMode.RANDOM, newSeed.activeSettings.seedMode)
        assertTrue(newSeed.naturalLanguageMode)

        val seedOnly = current.applyHistoryRecipe(recipe, 99L, NovelAiHistoryApplyMode.SEED_ONLY)
        assertEquals("current base", seedOnly.basePrompt)
        assertEquals(NovelAiImageModel.V4_5_FULL, seedOnly.selectedModel)
        assertEquals(99L, seedOnly.activeSettings.seed)
    }

    @Test
    fun `guided history warns for settings and seed reuse`() {
        val recipe = NovelAiGenerationRecipe(
            imageGuidance = NovelAiImageGuidanceDraft(
                action = NovelAiGenerationAction.IMAGE_TO_IMAGE
            )
        )

        assertFalse(recipe.requiresImageGuidanceReuseWarning(NovelAiHistoryApplyMode.FULL))
        assertTrue(recipe.requiresImageGuidanceReuseWarning(NovelAiHistoryApplyMode.NEW_SEED))
        assertTrue(recipe.requiresImageGuidanceReuseWarning(NovelAiHistoryApplyMode.SEED_ONLY))
    }

    @Test
    fun `AI design language mode is not generation recipe state`() {
        val draft = NovelAiStudioDraft(naturalLanguageMode = true)

        assertFalse(draft.toRecipe().naturalLanguageMode)
    }

    @Test
    fun `AI design apply replaces positives and preserves unrelated studio state`() {
        val original = NovelAiStudioDraft(
            stylePrompt = "saved style",
            basePrompt = "old base",
            characters = listOf(
                NovelAiCharacterPromptDraft(prompt = "old character", negativePrompt = "old character negative")
            ),
            negativePrompt = "saved base negative",
            importedCharacterCardId = "card",
            importedCharacterPromptSources = listOf(NovelAiCharacterPromptSource("角色", "card prompt")),
            extraRequirement = "saved requirement",
            aiDesignModelId = "designer",
            selectedModel = NovelAiImageModel.V4_5_FULL,
            v45Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V4_5_FULL, steps = 24),
            v5Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, steps = 42),
            naturalLanguageMode = true,
            conversionSnapshot = NovelAiPositivePromptSnapshot("snapshot")
        )
        val plan = NovelAiPromptPlan(
            baseCaption = "new base",
            characterCaptions = listOf(
                NovelAiCharacterCaption("new character", DesignedCharacterCenter(0.5f, 0.5f), "ignored negative")
            )
        )

        val applied = original.applyDesignedPromptPlan(plan, NovelAiImageModel.V5_FULL)

        assertEquals("saved style", applied.stylePrompt)
        assertEquals("saved base negative", applied.negativePrompt)
        assertEquals("new base", applied.basePrompt)
        assertEquals(listOf("new character"), applied.characters.map { it.prompt })
        assertTrue(applied.characters.all { it.negativePrompt.isEmpty() })
        assertEquals(NovelAiImageModel.V5_FULL, applied.selectedModel)
        assertEquals(42, applied.activeSettings.steps)
        assertEquals(original.importedCharacterPromptSources, applied.importedCharacterPromptSources)
        assertEquals("saved requirement", applied.extraRequirement)
        assertEquals("designer", applied.aiDesignModelId)
        assertFalse(applied.naturalLanguageMode)
        assertNull(applied.conversionSnapshot)
    }

    @Test
    fun `batch history records consecutive seeds`() {
        assertEquals(
            listOf(10L, 11L, 12L, 13L),
            novelAiHistoryImages(listOf("a", "b", "c", "d"), 10L).map { it.seed }
        )
    }
}
