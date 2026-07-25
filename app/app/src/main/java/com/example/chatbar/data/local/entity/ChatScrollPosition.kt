package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable

/** 会话消息列表最后一次稳定视口位置。 */
@Serializable
data class ChatScrollPosition(
    val sessionId: String,
    val anchorMessageId: String? = null,
    val fallbackMessageIndex: Int = 0,
    val scrollOffset: Int = 0,
    val capturedAt: Long = 0
)
