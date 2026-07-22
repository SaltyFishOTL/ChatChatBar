package com.example.chatbar.domain.search

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val MAX_RESEARCH_ITEMS = 10
private const val MAX_EFFECTIVE_RESULTS_PER_QUERY = 1
private const val FIRST_PASS_EXCERPT_CHARS = 1200
private const val FINAL_EXCERPT_CHARS = 4000

class CharacterResearchService(
    private val settingsProvider: suspend () -> AppSettings,
    private val planner: CharacterResearchPlanProvider,
    private val backend: SearchBackend,
    private val summarizer: ResearchBriefSummarizer
) {
    suspend fun research(
        userInput: String,
        currentCard: CharacterCard,
        modelConfig: ModelConfig,
        webSearchEnabled: Boolean = true,
        onDebug: (ResearchDebugSnapshot) -> Unit = {},
        resumeFrom: ResearchDebugSnapshot? = null,
        onCheckpoint: (ResearchDebugSnapshot) -> Unit = {},
        onVisibleOutput: (String, String, String) -> Unit = { _, _, _ -> },
        onStatus: (String) -> Unit = {}
    ): ResearchBrief? = withContext(Dispatchers.IO) {
        if (!webSearchEnabled) {
            onStatus("搜索增强未启用，跳过搜索")
            return@withContext null
        }
        val settings = settingsProvider()

        val maxResearchItems = MAX_RESEARCH_ITEMS
        val maxResults = settings.webSearchMaxResultsPerQuery.coerceIn(1, MAX_EFFECTIVE_RESULTS_PER_QUERY)

        fun publish(snapshot: ResearchDebugSnapshot) {
            onDebug(snapshot)
            onCheckpoint(snapshot)
        }
        val planResult = if (resumeFrom?.plan == null) {
            planner.plan(
                userInput = userInput,
                currentCard = currentCard,
                modelConfig = modelConfig,
                maxQueries = maxResearchItems,
                onStatus = onStatus,
                onRawText = { text -> onVisibleOutput("research-plan", "搜索规划输出", text) }
            )
        } else {
            CharacterResearchPlanResult(plan = resumeFrom.plan)
        }
        val plan = planResult.plan ?: fallbackPlan(userInput, currentCard, maxResearchItems, planResult.failureReason)
        if (plan == null) {
            onStatus("搜索规划失败，且没有可用保底关键词，跳过搜索")
            return@withContext null
        }
        if (planResult.plan == null) {
            onStatus("搜索规划失败，改用保底关键词继续搜索：${plan.queries.joinToString("、") { it.query }.statusSnippet(120)}")
        }
        publish(ResearchDebugSnapshot(plan = plan))
        if (!plan.needSearch || plan.queries.isEmpty()) {
            return@withContext null
        }

        val queries = plan.queries.take(maxResearchItems)
        onStatus(
            buildString {
                append("AI 决定搜索 ${queries.size} 个关键词")
                plan.reason.takeIf(String::isNotBlank)?.let { append("：").append(it.statusSnippet(80)) }
            }
        )
        queries.forEachIndexed { index, query ->
            onStatus("关键词 ${index + 1}/${queries.size}：${query.query.statusSnippet(120)}")
        }

        val resumedSources = resumeFrom?.sources.orEmpty()
        val hits = if (resumedSources.isEmpty()) withTimeoutOrNull(35_000L) {
            queries.flatMapIndexed { index, query ->
                onStatus("正在搜索百科 ${index + 1}/${queries.size}：${query.query.statusSnippet(120)}")
                runCatching {
                    val queryHits = backend.search(
                        SearchBackendQuery(
                            query = query.query,
                            maxResults = maxResults
                        )
                    ).map { it.copy(query = query.query) }
                    onStatus("百科搜索完成 ${index + 1}/${queries.size}：命中 ${queryHits.size} 条")
                    queryHits
                }.getOrElse { error ->
                    onStatus("百科搜索失败 ${index + 1}/${queries.size}：${error.message ?: error::class.java.simpleName}")
                    emptyList()
                }
            }
        }.orEmpty() else emptyList()

        if (hits.isEmpty() && resumedSources.isEmpty()) {
            onStatus("没有可用百科结果，继续直接生成")
            return@withContext null
        }

        val firstPassSources = ResearchCleaner.toResearchSources(
            hits = hits,
            extracts = emptyList(),
            maxSources = maxResearchItems,
            maxExcerptChars = FIRST_PASS_EXCERPT_CHARS
        )
        val extracts = if (firstPassSources.isEmpty()) {
            emptyList()
        } else {
            onStatus("正在抽取百科正文：${firstPassSources.size} 个来源")
            runCatching {
                backend.extract(
                    urls = firstPassSources.map { it.url },
                    maxPages = maxResearchItems
                )
            }.getOrElse { error ->
                onStatus("百科正文抽取失败，改用搜索摘要：${error.message ?: error::class.java.simpleName}")
                emptyList()
            }
        }
        val sources = resumedSources.ifEmpty {
            ResearchCleaner.toResearchSources(
                hits = hits,
                extracts = extracts,
                maxSources = maxResearchItems,
                maxExcerptChars = FINAL_EXCERPT_CHARS
            )
        }
        if (sources.isEmpty()) {
            onStatus("百科结果清洗后为空，继续直接生成")
            return@withContext null
        }
        publish(ResearchDebugSnapshot(plan = plan, sources = sources))

        val resumedBrief = resumeFrom?.brief?.takeIf(ResearchBrief::hasContent)
        if (resumedBrief != null) onStatus("沿用已整理的搜索资料，开始生成")
        if (resumedBrief == null) onStatus("正在清洗并压缩百科资料：${sources.size} 个来源")
        val summaryResult = if (resumedBrief == null) runCatching {
            summarizer.summarize(
                request = userInput,
                currentCard = currentCard,
                plan = plan,
                sources = sources,
                modelConfig = modelConfig,
                onStatus = onStatus,
                onRawText = { text -> onVisibleOutput("research-brief", "资料整理输出", text) }
            )
        }.getOrElse { error ->
            ResearchBriefResult(failureReason = error.message ?: error::class.java.simpleName)
        } else ResearchBriefResult(brief = resumedBrief)
        if (summaryResult.failureReason.isNotBlank()) {
            onStatus("搜索资料压缩失败：${summaryResult.failureReason.statusSnippet(120)}")
            publish(
                ResearchDebugSnapshot(
                    plan = plan,
                    sources = sources,
                    briefFailureReason = summaryResult.failureReason,
                    briefRawResponsePreview = summaryResult.rawResponsePreview
                )
            )
        }
        val brief = if (summaryResult.brief?.hasContent() == true) {
            summaryResult.brief
        } else {
            onStatus("AI资料整理不可用，使用清洗正文兜底摘要")
            ResearchCleaner.fallbackBrief(plan.reason, queries.map { it.query }, sources)
        }
        if (brief?.hasContent() == true) {
            publish(ResearchDebugSnapshot(plan = plan, sources = sources, brief = brief))
            onStatus("搜索资料已整理，开始生成")
        } else {
            onStatus("搜索资料为空，继续直接生成")
        }
        brief
    }

    private fun String.statusSnippet(maxChars: Int): String =
        replace(Regex("\\s+"), " ").trim().let { text ->
            if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"
        }

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
