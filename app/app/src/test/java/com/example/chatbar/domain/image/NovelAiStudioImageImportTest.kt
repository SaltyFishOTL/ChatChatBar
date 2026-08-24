package com.example.chatbar.domain.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovelAiStudioImageImportTest {
    private val metadata = NovelAiStudioPngMetadata(
        imagePath = "/tmp/import.png",
        positivePrompt = "imported positive",
        negativePrompt = "imported negative",
        characters = listOf(
            NovelAiImportedCharacterPrompt("alice", "bad alice"),
            NovelAiImportedCharacterPrompt("bob", "bad bob")
        ),
        hasCharacterPrompts = true,
        settings = NovelAiImportedGenerationSettings(
            model = NovelAiImageModel.V5_FULL,
            sizeTier = NovelAiSizeTier.LARGE,
            aspectRatio = NovelAiAspectRatio.LANDSCAPE,
            count = 2,
            steps = 35,
            guidance = 5.5f,
            sampler = NovelAiSampler.DPM_PLUS_PLUS_SDE
        ),
        seed = 987654321L,
        width = 1536,
        height = 1024
    )

    @Test
    fun `applies selected metadata sections and preserves studio-only fields`() {
        val draft = NovelAiStudioDraft(
            stylePrompt = "keep style",
            basePrompt = "old positive",
            characters = listOf(NovelAiCharacterPromptDraft(prompt = "old character", negativePrompt = "old negative")),
            negativePrompt = "keep negative",
            naturalLanguageMode = true,
            conversionSnapshot = NovelAiPositivePromptSnapshot("snapshot")
        )

        val result = draft.applyImportedMetadata(
            metadata,
            NovelAiStudioMetadataSelection(negativePrompt = false)
        )

        assertEquals("keep style", result.stylePrompt)
        assertEquals(true, result.naturalLanguageMode)
        assertEquals("imported positive", result.basePrompt)
        assertEquals("keep negative", result.negativePrompt)
        assertEquals(listOf("alice", "bob"), result.characters.map { it.prompt })
        assertEquals(listOf("bad alice", "bad bob"), result.characters.map { it.negativePrompt })
        assertEquals(NovelAiImageModel.V5_FULL, result.selectedModel)
        assertEquals(NovelAiSizeTier.LARGE, result.activeSettings.sizeTier)
        assertEquals(NovelAiAspectRatio.LANDSCAPE, result.activeSettings.aspectRatio)
        assertEquals(2, result.activeSettings.count)
        assertEquals(35, result.activeSettings.steps)
        assertEquals(5.5f, result.activeSettings.guidance)
        assertEquals(NovelAiSampler.DPM_PLUS_PLUS_SDE, result.activeSettings.sampler)
        assertEquals(NovelAiSeedMode.FIXED, result.activeSettings.seedMode)
        assertEquals(987654321L, result.activeSettings.seed)
        assertNull(result.conversionSnapshot)
    }

    @Test
    fun `unchecked sections do not change draft`() {
        val draft = NovelAiStudioDraft(
            stylePrompt = "style",
            basePrompt = "base",
            characters = listOf(NovelAiCharacterPromptDraft(prompt = "character", negativePrompt = "negative")),
            negativePrompt = "base negative"
        )

        val result = draft.applyImportedMetadata(
            metadata,
            NovelAiStudioMetadataSelection(
                positivePrompt = false,
                negativePrompt = false,
                characterPrompts = false,
                generationSettings = false,
                seed = false
            )
        )

        assertEquals(draft, result)
    }
}
