package com.example.chatbar.domain.image

import com.example.chatbar.domain.ProxyAwareClient
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.msgpack.core.MessagePack
import org.msgpack.value.Value

sealed class NovelAiImageEvent {
    data class Intermediate(val image: ByteArray, val step: Int, val progress: Float) : NovelAiImageEvent()
    data class Final(val image: ByteArray) : NovelAiImageEvent()
    data class Error(val message: String) : NovelAiImageEvent()
}

class NovelAiImageService(
    private val client: OkHttpClient = ProxyAwareClient.builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
) {
    fun generate(
        token: String,
        prompt: NovelAiPromptPlan,
        seed: Int,
        imageSize: NovelAiImageSize = prompt.sizePreset.imageSize,
        batchSize: Int = 1
    ): Flow<NovelAiImageEvent> = callbackFlow {
        val requestBody = buildRequestBody(prompt, seed, imageSize, batchSize).toRequestBody(JSON_MEDIA_TYPE)
        val activeCall = AtomicReference<Call?>()

        fun enqueueAttempt(attempt: Int) {
            if (!this@callbackFlow.isActive) return
            val correlationId = correlationId()
            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer ${token.trim()}")
                .header("Content-Type", "application/json")
                .header("Accept", "application/octet-stream")
                .header("x-correlation-id", correlationId)
                .post(requestBody)
                .build()
            val call = client.newCall(request)
            activeCall.set(call)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val reason = when (e) {
                        is SocketTimeoutException -> "连接/读取超时"
                        is ConnectException -> "无法连接到服务器"
                        is UnknownHostException -> "DNS 解析失败: ${e.message}"
                        is SSLException -> "SSL 握手失败: ${e.message}"
                        is java.io.EOFException -> "服务器连接意外断开"
                        else -> e.javaClass.simpleName + if (e.message != null) ": ${e.message}" else ""
                    }
                    trySend(NovelAiImageEvent.Error("NovelAI 生图请求失败 ($reason)"))
                    close()
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            if (response.code == 429 && attempt < MAX_GENERATION_ATTEMPTS) {
                                val retryDelay = retryDelayMillis(attempt, response.header("Retry-After"))
                                launch {
                                    delay(retryDelay)
                                    enqueueAttempt(attempt + 1)
                                }
                                return
                            }
                            val body = response.body?.string().orEmpty().take(1000)
                            val reason = when (response.code) {
                                400 -> "请求参数有误"
                                401 -> "认证失败，请检查 NovelAI Token 是否有效"
                                402 -> "账户余额不足"
                                403 -> "无权访问，Token 权限不足"
                                429 -> "请求频率过高，已尝试 $MAX_GENERATION_ATTEMPTS 次仍失败"
                                500 -> "NovelAI 服务器内部错误"
                                502 -> "NovelAI 网关错误"
                                503 -> "NovelAI 服务暂不可用"
                                else -> "未知服务端错误"
                            }
                            trySend(NovelAiImageEvent.Error("NovelAI 生图失败 ($reason, HTTP ${response.code})${if (body.isNotEmpty()) ": $body" else ""}"))
                            close()
                            return
                        }
                        val stream = response.body?.byteStream()
                        if (stream == null) {
                            trySend(NovelAiImageEvent.Error("NovelAI 生图响应为空：服务器未返回图片数据"))
                            close()
                            return
                        }
                        try {
                            val decoder = NovelAiStreamFrameDecoder()
                            val buffer = ByteArray(16 * 1024)
                            while (!call.isCanceled()) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                decoder.feed(buffer.copyOf(count)).forEach { frame ->
                                    when (val event = decodeFrame(frame)) {
                                        is NovelAiImageEvent.Intermediate -> trySend(event)
                                        is NovelAiImageEvent.Final -> trySend(event)
                                        is NovelAiImageEvent.Error -> trySend(
                                            NovelAiImageEvent.Error("${event.message} [request: $correlationId]")
                                        )
                                    }
                                }
                            }
                        } catch (error: Throwable) {
                            if (!call.isCanceled()) {
                                val detail = buildString {
                                    append("NovelAI 流解析失败")
                                    append(" (${error.javaClass.simpleName}")
                                    if (error.message != null) append(": ${error.message}")
                                    append(")")
                                }
                                trySend(NovelAiImageEvent.Error(detail))
                            }
                        } finally {
                            close()
                        }
                    }
                }
            })
        }

        enqueueAttempt(attempt = 1)
        awaitClose { activeCall.get()?.cancel() }
    }

    fun buildRequestBody(
        prompt: NovelAiPromptPlan,
        seed: Int = randomSeed(),
        imageSize: NovelAiImageSize = prompt.sizePreset.imageSize,
        batchSize: Int = 1
    ): String {
        require(batchSize in 1..NOVEL_AI_MAX_BATCH_SIZE) {
            "NovelAI 批量生图数量必须在 1..$NOVEL_AI_MAX_BATCH_SIZE 之间"
        }
        val negative = deduplicateNegativePrompt(prompt.effectiveNegativePrompt)
        val characterCaptions = buildJsonArray {
            prompt.characterCaptions.forEach { caption ->
                add(buildJsonObject {
                    put("char_caption", caption.prompt)
                    put("centers", centerArray(caption.center))
                })
            }
        }
        val v4Prompt = buildJsonObject {
            put("caption", buildJsonObject {
                put("base_caption", prompt.baseCaption)
                put("char_captions", characterCaptions)
            })
            put("use_coords", false)
            put("use_order", true)
        }
        val v4NegativePrompt = buildJsonObject {
            put("caption", buildJsonObject {
                put("base_caption", negative)
                put("char_captions", buildJsonArray {
                    prompt.characterCaptions.forEach { caption ->
                        add(buildJsonObject {
                            put("char_caption", "")
                            put("centers", centerArray(caption.center))
                        })
                    }
                })
            })
            put("legacy_uc", false)
            put("use_coords", false)
            put("use_order", true)
        }
        return buildJsonObject {
            put("input", prompt.baseCaption)
            put("model", MODEL)
            put("action", "generate")
            put("parameters", buildJsonObject {
                put("params_version", 3)
                put("width", imageSize.width)
                put("height", imageSize.height)
                put("scale", SCALE)
                put("sampler", "k_euler_ancestral")
                put("steps", STEPS)
                put("seed", seed)
                put("extra_noise_seed", seed)
                put("n_samples", batchSize)
                put("ucPreset", 0)
                put("qualityToggle", true)
                put("negative_prompt", negative)
                put("noise_schedule", "karras")
                put("legacy", false)
                put("legacy_uc", false)
                put("use_coords", false)
                put("legacy_v3_extend", false)
                put("autoSmea", false)
                put("sm", false)
                put("sm_dyn", false)
                put("dynamic_thresholding", false)
                put("cfg_rescale", 0.0)
                put("skip_cfg_above_sigma", JsonNull)
                put("deliberate_euler_ancestral_bug", false)
                put("prefer_brownian", true)
                put("stream", "msgpack")
                put("v4_prompt", v4Prompt)
                put("v4_negative_prompt", v4NegativePrompt)
            })
        }.toString()
    }

    private fun centerArray(center: DesignedCharacterCenter) = buildJsonArray {
        add(buildJsonObject {
            put("x", center.x)
            put("y", center.y)
        })
    }

    internal fun decodeFrame(frame: ByteArray): NovelAiImageEvent {
        val unpacker = MessagePack.newDefaultUnpacker(frame)
        val map = unpacker.unpackValue().asMapValue().map()
        unpacker.close()
        fun value(name: String): Value? = map.entries.firstOrNull {
            it.key.isStringValue && it.key.asStringValue().asString() == name
        }?.value
        return when (value("event_type")?.asStringValue()?.asString()) {
            "intermediate" -> {
                val image = value("image")?.asBinaryValue()?.asByteArray()
                    ?: error("intermediate 事件缺少图片")
                val step = value("step_ix")?.asIntegerValue()?.toInt() ?: 0
                NovelAiImageEvent.Intermediate(image, step, (step / STEPS.toFloat()).coerceIn(0f, 1f))
            }
            "final" -> NovelAiImageEvent.Final(
                value("image")?.asBinaryValue()?.asByteArray() ?: error("final 事件缺少图片")
            )
            "error" -> {
                val msg = value("message")?.asStringValue()?.asString()
                NovelAiImageEvent.Error("NovelAI 服务端报错: ${msg ?: "未知错误"}")
            }
            else -> error("未知 NovelAI 流事件: ${value("event_type")?.asStringValue()?.asString() ?: "null"}")
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val ENDPOINT = "https://image.novelai.net/ai/generate-image-stream"
        const val MODEL = "nai-diffusion-4-5-full"
        const val STEPS = 28
        const val SCALE = 8.0
        const val CONNECT_TIMEOUT_SECONDS = 30L
        const val READ_TIMEOUT_MINUTES = 10L
        const val WRITE_TIMEOUT_SECONDS = 30L
        const val MAX_GENERATION_ATTEMPTS = 3
        const val BASE_RETRY_DELAY_MS = 1_000L
        const val MAX_RETRY_AFTER_SECONDS = 30L
        fun randomSeed(): Int = kotlin.random.Random.nextInt(0, Int.MAX_VALUE)
        fun correlationId(): String = (1..6).map { ALPHANUMERIC.random() }.joinToString("")
        const val ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        fun deduplicateNegativePrompt(negative: String): String =
            negative.split(',')
                .map { it.trim().lowercase() }
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(", ")

        fun retryDelayMillis(failedAttempt: Int, retryAfterHeader: String?): Long {
            val retryAfterSeconds = retryAfterHeader
                ?.trim()
                ?.toLongOrNull()
                ?.coerceIn(0L, MAX_RETRY_AFTER_SECONDS)
            return retryAfterSeconds?.times(1_000L) ?: BASE_RETRY_DELAY_MS * failedAttempt
        }
    }

    fun newSeed(): Int = randomSeed()
}
