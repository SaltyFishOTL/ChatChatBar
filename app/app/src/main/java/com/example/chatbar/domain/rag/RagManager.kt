package com.example.chatbar.domain.rag

import com.example.chatbar.data.local.entity.ChatMessage
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
     * 索引聊天消息为记忆向量
     *
     * @param messages  待索引的消息列表
     * @param sessionId 会话ID
     */
    suspend fun indexChatMemory(
        messages: List<ChatMessage>,
        sessionId: String,
        embeddingConfig: EmbeddingConfig
    ) {
        val chunksWithMeta = chunkingEngine.chunkChatMessages(messages)
        if (chunksWithMeta.isEmpty()) return

        val texts = chunksWithMeta.map { it.first }
        val embeddings = embeddingService.getEmbeddings(texts, embeddingConfig)

        val vectorChunks = chunksWithMeta.mapIndexed { index, (text, meta) ->
            // 提取第一条消息的 ID 作为 messageId 关联
            val firstMessageId = meta["messageIds"]?.split(",")?.firstOrNull()
            VectorChunk.create(
                sourceType = ChunkSourceType.CHAT_MEMORY,
                sourceId = sessionId,
                content = text,
                embedding = embeddings[index],
                messageId = firstMessageId,
                metadata = meta + mapOf("sessionId" to sessionId)
            )
        }

        ragRepository.saveChunks(vectorChunks)
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
    suspend fun indexMessagePairMemory(
        pair: ChatMemoryMessagePair,
        sessionId: String,
        embeddingConfig: EmbeddingConfig
    ) {
        if (!ChatMemoryIndexPolicy.shouldIndex(pair)) {
            pair.messageIds.forEach { ragRepository.deleteChunksByMessageId(it) }
            return
        }
        val memoryText = ChatMemoryIndexPolicy.contentForIndex(pair)

        val embedding = embeddingService.getEmbedding(memoryText, embeddingConfig)
        pair.messageIds.forEach { ragRepository.deleteChunksByMessageId(it) }
        val chunk = VectorChunk(
            id = chatMemoryChunkId(sessionId, pair.assistantMessage.id),
            sourceType = ChunkSourceType.CHAT_MEMORY,
            sourceId = sessionId,
            content = memoryText,
            embedding = embedding,
            messageId = pair.assistantMessage.id,
            metadata = mapOf(
                "sessionId" to sessionId,
                "messageIds" to listOf(pair.userMessage.id, pair.assistantMessage.id).joinToString(","),
                "userMessageId" to pair.userMessage.id,
                "assistantMessageId" to pair.assistantMessage.id,
                "messageTime" to pair.assistantMessage.createdAt.toString(),
                "indexMode" to "message_pair",
                "contentVersion" to "4",
                "embeddingKey" to embeddingKey(embeddingConfig)
            ),
            createdAt = System.currentTimeMillis()
        )
        ragRepository.saveChunks(listOf(chunk))
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

internal fun chatMemoryChunkId(sessionId: String, messageId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$sessionId|$messageId".toByteArray(Charsets.UTF_8))
    return "chat-memory-" + digest.joinToString("") { "%02x".format(it) }
}
