package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryCompressionKind
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.chat.ModelRequestException
import com.example.chatbar.domain.chat.ModelResponseTruncatedException
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EpisodeResponse(
    val summary: String
)

@Serializable
data class CompressionResponse(
    val compressible: Boolean,
    val consumedChildIds: List<String> = emptyList(),
    val summary: String = ""
)

@Serializable
data class HeadResponse(
    val throughT: Long,
    val location: String = "",
    val participants: String = "",
    val relationships: String = "",
    val goals: String = "",
    val unresolved: String = "",
    val worldState: String = ""
)

fun HeadResponse.hasContent(): Boolean = listOf(
    location,
    participants,
    relationships,
    goals,
    unresolved,
    worldState
).any { it.isNotBlank() }

internal const val MEMORY_AI_MAX_ATTEMPTS = 5
internal const val MEMORY_AI_MAX_TRANSPORT_ATTEMPTS = 3
internal const val MEMORY_COMPRESSION_PLANNER_MAX_TOKENS = 128

internal enum class MemoryAiTaskStage(val displayName: String) {
    EPISODE("近期流程生成"),
    COMPRESSION_PLANNING("压缩规划"),
    COMPRESSION_SUMMARY("正式压缩"),
    HEAD("HEAD生成")
}

internal enum class MemoryAiFailureKind {
    OUTPUT,
    TRANSPORT,
    NON_RETRYABLE_REQUEST
}

internal class MemoryAiRetryException(
    val taskStage: MemoryAiTaskStage,
    val failureKind: MemoryAiFailureKind,
    val attemptCount: Int,
    val lastFailure: Throwable
) : IllegalStateException(
    when (failureKind) {
        MemoryAiFailureKind.OUTPUT ->
            "${taskStage.displayName}：输出连续${attemptCount}次失败；最后错误：${lastFailure.message ?: lastFailure::class.simpleName}"
        MemoryAiFailureKind.TRANSPORT ->
            "${taskStage.displayName}：请求连续${attemptCount}次失败；最后错误：${lastFailure.message ?: lastFailure::class.simpleName}"
        MemoryAiFailureKind.NON_RETRYABLE_REQUEST ->
            "${taskStage.displayName}：第${attemptCount}次请求失败且不可重试；错误：${lastFailure.message ?: lastFailure::class.simpleName}"
    },
    lastFailure
)

internal class MemoryOutputTokenBudget(
    initial: Int,
    modelMaxOutputTokens: Int?
) {
    private val cap = minOf(4096, modelMaxOutputTokens ?: 4096)
    var current: Int = initial.coerceAtMost(cap)
        private set

    fun expandAfterTruncation() {
        current = (current * 2).coerceAtMost(cap)
    }
}

internal fun shouldDisableMemoryThinking(model: ModelConfig): Boolean =
    model.supportsDisableThinking || model.baseUrl.contains("siliconflow", ignoreCase = true)

internal fun ModelConfig.forMemoryCompressionPlanner(): ModelConfig = copy(
    reasoningEffort = null,
    enableThinking = null
)

internal interface MemoryAiClient {
    suspend fun episode(
        model: ModelConfig,
        renderedTurns: String,
        summaryPromptMaxChars: Int,
        onStreamingSummary: ((String) -> Unit)? = null,
        validate: (EpisodeResponse) -> Unit
    ): EpisodeResponse

    suspend fun compression(
        model: ModelConfig,
        kind: MemoryCompressionKind,
        forcedConsumedChildIds: List<String>,
        renderedChildren: String,
        onStreamingSummary: ((String) -> Unit)? = null,
        validate: (CompressionResponse) -> Unit
    ): CompressionResponse

    suspend fun head(
        model: ModelConfig,
        mode: MemoryHeadUpdateMode,
        throughT: Long,
        currentHead: String,
        archive: String,
        sourceTurns: String,
        validate: (HeadResponse) -> Unit
    ): HeadResponse
}

internal suspend fun <T> retryMemoryAiOutput(
    maxAttempts: Int,
    taskStage: MemoryAiTaskStage,
    request: suspend (attempt: Int, lastError: Throwable?) -> T
): T {
    require(maxAttempts > 0) { "长期记忆AI最大尝试次数必须大于0" }
    var lastError: Throwable? = null
    var validationAttempt = 0
    var transportAttempt = 0
    while (validationAttempt < maxAttempts) {
        try {
            return request(validationAttempt, lastError)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error is ModelRequestException) {
                transportAttempt++
                if (!error.isRetryable) {
                    throw MemoryAiRetryException(
                        taskStage = taskStage,
                        failureKind = MemoryAiFailureKind.NON_RETRYABLE_REQUEST,
                        attemptCount = transportAttempt,
                        lastFailure = error
                    )
                }
                if (transportAttempt >= MEMORY_AI_MAX_TRANSPORT_ATTEMPTS) {
                    throw MemoryAiRetryException(
                        taskStage = taskStage,
                        failureKind = MemoryAiFailureKind.TRANSPORT,
                        attemptCount = transportAttempt,
                        lastFailure = error
                    )
                }
                delay(error.retryAfterMillis ?: listOf(1_000L, 2_000L, 4_000L)[transportAttempt - 1])
                continue
            }
            lastError = error
            validationAttempt++
            if (validationAttempt >= maxAttempts) {
                throw MemoryAiRetryException(
                    taskStage = taskStage,
                    failureKind = MemoryAiFailureKind.OUTPUT,
                    attemptCount = validationAttempt,
                    lastFailure = error
                )
            }
        }
    }
    error("长期记忆AI重试状态异常")
}

class MemoryAiGateway(private val chatService: StreamingChatService) : MemoryAiClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun episode(
        model: ModelConfig,
        renderedTurns: String,
        summaryPromptMaxChars: Int,
        onStreamingSummary: ((String) -> Unit)?,
        validate: (EpisodeResponse) -> Unit
    ): EpisodeResponse = requestJson(
        taskStage = MemoryAiTaskStage.EPISODE,
        serializer = EpisodeResponse.serializer(),
        model = model,
        basePrompt = PromptTemplates.memoryEpisodePrompt(renderedTurns, summaryPromptMaxChars),
        maxTokens = summaryPromptMaxChars * 2 + 128,
        onStreamingText = onStreamingSummary,
        validate = validate
    )

    override suspend fun compression(
        model: ModelConfig,
        kind: MemoryCompressionKind,
        forcedConsumedChildIds: List<String>,
        renderedChildren: String,
        onStreamingSummary: ((String) -> Unit)?,
        validate: (CompressionResponse) -> Unit
    ): CompressionResponse {
        val compressionPlan = requestCompressionPlan(
            model = model,
            kind = kind,
            forcedConsumedChildIds = forcedConsumedChildIds,
            renderedChildren = renderedChildren
        )
        return requestJson(
            taskStage = MemoryAiTaskStage.COMPRESSION_SUMMARY,
            serializer = CompressionResponse.serializer(),
            model = model,
            basePrompt = PromptTemplates.memoryCompressionPrompt(
                kind = kind.name,
                forcedConsumedChildIds = forcedConsumedChildIds,
                compressionPlan = compressionPlan,
                children = renderedChildren
            ),
            onStreamingText = onStreamingSummary,
            validate = validate
        )
    }

    override suspend fun head(
        model: ModelConfig,
        mode: MemoryHeadUpdateMode,
        throughT: Long,
        currentHead: String,
        archive: String,
        sourceTurns: String,
        validate: (HeadResponse) -> Unit
    ): HeadResponse = requestJson(
        taskStage = MemoryAiTaskStage.HEAD,
        serializer = HeadResponse.serializer(),
        model = model,
        basePrompt = PromptTemplates.memoryHeadPrompt(
            mode = mode.name,
            throughT = throughT,
            currentHead = currentHead,
            archive = archive,
            sourceTurns = sourceTurns
        ),
        validate = validate
    )

    private suspend fun requestCompressionPlan(
        model: ModelConfig,
        kind: MemoryCompressionKind,
        forcedConsumedChildIds: List<String>,
        renderedChildren: String
    ): String = retryMemoryAiOutput(
        maxAttempts = MEMORY_AI_MAX_ATTEMPTS,
        taskStage = MemoryAiTaskStage.COMPRESSION_PLANNING
    ) { _, _ ->
        val plannerModel = model.forMemoryCompressionPlanner()
        chatService.completeText(
            messages = listOf(
                ChatApiMessage.text(
                    "user",
                    PromptTemplates.memoryCompressionPlannerPrompt(
                        kind = kind.name,
                        forcedConsumedChildIds = forcedConsumedChildIds,
                        children = renderedChildren
                    )
                )
            ),
            modelConfig = plannerModel,
            maxTokens = MEMORY_COMPRESSION_PLANNER_MAX_TOKENS,
            disableThinking = shouldDisableMemoryThinking(plannerModel),
            isolatedTaskParameters = true,
            responseFormatJson = false
        ).trim()
    }

    private suspend fun <T> requestJson(
        taskStage: MemoryAiTaskStage,
        serializer: KSerializer<T>,
        model: ModelConfig,
        basePrompt: String,
        maxTokens: Int = 1800,
        onStreamingText: ((String) -> Unit)? = null,
        validate: (T) -> Unit
    ): T {
        val tokenBudget = MemoryOutputTokenBudget(maxTokens, model.maxOutputTokens)
        return retryMemoryAiOutput(
            maxAttempts = MEMORY_AI_MAX_ATTEMPTS,
            taskStage = taskStage
        ) { attempt, lastError ->
            val correction = if (attempt == 0) {
                ""
            } else {
                PromptTemplates.memoryJsonCorrectionPrompt(lastError?.message.orEmpty())
            }
            onStreamingText?.invoke("")
            val messages = listOf(ChatApiMessage.text("user", basePrompt + correction))
            val raw = try {
                if (onStreamingText == null) {
                    chatService.completeText(
                        messages = messages,
                        modelConfig = model,
                        maxTokens = tokenBudget.current,
                        disableThinking = shouldDisableMemoryThinking(model),
                        isolatedTaskParameters = true,
                        responseFormatJson = model.supportsJsonMode
                    )
                } else {
                    val streamed = StringBuilder()
                    chatService.completeTextStreaming(
                        messages = messages,
                        modelConfig = model,
                        maxTokens = tokenBudget.current,
                        disableThinking = shouldDisableMemoryThinking(model),
                        isolatedTaskParameters = true,
                        responseFormatJson = model.supportsJsonMode,
                        onDelta = { chunk ->
                            streamed.append(chunk)
                            extractStreamingJsonString(streamed.toString(), "summary")
                                ?.let(onStreamingText)
                        }
                    )
                }
            } catch (error: ModelResponseTruncatedException) {
                tokenBudget.expandAfterTruncation()
                throw error
            }
            val candidate = extractFirstJsonObject(raw) ?: error("AI未返回JSON对象")
            val decoded = json.decodeFromString(serializer, candidate)
            validate(decoded)
            decoded
        }
    }
}

/** 从尚未闭合的JSON字符串字段中提取当前可见文本，供流式预览。 */
internal fun extractStreamingJsonString(raw: String, key: String): String? {
    val keyIndex = raw.indexOf("\"$key\"")
    if (keyIndex < 0) return null
    var index = keyIndex + key.length + 2
    while (index < raw.length && raw[index].isWhitespace()) index++
    if (index >= raw.length || raw[index] != ':') return null
    index++
    while (index < raw.length && raw[index].isWhitespace()) index++
    if (index >= raw.length || raw[index] != '"') return null
    index++
    val result = StringBuilder()
    while (index < raw.length) {
        val char = raw[index]
        when (char) {
            '"' -> return result.toString()
            '\\' -> {
                if (index + 1 >= raw.length) return result.toString()
                when (val escaped = raw[index + 1]) {
                    '"', '\\', '/' -> result.append(escaped)
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000C')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> {
                        if (index + 5 >= raw.length) return result.toString()
                        val code = raw.substring(index + 2, index + 6).toIntOrNull(16)
                            ?: return result.toString()
                        result.append(code.toChar())
                        index += 4
                    }
                    else -> result.append(escaped)
                }
                index++
            }
            else -> result.append(char)
        }
        index++
    }
    return result.toString()
}

internal fun extractFirstJsonObject(raw: String): String? {
    val start = raw.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until raw.length) {
        val char = raw[index]
        when {
            escaped -> escaped = false
            char == '\\' && inString -> escaped = true
            char == '"' -> inString = !inString
            !inString && char == '{' -> depth++
            !inString && char == '}' -> {
                depth--
                if (depth == 0) return raw.substring(start, index + 1)
            }
        }
    }
    return null
}
