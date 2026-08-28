package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.card.extractJsonObjectCandidates
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.NovelAiTagSearchEvidence
import com.example.chatbar.domain.prompt.NovelAiCodexEvidence
import com.example.chatbar.domain.prompt.PromptTemplates
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class NovelAiTagSearchDecision(
    val action: String = "",
    val queries: List<String> = emptyList(),
    val sceneDescription: String = ""
)

data class NovelAiTagSearchDecisionResult(
    val decision: NovelAiTagSearchDecision? = null,
    val systemPrompt: String = "",
    val requestText: String = "",
    val reasoningResponse: String = "",
    val rawResponse: String = "",
    val failureReason: String = ""
)

internal fun NovelAiTagSearchDecisionResult.displayResponse(): String =
    renderNovelAiTagPlannerStream(reasoningResponse, rawResponse)

internal fun renderNovelAiTagPlannerStream(reasoning: String, content: String): String =
    buildString {
        reasoning.takeIf(String::isNotBlank)?.let {
            appendLine("【思考】")
            append(it.trimEnd())
        }
        content.takeIf(String::isNotBlank)?.let {
            if (isNotEmpty()) appendLine().appendLine()
            appendLine("【输出】")
            append(it.trimEnd())
        }
    }

internal class NovelAiTagPlannerStreamingProgress(
    private val onUpdate: (String) -> Unit
) {
    private val reasoning = StringBuilder()
    private val content = StringBuilder()

    val reasoningText: String get() = reasoning.toString()
    val contentText: String get() = content.toString()

    fun appendReasoning(delta: String) {
        reasoning.append(delta)
        publish()
    }

    fun appendContent(delta: String) {
        content.append(delta)
        publish()
    }

    private fun publish() {
        onUpdate(renderNovelAiTagPlannerStream(reasoningText, contentText))
    }
}

enum class NovelAiTagCategory(val code: Int, val label: String) {
    GENERAL(0, "general"),
    COPYRIGHT(3, "copyright"),
    CHARACTER(4, "character");

    companion object {
        fun fromCode(code: Int): NovelAiTagCategory? = entries.firstOrNull { it.code == code }
    }
}

data class NovelAiTagCandidate(
    val name: String,
    val translatedName: String,
    val count: Long,
    val category: NovelAiTagCategory
)

data class NovelAiTagSearchOutcome(
    val effectiveQuery: String,
    val candidates: List<NovelAiTagCandidate>,
    val fromCache: Boolean = false
)

data class NovelAiTagQueryResult(
    val query: String,
    val effectiveQuery: String = query,
    val candidates: List<NovelAiTagCandidate> = emptyList(),
    val fromCache: Boolean = false,
    val failureReason: String = ""
) {
    fun asEvidence(): List<NovelAiTagSearchEvidence> = candidates.map { candidate ->
        NovelAiTagSearchEvidence(
            query = query,
            name = candidate.name,
            translatedName = candidate.translatedName,
            count = candidate.count,
            category = candidate.category.label
        )
    }
}

data class NovelAiTagResearchResult(
    val decisionResults: List<NovelAiTagSearchDecisionResult> = emptyList(),
    val sceneDescription: String = "",
    val queryResults: List<NovelAiTagQueryResult> = emptyList(),
    val codexSearchResult: NovelAiCodexSearchResult = NovelAiCodexSearchResult(),
    val transcript: String = ""
) {
    val plannerRequestText: String
        get() = decisionResults.firstOrNull()?.requestText.orEmpty()

    val evidence: List<NovelAiTagSearchEvidence>
        get() = queryResults.flatMap(NovelAiTagQueryResult::asEvidence)

    val codexEvidence: List<NovelAiCodexEvidence>
        get() = codexSearchResult.matches.map { match ->
            NovelAiCodexEvidence(
                id = match.entry.id,
                kind = match.entry.kind.name,
                title = match.entry.title,
                category = match.entry.category,
                prompt = match.entry.prompt,
                matchedQueries = match.matchedQueries
            )
        }
}

interface NovelAiTagSearchPlanner {
    suspend fun decide(
        taskInput: String,
        characterPrompts: List<Pair<String, String>>,
        imageBase64s: List<String>,
        model: ModelConfig,
        playerName: String? = null,
        botName: String = "",
        onRawText: (String) -> Unit = {}
    ): NovelAiTagSearchDecisionResult

    suspend fun decideQueriesOnly(
        taskInput: String,
        characterPrompts: List<Pair<String, String>>,
        model: ModelConfig,
        playerName: String? = null,
        botName: String = "",
        onRawText: (String) -> Unit = {}
    ): NovelAiTagSearchDecisionResult = decide(
        taskInput = taskInput,
        characterPrompts = characterPrompts,
        imageBase64s = emptyList(),
        model = model,
        playerName = playerName,
        botName = botName,
        onRawText = onRawText
    )
}

interface NovelAiTagSearchClient {
    suspend fun search(query: String): NovelAiTagSearchOutcome
}

@OptIn(ExperimentalSerializationApi::class)
class LlmNovelAiTagSearchPlanner(
    private val chatService: StreamingChatService,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
        allowTrailingComma = true
    }
) : NovelAiTagSearchPlanner {
    override suspend fun decide(
        taskInput: String,
        characterPrompts: List<Pair<String, String>>,
        imageBase64s: List<String>,
        model: ModelConfig,
        playerName: String?,
        botName: String,
        onRawText: (String) -> Unit
    ): NovelAiTagSearchDecisionResult {
        val systemPrompt = PromptTemplates.novelAiTagSearchPlannerSystem(
            playerName = playerName,
            botName = botName
        )
        val requestText = PromptTemplates.novelAiTagSearchPlannerUser(
            taskInput = taskInput,
            characterPrompts = characterPrompts,
            playerName = playerName,
            botName = botName
        )
        val streamingProgress = NovelAiTagPlannerStreamingProgress(onRawText)
        return try {
            val raw = chatService.completeTextStreaming(
                messages = requestMessages(requestText, imageBase64s, systemPrompt),
                modelConfig = model,
                thinkingBudget = NOVEL_AI_SCENE_PLANNING_THINKING_BUDGET,
                onDelta = streamingProgress::appendContent,
                onReasoningDelta = streamingProgress::appendReasoning
            )
            val decision = parseDecision(raw, characterPrompts)
            if (decision == null) {
                NovelAiTagSearchDecisionResult(
                    systemPrompt = systemPrompt,
                    requestText = requestText,
                    reasoningResponse = streamingProgress.reasoningText,
                    rawResponse = raw,
                    failureReason = "画面草案与检索规划 JSON 无法解析"
                )
            } else {
                NovelAiTagSearchDecisionResult(
                    decision = decision,
                    systemPrompt = systemPrompt,
                    requestText = requestText,
                    reasoningResponse = streamingProgress.reasoningText,
                    rawResponse = raw
                )
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            NovelAiTagSearchDecisionResult(
                systemPrompt = systemPrompt,
                requestText = requestText,
                reasoningResponse = streamingProgress.reasoningText,
                rawResponse = streamingProgress.contentText,
                failureReason = error.message ?: error::class.java.simpleName
            )
        }
    }

    override suspend fun decideQueriesOnly(
        taskInput: String,
        characterPrompts: List<Pair<String, String>>,
        model: ModelConfig,
        playerName: String?,
        botName: String,
        onRawText: (String) -> Unit
    ): NovelAiTagSearchDecisionResult {
        val systemPrompt = PromptTemplates.novelAiTagRevisionQueryPlannerSystem(playerName, botName)
        val requestText = PromptTemplates.novelAiTagRevisionQueryPlannerUser(
            taskInput = taskInput,
            characterPrompts = characterPrompts,
            playerName = playerName,
            botName = botName
        )
        val streamingProgress = NovelAiTagPlannerStreamingProgress(onRawText)
        return try {
            val raw = chatService.completeTextStreaming(
                messages = requestMessages(requestText, emptyList(), systemPrompt),
                modelConfig = model,
                thinkingBudget = NOVEL_AI_SCENE_PLANNING_THINKING_BUDGET,
                onDelta = streamingProgress::appendContent,
                onReasoningDelta = streamingProgress::appendReasoning
            )
            val decision = parseQueryDecision(raw, characterPrompts)
            if (decision == null) {
                NovelAiTagSearchDecisionResult(
                    systemPrompt = systemPrompt,
                    requestText = requestText,
                    reasoningResponse = streamingProgress.reasoningText,
                    rawResponse = raw,
                    failureReason = "修改需求检索规划 JSON 无法解析"
                )
            } else {
                NovelAiTagSearchDecisionResult(
                    decision = decision,
                    systemPrompt = systemPrompt,
                    requestText = requestText,
                    reasoningResponse = streamingProgress.reasoningText,
                    rawResponse = raw
                )
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            NovelAiTagSearchDecisionResult(
                systemPrompt = systemPrompt,
                requestText = requestText,
                reasoningResponse = streamingProgress.reasoningText,
                rawResponse = streamingProgress.contentText,
                failureReason = error.message ?: error::class.java.simpleName
            )
        }
    }

    internal fun parseDecision(
        raw: String,
        characterPrompts: List<Pair<String, String>> = emptyList()
    ): NovelAiTagSearchDecision? {
        val candidates = raw.extractJsonObjectCandidates().ifEmpty { listOf(raw.trim()) }
        val parsed = candidates.firstNotNullOfOrNull { candidate ->
            decodeFlexibleDecision(candidate) ?: decodeStrictDecision(candidate)
        }
        return parsed?.withoutExistingCharacterQueries(characterPrompts)
    }

    internal fun parseQueryDecision(
        raw: String,
        characterPrompts: List<Pair<String, String>> = emptyList()
    ): NovelAiTagSearchDecision? {
        val candidates = raw.extractJsonObjectCandidates().ifEmpty { listOf(raw.trim()) }
        val parsed = candidates.firstNotNullOfOrNull { candidate ->
            runCatching {
                val obj = json.parseToJsonElement(candidate).jsonObject
                if (!obj.containsKey("queries") && !obj.containsKey("keywords")) return@runCatching null
                val queries = obj.queryValues().asSequence()
                    .map(String::normalizePlannerQuery)
                    .filter { it.length in MIN_TAG_QUERY_LENGTH..MAX_TAG_QUERY_LENGTH }
                    .distinctBy { it.lowercase(Locale.ROOT) }
                    .take(MAX_TAG_SEARCH_QUERIES)
                    .toList()
                NovelAiTagSearchDecision(
                    action = if (queries.isEmpty()) TAG_SEARCH_ACTION_FINISH else TAG_SEARCH_ACTION_SEARCH,
                    queries = queries
                )
            }.getOrNull()
        }
        return parsed?.withoutExistingCharacterQueries(characterPrompts)
    }

    internal fun requestMessages(
        requestText: String,
        imageBase64s: List<String>,
        systemPrompt: String = PromptTemplates.novelAiTagSearchPlannerSystem()
    ): List<ChatApiMessage> {
        val sourceImages = imageBase64s.filter(String::isNotBlank)
        val userMessage = if (sourceImages.isEmpty()) {
            ChatApiMessage.text("user", requestText)
        } else {
            ChatApiMessage.withImages("user", requestText, sourceImages)
        }
        return listOf(
            ChatApiMessage.text("system", systemPrompt),
            userMessage
        )
    }

    private fun decodeStrictDecision(candidate: String): NovelAiTagSearchDecision? = runCatching {
        normalize(json.decodeFromString(NovelAiTagSearchDecision.serializer(), candidate))
    }.getOrNull()

    private fun decodeFlexibleDecision(candidate: String): NovelAiTagSearchDecision? = runCatching {
        val obj = json.parseToJsonElement(candidate).jsonObject
        val needSearch = obj["needSearch"]?.jsonPrimitive?.booleanOrNull
            ?: obj["need_search"]?.jsonPrimitive?.booleanOrNull
        val action = obj.stringField("action")
            ?: obj.stringField("type")
            ?: if (needSearch == false) TAG_SEARCH_ACTION_FINISH else null
        val queries = obj.queryValues()
        val sceneDescription = obj.stringField("sceneDescription")
            ?: obj.stringField("scene_description")
            ?: obj.stringField("imageDescription")
            ?: obj.stringField("image_description")
        normalize(
            NovelAiTagSearchDecision(
                action = action ?: if (queries.isNotEmpty()) TAG_SEARCH_ACTION_SEARCH else "",
                queries = queries,
                sceneDescription = sceneDescription.orEmpty()
            )
        )
    }.getOrNull()

    private fun normalize(decision: NovelAiTagSearchDecision): NovelAiTagSearchDecision? {
        val sceneDescription = decision.sceneDescription.normalizeSceneDescription()
            .takeIf(String::isNotBlank)
            ?: return null
        val action = decision.action.trim().lowercase(Locale.ROOT)
        val queries = decision.queries.asSequence()
            .map(String::normalizePlannerQuery)
            .filter { it.length in MIN_TAG_QUERY_LENGTH..MAX_TAG_QUERY_LENGTH }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(MAX_TAG_SEARCH_QUERIES)
            .toList()
        val normalizedAction = when {
            action in TAG_SEARCH_FINISH_ACTIONS -> TAG_SEARCH_ACTION_FINISH
            action.isBlank() && queries.isEmpty() -> TAG_SEARCH_ACTION_FINISH
            action.isBlank() || action in TAG_SEARCH_ACTION_ALIASES -> TAG_SEARCH_ACTION_SEARCH
            else -> return null
        }
        return NovelAiTagSearchDecision(
            action = if (queries.isEmpty()) TAG_SEARCH_ACTION_FINISH else normalizedAction,
            queries = if (normalizedAction == TAG_SEARCH_ACTION_FINISH) emptyList() else queries,
            sceneDescription = sceneDescription
        )
    }

    private fun NovelAiTagSearchDecision.withoutExistingCharacterQueries(
        characterPrompts: List<Pair<String, String>>
    ): NovelAiTagSearchDecision {
        if (queries.isEmpty() || characterPrompts.isEmpty()) return this
        val existingKeys = buildSet {
            characterPrompts.forEach { (name, prompt) ->
                (sequenceOf(name) + name.split(Regex("[/;；()（）]")).asSequence())
                    .map(String::existingCharacterLookupKey)
                    .filter(String::isNotBlank)
                    .forEach(::add)
                prompt.split(',', '\n', ';', '；')
                    .map(String::existingCharacterLookupKey)
                    .filter(String::isNotBlank)
                    .forEach(::add)
            }
        }
        val filteredQueries = queries.filterNot { query ->
            query.existingCharacterLookupKey() in existingKeys
        }
        return copy(
            action = if (filteredQueries.isEmpty()) TAG_SEARCH_ACTION_FINISH else action,
            queries = filteredQueries
        )
    }

    private fun JsonObject.stringField(key: String): String? =
        get(key)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

    private fun JsonObject.queryValues(): List<String> {
        val queryElement = get("queries") ?: get("keywords")
        val fromArray = (queryElement as? JsonArray).orEmpty().mapNotNull { element ->
            when (element) {
                is JsonObject -> element.stringField("query")
                    ?: element.stringField("keyword")
                    ?: element.stringField("term")
                else -> element.jsonPrimitive.contentOrNull
            }
        }
        if (fromArray.isNotEmpty()) return fromArray
        return listOfNotNull(
            stringField("query") ?: stringField("keyword") ?: stringField("term")
        )
    }
}

class NovelAiTagResearchService(
    private val planner: NovelAiTagSearchPlanner,
    private val searchClient: NovelAiTagSearchClient,
    private val codexSearcher: NovelAiCodexSearcher = NovelAiCodexSearcher { _, _, _ ->
        NovelAiCodexSearchResult()
    },
    private val requestTimeoutMs: Long = TAG_SEARCH_REQUEST_TIMEOUT_MS,
    private val batchTimeoutMs: Long = TAG_SEARCH_BATCH_TIMEOUT_MS
) {
    suspend fun planSceneOnly(
        taskInput: String,
        characterPrompts: List<Pair<String, String>>,
        imageBase64s: List<String>,
        model: ModelConfig,
        playerName: String? = null,
        botName: String = "",
        onProgress: (String) -> Unit = {}
    ): NovelAiTagResearchResult {
        val transcript = TagResearchTranscript(onProgress)
        val title = "AI 图片画面设计"
        transcript.update(title, "正在连接 AI；只生成自然语言画面草案…")
        val decisionResult = planner.decide(
            taskInput = taskInput,
            characterPrompts = characterPrompts,
            imageBase64s = imageBase64s,
            model = model,
            playerName = playerName,
            botName = botName,
            onRawText = { streamed -> transcript.update(title, streamed) }
        )
        val decision = decisionResult.decision
            ?: error("画面规划失败：${decisionResult.failureReason.ifBlank { "返回内容无法解析" }}")
        transcript.complete(title, decisionResult.displayResponse())
        return NovelAiTagResearchResult(
            decisionResults = listOf(decisionResult),
            sceneDescription = decision.sceneDescription,
            transcript = transcript.snapshot()
        )
    }

    suspend fun research(
        taskInput: String,
        characterPrompts: List<Pair<String, String>>,
        imageBase64s: List<String>,
        model: ModelConfig,
        onProgress: (String) -> Unit
    ): NovelAiTagResearchResult = research(
        taskInput = taskInput,
        characterPrompts = characterPrompts,
        imageBase64s = imageBase64s,
        model = model,
        diversityKey = "",
        playerName = null,
        botName = "",
        onProgress = onProgress
    )

    suspend fun research(
        taskInput: String,
        characterPrompts: List<Pair<String, String>>,
        imageBase64s: List<String>,
        model: ModelConfig,
        diversityKey: String = "",
        playerName: String? = null,
        botName: String = "",
        onProgress: (String) -> Unit = {}
    ): NovelAiTagResearchResult {
        val transcript = TagResearchTranscript(onProgress)
        val planningTitle = "AI 图片画面设计"
        transcript.update(
            planningTitle,
            "正在连接 AI；思考与画面规划将在此处实时显示…"
        )
        val decisionResult = planner.decide(
            taskInput = taskInput,
            characterPrompts = characterPrompts,
            imageBase64s = imageBase64s,
            model = model,
            playerName = playerName,
            botName = botName,
            onRawText = { streamed -> transcript.update(planningTitle, streamed) }
        )
        val decision = decisionResult.decision
        if (decision == null) {
            val fallbackSceneDescription = taskInput.normalizeSceneDescription()
            transcript.complete(
                planningTitle,
                listOf(
                    decisionResult.displayResponse(),
                    "规划失败：${decisionResult.failureReason.ifBlank { "未知错误" }}；跳过 Danbooru 词条库，继续本地法典召回"
                ).filter(String::isNotBlank).joinToString("\n")
            )
            val codexResult = retrieveCodex(
                queries = emptyList(),
                sceneDescription = fallbackSceneDescription,
                diversityKey = diversityKey,
                transcript = transcript
            )
            return NovelAiTagResearchResult(
                decisionResults = listOf(decisionResult),
                sceneDescription = fallbackSceneDescription,
                codexSearchResult = codexResult,
                transcript = transcript.snapshot()
            )
        }
        transcript.complete(
            planningTitle,
            listOf(
                decisionResult.displayResponse(),
                if (decision.queries.isEmpty()) {
                    "画面草案完成；无需查询 Danbooru 词条库，使用草案召回本地经验模板"
                } else {
                    "画面草案完成；同时使用草案与 ${decision.queries.size} 个检索词召回本地经验模板，并查询 Danbooru 词条库"
                }
            ).filter(String::isNotBlank).joinToString("\n")
        )
        val codexResult = retrieveCodex(
            queries = decision.queries,
            sceneDescription = decision.sceneDescription,
            diversityKey = diversityKey,
            transcript = transcript
        )
        if (decision.action == TAG_SEARCH_ACTION_FINISH || decision.queries.isEmpty()) {
            return NovelAiTagResearchResult(
                decisionResults = listOf(decisionResult),
                sceneDescription = decision.sceneDescription,
                codexSearchResult = codexResult,
                transcript = transcript.snapshot()
            )
        }

        val batchTitle = "Danbooru 词条库批量搜索"
        val statusLock = Any()
        val statuses = decision.queries.map { query ->
            "- $query → q=${query.normalizeDanbooruTagQuery()}｜等待查询"
        }.toMutableList()
        transcript.update(batchTitle, statuses.joinToString("\n"))
        val rawResults = coroutineScope {
            decision.queries.mapIndexed { index, query ->
                async {
                    val effectiveQuery = query.normalizeDanbooruTagQuery()
                    synchronized(statusLock) {
                        statuses[index] = "- $query → q=$effectiveQuery｜请求中…"
                        transcript.update(batchTitle, statuses.joinToString("\n"))
                    }
                    val result = searchOne(query, effectiveQuery)
                    synchronized(statusLock) {
                        statuses[index] = result.batchProgressText()
                        transcript.update(batchTitle, statuses.joinToString("\n"))
                    }
                    result
                }
            }.awaitAll()
        }

        val seenTags = mutableSetOf<String>()
        val results = rawResults.map { result ->
            val remainingCandidates = MAX_TOTAL_TAG_CANDIDATES - seenTags.size
            result.copy(
                candidates = result.candidates.asSequence()
                    .filter { seenTags.add(it.name.lowercase(Locale.ROOT)) }
                    .take(remainingCandidates.coerceAtLeast(0))
                    .toList()
            )
        }
        transcript.complete(
            batchTitle,
            statuses.joinToString("\n") +
                "\n\n全局去重后保留 ${results.sumOf { it.candidates.size }} 个候选"
        )
        return NovelAiTagResearchResult(
            decisionResults = listOf(decisionResult),
            sceneDescription = decision.sceneDescription,
            queryResults = results,
            codexSearchResult = codexResult,
            transcript = transcript.snapshot()
        )
    }

    suspend fun researchTagsOnly(
        taskInput: String,
        characterPrompts: List<Pair<String, String>>,
        model: ModelConfig,
        playerName: String? = null,
        botName: String = "",
        onProgress: (String) -> Unit = {}
    ): NovelAiTagResearchResult {
        val transcript = TagResearchTranscript(onProgress)
        val planningTitle = "AI 修改需求检索规划"
        transcript.update(planningTitle, "正在判断本轮是否需要查询新 Tag…")
        val decisionResult = planner.decideQueriesOnly(
            taskInput = taskInput,
            characterPrompts = characterPrompts,
            model = model,
            playerName = playerName,
            botName = botName,
            onRawText = { streamed -> transcript.update(planningTitle, streamed) }
        )
        val decision = decisionResult.decision
        if (decision == null) {
            transcript.complete(
                planningTitle,
                listOf(
                    decisionResult.displayResponse(),
                    "规划失败：${decisionResult.failureReason.ifBlank { "未知错误" }}；跳过本轮 Danbooru 词条库查询，继续修改 Prompt"
                ).filter(String::isNotBlank).joinToString("\n")
            )
            return NovelAiTagResearchResult(
                decisionResults = listOf(decisionResult),
                transcript = transcript.snapshot()
            )
        }
        transcript.complete(
            planningTitle,
            listOf(
                decisionResult.displayResponse(),
                if (decision.queries.isEmpty() || decision.action == TAG_SEARCH_ACTION_FINISH) {
                    "本轮无需查询 Danbooru 词条库"
                } else {
                    "本轮需要查询 ${decision.queries.size} 个新词条"
                }
            ).filter(String::isNotBlank).joinToString("\n")
        )
        if (decision.action == TAG_SEARCH_ACTION_FINISH || decision.queries.isEmpty()) {
            return NovelAiTagResearchResult(
                decisionResults = listOf(decisionResult),
                sceneDescription = decision.sceneDescription,
                transcript = transcript.snapshot()
            )
        }

        val batchTitle = "Danbooru 词条库批量搜索"
        val statusLock = Any()
        val statuses = decision.queries.map { query ->
            "- $query → q=${query.normalizeDanbooruTagQuery()}｜等待查询"
        }.toMutableList()
        transcript.update(batchTitle, statuses.joinToString("\n"))
        val rawResults = coroutineScope {
            decision.queries.mapIndexed { index, query ->
                async {
                    val effectiveQuery = query.normalizeDanbooruTagQuery()
                    synchronized(statusLock) {
                        statuses[index] = "- $query → q=$effectiveQuery｜请求中…"
                        transcript.update(batchTitle, statuses.joinToString("\n"))
                    }
                    val result = searchOne(query, effectiveQuery)
                    synchronized(statusLock) {
                        statuses[index] = result.batchProgressText()
                        transcript.update(batchTitle, statuses.joinToString("\n"))
                    }
                    result
                }
            }.awaitAll()
        }
        val seenTags = mutableSetOf<String>()
        val results = rawResults.map { result ->
            val remainingCandidates = MAX_TOTAL_TAG_CANDIDATES - seenTags.size
            result.copy(
                candidates = result.candidates.asSequence()
                    .filter { seenTags.add(it.name.lowercase(Locale.ROOT)) }
                    .take(remainingCandidates.coerceAtLeast(0))
                    .toList()
            )
        }
        transcript.complete(
            batchTitle,
            statuses.joinToString("\n") +
                "\n\n全局去重后保留 ${results.sumOf { it.candidates.size }} 个候选"
        )
        return NovelAiTagResearchResult(
            decisionResults = listOf(decisionResult),
            sceneDescription = decision.sceneDescription,
            queryResults = results,
            transcript = transcript.snapshot()
        )
    }

    private fun retrieveCodex(
        queries: List<String>,
        sceneDescription: String,
        diversityKey: String,
        transcript: TagResearchTranscript
    ): NovelAiCodexSearchResult {
        val codexTitle = "本地 NovelAI 法典召回"
        transcript.update(codexTitle, "按概念模糊匹配并执行多样性抽样…")
        val result = runCatching {
            codexSearcher.search(queries, sceneDescription, diversityKey)
        }.getOrElse { error ->
            NovelAiCodexSearchResult(
                failureReason = error.message ?: error::class.java.simpleName
            )
        }
        transcript.complete(
            codexTitle,
            when {
                result.failureReason.isNotBlank() -> "召回失败：${result.failureReason}"
                result.matches.isEmpty() -> "没有匹配到可用参考"
                else -> result.matches.joinToString("\n") { match ->
                    "- [${match.entry.kind}] ${match.entry.title}｜${match.entry.category.ifBlank { "未分类" }}｜命中=${match.matchedQueries.joinToString("/")}"
                }
            }
        )
        return result
    }

    private suspend fun searchOne(query: String, effectiveQuery: String): NovelAiTagQueryResult = try {
        val outcome = withTimeout(minOf(requestTimeoutMs, batchTimeoutMs)) {
            searchClient.search(query)
        }
        NovelAiTagQueryResult(
            query = query,
            effectiveQuery = outcome.effectiveQuery,
            candidates = outcome.candidates.take(MAX_TAG_CANDIDATES_PER_QUERY),
            fromCache = outcome.fromCache
        )
    } catch (error: TimeoutCancellationException) {
        NovelAiTagQueryResult(
            query = query,
            effectiveQuery = effectiveQuery,
            failureReason = "请求超时"
        )
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        NovelAiTagQueryResult(
            query = query,
            effectiveQuery = effectiveQuery,
            failureReason = error.message ?: error::class.java.simpleName
        )
    }

    private fun NovelAiTagQueryResult.batchProgressText(): String = when {
        failureReason.isNotBlank() -> "- $query → q=$effectiveQuery｜失败：$failureReason"
        candidates.isEmpty() -> "- $query → q=$effectiveQuery｜未找到候选"
        else -> buildString {
            append("- $query → q=$effectiveQuery｜${candidates.size} 个候选")
            if (fromCache) append("（缓存）")
            candidates.forEach { candidate ->
                appendLine()
                append("  · ${candidate.name}")
                candidate.translatedName.takeIf(String::isNotBlank)?.let { append("｜$it") }
                append("｜${candidate.category.label}｜count=${candidate.count}")
            }
        }
    }
}

private class TagResearchTranscript(
    private val onProgress: (String) -> Unit
) {
    private val completed = StringBuilder()
    private var activeTitle = ""
    private var activeText = ""

    fun update(title: String, text: String) {
        if (activeTitle.isNotBlank() && activeTitle != title) commitActive()
        activeTitle = title
        activeText = text
        onProgress(snapshot())
    }

    fun complete(title: String, text: String) {
        update(title, text)
        commitActive()
        onProgress(snapshot())
    }

    fun snapshot(): String = buildString {
        append(completed)
        if (activeTitle.isNotBlank()) {
            if (isNotEmpty() && !endsWith("\n\n")) appendLine()
            appendLine("【$activeTitle】")
            append(activeText.trimEnd())
        }
    }.trimEnd()

    private fun commitActive() {
        if (activeTitle.isBlank()) return
        if (completed.isNotEmpty() && !completed.endsWith("\n\n")) completed.appendLine().appendLine()
        completed.appendLine("【$activeTitle】")
        completed.append(activeText.trimEnd())
        activeTitle = ""
        activeText = ""
    }
}

private fun String.normalizePlannerQuery(): String =
    replace(Regex("\\s+"), " ").trim().take(MAX_TAG_QUERY_LENGTH)

private fun String.normalizeSceneDescription(): String =
    replace(Regex("\\s+"), " ").trim()

private fun String.existingCharacterLookupKey(): String =
    replace(Regex("[{}\\[\\]()]"), "")
        .replace(Regex("[:：]\\s*[+-]?\\d+(?:\\.\\d+)?$"), "")
        .normalizeTagLookupKey()
        .replace(" ", "")

internal const val MAX_TAG_SEARCH_QUERIES = 6
internal const val MAX_TAG_CANDIDATES_PER_QUERY = 8
internal const val MAX_TOTAL_TAG_CANDIDATES = 24
private const val MIN_TAG_QUERY_LENGTH = 2
private const val MAX_TAG_QUERY_LENGTH = 80
private const val TAG_SEARCH_REQUEST_TIMEOUT_MS = 8_000L
private const val TAG_SEARCH_BATCH_TIMEOUT_MS = 20_000L
private const val TAG_SEARCH_ACTION_SEARCH = "search"
private const val TAG_SEARCH_ACTION_FINISH = "finish"
private val TAG_SEARCH_ACTION_ALIASES = setOf("search", "query", "lookup", "搜索", "查询")
private val TAG_SEARCH_FINISH_ACTIONS = setOf("finish", "done", "stop", "结束", "完成")
