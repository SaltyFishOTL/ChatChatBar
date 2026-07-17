package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ParamValue
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.addModelApiAuthorization
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val PARAM_ENABLE_THINKING = "enable_thinking"
private const val PARAM_THINKING_BUDGET = "thinking_budget"
private const val PARAM_MAX_THINKING_TOKENS = "max_thinking_tokens"
private const val PARAM_REASONING_EFFORT = "reasoning_effort"

// ========================= 数据模型 =========================

/**
 * SSE 流事件
 */
sealed class StreamEvent {
    /** 增量文本片段 */
    data class Delta(val text: String) : StreamEvent()

    /** 增量思维链片段 */
    data class ReasoningDelta(val text: String) : StreamEvent()

    /** 供应商在流结束前返回的真实输入与提示词缓存计量。 */
    data class Usage(val usage: PromptCacheUsage) : StreamEvent()

    /** 错误 */
    data class Error(val message: String) : StreamEvent()

    /** 流结束 */
    data object Done : StreamEvent()
}

data class PromptCacheUsage(
    val promptTokens: Int? = null,
    val cachedTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val cacheMissTokens: Int? = null
)

/**
 * 发送给 API 的消息格式
 *
 * [content] 为 JsonElement 类型以支持多模态：
 * - 纯文本: JsonPrimitive("text")
 * - 多模态: JsonArray of content parts
 */
@Serializable
data class ChatApiMessage(
    val role: String,
    val content: JsonElement
) {
    companion object {
        /** 创建纯文本消息 */
        fun text(role: String, content: String) = ChatApiMessage(
            role = role,
            content = JsonPrimitive(content)
        )

        /** 创建带图片的多模态消息 */
        fun withImage(role: String, text: String, imageBase64: String) = ChatApiMessage(
            role = role,
            content = multimodalContent(text, listOf(imageBase64))
        )

        fun withImages(role: String, text: String, imageBase64s: List<String>) = ChatApiMessage(
            role = role,
            content = multimodalContent(text, imageBase64s)
        )

        private fun multimodalContent(text: String, imageBase64s: List<String>) =
            buildJsonArray {
                text.takeIf(String::isNotBlank)?.let { nonBlankText ->
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", nonBlankText)
                    })
                }
                imageBase64s.forEach { imageBase64 ->
                    if (imageBase64.isNotBlank()) {
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:image/jpeg;base64,$imageBase64")
                            })
                        })
                    }
                }
            }
    }
}

// ========================= 服务 =========================

/**
 * 流式聊天服务 — 通过 OkHttp SSE 与 OpenAI 兼容 API 通信
 *
 * 请求格式:
 * POST {baseUrl}/chat/completions
 * {"model": "...", "messages": [...], "stream": true, ...customParams}
 *
 * SSE 响应:
 * data: {"choices": [{"delta": {"content": "..."}}]}
 * data: [DONE]
 */
class StreamingChatService(
    allowCleartextHttp: () -> Boolean = { false }
) {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val CONNECT_TIMEOUT = 30L
        private const val READ_TIMEOUT = 120L // SSE 需要较长读取超时
        private const val IMAGE_DESCRIPTION_OUTPUT_TOKENS = 500
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val client = ProxyAwareClient.modelApiBuilder(allowCleartextHttp)
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .build()

    /**
     * 流式聊天补全
     *
     * @param sessionId    会话 ID
     * @param messages    消息列表
     * @param modelConfig 模型配置
     * @param systemPrompt 组装后的 System Prompt
     * @param ragChunks    RAG 召回的知识块列表
     * @return 包含 [StreamEvent] 的 Flow
     */
    fun streamChat(
        sessionId: String,
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig,
        systemPrompt: String = "",
        ragChunks: List<String> = emptyList(),
        promptCacheKey: String? = null
    ): Flow<StreamEvent> = callbackFlow {
        val maxRetries = 2
        var retryCount = 0
        var shouldStop = false

        val baseUrl = modelConfig.baseUrl.trimEnd('/')
        val url = "$baseUrl/chat/completions"
        val supportsOpenAiCacheInstrumentation = modelConfig.supportsOpenAiPromptCacheInstrumentation()
        val requestBody = buildRequestBody(
            messages = messages,
            modelConfig = modelConfig,
            stream = true,
            promptCacheKey = promptCacheKey.takeIf { supportsOpenAiCacheInstrumentation },
            includeStreamUsage = supportsOpenAiCacheInstrumentation
        )

        // 写入 Debug 日志开始记录（仅一次）
        com.example.chatbar.utils.DebugLogManager.startRequest(
            sessionId = sessionId,
            modelName = modelConfig.displayName,
            apiUrl = url,
            requestBodyJson = requestBody,
            systemPrompt = systemPrompt,
            ragChunks = ragChunks
        )

        while (!shouldStop && retryCount <= maxRetries) {
            var retrying = false
            val request = Request.Builder()
                .url(url)
                .addModelApiAuthorization(modelConfig.apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            suspendCancellableCoroutine { continuation ->
                var resumed = false
                val listener = object : EventSourceListener() {
                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String
                    ) {
                        if (data.trim() == "[DONE]") {
                            com.example.chatbar.utils.DebugLogManager.appendResponseChunk(sessionId, data)
                            com.example.chatbar.utils.DebugLogManager.completeRequest(sessionId)
                            trySend(StreamEvent.Done)
                            shouldStop = true
                            if (!resumed) {
                                resumed = true
                                continuation.resume(Unit)
                            }
                            return
                        }

                        try {
                            val delta = parseDelta(data)
                            com.example.chatbar.utils.DebugLogManager.appendResponseChunk(
                                sessionId = sessionId,
                                chunkData = data,
                                deltaText = delta.content,
                                reasoningText = delta.reasoningContent
                            )

                            if (delta.reasoningContent != null) {
                                trySend(StreamEvent.ReasoningDelta(delta.reasoningContent))
                            }
                            if (delta.content != null) {
                                trySend(StreamEvent.Delta(delta.content))
                            }
                            parsePromptCacheUsage(data)?.let { usage ->
                                com.example.chatbar.utils.DebugLogManager.recordPromptCacheUsage(sessionId, usage)
                                trySend(StreamEvent.Usage(usage))
                            }
                        } catch (e: Exception) {
                            com.example.chatbar.utils.DebugLogManager.appendResponseChunk(sessionId, data)
                            trySend(StreamEvent.Error("解析 SSE 数据失败: ${e.message}"))
                        }
                    }

                    override fun onFailure(
                        eventSource: EventSource,
                        t: Throwable?,
                        response: Response?
                    ) {
                        val body = try { response?.body?.string() } catch (_: Exception) { null }

                        if (retryCount < maxRetries && response?.code == 400 && body?.contains("20015") == true) {
                            retrying = true
                            retryCount++
                            com.example.chatbar.utils.DebugLogManager.appendResponseChunk(
                                sessionId,
                                "[RETRY #$retryCount] 服务器返回 400/20015，${retryCount}秒后重试..."
                            )
                            continuation.resume(Unit)
                            return
                        }

                        val errorMsg = buildString {
                            append("流式请求失败")
                            if (response != null) {
                                append(" (${response.code})")
                                if (!body.isNullOrBlank()) append(": $body")
                            }
                            if (t != null) {
                                append(" - ${t.message}")
                            }
                            if (retryCount > 0) append(" (已重试${retryCount}次)")
                        }
                        com.example.chatbar.utils.DebugLogManager.logError(sessionId, errorMsg)
                        trySend(StreamEvent.Error(errorMsg))
                        shouldStop = true
                        if (!resumed) {
                            resumed = true
                            continuation.resume(Unit)
                        }
                    }

                    override fun onClosed(eventSource: EventSource) {
                        if (!retrying) {
                            com.example.chatbar.utils.DebugLogManager.completeRequest(sessionId)
                            shouldStop = true
                        }
                        if (!resumed) {
                            resumed = true
                            continuation.resume(Unit)
                        }
                    }
                }

                val eventSource = EventSources.createFactory(client)
                    .newEventSource(request, listener)

                continuation.invokeOnCancellation {
                    eventSource.cancel()
                }
            }

            if (!shouldStop) {
                delay(retryCount * 1000L)
            }
        }

        close()
    }

    /** 流式短文本任务；默认不覆盖模型思考配置，完全遵循 ModelConfig。 */
    fun streamText(
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig,
        maxTokens: Int? = null,
        enableThinking: Boolean? = null,
        maxThinkingTokens: Int? = null,
        thinkingBudget: Int? = null,
        disableThinking: Boolean = false
    ): Flow<StreamEvent> = callbackFlow {
        val url = "${modelConfig.baseUrl.trimEnd('/')}/chat/completions"
        val requestBody = buildRequestBody(
            messages = messages,
            modelConfig = modelConfig,
            stream = true,
            maxTokens = maxTokens,
            enableThinkingOverride = enableThinking,
            maxThinkingTokens = maxThinkingTokens,
            thinkingBudget = thinkingBudget,
            disableThinking = disableThinking
        )
        val request = Request.Builder()
            .url(url)
            .addModelApiAuthorization(modelConfig.apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val listener = object : EventSourceListener() {
            private var closed = false

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data.trim() == "[DONE]") {
                    trySend(StreamEvent.Done)
                    if (!closed) { closed = true; close() }
                    return
                }
                val delta = parseDelta(data)
                delta.reasoningContent?.let { trySend(StreamEvent.ReasoningDelta(it)) }
                delta.content?.let { trySend(StreamEvent.Delta(it)) }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val message = buildString {
                    append("流式文本补全失败")
                    response?.let {
                        append(" (${it.code})")
                        runCatching { it.body?.string() }.getOrNull()
                            ?.takeIf(String::isNotBlank)
                            ?.let { body -> append(": ${body.take(2000)}") }
                    }
                    t?.let { append(" - ${it.message ?: it::class.java.simpleName}") }
                }
                trySend(StreamEvent.Error(message))
                if (!closed) { closed = true; close() }
            }

            override fun onClosed(eventSource: EventSource) {
                if (!closed) { closed = true; close() }
            }
        }
        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }

    /**
     * 使用视觉模型描述图片
     *
     * @param imageBase64 图片的 Base64 编码
     * @param modelConfig 视觉模型配置
     * @return 图片描述文本
     */
    suspend fun describeImage(
        imageBase64: String,
        modelConfig: ModelConfig
    ): String = suspendCancellableCoroutine { continuation ->
        val baseUrl = modelConfig.baseUrl.trimEnd('/')
        val url = "$baseUrl/chat/completions"

        val messages = listOf(
            ChatApiMessage.withImage(
                role = "user",
                text = PromptTemplates.IMAGE_DESCRIPTION_PROMPT,
                imageBase64 = imageBase64
            )
        )

        val requestBody = buildRequestBody(
            messages = messages,
            modelConfig = modelConfig.forImageDescriptionRequest(),
            stream = false,
            maxTokens = IMAGE_DESCRIPTION_OUTPUT_TOKENS
        )

        val request = Request.Builder()
            .url(url)
            .addModelApiAuthorization(modelConfig.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = client.newCall(request)

        continuation.invokeOnCancellation { call.cancel() }

        Thread {
            try {
                val response = call.execute()
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    continuation.resumeWithException(
                        RuntimeException("图片描述失败 (${response.code}): $body")
                    )
                    return@Thread
                }
                continuation.resume(compactImageDescription(parseNonStreamResponse(body)))
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        RuntimeException("图片描述请求失败: ${e.message ?: e::class.java.simpleName}", e)
                    )
                }
            }
        }.start()
    }

    suspend fun describeImageStreaming(
        imageBase64: String,
        modelConfig: ModelConfig,
        onDelta: (String) -> Unit = {}
    ): String {
        val raw = completeTextStreaming(
            messages = listOf(
                ChatApiMessage.withImage(
                    role = "user",
                    text = PromptTemplates.IMAGE_DESCRIPTION_PROMPT,
                    imageBase64 = imageBase64
                )
            ),
            modelConfig = modelConfig.forImageDescriptionRequest(),
            maxTokens = IMAGE_DESCRIPTION_OUTPUT_TOKENS,
            onDelta = onDelta
        )
        return compactImageDescription(raw)
    }

    /**
     * 非流式文本补全，用于短任务：RAG 联想判断、query planning、图片描述等。
     */
    suspend fun completeText(
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig,
        maxTokens: Int? = null,
        thinkingBudget: Int? = null,
        disableThinking: Boolean = false
    ): String = suspendCancellableCoroutine { continuation ->
        val baseUrl = modelConfig.baseUrl.trimEnd('/')
        val url = "$baseUrl/chat/completions"
        val requestBody = buildRequestBody(
            messages = messages,
            modelConfig = modelConfig,
            stream = false,
            maxTokens = maxTokens,
            thinkingBudget = thinkingBudget,
            disableThinking = disableThinking
        )

        val request = Request.Builder()
            .url(url)
            .addModelApiAuthorization(modelConfig.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        RuntimeException(
                            "文本补全请求失败: ${e.message ?: e::class.java.simpleName}",
                            e
                        )
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        continuation.resumeWithException(
                            RuntimeException("文本补全失败 (${response.code}): ${body.take(2000)}")
                        )
                        return
                    }
                    runCatching { parseNonStreamResponse(body) }
                        .onSuccess { content ->
                            if (!continuation.isActive) return@onSuccess
                            if (content.isBlank()) {
                                continuation.resumeWithException(
                                    RuntimeException("文本补全返回空内容。Raw body: ${body.take(2000)}")
                                )
                            } else {
                                continuation.resume(content)
                            }
                        }
                        .onFailure { error ->
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    RuntimeException(
                                        "文本补全响应解析失败: ${error.message ?: error::class.java.simpleName}",
                                        error
                                    )
                                )
                            }
                        }
                }
            }
        })
    }

    suspend fun completeTextStreaming(
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig,
        maxTokens: Int? = null,
        enableThinking: Boolean? = null,
        maxThinkingTokens: Int? = null,
        thinkingBudget: Int? = null,
        reasoningEffort: String? = null,
        onDelta: (String) -> Unit = {},
        onReasoningDelta: (String) -> Unit = {},
        disableThinking: Boolean = false
    ): String = suspendCancellableCoroutine { continuation ->
        val baseUrl = modelConfig.baseUrl.trimEnd('/')
        val url = "$baseUrl/chat/completions"
        val requestBody = buildRequestBody(
            messages = messages,
            modelConfig = modelConfig,
            stream = true,
            maxTokens = maxTokens,
            enableThinkingOverride = enableThinking,
            maxThinkingTokens = maxThinkingTokens,
            thinkingBudget = thinkingBudget,
            reasoningEffortOverride = reasoningEffort,
            disableThinking = disableThinking
        )

        val request = Request.Builder()
            .url(url)
            .addModelApiAuthorization(modelConfig.apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val text = StringBuilder()
        val lock = Any()
        var completed = false

        fun resumeSuccessIfActive() {
            synchronized(lock) {
                if (completed || !continuation.isActive) return
                completed = true
                val content = text.toString()
                if (content.isBlank()) {
                    continuation.resumeWithException(RuntimeException("流式文本补全返回空内容"))
                } else {
                    continuation.resume(content)
                }
            }
        }

        fun resumeFailureIfActive(error: Throwable) {
            synchronized(lock) {
                if (completed || !continuation.isActive) return
                completed = true
                continuation.resumeWithException(error)
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(
            request,
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    if (data.trim() == "[DONE]") {
                        resumeSuccessIfActive()
                        return
                    }
                    val delta = parseDelta(data)
                    delta.reasoningContent?.takeIf(String::isNotBlank)?.let(onReasoningDelta)
                    delta.content?.takeIf(String::isNotBlank)?.let { chunk ->
                        text.append(chunk)
                        onDelta(chunk)
                    }
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    val body = try { response?.body?.string() } catch (_: Exception) { null }
                    val message = buildString {
                        append("流式文本补全失败")
                        if (response != null) {
                            append(" (${response.code})")
                            if (!body.isNullOrBlank()) append(": ${body.take(2000)}")
                        }
                        if (t != null) append(" - ${t.message ?: t::class.java.simpleName}")
                    }
                    resumeFailureIfActive(RuntimeException(message, t))
                }

                override fun onClosed(eventSource: EventSource) {
                    resumeSuccessIfActive()
                }
            }
        )
        continuation.invokeOnCancellation { eventSource.cancel() }
    }

    // ========================= 内部方法 =========================

    /** 构建请求 JSON body */
    internal fun buildRequestBody(
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig,
        stream: Boolean,
        maxTokens: Int? = null,
        enableThinkingOverride: Boolean? = null,
        maxThinkingTokens: Int? = null,
        thinkingBudget: Int? = null,
        reasoningEffortOverride: String? = null,
        promptCacheKey: String? = null,
        includeStreamUsage: Boolean = false,
        disableThinking: Boolean = false
    ): String {
        val messagesArray = buildJsonArray {
            for (msg in messages) {
                add(buildJsonObject {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
        }

        val bodyObj = buildJsonObject {
            put("model", modelConfig.modelName)
            put("messages", messagesArray)
            put("stream", stream)
            promptCacheKey?.takeIf(String::isNotBlank)?.let { put("prompt_cache_key", it) }
            if (stream && includeStreamUsage) {
                put("stream_options", buildJsonObject { put("include_usage", true) })
            }

            // 追加自定义参数
            for ((key, value) in modelConfig.customParams) {
                if (disableThinking && key in THINKING_PARAMETER_KEYS) continue
                when (value) {
                    is ParamValue.NumberValue -> {
                        val d = value.value
                        if (d % 1.0 == 0.0) {
                            put(key, d.toLong())
                        } else {
                            put(key, d)
                        }
                    }
                    is ParamValue.BooleanValue -> put(key, value.value)
                    is ParamValue.StringValue -> put(key, value.value)
                }
            }
            val outputTokenLimit = maxTokens ?: modelConfig.maxOutputTokens
            if (outputTokenLimit != null) {
                put("max_tokens", outputTokenLimit)
                put("max_completion_tokens", outputTokenLimit)
            }
            if (disableThinking) {
                put(PARAM_ENABLE_THINKING, false)
            } else {
                (reasoningEffortOverride ?: modelConfig.reasoningEffort)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put(PARAM_REASONING_EFFORT, it) }
                (enableThinkingOverride ?: modelConfig.enableThinking)?.let { put(PARAM_ENABLE_THINKING, it) }
                maxThinkingTokens?.let { put(PARAM_MAX_THINKING_TOKENS, it) }
                thinkingBudget?.let { put(PARAM_THINKING_BUDGET, it) }
            }
        }

        return bodyObj.toString()
    }

    private fun compactImageDescription(text: String): String {
        return text
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(220)
    }

    data class DeltaResult(val content: String?, val reasoningContent: String?)

    /** 从 SSE data 行解析增量文本和思维链 */
    private fun parseDelta(data: String): DeltaResult {
        return try {
            val obj = json.decodeFromString<JsonObject>(data)
            val delta = obj["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("delta")?.jsonObject
            val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
            val reasoning = delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                ?: delta?.get("reasoning")?.jsonPrimitive?.contentOrNull
            DeltaResult(content, reasoning)
        } catch (_: Exception) {
            DeltaResult(null, null)
        }
    }

    /** 从非流式响应解析完整回复内容 */
    private fun parseNonStreamResponse(body: String): String {
        val obj = json.decodeFromString<JsonObject>(body)
        val message = obj["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")
            ?.jsonObject
        val content = message?.get("content")
        val primitiveContent = content?.jsonPrimitive?.contentOrNull
        if (primitiveContent != null) return primitiveContent

        val arrayContent = runCatching {
            content?.jsonArray?.joinToString("") { part ->
                val partObj = part.jsonObject
                partObj["text"]?.jsonPrimitive?.contentOrNull
                    ?: partObj["content"]?.jsonPrimitive?.contentOrNull
                    ?: ""
            }
        }.getOrNull()
        if (arrayContent != null) return arrayContent

        val reasoningContent = message?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
            ?: message?.get("reasoning")?.jsonPrimitive?.contentOrNull
        if (reasoningContent != null) return reasoningContent

        val legacyText = obj["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")
            ?.jsonPrimitive?.contentOrNull
        if (legacyText != null) return legacyText

        val outputText = obj["output_text"]?.jsonPrimitive?.contentOrNull
        if (outputText != null) return outputText

        throw RuntimeException("无法解析响应内容。Raw body: ${body.take(2000)}")
    }

    private fun parsePromptCacheUsage(data: String): PromptCacheUsage? {
        val usage = runCatching {
            json.decodeFromString<JsonObject>(data)["usage"]?.jsonObject
        }.getOrNull() ?: return null
        val promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull
        val details = usage["prompt_tokens_details"]?.jsonObject
        val cachedTokens = details?.get("cached_tokens")?.jsonPrimitive?.intOrNull
            ?: usage["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
        val cacheWriteTokens = details?.get("cache_write_tokens")?.jsonPrimitive?.intOrNull
        val cacheMissTokens = usage["prompt_cache_miss_tokens"]?.jsonPrimitive?.intOrNull
        return PromptCacheUsage(
            promptTokens = promptTokens,
            cachedTokens = cachedTokens,
            cacheWriteTokens = cacheWriteTokens,
            cacheMissTokens = cacheMissTokens
        ).takeIf {
            it.promptTokens != null || it.cachedTokens != null ||
                it.cacheWriteTokens != null || it.cacheMissTokens != null
        }
    }
}

private val THINKING_PARAMETER_KEYS = setOf(
    PARAM_ENABLE_THINKING,
    PARAM_THINKING_BUDGET,
    PARAM_MAX_THINKING_TOKENS,
    PARAM_REASONING_EFFORT
)

private fun ModelConfig.supportsOpenAiPromptCacheInstrumentation(): Boolean {
    val host = runCatching { java.net.URI(baseUrl).host }.getOrNull()
    return host.equals("api.openai.com", ignoreCase = true)
}

internal fun ModelConfig.forImageDescriptionRequest(): ModelConfig {
    val nextParams = customParams.toMutableMap().apply {
        if (containsKey(PARAM_ENABLE_THINKING)) {
            put(PARAM_ENABLE_THINKING, ParamValue.BooleanValue(false))
        }
        remove(PARAM_THINKING_BUDGET)
        remove(PARAM_MAX_THINKING_TOKENS)
        remove(PARAM_REASONING_EFFORT)
    }
    return copy(
        customParams = nextParams,
        enableThinking = enableThinking?.let { false },
        reasoningEffort = null
    )
}
