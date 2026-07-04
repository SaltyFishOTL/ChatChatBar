package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage

object PlaceholderRenderer {
    fun normalize(text: String): String =
        text.replace("{{char}}", "\$botname")
            .replace("{{user}}", "\$username")
            .replace("<BOT>", "\$botname")
            .replace("<USER>", "\$username")

    fun render(
        text: String,
        playerName: String?,
        botName: String
    ): String {
        val normalized = normalize(text).replace("\$botname", botName)
        return if (!playerName.isNullOrBlank()) {
            normalized.replace("\$username", playerName)
        } else {
            normalized
        }
    }

    fun renderMessage(
        message: ChatMessage,
        playerName: String?,
        botName: String
    ): ChatMessage {
        val renderedContent = render(message.content, playerName, botName)
        val renderedAlternatives = message.alternatives.map { render(it, playerName, botName) }
        val renderedReasoning = message.reasoningContent?.let { render(it, playerName, botName) }
        return if (
            renderedContent == message.content &&
            renderedAlternatives == message.alternatives &&
            renderedReasoning == message.reasoningContent
        ) {
            message
        } else {
            message.copy(
                content = renderedContent,
                alternatives = renderedAlternatives,
                reasoningContent = renderedReasoning
            )
        }
    }
}
