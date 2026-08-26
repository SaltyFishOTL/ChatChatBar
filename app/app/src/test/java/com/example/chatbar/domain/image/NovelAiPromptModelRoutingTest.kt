package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAiPromptModelRoutingTest {
    @Test
    fun `chat moment and studio prompt entries select V5 system from target image model`() {
        val chatMessages = NovelAiPromptDesigner.conversationDesignMessages(
            messages = listOf(ChatMessage.create("session", MessageRole.ASSISTANT, "画面锚点")),
            playerName = null,
            imageContentHint = "生成图片",
            finalPromptRequirement = "",
            characterImagePrompts = emptyList(),
            structured = true,
            targetImageModel = NovelAiImageModel.V5_FULL
        )
        val chatSystems = chatMessages
            .filter { it.role == "system" }
            .joinToString("\n") { it.content.jsonPrimitive.content }
        val momentSystem = PromptTemplates.novelAiImagePromptSystem(
            characterImagePrompts = emptyList(),
            structured = true,
            targetImageModel = NovelAiImageModel.V5_FULL
        )
        val studioSystem = PromptTemplates.novelAiImagePromptCoreSystem(
            targetImageModel = NovelAiImageModel.V5_FULL
        )

        listOf(chatSystems, momentSystem, studioSystem).forEach { prompt ->
            assertTrue(prompt.contains("NovelAI Diffusion V5 Full"))
            assertTrue(prompt.contains("总token<=1000"))
            assertFalse(prompt.contains("角色部分尽量简洁"))
        }
        assertEqualsFinalUser(chatMessages)
    }

    private fun assertEqualsFinalUser(messages: List<com.example.chatbar.domain.chat.ChatApiMessage>) {
        assertTrue(messages.last().role == "user")
    }
}
