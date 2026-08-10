package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.card.extractJsonObjectCandidates
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.NovelAiTagSearchEvidence
import com.example.chatbar.domain.prompt.NovelAiCodexEvidence
import com.example.chatbar.domain.prompt.PromptTemplates
import java.io.IOException
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@Serializable
data class NovelAiTagSearchDecision(
    val action: String = "",
    val queries: List<String> = emptyList(),
    val sceneDescription: String = ""
)

data class NovelAiTagSearchDecisionResult(
    val decision: NovelAiTagSearchDecision? = null,
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
        onRawText: (String) -> Unit = {}
    ): NovelAiTagSearchDecisionResult
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
        onRawText: (String) -> Unit
    ): NovelAiTagSearchDecisionResult {
        val requestText = PromptTemplates.novelAiTagSearchPlannerUser(
            taskInput = taskInput,
            characterPrompts = characterPrompts
        )
        val streamingProgress = NovelAiTagPlannerStreamingProgress(onRawText)
        return try {
            val raw = chatService.completeTextStreaming(
                messages = requestMessages(requestText, imageBase64s),
                modelConfig = model,
                thinkingBudget = NOVEL_AI_SCENE_PLANNING_THINKING_BUDGET,
                onDelta = streamingProgress::appendContent,
                onReasoningDelta = streamingProgress::appendReasoning
            )
            val decision = parseDecision(raw, characterPrompts)
            if (decision == null) {
                NovelAiTagSearchDecisionResult(
                    requestText = requestText,
                    reasoningResponse = streamingProgress.reasoningText,
                    rawResponse = raw,
                    failureReason = "画面草案与检索规划 JSON 无法解析"
                )
            } else {
                NovelAiTagSearchDecisionResult(
                    decision = decision,
                    requestText = requestText,
                    reasoningResponse = streamingProgress.reasoningText,
                    rawResponse = raw
                )
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            NovelAiTagSearchDecisionResult(
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

    internal fun requestMessages(
        requestText: String,
        imageBase64s: List<String>
    ): List<ChatApiMessage> {
        val sourceImages = imageBase64s.filter(String::isNotBlank)
        val userMessage = if (sourceImages.isEmpty()) {
            ChatApiMessage.text("user", requestText)
        } else {
            ChatApiMessage.withImages("user", requestText, sourceImages)
        }
        return listOf(
            ChatApiMessage.text("system", PromptTemplates.novelAiTagSearchPlannerSystem()),
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

class TagSuggestClient internal constructor(
    private val client: OkHttpClient = buildTagSuggestClient(),
    private val baseUrl: HttpUrl = TAG_SUGGEST_BASE_URL.toHttpUrl(),
    private val clockMillis: () -> Long = System::currentTimeMillis
) : NovelAiTagSearchClient {
    private data class CacheEntry(
        val storedAt: Long,
        val candidates: List<NovelAiTagCandidate>
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = LinkedHashMap<String, CacheEntry>(16, 0.75f, true)

    override suspend fun search(query: String): NovelAiTagSearchOutcome {
        val normalized = query.normalizeTagSuggestQuery()
        require(normalized.length in MIN_TAG_QUERY_LENGTH..MAX_TAG_QUERY_LENGTH) {
            "TagSuggest 查询长度必须在 $MIN_TAG_QUERY_LENGTH..$MAX_TAG_QUERY_LENGTH 之间"
        }
        val cacheKey = normalized.lowercase(Locale.ROOT)
        cached(cacheKey)?.let {
            return NovelAiTagSearchOutcome(
                effectiveQuery = normalized,
                candidates = it,
                fromCache = true
            )
        }

        val url = baseUrl.newBuilder()
            .addPathSegments("api/tags/suggest")
            .addQueryParameter("q", normalized)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", TAG_SUGGEST_USER_AGENT)
            .get()
            .build()
        val responseText = awaitBody(request)
        val candidates = parseResponse(responseText).take(MAX_TAG_CANDIDATES_PER_QUERY)
        cache(cacheKey, candidates)
        return NovelAiTagSearchOutcome(
            effectiveQuery = normalized,
            candidates = candidates
        )
    }

    internal fun parseResponse(raw: String): List<NovelAiTagCandidate> {
        require(raw.length <= MAX_TAG_SUGGEST_RESPONSE_CHARS) { "TagSuggest 响应过大" }
        val root = json.parseToJsonElement(raw).jsonObject
        val results = root["results"]?.jsonArray ?: error("TagSuggest 响应缺少 results")
        return results.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                val categoryCode = obj["category"]?.jsonPrimitive?.longOrNull?.toInt()
                    ?: return@runCatching null
                val category = NovelAiTagCategory.fromCode(categoryCode)
                    ?: return@runCatching null
                if (!name.isValidDanbooruTagName()) return@runCatching null
                NovelAiTagCandidate(
                    name = name,
                    translatedName = obj["cn_name"]?.jsonPrimitive?.contentOrNull
                        .orEmpty()
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .take(MAX_TRANSLATED_TAG_CHARS),
                    count = obj["count"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(0L) ?: 0L,
                    category = category
                )
            }.getOrNull()
        }.distinctBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun cached(key: String): List<NovelAiTagCandidate>? = synchronized(cache) {
        val entry = cache[key] ?: return@synchronized null
        if (clockMillis() - entry.storedAt > TAG_SUGGEST_CACHE_TTL_MS) {
            cache.remove(key)
            null
        } else {
            entry.candidates
        }
    }

    private fun cache(key: String, candidates: List<NovelAiTagCandidate>) = synchronized(cache) {
        cache[key] = CacheEntry(clockMillis(), candidates)
        while (cache.size > TAG_SUGGEST_CACHE_MAX_ENTRIES) {
            val eldest = cache.entries.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
    }

    private suspend fun awaitBody(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = runCatching { response.body?.string().orEmpty() }
                        .getOrElse { error ->
                            if (continuation.isActive) continuation.resumeWithException(error)
                            return
                        }
                    if (!response.isSuccessful) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IOException("TagSuggest HTTP ${response.code}: ${body.take(240)}")
                            )
                        }
                        return
                    }
                    if (continuation.isActive) continuation.resume(body)
                }
            }
        })
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
        onProgress = onProgress
    )

    suspend fun research(
        taskInput: String,
        characterPrompts: List<Pair<String, String>>,
        imageBase64s: List<String>,
        model: ModelConfig,
        diversityKey: String = "",
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
            onRawText = { streamed -> transcript.update(planningTitle, streamed) }
        )
        val decision = decisionResult.decision
        if (decision == null) {
            val fallbackSceneDescription = taskInput.normalizeSceneDescription()
            transcript.complete(
                planningTitle,
                listOf(
                    decisionResult.displayResponse(),
                    "规划失败：${decisionResult.failureReason.ifBlank { "未知错误" }}；跳过 TagSuggest，继续本地法典召回"
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
                    "画面草案完成；无需 TagSuggest，使用草案召回本地经验模板"
                } else {
                    "画面草案完成；同时使用草案与 ${decision.queries.size} 个检索词召回本地经验模板，并用检索词查询 TagSuggest"
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

        val batchTitle = "TagSuggest 批量搜索"
        val statusLock = Any()
        val statuses = decision.queries.map { query ->
            "- $query → q=${query.normalizeTagSuggestQuery()}｜等待请求"
        }.toMutableList()
        transcript.update(batchTitle, statuses.joinToString("\n"))
        val rawResults = coroutineScope {
            decision.queries.mapIndexed { index, query ->
                async {
                    val effectiveQuery = query.normalizeTagSuggestQuery()
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

internal fun String.normalizeTagSuggestQuery(): String {
    val collapsed = normalizePlannerQuery()
    val containsCjk = collapsed.any { char ->
        char.code in 0x3400..0x9FFF || char.code in 0xF900..0xFAFF
    }
    return if (containsCjk) {
        collapsed.replace(" ", "")
    } else {
        collapsed.replace(" ", "_")
    }
}

private fun String.isValidDanbooruTagName(): Boolean =
    length in 1..MAX_TAG_NAME_CHARS && none { char ->
        char.isWhitespace() || char == ',' || char.code !in 0x21..0x7E
    }

private fun buildTagSuggestClient(): OkHttpClient {
    val dispatcher = Dispatcher().apply {
        maxRequests = MAX_TAG_SEARCH_QUERIES
        maxRequestsPerHost = MAX_TAG_SEARCH_QUERIES
    }
    return ProxyAwareClient.builder()
        .dispatcher(dispatcher)
        .connectTimeout(TAG_SEARCH_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TAG_SEARCH_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TAG_SEARCH_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()
}

internal const val MAX_TAG_SEARCH_QUERIES = 6
internal const val MAX_TAG_CANDIDATES_PER_QUERY = 8
internal const val MAX_TOTAL_TAG_CANDIDATES = 24
private const val MIN_TAG_QUERY_LENGTH = 2
private const val MAX_TAG_QUERY_LENGTH = 80
private const val MAX_TAG_NAME_CHARS = 200
private const val MAX_TRANSLATED_TAG_CHARS = 200
private const val MAX_TAG_SUGGEST_RESPONSE_CHARS = 200_000
private const val TAG_SEARCH_REQUEST_TIMEOUT_MS = 8_000L
private const val TAG_SEARCH_BATCH_TIMEOUT_MS = 20_000L
private const val TAG_SUGGEST_CACHE_TTL_MS = 30 * 60 * 1000L
private const val TAG_SUGGEST_CACHE_MAX_ENTRIES = 128
private const val TAG_SUGGEST_BASE_URL = "https://tagsuggest.zeabur.app/"
private const val TAG_SUGGEST_USER_AGENT = "ChatBar/1.0 (Android; NovelAI tag research)"
private const val TAG_SEARCH_ACTION_SEARCH = "search"
private const val TAG_SEARCH_ACTION_FINISH = "finish"
private val TAG_SEARCH_ACTION_ALIASES = setOf("search", "query", "lookup", "搜索", "查询")
private val TAG_SEARCH_FINISH_ACTIONS = setOf("finish", "done", "stop", "结束", "完成")
