package com.example.chatbar.domain.chat

import java.net.URI

internal object CleartextHttpChatTemplatePolicy {
    fun adaptMessages(
        messages: List<ChatApiMessage>,
        allowCleartextHttp: Boolean,
        baseUrl: String
    ): List<ChatApiMessage> {
        if (!allowCleartextHttp || !baseUrl.isCleartextHttpUrl()) return messages

        var firstSystemSeen = false
        return messages.map { message ->
            if (message.role != "system") {
                message
            } else if (!firstSystemSeen) {
                firstSystemSeen = true
                message
            } else {
                message.copy(role = "assistant")
            }
        }
    }

    private fun String.isCleartextHttpUrl(): Boolean = runCatching {
        URI(trim()).scheme.equals("http", ignoreCase = true)
    }.getOrDefault(false)
}
