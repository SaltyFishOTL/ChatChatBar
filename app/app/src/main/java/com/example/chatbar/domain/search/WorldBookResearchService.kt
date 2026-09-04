package com.example.chatbar.domain.search

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.domain.card.extractJsonObjectCandidates
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val WORLD_BOOK_RESEARCH_MAX_ITEMS = 10
private const val WORLD_BOOK_RESEARCH_EXCERPT_CHARS = 4_000
private const val WORLD_BOOK_RESEARCH_MAX_COMBINED_FACTS = 240
private const val WORLD_BOOK_RESEARCH_MAX_COMBINED_NOTES = 120

private data class WorldBookResearchSummaryBatch(
    val checkpointKey: String,
    val label: String,
    val sources: List<ResearchSource>,
    val reusableAcrossTargets: Boolean
)

private fun normalizedResearchQueryKey(query: String): String =
    query.trim().lowercase().replace(Regex("\\s+"), " ")

@Serializable
private data class WorldBookResearchPlanDraft(
    val queries: List<String> = emptyList()
)

data class WorldBookResearchSession(
    val preparedDocument: PreparedReferenceDocumentIndex? = null,
    val manualSources: List<ResearchSource> = emptyList(),
    val encyclopediaSourcesByQuery: Map<String, List<ResearchSource>> = emptyMap(),
    val summarizedBriefs: Map<String, ResearchBrief> = emptyMap()
)

data class WorldBookResearchResult(
    val brief: ResearchBrief? = null,
    val debug: ResearchDebugSnapshot = ResearchDebugSnapshot(),
    val session: WorldBookResearchSession = WorldBookResearchSession()
)

@OptIn(ExperimentalSerializationApi::class)
class WorldBookResearchService(
    private val settingsProvider: suspend () -> AppSettings,
    private val chatService: StreamingChatService,
    private val backend: SearchBackend,
    private val referenceRetriever: RagCharacterReferenceDocumentRetriever,
    private val manualRetriever: ManualWebPageRetriever,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
        allowTrailingComma = true
    }
) {
    suspend fun research(
        request: String,
        book: WorldBook,
        targets: List<WorldBookEntry>,
        model: ModelConfig,
        options: CharacterResearchOptions,
        referenceDocument: CharacterReferenceDocument?,
        session: WorldBookResearchSession? = null,
        resumeFrom: ResearchDebugSnapshot? = null,
        onSession: (WorldBookResearchSession) -> Unit = {},
        onDebug: (ResearchDebugSnapshot) -> Unit = {},
        onVisibleOutput: (String, String, String) -> Unit = { _, _, _ -> },
        onStatus: (String) -> Unit = {}
    ): WorldBookResearchResult = withContext(Dispatchers.IO) {
        resumeFrom?.brief?.takeIf(ResearchBrief::hasContent)?.let { brief ->
            onStatus("沿用已整理的世界书资料")
            return@withContext WorldBookResearchResult(
                brief = brief,
                debug = resumeFrom,
                session = session ?: WorldBookResearchSession()
            )
        }

        val normalizedUrls = if (options.mode.usesManualUrls()) {
            validateManualResearchUrls(options.urls).also {
                require(it.isValid) { it.errors.joinToString("；") }
                require(it.urls.isNotEmpty()) { "当前资料模式至少需要一个有效网址" }
            }.urls
        } else {
            emptyList()
        }
        var activeSession = session ?: prepareSession(
            referenceDocument = referenceDocument,
            manualUrls = normalizedUrls,
            onStatus = onStatus
        ).also(onSession)

        if (!options.mode.usesEncyclopediaSearch() &&
            activeSession.manualSources.isEmpty() &&
            activeSession.preparedDocument == null
        ) {
            onStatus("未启用外部资料，直接生成")
            return@withContext WorldBookResearchResult(session = activeSession)
        }

        val queryContext = buildWorldBookQueryContext(request, book, targets)
        val plan = resumeFrom?.plan ?: if (options.mode.usesEncyclopediaSearch()) {
            val maxQueries = if (targets.isEmpty()) {
                WORLD_BOOK_RESEARCH_MAX_ITEMS
            } else {
                targets.size.coerceIn(1, WORLD_BOOK_RESEARCH_MAX_ITEMS)
            }
            try {
                planQueries(queryContext, model, maxQueries, onVisibleOutput, onStatus)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onStatus("检索规划失败，改用世界书与条目名称：${error.message.orEmpty()}")
                null
            } ?: fallbackPlan(request, book, targets)
        } else {
            CharacterResearchPlan(
                needSearch = false,
                queries = emptyList()
            )
        }
        var snapshot = ResearchDebugSnapshot(plan = plan)
        onDebug(snapshot)

        val sources = resumeFrom?.sources?.takeIf(List<ResearchSource>::isNotEmpty) ?: run {
            val documentSources = activeSession.preparedDocument?.let { prepared ->
                val hits = referenceRetriever.searchPrepared(
                    prepared = prepared,
                    query = queryContext,
                    topK = CHARACTER_REFERENCE_DOCUMENT_TOP_K,
                    statusText = "正在匹配世界书目标与参考文档",
                    onStatus = onStatus
                )
                ResearchCleaner.toResearchSources(
                    hits = hits,
                    extracts = emptyList(),
                    maxSources = CHARACTER_REFERENCE_DOCUMENT_TOP_K,
                    maxExcerptChars = WORLD_BOOK_RESEARCH_EXCERPT_CHARS
                )
            }.orEmpty()
            val webSources = if (options.mode.usesEncyclopediaSearch() && plan.needSearch) {
                val plannedQueries = plan.queries.take(WORLD_BOOK_RESEARCH_MAX_ITEMS)
                plannedQueries.flatMapIndexed { index, query ->
                    val cacheKey = normalizedResearchQueryKey(query.query)
                    if (activeSession.encyclopediaSourcesByQuery.containsKey(cacheKey)) {
                        onStatus(
                            "复用已搜索词条 ${index + 1}/${plannedQueries.size}：${query.query.take(100)}"
                        )
                        activeSession.encyclopediaSourcesByQuery.getValue(cacheKey)
                    } else {
                        onStatus("正在处理百科词条 ${index + 1}/${plannedQueries.size}：${query.query.take(100)}")
                        val fetched = searchWeb(listOf(query), onStatus)
                        activeSession = activeSession.copy(
                            encyclopediaSourcesByQuery = activeSession.encyclopediaSourcesByQuery +
                                (cacheKey to fetched)
                        )
                        onSession(activeSession)
                        fetched
                    }
                }
            } else {
                emptyList()
            }
            (documentSources + activeSession.manualSources + webSources)
                .distinctBy { ResearchCleaner.canonicalUrl(it.url) }
                .mapIndexed { index, source -> source.copy(sourceId = "S${index + 1}") }
        }

        snapshot = ResearchDebugSnapshot(plan = plan, sources = sources)
        onDebug(snapshot)
        if (sources.isEmpty()) {
            if (referenceDocument != null) error("参考文档检索和数据清理后没有可用内容")
            if (normalizedUrls.isNotEmpty()) {
                error("指定网页全部读取失败，或清理后没有可用内容")
            }
            onStatus("外部资料为空，继续直接生成")
            return@withContext WorldBookResearchResult(debug = snapshot, session = activeSession)
        }

        val summaryBatches = buildSummaryBatches(sources, queryContext)
        val briefs = mutableListOf<ResearchBrief>()
        val failures = mutableListOf<String>()
        val rawPreviews = mutableListOf<String>()
        summaryBatches.forEachIndexed { index, batch ->
            activeSession.summarizedBriefs[batch.checkpointKey]?.let { completed ->
                val reuseLabel = if (batch.reusableAcrossTargets) "复用已清洗词条" else "沿用已整理资料"
                onStatus("$reuseLabel ${index + 1}/${summaryBatches.size}：${batch.label.take(80)}")
                briefs += completed
                return@forEachIndexed
            }
            val briefResult = summarizeBatch(
                request = request,
                targets = targets,
                batch = batch,
                batchIndex = index,
                batchCount = summaryBatches.size,
                model = model,
                onVisibleOutput = onVisibleOutput,
                onStatus = onStatus
            )
            val brief = briefResult.brief ?: fallbackBrief(batch)
            briefResult.failureReason.takeIf(String::isNotBlank)?.let { reason ->
                failures += "${batch.label}：$reason"
            }
            briefResult.rawResponsePreview.takeIf(String::isNotBlank)?.let { preview ->
                rawPreviews += "【${batch.label}】\n$preview"
            }
            if (brief != null) {
                briefs += brief
                activeSession = activeSession.copy(
                    summarizedBriefs = activeSession.summarizedBriefs + (batch.checkpointKey to brief)
                )
                onSession(activeSession)
            }
        }
        val brief = combineBriefs(plan, briefs)
        snapshot = ResearchDebugSnapshot(
            plan = plan,
            sources = if (brief == null) sources else emptyList(),
            brief = brief,
            briefFailureReason = failures.joinToString("；"),
            briefRawResponsePreview = rawPreviews.joinToString("\n\n").take(8_000)
        )
        onDebug(snapshot)
        WorldBookResearchResult(brief = brief, debug = snapshot, session = activeSession)
    }

    private suspend fun prepareSession(
        referenceDocument: CharacterReferenceDocument?,
        manualUrls: List<String>,
        onStatus: (String) -> Unit
    ): WorldBookResearchSession {
        val preparedDocument = referenceDocument?.let {
            referenceRetriever.prepare(listOf(it), onStatus)
        }
        val manualSources = if (manualUrls.isEmpty()) {
            emptyList()
        } else {
            val result = manualRetriever.retrieve(manualUrls, onStatus)
            result.failures.forEach { failure ->
                onStatus("指定网页失败：${failure.url.take(80)}；${failure.reason.take(120)}")
            }
            ResearchCleaner.toResearchSources(
                hits = result.hits,
                extracts = emptyList(),
                maxSources = MAX_MANUAL_RESEARCH_URLS,
                maxExcerptChars = MAX_MANUAL_WEB_PAGE_EXCERPT_CHARS
            )
        }
        return WorldBookResearchSession(preparedDocument, manualSources)
    }

    private suspend fun planQueries(
        context: String,
        model: ModelConfig,
        maxQueries: Int,
        onVisibleOutput: (String, String, String) -> Unit,
        onStatus: (String) -> Unit
    ): CharacterResearchPlan? {
        val visible = StringBuilder()
        onStatus("AI 正在规划世界书资料搜索")
        val raw = chatService.completeTextStreaming(
            messages = listOf(
                ChatApiMessage.text(
                    "system",
                    PromptTemplates.worldBookResearchPlannerSystemPrompt(maxQueries)
                ),
                ChatApiMessage.text(
                    "user",
                    PromptTemplates.worldBookResearchPlannerUserPrompt(context)
                )
            ),
            modelConfig = model,
            maxTokens = 500,
            enableThinking = false,
            reasoningEffort = "low",
            isolatedTaskParameters = true,
            onDelta = { chunk ->
                visible.append(chunk)
                onVisibleOutput("research-plan", "世界书搜索规划输出", visible.toString())
            },
            onReasoningDelta = { onStatus("AI 正在规划世界书资料搜索（思考中）") }
        )
        return raw.extractJsonObjectCandidates().firstNotNullOfOrNull { candidate ->
            runCatching { json.decodeFromString(WorldBookResearchPlanDraft.serializer(), candidate) }
                .getOrNull()
        }?.let { draft ->
            val queries = draft.queries
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinctBy(::normalizedResearchQueryKey)
                .take(maxQueries)
                .mapIndexed { index, query -> CharacterResearchQuery(query, index + 1) }
            CharacterResearchPlan(needSearch = queries.isNotEmpty(), queries = queries)
        }
    }

    private fun fallbackPlan(
        request: String,
        book: WorldBook,
        targets: List<WorldBookEntry>
    ): CharacterResearchPlan {
        val queries = (targets.map { it.name } + book.name + request.take(200))
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase() }
            .take(WORLD_BOOK_RESEARCH_MAX_ITEMS)
            .mapIndexed { index, query -> CharacterResearchQuery(query, index + 1) }
        return CharacterResearchPlan(
            needSearch = queries.isNotEmpty(),
            queries = queries
        )
    }

    private suspend fun searchWeb(
        queries: List<CharacterResearchQuery>,
        onStatus: (String) -> Unit
    ): List<ResearchSource> {
        val maxResults = settingsProvider().webSearchMaxResultsPerQuery.coerceIn(1, 1)
        val hitsOrNull = withTimeoutOrNull(35_000L) {
            queries.flatMapIndexed { index, query ->
                onStatus("正在搜索百科 ${index + 1}/${queries.size}：${query.query.take(100)}")
                try {
                    backend.search(SearchBackendQuery(query.query, maxResults))
                        .map { it.copy(query = query.query) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    onStatus("百科搜索失败：${error.message ?: error::class.java.simpleName}")
                    emptyList()
                }
            }
        }
        if (hitsOrNull == null) onStatus("百科搜索超时，使用已取得的其他资料")
        val hits = hitsOrNull.orEmpty()
        val firstPass = ResearchCleaner.toResearchSources(hits, emptyList(), WORLD_BOOK_RESEARCH_MAX_ITEMS, 1_200)
        val extracts = if (firstPass.isEmpty()) {
            emptyList()
        } else {
            try {
                backend.extract(firstPass.map { it.url }, WORLD_BOOK_RESEARCH_MAX_ITEMS)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onStatus("百科正文抽取失败，使用搜索摘要：${error.message ?: error::class.java.simpleName}")
                emptyList()
            }
        }
        return ResearchCleaner.toResearchSources(
            hits,
            extracts,
            WORLD_BOOK_RESEARCH_MAX_ITEMS,
            WORLD_BOOK_RESEARCH_EXCERPT_CHARS
        )
    }

    private fun buildSummaryBatches(
        sources: List<ResearchSource>,
        scopeKey: String
    ): List<WorldBookResearchSummaryBatch> =
        sources
            .groupBy { source ->
                when {
                    source.sourceType == "reference-document" -> "上传参考文档"
                    source.query.isNotBlank() -> source.query.trim()
                    else -> source.title.trim().ifBlank { "未标注资料" }
                }
            }
            .map { (label, groupedSources) ->
                val reusableAcrossTargets = groupedSources.all { source ->
                    source.sourceType != "reference-document" &&
                        normalizedResearchQueryKey(source.query) != normalizedResearchQueryKey("用户指定网址") &&
                        source.query.isNotBlank()
                }
                WorldBookResearchSummaryBatch(
                    checkpointKey = if (reusableAcrossTargets) {
                        "encyclopedia:${normalizedResearchQueryKey(label)}"
                    } else {
                        buildString {
                            append(scopeKey)
                            append("||")
                            append(label.lowercase())
                            groupedSources.forEach { source ->
                                append('|')
                                append(source.sourceId)
                                append(':')
                                append(source.url)
                            }
                        }
                    },
                    label = label.take(240),
                    sources = groupedSources,
                    reusableAcrossTargets = reusableAcrossTargets
                )
            }

    private fun fallbackBrief(batch: WorldBookResearchSummaryBatch): ResearchBrief? {
        if (batch.sources.isEmpty()) return null
        return ResearchBrief(
            queries = listOf(batch.label),
            facts = batch.sources.map { source ->
                source.excerpt.take(800)
            },
            sources = batch.sources
        )
    }

    private fun combineBriefs(
        plan: CharacterResearchPlan,
        briefs: List<ResearchBrief>
    ): ResearchBrief? {
        if (briefs.none(ResearchBrief::hasContent)) return null
        val queries = (plan.queries.map(CharacterResearchQuery::query) + briefs.flatMap(ResearchBrief::queries))
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
        val facts = briefs.flatMap(ResearchBrief::facts)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase().replace(Regex("\\s+"), " ") }
            .take(WORLD_BOOK_RESEARCH_MAX_COMBINED_FACTS)
        val notes = briefs.flatMap(ResearchBrief::notes)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase().replace(Regex("\\s+"), " ") }
            .take(WORLD_BOOK_RESEARCH_MAX_COMBINED_NOTES)
        return ResearchBrief(
            queries = queries,
            facts = facts,
            notes = notes
        )
    }

    private suspend fun summarizeBatch(
        request: String,
        targets: List<WorldBookEntry>,
        batch: WorldBookResearchSummaryBatch,
        batchIndex: Int,
        batchCount: Int,
        model: ModelConfig,
        onVisibleOutput: (String, String, String) -> Unit,
        onStatus: (String) -> Unit
    ): ResearchBriefResult {
        var raw = ""
        return try {
            val visible = StringBuilder()
            val targetText = when {
                batch.reusableAcrossTargets ->
                    "围绕本次检索词完整整理可用于世界书的事实。"
                targets.isEmpty() ->
                    "条目创建阶段：根据整体需求规划世界书覆盖范围"
                else -> targets.joinToString("\n") { target ->
                    val keys = target.keys.joinToString("、").ifBlank { "（无主触发词）" }
                    "- ${target.name.ifBlank { "（未命名条目）" }}｜主触发词：$keys"
                }
            }
            val sourceText = batch.sources.joinToString("\n\n") { source ->
                PromptTemplates.worldBookResearchBriefSource(
                    title = source.title,
                    sourceType = source.sourceType,
                    excerpt = source.excerpt
                )
            }
            onStatus("AI 正在整理世界书资料 ${batchIndex + 1}/$batchCount：${batch.label.take(80)}")
            raw = chatService.completeTextStreaming(
                messages = listOf(
                    ChatApiMessage.text("system", PromptTemplates.worldBookResearchBriefSystemPrompt()),
                    ChatApiMessage.text(
                        "user",
                        PromptTemplates.worldBookResearchBriefUserPrompt(
                            request = request,
                            targets = targetText,
                            query = batch.label,
                            sources = sourceText
                        )
                    )
                ),
                modelConfig = model,
                maxTokens = 4_000,
                enableThinking = false,
                reasoningEffort = "low",
                isolatedTaskParameters = true,
                onDelta = { chunk ->
                    visible.append(chunk)
                    onVisibleOutput(
                        "research-brief-${batchIndex + 1}",
                        "资料整理 ${batchIndex + 1}/$batchCount：${batch.label.take(40)}",
                        visible.toString()
                    )
                },
                onReasoningDelta = {
                    onStatus("AI 正在整理世界书资料 ${batchIndex + 1}/$batchCount（思考中）")
                }
            )
            val draft = raw.extractJsonObjectCandidates().firstNotNullOfOrNull { candidate ->
                runCatching { json.decodeFromString(ResearchBriefDraft.serializer(), candidate) }.getOrNull()
            }
            if (draft == null) {
                val sanitized = ResearchCleaner.sanitizeText(raw)
                ResearchBriefResult(
                    brief = sanitized.takeIf(String::isNotBlank)?.let {
                        ResearchBrief(
                            queries = listOf(batch.label),
                            facts = listOf(it)
                        )
                    },
                    failureReason = "资料整理 JSON 解析失败",
                    rawResponsePreview = raw.take(1_200)
                )
            } else {
                ResearchBriefResult(
                    brief = ResearchBrief(
                        queries = listOf(batch.label),
                        facts = draft.facts.map(ResearchCleaner::sanitizeText).filter(String::isNotBlank).take(30),
                        notes = draft.notes.map(ResearchCleaner::sanitizeText).filter(String::isNotBlank).take(20)
                    ),
                    rawResponsePreview = raw.take(1_200)
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ResearchBriefResult(
                failureReason = error.message ?: error::class.java.simpleName,
                rawResponsePreview = raw.take(1_200)
            )
        }
    }
}

internal fun buildWorldBookQueryContext(
    request: String,
    book: WorldBook,
    targets: List<WorldBookEntry>
): String = buildString {
    request.trim().takeIf(String::isNotBlank)?.let { appendLine("用户要求：$it") }
    appendLine("世界书：${book.name.ifBlank { "（未命名）" }}")
    book.description.trim().takeIf(String::isNotBlank)?.let { appendLine("描述：${it.take(1_000)}") }
    if (targets.isNotEmpty()) {
        appendLine("目标条目：")
        targets.forEach { entry ->
            appendLine("- ${entry.name.ifBlank { "未命名" }}｜${entry.keys.joinToString("、")}")
        }
    }
}.trim().take(12_000)
