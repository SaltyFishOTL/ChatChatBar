package com.example.chatbar.ui.imageprompt

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry
import com.example.chatbar.domain.image.NovelAiGenerationHistoryImage
import com.example.chatbar.domain.image.NovelAiStudioDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePromptToolUiStateTest {
    @Test
    fun `extra requirement alone cannot start AI design`() {
        val state = baseState().copy(draft = NovelAiStudioDraft(extraRequirement = "保持简洁"))
        assertFalse(state.canDesign)
    }

    @Test
    fun `image content enables AI design when helper model is usable`() {
        val state = baseState().copy(draft = NovelAiStudioDraft(imageDescription = "雨夜窗边"))
        assertTrue(state.canDesign)
    }

    @Test
    fun `style prompt alone cannot start AI design`() {
        val state = baseState().copy(draft = NovelAiStudioDraft(stylePrompt = "anime screencap"))
        assertFalse(state.canDesign)
    }

    @Test
    fun `base prompt enables direct generation without helper model`() {
        val state = ImagePromptToolUiState(
            draft = NovelAiStudioDraft(basePrompt = "1girl, rainy street"),
            modelUsable = false
        )
        assertTrue(state.canGenerate)
    }

    @Test
    fun `selected recent image retains owning recipe for apply actions`() {
        val image = NovelAiGenerationHistoryImage(path = "history/image.png", seed = 42L)
        val entry = NovelAiGenerationHistoryEntry(id = "batch", images = listOf(image))
        val state = ImagePromptToolUiState(
            recentHistoryItems = listOf(NovelAiRecentHistoryItem(entry, image)),
            selectedOutputPath = image.path
        )

        assertEquals(entry, state.selectedRecentHistoryItem?.entry)
        assertEquals(42L, state.selectedRecentHistoryItem?.image?.seed)
    }

    @Test
    fun `history apply blocks editing and generation actions`() {
        val state = baseState().copy(
            draft = NovelAiStudioDraft(imageDescription = "scene", basePrompt = "tags"),
            applyingHistory = true
        )

        assertFalse(state.canDesign)
        assertFalse(state.canGenerate)
    }

    @Test
    fun `character card import requires loaded idle draft`() {
        val ready = baseState().copy(draftLoaded = true)

        assertTrue(ready.canImportCharacterCard)
        assertFalse(ready.copy(draftLoaded = false).canImportCharacterCard)
        assertFalse(ready.copy(phase = ImagePromptToolPhase.DESIGNING).canImportCharacterCard)
        assertFalse(ready.copy(applyingHistory = true).canImportCharacterCard)
    }

    private fun baseState(): ImagePromptToolUiState = ImagePromptToolUiState(
        models = listOf(model()),
        selectedModelId = "model",
        modelUsable = true
    )

    private fun model() = ModelConfig(
        id = "model",
        displayName = "Model",
        baseUrl = "https://example.test",
        apiKey = "key",
        modelName = "model",
        createdAt = 1
    )
}
