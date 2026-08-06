package com.example.chatbar.ui.chat

import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.PromptCacheKeyFactory
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentTurnMessageOrderTest {
    @Test
    fun startPositionAddsRequirementsOnlyToOpeningSystemMessage() {
        val messages = mutableListOf(
            ChatApiMessage.text(
                role = "system",
                content = buildOpeningSystemPrompt(
                    requirementsSystemPrompt = "格式要求",
                    stableSystemPrompt = "主系统提示",
                    formatPromptPosition = FormatPromptPosition.START
                )
            )
        )

        appendCurrentUserAndRequirementsSystemMessages(
            messages = messages,
            userMessage = ChatApiMessage.text(role = "user", content = "用户输入"),
            requirementsSystemPrompt = "格式要求",
            formatPromptPosition = FormatPromptPosition.START
        )

        assertEquals(listOf("system", "user"), messages.map { it.role })
        assertEquals(JsonPrimitive("格式要求\n\n主系统提示"), messages[0].content)
    }

    @Test
    fun endPositionAddsRequirementsOnlyAfterCurrentUserMessage() {
        val messages = mutableListOf(
            ChatApiMessage.text(
                role = "system",
                content = buildOpeningSystemPrompt(
                    requirementsSystemPrompt = "格式要求",
                    stableSystemPrompt = "主系统提示",
                    formatPromptPosition = FormatPromptPosition.END
                )
            )
        )

        appendCurrentUserAndRequirementsSystemMessages(
            messages = messages,
            userMessage = ChatApiMessage.text(role = "user", content = "用户输入"),
            requirementsSystemPrompt = "格式要求",
            formatPromptPosition = FormatPromptPosition.END
        )

        assertEquals(listOf("system", "user", "system"), messages.map { it.role })
        assertEquals(JsonPrimitive("主系统提示"), messages[0].content)
        assertEquals(JsonPrimitive("格式要求"), messages[2].content)
    }

    @Test
    fun requirementsSystemPromptIsPlacedAtStartOfOpeningSystemMessage() {
        val openingSystemPrompt = buildOpeningSystemPrompt(
            requirementsSystemPrompt = "格式要求",
            stableSystemPrompt = "主系统提示"
        )

        assertEquals("格式要求\n\n主系统提示", openingSystemPrompt)
    }

    @Test
    fun openingRequirementsParticipateInPromptCacheKey() {
        val firstKey = PromptCacheKeyFactory.cacheKey(
            buildOpeningSystemPrompt(
                requirementsSystemPrompt = "格式要求 A",
                stableSystemPrompt = "主系统提示"
            )
        )
        val secondKey = PromptCacheKeyFactory.cacheKey(
            buildOpeningSystemPrompt(
                requirementsSystemPrompt = "格式要求 B",
                stableSystemPrompt = "主系统提示"
            )
        )

        assertNotEquals(firstKey, secondKey)
    }

    @Test
    fun openingSystemPromptKeepsRequirementsWhenStablePromptIsEmpty() {
        assertEquals(
            "格式要求",
            buildOpeningSystemPrompt(
                requirementsSystemPrompt = "格式要求",
                stableSystemPrompt = ""
            )
        )
    }

    @Test
    fun requirementsSystemMessageIsPlacedImmediatelyAfterCurrentUserMessage() {
        val messages = mutableListOf(
            ChatApiMessage.text(
                role = "system",
                content = buildOpeningSystemPrompt(
                    requirementsSystemPrompt = "格式要求",
                    stableSystemPrompt = "主系统提示"
                )
            )
        )

        appendCurrentUserAndRequirementsSystemMessages(
            messages = messages,
            userMessage = ChatApiMessage.text(role = "user", content = "用户输入"),
            requirementsSystemPrompt = "格式要求"
        )

        assertEquals(listOf("system", "user", "system"), messages.map { it.role })
        assertEquals(JsonPrimitive("格式要求\n\n主系统提示"), messages[0].content)
        assertEquals(JsonPrimitive("用户输入"), messages[1].content)
        assertEquals(JsonPrimitive("格式要求"), messages[2].content)
    }

    @Test
    fun formatContinuityNoticeUsesSameRequirementsAtOpeningAndAfterCurrentUser() {
        val requirements = PromptTemplates.currentTurnOutputRequirementsSystemPrompt(
            formatCardContent = "格式正文",
            replyLength = 300,
            includeFormatHistoryContinuityNotice = true
        )
        val messages = mutableListOf(
            ChatApiMessage.text(
                role = "system",
                content = buildOpeningSystemPrompt(
                    requirementsSystemPrompt = requirements,
                    stableSystemPrompt = "主系统提示"
                )
            )
        )

        appendCurrentUserAndRequirementsSystemMessages(
            messages = messages,
            userMessage = ChatApiMessage.text(role = "user", content = "用户输入"),
            requirementsSystemPrompt = requirements
        )

        assertTrue(messages.first().content.toString().contains(PromptTemplates.FORMAT_HISTORY_CONTINUITY_NOTICE))
        assertEquals(JsonPrimitive(requirements), messages.last().content)
    }
}
