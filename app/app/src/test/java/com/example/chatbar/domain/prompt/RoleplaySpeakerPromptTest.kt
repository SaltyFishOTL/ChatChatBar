package com.example.chatbar.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleplaySpeakerPromptTest {
    @Test
    fun speakerPrompt_listsOnlyNormalizedDistinctNamesAboveUserContent() {
        val prompt = PromptTemplates.roleplaySpeakerFormatUserPrompt(
            characterNames = listOf(" 爱音 ", "灯", "爱音", "  "),
            userContent = "继续剧情"
        )

        assertTrue(prompt.contains("角色姓名：爱音、灯"))
        assertTrue(prompt.contains("对白格式：<n=\"完整角色名（不可见）\"/>[对白内容]()"))
        assertTrue(prompt.contains("内心格式：<n=\"完整角色名（不可见）\"/>『**内心内容**』"))
        assertTrue(prompt.endsWith("【用户本轮输入】\n继续剧情"))
        assertFalse(prompt.contains("角色姓名：爱音、灯、爱音"))
    }

    @Test
    fun speakerPrompt_usesNoneWhenCharacterListIsEmpty() {
        val prompt = PromptTemplates.roleplaySpeakerFormatUserPrompt(emptyList(), "test")

        assertTrue(prompt.contains("角色姓名：无"))
    }

    @Test
    fun avatarCompositionTags_remainApprovedValue() {
        assertEquals(
            "solo, portrait, upper body, looking at viewer, centered",
            PromptTemplates.CHARACTER_AVATAR_NAI_COMPOSITION_TAGS
        )
    }

    @Test
    fun avatarPositivePrompt_usesOnlyProvidedSourcesAndApprovedComposition() {
        assertEquals(
            "style tags, character tags, solo, portrait, upper body, looking at viewer, centered",
            PromptTemplates.novelAiCharacterAvatarPositivePrompt(" style tags ", "character tags")
        )
        assertEquals(
            "manual full prompt, solo, portrait, upper body, looking at viewer, centered",
            PromptTemplates.novelAiCharacterAvatarPositivePrompt("manual full prompt")
        )
    }
}
