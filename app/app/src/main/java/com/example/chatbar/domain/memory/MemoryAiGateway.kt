package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryCompressionKind
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EpisodeResponse(
    val summary: String
)

@Serializable
data class ChildCoverageResponse(
    val childId: String,
    val text: String
)

@Serializable
data class CompressionResponse(
    val compressible: Boolean,
    val consumedChildIds: List<String> = emptyList(),
    val summary: String = "",
    val childCoverage: List<ChildCoverageResponse> = emptyList()
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

internal suspend fun <T> retryMemoryAiOutput(
    maxAttempts: Int,
    request: suspend (attempt: Int, lastError: Throwable?) -> T
): T {
    require(maxAttempts > 0) { "长期记忆AI最大尝试次数必须大于0" }
    var lastError: Throwable? = null
    repeat(maxAttempts) { attempt ->
        try {
            return request(attempt, lastError)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            lastError = error
        }
    }
    throw IllegalStateException(
        "长期记忆AI输出连续${maxAttempts}次非法：${lastError?.message}",
        lastError
    )
}

class MemoryAiGateway(private val chatService: StreamingChatService) {
    private val json = Json { ignoreUnknownKeys = false }

    suspend fun episode(
        model: ModelConfig,
        renderedTurns: String,
        summaryMaxChars: Int,
        onStreamingSummary: ((String) -> Unit)? = null,
        validate: (EpisodeResponse) -> Unit
    ): EpisodeResponse = requestJson(
        serializer = EpisodeResponse.serializer(),
        model = model,
        basePrompt = PromptTemplates.memoryEpisodePrompt(renderedTurns, summaryMaxChars),
        maxTokens = summaryMaxChars * 2 + 128,
        onStreamingText = onStreamingSummary,
        validate = validate
    )

    suspend fun compression(
        model: ModelConfig,
        kind: MemoryCompressionKind,
        forcedConsumedChildIds: List<String>,
        renderedChildren: String,
        onStreamingSummary: ((String) -> Unit)? = null,
        validate: (CompressionResponse) -> Unit
    ): CompressionResponse = requestJson(
        serializer = CompressionResponse.serializer(),
        model = model,
        basePrompt = PromptTemplates.memoryCompressionPrompt(
            kind = kind.name,
            forcedConsumedChildIds = forcedConsumedChildIds,
            children = renderedChildren
        ),
        onStreamingText = onStreamingSummary,
        validate = validate
    )

    suspend fun head(
        model: ModelConfig,
        mode: MemoryHeadUpdateMode,
        throughT: Long,
        currentHead: String,
        archive: String,
        sourceTurns: String,
        validate: (HeadResponse) -> Unit
    ): HeadResponse = requestJson(
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

    private suspend fun <T> requestJson(
        serializer: KSerializer<T>,
        model: ModelConfig,
        basePrompt: String,
        maxTokens: Int = 1800,
        onStreamingText: ((String) -> Unit)? = null,
        validate: (T) -> Unit
    ): T = retryMemoryAiOutput(MEMORY_AI_MAX_ATTEMPTS) { attempt, lastError ->
        val correction = if (attempt == 0) {
            ""
        } else {
            "\n\n上次输出校验失败：${lastError?.message.orEmpty()}\n请修正并重新输出完整JSON。"
        }
        onStreamingText?.invoke("")
        val messages = listOf(ChatApiMessage.text("user", basePrompt + correction))
        val raw = if (onStreamingText == null) {
            chatService.completeText(
                messages = messages,
                modelConfig = model,
                maxTokens = maxTokens,
                thinkingBudget = 512
            )
        } else {
            val streamed = StringBuilder()
            chatService.completeTextStreaming(
                messages = messages,
                modelConfig = model,
                maxTokens = maxTokens,
                thinkingBudget = 512,
                onDelta = { chunk ->
                    streamed.append(chunk)
                    extractStreamingJsonString(streamed.toString(), "summary")
                        ?.let(onStreamingText)
                }
            )
        }
        val candidate = extractFirstJsonObject(raw) ?: error("AI未返回JSON对象")
        val decoded = json.decodeFromString(serializer, candidate)
        validate(decoded)
        decoded
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
