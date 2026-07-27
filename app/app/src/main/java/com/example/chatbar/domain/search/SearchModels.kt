package com.example.chatbar.domain.search

import com.example.chatbar.data.local.entity.CharacterResearchSourceMode
import kotlinx.serialization.Serializable

const val MAX_MANUAL_RESEARCH_URLS = 5

data class CharacterResearchOptions(
    val mode: CharacterResearchSourceMode = CharacterResearchSourceMode.ENCYCLOPEDIA_SEARCH,
    val urls: List<String> = emptyList()
)

fun CharacterResearchSourceMode.usesEncyclopediaSearch(): Boolean =
    this == CharacterResearchSourceMode.ENCYCLOPEDIA_SEARCH ||
        this == CharacterResearchSourceMode.ENCYCLOPEDIA_SEARCH_AND_MANUAL_URLS

fun CharacterResearchSourceMode.usesManualUrls(): Boolean =
    this == CharacterResearchSourceMode.MANUAL_URLS ||
        this == CharacterResearchSourceMode.ENCYCLOPEDIA_SEARCH_AND_MANUAL_URLS

fun CharacterResearchOptions.hasManualUrlSource(): Boolean =
    mode.usesManualUrls() && urls.any(String::isNotBlank)

fun CharacterResearchOptions.sourceSignaturePart(): String =
    buildString {
        append(mode.name)
        urls.map(ResearchCleaner::canonicalUrl).forEach { url -> append('\n').append(url) }
    }

data class ManualResearchUrlValidation(
    val urls: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val hasCleartextHttp: Boolean = false
) {
    val isValid: Boolean
        get() = errors.isEmpty()
}

fun validateManualResearchUrls(
    rawText: String,
    maxUrls: Int = MAX_MANUAL_RESEARCH_URLS
): ManualResearchUrlValidation =
    validateManualResearchUrls(rawText.lineSequence().toList(), maxUrls)

fun validateManualResearchUrls(
    rawUrls: List<String>,
    maxUrls: Int = MAX_MANUAL_RESEARCH_URLS
): ManualResearchUrlValidation {
    val entries = rawUrls.mapIndexedNotNull { index, raw ->
        raw.trim().takeIf(String::isNotBlank)?.let { value -> index + 1 to value }
    }
    val errors = mutableListOf<String>()
    if (entries.size > maxUrls) {
        errors += "指定网址最多 $maxUrls 个，当前 ${entries.size} 个"
    }
    val normalized = mutableListOf<String>()
    val firstLineByUrl = mutableMapOf<String, Int>()
    entries.forEach { (lineNumber, value) ->
        val uri = runCatching { java.net.URI(value) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        when {
            uri == null || scheme !in setOf("http", "https") || uri.host.isNullOrBlank() ->
                errors += "第 $lineNumber 个网址必须是完整 HTTP(S) 地址"
            !uri.userInfo.isNullOrBlank() ->
                errors += "第 $lineNumber 个网址不能包含用户名或密码"
            else -> {
                val canonical = ResearchCleaner.canonicalUrl(value)
                val duplicateLine = firstLineByUrl.putIfAbsent(canonical, lineNumber)
                if (duplicateLine != null) {
                    errors += "第 $lineNumber 个网址与第 $duplicateLine 个重复"
                } else {
                    normalized += canonical
                }
            }
        }
    }
    return ManualResearchUrlValidation(
        urls = normalized.take(maxUrls),
        errors = errors.distinct(),
        hasCleartextHttp = normalized.any { it.startsWith("http://", ignoreCase = true) }
    )
}

@Serializable
data class CharacterResearchPlan(
    val needSearch: Boolean = false,
    val queries: List<CharacterResearchQuery> = emptyList(),
    val reason: String = ""
)

@Serializable
data class CharacterResearchQuery(
    val query: String = "",
    val priority: Int = 3
)

data class CharacterResearchPlanResult(
    val plan: CharacterResearchPlan? = null,
    val failureReason: String? = null,
    val rawResponsePreview: String = ""
)

@Serializable
data class ResearchDebugSnapshot(
    val plan: CharacterResearchPlan? = null,
    val sources: List<ResearchSource> = emptyList(),
    val brief: ResearchBrief? = null,
    val briefFailureReason: String = "",
    val briefRawResponsePreview: String = ""
) {
    fun hasContent(): Boolean =
        plan != null ||
            sources.isNotEmpty() ||
            brief?.hasContent() == true ||
            briefFailureReason.isNotBlank() ||
            briefRawResponsePreview.isNotBlank()
}

@Serializable
data class ResearchBrief(
    val reason: String = "",
    val queries: List<String> = emptyList(),
    val facts: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val sources: List<ResearchSource> = emptyList()
) {
    fun hasContent(): Boolean =
        facts.isNotEmpty() ||
            notes.isNotEmpty() ||
            sources.isNotEmpty()
}

@Serializable
data class ResearchSource(
    val sourceId: String,
    val title: String,
    val url: String,
    val sourceType: String,
    val query: String,
    val excerpt: String,
    val score: Double = 0.0
)

data class SearchBackendQuery(
    val query: String,
    val maxResults: Int
)

data class SearchHit(
    val title: String,
    val url: String,
    val content: String,
    val rawContent: String? = null,
    val score: Double = 0.0,
    val query: String = ""
)

data class SearchExtract(
    val url: String,
    val rawContent: String
)

data class ResearchBriefResult(
    val brief: ResearchBrief? = null,
    val failureReason: String = "",
    val rawResponsePreview: String = ""
)

interface SearchBackend {
    suspend fun search(query: SearchBackendQuery): List<SearchHit>

    suspend fun extract(urls: List<String>, maxPages: Int): List<SearchExtract>
}

interface CharacterResearchPlanProvider {
    suspend fun plan(
        userInput: String,
        currentCard: com.example.chatbar.data.local.entity.CharacterCard,
        modelConfig: com.example.chatbar.data.local.entity.ModelConfig,
        maxQueries: Int,
        onStatus: (String) -> Unit = {},
        onRawText: (String) -> Unit = {}
    ): CharacterResearchPlanResult
}

interface ResearchBriefSummarizer {
    suspend fun summarize(
        request: String,
        currentCard: com.example.chatbar.data.local.entity.CharacterCard,
        plan: CharacterResearchPlan,
        sources: List<ResearchSource>,
        modelConfig: com.example.chatbar.data.local.entity.ModelConfig,
        onStatus: (String) -> Unit = {},
        onRawText: (String) -> Unit = {}
    ): ResearchBriefResult
}
