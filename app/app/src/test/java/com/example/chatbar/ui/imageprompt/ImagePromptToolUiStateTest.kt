package com.example.chatbar.ui.imageprompt

import com.example.chatbar.domain.image.NovelAiCharacterPromptDraft
import com.example.chatbar.domain.image.NovelAiCharacterPromptSource
import com.example.chatbar.domain.image.NovelAiDesignConversation
import com.example.chatbar.domain.image.NovelAiDesignTurn
import com.example.chatbar.domain.image.NovelAiDesignTurnStatus
import com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry
import com.example.chatbar.domain.image.NovelAiGenerationHistoryImage
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiStudioDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePromptToolUiStateTest {
    @Test
    fun `fullscreen prompt session rejects an external editor revision`() {
        assertTrue(isStudioFullscreenPromptSessionCurrent(12, 12))
        assertFalse(isStudioFullscreenPromptSessionCurrent(12, 13))
    }

    @Test
    fun `natural language AI design always targets V5`() {
        assertEquals(
            NovelAiImageModel.V5_FULL,
            novelAiDesignTargetModel(
                NovelAiStudioDraft(
                    selectedModel = NovelAiImageModel.V4_5_FULL,
                    aiDesignNaturalLanguageMode = true
                )
            )
        )
        assertEquals(
            NovelAiImageModel.V4_5_FULL,
            novelAiDesignTargetModel(
                NovelAiStudioDraft(
                    selectedModel = NovelAiImageModel.V4_5_FULL,
                    aiDesignNaturalLanguageMode = false
                )
            )
        )
    }

    @Test
    fun `regeneration follows current natural language mode while preserving ordinary target`() {
        val originalTurn = NovelAiDesignTurn(
            targetImageModel = NovelAiImageModel.V4_5_FULL,
            naturalLanguageMode = false
        )

        val natural = novelAiDesignRegenerationMode(
            originalTurn,
            NovelAiStudioDraft(
                selectedModel = NovelAiImageModel.V4_5_FULL,
                aiDesignNaturalLanguageMode = true
            )
        )
        val ordinary = novelAiDesignRegenerationMode(
            originalTurn.copy(
                targetImageModel = NovelAiImageModel.V5_FULL,
                naturalLanguageMode = true
            ),
            NovelAiStudioDraft(
                selectedModel = NovelAiImageModel.V4_5_FULL,
                aiDesignNaturalLanguageMode = false
            )
        )

        assertTrue(natural.naturalLanguageMode)
        assertEquals(NovelAiImageModel.V5_FULL, natural.targetImageModel)
        assertFalse(ordinary.naturalLanguageMode)
        assertEquals(NovelAiImageModel.V5_FULL, ordinary.targetImageModel)
    }

    @Test
    fun `new AI design context excludes editable prompts from earlier conversations`() {
        val original = NovelAiStudioDraft(
            characters = listOf(NovelAiCharacterPromptDraft(prompt = "first character")),
            importedCharacterPromptSources = listOf(
                NovelAiCharacterPromptSource(name = "first card", prompt = "first reference")
            ),
            extraRequirement = "first requirement"
        )

        val snapshot = novelAiDesignContextSnapshot(original)
        val changed = original.copy(
            characters = listOf(NovelAiCharacterPromptDraft(prompt = "other conversation")),
            importedCharacterPromptSources = emptyList(),
            extraRequirement = "other requirement"
        )

        assertEquals("", snapshot.characterPrompt)
        assertEquals("first card", snapshot.characterImagePrompts.single().name)
        assertEquals("first reference", snapshot.characterImagePrompts.single().prompt)
        assertEquals("first requirement", snapshot.finalPromptRequirement)
        assertEquals("other conversation", changed.characters.single().prompt)
    }

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
