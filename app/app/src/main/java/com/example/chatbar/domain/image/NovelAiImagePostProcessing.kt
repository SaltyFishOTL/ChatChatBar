package com.example.chatbar.domain.image

import kotlinx.serialization.json.JsonObject
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class NovelAiPostProcessTab(val label: String) { ENHANCE("Enhance（增强）"), UPSCALE("Upscale（放大）") }

enum class NovelAiEnhanceScale(val label: String, val factor: Double) {
    ORIGINAL("1×", 1.0), HALF("1.5×", 1.5), DOUBLE("2×", 2.0), MAX("Max✨", 0.0)
}

data class NovelAiEnhanceOptions(
    val scale: NovelAiEnhanceScale = NovelAiEnhanceScale.ORIGINAL,
    val strength: Float = 0.5f,
    val noise: Float = 0f
) {
    val magnitude: Int? get() = PRESETS.indexOf(strength to noise).takeIf { it >= 0 }?.plus(1)
    fun withMagnitude(level: Int): NovelAiEnhanceOptions {
        val preset = PRESETS[level.coerceIn(1, 5) - 1]
        return copy(strength = preset.first, noise = preset.second)
    }
    companion object {
        private val PRESETS = listOf(0.2f to 0f, 0.4f to 0f, 0.5f to 0f, 0.6f to 0f, 0.7f to 0.1f)
    }
}

data class NovelAiEnhanceSource(
    val prompt: NovelAiPromptPlan,
    val settings: NovelAiGenerationSettings,
    val parameters: JsonObject
)

/** Request-only options; never persisted into the ordinary studio draft. */
data class NovelAiEnhanceRequestOptions(
    val upscaledEnhance: Boolean,
    val sourceParameters: JsonObject
)

data class NovelAiPostProcessResult(
    val image: ImportedProcessImage,
    val options: NovelAiEnhanceOptions? = null
)

data class NovelAiPostProcessState(
    val sourcePath: String? = null,
    val enhanceSource: NovelAiEnhanceSource? = null,
    val enhanceUnavailable: String? = null,
    val tab: NovelAiPostProcessTab = NovelAiPostProcessTab.UPSCALE,
    val options: NovelAiEnhanceOptions = NovelAiEnhanceOptions(),
    val enhanceResult: NovelAiPostProcessResult? = null,
    val upscaleResult: NovelAiPostProcessResult? = null,
    val busy: Boolean = false,
    val cancelling: Boolean = false,
    val status: String? = null,
    val error: String? = null
) {
    val result: NovelAiPostProcessResult? get() = when (tab) {
        NovelAiPostProcessTab.ENHANCE -> enhanceResult
        NovelAiPostProcessTab.UPSCALE -> upscaleResult
    }
    val parametersChanged: Boolean get() = tab == NovelAiPostProcessTab.ENHANCE &&
        enhanceResult != null && enhanceResult.options != options
}

object NovelAiPostProcessPolicy {
    const val MAX_PIXELS = 3_145_728L

    fun upscaleCost(width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0) return null
        val pixels = width.toLong() * height
        return when {
            pixels <= 1_048_576 -> 1
            pixels <= 1_747_627 -> 2
            pixels <= 2_446_678 -> 3
            pixels <= MAX_PIXELS -> 4
            else -> null
        }
    }

    fun scales(width: Int, height: Int, model: NovelAiImageModel): List<NovelAiEnhanceScale> {
        if (width <= 0 || height <= 0) return emptyList()
        val pixels = width.toLong() * height
        val normalPortrait = setOf(width, height) == setOf(832, 1216)
        return NovelAiEnhanceScale.entries.filter { scale ->
            if (scale == NovelAiEnhanceScale.MAX) {
                model == NovelAiImageModel.V5_FULL && pixels < MAX_PIXELS * 0.8 &&
                    runCatching { maxOutputSize(width, height) }.isSuccess
            } else {
                val w = (width * scale.factor).toInt()
                val h = (height * scale.factor).toInt()
                w >= 64 && h >= 64 && w.toLong() * h <= MAX_PIXELS &&
                    (w % 64 == 0 && h % 64 == 0 || normalPortrait && scale == NovelAiEnhanceScale.HALF)
            }
        }
    }

    fun requestSize(width: Int, height: Int, scale: NovelAiEnhanceScale): NovelAiImageSize =
        if (scale == NovelAiEnhanceScale.MAX) NovelAiImageSize(width, height, "Max 输入")
        else NovelAiImageSize((width * scale.factor).toInt(), (height * scale.factor).toInt(), scale.label)

    fun outputSize(width: Int, height: Int, scale: NovelAiEnhanceScale): NovelAiImageSize =
        if (scale == NovelAiEnhanceScale.MAX) maxOutputSize(width, height) else requestSize(width, height, scale)

    // Matches the standalone Max enhancement geometry used for pricing by the official client.
    fun maxOutputSize(width: Int, height: Int): NovelAiImageSize {
        val w = width / 16 * 32
        val h = height / 16 * 32
        require(w > 0 && h > 0) { "图片长宽比不支持 Max" }
        val factor = minOf(1.0, sqrt(MAX_PIXELS.toDouble() / (w.toLong() * h)))
        var outW = (w * factor / 32).roundToInt() * 32
        var outH = (h * factor / 32).roundToInt() * 32
        if (outW.toLong() * outH > MAX_PIXELS) {
            outW = floor(w * factor / 32).toInt() * 32
            outH = floor(h * factor / 32).toInt() * 32
        }
        require(outW >= 32 && outH >= 32) { "图片长宽比不支持 Max" }
        return NovelAiImageSize(outW, outH, "Max")
    }

    fun enhanceCost(
        source: NovelAiEnhanceSource,
        width: Int,
        height: Int,
        options: NovelAiEnhanceOptions,
        account: NovelAiAccountUsage?
    ): NovelAiGenerationCost {
        val size = outputSize(width, height, options.scale)
        val pixels = if (options.scale == NovelAiEnhanceScale.MAX) size.width.toLong() * size.height
        else (size.width / 64.0).roundToInt().toLong() * 64 * (size.height / 64.0).roundToInt() * 64
        val v5 = source.settings.model == NovelAiImageModel.V5_FULL
        if (account?.isActiveOpus == true && pixels <= 1_048_576 && source.settings.effectiveSteps <= 28 &&
            (!v5 || account.v5AllowancePercent != null && !account.v5AllowanceExhausted)) {
            return NovelAiGenerationCost(if (v5) NovelAiGenerationChargeKind.V5_ALLOWANCE else NovelAiGenerationChargeKind.FREE)
        }
        // Price the decimal value serialized on the wire, not its Float-to-Double rounding residue.
        val strength = options.strength.toString().toDouble()
        return NovelAiGenerationCost(NovelAiGenerationChargeKind.ANLAS,
            NovelAiSingleImagePricing.cost(source.settings, pixels, strength))
    }
}
