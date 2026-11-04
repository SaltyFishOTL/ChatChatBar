package com.example.chatbar.domain.image

import kotlin.math.ceil

/** Per-sample pricing from NovelAI web build b94bdee-41520193098-1, module 643656.
 * Medium keeps its 23-step base fractional until the final V5/strength rounding.
 * Callers own canvas geometry, free eligibility, batch count and additional fees.
 */
internal object NovelAiSingleImagePricing {
    fun cost(settings: NovelAiGenerationSettings, pixels: Long, strength: Double = 1.0): Int {
        val base = if (settings.isMediumEffort) {
            (PIXEL_BASE * pixels + PIXEL_STEP * pixels * 23) / 1.75
        } else {
            ceil(PIXEL_BASE * pixels + PIXEL_STEP * pixels * settings.effectiveSteps)
        }
        val multiplier = if (settings.model == NovelAiImageModel.V5_FULL) 1.5 else 1.0
        return ceil(base * multiplier * strength).toInt().coerceAtLeast(2)
    }

    private const val PIXEL_BASE = 2.951823174884865e-6
    private const val PIXEL_STEP = 5.753298233447344e-7
}
