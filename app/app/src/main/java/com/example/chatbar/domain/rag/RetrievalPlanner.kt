package com.example.chatbar.domain.rag

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RetrievalPlanner(
    private val chatService: StreamingChatService
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun plan(
        currentUserContent: String,
        contextMessages: List<ChatMessage>,
        characterName: String,
        modelConfig: ModelConfig
    ): RetrievalPlanResult = withContext(Dispatchers.IO) {
        var rawResponse = ""
        val result = withTimeoutOrNull(15_000L) {
            runCatching {
                rawResponse = chatService.completeText(
                    messages = listOf(
                        ChatApiMessage.text("system", PromptTemplates.RETRIEVAL_PLANNER_SYSTEM_PROMPT),
                        ChatApiMessage.text(
                            "user",
                            PromptTemplates.retrievalPlannerUserInput(
                                currentUserContent = currentUserContent,
                                contextMessages = contextMessages,
                                characterName = characterName
                            )
                        )
                    ),
                    modelConfig = modelConfig,
                    maxTokens = null
                )
                val plan = parsePlan(rawResponse)
                if (plan == null) {
                    RetrievalPlanResult(
                        plan = null,
                        failureReason = "JSON parse failed",
                        rawResponsePreview = rawResponse.take(1200)
                    )
                } else {
                    RetrievalPlanResult(
                        plan = plan,
                        rawResponsePreview = rawResponse.take(1200)
                    )
                }
            }.getOrElse { e ->
                RetrievalPlanResult(
                    plan = null,
                    failureReason = e.message ?: e::class.java.simpleName,
                    rawResponsePreview = rawResponse.take(1200)
                )
            }
        }
        result ?: RetrievalPlanResult(plan = null, failureReason = "timeout after 15000ms")
    }

    private fun parsePlan(response: String): RetrievalPlan? {
        val candidates = buildList {
            add(response.trim())
            add(response.removeMarkdownFence().trim())
            response.extractBalancedJsonObject()?.let { add(it) }
            response.removeMarkdownFence().extractBalancedJsonObject()?.let { add(it) }
        }
            .flatMap { listOf(it, it.repairCommonJsonIssues()) }
            .distinct()
            .filter { it.isNotBlank() }

        return candidates.firstNotNullOfOrNull { candidate ->
            runCatching { parsePlanManually(candidate) }.getOrNull()
        }
    }

    private fun parsePlanManually(candidate: String): RetrievalPlan? {
        val root = json.parseToJsonElement(candidate).jsonObject
        if (!root.containsKey("t") && !root.containsKey("q") && !root.containsKey("e")) {
            return null
        }

        val topics = root.stringList("t")
        val queries = root.stringList("q")
        val entities = root.stringList("e")
        return RetrievalPlan(
            topic = topics.distinct(),
            queries = queries.distinct(),
            entities = entities.distinct()
        )
    }

}

private fun String.removeMarkdownFence(): String {
    return trim()
        .removePrefix("```json")
        .removePrefix("```JSON")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
}

private fun String.extractBalancedJsonObject(): String? {
    val start = indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until length) {
        val c = this[index]
        if (escaped) {
            escaped = false
            continue
        }
        if (c == '\\' && inString) {
            escaped = true
            continue
        }
        if (c == '"') {
            inString = !inString
            continue
        }
        if (inString) continue
        when (c) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return substring(start, index + 1)
            }
        }
    }
    return null
}

private fun String.repairCommonJsonIssues(): String {
    return replace('“', '"')
        .replace('”', '"')
        .replace('‘', '\'')
        .replace('’', '\'')
        .replace(Regex(",\\s*([}\\]])"), "$1")
        .trim()
}

private fun JsonObject?.stringList(key: String): List<String> {
    val element = this?.get(key) ?: return emptyList()
    return when (element) {
        is JsonArray -> element.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }.filter { it.isNotBlank() }
        else -> element.jsonPrimitive.contentOrNull
            ?.split(",", "，")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }
}

data class RetrievalPlanResult(
    val plan: RetrievalPlan?,
    val failureReason: String? = null,
    val rawResponsePreview: String = ""
)
