package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ParamValue
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.AiTaskFailureKind
import com.example.chatbar.domain.prompt.AiTaskMessageAssembler
import com.example.chatbar.domain.prompt.AiTaskRefusalPolicy
import com.example.chatbar.domain.prompt.AiTaskEmptyResponseException
import com.example.chatbar.domain.prompt.aiTaskFailureKind
import com.example.chatbar.utils.DebugLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
    data class Error(
        val message: String,
        val failureKind: AiTaskFailureKind? = null,
        val cause: Throwable? = null
    ) : StreamEvent() {
        fun asException(): Throwable = cause ?: IllegalStateException(message)
    }

    /** 流结束 */
    data object Done : StreamEvent()
}

data class PromptCacheUsage(
    val promptTokens: Int? = null,
    val cachedTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val cacheMissTokens: Int? = null,
    val completionTokens: Int? = null
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
    private val allowCleartextHttp: () -> Boolean = { false }
) {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val CONNECT_TIMEOUT = 30L
        private const val READ_TIMEOUT = 120L // SSE 需要较长读取超时
        private const val FINISH_REASON_GRACE_MILLIS = 250L
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
        promptCacheKey: String? = null,
        maxTokens: Int? = null,
        onReplyCompletion: (ChatReplyCompletion) -> Unit = {}
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
            maxTokens = maxTokens,
            promptCacheKey = promptCacheKey.takeIf { supportsOpenAiCacheInstrumentation },
            includeStreamUsage = supportsOpenAiCacheInstrumentation
        )

        // 写入 Debug 日志开始记录（仅一次）
        val mainLogId = com.example.chatbar.utils.DebugLogManager.startRequest(
            sessionId = sessionId,
            modelName = modelConfig.displayName,
            apiUrl = url,
            requestBodyJson = requestBody,
            systemPrompt = systemPrompt,
            ragChunks = ragChunks,
            secrets = listOf(modelConfig.apiKey)
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
                val resumed = AtomicBoolean(false)
                val terminalDelivered = AtomicBoolean(false)
                val finishReasonObserved = AtomicBoolean(false)
                val replyCompletion = AtomicReference(ChatReplyCompletion())
                var finishReasonCompletionJob: Job? = null

                fun resumeAttempt() {
                    if (resumed.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }

                fun deliverTerminal(
                    eventSource: EventSource,
                    event: StreamEvent,
                    completed: Boolean
                ) {
                    if (!terminalDelivered.compareAndSet(false, true)) return
                    shouldStop = true
                    onReplyCompletion(replyCompletion.get())
                    DebugLogManager.recordCompletion(mainLogId, replyCompletion.get().finishReason, replyCompletion.get().refused)
                    if (completed) {
                        com.example.chatbar.utils.DebugLogManager.completeRequest(mainLogId)
                    } else if (event is StreamEvent.Error) {
                        com.example.chatbar.utils.DebugLogManager.logError(mainLogId, event.message)
                    }
                    trySend(event)
                    resumeAttempt()
                    eventSource.cancel()
                }

                val listener = object : EventSourceListener() {
                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String
                    ) {
                        if (terminalDelivered.get()) return
                        if (data.trim() == "[DONE]") {
                            finishReasonCompletionJob?.cancel()
                            com.example.chatbar.utils.DebugLogManager.appendResponseChunk(mainLogId, data)
                            deliverTerminal(eventSource, StreamEvent.Done, completed = true)
                            return
                        }

                        try {
                            val delta = parseDelta(data)
                            replyCompletion.updateAndGet { previous ->
                                previous.copy(
                                    finishReason = delta.finishReason ?: previous.finishReason,
                                    refused = previous.refused || delta.refused
                                )
                            }
                            com.example.chatbar.utils.DebugLogManager.appendResponseChunk(
                                sessionId = mainLogId,
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
                                com.example.chatbar.utils.DebugLogManager.recordPromptCacheUsage(mainLogId, usage)
                                trySend(StreamEvent.Usage(usage))
                            }
                            if (
                                delta.finishReason != null &&
                                finishReasonObserved.compareAndSet(false, true)
                            ) {
                                finishReasonCompletionJob = launch {
                                    delay(FINISH_REASON_GRACE_MILLIS)
                                    deliverTerminal(eventSource, StreamEvent.Done, completed = true)
                                }
                            }
                        } catch (e: Exception) {
                            com.example.chatbar.utils.DebugLogManager.appendResponseChunk(mainLogId, data)
                            deliverTerminal(
                                eventSource,
                                StreamEvent.Error("解析 SSE 数据失败: ${e.message}"),
                                completed = false
                            )
                        }
                    }

                    override fun onFailure(
                        eventSource: EventSource,
                        t: Throwable?,
                        response: Response?
                    ) {
                        if (terminalDelivered.get()) {
                            resumeAttempt()
                            return
                        }
                        if (finishReasonObserved.get()) {
                            finishReasonCompletionJob?.cancel()
                            replyCompletion.updateAndGet { it.copy(transportFailed = true) }
                            deliverTerminal(eventSource, StreamEvent.Done, completed = true)
                            return
                        }
                        val body = try { response?.body?.string() } catch (_: Exception) { null }

                        if (retryCount < maxRetries && response?.code == 400 && body?.contains("20015") == true) {
                            retrying = true
                            retryCount++
                            com.example.chatbar.utils.DebugLogManager.appendResponseChunk(
                                mainLogId,
                                "[RETRY #$retryCount] 服务器返回 400/20015，${retryCount}秒后重试..."
                            )
                            resumeAttempt()
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
                        deliverTerminal(
                            eventSource,
                            StreamEvent.Error(errorMsg),
                            completed = false
                        )
                    }

                    override fun onClosed(eventSource: EventSource) {
                        if (retrying || terminalDelivered.get()) {
                            resumeAttempt()
                            return
                        }
                        finishReasonCompletionJob?.cancel()
                        if (finishReasonObserved.get()) {
                            deliverTerminal(eventSource, StreamEvent.Done, completed = true)
                        } else {
                            deliverTerminal(
                                eventSource,
                                StreamEvent.Error("流式连接已关闭，但未收到 finish_reason 或 [DONE]"),
                                completed = false
                            )
                        }
                    }
                }

                val eventSource = EventSources.createFactory(client)
                    .newEventSource(request, listener)

                continuation.invokeOnCancellation {
                    DebugLogManager.logError(mainLogId, "请求已取消", AiTaskFailureKind.CANCELLED)
                    eventSource.cancel()
                }
            }

            if (!shouldStop) {
                delay(retryCount * 1000L)
            }
        }

        close()
    }.buffer(Channel.UNLIMITED)

    /** One HTTP request per collection. Task confirmation is assembled only at this boundary. */
    fun streamText(
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig,
        maxTokens: Int? = null,
        enableThinking: Boolean? = null,
        maxThinkingTokens: Int? = null,
        thinkingBudget: Int? = null,
        disableThinking: Boolean = false,
        readTimeoutSeconds: Long? = null,
        taskContext: AiTaskContext? = null,
        reasoningEffort: String? = null,
        isolatedTaskParameters: Boolean = false,
        responseFormatJson: Boolean = false
    ): Flow<StreamEvent> = callbackFlow {
        val context = taskContext?.forRequest()
        val actualMessages = context?.let { AiTaskMessageAssembler.assemble(messages, it) } ?: messages
        val url = "${modelConfig.baseUrl.trimEnd('/')}/chat/completions"
        val requestBody = buildRequestBody(
            messages = actualMessages,
            modelConfig = modelConfig,
            stream = true,
            maxTokens = maxTokens,
            enableThinkingOverride = enableThinking,
            maxThinkingTokens = maxThinkingTokens,
            thinkingBudget = thinkingBudget,
            disableThinking = disableThinking,
            reasoningEffortOverride = reasoningEffort,
            isolatedTaskParameters = isolatedTaskParameters,
            responseFormatJson = responseFormatJson,
            includeStreamUsage = modelConfig.supportsOpenAiPromptCacheInstrumentation()
        )
        val logId = DebugLogManager.startRequest(
            sessionId = context?.taskId ?: java.util.UUID.randomUUID().toString(),
            modelName = modelConfig.displayName, apiUrl = url, requestBodyJson = requestBody,
            systemPrompt = "", ragChunks = emptyList(), taskContext = context,
            confirmationText = context?.let { AiTaskMessageAssembler.addedText(messages, it) }.orEmpty(),
            secrets = listOf(modelConfig.apiKey)
        )
        val request = Request.Builder().url(url).addModelApiAuthorization(modelConfig.apiKey)
            .addHeader("Content-Type", "application/json").addHeader("Accept", "text/event-stream")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE)).build()
        val lock = Any()
        val progress = currentCoroutineContext()[AiStreamProgress]
        progress?.start(logId, context, modelConfig.displayName.ifBlank { modelConfig.modelName })
        val closed = AtomicBoolean(false)
        val text = StringBuilder()
        var finishReason: String? = null
        var refused = false
        var receivedReasoning = false
        var graceJob: Job? = null
        var inactivityJob: Job? = null
        var lastMeaningfulEvent = System.nanoTime()

        fun fail(eventSource: EventSource, error: Throwable) {
            if (!closed.compareAndSet(false, true)) return
            graceJob?.cancel()
            inactivityJob?.cancel()
            progress?.finish(logId, error.message ?: "请求失败")
            DebugLogManager.logError(logId, error.message ?: error::class.java.simpleName, error.aiTaskFailureKind())
            trySend(StreamEvent.Error(error.message ?: "AI 请求失败", error.aiTaskFailureKind(), error))
            close()
            eventSource.cancel()
        }

        fun complete(eventSource: EventSource) {
            synchronized(lock) {
                if (closed.get()) return
                val content = text.toString()
                val rejection = AiTaskRefusalPolicy.failure(content, finishReason, refused)
                if (rejection != null) {
                    fail(eventSource, rejection)
                    return
                }
                if (finishReason == "length") {
                    fail(eventSource, ModelResponseTruncatedException())
                    return
                }
                if (content.isBlank()) {
                    fail(eventSource, AiTaskEmptyResponseException(receivedReasoning))
                    return
                }
                if (!closed.compareAndSet(false, true)) return
                graceJob?.cancel()
                inactivityJob?.cancel()
                progress?.finish(logId, "输出完成")
                DebugLogManager.completeRequest(logId)
                trySend(StreamEvent.Done)
                close()
                eventSource.cancel()
            }
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                synchronized(lock) {
                    if (closed.get()) return
                    if (data.trim() == "[DONE]") {
                        complete(eventSource)
                        return
                    }
                    val delta = try {
                        parseDelta(data)
                    } catch (error: Exception) {
                        DebugLogManager.appendResponseChunk(logId, data)
                        fail(eventSource, IllegalArgumentException(
                            "解析 SSE 数据失败: ${error.message}\n原始数据: ${data.take(2000)}",
                            error
                        ))
                        return
                    }
                    finishReason = delta.finishReason ?: finishReason
                    refused = refused || delta.refused
                    if (!delta.content.isNullOrBlank() || !delta.reasoningContent.isNullOrBlank() || delta.finishReason != null) {
                        lastMeaningfulEvent = System.nanoTime()
                    }
                    progress?.append(logId, delta.reasoningContent, delta.content)
                    DebugLogManager.appendResponseChunk(logId, data, delta.content, delta.reasoningContent)
                    DebugLogManager.recordCompletion(logId, finishReason, refused)
                    parsePromptCacheUsage(data)?.let {
                        DebugLogManager.recordPromptCacheUsage(logId, it)
                        trySend(StreamEvent.Usage(it))
                    }
                    delta.reasoningContent?.takeIf(String::isNotBlank)?.let {
                        receivedReasoning = true
                        trySend(StreamEvent.ReasoningDelta(it))
                    }
                    delta.content?.takeIf(String::isNotEmpty)?.let {
                        text.append(it)
                        trySend(StreamEvent.Delta(it))
                    }
                    if (delta.finishReason != null && graceJob == null) {
                        // Some providers send usage after finish_reason. No extra request or retry.
                        graceJob = launch {
                            delay(FINISH_REASON_GRACE_MILLIS)
                            complete(eventSource)
                        }
                    }
                    // Explicit refusals without a finish reason are terminal as well.
                    if (refused && finishReason == null) complete(eventSource)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                synchronized(lock) {
                    if (closed.get()) return
                    val body = runCatching { response?.body?.string() }.getOrNull()
                    val rejection = AiTaskRefusalPolicy.failure(text.toString(), finishReason, refused)
                    fail(eventSource, rejection ?: ModelRequestException(
                        message = "流式文本补全失败${response?.code?.let { " ($it)" }.orEmpty()}: ${body?.take(2000) ?: t?.message.orEmpty()}",
                        httpStatus = response?.code,
                        traceId = response?.header("x-request-id") ?: response?.header("x-trace-id"),
                        retryAfterMillis = response?.header("Retry-After")?.toRetryAfterMillis(),
                        cause = t
                    ))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                synchronized(lock) {
                    if (closed.get()) return
                    if (finishReason != null) complete(eventSource)
                    else fail(eventSource, ModelRequestException("流式文本补全连接已关闭，但未收到 finish_reason 或 [DONE]"))
                }
            }
        }
        val requestClient = readTimeoutSeconds?.let { client.newBuilder().readTimeout(it, TimeUnit.SECONDS).build() } ?: client
        val eventSource = EventSources.createFactory(requestClient).newEventSource(request, listener)
        // Heartbeats/comments keep socket reads alive without advancing the model output.
        val inactivitySeconds = readTimeoutSeconds ?: READ_TIMEOUT
        inactivityJob = launch {
            while (!closed.get()) {
                delay(200)
                progress?.flush()
                synchronized(lock) {
                    if (!closed.get() && System.nanoTime() - lastMeaningfulEvent >= TimeUnit.SECONDS.toNanos(inactivitySeconds)) {
                        fail(eventSource, ModelRequestException("AI 已 ${inactivitySeconds} 秒未返回正文或思考内容，请重试或切换模型"))
                    }
                }
            }
        }
        awaitClose {
            graceJob?.cancel()
            inactivityJob.cancel()
            if (closed.compareAndSet(false, true)) {
                progress?.finish(logId, "已取消")
                DebugLogManager.logError(logId, "请求已取消", AiTaskFailureKind.CANCELLED)
            }
            eventSource.cancel()
        }
    }.buffer(Channel.UNLIMITED)

    suspend fun describeImage(imageBase64: String, modelConfig: ModelConfig): String =
        compactImageDescription(completeText(
            messages = listOf(
                ChatApiMessage.text("system", PromptTemplates.IMAGE_DESCRIPTION_PROMPT),
                ChatApiMessage.withImage("user", "", imageBase64)
            ),
            modelConfig = modelConfig.forImageDescriptionRequest(),
            taskContext = AiTaskContext(AiTaskKind.IMAGE_DESCRIPTION)
        ))

    suspend fun describeImageStreaming(
        imageBase64: String,
        modelConfig: ModelConfig,
        onDelta: (String) -> Unit = {}
    ): String = compactImageDescription(completeTextStreaming(
        messages = listOf(
            ChatApiMessage.text("system", PromptTemplates.IMAGE_DESCRIPTION_PROMPT),
            ChatApiMessage.withImage("user", "", imageBase64)
        ),
        modelConfig = modelConfig.forImageDescriptionRequest(),
        onDelta = onDelta,
        taskContext = AiTaskContext(AiTaskKind.IMAGE_DESCRIPTION)
    ))

    suspend fun completeText(
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig,
        maxTokens: Int? = null,
        thinkingBudget: Int? = null,
        disableThinking: Boolean = false,
        isolatedTaskParameters: Boolean = false,
        responseFormatJson: Boolean = false,
        readTimeoutSeconds: Long? = null,
        taskContext: AiTaskContext? = null
    ): String {
        val context = taskContext?.forRequest()
        val actualMessages = context?.let { AiTaskMessageAssembler.assemble(messages, it) } ?: messages
        val url = "${modelConfig.baseUrl.trimEnd('/')}/chat/completions"
        val requestBody = buildRequestBody(
            messages = actualMessages, modelConfig = modelConfig, stream = false,
            maxTokens = maxTokens, thinkingBudget = thinkingBudget, disableThinking = disableThinking,
            isolatedTaskParameters = isolatedTaskParameters, responseFormatJson = responseFormatJson
        )
        val logId = DebugLogManager.startRequest(
            sessionId = context?.taskId ?: java.util.UUID.randomUUID().toString(),
            modelName = modelConfig.displayName, apiUrl = url, requestBodyJson = requestBody,
            systemPrompt = "", ragChunks = emptyList(), taskContext = context,
            confirmationText = context?.let { AiTaskMessageAssembler.addedText(messages, it) }.orEmpty(),
            secrets = listOf(modelConfig.apiKey)
        )
        try {
            val body = suspendCancellableCoroutine<String> { continuation ->
                val request = Request.Builder().url(url).addModelApiAuthorization(modelConfig.apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toRequestBody(JSON_MEDIA_TYPE)).build()
                val requestClient = readTimeoutSeconds?.let { client.newBuilder().readTimeout(it, TimeUnit.SECONDS).build() } ?: client
                val call = requestClient.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(
                            ModelRequestException("文本补全请求失败: ${e.message}", cause = e)
                        )
                    }
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            try {
                                val raw = response.body?.string().orEmpty()
                                if (!continuation.isActive) return
                                if (!response.isSuccessful) continuation.resumeWithException(ModelRequestException(
                                    "文本补全失败 (${response.code}): ${raw.take(2000)}",
                                    httpStatus = response.code,
                                    traceId = response.header("x-request-id") ?: response.header("x-trace-id"),
                                    retryAfterMillis = response.header("Retry-After")?.toRetryAfterMillis()
                                )) else continuation.resume(raw)
                            } catch (error: IOException) {
                                if (continuation.isActive) continuation.resumeWithException(
                                    ModelRequestException("读取文本补全响应失败: ${error.message}", cause = error)
                                )
                            }
                        }
                    }
                })
            }
            DebugLogManager.appendResponseChunk(logId, body)
            val finishReason = parseFinishReason(body)
            val refused = AiTaskRefusalPolicy.responseRefused(body)
            DebugLogManager.recordCompletion(logId, finishReason, refused)
            parsePromptCacheUsage(body)?.let { DebugLogManager.recordPromptCacheUsage(logId, it) }
            AiTaskRefusalPolicy.failure("", finishReason, refused)?.let { throw it }
            val content = parseNonStreamResponse(body)
            DebugLogManager.appendResponseChunk(logId, "", content)
            AiTaskRefusalPolicy.failure(content, finishReason, refused)?.let { throw it }
            if (finishReason == "length") throw ModelResponseTruncatedException()
            if (content.isBlank()) throw AiTaskEmptyResponseException(false)
            DebugLogManager.completeRequest(logId)
            return content
        } catch (error: Throwable) {
            DebugLogManager.logError(logId, error.message ?: error::class.java.simpleName, error.aiTaskFailureKind())
            throw error
        }
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
        disableThinking: Boolean = false,
        isolatedTaskParameters: Boolean = false,
        responseFormatJson: Boolean = false,
        readTimeoutSeconds: Long? = null,
        taskContext: AiTaskContext? = null
    ): String {
        val text = StringBuilder()
        var completed = false
        streamText(
            messages = messages, modelConfig = modelConfig, maxTokens = maxTokens,
            enableThinking = enableThinking, maxThinkingTokens = maxThinkingTokens,
            thinkingBudget = thinkingBudget, disableThinking = disableThinking,
            readTimeoutSeconds = readTimeoutSeconds, taskContext = taskContext,
            reasoningEffort = reasoningEffort, isolatedTaskParameters = isolatedTaskParameters,
            responseFormatJson = responseFormatJson
        ).collect { event ->
            when (event) {
                is StreamEvent.Delta -> { text.append(event.text); onDelta(event.text) }
                is StreamEvent.ReasoningDelta -> onReasoningDelta(event.text)
                is StreamEvent.Error -> throw event.asException()
                StreamEvent.Done -> completed = true
                is StreamEvent.Usage -> Unit
            }
        }
        check(completed) { "流式文本补全未正常完成" }
        return text.toString()
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
        disableThinking: Boolean = false,
        isolatedTaskParameters: Boolean = false,
        responseFormatJson: Boolean = false
    ): String {
        val legacyThinking = ThinkingRequestPolicy.usesLegacyControls(modelConfig)
        val taskEffort = ThinkingRequestPolicy.taskEffort(
            disableThinking, enableThinkingOverride, thinkingBudget, maxThinkingTokens,
            reasoningEffortOverride
        )
        val requestMessages = CleartextHttpChatTemplatePolicy.adaptMessages(
            messages = messages,
            allowCleartextHttp = allowCleartextHttp(),
            baseUrl = modelConfig.baseUrl
        )
        val messagesArray = buildJsonArray {
            for (msg in requestMessages) {
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
                if (taskEffort != null && !legacyThinking && key in THINKING_PARAMETER_KEYS) continue
                if (taskEffort != null && legacyThinking && key == PARAM_REASONING_EFFORT) continue
                if (disableThinking && key in THINKING_PARAMETER_KEYS) continue
                if (isolatedTaskParameters && key in ISOLATED_TASK_PARAMETER_KEYS) continue
                if (maxTokens != null && key in OUTPUT_TOKEN_PARAMETER_KEYS) continue
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
            val outputTokenLimit = maxTokens ?: modelConfig.maxOutputTokens.takeUnless { isolatedTaskParameters }
            if (outputTokenLimit != null) {
                when (modelConfig.outputTokenParameter) {
                    com.example.chatbar.data.local.entity.OutputTokenParameter.MAX_TOKENS ->
                        put("max_tokens", outputTokenLimit)
                    com.example.chatbar.data.local.entity.OutputTokenParameter.MAX_COMPLETION_TOKENS ->
                        put("max_completion_tokens", outputTokenLimit)
                }
            }
            if (taskEffort != null && !legacyThinking) {
                put(PARAM_REASONING_EFFORT, taskEffort)
            } else if (disableThinking) {
                put(PARAM_ENABLE_THINKING, false)
            } else {
                (reasoningEffortOverride ?: modelConfig.reasoningEffort).takeIf { taskEffort == null }
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put(PARAM_REASONING_EFFORT, it) }
                (enableThinkingOverride ?: modelConfig.enableThinking)?.let { put(PARAM_ENABLE_THINKING, it) }
                maxThinkingTokens?.let { put(PARAM_MAX_THINKING_TOKENS, it) }
                thinkingBudget?.let { put(PARAM_THINKING_BUDGET, it) }
            }
            if (responseFormatJson) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
        }

        return bodyObj.toString()
    }

    private fun compactImageDescription(text: String): String {
        return text
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    data class DeltaResult(
        val content: String?,
        val reasoningContent: String?,
        val finishReason: String? = null,
        val refused: Boolean = false
    )

    /** 从 SSE data 行解析增量文本和思维链；解析失败或遇到服务端错误体时抛出异常，由调用方转成显式错误。 */
    private fun parseDelta(data: String): DeltaResult {
        val obj = try {
            json.decodeFromString<JsonObject>(data)
        } catch (e: Exception) {
            throw IllegalArgumentException("无效的 SSE 数据: ${e.message}", e)
        }
        obj["error"]?.let { errorElement ->
            val errorText = when (errorElement) {
                is JsonObject -> buildString {
                    append(errorElement["message"]?.jsonPrimitive?.contentOrNull ?: "未知错误")
                    errorElement["code"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?.let { append(" (code=$it)") }
                }
                else -> errorElement.jsonPrimitive.contentOrNull ?: "未知错误"
            }
            throw IllegalArgumentException("服务端返回错误: $errorText")
        }
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val delta = choice?.get("delta") as? JsonObject
        val content = delta?.get("content")?.let(::deltaContentText)
        val reasoning = delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
            ?: delta?.get("reasoning")?.jsonPrimitive?.contentOrNull
            ?: delta?.get("thinking")?.jsonPrimitive?.contentOrNull
        val finishReason = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
        val refused = !delta?.get("refusal")?.let(::deltaContentText).isNullOrBlank() ||
            (delta?.get("content") as? JsonArray)?.any { part ->
                (part as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "refusal"
            } == true || finishReason == "content_filter"
        return DeltaResult(content, reasoning, finishReason, refused)
    }

    /** 容忍 content 为字符串或文本分段数组（如 [{"type":"text","text":"..."}]）的增量。 */
    private fun deltaContentText(content: JsonElement): String? = when (content) {
        is JsonPrimitive -> content.contentOrNull
        is JsonArray -> content.joinToString("") { part ->
            when (part) {
                is JsonObject -> part["text"]?.jsonPrimitive?.contentOrNull
                    ?: part["content"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                is JsonPrimitive -> part.contentOrNull ?: ""
                else -> ""
            }
        }.takeIf(String::isNotBlank)
        else -> null
    }

    /** 从非流式响应解析完整回复内容 */
    private fun parseNonStreamResponse(body: String): String {
        val obj = json.decodeFromString<JsonObject>(body)
        val message = obj["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")
            ?.jsonObject
        val content = message?.get("content")
        val primitiveContent = (content as? JsonPrimitive)?.contentOrNull
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
        if (reasoningContent != null) throw AiTaskEmptyResponseException(true)

        val legacyText = obj["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")
            ?.jsonPrimitive?.contentOrNull
        if (legacyText != null) return legacyText

        val outputText = obj["output_text"]?.jsonPrimitive?.contentOrNull
        if (outputText != null) return outputText

        throw RuntimeException("无法解析响应内容。Raw body: ${body.take(2000)}")
    }

    private fun parseFinishReason(body: String): String? = runCatching {
        json.decodeFromString<JsonObject>(body)["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("finish_reason")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun parsePromptCacheUsage(data: String): PromptCacheUsage? {
        val usage = runCatching { json.decodeFromString<JsonObject>(data) }
            .getOrNull()
            ?.get("usage") as? JsonObject ?: return null
        val promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull
        val details = usage["prompt_tokens_details"] as? JsonObject
        val cachedTokens = details?.get("cached_tokens")?.jsonPrimitive?.intOrNull
            ?: usage["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
        val cacheWriteTokens = details?.get("cache_write_tokens")?.jsonPrimitive?.intOrNull
        val cacheMissTokens = usage["prompt_cache_miss_tokens"]?.jsonPrimitive?.intOrNull
        return PromptCacheUsage(
            promptTokens = promptTokens,
            cachedTokens = cachedTokens,
            cacheWriteTokens = cacheWriteTokens,
            cacheMissTokens = cacheMissTokens,
            completionTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull
        ).takeIf {
            it.promptTokens != null || it.cachedTokens != null ||
                it.cacheWriteTokens != null || it.cacheMissTokens != null || it.completionTokens != null
        }
    }
}

class ModelRequestException(
    message: String,
    val httpStatus: Int? = null,
    val traceId: String? = null,
    val retryAfterMillis: Long? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    val isAuthenticationFailure: Boolean get() = httpStatus == 401 || httpStatus == 403
    val isRetryable: Boolean get() = httpStatus == null || httpStatus in setOf(408, 425, 429) ||
        (httpStatus != null && httpStatus in 500..599)
}

internal const val MODEL_OUTPUT_TRUNCATED_MESSAGE =
    "模型服务返回输出截断（finish_reason=length），本次内容未完整生成"

class ModelResponseTruncatedException : RuntimeException(MODEL_OUTPUT_TRUNCATED_MESSAGE)

private fun String.toRetryAfterMillis(): Long? = trim().toLongOrNull()?.times(1000L)

private val THINKING_PARAMETER_KEYS = setOf(
    PARAM_ENABLE_THINKING,
    PARAM_THINKING_BUDGET,
    PARAM_MAX_THINKING_TOKENS,
    PARAM_REASONING_EFFORT
)

private val OUTPUT_TOKEN_PARAMETER_KEYS = setOf(
    "max_tokens",
    "max_completion_tokens"
)

private val ISOLATED_TASK_PARAMETER_KEYS = THINKING_PARAMETER_KEYS + OUTPUT_TOKEN_PARAMETER_KEYS + setOf(
    "temperature",
    "top_p",
    "top_k",
    "min_p",
    "stop",
    "presence_penalty",
    "frequency_penalty",
    "repetition_penalty",
    "seed"
)

private fun ModelConfig.supportsOpenAiPromptCacheInstrumentation(): Boolean {
    val host = runCatching { java.net.URI(baseUrl).host }.getOrNull()
    return host.equals("api.openai.com", ignoreCase = true)
}

internal fun ModelConfig.forImageDescriptionRequest(): ModelConfig {
    return this
}
