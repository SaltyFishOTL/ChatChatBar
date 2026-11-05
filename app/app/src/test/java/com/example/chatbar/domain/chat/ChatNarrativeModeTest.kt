package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatNarrativeMode
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.SaveSlot
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNarrativeModeTest {
    private val json = Json { encodeDefaults = true }
    private val focus = ChatNarrativeMode.FOCUS
    private val advance = ChatNarrativeMode.ADVANCE

    private fun reply(mode: ChatNarrativeMode = focus) = ChatMessage(
        id = "reply", sessionId = "session", role = MessageRole.ASSISTANT,
        content = "fixture-original", narrativeMode = mode, createdAt = 1, updatedAt = 1
    )

    @Test
    fun oldSessionMessageAndSaveSlotDefaultToFocus() {
        val session = json.decodeFromString(ChatSession.serializer(),
            """{"id":"s","characterCardId":"c","title":"t","createdAt":1,"updatedAt":1}""")
        val message = json.decodeFromString(ChatMessage.serializer(),
            """{"id":"m","sessionId":"s","role":"ASSISTANT","content":"old","alternatives":["old","other"],"createdAt":1,"updatedAt":1}""")
        val slot = json.decodeFromString(SaveSlot.serializer(),
            """{"id":"slot","sessionId":"s","name":"old","createdAt":1}""")
        assertEquals(focus, session.narrativeMode)
        assertEquals(focus, slot.narrativeMode)
        assertTrue(MessageAlternativeVersionPolicy.versions(message).all { it.narrativeMode == focus })
    }

    @Test
    fun candidateModeSurvivesSelectionEditingTrimmingAndInterruptedDraft() {
        val second = MessageAlternativeVersionPolicy.append(reply(), "second", "v2", narrativeMode = advance)
        val first = MessageAlternativeVersionPolicy.select(second, 0)
        assertEquals(focus, first.narrativeMode)
        val edited = MessageAlternativeVersionPolicy.editCurrentContent(first, "edited")
        assertEquals(focus, edited.narrativeMode)
        assertEquals(advance, MessageAlternativeVersionPolicy.select(edited, 1).narrativeMode)
        val trimmed = MessageAlternativeVersionPolicy.append(edited, "third", "v3", maxVersions = 2, narrativeMode = focus)
        assertEquals(listOf("v2", "v3"), trimmed.alternativeVersionIds)
        assertEquals(listOf(advance, focus), trimmed.alternativeNarrativeModes)
        assertEquals(advance, InterruptedReplyPolicy.persistableDraft(reply(advance))?.narrativeMode)
        assertEquals(advance, MessageFormatRepairPolicy.replaceCurrentDisplayContent(second, "repaired").narrativeMode)
        assertEquals(trimmed, MessageAlternativeVersionPolicy.normalize(trimmed))
    }

    @Test
    fun saveSlotAndCopyRetainSessionAndPerVersionModes() {
        val message = MessageAlternativeVersionPolicy.append(reply(), "advance", "v2", narrativeMode = advance)
        val slot = SaveSlot.create("session", "fixture", messages = listOf(message)).copy(narrativeMode = advance)
        val decoded = json.decodeFromString(SaveSlot.serializer(), json.encodeToString(SaveSlot.serializer(), slot))
        assertEquals(slot, decoded)
        val copy = SessionCopyPolicy("copy", setOf(message.id))
        val copied = copy.message(decoded.messages.single(), "copy")
        assertEquals(MessageAlternativeVersionPolicy.versions(message), MessageAlternativeVersionPolicy.versions(copied))
        val session = ChatSession.create("card", "fixture").copy(narrativeMode = advance)
        val restoredSession = json.decodeFromString(ChatSession.serializer(), json.encodeToString(ChatSession.serializer(), session))
        assertEquals(advance, copy.session(restoredSession, "copy", "copy", 10).narrativeMode)
    }

    @Test
    fun historyWrappingUsesSelectedCandidateWithoutChangingStoredTextOrUserInput() {
        val message = MessageAlternativeVersionPolicy.append(reply(), "second", "v2", narrativeMode = advance)
        val focusMessage = MessageAlternativeVersionPolicy.select(message, 0)
        assertEquals("<历史回复 模式=\"推进\">\nrendered\n</历史回复>",
            ChatHistoryPromptPolicy.renderedHistoryText(message, "rendered"))
        assertEquals("<历史回复 模式=\"聚焦\">\noriginal\n</历史回复>",
            ChatHistoryPromptPolicy.renderedHistoryText(focusMessage, "original"))
        assertEquals("user", ChatHistoryPromptPolicy.renderedHistoryText(message.copy(role = MessageRole.USER), "user"))
        assertEquals("", ChatHistoryPromptPolicy.renderedHistoryText(message, ""))
        assertEquals("second", message.displayContent)
        assertEquals("fixture-original", focusMessage.displayContent)
        val statusContent = "body\n```status\nstate\n```"
        val withStatus = message.copy(alternatives = emptyList(), content = statusContent)
        val earlier = ChatHistoryPromptPolicy.sourceText(withStatus, true, ChatHistoryPromptZone.EARLIER_HISTORY)
        val previous = ChatHistoryPromptPolicy.sourceText(withStatus, true, ChatHistoryPromptZone.PREVIOUS_TURN)
        assertFalse(ChatHistoryPromptPolicy.renderedHistoryText(withStatus, earlier).contains("```status"))
        assertTrue(ChatHistoryPromptPolicy.renderedHistoryText(withStatus, previous).contains(statusContent))
    }

    @Test
    fun previousModeUsesNarrativeReplyOrRegenerationTargetAndIgnoresDerivedImages() {
        val advanced = reply(advance)
        val image = reply().copy(id = "image", content = "image", generatedFromMessageId = advanced.id)
        assertEquals(advance, ChatHistoryPromptPolicy.previousNarrativeMode(listOf(advanced, image)))
        assertEquals(focus, ChatHistoryPromptPolicy.previousNarrativeMode(listOf(advanced), reply()))
        assertEquals(focus, ChatHistoryPromptPolicy.previousNarrativeMode(emptyList()))
        assertNotEquals(PromptTemplates.narrativeModeSystemPrompt(focus), PromptTemplates.narrativeModeSystemPrompt(advance))
        val switched = PromptTemplates.narrativeModeTailSystemPrompt(advance, focus)
        assertNotEquals(switched, PromptTemplates.narrativeModeTailSystemPrompt(advance, advance))
        assertTrue(switched.contains(PromptTemplates.narrativeModeSystemPrompt(advance)))
        assertFalse(switched.contains(PromptTemplates.narrativeModeSystemPrompt(focus)))
    }
}
