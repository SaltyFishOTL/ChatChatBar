package com.example.chatbar.domain.image

import com.example.chatbar.domain.prompt.PromptTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterAvatarImagePolicyTest {
    @Test
    fun `avatar plan directly combines style character and fixed composition`() {
        val plan = CharacterAvatarImagePolicy.promptPlan(
            stylePrompt = "very aesthetic, anime screencap,",
            characterPrompt = "1girl, silver hair, blue-gray eyes",
            negativePrompt = "lowres"
        )

        assertEquals(
            PromptTemplates.novelAiCharacterAvatarPositivePrompt(
                "very aesthetic, anime screencap,", "1girl, silver hair, blue-gray eyes"
            ),
            plan.baseCaption
        )
        assertTrue(plan.characterCaptions.isEmpty())
        assertEquals(NovelAiImageSizePreset.SQUARE, plan.sizePreset)
        assertEquals(PromptTemplates.novelAiCharacterAvatarNegativePrompt("lowres"), plan.negativePrompt)
    }

    @Test
    fun `avatar negative appends dedicated terms without replacing card negative`() {
        assertEquals("card negative, avatar negative", PromptTemplates.novelAiCharacterAvatarNegativePrompt(
            "card negative", " , avatar negative, "
        ))
        assertEquals("card negative", PromptTemplates.novelAiCharacterAvatarNegativePrompt("card negative", ""))
        assertEquals(
            PromptTemplates.effectiveCharacterNaiNegativePrompt("") + ", avatar negative",
            PromptTemplates.novelAiCharacterAvatarNegativePrompt("", "avatar negative")
        )
    }

    @Test
    fun `avatar size is fixed small square`() {
        assertEquals(512, CharacterAvatarImagePolicy.imageSize.width)
        assertEquals(512, CharacterAvatarImagePolicy.imageSize.height)
        assertEquals("Small Square", CharacterAvatarImagePolicy.imageSize.label)
    }
}
