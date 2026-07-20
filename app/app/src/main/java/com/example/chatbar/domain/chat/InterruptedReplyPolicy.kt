package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole

internal object InterruptedReplyPolicy {
    fun persistableDraft(draft: ChatMessage?): ChatMessage? = draft?.takeIf {
        it.role == MessageRole.ASSISTANT && it.content.isNotBlank()
    }
}
