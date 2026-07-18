package com.example.chatbar.domain.rag

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.VectorChunk

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
        storage.saveAllUncached(ENTITY_TYPE, entityMap, VectorChunk.serializer())
    }

    suspend fun getChunkById(chunkId: String): VectorChunk? =
        storage.loadEntity(ENTITY_TYPE, chunkId, VectorChunk.serializer())

    suspend fun deleteChunkById(chunkId: String) {
        storage.deleteEntityUncached(ENTITY_TYPE, chunkId)
    }

    /**
     * 按来源类型和来源ID查询向量块
     */
    suspend fun getChunksBySource(
        sourceType: ChunkSourceType,
        sourceId: String
    ): List<VectorChunk> {
        return storage.queryUncached(ENTITY_TYPE, VectorChunk.serializer()) { chunk ->
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
        storage.deleteWhereUncached(ENTITY_TYPE, VectorChunk.serializer()) { chunk ->
            chunk.sourceType == sourceType && chunk.sourceId == sourceId
        }
    }

    /**
     * 按原文档ID删除对应的 RAG 向量块
     */
    suspend fun deleteChunksByDocumentId(docId: String) {
        storage.deleteWhereUncached(ENTITY_TYPE, VectorChunk.serializer()) { chunk ->
            chunk.sourceType == ChunkSourceType.DOCUMENT && chunk.metadata["originalDocId"] == docId
        }
    }

    /**
     * 删除与指定消息关联的向量块
     */
    suspend fun deleteChunksByMessageId(messageId: String) {
        storage.deleteWhereUncached(ENTITY_TYPE, VectorChunk.serializer()) { chunk ->
            chunk.messageId == messageId || chunk.metadataMessageIds().contains(messageId)
        }
    }

    suspend fun deleteAllChunksBySourceType(sourceType: ChunkSourceType) {
        storage.deleteWhereUncached(ENTITY_TYPE, VectorChunk.serializer()) { it.sourceType == sourceType }
    }

    suspend fun getChunksByMessageId(messageId: String): List<VectorChunk> {
        return storage.queryUncached(ENTITY_TYPE, VectorChunk.serializer()) { chunk ->
            chunk.messageId == messageId || chunk.metadataMessageIds().contains(messageId)
        }
    }

    /**
     * 获取某会话的所有向量块（CHAT_MEMORY 类型 + sourceId == sessionId）
     */
    suspend fun getAllChunksForSession(sessionId: String): List<VectorChunk> {
        return storage.queryUncached(ENTITY_TYPE, VectorChunk.serializer()) { chunk ->
            chunk.sourceType == ChunkSourceType.CHAT_MEMORY && chunk.sourceId == sessionId
        }
    }

    /** 新T块保存成功后清理同来源旧自动块；手动块和长期记忆旧块不受影响。 */
    suspend fun deleteSupersededAutomaticChatMemory(
        sessionId: String,
        sourceTurnId: String?,
        messageIds: Set<String>,
        keepChunkId: String?
    ): Int {
        val idsToDelete = automaticChatMemoryChunkIdsToReplace(
            chunks = getAllChunksForSession(sessionId),
            sourceTurnId = sourceTurnId,
            messageIds = messageIds,
            keepChunkId = keepChunkId
        )
        idsToDelete.forEach { id -> storage.deleteEntityUncached(ENTITY_TYPE, id) }
        return idsToDelete.size
    }

    /** 完整重建成功后只保留本次生成的自动块；所有手动块永久保留。 */
    suspend fun deleteObsoleteAutomaticChatMemory(
        sessionId: String,
        keepChunkIds: Set<String>
    ): Int {
        val idsToDelete = getAllChunksForSession(sessionId)
            .filter(ChatMemoryIndexPolicy::isAutomaticChunk)
            .map { it.id }
            .filterNot { it in keepChunkIds }
            .toSet()
        idsToDelete.forEach { id -> storage.deleteEntityUncached(ENTITY_TYPE, id) }
        return idsToDelete.size
    }

    /** 清理已删除消息的孤儿记忆，以及旧版本产生的同消息重复记忆。 */
    suspend fun pruneChatMemory(
        sessionId: String,
        liveMessageIds: Set<String>
    ): Int {
        val idsToDelete = chatMemoryChunkIdsToPrune(
            chunks = getAllChunksForSession(sessionId),
            liveMessageIds = liveMessageIds
        )
        idsToDelete.forEach { id ->
            storage.deleteEntityUncached(ENTITY_TYPE, id)
        }
        return idsToDelete.size
    }

    /** 获取某角色卡的参考文档向量块。 */
    suspend fun getAllChunksForCharacter(characterId: String): List<VectorChunk> {
        return storage.queryUncached(ENTITY_TYPE, VectorChunk.serializer()) { chunk ->
            chunk.sourceId == characterId && chunk.sourceType == ChunkSourceType.DOCUMENT
        }
    }

    /** 一次扫描加载本轮实际需要的文档和长期记忆来源。 */
    suspend fun getChunksForRetrieval(
        characterId: String?,
        sessionId: String?
    ): List<VectorChunk> {
        if (characterId == null && sessionId == null) return emptyList()
        return storage.queryUncached(ENTITY_TYPE, VectorChunk.serializer()) { chunk ->
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
        return storage.queryUncached(ENTITY_TYPE, VectorChunk.serializer()) { true }
    }
}

internal fun chatMemoryChunkIdsToPrune(
    chunks: List<VectorChunk>,
    liveMessageIds: Set<String>
): Set<String> {
    val idsToDelete = mutableSetOf<String>()
    val liveChunks = chunks.filter { chunk ->
        val messageIds = chunk.metadataMessageIds() + listOfNotNull(chunk.messageId)
        val isOrphan = messageIds.isNotEmpty() && messageIds.any { it !in liveMessageIds }
        if (isOrphan) idsToDelete.add(chunk.id)
        !isOrphan
    }

    liveChunks
        .filter { it.metadata["indexMode"] == "single_message_contextual" }
        .groupBy { chunk ->
            (chunk.metadataMessageIds() + listOfNotNull(chunk.messageId))
                .sorted()
                .joinToString(",")
        }
        .filterKeys { it.isNotBlank() }
        .values
        .forEach { duplicates ->
            val keep = duplicates.maxWithOrNull(compareBy<VectorChunk> { it.createdAt }.thenBy { it.id })
            duplicates.filterNot { it.id == keep?.id }.forEach { idsToDelete.add(it.id) }
        }

    return idsToDelete
}

internal fun automaticChatMemoryChunkIdsToReplace(
    chunks: List<VectorChunk>,
    sourceTurnId: String?,
    messageIds: Set<String>,
    keepChunkId: String?
): Set<String> = chunks.asSequence()
    .filter(ChatMemoryIndexPolicy::isAutomaticChunk)
    .filter { chunk ->
        val sameTurn = sourceTurnId != null && chunk.metadata["sourceTurnId"] == sourceTurnId
        val overlapsMessages = chunk.metadataMessageIds().any { it in messageIds } ||
            chunk.messageId?.let { it in messageIds } == true
        sameTurn || overlapsMessages
    }
    .map { it.id }
    .filterNot { it == keepChunkId }
    .toSet()

internal fun VectorChunk.isChatMemoryForSession(sessionId: String): Boolean =
    sourceType == ChunkSourceType.CHAT_MEMORY && sourceId == sessionId

private fun VectorChunk.metadataMessageIds(): Set<String> {
    return metadata["messageIds"]
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        .orEmpty()
}
