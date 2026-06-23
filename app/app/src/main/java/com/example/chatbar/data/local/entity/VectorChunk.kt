package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 向量块来源类型
 */
@Serializable
enum class ChunkSourceType {
    CHARACTER_SETTING, // 角色设定
    DOCUMENT,          // 自定义文档
    CHAT_MEMORY        // 聊天记忆
}

/**
 * 向量块 - RAG检索用
 */
@Serializable
data class VectorChunk(
    val id: String,
    val sourceType: ChunkSourceType,
    val sourceId: String,           // characterCardId 或 sessionId
    val messageId: String? = null,  // CHAT_MEMORY类型时关联的消息ID
    val content: String,
    val embedding: List<Float>,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long
) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun create(
            sourceType: ChunkSourceType,
            sourceId: String,
            content: String,
            embedding: List<Float>,
            messageId: String? = null,
            metadata: Map<String, String> = emptyMap()
        ): VectorChunk = VectorChunk(
            id = Uuid.random().toString(),
            sourceType = sourceType,
            sourceId = sourceId,
            messageId = messageId,
            content = content,
            embedding = embedding,
            metadata = metadata,
            createdAt = System.currentTimeMillis()
        )
    }
}
