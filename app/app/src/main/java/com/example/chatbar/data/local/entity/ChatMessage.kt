package com.example.chatbar.data.local.entity

import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 消息角色
 */
@Serializable
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

/**
 * 聊天消息
 */
@Stable
@Serializable
data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val images: List<String> = emptyList(),           // 图片文件路径
    val alternatives: List<String> = emptyList(),      // 重新生成的替代回复
    val currentAlternativeIndex: Int = 0,
    val reasoningContent: String? = null,              // 思维链内容
    val createdAt: Long,
    val updatedAt: Long
) {
    /** 获取当前显示的内容（考虑替代回复） */
    val displayContent: String
        get() = if (alternatives.isNotEmpty() && currentAlternativeIndex in alternatives.indices) {
            alternatives[currentAlternativeIndex]
        } else {
            content
        }

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun create(
            sessionId: String,
            role: MessageRole,
            content: String,
            images: List<String> = emptyList(),
            reasoningContent: String? = null
        ): ChatMessage {
            val now = System.currentTimeMillis()
            return ChatMessage(
                id = Uuid.random().toString(),
                sessionId = sessionId,
                role = role,
                content = content,
                images = images,
                reasoningContent = reasoningContent,
                createdAt = now,
                updatedAt = now
            )
        }
    }
}
