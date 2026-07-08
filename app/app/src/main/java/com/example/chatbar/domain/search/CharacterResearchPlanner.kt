package com.example.chatbar.domain.search

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max

class CharacterResearchPlanner(
    private val chatService: StreamingChatService,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }
) : CharacterResearchPlanProvider {
    override suspend fun plan(
        userInput: String,
        currentCard: CharacterCard,
        modelConfig: ModelConfig,
        maxQueries: Int,
        onStatus: (String) -> Unit,
        onRawText: (String) -> Unit
    ): CharacterResearchPlanResult = withContext(Dispatchers.IO) {
        var rawResponse = ""
        runCatching {
            var reasoningNotified = false
            var contentNotified = false
            val visibleText = StringBuilder()
            onStatus("AI 正在规划搜索")
            rawResponse = chatService.completeTextStreaming(
                messages = listOf(
                    ChatApiMessage.text("system", PromptTemplates.characterResearchPlannerSystemPrompt(maxQueries)),
                    ChatApiMessage.text(
                        "user",
                        PromptTemplates.characterResearchPlannerUserPrompt(userInput)
                    )
                ),
                modelConfig = modelConfig,
                maxTokens = 300,
                enableThinking = false,
                maxThinkingTokens = 64,
                thinkingBudget = 64,
                reasoningEffort = "low",
                onDelta = { chunk ->
                    visibleText.append(chunk)
                    onRawText(visibleText.toString())
                    if (!contentNotified) {
                        contentNotified = true
                        onStatus("AI 正在输出搜索规划")
                    }
                },
                onReasoningDelta = {
                    if (!reasoningNotified) {
                        reasoningNotified = true
                        onStatus("AI 正在规划搜索（思考中）")
                    }
                }
            )
            parsePlan(rawResponse, maxQueries)
                ?.let { CharacterResearchPlanResult(plan = it, rawResponsePreview = rawResponse.take(1200)) }
                ?: CharacterResearchPlanResult(
                    failureReason = "JSON parse failed",
                    rawResponsePreview = rawResponse.take(1200)
                )
        }.getOrElse { error ->
                CharacterResearchPlanResult(
                    failureReason = error.message ?: error::class.java.simpleName,
                    rawResponsePreview = rawResponse.take(1200)
                )
        }
    }

    fun parsePlan(raw: String, maxQueries: Int): CharacterResearchPlan? {
        val candidates = raw.extractJsonObjectCandidates().ifEmpty { listOf(raw.trim()) }
        return candidates.firstNotNullOfOrNull { candidate ->
            decodeFlexiblePlan(candidate, maxQueries) ?: decodeStrictPlan(candidate, maxQueries)
        }
    }

    private fun decodeStrictPlan(candidate: String, maxQueries: Int): CharacterResearchPlan? =
        runCatching {
            val decoded = json.decodeFromString(CharacterResearchPlan.serializer(), candidate)
            normalize(decoded, maxQueries)
        }.getOrNull()

    private fun decodeFlexiblePlan(candidate: String, maxQueries: Int): CharacterResearchPlan? =
        runCatching {
            val obj = json.parseToJsonElement(candidate).jsonObject
            val queryElements = obj["queries"] ?: obj["query"] ?: obj["keywords"]
            val queries = queryElements.toResearchQueries()
            val needSearch = obj.booleanField("needSearch")
                ?: obj.booleanField("need_search")
                ?: queries.isNotEmpty()
            normalize(
                CharacterResearchPlan(
                    needSearch = needSearch,
                    reason = obj.stringField("reason") ?: obj.stringField("purpose") ?: "",
                    queries = queries
                ),
                maxQueries
            )
        }.getOrNull()

    private fun normalize(plan: CharacterResearchPlan, maxQueries: Int): CharacterResearchPlan {
        val limit = max(1, maxQueries)
        val queries = plan.queries
            .asSequence()
            .map {
                it.copy(
                    query = it.query.trim(),
                    priority = it.priority.coerceIn(1, 5)
                )
            }
            .filter { it.query.isNotBlank() }
            .distinctBy { it.query.lowercase() }
            .sortedBy { it.priority }
            .take(limit)
            .toList()
        return plan.copy(
            needSearch = plan.needSearch && queries.isNotEmpty(),
            queries = queries,
            reason = plan.reason.trim()
        )
    }

    private fun JsonObject.booleanField(key: String): Boolean? {
        val primitive = get(key)?.jsonPrimitive ?: return null
        primitive.booleanOrNull?.let { return it }
        return when (primitive.contentOrNull?.trim()?.lowercase()) {
            "true", "yes", "y", "1", "需要", "搜索" -> true
            "false", "no", "n", "0", "不需要", "无需" -> false
            else -> null
        }
    }

    private fun JsonObject.stringField(key: String): String? =
        get(key)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

    private fun JsonElement?.toResearchQueries(): List<CharacterResearchQuery> = when (this) {
        is JsonArray -> mapIndexedNotNull { index, element -> element.toResearchQuery(index + 1) }
        is JsonObject -> listOfNotNull(toResearchQuery(1))
        else -> listOfNotNull(this?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)?.let {
            CharacterResearchQuery(query = it, priority = 1)
        })
    }

    private fun JsonElement.toResearchQuery(defaultPriority: Int): CharacterResearchQuery? = when (this) {
        is JsonObject -> {
            val query = stringField("query")
                ?: stringField("keyword")
                ?: stringField("text")
                ?: stringField("title")
            query?.let {
                CharacterResearchQuery(
                    query = it,
                    priority = get("priority")?.jsonPrimitive?.intOrNull ?: defaultPriority
                )
            }
        }
        else -> jsonPrimitive.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { CharacterResearchQuery(query = it, priority = defaultPriority) }
    }

}

internal fun CharacterCard.researchSummary(): String = buildString {
    appendLine("名称：${name.ifBlank { "（空）" }}")
    appendLine("编辑模式：${editMode.name}")
    if (basicSetting.isNotBlank()) appendLine("基础设定：${basicSetting.take(700)}")
    if (greeting.isNotBlank()) appendLine("开场白：${greeting.take(300)}")
    if (editMode == CharacterEditMode.FREEFORM) {
        if (freeformCharacterText.isNotBlank()) appendLine("自由文本：${freeformCharacterText.take(1200)}")
    } else {
        characters.take(8).forEachIndexed { index, character ->
            appendLine("角色[$index]：${character.name.ifBlank { "（空）" }}")
            listOf(
                "简介" to character.profile,
                "外貌" to character.appearance,
                "背景" to character.background,
                "关系" to character.relationships
            ).forEach { (label, value) ->
                if (value.isNotBlank()) appendLine("$label：${value.take(350)}")
            }
        }
    }
}.trim()
