package com.example.chatbar.domain.rag

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.VectorChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RAG 向量块持久化仓库
 *
 * 使用 JsonFileStorage 存储向量块，以 chunk ID 为文件名。
 * 目录结构: entities/vector_chunks/{chunkId}.json
 */
class RagRepository(private val storage: JsonFileStorage) {

    companion object {
        private const val ENTITY_TYPE = "vector_chunks"
    }

    /**
     * 批量保存向量块
     */
    suspend fun saveChunks(chunks: List<VectorChunk>) {
        val entityMap = chunks.associate { it.id to it }
        storage.saveAll(ENTITY_TYPE, entityMap, VectorChunk.serializer())
    }

    /**
     * 按来源类型和来源ID查询向量块
     */
    suspend fun getChunksBySource(
        sourceType: ChunkSourceType,
        sourceId: String
    ): List<VectorChunk> {
        return storage.query(ENTITY_TYPE, VectorChunk.serializer()) { chunk ->
            chunk.sourceType == sourceType && chunk.sourceId == sourceId
        }
    }

    /**
     * 删除指定来源的所有向量块
     */
    suspend fun deleteChunksBySource(
        sourceType: ChunkSourceType,
        sourceId: String
    ) {
        storage.deleteWhere(ENTITY_TYPE, VectorChunk.serializer()) { chunk ->
            chunk.sourceType == sourceType && chunk.sourceId == sourceId
        }
    }

    /**
     * 按原文档ID删除对应的 RAG 向量块
     */
    suspend fun deleteChunksByDocumentId(docId: String) {
        storage.deleteWhere(ENTITY_TYPE, VectorChunk.serializer()) { chunk ->
            chunk.sourceType == ChunkSourceType.DOCUMENT && chunk.metadata["originalDocId"] == docId
        }
    }

    /**
     * 删除与指定消息关联的向量块
     */
    suspend fun deleteChunksByMessageId(messageId: String) {
        storage.deleteWhere(ENTITY_TYPE, VectorChunk.serializer()) { chunk ->
            chunk.messageId == messageId || chunk.metadataMessageIds().contains(messageId)
        }
    }

    suspend fun deleteAllChunksBySourceType(sourceType: ChunkSourceType) {
        storage.deleteWhere(ENTITY_TYPE, VectorChunk.serializer()) { it.sourceType == sourceType }
    }

    suspend fun getChunksByMessageId(messageId: String): List<VectorChunk> {
        return storage.query(ENTITY_TYPE, VectorChunk.serializer()) { chunk ->
            chunk.messageId == messageId || chunk.metadataMessageIds().contains(messageId)
        }
    }

    /**
     * 获取某会话的所有向量块（CHAT_MEMORY 类型 + sourceId == sessionId）
     */
    suspend fun getAllChunksForSession(sessionId: String): List<VectorChunk> {
        return storage.query(ENTITY_TYPE, VectorChunk.serializer()) { chunk ->
            chunk.sourceType == ChunkSourceType.CHAT_MEMORY && chunk.sourceId == sessionId
        }
    }

    /** 获取某角色卡的参考文档向量块。 */
    suspend fun getAllChunksForCharacter(characterId: String): List<VectorChunk> {
        return storage.query(ENTITY_TYPE, VectorChunk.serializer()) { chunk ->
            chunk.sourceId == characterId && chunk.sourceType == ChunkSourceType.DOCUMENT
        }
    }

    /** 一次扫描加载本轮实际需要的文档和长期记忆来源。 */
    suspend fun getChunksForRetrieval(
        characterId: String?,
        sessionId: String?
    ): List<VectorChunk> {
        if (characterId == null && sessionId == null) return emptyList()
        return storage.query(ENTITY_TYPE, VectorChunk.serializer()) { chunk ->
            (characterId != null &&
                chunk.sourceType == ChunkSourceType.DOCUMENT &&
                chunk.sourceId == characterId) ||
                (sessionId != null &&
                    chunk.sourceType == ChunkSourceType.CHAT_MEMORY &&
                    chunk.sourceId == sessionId)
        }
    }

    /**
     * 获取所有向量块
     */
    suspend fun getAllChunks(): List<VectorChunk> {
        return storage.loadAll(ENTITY_TYPE, VectorChunk.serializer())
    }
}

private fun VectorChunk.metadataMessageIds(): Set<String> {
    return metadata["messageIds"]
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        .orEmpty()
}
