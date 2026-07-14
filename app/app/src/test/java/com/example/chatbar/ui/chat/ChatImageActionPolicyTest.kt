package com.example.chatbar.ui.chat

import com.example.chatbar.data.local.entity.GeneratedImageCharacterPrompt
import com.example.chatbar.data.local.entity.GeneratedImageMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImageActionPolicyTest {
    @Test
    fun `text response does not disable image long press`() {
        assertTrue(
            canUseChatImageLongPress(
                isResponding = true,
                screenshotSelectionMode = false
            )
        )
    }

    @Test
    fun `screenshot selection keeps image long press disabled`() {
        assertFalse(
            canUseChatImageLongPress(
                isResponding = false,
                screenshotSelectionMode = true
            )
        )
    }

    @Test
    fun `regeneration draft exposes all saved prompts and applies edits`() {
        val draft = GeneratedImageMetadata(
            imagePath = "image.png",
            baseCaption = "night street",
            characterPrompts = listOf(
                GeneratedImageCharacterPrompt("1girl, black hair", 0.25f, 0.6f)
            ),
            negativePrompt = "watermark",
            sizePreset = "HORIZONTAL",
            width = 1216,
            height = 832
        ).toRegenerationDraft().copy(
            baseCaption = "rainy night street",
            characterPrompts = listOf(
                GeneratedImageCharacterPrompt("1girl, silver hair", 0.25f, 0.6f)
            ),
            negativePrompt = "watermark, text"
        )

        val prompt = draft.toPromptPlan()

        assertEquals("rainy night street", prompt.baseCaption)
        assertEquals("1girl, silver hair", prompt.characterCaptions.single().prompt)
        assertEquals(0.25f, prompt.characterCaptions.single().center.x, 0.001f)
        assertEquals("watermark, text", prompt.negativePrompt)
        assertEquals(1216, draft.width)
        assertEquals(832, draft.height)
    }
}
