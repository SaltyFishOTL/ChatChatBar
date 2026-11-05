package com.example.chatbar.domain.prompt

import com.example.chatbar.BuildConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.ModelRequestException
import com.example.chatbar.domain.chat.ModelResponseTruncatedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import com.example.chatbar.utils.DebugLogManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

enum class AiTaskKind {
    CHARACTER_FILL, CHARACTER_REWRITE, CHARACTER_APPEARANCE, FORMAT_CARD,
    WORLD_BOOK_CREATE, WORLD_BOOK_FILL, IMAGE_DESCRIPTION, IMAGE_DESIGN, IMAGE_RESEARCH,
    MOMENT_JUDGE, MOMENT_GENERATION, MEMORY_EPISODE, MEMORY_COMPRESSION_PLAN,
    MEMORY_COMPRESSION, MEMORY_HEAD, RETRIEVAL_PLAN, CHARACTER_RESEARCH, CHARACTER_BRIEF,
    WORLD_BOOK_RESEARCH, WORLD_BOOK_BRIEF, VOICE_TRANSLATION, VOICE_TAGS, FORMAT_REPAIR, IMAGE_JUDGE
}

enum class AiTaskStage { GENERATE, REPAIR, PLAN, JUDGE, TRANSLATE, TAG, SUMMARIZE }

data class AiTaskPromptProfile(val name: String, val boundary: String, val templateSymbols: List<String>)

/** A logical operation owns its requests, including nested research and repair calls. */
class AiTaskRun(val taskId: String = UUID.randomUUID().toString()) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AiTaskRun>
}

suspend fun aiTaskRunContext(): AiTaskRun = currentCoroutineContext()[AiTaskRun] ?: AiTaskRun()

suspend fun <T> withAiTaskRun(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> T
): T {
    val run = aiTaskRunContext()
    return withContext(context + run) {
        try {
            block()
        } catch (error: Throwable) {
            DebugLogManager.recordTaskFailure(run.taskId, error)
            throw error
        }
    }
}

data class AiTaskContext(
    val kind: AiTaskKind,
    val stage: AiTaskStage = AiTaskStage.GENERATE,
    val taskId: String = UUID.randomUUID().toString(),
    val requestId: String = UUID.randomUUID().toString()
) {
    val profile: AiTaskPromptProfile get() = PromptTemplates.aiTaskProfile(kind, stage)
    val preservesInputRoles: Boolean get() = kind == AiTaskKind.IMAGE_DESIGN && stage == AiTaskStage.GENERATE
    val templateFingerprint: String get() = templateFingerprint(BuildConfig.AI_PROMPT_SOURCE_SHA256, kind, stage)

    // Resolve at collection time: collecting a cold Flow twice represents two billable requests.
    suspend fun forRequest(): AiTaskContext = copy(
        taskId = currentCoroutineContext()[AiTaskRun]?.taskId ?: taskId,
        requestId = UUID.randomUUID().toString()
    )

    companion object {
        fun templateFingerprint(source: String, kind: AiTaskKind, stage: AiTaskStage): String =
            MessageDigest.getInstance("SHA-256").digest("$source:${kind.name}:${stage.name}".toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)
    }
}

object AiTaskMessageAssembler {
    fun assemble(messages: List<ChatApiMessage>, context: AiTaskContext): List<ChatApiMessage> {
        require(messages.isNotEmpty()) { "AI 任务没有输入" }
        val prefix = listOf(
            ChatApiMessage.text("assistant", PromptTemplates.GENERAL_FIRST_ACK_ASSISTANT_PROMPT),
            ChatApiMessage.text("user", PromptTemplates.GENERAL_CREATIVE_CONTRACT_USER_PROMPT),
            ChatApiMessage.text("assistant", PromptTemplates.GENERAL_CONTRACT_CONFIRMATION_ASSISTANT_PROMPT)
        )
        val tail = listOf(
            ChatApiMessage.text("assistant", PromptTemplates.GENERAL_CONTEXT_APPROVAL_ASSISTANT_PROMPT),
            ChatApiMessage.text("assistant", PromptTemplates.GENERAL_POST_USER_ACK_ASSISTANT_PROMPT)
        )
        // Check the entire envelope, not phrases appearing inside ordinary input.
        if (messages.size >= 7 && messages.first().role == "system" &&
            messages.subList(4, messages.size - 2).all { it.role == "user" || it.role == "assistant" } &&
            messages.subList(1, 4) == prefix && messages.takeLast(2) == tail &&
            systemHasEnvelope(messages.first().content)
        ) return messages
        val system = mergeContents(
            listOf(JsonPrimitive(PromptTemplates.GENERAL_SYSTEM_PROMPT)) +
                messages.filter { it.role == "system" }.map { it.content } +
                JsonPrimitive(PromptTemplates.GENERAL_CREATOR_IDENTITY_SYSTEM_PROMPT)
        )
        val inputs = messages.filter { it.role != "system" }
        // Image design continues real planner/revision history instead of quoting it inside a user input.
        if (context.preservesInputRoles && inputs.isNotEmpty()) {
            return listOf(ChatApiMessage("system", system)) + prefix + inputs + tail
        }
        val input = when {
            inputs.isEmpty() -> JsonPrimitive(PromptTemplates.GENERAL_TASK_EMPTY_INPUT)
            inputs.size == 1 && inputs.single().role == "user" -> inputs.single().content
            else -> mergeContents(inputs.flatMapIndexed { index, message ->
                listOf(JsonPrimitive(PromptTemplates.generalTaskInputHeading(index, message.role)), message.content)
            })
        }
        return listOf(ChatApiMessage("system", system)) + prefix + ChatApiMessage("user", input) + tail
    }

    private fun systemHasEnvelope(content: JsonElement): Boolean {
        val first: String?
        val last: String?
        if (content is JsonArray) {
            first = ((content.firstOrNull() as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
            last = ((content.lastOrNull() as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
        } else {
            first = (content as? JsonPrimitive)?.contentOrNull
            last = first
        }
        return first?.startsWith(PromptTemplates.GENERAL_SYSTEM_PROMPT) == true &&
            last?.endsWith(PromptTemplates.GENERAL_CREATOR_IDENTITY_SYSTEM_PROMPT) == true
    }

    private fun mergeContents(contents: List<JsonElement>): JsonElement {
        if (contents.all { it is JsonPrimitive }) {
            return JsonPrimitive(contents.joinToString("\n\n") { (it as JsonPrimitive).content })
        }
        return JsonArray(contents.flatMap { content ->
            when (content) {
                is JsonArray -> content.toList()
                is JsonPrimitive -> listOf(buildJsonObject {
                    put("type", "text")
                    put("text", content.content)
                })
                else -> listOf(content)
            }
        })
    }

    fun addedText(messages: List<ChatApiMessage>, context: AiTaskContext? = null): String = buildList {
        add(PromptTemplates.GENERAL_SYSTEM_PROMPT)
        add(PromptTemplates.GENERAL_CREATOR_IDENTITY_SYSTEM_PROMPT)
        add(PromptTemplates.GENERAL_FIRST_ACK_ASSISTANT_PROMPT)
        add(PromptTemplates.GENERAL_CREATIVE_CONTRACT_USER_PROMPT)
        add(PromptTemplates.GENERAL_CONTRACT_CONFIRMATION_ASSISTANT_PROMPT)
        add(PromptTemplates.GENERAL_CONTEXT_APPROVAL_ASSISTANT_PROMPT)
        add(PromptTemplates.GENERAL_POST_USER_ACK_ASSISTANT_PROMPT)
        val inputs = messages.filter { it.role != "system" }
        if (inputs.isEmpty()) add(PromptTemplates.GENERAL_TASK_EMPTY_INPUT)
        else if (context?.preservesInputRoles != true && (inputs.size != 1 || inputs.single().role != "user")) {
            inputs.forEachIndexed { index, message -> add(PromptTemplates.generalTaskInputHeading(index, message.role)) }
        }
    }.joinToString("\n")
}

enum class AiTaskFailureKind { REFUSAL, CONTENT_FILTER, FORMAT, TRUNCATED, NETWORK, AUTHENTICATION, REQUEST, CANCELLED, EMPTY }

class AiTaskEmptyResponseException(reasoningOnly: Boolean) :
    RuntimeException(if (reasoningOnly) "AI 仅返回思考内容，没有任务结果" else "AI 返回空内容")

class AiTaskRefusalException(val kind: AiTaskFailureKind, val finishReason: String? = null) :
    RuntimeException(if (kind == AiTaskFailureKind.CONTENT_FILTER) "模型服务过滤了本次输出，已停止后续修复和重试" else "模型拒绝了本次任务，已停止后续修复和重试")

/** Terminal evidence must survive caller wrappers and cannot enter a success-looking fallback. */
fun Throwable.rethrowIfAiTaskTerminalFailure() {
    var error: Throwable? = this
    val visited = mutableSetOf<Throwable>()
    while (error != null && visited.add(error)) {
        if (error is CancellationException || error is AiTaskRefusalException) throw error
        error = error.cause
    }
}

fun Throwable.aiTaskFailureKind(): AiTaskFailureKind = when (this) {
    is AiTaskRefusalException -> kind
    is CancellationException -> AiTaskFailureKind.CANCELLED
    is ModelResponseTruncatedException -> AiTaskFailureKind.TRUNCATED
    is AiTaskEmptyResponseException -> AiTaskFailureKind.EMPTY
    is ModelRequestException -> when {
        isAuthenticationFailure -> AiTaskFailureKind.AUTHENTICATION
        isRetryable -> AiTaskFailureKind.NETWORK
        else -> AiTaskFailureKind.REQUEST
    }
    is java.io.IOException -> AiTaskFailureKind.NETWORK
    else -> AiTaskFailureKind.FORMAT
}

object AiTaskRefusalPolicy {
    fun failure(content: String, finishReason: String?, refused: Boolean): AiTaskRefusalException? = when {
        finishReason == "content_filter" -> AiTaskRefusalException(AiTaskFailureKind.CONTENT_FILTER, finishReason)
        refused -> AiTaskRefusalException(AiTaskFailureKind.REFUSAL, finishReason)
        isStandaloneRefusal(content) -> AiTaskRefusalException(AiTaskFailureKind.REFUSAL, finishReason)
        else -> null
    }

    /** Deliberately conservative: JSON, quotes, dialogue and mixed payloads are not keyword-scanned. */
    internal fun isStandaloneRefusal(content: String): Boolean {
        val text = content.trim()
        if (text.isEmpty() || text.length > 500 || text.contains('\n') || text.contains('"') ||
            text.contains('“') || text.contains('「') || text.contains('：') || text.contains('`') ||
            text.contains('{') || text.contains('[')
        ) return false
        if (runCatching { Json.parseToJsonElement(text) }.getOrNull() is JsonObject) return false
        return Regex("^(?:抱歉[，,。 ]*|对不起[，,。 ]*)?我(?:无法|不能)(?:帮助|协助|提供|生成|完成|处理|满足|继续).{0,180}(?:请求|内容|任务|要求|生成|创作)[。！.! ]*$")
            .matches(text) || Regex("^(?:I(?:'m| am) sorry[, .]*|Sorry[, .]*)?I (?:cannot|can't|am unable to) (?:help|assist|provide|generate|fulfill|comply with).{0,180}(?:request|content|task)[.! ]*$", RegexOption.IGNORE_CASE)
            .matches(text)
    }

    fun responseRefused(body: String): Boolean = runCatching {
        val root = Json.parseToJsonElement(body) as? JsonObject
        val choice = (root?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject
        val hasRefusal = !(message?.get("refusal") as? JsonPrimitive)?.contentOrNull.isNullOrBlank()
        hasRefusal ||
            (message?.get("content") as? JsonArray)?.any {
                ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "refusal"
            } == true
    }.getOrDefault(false)
}
