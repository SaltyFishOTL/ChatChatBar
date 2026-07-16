package com.example.chatbar.domain.chat

object ChatHistoryPromptPolicy {
    fun payloadText(
        renderedBody: String,
        timelinePrefix: String,
        hasSupportedImage: Boolean
    ): String? {
        if (renderedBody.isBlank() && !hasSupportedImage) return null
        return timelinePrefix + renderedBody
    }

    fun requirePersistableAssistantBody(body: String): String {
        check(body.isNotBlank()) { "模型未返回可用正文" }
        return body
    }
}
