package com.example.chatbar.domain.rag

import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.VectorChunk

data class RetrievalIntent(
    val topic: List<String> = emptyList(),
    val queries: List<String> = emptyList(),
    val entities: List<String> = emptyList()
) {
    val shouldRecall: Boolean
        get() = topic.isNotEmpty() || queries.isNotEmpty() || entities.isNotEmpty()
}

typealias RetrievalPlan = RetrievalIntent

data class RetrievedKnowledgeCard(
    val id: String,
    val type: ChunkSourceType,
    val sourceId: String,
    val sourceLabel: String,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val vectorChunk: VectorChunk? = null
) {
    val typeLabel: String
        get() = when (type) {
            ChunkSourceType.DOCUMENT -> "知识库文档"
            ChunkSourceType.CHAT_MEMORY -> "历史对话碎片"
            ChunkSourceType.CHARACTER_SETTING -> "角色固定设定"
        }

    companion object {
        fun fromChunk(chunk: VectorChunk): RetrievedKnowledgeCard {
            return RetrievedKnowledgeCard(
                id = chunk.id,
                type = chunk.sourceType,
                sourceId = chunk.sourceId,
                sourceLabel = chunk.metadata["sourceLabel"]
                    ?: chunk.metadata["fileName"]
                    ?: chunk.metadata["originalDocId"]
                    ?: chunk.metadata["messageIds"]
                    ?: chunk.sourceId,
                content = chunk.content.trim().take(1800),
                metadata = chunk.metadata,
                vectorChunk = chunk
            )
        }
    }
}
