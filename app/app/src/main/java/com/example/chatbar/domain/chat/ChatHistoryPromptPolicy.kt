package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole

internal enum class ChatHistoryPromptZone {
    EARLIER_HISTORY,
    PREVIOUS_TURN
}

object ChatHistoryPromptPolicy {
    internal fun sourceText(
        message: ChatMessage,
        excludeAssistantStatusFromHistory: Boolean,
        zone: ChatHistoryPromptZone
    ): String {
        val shouldStripStatus =
            zone == ChatHistoryPromptZone.EARLIER_HISTORY &&
                excludeAssistantStatusFromHistory &&
                message.role == MessageRole.ASSISTANT
        return if (shouldStripStatus) {
            stripRoleplayStatusSegments(message.displayContent)
        } else {
            message.displayContent
        }
    }

    internal fun shouldIncludeFormatContinuityNotice(
        excludeAssistantStatusFromHistory: Boolean,
        formatCardContent: String?,
        earlierHistoryMessages: List<ChatMessage>
    ): Boolean =
        excludeAssistantStatusFromHistory &&
            !formatCardContent.isNullOrBlank() &&
            earlierHistoryMessages.any { it.role == MessageRole.ASSISTANT }

    fun payloadText(
        renderedBody: String,
        hasSupportedImage: Boolean
    ): String? {
        if (renderedBody.isBlank() && !hasSupportedImage) return null
        return renderedBody
    }

    fun requirePersistableAssistantBody(body: String): String {
        check(body.isNotBlank()) { "模型未返回可用正文" }
        return body
    }
}
