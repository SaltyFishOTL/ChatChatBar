package com.example.chatbar.domain.rag

import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.local.entity.VectorChunk
import java.security.MessageDigest

/**
 * RAG 管理器 — 编排完整的 RAG 流水线
 *
 * 职责：
 * 1. 索引：文档 / 聊天记忆 → 分块 → 向量化 → 持久化
 * 2. 检索：查询文本 → 向量化 → 相似度搜索 → 返回相关上下文
 * 3. 生命周期管理：删除/重新索引
 */
class RagManager(
    private val chunkingEngine: ChunkingEngine,
    private val embeddingService: EmbeddingService,
    private val vectorSearch: VectorSearchEngine,
    private val ragRepository: RagRepository
) {

    data class DocumentIndexResult(
        val contentHash: String,
        val chunkCount: Int
    )

    /**
     * 索引文档到向量库
     *
     * @param doc             文档信息
     * @param content         文档原始文本内容
     * @param characterCardId 所属角色卡ID（作为 sourceId 关联）
     */
    suspend fun indexDocument(
        doc: DocumentInfo,
        content: String,
        characterCardId: String,
        embeddingConfig: EmbeddingConfig
    ): DocumentIndexResult {
        val contentHash = sha256(content)
        // 清除旧索引
        ragRepository.deleteChunksByDocumentId(doc.id)

        val chunksWithMeta = chunkingEngine.chunkDocument(content, doc.id, doc.fileName)
        if (chunksWithMeta.isEmpty()) return DocumentIndexResult(contentHash, 0)

        val texts = chunksWithMeta.map { it.first }
        val embeddings = embeddingService.getEmbeddings(texts, embeddingConfig)

        val vectorChunks = chunksWithMeta.mapIndexed { index, (text, meta) ->
            VectorChunk.create(
                sourceType = ChunkSourceType.DOCUMENT,
                sourceId = characterCardId,
                content = text,
                embedding = embeddings[index],
                metadata = meta + mapOf(
                    "fileName" to doc.fileName,
                    "originalDocId" to doc.id,
                    "contentHash" to contentHash,
                    "embeddingKey" to embeddingConfig.key()
                )
            )
        }

        ragRepository.saveChunks(vectorChunks)
        return DocumentIndexResult(contentHash, vectorChunks.size)
    }

    /**
     * 搜索与查询相关的上下文
     *
     * 搜索范围：该角色卡的文档块 + 该会话的聊天记忆块
     *
     * @param query           查询文本
     * @param characterCardId 角色卡ID
     * @param sessionId       会话ID
     * @param topK            最多返回条数
     * @param threshold       最低相似度阈值
     * @return 相关的向量块列表
     */
    suspend fun indexTimelineTurnMemory(
        turn: ChatMemoryTurn,
        sessionId: String,
        embeddingConfig: EmbeddingConfig
    ) {
        if (!ChatMemoryIndexPolicy.shouldIndex(turn)) {
            ragRepository.deleteSupersededAutomaticChatMemory(
                sessionId = sessionId,
                sourceTurnId = turn.sourceTurnId,
                messageIds = turn.messageIds,
                keepChunkIds = emptySet()
            )
            return
        }
        val memoryTexts = ChatMemoryIndexPolicy.contentsForIndex(turn)
        val embeddings = embeddingService.getEmbeddings(memoryTexts, embeddingConfig)
        val now = System.currentTimeMillis()
        val chunks = memoryTexts.mapIndexed { index, memoryText ->
            VectorChunk(
                id = chatMemoryChunkId(sessionId, turn.identityKey, index),
                sourceType = ChunkSourceType.CHAT_MEMORY,
                sourceId = sessionId,
                content = memoryText,
                embedding = embeddings[index],
                messageId = turn.anchorMessage.id,
                metadata = buildMap {
                    put("sessionId", sessionId)
                    put("messageIds", turn.messageIds.joinToString(","))
                    put("messageTime", turn.anchorMessage.createdAt.toString())
                    put("indexMode", ChatMemoryIndexPolicy.INDEX_MODE)
                    put("contentVersion", ChatMemoryIndexPolicy.CONTENT_VERSION)
                    put("chunkIndex", index.toString())
                    put("chunkCount", memoryTexts.size.toString())
                    put("embeddingKey", embeddingKey(embeddingConfig))
                    put("sourceHash", hashContent(memoryText))
                    turn.sourceTurnId?.let { put("sourceTurnId", it) }
                    turn.sourceTurnOrder?.let { put("sourceTurnOrder", it.toString()) }
                },
                createdAt = now
            )
        }
        ragRepository.saveChunks(chunks)
        ragRepository.deleteSupersededAutomaticChatMemory(
            sessionId = sessionId,
            sourceTurnId = turn.sourceTurnId,
            messageIds = turn.messageIds,
            keepChunkIds = chunks.mapTo(mutableSetOf()) { it.id }
        )
    }

    @Deprecated(
        message = "Use ChatViewModel split retrieval instead: DOCUMENT and CHAT_MEMORY need separate thresholds/topK, and current context messages must be excluded from memory recall."
    )
    suspend fun search(
        query: String,
        characterCardId: String,
        sessionId: String,
        embeddingConfig: EmbeddingConfig,
        topK: Int = 5,
        threshold: Float = 0.7f
    ): List<VectorChunk> {
        val queryEmbedding = embeddingService.getEmbedding(query, embeddingConfig)

        // 合并角色卡相关块 + 会话记忆块
        val characterChunks = ragRepository.getAllChunksForCharacter(characterCardId)
        val sessionChunks = ragRepository.getAllChunksForSession(sessionId)
        val allChunks = characterChunks + sessionChunks

        if (allChunks.isEmpty()) return emptyList()

        return vectorSearch.search(queryEmbedding, allChunks, topK, threshold)
    }

    /**
     * 删除指定消息关联的记忆向量
     */
    suspend fun deleteMemoryForMessage(messageId: String) {
        ragRepository.deleteChunksByMessageId(messageId)
    }

    fun hashContent(content: String): String = sha256(content)

    fun embeddingKey(config: EmbeddingConfig): String = config.key()

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun EmbeddingConfig.key(): String {
        return sha256("${baseUrl.trimEnd('/')}|$modelName")
    }
}

internal fun chatMemoryChunkId(
    sessionId: String,
    turnIdentity: String,
    chunkIndex: Int = 0
): String {
    require(chunkIndex >= 0) { "chunkIndex must be non-negative" }
    val chunkIdentity = if (chunkIndex == 0) turnIdentity else "$turnIdentity|chunk:$chunkIndex"
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$sessionId|$chunkIdentity".toByteArray(Charsets.UTF_8))
    return "chat-memory-" + digest.joinToString("") { "%02x".format(it) }
}
