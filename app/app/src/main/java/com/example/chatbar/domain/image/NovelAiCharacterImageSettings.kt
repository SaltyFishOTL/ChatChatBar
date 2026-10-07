package com.example.chatbar.domain.image

import kotlinx.serialization.Serializable

@Serializable
data class NovelAiCharacterImageSettings(
    val sampler: NovelAiSampler = NovelAiSampler.EULER_ANCESTRAL,
    val steps: Int = 28,
    val guidance: Float = 8f,
    val cfgRescale: Float = 0f,
    val varietyPlus: Boolean = false
) {
    fun applyTo(settings: NovelAiGenerationSettings): NovelAiGenerationSettings = settings.copy(
        sampler = sampler,
        steps = steps,
        guidance = guidance,
        cfgRescale = cfgRescale,
        varietyPlus = varietyPlus
    ).normalized()
}
