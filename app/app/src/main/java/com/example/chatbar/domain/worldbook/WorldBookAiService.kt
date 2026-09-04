package com.example.chatbar.domain.worldbook

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.domain.card.extractJsonObjectCandidates
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.domain.search.CharacterReferenceDocument
import com.example.chatbar.domain.search.CharacterResearchOptions
import com.example.chatbar.domain.search.ResearchBrief
import com.example.chatbar.domain.search.ResearchDebugSnapshot
import com.example.chatbar.domain.search.WorldBookResearchService
import com.example.chatbar.domain.search.WorldBookResearchSession
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

const val WORLD_BOOK_AI_BATCH_SIZE = 5
const val WORLD_BOOK_AI_CREATE_LIMIT = 50

data class WorldBookEntryPlanCandidate(
    val candidateId: String,
    val name: String,
    val keys: List<String>
)

data class WorldBookContentCandidate(
    val targetId: String,
    val name: String,
    val keys: List<String>,
    val content: String
)

data class WorldBookAiRawOutput(
    val key: String,
    val title: String,
    val text: String
)

internal data class FillConstraintResult(
    val candidates: List<WorldBookContentCandidate>,
    val rejections: List<String>
)

internal data class CreateContinuationDecision(
    val shouldContinue: Boolean,
    val warning: String = ""
)

data class WorldBookCreateCheckpoint(
    val candidates: List<WorldBookEntryPlanCandidate> = emptyList(),
    val batchNumber: Int = 1,
    val researchDebug: ResearchDebugSnapshot? = null,
    val researchSession: WorldBookResearchSession? = null,
    val rawOutputs: List<WorldBookAiRawOutput> = emptyList(),
    val warning: String = ""
)

data class WorldBookFillCheckpoint(
    val candidates: List<WorldBookContentCandidate> = emptyList(),
    val nextTargetIndex: Int = 0,
    val batchNumber: Int = 1,
    val activeResearchDebug: ResearchDebugSnapshot? = null,
    val researchSession: WorldBookResearchSession? = null,
    val rawOutputs: List<WorldBookAiRawOutput> = emptyList()
)

data class WorldBookCreateResult(
    val candidates: List<WorldBookEntryPlanCandidate>,
    val checkpoint: WorldBookCreateCheckpoint,
    val warning: String = ""
)

data class WorldBookFillResult(
    val candidates: List<WorldBookContentCandidate>,
    val checkpoint: WorldBookFillCheckpoint
)

@Serializable
private data class CreateBatchResponse(
    val entries: List<CreateEntryResponse> = emptyList(),
    val hasMore: Boolean = false
)

@Serializable
internal data class CreateEntryResponse(
    val name: String = "",
    val keys: List<String> = emptyList()
)

@Serializable
private data class FillBatchResponse(
    val entries: List<FillEntryResponse> = emptyList()
)

@Serializable
internal data class FillEntryResponse(
    val targetId: String = "",
    val content: String = ""
)

@OptIn(ExperimentalSerializationApi::class)
class WorldBookAiService(
    private val modelResolver: EffectiveModelResolver,
    private val chatService: StreamingChatService,
    private val researchService: WorldBookResearchService,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
        allowTrailingComma = true
    }
) {
    suspend fun createEntriesStreaming(
        request: String,
        book: WorldBook,
        modelOverride: ModelConfig? = null,
        researchOptions: CharacterResearchOptions = CharacterResearchOptions(),
        referenceDocument: CharacterReferenceDocument? = null,
        resumeFrom: WorldBookCreateCheckpoint? = null,
        onCheckpoint: (WorldBookCreateCheckpoint) -> Unit = {},
        onStatus: (String) -> Unit = {},
        onRawText: (String) -> Unit = {},
        onResearchDebug: (ResearchDebugSnapshot) -> Unit = {},
        onVisibleOutput: (String, String, String) -> Unit = { _, _, _ -> }
    ): WorldBookCreateResult = withContext(Dispatchers.IO) {
        require(request.isNotBlank() || referenceDocument != null || researchOptions.urls.isNotEmpty()) {
            "请输入世界书需求，或提供参考文档、网址"
        }
        val model = modelOverride ?: modelResolver.defaultChatModel()
            ?: error("未配置可用对话模型")
        var checkpoint = resumeFrom ?: WorldBookCreateCheckpoint()
        var candidates = checkpoint.candidates
        var researchDebug = checkpoint.researchDebug
        var researchSession = checkpoint.researchSession
        var rawOutputs = checkpoint.rawOutputs
        val recordVisibleOutput: (String, String, String) -> Unit = { key, title, text ->
            rawOutputs = rawOutputs.upsertRawOutput(key, title, text)
            checkpoint = checkpoint.copy(rawOutputs = rawOutputs)
            onCheckpoint(checkpoint)
            onVisibleOutput(key, title, text)
        }

        val research = researchService.research(
            request = request,
            book = book,
            targets = emptyList(),
            model = model,
            options = researchOptions,
            referenceDocument = referenceDocument,
            session = researchSession,
            resumeFrom = researchDebug,
            onSession = { session ->
                researchSession = session
                checkpoint = checkpoint.copy(researchSession = session)
                onCheckpoint(checkpoint)
            },
            onDebug = { debug ->
                researchDebug = debug
                onResearchDebug(debug)
                checkpoint = checkpoint.copy(researchDebug = debug, researchSession = researchSession)
                onCheckpoint(checkpoint)
            },
            onVisibleOutput = recordVisibleOutput,
            onStatus = onStatus
        )
        researchDebug = research.debug.takeIf(ResearchDebugSnapshot::hasContent)
        researchSession = research.session
        checkpoint = checkpoint.copy(researchDebug = researchDebug, researchSession = researchSession)
        onCheckpoint(checkpoint)

        var hasMore = true
        var warning = checkpoint.warning
        while (hasMore && candidates.size < WORLD_BOOK_AI_CREATE_LIMIT) {
            val batchNumber = checkpoint.batchNumber
            val remaining = WORLD_BOOK_AI_CREATE_LIMIT - candidates.size
            onStatus("正在创建条目第 $batchNumber 批；已完成 ${candidates.size}/$WORLD_BOOK_AI_CREATE_LIMIT")
            val raw = streamJsonTask(
                systemPrompt = PromptTemplates.WORLD_BOOK_CREATE_ENTRIES_SYSTEM_PROMPT,
                userPrompt = buildCreatePayload(request, book, candidates, research.brief, remaining),
                model = model,
                maxTokens = 4_000,
                outputKey = "create-batch-$batchNumber",
                outputTitle = "创建条目第 $batchNumber 批输出",
                onStatus = onStatus,
                onRawText = onRawText,
                onVisibleOutput = recordVisibleOutput
            )
            val response = parseCreateResponse(raw) ?: repairCreateResponse(
                raw = raw,
                model = model,
                batchNumber = batchNumber,
                onStatus = onStatus,
                onRawText = onRawText,
                onVisibleOutput = recordVisibleOutput
            ) ?: error("创建条目第 $batchNumber 批输出无法解析")

            val newCandidates = constrainCreatedEntries(book, candidates, response.entries, remaining)
            candidates = candidates + newCandidates
            val continuation = decideCreateContinuation(
                responseHasMore = response.hasMore,
                newCandidateCount = newCandidates.size,
                totalCandidateCount = candidates.size
            )
            hasMore = continuation.shouldContinue
            if (continuation.warning.isNotBlank()) {
                warning = continuation.warning
                onStatus(warning)
            }
            checkpoint = WorldBookCreateCheckpoint(
                candidates = candidates,
                batchNumber = batchNumber + 1,
                researchDebug = researchDebug,
                researchSession = researchSession,
                rawOutputs = rawOutputs,
                warning = warning
            )
            onCheckpoint(checkpoint)
        }
        onStatus("条目创建候选已完成：${candidates.size} 条")
        WorldBookCreateResult(candidates, checkpoint, warning)
    }

    suspend fun fillEmptyContentsStreaming(
        request: String,
        book: WorldBook,
        targets: List<WorldBookEntry>,
        modelOverride: ModelConfig? = null,
        researchOptions: CharacterResearchOptions = CharacterResearchOptions(),
        referenceDocument: CharacterReferenceDocument? = null,
        resumeFrom: WorldBookFillCheckpoint? = null,
        onCheckpoint: (WorldBookFillCheckpoint) -> Unit = {},
        onStatus: (String) -> Unit = {},
        onRawText: (String) -> Unit = {},
        onResearchDebug: (ResearchDebugSnapshot) -> Unit = {},
        onVisibleOutput: (String, String, String) -> Unit = { _, _, _ -> }
    ): WorldBookFillResult = withContext(Dispatchers.IO) {
        val frozenTargets = targets.filter { it.content.isBlank() }
        require(frozenTargets.isNotEmpty()) { "当前没有正文为空的世界书条目" }
        val model = modelOverride ?: modelResolver.defaultChatModel()
            ?: error("未配置可用对话模型")
        var checkpoint = resumeFrom ?: WorldBookFillCheckpoint()
        var candidates = checkpoint.candidates
        var targetIndex = checkpoint.nextTargetIndex.coerceIn(0, frozenTargets.size)
        var researchSession = checkpoint.researchSession
        var rawOutputs = checkpoint.rawOutputs
        val recordVisibleOutput: (String, String, String) -> Unit = { key, title, text ->
            rawOutputs = rawOutputs.upsertRawOutput(key, title, text)
            checkpoint = checkpoint.copy(rawOutputs = rawOutputs)
            onCheckpoint(checkpoint)
            onVisibleOutput(key, title, text)
        }

        while (targetIndex < frozenTargets.size) {
            val batchNumber = checkpoint.batchNumber
            val batch = frozenTargets.subList(targetIndex, minOf(targetIndex + WORLD_BOOK_AI_BATCH_SIZE, frozenTargets.size))
            var activeDebug = checkpoint.activeResearchDebug
            onStatus("正在填充第 $batchNumber 批；${targetIndex}/${frozenTargets.size} 条已完成")
            val research = researchService.research(
                request = request,
                book = book,
                targets = batch,
                model = model,
                options = researchOptions,
                referenceDocument = referenceDocument,
                session = researchSession,
                resumeFrom = activeDebug,
                onSession = { session ->
                    researchSession = session
                    checkpoint = checkpoint.copy(researchSession = session)
                    onCheckpoint(checkpoint)
                },
                onDebug = { debug ->
                    activeDebug = debug
                    onResearchDebug(debug)
                    checkpoint = checkpoint.copy(
                        activeResearchDebug = debug,
                        researchSession = researchSession
                    )
                    onCheckpoint(checkpoint)
                },
                onVisibleOutput = { key, title, text ->
                    recordVisibleOutput("batch-$batchNumber-$key", "第 $batchNumber 批 · $title", text)
                },
                onStatus = onStatus
            )
            val raw = streamJsonTask(
                systemPrompt = PromptTemplates.WORLD_BOOK_FILL_CONTENT_SYSTEM_PROMPT,
                userPrompt = buildFillPayload(request, book, batch, research.brief),
                model = model,
                maxTokens = 12_000,
                outputKey = "fill-batch-$batchNumber",
                outputTitle = "填充内容第 $batchNumber 批输出",
                onStatus = onStatus,
                onRawText = onRawText,
                onVisibleOutput = recordVisibleOutput
            )
            val firstParse = parseFillResponse(raw, batch)
            firstParse?.rejections?.forEach(onStatus)
            val parsed = firstParse?.candidates?.takeIf { it.size == batch.size } ?: repairFillResponse(
                raw = raw,
                targets = batch,
                model = model,
                batchNumber = batchNumber,
                onStatus = onStatus,
                onRawText = onRawText,
                onVisibleOutput = recordVisibleOutput
            ) ?: error("填充内容第 $batchNumber 批输出无法解析或缺少目标条目")
            candidates = candidates + parsed
            targetIndex += batch.size
            checkpoint = WorldBookFillCheckpoint(
                candidates = candidates,
                nextTargetIndex = targetIndex,
                batchNumber = batchNumber + 1,
                activeResearchDebug = null,
                researchSession = researchSession,
                rawOutputs = rawOutputs
            )
            onCheckpoint(checkpoint)
        }
        onStatus("条目内容候选已完成：${candidates.size} 条")
        WorldBookFillResult(candidates, checkpoint)
    }

    private suspend fun streamJsonTask(
        systemPrompt: String,
        userPrompt: String,
        model: ModelConfig,
        maxTokens: Int,
        outputKey: String,
        outputTitle: String,
        onStatus: (String) -> Unit,
        onRawText: (String) -> Unit,
        onVisibleOutput: (String, String, String) -> Unit
    ): String {
        val visible = StringBuilder()
        return chatService.completeTextStreaming(
            messages = listOf(
                ChatApiMessage.text("system", systemPrompt.trimIndent()),
                ChatApiMessage.text("user", userPrompt)
            ),
            modelConfig = model,
            maxTokens = maxTokens,
            isolatedTaskParameters = true,
            onDelta = { chunk ->
                visible.append(chunk)
                val text = visible.toString()
                onRawText(text)
                onVisibleOutput(outputKey, outputTitle, text)
            },
            onReasoningDelta = { onStatus("AI 正在处理（思考中）") }
        )
    }

    private suspend fun repairCreateResponse(
        raw: String,
        model: ModelConfig,
        batchNumber: Int,
        onStatus: (String) -> Unit,
        onRawText: (String) -> Unit,
        onVisibleOutput: (String, String, String) -> Unit
    ): CreateBatchResponse? {
        onStatus("正在修复创建条目第 $batchNumber 批 JSON")
        val repaired = streamJsonTask(
            PromptTemplates.WORLD_BOOK_CREATE_ENTRIES_REPAIR_PROMPT,
            raw,
            model,
            4_000,
            "create-repair-$batchNumber",
            "创建条目第 $batchNumber 批修复输出",
            onStatus,
            onRawText,
            onVisibleOutput
        )
        return parseCreateResponse(repaired)
    }

    private suspend fun repairFillResponse(
        raw: String,
        targets: List<WorldBookEntry>,
        model: ModelConfig,
        batchNumber: Int,
        onStatus: (String) -> Unit,
        onRawText: (String) -> Unit,
        onVisibleOutput: (String, String, String) -> Unit
    ): List<WorldBookContentCandidate>? {
        onStatus("正在修复填充内容第 $batchNumber 批 JSON")
        val repairInput = buildJsonObject {
            put("targets", buildJsonArray {
                targets.forEach { target ->
                    add(buildJsonObject {
                        put("targetId", target.id)
                        put("name", target.name)
                        put("keys", json.encodeToJsonElement(target.keys))
                    })
                }
            })
            put("text", raw)
        }.toString()
        val repaired = streamJsonTask(
            PromptTemplates.WORLD_BOOK_FILL_CONTENT_REPAIR_PROMPT,
            repairInput,
            model,
            12_000,
            "fill-repair-$batchNumber",
            "填充内容第 $batchNumber 批修复输出",
            onStatus,
            onRawText,
            onVisibleOutput
        )
        val parsed = parseFillResponse(repaired, targets)
        parsed?.rejections?.forEach(onStatus)
        return parsed?.candidates?.takeIf { it.size == targets.size }
    }

    private fun buildCreatePayload(
        request: String,
        book: WorldBook,
        candidates: List<WorldBookEntryPlanCandidate>,
        research: ResearchBrief?,
        remaining: Int
    ): String = buildJsonObject {
        put("request", request.trim())
        put("currentBook", book.promptSummary())
        put("existingAndPlanned", buildJsonArray {
            book.entries.forEach { entry -> add(entry.planSummaryJson()) }
            candidates.forEach { candidate ->
                add(buildJsonObject {
                    put("name", candidate.name)
                    put("keys", json.encodeToJsonElement(candidate.keys))
                })
            }
        })
        research?.takeIf(ResearchBrief::hasContent)?.let {
            put("externalResearch", it.toWorldBookPromptJson())
        }
        put("batchLimit", minOf(WORLD_BOOK_AI_BATCH_SIZE, remaining))
        put("remainingLimit", remaining)
    }.toString()

    private fun buildFillPayload(
        request: String,
        book: WorldBook,
        targets: List<WorldBookEntry>,
        research: ResearchBrief?
    ): String = buildJsonObject {
        put("request", request.trim())
        put("currentBook", book.promptSummary())
        put("targets", buildJsonArray {
            targets.forEach { entry ->
                add(buildJsonObject {
                    put("targetId", entry.id)
                    put("name", entry.name)
                    put("keys", json.encodeToJsonElement(entry.keys))
                })
            }
        })
        research?.takeIf(ResearchBrief::hasContent)?.let {
            put("externalResearch", it.toWorldBookPromptJson())
        }
    }.toString()

    private fun ResearchBrief.toWorldBookPromptJson() = buildJsonObject {
        put("facts", json.encodeToJsonElement(facts))
        put("notes", json.encodeToJsonElement(notes))
    }

    private fun parseCreateResponse(raw: String): CreateBatchResponse? =
        raw.extractJsonObjectCandidates().firstNotNullOfOrNull { candidate ->
            runCatching { json.decodeFromString(CreateBatchResponse.serializer(), candidate) }.getOrNull()
        }

    private fun parseFillResponse(
        raw: String,
        targets: List<WorldBookEntry>
    ): FillConstraintResult? {
        val response = raw.extractJsonObjectCandidates().firstNotNullOfOrNull { candidate ->
            runCatching { json.decodeFromString(FillBatchResponse.serializer(), candidate) }.getOrNull()
        } ?: return null
        return constrainFilledEntries(targets, response.entries)
    }

    companion object {
        internal fun constrainCreatedEntries(
            book: WorldBook,
            completed: List<WorldBookEntryPlanCandidate>,
            rawEntries: List<CreateEntryResponse>,
            remaining: Int,
            idFactory: () -> String = { UUID.randomUUID().toString() }
        ): List<WorldBookEntryPlanCandidate> {
            val usedNames = (book.entries.map { it.name } + completed.map { it.name })
                .map(String::entryIdentity)
                .filter(String::isNotBlank)
                .toMutableSet()
            val usedKeySets = (book.entries.map { it.keys } + completed.map { it.keys })
                .map(List<String>::normalizedKeySet)
                .filter(Set<String>::isNotEmpty)
                .toMutableSet()
            val result = mutableListOf<WorldBookEntryPlanCandidate>()
            rawEntries.take(minOf(WORLD_BOOK_AI_BATCH_SIZE, remaining)).forEach { raw ->
                val name = raw.name.trim()
                val keys = raw.keys.map(String::trim).filter(String::isNotBlank)
                    .distinctBy { it.lowercase() }.take(12)
                val nameKey = name.entryIdentity()
                val keySet = keys.normalizedKeySet()
                if (nameKey.isBlank() || keys.isEmpty()) return@forEach
                if (!usedNames.add(nameKey)) return@forEach
                if (keySet.isNotEmpty() && !usedKeySets.add(keySet)) return@forEach
                result += WorldBookEntryPlanCandidate(idFactory(), name, keys)
            }
            return result
        }

        internal fun constrainFilledEntries(
            targets: List<WorldBookEntry>,
            rawEntries: List<FillEntryResponse>
        ): FillConstraintResult {
            val targetsById = targets.associateBy(WorldBookEntry::id)
            val seen = mutableSetOf<String>()
            val rejections = mutableListOf<String>()
            val constrained = rawEntries.mapNotNull { item ->
                val target = targetsById[item.targetId]
                if (target == null) {
                    rejections += "已丢弃未知目标 ID：${item.targetId.take(80)}"
                    return@mapNotNull null
                }
                if (target.content.isNotBlank()) {
                    rejections += "已丢弃正文非空目标：${item.targetId}"
                    return@mapNotNull null
                }
                if (!seen.add(item.targetId)) {
                    rejections += "已丢弃重复目标：${item.targetId}"
                    return@mapNotNull null
                }
                if (item.content.isBlank()) {
                    rejections += "已丢弃空正文结果：${item.targetId}"
                    return@mapNotNull null
                }
                WorldBookContentCandidate(
                    targetId = target.id,
                    name = target.name,
                    keys = target.keys,
                    content = item.content.trim()
                )
            }
            return FillConstraintResult(constrained, rejections)
        }

        internal fun decideCreateContinuation(
            responseHasMore: Boolean,
            newCandidateCount: Int,
            totalCandidateCount: Int
        ): CreateContinuationDecision = when {
            !responseHasMore -> CreateContinuationDecision(false)
            totalCandidateCount >= WORLD_BOOK_AI_CREATE_LIMIT -> CreateContinuationDecision(
                false,
                "已达到单次创建 50 条上限"
            )
            newCandidateCount == 0 -> CreateContinuationDecision(
                false,
                "AI 表示仍有条目，但本批没有产生新的有效候选，已停止以防重复循环"
            )
            else -> CreateContinuationDecision(true)
        }

        fun applyCreatedEntries(
            current: List<WorldBookEntry>,
            candidates: List<WorldBookEntryPlanCandidate>,
            selectedIds: Set<String>,
            idFactory: () -> String = { UUID.randomUUID().toString() }
        ): List<WorldBookEntry> = current + candidates
            .filter { it.candidateId in selectedIds }
            .map { candidate ->
                WorldBookEntry(
                    id = idFactory(),
                    name = candidate.name,
                    keys = candidate.keys,
                    content = ""
                )
            }

        fun applyFilledContents(
            current: List<WorldBookEntry>,
            candidates: List<WorldBookContentCandidate>,
            selectedIds: Set<String>
        ): List<WorldBookEntry> {
            val contentById = candidates
                .filter { it.targetId in selectedIds && it.content.isNotBlank() }
                .associate { it.targetId to it.content.trim() }
            return current.map { entry ->
                val content = contentById[entry.id]
                if (content != null && entry.content.isBlank()) entry.copy(content = content) else entry
            }
        }
    }
}

internal fun isWorldBookAiCheckpointCompatible(
    checkpointSignature: String,
    currentSignature: String
): Boolean = checkpointSignature.isNotBlank() && checkpointSignature == currentSignature

private fun List<WorldBookAiRawOutput>.upsertRawOutput(
    key: String,
    title: String,
    text: String
): List<WorldBookAiRawOutput> {
    val replacement = WorldBookAiRawOutput(key, title, text)
    val index = indexOfFirst { it.key == key }
    if (index < 0) return this + replacement
    return toMutableList().also { it[index] = replacement }
}

private fun WorldBook.promptSummary(): String = buildString {
    appendLine("名称：${name.ifBlank { "（未命名）" }}")
    description.trim().takeIf(String::isNotBlank)?.let { appendLine("描述：${it.take(1_000)}") }
    entries.take(200).forEachIndexed { index, entry ->
        append("${index + 1}. ${entry.name.ifBlank { "未命名" }}")
        if (entry.keys.isNotEmpty()) append("｜${entry.keys.joinToString("、")}")
        if (entry.content.isNotBlank()) append("｜已有正文：${entry.content.take(240)}")
        appendLine()
    }
}.trim().take(30_000)

private fun WorldBookEntry.planSummaryJson() = buildJsonObject {
    put("name", name)
    put("keys", buildJsonArray { keys.forEach { add(JsonPrimitive(it)) } })
}

private fun String.entryIdentity(): String = trim().lowercase().replace(Regex("\\s+"), "")

private fun List<String>.normalizedKeySet(): Set<String> =
    map { it.trim().lowercase() }.filter(String::isNotBlank).toSet()
