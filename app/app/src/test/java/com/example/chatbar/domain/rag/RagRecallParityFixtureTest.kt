package com.example.chatbar.domain.rag

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.VectorChunk
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagRecallParityFixtureTest {
    @Test
    fun androidRagRecallParityFixtureEmitsStableReport() {
        val embeddingKey = embeddingKey(
            baseUrl = "https://example.test/v1",
            modelName = "text-embedding"
        )
        val chunks = listOf(
            chunk(
                id = "doc-lock-guitar",
                sourceType = ChunkSourceType.DOCUMENT,
                sourceId = "card-1",
                content = "【来源】world.md > band\nLOCK 是 RAS 的吉他手，也负责稳定舞台推进。",
                embedding = listOf(1f, 0f),
                metadata = docMetadata(
                    embeddingKey = embeddingKey,
                    sourceLabel = "world.md > band",
                    originalDocId = "doc-world"
                )
            ),
            chunk(
                id = "doc-layer-vocal",
                sourceType = ChunkSourceType.DOCUMENT,
                sourceId = "card-1",
                content = "【来源】music.md > band\nLAYER 是 RAS 的主唱和贝斯手。",
                embedding = listOf(0.82f, 0.1f),
                metadata = docMetadata(
                    embeddingKey = embeddingKey,
                    sourceLabel = "music.md > band",
                    originalDocId = "doc-music"
                )
            ),
            chunk(
                id = "doc-noise",
                sourceType = ChunkSourceType.DOCUMENT,
                sourceId = "card-1",
                content = "【来源】food.md > cafe\n咖啡店今日推荐芝士蛋糕。",
                embedding = listOf(0f, 1f),
                metadata = docMetadata(
                    embeddingKey = embeddingKey,
                    sourceLabel = "food.md > cafe",
                    originalDocId = "doc-food"
                )
            ),
            chunk(
                id = "memory-old-lock",
                sourceType = ChunkSourceType.CHAT_MEMORY,
                sourceId = "session-1",
                messageId = "old-user",
                content = "用户之前确认过：LOCK 负责 RAS 吉他与舞台节奏。",
                embedding = listOf(0.96f, 0f),
                metadata = mapOf("messageIds" to "old-user")
            ),
            chunk(
                id = "memory-current-excluded",
                sourceType = ChunkSourceType.CHAT_MEMORY,
                sourceId = "session-1",
                messageId = "current-user",
                content = "当前上下文里的 LOCK 记忆，不应重复进入 RAG。",
                embedding = listOf(1f, 0f),
                metadata = mapOf("messageIds" to "current-user")
            )
        )
        val messages = listOf(
            message("old-user", "之前说 LOCK 很可靠。"),
            message("current-user", "她在 RAS 里负责什么？")
        )
        val plan = RetrievalPlan(
            topic = listOf("RAS band role"),
            queries = listOf("LOCK RAS guitarist stage rhythm"),
            entities = listOf("LOCK", "RAS")
        )

        val result = runFixture(
            query = "她在 RAS 里负责什么？",
            contextWindowSize = 1,
            chunks = chunks,
            messages = messages,
            embeddingKey = embeddingKey,
            queryEmbedding = listOf(1f, 0f),
            plan = plan
        )
        val report = report(result)

        assertEquals(
            mapOf(
                "fixtureId" to "rag-parity-lock-ras-v1",
                "query" to "她在 RAS 里负责什么？",
                "ragQuery" to result.ragQuery,
                "cards" to listOf(
                    mapOf(
                        "type" to "DOCUMENT",
                        "source" to "world.md > band",
                        "content" to "【来源】world.md > band\nLOCK 是 RAS 的吉他手，也负责稳定舞台推进。"
                    ),
                    mapOf(
                        "type" to "DOCUMENT",
                        "source" to "music.md > band",
                        "content" to "【来源】music.md > band\nLAYER 是 RAS 的主唱和贝斯手。"
                    ),
                    mapOf(
                        "type" to "CHAT_MEMORY",
                        "source" to "old-user",
                        "content" to "用户之前确认过：LOCK 负责 RAS 吉他与舞台节奏。"
                    )
                ),
                "debugMustContain" to listOf(
                    "eligible chat_memory after context filter=1",
                    "Retrieval Planner",
                    "Document multi-route top scores",
                    "Chat memory multi-route top scores"
                )
            ),
            report
        )
        val debug = result.debugLogs.joinToString("\n")
        val debugMustContain = report.getValue("debugMustContain") as List<*>
        debugMustContain.forEach { token ->
            assertTrue(debug.contains(token.toString()))
        }
    }
}

private data class FixtureResult(
    val ragQuery: String,
    val cards: List<RetrievedKnowledgeCard>,
    val debugLogs: List<String>
)

private data class Rank(
    val chunk: VectorChunk,
    val vectorScore: Float,
    val lexicalScore: Float,
    val combinedScore: Float,
    val vectorRank: Int?,
    val lexicalRank: Int?,
    val rrfScore: Float
)

private fun runFixture(
    query: String,
    contextWindowSize: Int,
    chunks: List<VectorChunk>,
    messages: List<ChatMessage>,
    embeddingKey: String,
    queryEmbedding: List<Float>,
    plan: RetrievalPlan
): FixtureResult {
    val activeContextIds = messages.takeLast(contextWindowSize).map { it.id }.toSet()
    val ragQuery = plan.toParityRagQuery(query, messages)
    val debugLogs = mutableListOf<String>()

    val documentChunks = chunks.filter {
        it.sourceType == ChunkSourceType.DOCUMENT &&
            it.sourceId == "card-1" &&
            it.metadata["embeddingKey"] == embeddingKey &&
            !it.hasMismatchedSourceLabel()
    }
    val memoryChunks = chunks.filter {
        it.sourceType == ChunkSourceType.CHAT_MEMORY &&
            it.sourceId == "session-1" &&
            it.messageIds().none { id -> id in activeContextIds }
    }

    debugLogs += "eligible chat_memory after context filter=${memoryChunks.size}, active context messages=${activeContextIds.size}"
    debugLogs += plan.toParityDebugLog()

    val documentRanks = rankChunksByMultiRoute(
        chunks = documentChunks,
        queryEmbedding = queryEmbedding,
        ragQuery = ragQuery,
        routeLimit = routeCandidateLimit(topK = 2, totalSize = documentChunks.size)
    )
    debugLogs += "Document multi-route top scores:\n${documentRanks.toDebugLines()}"
    val documentCards = documentRanks
        .filter { it.vectorScore >= 0.5f || it.lexicalScore >= 0.24f }
        .withSourceDiversity(topK = 2)
        .map { RetrievedKnowledgeCard.fromChunk(it.chunk) }

    val memoryRanks = rankChunksByMultiRoute(
        chunks = memoryChunks,
        queryEmbedding = queryEmbedding,
        ragQuery = ragQuery,
        routeLimit = routeCandidateLimit(topK = 2, totalSize = memoryChunks.size)
    )
    debugLogs += "Chat memory multi-route top scores:\n${memoryRanks.toDebugLines()}"
    val memoryCards = memoryRanks
        .filter { it.vectorScore >= 0.5f || it.lexicalScore >= 0.18f }
        .withSourceDiversity(topK = 2)
        .map { RetrievedKnowledgeCard.fromChunk(it.chunk) }

    return FixtureResult(
        ragQuery = ragQuery,
        cards = documentCards + memoryCards,
        debugLogs = debugLogs
    )
}

private fun report(result: FixtureResult): Map<String, Any> {
    return mapOf(
        "fixtureId" to "rag-parity-lock-ras-v1",
        "query" to "她在 RAS 里负责什么？",
        "ragQuery" to result.ragQuery,
        "cards" to result.cards.map { card ->
            mapOf(
                "type" to card.type.name,
                "source" to card.sourceLabel,
                "content" to card.content
            )
        },
        "debugMustContain" to listOf(
            "eligible chat_memory after context filter=1",
            "Retrieval Planner",
            "Document multi-route top scores",
            "Chat memory multi-route top scores"
        )
    )
}

private fun chunk(
    id: String,
    sourceType: ChunkSourceType,
    sourceId: String,
    content: String,
    embedding: List<Float>,
    messageId: String? = null,
    metadata: Map<String, String> = emptyMap()
): VectorChunk {
    return VectorChunk(
        id = id,
        sourceType = sourceType,
        sourceId = sourceId,
        messageId = messageId,
        content = content,
        embedding = embedding,
        metadata = metadata,
        createdAt = 1L
    )
}

private fun message(id: String, content: String): ChatMessage {
    return ChatMessage(
        id = id,
        sessionId = "session-1",
        role = MessageRole.USER,
        content = content,
        createdAt = 1L,
        updatedAt = 1L
    )
}

private fun docMetadata(
    embeddingKey: String,
    sourceLabel: String,
    originalDocId: String
): Map<String, String> {
    return mapOf(
        "embeddingKey" to embeddingKey,
        "sourceLabel" to sourceLabel,
        "fileName" to sourceLabel.substringBefore(" > "),
        "originalDocId" to originalDocId
    )
}

private fun embeddingKey(baseUrl: String, modelName: String): String {
    val input = "${baseUrl.trimEnd('/')}|$modelName"
    return MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private fun VectorChunk.messageIds(): Set<String> {
    val metadataIds = metadata["messageIds"]
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    return (metadataIds + listOfNotNull(messageId)).toSet()
}

private fun routeCandidateLimit(topK: Int, totalSize: Int): Int {
    if (totalSize <= 0) return 0
    return maxOf(topK * 6, 30).coerceAtMost(totalSize)
}

private fun rankChunksByMultiRoute(
    chunks: List<VectorChunk>,
    queryEmbedding: List<Float>,
    ragQuery: String,
    routeLimit: Int
): List<Rank> {
    if (chunks.isEmpty() || routeLimit <= 0) return emptyList()

    val vectorScores = chunks.associateWith { cosineSimilarity(queryEmbedding, it.embedding) }
    val lexicalScores = chunks.associateWith { it.lexicalScore(ragQuery) }
    val vectorRanks = vectorScores.entries
        .sortedByDescending { it.value }
        .take(routeLimit)
        .mapIndexed { index, entry -> entry.key.id to index + 1 }
        .toMap()
    val lexicalRanks = lexicalScores.entries
        .filter { it.value > 0f }
        .sortedByDescending { it.value }
        .take(routeLimit)
        .mapIndexed { index, entry -> entry.key.id to index + 1 }
        .toMap()
    val chunksById = chunks.associateBy { it.id }
    val candidateIds = (vectorRanks.keys + lexicalRanks.keys).distinct()

    return candidateIds.mapNotNull { id ->
        val chunk = chunksById[id] ?: return@mapNotNull null
        val vectorRank = vectorRanks[id]
        val lexicalRank = lexicalRanks[id]
        val vectorScore = vectorScores[chunk] ?: 0f
        val lexicalScore = lexicalScores[chunk] ?: 0f
        Rank(
            chunk = chunk,
            vectorScore = vectorScore,
            lexicalScore = lexicalScore,
            combinedScore = vectorScore + lexicalScore,
            vectorRank = vectorRank,
            lexicalRank = lexicalRank,
            rrfScore = vectorRank.rrfScore() + lexicalRank.rrfScore()
        )
    }.sortedWith(
        compareByDescending<Rank> { it.rrfScore }
            .thenByDescending { it.combinedScore }
    )
}

private fun Int?.rrfScore(): Float = this?.let { 1f / (60f + it) } ?: 0f

private fun List<Rank>.withSourceDiversity(topK: Int): List<Rank> {
    if (topK <= 0 || isEmpty()) return emptyList()
    val sourceCap = maxOf(1, (topK + 1) / 2)
    val selected = mutableListOf<Rank>()
    val sourceCounts = mutableMapOf<String, Int>()

    for (rank in this) {
        val source = rank.chunk.sourceDiversityKey()
        val count = sourceCounts[source] ?: 0
        if (count < sourceCap) {
            selected += rank
            sourceCounts[source] = count + 1
            if (selected.size >= topK) return selected
        }
    }
    for (rank in this) {
        if (rank !in selected) {
            selected += rank
            if (selected.size >= topK) break
        }
    }
    return selected
}

private fun VectorChunk.sourceDiversityKey(): String {
    return metadata["sourceLabel"]
        ?.substringBefore(" > ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: metadata["fileName"]?.trim()?.takeIf { it.isNotBlank() }
        ?: metadata["originalDocId"]?.trim()?.takeIf { it.isNotBlank() }
        ?: sourceId
}

private fun RetrievalPlan.toParityRagQuery(
    currentUserContent: String,
    contextMsgs: List<ChatMessage>
): String {
    val plannedTopics = topic.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    val plannedQueries = queries.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    val plannedEntities = entities.map { it.trim() }.filter { it.isNotBlank() }.distinct()

    return buildString {
        if (plannedTopics.isNotEmpty()) {
            appendLine("Topic: ${plannedTopics.joinToString(", ")}")
        }
        if (plannedEntities.isNotEmpty()) {
            appendLine("Entities: ${plannedEntities.joinToString(", ")}")
        }
        if (plannedQueries.isNotEmpty()) {
            appendLine("Queries:")
            plannedQueries.take(8).forEach { appendLine(it) }
        }
        if (isBlank()) {
            appendLine(buildRagQuery(currentUserContent, contextMsgs))
        }
    }.trim().take(3200)
}

private fun buildRagQuery(currentUserContent: String, contextMsgs: List<ChatMessage>): String {
    val recentUserContext = contextMsgs
        .dropLastWhile { it.role == MessageRole.USER && it.content == currentUserContent }
        .takeLast(6)
        .filter { it.role == MessageRole.USER }

    return buildString {
        appendLine("Current user message:")
        appendLine(currentUserContent.trim().take(800))
        if (recentUserContext.isNotEmpty()) {
            appendLine()
            appendLine("Recent user context:")
            recentUserContext.forEach { msg ->
                val text = msg.displayContent.replace(Regex("\\s+"), " ").trim().take(300)
                if (text.isNotBlank()) {
                    appendLine("user: $text")
                }
            }
        }
    }.trim().take(2400)
}

private fun RetrievalPlan.toParityDebugLog(): String {
    return buildString {
        appendLine("--- Retrieval Planner ---")
        appendLine("topic=$topic")
        appendLine("queries=$queries")
        appendLine("entities=$entities")
        appendLine("should_recall=$shouldRecall")
    }.trim()
}

private fun List<Rank>.toDebugLines(): String {
    return take(6).joinToString("\n") {
        "${it.chunk.id}: vector=${"%.3f".format(Locale.US, it.vectorScore)}, lexical=${"%.3f".format(Locale.US, it.lexicalScore)}, rrf=${"%.4f".format(Locale.US, it.rrfScore)}"
    }
}

private fun VectorChunk.contentSourceLabel(): String? {
    val firstLine = content.lineSequence().firstOrNull()?.trim().orEmpty()
    return firstLine.removePrefix("【来源】").takeIf { it != firstLine && it.isNotBlank() }
}

private fun VectorChunk.hasMismatchedSourceLabel(): Boolean {
    val contentSource = contentSourceLabel() ?: return false
    val contentFile = contentSource.substringBefore(" > ").trim()
    val metadataFile = metadata["fileName"]?.trim()
    val metadataSource = metadata["sourceLabel"]?.substringBefore(" > ")?.trim()
    return listOfNotNull(metadataFile, metadataSource).any { it.isNotBlank() && it != contentFile }
}

private fun VectorChunk.lexicalScore(query: String): Float {
    val tokens = query.searchTokens()
    if (tokens.isEmpty()) return 0f

    val haystack = buildString {
        append(content)
        append('\n')
        metadata.values.forEach {
            append(it)
            append('\n')
        }
    }.lowercase(Locale.ROOT)

    var score = 0f
    for (token in tokens) {
        if (haystack.contains(token)) {
            score += when {
                token.length >= 5 -> 0.34f
                token.length == 4 -> 0.26f
                token.length == 3 -> 0.16f
                else -> 0.08f
            }
        }
    }
    return score.coerceAtMost(0.72f)
}

private fun String.searchTokens(): Set<String> {
    val normalized = lowercase(Locale.ROOT)
    val tokens = linkedSetOf<String>()

    Regex("[a-z0-9_!！?？-]{2,}").findAll(normalized).forEach { match ->
        tokens.add(match.value)
    }
    Regex("\\p{IsHan}+").findAll(normalized).forEach { match ->
        val run = match.value
        val maxGram = minOf(6, run.length)
        for (size in 2..maxGram) {
            for (start in 0..run.length - size) {
                val token = run.substring(start, start + size)
                if (token !in RAG_LEXICAL_STOPWORDS) {
                    tokens.add(token)
                }
            }
        }
    }
    return tokens.take(240).toSet()
}

private fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
    if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f
    var dot = 0f
    var normA = 0f
    var normB = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    if (normA == 0f || normB == 0f) return 0f
    return (dot / kotlin.math.sqrt(normA * normB)).coerceIn(-1f, 1f)
}

private val RAG_LEXICAL_STOPWORDS = setOf(
    "当前", "用户", "消息", "最近", "上下", "文中", "什么", "还有", "值得", "注意",
    "一下", "一个", "这个", "那个", "哪些", "怎么", "如何", "我们", "你们",
    "他们", "她们", "关于", "提到", "讨论", "对话", "内容", "相关"
)
