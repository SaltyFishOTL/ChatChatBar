package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatSession

object SessionDisplayTitlePolicy {
    const val MAX_LENGTH = 80

    fun normalize(value: String?): String? = value
        ?.replace("\r\n", " ")
        ?.replace('\r', ' ')
        ?.replace('\n', ' ')
        ?.trim()
        ?.take(MAX_LENGTH)
        ?.takeIf(String::isNotEmpty)

    fun resolve(session: ChatSession): String =
        normalize(session.displayTitleOverride) ?: session.title
}
