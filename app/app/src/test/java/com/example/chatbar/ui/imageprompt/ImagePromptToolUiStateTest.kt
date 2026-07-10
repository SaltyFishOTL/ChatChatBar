package com.example.chatbar.ui.imageprompt

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ModelConfig
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
        assertTrue(baseState().copy(stylePrompt = "anime screencap", imagePromptPreference = "保持简洁").canDesign)
        assertTrue(baseState().copy(characterPrompt = "1girl, silver hair", imagePromptPreference = "保持简洁").canDesign)
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
