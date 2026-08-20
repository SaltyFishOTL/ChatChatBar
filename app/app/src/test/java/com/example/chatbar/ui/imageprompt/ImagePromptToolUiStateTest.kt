package com.example.chatbar.ui.imageprompt

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.image.NovelAiImageRegenerationDraft
import com.example.chatbar.domain.image.NovelAiImageSizePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePromptToolUiStateTest {
    @Test
    fun `image prompt preference alone cannot design`() {
        val state = baseState().copy(imagePromptPreference = "保持最终 tags 简洁")

        assertFalse(state.canDesign)
    }

    @Test
    fun `image prompt source fields can design with preference`() {
        assertTrue(baseState().copy(imageDescription = "雨夜窗边", imagePromptPreference = "保持简洁").canDesign)
        assertTrue(baseState().copy(characterPrompt = "1girl, silver hair", imagePromptPreference = "保持简洁").canDesign)
    }

    @Test
    fun `style prompt alone cannot design because it is appended only when generating image`() {
        assertFalse(baseState().copy(stylePrompt = "anime screencap").canDesign)
    }

    @Test
    fun `selected card only qualifies after prompts are imported`() {
        val card = CharacterCard(
            id = "card",
            name = "夜雨诊所",
            defaultImagePrompt = "",
            createdAt = 1,
            updatedAt = 1
        )
        val selectedOnly = baseState().copy(
            characterCards = listOf(card),
            selectedCharacterCardId = card.id,
            imagePromptPreference = "保持简洁"
        )
        val importedPrompt = selectedOnly.copy(characterPrompt = "1girl, silver hair")

        assertFalse(selectedOnly.canDesign)
        assertTrue(importedPrompt.canDesign)
    }

    @Test
    fun `switching character card preserves every editable NovelAI prompt`() {
        val draft = NovelAiImageRegenerationDraft(
            baseCaption = "1girl, rainy street",
            characterPrompts = emptyList(),
            negativePrompt = "lowres",
            sizePreset = NovelAiImageSizePreset.PORTRAIT.name,
            width = NovelAiImageSizePreset.PORTRAIT.width,
            height = NovelAiImageSizePreset.PORTRAIT.height
        )
        val card = CharacterCard(
            id = "new-card",
            name = "新角色卡",
            defaultImagePrompt = "anime screencap",
            characters = listOf(
                CharacterInfo(id = "character", name = "角色", imagePrompt = "1girl, silver hair")
            ),
            createdAt = 1,
            updatedAt = 1
        )
        val switched = baseState().copy(
            imageDescription = "保留图片描述",
            imagePromptPreference = "保留生图偏好",
            promptDraft = draft
        ).importCharacterCardPrompts(card)

        assertEquals("new-card", switched.selectedCharacterCardId)
        assertEquals("anime screencap", switched.stylePrompt)
        assertEquals("角色:\n1girl, silver hair", switched.characterPrompt)
        assertEquals("保留图片描述", switched.imageDescription)
        assertEquals("保留生图偏好", switched.imagePromptPreference)
        assertEquals(draft, switched.promptDraft)
        assertEquals(ImagePromptToolPhase.READY, switched.phase)
    }

    @Test
    fun `blank character card prompts do not erase manual prompts`() {
        val switched = baseState().copy(
            stylePrompt = "manual style",
            characterPrompt = "manual character"
        ).importCharacterCardPrompts(
            CharacterCard(
                id = "blank-card",
                name = "空提示词角色卡",
                createdAt = 1,
                updatedAt = 1
            )
        )

        assertEquals("manual style", switched.stylePrompt)
        assertEquals("manual character", switched.characterPrompt)
    }

    private fun baseState(): ImagePromptToolUiState =
        ImagePromptToolUiState(
            models = listOf(model()),
            selectedModelId = "model",
            modelUsable = true
        )

    private fun model(): ModelConfig =
        ModelConfig(
            id = "model",
            displayName = "Model",
            baseUrl = "https://example.test",
            apiKey = "key",
            modelName = "model",
            createdAt = 1
        )
}
