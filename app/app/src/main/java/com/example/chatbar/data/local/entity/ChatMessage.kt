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

@Serializable
enum class MessageFormatRepairNoticeKind {
    ERROR,
    STOPPED,
    LENGTH_ANOMALY
}

@Serializable
data class MessageFormatRepairNotice(
    val kind: MessageFormatRepairNoticeKind,
    val targetContent: String,
    val originalContent: String? = null,
    val errorMessage: String? = null
)

const val MESSAGE_ORDER_STEP: Long = 1_000_000L

fun Long.toMessageOrderKey(): Long = this * MESSAGE_ORDER_STEP

@Serializable
data class GeneratedImageCharacterPrompt(
    val prompt: String,
    val centerX: Float,
    val centerY: Float
)

@Serializable
data class GeneratedImageMetadata(
    val imagePath: String,
    val baseCaption: String,
    val characterPrompts: List<GeneratedImageCharacterPrompt> = emptyList(),
    val negativePrompt: String,
    val sizePreset: String,
    val width: Int,
    val height: Int
)

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
    val generatedImageMetadata: List<GeneratedImageMetadata> = emptyList(),
    val alternatives: List<String> = emptyList(),      // 重新生成的替代回复
    val currentAlternativeIndex: Int = 0,
    val reasoningContent: String? = null,              // 思维链内容
    val generatedFromMessageId: String? = null,
    val formatRepairNotice: MessageFormatRepairNotice? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val orderKey: Long = createdAt.toMessageOrderKey()
) {
    /** 获取当前显示的内容（考虑替代回复） */
    val displayContent: String
        get() = if (alternatives.isNotEmpty() && currentAlternativeIndex in alternatives.indices) {
            alternatives[currentAlternativeIndex]
        } else {
            content
        }

    companion object {
        val TimelineComparator: Comparator<ChatMessage> =
            compareBy<ChatMessage> { it.orderKey }
                .thenBy { it.createdAt }
                .thenBy { it.id }

        @OptIn(ExperimentalUuidApi::class)
        fun create(
            sessionId: String,
            role: MessageRole,
            content: String,
            images: List<String> = emptyList(),
            generatedImageMetadata: List<GeneratedImageMetadata> = emptyList(),
            reasoningContent: String? = null,
            generatedFromMessageId: String? = null,
            orderKey: Long? = null
        ): ChatMessage {
            val now = System.currentTimeMillis()
            return ChatMessage(
                id = Uuid.random().toString(),
                sessionId = sessionId,
                role = role,
                content = content,
                images = images,
                generatedImageMetadata = generatedImageMetadata,
                reasoningContent = reasoningContent,
                generatedFromMessageId = generatedFromMessageId,
                createdAt = now,
                updatedAt = now,
                orderKey = orderKey ?: now.toMessageOrderKey()
            )
        }
    }
}
