package com.example.chatbar.domain.image

import android.content.Context
import android.util.Base64
import com.example.chatbar.domain.ProxyAwareClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class NovelAiVibeEncodingService(
    context: Context,
    private val client: OkHttpClient = ProxyAwareClient.builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val cacheRoot = File(context.filesDir, "images/studio-guidance/vibe-cache")

    fun isCached(assetSha256: String, model: NovelAiImageModel, informationExtracted: Float): Boolean =
        cacheFile(assetSha256, model, informationExtracted).isFile

    suspend fun resolve(
        token: String,
        asset: NovelAiStudioAssetRef,
        model: NovelAiImageModel,
        informationExtracted: Float
    ): String {
        require(asset.isUsable) { "氛围参考原图不可用" }
        require(model == NovelAiImageModel.V4_5_FULL) { "当前模型不支持氛围参考" }
        require(informationExtracted in 0f..1f) { "信息提取必须在 0.0–1.0 之间" }
        val target = cacheFile(asset.sha256, model, informationExtracted)
        target.takeIf(File::isFile)?.readText(Charsets.UTF_8)?.takeIf(String::isNotBlank)?.let { return it }
        val source = File(asset.path)
        require(source.isFile) { "氛围参考原图不存在" }
        val requestJson = buildJsonObject {
            put("image", Base64.encodeToString(source.readBytes(), Base64.NO_WRAP))
            put("information_extracted", informationExtracted)
            put("model", model.apiId)
        }.toString()
        val correlationId = correlationId()
        val request = Request.Builder()
            .url(ENCODE_ENDPOINT)
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/octet-stream")
            .header("x-correlation-id", correlationId)
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val encoded = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    runCatching {
                        response.use {
                            val bytes = response.body?.bytes() ?: byteArrayOf()
                            if (!response.isSuccessful) {
                                val detail = bytes.toString(Charsets.UTF_8).take(500)
                                throw IOException(
                                    "氛围编码失败（HTTP ${response.code}）" +
                                        "${if (detail.isBlank()) "" else "：$detail"} [request: $correlationId]"
                                )
                            }
                            require(bytes.isNotEmpty()) { "氛围编码响应为空" }
                            Base64.encodeToString(bytes, Base64.NO_WRAP)
                        }
                    }.onSuccess { value ->
                        if (continuation.isActive) continuation.resume(value)
                    }.onFailure { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
        cacheRoot.mkdirs()
        val temporary = File(cacheRoot, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(encoded, Charsets.UTF_8)
            check(temporary.renameTo(target)) { "氛围编码缓存写入失败" }
        } finally {
            temporary.delete()
        }
        return encoded
    }

    private fun cacheFile(assetSha256: String, model: NovelAiImageModel, informationExtracted: Float): File {
        val stableInformation = "%.4f".format(java.util.Locale.ROOT, informationExtracted.coerceIn(0f, 1f))
        val key = "$assetSha256|${model.apiId}|$stableInformation"
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(cacheRoot, "$digest.vibe")
    }

    private companion object {
        const val ENCODE_ENDPOINT = "https://image.novelai.net/ai/encode-vibe"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        fun correlationId(): String = (1..6).map { ALPHANUMERIC.random() }.joinToString("")
    }
}
