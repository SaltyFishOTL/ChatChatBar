package com.example.chatbar.domain.search

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class LlmResearchBriefSummarizer(
    private val chatService: StreamingChatService,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }
) : ResearchBriefSummarizer {
    override suspend fun summarize(
        request: String,
        currentCard: CharacterCard,
        plan: CharacterResearchPlan,
        sources: List<ResearchSource>,
        modelConfig: ModelConfig,
        onStatus: (String) -> Unit
    ): ResearchBriefResult = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) return@withContext ResearchBriefResult(failureReason = "sources empty")
        var rawResponse = ""
        runCatching {
            var reasoningNotified = false
            var contentNotified = false
            rawResponse = chatService.completeTextStreaming(
                messages = listOf(
                    ChatApiMessage.text("system", PromptTemplates.characterResearchBriefSystemPrompt()),
                    ChatApiMessage.text("user", summaryUserPrompt(request, currentCard, plan, sources))
                ),
                modelConfig = modelConfig,
                maxTokens = 1200,
                enableThinking = false,
                maxThinkingTokens = 128,
                thinkingBudget = 128,
                reasoningEffort = "low",
                onDelta = {
                    if (!contentNotified) {
                        contentNotified = true
                        onStatus("AI 正在输出资料整理结果")
                    }
                },
                onReasoningDelta = {
                    if (!reasoningNotified) {
                        reasoningNotified = true
                        onStatus("AI 正在整理资料（思考中）")
                    }
                }
            )
            val draft = parseSummary(rawResponse) ?: return@runCatching ResearchBriefResult(
                failureReason = "summary JSON parse failed",
                rawResponsePreview = rawResponse.take(1200)
            )
            ResearchBriefResult(
                brief = ResearchBrief(
                    reason = plan.reason,
                    queries = plan.queries.map { it.query },
                    facts = draft.facts.cleanList(12, 320),
                    notes = draft.notes.cleanList(8, 320),
                    sources = sources
                ),
                rawResponsePreview = rawResponse.take(1200)
            )
        }.getOrElse { error ->
            ResearchBriefResult(
                failureReason = error.message ?: error::class.java.simpleName,
                rawResponsePreview = rawResponse.take(1200)
            )
        }
    }

    fun parseSummary(raw: String): ResearchBriefDraft? {
        val candidates = raw.extractJsonObjectCandidates().ifEmpty { listOf(raw.trim()) }
        return candidates.firstNotNullOfOrNull { candidate ->
            runCatching { json.decodeFromString(ResearchBriefDraft.serializer(), candidate) }.getOrNull()
        }
    }

    private fun summaryUserPrompt(
        request: String,
        currentCard: CharacterCard,
        plan: CharacterResearchPlan,
        sources: List<ResearchSource>
    ): String = PromptTemplates.characterResearchBriefUserPrompt(
        request = request,
        currentCardSummary = currentCard.researchSummary(),
        sources = sources.joinToString(separator = "\n\n") { source ->
            PromptTemplates.characterResearchBriefSource(
                sourceId = source.sourceId,
                title = source.title,
                excerpt = source.excerpt
            )
        }
    )

    private fun List<String>.cleanList(maxItems: Int, maxChars: Int): List<String> =
        map { ResearchCleaner.sanitizeText(it).take(maxChars).trim() }
            .filter(String::isNotBlank)
            .distinct()
            .take(maxItems)
}

@Serializable
data class ResearchBriefDraft(
    val facts: List<String> = emptyList(),
    val notes: List<String> = emptyList()
)
