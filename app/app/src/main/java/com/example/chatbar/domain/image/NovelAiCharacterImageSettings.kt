package com.example.chatbar.domain.image

import kotlinx.serialization.Serializable

@Serializable
data class NovelAiCharacterImageSettings(
    val sampler: NovelAiSampler = NovelAiSampler.EULER_ANCESTRAL,
    val steps: Int = 28,
    val guidance: Float = 8f,
    val cfgRescale: Float = 0f,
    val varietyPlus: Boolean = false,
    val effort: NovelAiEffort = NovelAiEffort.HIGH,
    val transparentBackground: Boolean = false
) {
    fun withDefaultSampling(model: NovelAiImageModel): NovelAiCharacterImageSettings = copy(
        steps = model.defaultSteps,
        guidance = model.defaultGuidance,
        cfgRescale = 0f,
        sampler = NovelAiSampler.EULER_ANCESTRAL
    )

    fun applyTo(settings: NovelAiGenerationSettings): NovelAiGenerationSettings = settings.copy(
        sampler = sampler,
        steps = steps,
        guidance = guidance,
        cfgRescale = cfgRescale,
        varietyPlus = varietyPlus,
        effort = effort,
        transparentBackground = transparentBackground,
        varietyPlusSigma = null
    ).normalized()
}
