package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NovelAiActionOptionsProjectionTest {
    private val options = "————————\n【行动选项】\n1. 去图书馆\n2. 回旅店\n————————"
    private fun reply() = ChatMessage.create("synthetic", MessageRole.ASSISTANT,
        "站在广场。\n$options\n```text\n衣着：蓝外套\n```")
    @Test fun finalDesignMessagesKeepNarrationClothesAndManualHintButOmitActions() {
        val reply = reply()
        for (target in listOf(NovelAiImageModel.V4_5_FULL, NovelAiImageModel.V5_FULL)) {
            val messages = NovelAiPromptDesigner.conversationDesignMessages(listOf(reply), null,
                imageContentHint = "夕阳", finalPromptRequirement = "", characterImagePrompts = emptyList(),
                structured = false, targetImageModel = target, naturalLanguageMode = target == NovelAiImageModel.V5_FULL)
            val assistant = messages.first { it.role == "assistant" }.content.jsonPrimitive.content
            assertTrue(assistant.contains("站在广场")); assertTrue(assistant.contains("蓝外套"))
            assertFalse(assistant.contains("去图书馆")); assertFalse(assistant.contains("回旅店"))
            assertTrue(messages.any { it.content.toString().contains("夕阳") })
        }
        assertTrue(reply.content.contains("回旅店"))
    }
    @Test fun researchInputUsesSameReadOnlyProjection() {
        val prompt = PromptTemplates.novelAiImagePromptConversation(listOf(reply()))
        assertTrue(prompt.contains("站在广场")); assertTrue(prompt.contains("蓝外套"))
        assertFalse(prompt.contains("去图书馆")); assertFalse(prompt.contains("回旅店"))
    }
    @Test fun actualUserTextIsNeverFiltered() {
        val user = ChatMessage.create("synthetic", MessageRole.USER, options)
        assertTrue(PromptTemplates.novelAiImagePromptAssistantScene(user).contains("回旅店"))
        assertTrue(PromptTemplates.novelAiImagePromptConversation(listOf(user)).contains("回旅店"))
    }
    @Test fun actionOnlyReplyProjectsToEmptySceneWithoutMutatingSavedContent() {
        val message = ChatMessage.create("synthetic", MessageRole.ASSISTANT, options)
        assertTrue(PromptTemplates.novelAiImagePromptAssistantScene(message).isBlank())
        assertEquals(options, message.content)
    }
}
