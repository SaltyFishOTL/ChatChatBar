package com.example.chatbar.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleplaySpeakerPromptTest {
    @Test
    fun speakerSystemPrompt_preservesProtocolAndListsNormalizedDistinctNames() {
        val prompt = PromptTemplates.roleplaySpeakerFormatSystemPrompt(
            characterNames = listOf(" 爱音 ", "灯", "爱音", "  ")
        )

        assertTrue(prompt.contains("角色姓名：爱音、灯"))
        assertTrue(prompt.contains("<n=\"完整角色名\"/>"))
        assertTrue(prompt.contains("[对白内容]()"))
        assertTrue(prompt.contains("『**内心内容**』"))
        assertFalse(prompt.contains("【用户本轮输入】"))
        assertFalse(prompt.contains("继续剧情"))
        assertFalse(prompt.contains("角色姓名：爱音、灯、爱音"))
    }

    @Test
    fun speakerPrompt_usesNoneWhenCharacterListIsEmpty() {
        val prompt = PromptTemplates.roleplaySpeakerFormatSystemPrompt(emptyList())

        assertTrue(prompt.contains("角色姓名：无"))
    }

    @Test
    fun replyTailSystemPrompt_mergesSpeakerProtocolBeforeLengthWhenEnabled() {
        val prompt = PromptTemplates.replyTailSystemPrompt(
            replyLength = 500,
            roleplaySpeakerFormatEnabled = true,
            characterNames = listOf("爱音", "灯")
        )

        assertTrue(prompt.contains("角色姓名：爱音、灯"))
        assertTrue(prompt.endsWith(PromptTemplates.replyLengthTailSystemPrompt(500)))
        assertTrue(
            prompt.indexOf("角色姓名：爱音、灯") <
                prompt.indexOf(PromptTemplates.replyLengthTailSystemPrompt(500))
        )
    }

    @Test
    fun replyTailSystemPrompt_omitsSpeakerProtocolWhenDisabled() {
        val prompt = PromptTemplates.replyTailSystemPrompt(
            replyLength = 500,
            roleplaySpeakerFormatEnabled = false,
            characterNames = listOf("爱音", "灯")
        )

        assertEquals(PromptTemplates.replyLengthTailSystemPrompt(500), prompt)
        assertFalse(prompt.contains("角色姓名："))
        assertFalse(prompt.contains("<n=\"完整角色名\"/>"))
    }

    @Test
    fun avatarPositivePrompt_emptySourcesUseConfiguredTemplate() {
        assertEquals(
            PromptTemplates.CHARACTER_AVATAR_NAI_POSITIVE_PROMPT.trim().trim(',').trim(),
            PromptTemplates.novelAiCharacterAvatarPositivePrompt("", " , ")
        )
    }

    @Test
    fun avatarPositivePrompt_usesOnlyProvidedSourcesAndApprovedComposition() {
        assertEquals(
            listOf("style tags", "character tags", PromptTemplates.CHARACTER_AVATAR_NAI_POSITIVE_PROMPT.trim().trim(',').trim())
                .filter(String::isNotEmpty).joinToString(", "),
            PromptTemplates.novelAiCharacterAvatarPositivePrompt(" style tags ", "character tags")
        )
        assertEquals(
            listOf("manual full prompt", PromptTemplates.CHARACTER_AVATAR_NAI_POSITIVE_PROMPT.trim().trim(',').trim())
                .filter(String::isNotEmpty).joinToString(", "),
            PromptTemplates.novelAiCharacterAvatarPositivePrompt("manual full prompt,")
        )
    }
}
