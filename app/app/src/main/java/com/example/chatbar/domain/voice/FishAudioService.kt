package com.example.chatbar.domain.voice

import com.example.chatbar.domain.ProxyAwareClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody

class FishAudioService(
    private val storage: FishAudioStorage,
    private val client: OkHttpClient = ProxyAwareClient.builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    private val ttsSlots = Semaphore(5)

    suspend fun listModels(
        apiKey: String,
        query: FishAudioModelQuery
    ): FishAudioModelPage = withContext(Dispatchers.IO) {
        val request = FishAudioRequestFactory.listModels(baseUrl, apiKey, query)
        execute(request) { body -> json.decodeFromString(FishAudioModelPage.serializer(), body) }
    }

    suspend fun synthesize(
        apiKey: String,
        modelId: String,
        referenceId: String,
        text: String,
        sessionId: String,
        voiceId: String,
        onProgress: (FishAudioDownloadProgress.Downloading) -> Unit = {}
    ): FishAudioStoredAudio = withContext(Dispatchers.IO) {
        ttsSlots.withPermit {
            val request = FishAudioRequestFactory.tts(
                baseUrl = baseUrl,
                apiKey = apiKey,
                modelId = modelId,
                referenceId = referenceId,
                text = text,
                json = json
            )
            val response = awaitResponse(request)
            response.use {
                coroutineContext.ensureActive()
                if (!response.isSuccessful) throw httpError(response.code, response.body?.string().orEmpty())
                val body = response.body ?: throw FishAudioApiException(response.code, "Fish Audio 未返回音频")
                storage.persistTtsResponse(sessionId, voiceId, body, onProgress)
            }
        }
    }

    suspend fun downloadPreview(
        apiKey: String,
        modelId: String,
        url: String
    ) = withContext(Dispatchers.IO) {
        val request = FishAudioRequestFactory.preview(url, apiKey)
        val response = awaitResponse(request, "音色预览下载失败")
        response.use {
            if (!response.isSuccessful) throw httpError(response.code, response.body?.string().orEmpty())
            storage.persistPreview(
                modelId,
                response.body ?: throw FishAudioApiException(response.code, "音色预览为空")
            )
        }
    }

    suspend fun synthesizePreview(
        apiKey: String,
        modelId: String,
        referenceId: String,
        text: String,
        previewSessionId: String
    ): File = withContext(Dispatchers.IO) {
        ttsSlots.withPermit {
            val request = FishAudioRequestFactory.tts(
                baseUrl = baseUrl,
                apiKey = apiKey,
                modelId = modelId,
                referenceId = referenceId,
                text = text,
                json = json
            )
            val response = awaitResponse(request)
            response.use {
                coroutineContext.ensureActive()
                if (!response.isSuccessful) throw httpError(response.code, response.body?.string().orEmpty())
                val body = response.body ?: throw FishAudioApiException(response.code, "Fish Audio 未返回音频")
                storage.persistGeneratedPreview(previewSessionId, referenceId, body)
            }
        }
    }

    fun clearGeneratedPreviews(previewSessionId: String): Boolean =
        storage.clearGeneratedPreviews(previewSessionId)

    private suspend fun <T> execute(request: Request, decode: (String) -> T): T {
        val response = awaitResponse(request)
        response.use {
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw httpError(response.code, body)
            return runCatching { decode(body) }.getOrElse { error ->
                throw FishAudioApiException(response.code, "Fish Audio 响应解析失败：${error.message}", error)
            }
        }
    }

    private suspend fun awaitResponse(
        request: Request,
        failurePrefix: String = "Fish Audio 请求失败"
    ): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isActive) return
                continuation.resumeWith(
                    Result.failure(
                        FishAudioApiException(
                            null,
                            "$failurePrefix：${e.message ?: e.javaClass.simpleName}",
                            e
                        )
                    )
                )
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                } else {
                    continuation.resumeWith(Result.success(response))
                }
            }
        })
    }

    private fun httpError(code: Int, body: String): FishAudioApiException {
        val detail = runCatching {
            json.decodeFromString(FishAudioError.serializer(), body).message
        }.getOrNull()?.takeIf(String::isNotBlank) ?: body.take(500).takeIf(String::isNotBlank)
        val summary = when (code) {
            401 -> "认证失败，请检查 API Key"
            402 -> "账户余额不足"
            403 -> "该音色不可用：当前 API Key 无权访问"
            404 -> "音色或接口不存在"
            422 -> "请求参数不合法"
            429 -> "并发或频率超过 Fish Audio 限制"
            in 500..599 -> "Fish Audio 服务暂不可用"
            else -> "请求失败"
        }
        return FishAudioApiException(code, "$summary（HTTP $code）${detail?.let { "：$it" }.orEmpty()}")
    }

    @Serializable
    private data class FishAudioError(
        val status: Int? = null,
        val message: String = ""
    )

    private companion object {
        const val DEFAULT_BASE_URL = "https://api.fish.audio"
    }
}

@Serializable
internal data class FishAudioTtsRequest(
    val text: String,
    @kotlinx.serialization.SerialName("reference_id")
    val referenceId: String,
    val format: String,
    @kotlinx.serialization.SerialName("sample_rate")
    val sampleRate: Int,
    @kotlinx.serialization.SerialName("mp3_bitrate")
    val mp3Bitrate: Int,
    val latency: String
)

internal object FishAudioRequestFactory {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun listModels(
        baseUrl: String,
        apiKey: String,
        query: FishAudioModelQuery
    ): Request {
        val url = "${baseUrl.trimEnd('/')}/model".toHttpUrl().newBuilder()
            .addQueryParameter("page_size", query.pageSize.coerceAtLeast(1).toString())
            .addQueryParameter("page_number", query.pageNumber.coerceAtLeast(1).toString())
            .addQueryParameter("self", (query.library == FishAudioLibrary.MINE).toString())
            .addQueryParameter("sort_by", query.sort.apiValue)
            .apply {
                query.title.trim().takeIf(String::isNotEmpty)?.let { addQueryParameter("title", it) }
                query.tags.map(String::trim).filter(String::isNotEmpty).forEach { addQueryParameter("tag", it) }
                query.languages.map(String::trim).filter(String::isNotEmpty)
                    .forEach { addQueryParameter("language", it) }
            }
            .build()
        return Request.Builder()
            .url(url)
            .header("Authorization", bearer(apiKey))
            .get()
            .build()
    }

    fun tts(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        referenceId: String,
        text: String,
        json: Json = Json { encodeDefaults = true }
    ): Request {
        val payload = FishAudioTtsRequest(
            text = text,
            referenceId = referenceId,
            format = "mp3",
            sampleRate = 44_100,
            mp3Bitrate = 64,
            latency = "normal"
        )
        return Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/tts")
            .header("Authorization", bearer(apiKey))
            .header("Content-Type", "application/json")
            .header("Accept", "audio/mpeg")
            .header("model", modelId)
            .post(json.encodeToString(payload).toRequestBody(jsonMediaType))
            .build()
    }

    fun preview(url: String, apiKey: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", bearer(apiKey))
            .get()
            .build()

    private fun bearer(apiKey: String): String {
        val normalized = apiKey.trim()
        require(normalized.isNotEmpty()) { "Fish Audio API Key 未配置" }
        return "Bearer $normalized"
    }
}
