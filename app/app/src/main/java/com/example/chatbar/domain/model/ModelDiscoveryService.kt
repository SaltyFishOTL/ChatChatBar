package com.example.chatbar.domain.model

import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.addModelApiAuthorization
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Uses the same OpenAI-compatible base URL and authentication as chat requests. */
class ModelDiscoveryService {
    private val httpsClient by lazy { createClient(false) }
    private val cleartextClient by lazy { createClient(true) }

    private fun createClient(allowCleartext: Boolean) =
        ProxyAwareClient.modelApiBuilder { allowCleartext }
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

    suspend fun fetch(baseUrl: String, apiKey: String, allowCleartext: Boolean): List<String> {
        val base = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IOException("Base URL 无效，请填写完整的 http:// 或 https:// 地址")
        if (base.username.isNotEmpty() || base.password.isNotEmpty() || base.query != null || base.fragment != null) {
            throw IOException("Base URL 不应包含账号、密码、查询参数或片段")
        }
        if (apiKey.trim().any { it !in '!'..'~' }) {
            throw IOException("API Key 含无效字符，请检查是否混入空格、换行或中文")
        }
        val client = if (allowCleartext) cleartextClient else httpsClient
        val requestBase = GeminiOpenAiEndpoint.resolve(baseUrl)?.toHttpUrlOrNull() ?: base
        val request = Request.Builder()
            .url(requestBase.newBuilder().addPathSegment("models").build())
            .addModelApiAuthorization(apiKey)
            .get()
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(IOException(
                        if (!base.isHttps && !allowCleartext) "明文 HTTP 模型 API 已禁用，请在管理 > 设置中开启后重试"
                        else "获取模型列表失败，请检查网络、代理与 Base URL 后重试", e
                    )))
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(runCatching {
                        response.use {
                            if (!it.isSuccessful) throw IOException(when (it.code) {
                                401, 403 -> "模型列表访问被拒绝（HTTP ${it.code}），请检查 API Key 和账号权限"
                                404, 405 -> "服务商未提供此模型列表接口（HTTP ${it.code}），请检查 Base URL 或手动填写模型标识"
                                else -> "获取模型列表失败（HTTP ${it.code}），请检查服务商状态后重试"
                            })
                            parseModelIds(it.body?.string().orEmpty())
                        }
                    })
                }
            })
        }
    }
}

internal fun parseModelIds(body: String): List<String> {
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    val data = root?.get("data") as? JsonArray
        ?: throw IOException("模型列表格式不兼容，需要 OpenAI 兼容接口的 data 列表")
    return data.mapNotNull { entry ->
        ((entry as? JsonObject)?.get("id") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER).also {
        if (it.isEmpty()) throw IOException("服务商未返回可用模型标识，请检查账号权限或手动填写")
    }
}
