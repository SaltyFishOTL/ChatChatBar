package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 聊天会话
 */
@Serializable
data class PromptCacheCheckpoint(
    /** 缓存前缀依赖的稳定系统提示词指纹。变化时此快照自动失效。 */
    val stablePromptFingerprint: String = "",
    /** 已冻结的长期记忆快照，覆盖 [coveredThroughMessageId] 及之前的对话。 */
    val memorySnapshot: String = "",
    val coveredThroughMessageId: String? = null,
    val createdAt: Long = 0L
)

@Serializable
data class ChatSession(
    val id: String,
    val characterCardId: String,
    val title: String,
    val modelId: String? = null,
    val imageModelId: String? = null,
    val formatCardId: String? = null,
    val replyLength: String? = null,         // 回复长度设定
    val replyLanguage: String? = null,       // 回复语言
    val roleplayStyle: String? = null,       // 会话扮演风格覆盖
    val supplementarySetting: String? = null, // 补充设定
    val playerName: String? = null,           // 玩家名称覆盖
    val playerSetting: String? = null,        // 个人设定覆盖
    val chatBackground: String? = null,
    val imagePromptPreference: String = "",
    val longTermMemoryEnabled: Boolean = true,
    val longTermMemory: String = "",
    val longTermMemoryUpdatedThroughMessageId: String? = null,
    val promptCacheCheckpoint: PromptCacheCheckpoint? = null,
    val contextWindowSize: Int = 20,
    val extraWorldBookIds: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val lastMessagePreview: String? = null,
    val lastMessageTime: Long? = null,
    val lastMessageRole: MessageRole? = null,
    val timedWorldInfo: Map<String, TimedEffectState> = emptyMap(),
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun create(
            characterCardId: String,
            title: String,
            modelId: String? = null,
            formatCardId: String? = null
        ): ChatSession {
            val now = System.currentTimeMillis()
            return ChatSession(
                id = Uuid.random().toString(),
                characterCardId = characterCardId,
                title = title,
                modelId = modelId,
                formatCardId = formatCardId,
                createdAt = now,
                updatedAt = now
            )
        }
    }
}
