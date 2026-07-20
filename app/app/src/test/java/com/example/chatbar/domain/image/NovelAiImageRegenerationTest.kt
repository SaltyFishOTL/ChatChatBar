package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.GeneratedImageCharacterPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAiImageRegenerationTest {
    @Test
    fun emptyDraft_startsWithBlankMainPromptAndDefaultNegativePrompt() {
        val draft = emptyNovelAiImageRegenerationDraft()

        assertEquals("", draft.baseCaption)
        assertTrue(draft.characterPrompts.isEmpty())
        assertTrue(draft.negativePrompt.isNotBlank())
        assertEquals(NovelAiImageSizePreset.PORTRAIT.name, draft.sizePreset)
        assertEquals(NovelAiImageSizePreset.PORTRAIT.width, draft.width)
        assertEquals(NovelAiImageSizePreset.PORTRAIT.height, draft.height)
        assertFalse(draft.canRegenerate)
    }

    @Test
    fun promptPlan_roundTripsIntoEditableDraft() {
        val plan = NovelAiPromptPlan(
            baseCaption = "1girl, rainy street",
            characterCaptions = listOf(
                NovelAiCharacterCaption(
                    prompt = "girl, black hair",
                    center = DesignedCharacterCenter(0.4f, 0.6f)
                )
            ),
            sizePreset = NovelAiImageSizePreset.HORIZONTAL,
            negativePrompt = "lowres"
        )

        val draft = plan.toRegenerationDraft()

        assertEquals(plan.baseCaption, draft.baseCaption)
        assertEquals("girl, black hair", draft.characterPrompts.single().prompt)
        assertEquals(0.4f, draft.characterPrompts.single().centerX)
        assertEquals(0.6f, draft.characterPrompts.single().centerY)
        assertEquals("lowres", draft.negativePrompt)
        assertEquals(NovelAiImageSizePreset.HORIZONTAL.name, draft.sizePreset)
        assertTrue(draft.canRegenerate)
    }

    @Test
    fun characterPrompts_canBeAddedAndRemovedWithinNovelAiLimit() {
        val initial = draft(characterPrompts = emptyList())

        val withMaximum = (1..NOVEL_AI_MAX_CHARACTER_PROMPTS).fold(initial) { draft, _ ->
            draft.addCharacterPrompt()
        }

        assertEquals(NOVEL_AI_MAX_CHARACTER_PROMPTS, withMaximum.characterPrompts.size)
        assertSame(withMaximum, withMaximum.addCharacterPrompt())

        val withoutThird = withMaximum.removeCharacterPrompt(2)
        assertEquals(NOVEL_AI_MAX_CHARACTER_PROMPTS - 1, withoutThird.characterPrompts.size)
        assertSame(withoutThird, withoutThird.removeCharacterPrompt(-1))
    }

    @Test
    fun canRegenerate_requiresMainAndEveryAddedCharacterPrompt() {
        val initial = draft(
            characterPrompts = listOf(characterPrompt("black hair"))
        )

        assertTrue(initial.canRegenerate)
        assertFalse(initial.addCharacterPrompt().canRegenerate)
        assertFalse(initial.copy(baseCaption = " ").canRegenerate)
        assertTrue(initial.copy(negativePrompt = "").canRegenerate)
    }

    private fun draft(
        characterPrompts: List<GeneratedImageCharacterPrompt>
    ) = NovelAiImageRegenerationDraft(
        baseCaption = "masterpiece, 1girl",
        characterPrompts = characterPrompts,
        negativePrompt = "lowres",
        sizePreset = "PORTRAIT",
        width = 832,
        height = 1216
    )

    private fun characterPrompt(prompt: String) = GeneratedImageCharacterPrompt(
        prompt = prompt,
        centerX = 0.5f,
        centerY = 0.5f
    )
}
