package com.example.chatbar.domain.image

import com.example.chatbar.domain.ProxyAwareClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

data class NovelAiAccountUsage(
    val anlas: Long,
    val tier: Int,
    val active: Boolean,
    val v5AllowancePercent: Double?,
    val v5AllowanceExhausted: Boolean
) {
    val isActiveOpus: Boolean get() = active && tier >= OPUS_TIER

    val approximateV5Images: Int?
        get() = if (v5AllowanceExhausted) 0 else v5AllowancePercent
            ?.coerceIn(0.0, 100.0)
            ?.times(V5_IMAGES_PER_PERCENT)
            ?.roundToInt()
            ?.coerceAtLeast(0)

    private companion object {
        const val OPUS_TIER = 3
        const val V5_IMAGES_PER_PERCENT = 17.3
    }
}

class NovelAiAccountService(
    private val client: OkHttpClient = ProxyAwareClient.builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    fun fetch(token: String): NovelAiAccountUsage {
        val request = Request.Builder()
            .url(SUBSCRIPTION_ENDPOINT)
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("NovelAI 账户信息获取失败（HTTP ${response.code}）")
            }
            val root = runCatching { json.parseToJsonElement(body).jsonObject }
                .getOrElse { throw IOException("NovelAI 账户信息格式无效", it) }
            val usage = root["usage"] as? JsonObject
            val tier = root["tier"]?.jsonPrimitive?.intOrNull ?: 0
            val expiresAt = root["expiresAt"]?.jsonPrimitive?.longOrNull ?: 0L
            val active = root["active"]?.jsonPrimitive?.booleanOrNull
                ?: (tier > 0 && expiresAt > System.currentTimeMillis() / 1_000L)
            NovelAiAccountUsage(
                anlas = parseAnlas(root["trainingStepsLeft"]),
                tier = tier,
                active = active,
                v5AllowancePercent = usage?.get("percent")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                v5AllowanceExhausted = usage?.get("isNegative")?.jsonPrimitive?.booleanOrNull ?: false
            )
        }
    }

    private fun parseAnlas(value: kotlinx.serialization.json.JsonElement?): Long = when (value) {
        is JsonObject -> {
            val fixed = value["fixedTrainingStepsLeft"]?.jsonPrimitive?.longOrNull ?: 0L
            val purchased = value["purchasedTrainingSteps"]?.jsonPrimitive?.longOrNull ?: 0L
            fixed + purchased
        }
        else -> value?.jsonPrimitive?.longOrNull ?: 0L
    }

    private companion object {
        const val SUBSCRIPTION_ENDPOINT = "https://image.novelai.net/user/subscription"
    }
}

enum class NovelAiGenerationChargeKind { FREE, V5_ALLOWANCE, ANLAS }

data class NovelAiGenerationCost(
    val kind: NovelAiGenerationChargeKind,
    val anlas: Int = 0,
    val encodingAnlas: Int = 0,
    val extraVibeAnlas: Int = 0
)

object NovelAiImageCostEstimator {
    fun estimate(
        settings: NovelAiGenerationSettings,
        account: NovelAiAccountUsage?,
        imageGuidance: NovelAiImageGuidanceDraft = NovelAiImageGuidanceDraft(),
        vibeCacheMisses: Int = 0
    ): NovelAiGenerationCost {
        val imageSize = settings.imageSize()
        val sourcePixels = imageSize.width.toLong() * imageSize.height
        val requestPixels = if (imageGuidance.action == NovelAiGenerationAction.INPAINT) {
            NORMAL_MAX_PIXELS
        } else {
            sourcePixels
        }
        val pixels = max(requestPixels, MIN_PRICED_PIXELS)
        val baseCost = ceil(
            PIXEL_BASE_COST_FACTOR * pixels +
                PIXEL_STEP_COST_FACTOR * pixels * settings.steps
        ).toInt()
        val modelCost = if (settings.model == NovelAiImageModel.V5_FULL) {
            ceil(baseCost * V5_PRICE_MULTIPLIER).toInt()
        } else {
            baseCost
        }.coerceAtLeast(MIN_IMAGE_COST)

        val freeSampleEligible = account?.isActiveOpus == true &&
            settings.count == 1 &&
            (imageGuidance.action == NovelAiGenerationAction.INPAINT || sourcePixels <= NORMAL_MAX_PIXELS) &&
            settings.steps <= FREE_MAX_STEPS &&
            imageGuidance.action != NovelAiGenerationAction.IMAGE_TO_IMAGE &&
            imageGuidance.effectiveReferenceMode(settings.model) == NovelAiReferenceMode.NONE &&
            (settings.model != NovelAiImageModel.V5_FULL ||
                account.v5AllowancePercent != null && !account.v5AllowanceExhausted)
        val preciseCost = if (
            settings.model == NovelAiImageModel.V4_5_FULL &&
            imageGuidance.effectiveReferenceMode(settings.model) == NovelAiReferenceMode.PRECISE &&
            imageGuidance.preciseReference.asset?.isUsable == true
        ) PRECISE_REFERENCE_COST * settings.count else 0
        val activeVibeCount = if (
            imageGuidance.effectiveReferenceMode(settings.model) == NovelAiReferenceMode.VIBE
        ) {
            imageGuidance.vibes.count(NovelAiVibeReferenceDraft::isUsable)
        } else {
            0
        }
        val extraVibeCost = (activeVibeCount - FREE_VIBE_COUNT).coerceAtLeast(0) *
            EXTRA_VIBE_COST * settings.count
        val encodingCost = vibeCacheMisses.coerceAtLeast(0) * VIBE_ENCODING_COST
        if (freeSampleEligible) {
            return NovelAiGenerationCost(
                kind = if (settings.model == NovelAiImageModel.V5_FULL) {
                    NovelAiGenerationChargeKind.V5_ALLOWANCE
                } else {
                    NovelAiGenerationChargeKind.FREE
                },
                anlas = preciseCost + extraVibeCost + encodingCost,
                encodingAnlas = encodingCost,
                extraVibeAnlas = extraVibeCost
            )
        }
        return NovelAiGenerationCost(
            kind = NovelAiGenerationChargeKind.ANLAS,
            anlas = modelCost * settings.count + preciseCost + extraVibeCost + encodingCost,
            encodingAnlas = encodingCost,
            extraVibeAnlas = extraVibeCost
        )
    }

    private const val MIN_PRICED_PIXELS = 65_536L
    private const val NORMAL_MAX_PIXELS = 1_048_576L
    private const val FREE_MAX_STEPS = 28
    private const val MIN_IMAGE_COST = 2
    private const val PIXEL_BASE_COST_FACTOR = 2.951823174884865e-6
    private const val PIXEL_STEP_COST_FACTOR = 5.753298233447344e-7
    private const val V5_PRICE_MULTIPLIER = 1.5
    private const val PRECISE_REFERENCE_COST = 5
    private const val VIBE_ENCODING_COST = 2
    private const val FREE_VIBE_COUNT = 4
    private const val EXTRA_VIBE_COST = 2
}
