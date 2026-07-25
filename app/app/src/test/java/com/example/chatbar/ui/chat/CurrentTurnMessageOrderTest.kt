package com.example.chatbar.ui.chat

import com.example.chatbar.domain.chat.ChatApiMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentTurnMessageOrderTest {
    @Test
    fun requirementsSystemMessageIsPlacedImmediatelyAfterCurrentUserMessage() {
        val messages = mutableListOf(
            ChatApiMessage.text(role = "system", content = "已有提示")
        )

        appendCurrentUserAndRequirementsSystemMessages(
            messages = messages,
            userMessage = ChatApiMessage.text(role = "user", content = "用户输入"),
            requirementsSystemPrompt = "格式要求"
        )

        assertEquals(listOf("system", "user", "system"), messages.map { it.role })
        assertEquals(JsonPrimitive("用户输入"), messages[1].content)
        assertEquals(JsonPrimitive("格式要求"), messages[2].content)
    }
}
