package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 存档槽位 - 保存会话完整状态快照
 */
@Serializable
data class SaveSlot(
    val id: String,
    val sessionId: String,
    val name: String,
    val description: String? = null,
    val playerName: String? = null,
    val playerSetting: String? = null,
    val supplementarySetting: String? = null,
    val modelId: String? = null,
    val formatCardId: String? = null,
    val replyLength: String? = null,
    val replyLanguage: String? = null,
    val roleplayStyle: String? = null,
    val chatBackground: String? = null,
    val contextWindowSize: Int? = null,
    val messages: List<ChatMessage> = emptyList(),
    val vectorChunks: List<VectorChunk> = emptyList(), // 记忆状态
    val createdAt: Long
) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun create(
            sessionId: String,
            name: String,
            description: String? = null,
            messages: List<ChatMessage> = emptyList(),
            vectorChunks: List<VectorChunk> = emptyList()
        ): SaveSlot = SaveSlot(
            id = Uuid.random().toString(),
            sessionId = sessionId,
            name = name,
            description = description,
            messages = messages,
            vectorChunks = vectorChunks,
            createdAt = System.currentTimeMillis()
        )
    }
}
