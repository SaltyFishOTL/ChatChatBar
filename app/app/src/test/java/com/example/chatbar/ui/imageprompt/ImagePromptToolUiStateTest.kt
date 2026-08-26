package com.example.chatbar.ui.imageprompt

import com.example.chatbar.domain.image.NovelAiDesignConversation
import com.example.chatbar.domain.image.NovelAiDesignTurn
import com.example.chatbar.domain.image.NovelAiDesignTurnStatus
import com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry
import com.example.chatbar.domain.image.NovelAiGenerationHistoryImage
import com.example.chatbar.domain.image.NovelAiStudioDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePromptToolUiStateTest {
    @Test
    fun `AI design conversation requires initialized usable model and nonblank input`() {
        val ready = NovelAiDesignUiState(
            initialized = true,
            selectedDesignModelId = "model",
            input = "雨夜窗边"
        )

        assertTrue(ready.canSend)
        assertFalse(ready.copy(initialized = false).canSend)
        assertFalse(ready.copy(selectedDesignModelId = null).canSend)
        assertFalse(ready.copy(modelError = "模型不可用").canSend)
        assertFalse(ready.copy(input = " ").canSend)
    }

    @Test
    fun `failed AI design turn blocks sending until retry succeeds`() {
        val blocked = NovelAiDesignUiState(
            initialized = true,
            selectedDesignModelId = "model",
            input = "增加月光",
            conversation = NovelAiDesignConversation(
                turns = listOf(
                    NovelAiDesignTurn(
                        userText = "初始画面",
                        status = NovelAiDesignTurnStatus.FAILED,
                        error = "invalid JSON"
                    )
                )
            )
        )

        assertFalse(blocked.canSend)
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
        val state = ImagePromptToolUiState().copy(
            draft = NovelAiStudioDraft(imageDescription = "scene", basePrompt = "tags"),
            applyingHistory = true
        )

        assertFalse(state.canGenerate)
    }

    @Test
    fun `character card import requires loaded idle draft`() {
        val ready = ImagePromptToolUiState().copy(draftLoaded = true)

        assertTrue(ready.canImportCharacterCard)
        assertFalse(ready.copy(draftLoaded = false).canImportCharacterCard)
        assertFalse(ready.copy(phase = ImagePromptToolPhase.DESIGNING).canImportCharacterCard)
        assertFalse(ready.copy(applyingHistory = true).canImportCharacterCard)
    }

}
