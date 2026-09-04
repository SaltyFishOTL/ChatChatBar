package com.example.chatbar.domain.search

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val MAX_RESEARCH_QUERIES = 30
private const val RESEARCH_QUERY_BATCH_SIZE = 10
private const val MAX_EFFECTIVE_RESULTS_PER_QUERY = 1
private const val FIRST_PASS_EXCERPT_CHARS = 1200
private const val FINAL_EXCERPT_CHARS = 4000

private data class ResearchCleaningBatch(
    val queries: List<CharacterResearchQuery>,
    val sources: List<ResearchSource>
)

class CharacterResearchService(
    private val settingsProvider: suspend () -> AppSettings,
    private val planner: CharacterResearchPlanProvider,
    private val backend: SearchBackend,
    private val summarizer: ResearchBriefSummarizer,
    private val referenceDocumentRetriever: CharacterReferenceDocumentRetriever? = null,
    private val manualWebPageRetriever: ManualWebPageRetriever? = null
) {
    suspend fun research(
        userInput: String,
        currentCard: CharacterCard,
        modelConfig: ModelConfig,
        researchOptions: CharacterResearchOptions = CharacterResearchOptions(),
        referenceDocuments: List<CharacterReferenceDocument> = emptyList(),
        onDebug: (ResearchDebugSnapshot) -> Unit = {},
        resumeFrom: ResearchDebugSnapshot? = null,
        onCheckpoint: (ResearchDebugSnapshot) -> Unit = {},
        onVisibleOutput: (String, String, String) -> Unit = { _, _, _ -> },
        onStatus: (String) -> Unit = {}
    ): ResearchBrief? = withContext(Dispatchers.IO) {
        runCatching {
            android.util.Log.d(
                "CharacterEditResume",
                "research entry: mode=${researchOptions.mode.name} " +
                    "resumeFrom=${resumeFrom != null} plan=${resumeFrom?.plan != null} " +
                    "sources=${resumeFrom?.sources?.size} brief=${resumeFrom?.brief?.hasContent()}"
            )
        }
        val encyclopediaEnabled = researchOptions.mode.usesEncyclopediaSearch()
        val manualUrls = if (researchOptions.mode.usesManualUrls()) {
            val validation = validateManualResearchUrls(researchOptions.urls)
            require(validation.isValid) { validation.errors.joinToString("；") }
            require(validation.urls.isNotEmpty()) { "当前资料模式至少需要一个有效网址" }
            validation.urls
        } else {
            emptyList()
        }
        val manualUrlsEnabled = manualUrls.isNotEmpty()
        if (!encyclopediaEnabled && !manualUrlsEnabled && referenceDocuments.isEmpty()) {
            onStatus("未启用外部资料，直接开始生成")
            return@withContext null
        }
        val maxResearchQueries = MAX_RESEARCH_QUERIES
        val maxResults = if (encyclopediaEnabled) {
            settingsProvider().webSearchMaxResultsPerQuery.coerceIn(1, MAX_EFFECTIVE_RESULTS_PER_QUERY)
        } else {
            1
        }

        fun publish(snapshot: ResearchDebugSnapshot) {
            onDebug(snapshot)
            onCheckpoint(snapshot)
        }
        val planResult = when {
            resumeFrom?.plan != null -> CharacterResearchPlanResult(plan = resumeFrom.plan)
            encyclopediaEnabled -> planner.plan(
                userInput = userInput,
                currentCard = currentCard,
                modelConfig = modelConfig,
                maxQueries = maxResearchQueries,
                onStatus = onStatus,
                onRawText = { text -> onVisibleOutput("research-plan", "搜索规划输出", text) }
            )
            manualUrlsEnabled -> CharacterResearchPlanResult(plan = manualUrlPlan(manualUrls))
            referenceDocuments.isNotEmpty() -> CharacterResearchPlanResult(plan = referenceDocumentPlan())
            else -> CharacterResearchPlanResult()
        }
        val resolvedPlan = planResult.plan ?: when {
            encyclopediaEnabled ->
                fallbackPlan(userInput, currentCard, maxResearchQueries, planResult.failureReason)
                    ?: manualUrlPlan(manualUrls).copy(needSearch = false)
                        .takeIf { manualUrlsEnabled }
                    ?: referenceDocumentPlan().takeIf { referenceDocuments.isNotEmpty() }
            referenceDocuments.isNotEmpty() -> referenceDocumentPlan()
            else -> null
        }
        val plan = resolvedPlan?.let { resolved ->
            if (encyclopediaEnabled) {
                val cappedQueries = resolved.queries.take(maxResearchQueries)
                resolved.copy(
                    needSearch = resolved.needSearch && cappedQueries.isNotEmpty(),
                    queries = cappedQueries
                )
            } else {
                resolved
            }
        }
        if (plan == null) {
            onStatus("资料规划失败，且没有可用来源，直接开始生成")
            return@withContext null
        }
        if (encyclopediaEnabled && planResult.plan == null) {
            if (plan.needSearch && plan.queries.isNotEmpty()) {
                onStatus(
                    "搜索规划失败，改用保底关键词继续搜索：" +
                        plan.queries.joinToString("、") { it.query }.statusSnippet(120)
                )
            } else {
                onStatus("搜索规划失败，继续读取指定网页")
            }
        }
        publish(ResearchDebugSnapshot(plan = plan))
        val resumedBrief = resumeFrom?.brief?.takeIf(ResearchBrief::hasContent)
        if (resumedBrief != null) {
            onStatus("沿用已整理的外部资料，开始生成")
            publish(
                ResearchDebugSnapshot(
                    plan = plan,
                    sources = resumedBrief.sources,
                    brief = resumedBrief
                )
            )
            return@withContext resumedBrief
        }
        if (
            (!plan.needSearch || plan.queries.isEmpty()) &&
            referenceDocuments.isEmpty() &&
            !manualUrlsEnabled
        ) {
            return@withContext null
        }

        val queries = if (encyclopediaEnabled && plan.needSearch) {
            plan.queries.take(maxResearchQueries)
        } else {
            emptyList()
        }
        if (queries.isNotEmpty()) {
            onStatus(
                buildString {
                    append("AI 决定搜索 ${queries.size} 个关键词")
                    plan.reason.takeIf(String::isNotBlank)?.let { append("：").append(it.statusSnippet(80)) }
                }
            )
            queries.forEachIndexed { index, query ->
                onStatus("关键词 ${index + 1}/${queries.size}：${query.query.statusSnippet(120)}")
            }
        }

        val resumedSources = resumeFrom?.sources.orEmpty()
        val sources = if (resumedSources.isNotEmpty()) {
            resumedSources
        } else {
            val documentHits = if (referenceDocuments.isNotEmpty()) {
                val retriever = referenceDocumentRetriever
                    ?: error("参考文档 RAG 检索服务不可用")
                onStatus("正在检索上传参考文档：Top $CHARACTER_REFERENCE_DOCUMENT_TOP_K")
                retriever.retrieve(
                    documents = referenceDocuments,
                    userInput = userInput,
                    currentCard = currentCard,
                    topK = CHARACTER_REFERENCE_DOCUMENT_TOP_K,
                    onStatus = onStatus
                ).also { hits ->
                    onStatus("参考文档检索完成：命中 ${hits.size} 张卡片")
                }
            } else {
                emptyList()
            }

            val documentSources = if (documentHits.isEmpty()) {
                emptyList()
            } else {
                onStatus("正在清洗参考文档卡片：${documentHits.size} 张")
                ResearchCleaner.toResearchSources(
                    hits = documentHits,
                    extracts = emptyList(),
                    maxSources = CHARACTER_REFERENCE_DOCUMENT_TOP_K,
                    maxExcerptChars = FINAL_EXCERPT_CHARS
                )
            }

            val manualPageSources = if (manualUrlsEnabled) {
                val retriever = manualWebPageRetriever
                    ?: error("指定网页读取服务不可用")
                val result = retriever.retrieve(manualUrls, onStatus)
                if (result.failures.isNotEmpty()) {
                    result.failures.forEachIndexed { index, failure ->
                        onStatus(
                            "指定网页失败 ${index + 1}/${result.failures.size}：" +
                                "${failure.url.statusSnippet(80)}；${failure.reason.statusSnippet(120)}"
                        )
                    }
                    onStatus(
                        "指定网页读取完成：成功 ${result.hits.size} 个，失败 ${result.failures.size} 个"
                    )
                }
                val cleaned = ResearchCleaner.toResearchSources(
                    hits = result.hits,
                    extracts = emptyList(),
                    maxSources = MAX_MANUAL_RESEARCH_URLS,
                    maxExcerptChars = MAX_MANUAL_WEB_PAGE_EXCERPT_CHARS
                )
                if (cleaned.isEmpty()) {
                    if (!encyclopediaEnabled) {
                        error("指定网页全部读取失败，或数据清理后没有可用内容")
                    }
                    onStatus("指定网页没有可用内容，继续使用百科搜索结果")
                } else {
                    onStatus("指定网页清洗完成：${cleaned.size} 个来源")
                }
                cleaned
            } else {
                emptyList()
            }

            val queryBatches = queries.chunked(RESEARCH_QUERY_BATCH_SIZE)
            val webSources = queryBatches.flatMapIndexed { batchIndex, batchQueries ->
                val batchNumber = batchIndex + 1
                val batchCount = queryBatches.size
                val webHits = withTimeoutOrNull(35_000L) {
                    batchQueries.flatMapIndexed { index, query ->
                        val globalIndex = batchIndex * RESEARCH_QUERY_BATCH_SIZE + index + 1
                        onStatus(
                            "正在搜索百科 $globalIndex/${queries.size}（第 $batchNumber/$batchCount 轮）：" +
                                query.query.statusSnippet(120)
                        )
                        runCatching {
                            val queryHits = backend.search(
                                SearchBackendQuery(
                                    query = query.query,
                                    maxResults = maxResults
                                )
                            ).map { it.copy(query = query.query) }
                            onStatus("百科搜索完成 $globalIndex/${queries.size}：命中 ${queryHits.size} 条")
                            queryHits
                        }.getOrElse { error ->
                            onStatus(
                                "百科搜索失败 $globalIndex/${queries.size}：" +
                                    (error.message ?: error::class.java.simpleName)
                            )
                            emptyList()
                        }
                    }
                }
                if (webHits == null) {
                    onStatus("百科搜索第 $batchNumber/$batchCount 轮超时，继续处理已完成轮次")
                    emptyList()
                } else {
                    val firstPassWebSources = ResearchCleaner.toResearchSources(
                        hits = webHits,
                        extracts = emptyList(),
                        maxSources = batchQueries.size,
                        maxExcerptChars = FIRST_PASS_EXCERPT_CHARS
                    )
                    val extracts = if (firstPassWebSources.isEmpty()) {
                        emptyList()
                    } else {
                        onStatus(
                            "正在抽取百科正文（第 $batchNumber/$batchCount 轮）：" +
                                "${firstPassWebSources.size} 个来源"
                        )
                        runCatching {
                            backend.extract(
                                urls = firstPassWebSources.map { it.url },
                                maxPages = batchQueries.size
                            )
                        }.getOrElse { error ->
                            onStatus(
                                "百科正文抽取失败，改用搜索摘要：" +
                                    (error.message ?: error::class.java.simpleName)
                            )
                            emptyList()
                        }
                    }
                    ResearchCleaner.toResearchSources(
                        hits = webHits,
                        extracts = extracts,
                        maxSources = batchQueries.size,
                        maxExcerptChars = FINAL_EXCERPT_CHARS
                    )
                }
            }
            (documentSources + manualPageSources + webSources)
                .distinctBy { ResearchCleaner.canonicalUrl(it.url) }
                .mapIndexed { index, source -> source.copy(sourceId = "S${index + 1}") }
        }
        if (sources.isEmpty()) {
            if (referenceDocuments.isNotEmpty()) {
                error("参考文档检索和数据清理后没有可用内容")
            }
            if (manualUrlsEnabled) {
                error("指定网页与百科搜索均没有可用内容")
            }
            onStatus("百科结果清洗后为空，继续直接生成")
            return@withContext null
        }
        publish(ResearchDebugSnapshot(plan = plan, sources = sources))

        val cleaningBatches = buildCleaningBatches(plan, queries, sources)
        val batchBriefs = mutableListOf<ResearchBrief>()
        val batchFailures = mutableListOf<String>()
        val batchRawPreviews = mutableListOf<String>()
        cleaningBatches.forEachIndexed { index, batch ->
            val batchNumber = index + 1
            val batchCount = cleaningBatches.size
            val batchLabel = if (batchCount == 1) "" else "（第 $batchNumber/$batchCount 轮）"
            onStatus("正在清洗并压缩外部资料$batchLabel：${batch.sources.size} 个来源")
            val summaryResult = runCatching {
                summarizer.summarize(
                    request = userInput,
                    currentCard = currentCard,
                    plan = plan.copy(queries = batch.queries),
                    sources = batch.sources,
                    modelConfig = modelConfig,
                    onStatus = onStatus,
                    onRawText = { text ->
                        onVisibleOutput(
                            if (batchCount == 1) "research-brief" else "research-brief-$batchNumber",
                            if (batchCount == 1) "资料整理输出" else "资料整理 $batchNumber/$batchCount",
                            text
                        )
                    }
                )
            }.getOrElse { error ->
                ResearchBriefResult(failureReason = error.message ?: error::class.java.simpleName)
            }
            val summarizedBrief = summaryResult.brief
                ?.takeIf { it.hasSummaryText() }
                ?.copy(sources = emptyList())
            val batchBrief = if (summarizedBrief != null) {
                if (summaryResult.failureReason.isNotBlank()) {
                    onStatus("AI 资料整理${batchLabel}未成功结构化，直接采用 AI 原文作为整理结果")
                }
                summarizedBrief
            } else {
                if (summaryResult.failureReason.isNotBlank()) {
                    onStatus(
                        "外部资料压缩${batchLabel}失败：" +
                            summaryResult.failureReason.statusSnippet(120)
                    )
                }
                onStatus("AI资料整理${batchLabel}不可用，使用清洗正文兜底摘要")
                ResearchCleaner.fallbackBrief(
                    plan.reason,
                    batch.queries.map { it.query } + if (referenceDocuments.isNotEmpty() && index == 0) {
                        listOf("上传参考文档 RAG")
                    } else {
                        emptyList()
                    },
                    batch.sources
                )
            }
            batchBrief?.let(batchBriefs::add)
            summaryResult.failureReason.takeIf(String::isNotBlank)?.let { failure ->
                batchFailures += if (batchCount == 1) failure else "第 $batchNumber/$batchCount 轮：$failure"
            }
            summaryResult.rawResponsePreview.takeIf {
                summaryResult.failureReason.isNotBlank() && it.isNotBlank()
            }?.let { preview ->
                batchRawPreviews += if (batchCount == 1) preview else "【第 $batchNumber/$batchCount 轮】\n$preview"
            }
        }
        val brief = combineBriefs(
            plan = plan,
            referenceDocumentsIncluded = referenceDocuments.isNotEmpty(),
            briefs = batchBriefs
        )
        if (brief?.hasContent() == true) {
            publish(
                ResearchDebugSnapshot(
                    plan = plan,
                    sources = brief.sources,
                    brief = brief,
                    briefFailureReason = batchFailures.joinToString("；"),
                    briefRawResponsePreview = batchRawPreviews.joinToString("\n\n")
                )
            )
            onStatus("外部资料已整理，开始生成")
        } else {
            onStatus("外部资料为空，继续直接生成")
        }
        brief
    }

    private fun buildCleaningBatches(
        plan: CharacterResearchPlan,
        activeQueries: List<CharacterResearchQuery>,
        sources: List<ResearchSource>
    ): List<ResearchCleaningBatch> {
        if (activeQueries.size <= RESEARCH_QUERY_BATCH_SIZE) {
            return listOf(ResearchCleaningBatch(queries = plan.queries, sources = sources))
        }
        val activeQueryKeys = activeQueries.map { normalizedQueryKey(it.query) }.toSet()
        val supplementalSources = sources.filter { normalizedQueryKey(it.query) !in activeQueryKeys }
        return activeQueries.chunked(RESEARCH_QUERY_BATCH_SIZE).mapIndexedNotNull { index, batchQueries ->
            val batchQueryKeys = batchQueries.map { normalizedQueryKey(it.query) }.toSet()
            val batchSources = buildList {
                if (index == 0) addAll(supplementalSources)
                addAll(sources.filter { normalizedQueryKey(it.query) in batchQueryKeys })
            }
            batchSources.takeIf { it.isNotEmpty() }?.let {
                ResearchCleaningBatch(queries = batchQueries, sources = it)
            }
        }
    }

    private fun combineBriefs(
        plan: CharacterResearchPlan,
        referenceDocumentsIncluded: Boolean,
        briefs: List<ResearchBrief>
    ): ResearchBrief? {
        if (briefs.isEmpty()) return null
        return ResearchBrief(
            reason = plan.reason,
            queries = (
                plan.queries.map(CharacterResearchQuery::query) +
                    if (referenceDocumentsIncluded) listOf("上传参考文档 RAG") else emptyList()
                ).distinct(),
            facts = briefs.flatMap(ResearchBrief::facts).distinct(),
            notes = briefs.flatMap(ResearchBrief::notes).distinct(),
            sources = briefs.flatMap(ResearchBrief::sources).distinctBy(ResearchSource::sourceId)
        )
    }

    private fun normalizedQueryKey(query: String): String =
        query.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun manualUrlPlan(urls: List<String>): CharacterResearchPlan = CharacterResearchPlan(
        needSearch = true,
        reason = "读取并整理用户指定网页，不执行关键词搜索",
        queries = urls.mapIndexed { index, url ->
            CharacterResearchQuery(query = url, priority = index + 1)
        }
    )

    private fun referenceDocumentPlan(): CharacterResearchPlan = CharacterResearchPlan(
        needSearch = true,
        reason = "使用用户要求与角色卡已有内容检索上传参考文档",
        queries = listOf(CharacterResearchQuery(query = "上传参考文档 RAG", priority = 1))
    )

    private fun String.statusSnippet(maxChars: Int): String =
        replace(Regex("\\s+"), " ").trim().let { text ->
            if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"
        }

    private fun ResearchBrief.hasSummaryText(): Boolean =
        facts.any(String::isNotBlank) || notes.any(String::isNotBlank)

    private fun fallbackPlan(
        userInput: String,
        currentCard: CharacterCard,
        maxQueries: Int,
        failureReason: String?
    ): CharacterResearchPlan? {
        val candidates = buildList {
            add(currentCard.name)
            addAll(extractQuotedTerms(userInput))
            add(cleanRequestForFallbackQuery(userInput))
        }
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 2 }
            .distinctBy(String::lowercase)
            .take(maxQueries)

        if (candidates.isEmpty()) return null
        val reason = buildString {
            append("AI 搜索规划失败，使用保底关键词")
            failureReason?.takeIf(String::isNotBlank)?.let { append("：").append(it.statusSnippet(80)) }
        }
        return CharacterResearchPlan(
            needSearch = true,
            reason = reason,
            queries = candidates.mapIndexed { index, query ->
                CharacterResearchQuery(query = query, priority = index + 1)
            }
        )
    }

    private fun extractQuotedTerms(text: String): List<String> {
        val patterns = listOf(
            Regex("[「『《“\"]([^」』》”\"]{2,60})[」』》”\"]"),
            Regex("'([^']{2,60})'")
        )
        return patterns.flatMap { pattern ->
            pattern.findAll(text).map { it.groupValues[1] }.toList()
        }
    }

    private fun cleanRequestForFallbackQuery(text: String): String =
        text
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("[，。！？、；：,.!?;:]"), " ")
            .replace(
                Regex("(帮我|请|根据|生成|创建|设计|改写|填充|完善|补全|角色卡|角色|设定|资料|信息|搜索|查找|一下|一个|一张|本APP|AI|的)"),
                " "
            )
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(60)
}
