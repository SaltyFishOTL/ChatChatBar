package com.example.chatbar.domain.search

import kotlinx.serialization.Serializable

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

    suspend fun extract(urls: List<String>): List<SearchExtract>
}

interface CharacterResearchPlanProvider {
    suspend fun plan(
        userInput: String,
        currentCard: com.example.chatbar.data.local.entity.CharacterCard,
        modelConfig: com.example.chatbar.data.local.entity.ModelConfig,
        maxQueries: Int,
        onStatus: (String) -> Unit = {}
    ): CharacterResearchPlanResult
}

interface ResearchBriefSummarizer {
    suspend fun summarize(
        request: String,
        currentCard: com.example.chatbar.data.local.entity.CharacterCard,
        plan: CharacterResearchPlan,
        sources: List<ResearchSource>,
        modelConfig: com.example.chatbar.data.local.entity.ModelConfig,
        onStatus: (String) -> Unit = {}
    ): ResearchBriefResult
}
