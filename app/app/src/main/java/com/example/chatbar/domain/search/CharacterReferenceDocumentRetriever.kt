package com.example.chatbar.domain.search

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.local.entity.VectorChunk
import com.example.chatbar.domain.rag.ChunkingEngine
import com.example.chatbar.domain.rag.EmbeddingService
import com.example.chatbar.domain.rag.VectorSearchEngine

const val CHARACTER_REFERENCE_DOCUMENT_TOP_K = 20

data class CharacterReferenceDocument(
    val fileName: String,
    val content: String
)

class PreparedReferenceDocumentIndex internal constructor(
    internal val embeddingConfig: EmbeddingConfig?,
    internal val chunks: List<VectorChunk>
)

interface CharacterReferenceDocumentRetriever {
    suspend fun retrieve(
        documents: List<CharacterReferenceDocument>,
        userInput: String,
        currentCard: CharacterCard,
        topK: Int = CHARACTER_REFERENCE_DOCUMENT_TOP_K,
        onStatus: (String) -> Unit = {}
    ): List<SearchHit>
}

class RagCharacterReferenceDocumentRetriever(
    private val chunkingEngine: ChunkingEngine,
    private val embeddingService: EmbeddingService,
    private val vectorSearch: VectorSearchEngine,
    private val embeddingConfigProvider: suspend () -> EmbeddingConfig?
) : CharacterReferenceDocumentRetriever {
    override suspend fun retrieve(
        documents: List<CharacterReferenceDocument>,
        userInput: String,
        currentCard: CharacterCard,
        topK: Int,
        onStatus: (String) -> Unit
    ): List<SearchHit> {
        val prepared = prepare(documents, onStatus)
        val query = buildCharacterReferenceDocumentQuery(userInput, currentCard)
        return searchPrepared(
            prepared = prepared,
            query = query,
            topK = topK,
            statusText = "正在匹配用户要求与角色卡已有内容",
            onStatus = onStatus
        )
    }

    suspend fun prepare(
        documents: List<CharacterReferenceDocument>,
        onStatus: (String) -> Unit = {}
    ): PreparedReferenceDocumentIndex {
        val candidates = documents.flatMapIndexed { documentIndex, document ->
            chunkingEngine.chunkDocument(
                content = document.content,
                documentId = "reference-$documentIndex",
                fileName = document.fileName
            ).mapIndexed { chunkIndex, (content, metadata) ->
                ReferenceDocumentChunk(
                    documentIndex = documentIndex,
                    chunkIndex = chunkIndex,
                    fileName = document.fileName,
                    sourceLabel = metadata["sourceLabel"].orEmpty().ifBlank { document.fileName },
                    content = content
                )
            }
        }
        if (candidates.isEmpty()) {
            return PreparedReferenceDocumentIndex(null, emptyList())
        }

        val embeddingConfig = embeddingConfigProvider()
            ?: error("未配置全局嵌入模型，无法检索参考文档")
        onStatus("正在向量化参考文档：${candidates.size} 张卡片")
        val embeddings = candidates.chunked(EMBEDDING_BATCH_SIZE).flatMap { batch ->
            embeddingService.getEmbeddings(batch.map(ReferenceDocumentChunk::content), embeddingConfig)
        }
        check(embeddings.size == candidates.size) {
            "参考文档向量数量不匹配：期望 ${candidates.size}，实际 ${embeddings.size}"
        }
        val vectorChunks = candidates.mapIndexed { index, candidate ->
            VectorChunk.create(
                sourceType = ChunkSourceType.DOCUMENT,
                sourceId = "reference-${candidate.documentIndex}",
                content = candidate.content,
                embedding = embeddings[index],
                metadata = mapOf(
                    "fileName" to candidate.fileName,
                    "sourceLabel" to candidate.sourceLabel,
                    "referenceDocumentIndex" to candidate.documentIndex.toString(),
                    "referenceChunkIndex" to candidate.chunkIndex.toString()
                )
            )
        }

        return PreparedReferenceDocumentIndex(embeddingConfig, vectorChunks)
    }

    suspend fun searchPrepared(
        prepared: PreparedReferenceDocumentIndex,
        query: String,
        topK: Int = CHARACTER_REFERENCE_DOCUMENT_TOP_K,
        statusText: String = "正在匹配参考文档",
        onStatus: (String) -> Unit = {}
    ): List<SearchHit> {
        if (prepared.chunks.isEmpty()) return emptyList()
        val embeddingConfig = requireNotNull(prepared.embeddingConfig) {
            "参考文档索引缺少嵌入模型配置"
        }
        val safeQuery = query.trim().ifBlank { "世界观、人物、地点、组织、规则与历史" }
            .take(MAX_REFERENCE_DOCUMENT_QUERY_CHARS)
        onStatus(statusText)
        val queryEmbedding = embeddingService.getEmbedding(safeQuery, embeddingConfig)
        val ranked = vectorSearch.search(
            query = queryEmbedding,
            chunks = prepared.chunks,
            topK = topK.coerceAtLeast(1),
            threshold = -1f
        )
        return ranked.map { chunk ->
            val documentIndex = chunk.metadata["referenceDocumentIndex"].orEmpty()
            val chunkIndex = chunk.metadata["referenceChunkIndex"].orEmpty()
            SearchHit(
                title = chunk.metadata["sourceLabel"]
                    ?: chunk.metadata["fileName"]
                    ?: "参考文档",
                url = "reference-document://local/document-$documentIndex/chunk-$chunkIndex",
                content = chunk.content,
                rawContent = chunk.content,
                score = vectorSearch.cosineSimilarity(queryEmbedding, chunk.embedding).toDouble(),
                query = safeQuery
            )
        }
    }

    private data class ReferenceDocumentChunk(
        val documentIndex: Int,
        val chunkIndex: Int,
        val fileName: String,
        val sourceLabel: String,
        val content: String
    )

    private companion object {
        const val EMBEDDING_BATCH_SIZE = 64
    }
}

internal fun buildCharacterReferenceDocumentQuery(
    userInput: String,
    currentCard: CharacterCard
): String {
    val query = buildString {
        userInput.trim().takeIf(String::isNotBlank)?.let {
            appendLine("用户要求：")
            appendLine(it)
        }
        appendLine("角色卡已有内容：")
        appendCardQueryField("名称", currentCard.name)
        appendCardQueryField("开场白", currentCard.greeting)
        appendCardQueryField("基本设定", currentCard.basicSetting)
        when (currentCard.editMode) {
            CharacterEditMode.STRUCTURED -> currentCard.characters.forEachIndexed { index, character ->
                appendLine("人物 ${index + 1}：")
                appendCardQueryField("姓名", character.name)
                appendCardQueryField("简介", character.profile)
                appendCardQueryField("外貌", character.appearance)
                appendCardQueryField("服装", character.clothing)
                appendCardQueryField("能力", character.abilities)
                appendCardQueryField("习惯", character.habits)
                appendCardQueryField("背景", character.background)
                appendCardQueryField("关系", character.relationships)
                appendCardQueryField("语气", character.speakingStyle)
            }
            CharacterEditMode.FREEFORM -> appendCardQueryField(
                "自由人物设定",
                currentCard.freeformCharacterText
            )
        }
    }.trim()
    return query
        .ifBlank { "角色设定、人物关系、外貌、背景与世界观" }
        .take(MAX_REFERENCE_DOCUMENT_QUERY_CHARS)
}

private fun StringBuilder.appendCardQueryField(label: String, value: String) {
    value.trim().takeIf(String::isNotBlank)?.let {
        append(label).append("：").appendLine(it)
    }
}

private const val MAX_REFERENCE_DOCUMENT_QUERY_CHARS = 12_000
