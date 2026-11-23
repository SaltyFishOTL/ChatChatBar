package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ChatNarrativeMode
import com.example.chatbar.domain.prompt.PromptTemplates

internal enum class ChatHistoryPromptZone {
    EARLIER_HISTORY,
    PREVIOUS_TURN
}

object ChatHistoryPromptPolicy {
    internal data class RenderedHistory(val text: String, val retainsLegacyFraming: Boolean = false)

    fun renderedHistoryText(
        message: ChatMessage,
        renderedBody: String,
        narrativeModesEnabled: Boolean = true,
        legacyHistoryWrapperDeclaredAsOutput: Boolean = false
    ): String = renderedHistory(message, renderedBody, narrativeModesEnabled, legacyHistoryWrapperDeclaredAsOutput).text

    internal fun renderedHistory(
        message: ChatMessage,
        renderedBody: String,
        narrativeModesEnabled: Boolean,
        legacyHistoryWrapperDeclaredAsOutput: Boolean,
    ): RenderedHistory {
        val mode = MessageAlternativeVersionPolicy.activeVersion(message).narrativeMode
        if (!narrativeModesEnabled || mode == null ||
            message.role != MessageRole.ASSISTANT || renderedBody.isBlank()
        ) return RenderedHistory(renderedBody)
        // Retain the exact legacy projection without inserting a second structural wrapper.
        if (mode == ChatNarrativeMode.FOCUS && !legacyHistoryWrapperDeclaredAsOutput &&
            NarrativeHistoryEcho.isStandaloneLegacyHistoryWrapper(renderedBody) &&
            (message.displayContent == renderedBody ||
                NarrativeHistoryEcho.isStandaloneLegacyHistoryWrapper(message.displayContent))
        ) return RenderedHistory(renderedBody, retainsLegacyFraming = true)
        return RenderedHistory(PromptTemplates.narrativeModeHistoryReply(renderedBody, mode))
    }

    fun previousNarrativeMode(
        history: List<ChatMessage>,
        regenerationTarget: ChatMessage? = null
    ): ChatNarrativeMode? {
        val previous = regenerationTarget ?: history.lastOrNull {
            it.role == MessageRole.ASSISTANT && it.displayContent.isNotBlank() &&
                it.generatedFromMessageId == null
        } ?: return ChatNarrativeMode.FOCUS
        return MessageAlternativeVersionPolicy.activeVersion(previous).narrativeMode
    }

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
