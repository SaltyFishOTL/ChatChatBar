package com.example.chatbar.domain.rag

import com.example.chatbar.data.local.entity.EmbeddingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.addModelApiAuthorization
import com.example.chatbar.domain.redactModelApiKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Embedding 服务 — 调用远程 OpenAI 兼容 API 获取文本向量
 *
 * 请求格式:
 * POST {baseUrl}/embeddings
 * {"model": "...", "input": ["text1", "text2"]}
 *
 * 响应格式:
 * {"data": [{"embedding": [0.1, 0.2, ...], "index": 0}, ...]}
 */
class EmbeddingService(
    allowCleartextHttp: () -> Boolean = { false }
) {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val TIMEOUT_SECONDS = 60L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = ProxyAwareClient.modelApiBuilder(allowCleartextHttp)
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * 批量获取文本嵌入向量
     *
     * @param texts  待向量化的文本列表
     * @param config Embedding 模型配置
     * @return 每个文本对应的向量（顺序与输入一致）
     * @throws EmbeddingException 调用失败时抛出
     */
    suspend fun getEmbeddings(
        texts: List<String>,
        config: EmbeddingConfig
    ): List<List<Float>> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()

        val baseUrl = config.baseUrl.trimEnd('/')
        val url = "$baseUrl/embeddings"

        val requestBody = buildJsonObject {
            put("model", config.modelName)
            put("input", buildJsonArray {
                texts.forEach { add(it) }
            })
        }.toString()

        val request = Request.Builder()
            .url(url)
            .addModelApiAuthorization(config.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val detail = e.message.orEmpty()
                    val safeDetail = detail.redactModelApiKey(config.apiKey)
                    continuation.resumeWithException(EmbeddingException(
                        "Embedding 请求失败: $safeDetail", e.takeIf { detail == safeDetail }
                    ))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            val body = it.body?.string() ?: throw EmbeddingException("空响应体")
                            if (!it.isSuccessful) {
                                throw EmbeddingException("API 错误 (${it.code}): ${body.redactModelApiKey(config.apiKey)}")
                            }
                            parseEmbeddingsResponse(body)
                        }
                    }.recoverCatching { error ->
                        throw if (error is EmbeddingException) error
                        else {
                            val detail = error.message.orEmpty()
                            val safeDetail = detail.redactModelApiKey(config.apiKey)
                            EmbeddingException("Embedding 请求失败: $safeDetail", error.takeIf { detail == safeDetail })
                        }
                    }
                    continuation.resumeWith(result)
                }
            })
        }
    }

    /**
     * 获取单个文本的嵌入向量
     */
    suspend fun getEmbedding(
        text: String,
        config: EmbeddingConfig
    ): List<Float> {
        return getEmbeddings(listOf(text), config).firstOrNull()
            ?: throw EmbeddingException("无法获取嵌入向量")
    }

    /**
     * 解析 OpenAI 兼容格式的 embedding 响应
     * 按 index 字段排序以保证输出顺序与输入一致
     */
    private fun parseEmbeddingsResponse(responseBody: String): List<List<Float>> {
        val jsonObj = json.decodeFromString<JsonObject>(responseBody)
        val dataArray = jsonObj["data"]?.jsonArray
            ?: throw EmbeddingException("响应缺少 data 字段")

        return dataArray
            .sortedBy { it.jsonObject["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 }
            .map { item ->
                item.jsonObject["embedding"]?.jsonArray?.map { it.jsonPrimitive.float }
                    ?: throw EmbeddingException("响应缺少 embedding 字段")
            }
    }
}

/**
 * Embedding 相关异常
 */
class EmbeddingException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
