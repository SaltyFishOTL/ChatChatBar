package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaceholderRendererTest {
    @Test
    fun renderReplacesSystemStylePlaceholders() {
        val rendered = PlaceholderRenderer.render(
            text = "{{user}} meets {{char}}. {user} follows {char}. <USER> trusts <BOT>. \$username calls \$botname.",
            playerName = "Alice",
            botName = "Bot"
        )

        assertEquals("Alice meets Bot. Alice follows Bot. Alice trusts Bot. Alice calls Bot.", rendered)
    }

    @Test
    fun renderLeavesUsernameWhenPlayerNameMissing() {
        val rendered = PlaceholderRenderer.render(
            text = "\$username calls \$botname.",
            playerName = null,
            botName = "Bot"
        )

        assertEquals("\$username calls Bot.", rendered)
    }

    @Test
    fun renderMessageReplacesContentAlternativesAndReasoning() {
        val message = ChatMessage(
            id = "m1",
            sessionId = "s1",
            role = MessageRole.ASSISTANT,
            content = "\$botname waits.",
            alternatives = listOf("\$username waves.", "{{char}} answers."),
            currentAlternativeIndex = 1,
            reasoningContent = "<BOT> sees <USER>.",
            createdAt = 1,
            updatedAt = 1
        )

        val rendered = PlaceholderRenderer.renderMessage(message, "Alice", "Bot")

        assertEquals("Bot waits.", rendered.content)
        assertEquals(listOf("Alice waves.", "Bot answers."), rendered.alternatives)
        assertEquals("Bot sees Alice.", rendered.reasoningContent)
        assertFalse(rendered.displayContent.contains("\$botname"))
    }
}
