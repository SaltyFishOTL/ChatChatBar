package com.example.chatbar.domain.image

import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.CleartextHttpChatTemplatePolicy
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.AiTaskMessageAssembler
import com.example.chatbar.domain.prompt.AiTaskStage
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NovelAiSceneHistoryTest {
    @Test
    fun plannedSceneRemainsAssistantAcrossEnvelopeAndHttpAdaptation() {
        val scene = "画面左侧是一把雨伞。\n右侧是一扇窗。"
        val actual = ChatApiMessage.withImage("user", "使用侧视构图", "aW1hZ2U=")
        val original = NovelAiPromptDesigner.withResearchEvidence(
            messages = listOf(ChatApiMessage.text("system", "fixture-rules"), actual),
            tagEvidence = emptyList(), codexEvidence = emptyList(),
            sceneDescription = scene, sceneFromPlanner = true
        )
        val context = AiTaskContext(AiTaskKind.IMAGE_DESIGN)
        val messages = AiTaskMessageAssembler.assemble(original, context)
        assertEquals(listOf("system", "assistant", "user", "assistant", "user", "assistant", "user", "assistant", "assistant"), messages.map { it.role })
        assertEquals(ChatApiMessage.text("assistant", scene), messages[5])
        assertEquals(actual, messages[6])
        assertFalse(messages.first().content.jsonPrimitive.content.contains(scene))
        assertEquals(messages, AiTaskMessageAssembler.assemble(messages, context))
        listOf("http://localhost/v1", "https://example.test/v1").forEach { url ->
            assertEquals(messages, CleartextHttpChatTemplatePolicy.adaptMessages(messages, true, url))
        }
    }

    @Test
    fun fallbackSourceIsNotPresentedAsAnAssistantScene() {
        val source = "用户原始需求"
        val original = NovelAiPromptDesigner.withResearchEvidence(
            messages = listOf(ChatApiMessage.text("system", "rules"), ChatApiMessage.text("user", "input")),
            tagEvidence = emptyList(), codexEvidence = emptyList(), sceneDescription = source
        )
        val messages = AiTaskMessageAssembler.assemble(original, AiTaskContext(AiTaskKind.IMAGE_DESIGN))
        assertFalse(messages.any { it.role == "assistant" && it.content.jsonPrimitive.content.contains(source) })
        assertEquals(7, messages.size)
    }

    @Test
    fun repairKeepsItsOrdinarySevenMessageStructure() {
        val messages = AiTaskMessageAssembler.assemble(
            listOf(ChatApiMessage.text("system", "repair"), ChatApiMessage.text("user", "raw")),
            AiTaskContext(AiTaskKind.IMAGE_DESIGN, AiTaskStage.REPAIR)
        )
        assertEquals(7, messages.size)
        assertEquals(ChatApiMessage.text("user", "raw"), messages[4])
    }
}
