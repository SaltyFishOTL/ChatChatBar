package com.example.chatbar.domain.rag

import com.example.chatbar.data.local.entity.VectorChunk
import kotlin.math.sqrt

/**
 * 向量搜索引擎 — 基于余弦相似度的内存向量检索
 *
 * 搜索策略：取 top-K 且相似度 ≥ threshold 的交集
 */
class VectorSearchEngine {

    /**
     * 计算两个向量的余弦相似度
     *
     * cos(a,b) = (a·b) / (|a| * |b|)
     *
     * @return 相似度值，范围 [-1, 1]；向量维度不一致或零向量时返回 0
     */
    fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        if (a.size != b.size || a.isEmpty()) return 0f

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = sqrt(normA) * sqrt(normB)
        if (denominator == 0f) return 0f

        return dotProduct / denominator
    }

    /**
     * 搜索与查询向量最相关的向量块
     *
     * @param query     查询向量
     * @param chunks    候选向量块列表
     * @param topK      最多返回的结果数
     * @param threshold 最低相似度阈值（低于此值的结果会被过滤）
     * @return 按相似度降序排列的结果列表（同时满足 top-K 和 threshold 两个条件）
     */
    fun search(
        query: List<Float>,
        chunks: List<VectorChunk>,
        topK: Int = 5,
        threshold: Float = 0.7f
    ): List<VectorChunk> {
        if (chunks.isEmpty() || query.isEmpty()) return emptyList()

        return chunks
            .map { chunk -> chunk to cosineSimilarity(query, chunk.embedding) }
            .filter { (_, similarity) -> similarity >= threshold }
            .sortedByDescending { (_, similarity) -> similarity }
            .take(topK)
            .map { (chunk, _) -> chunk }
    }
}
