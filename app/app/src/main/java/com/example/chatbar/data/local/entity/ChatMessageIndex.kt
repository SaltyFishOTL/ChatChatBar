package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable

/** 轻量消息目录。正文仍只存在独立 ChatMessage 文件。 */
@Serializable
data class ChatMessageIndexEntry(
    val messageId: String,
    val role: MessageRole,
    val orderKey: Long,
    val createdAt: Long,
    val sourceTurnId: String? = null,
    val sourceTurnOrder: Long? = null,
    val timelineTurn: Long? = null,
    val generatedFromMessageId: String? = null,
    val contentChars: Int = 0
) {
    val turnKey: String
        get() = when {
            role == MessageRole.SYSTEM -> "system:$messageId"
            !sourceTurnId.isNullOrBlank() -> "source:$sourceTurnId"
            sourceTurnOrder != null -> "source-order:$sourceTurnOrder"
            timelineTurn != null -> "timeline:$timelineTurn"
            else -> "message:$messageId"
        }
}

@Serializable
data class ChatMessageIndex(
    val sessionId: String,
    val entries: List<ChatMessageIndexEntry> = emptyList(),
    val fileCount: Int = 0,
    val fileFingerprint: Long = 0,
    val updatedAt: Long = 0
)

data class ChatMessagePage(
    val messages: List<ChatMessage>,
    val hasOlder: Boolean,
    val hasNewer: Boolean,
    val totalMessageCount: Int
)

fun ChatMessage.toIndexEntry(): ChatMessageIndexEntry = ChatMessageIndexEntry(
    messageId = id,
    role = role,
    orderKey = orderKey,
    createdAt = createdAt,
    sourceTurnId = sourceTurnId,
    sourceTurnOrder = sourceTurnOrder,
    timelineTurn = timelineTurn,
    generatedFromMessageId = generatedFromMessageId,
    contentChars = displayContent.length + reasoningContent.orEmpty().length
)
