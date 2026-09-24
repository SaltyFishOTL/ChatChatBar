package com.example.chatbar.domain.image

import kotlin.math.floor

object NovelAiCharacterPositionPolicy {
    fun normalize(center: DesignedCharacterCenter, model: NovelAiImageModel): DesignedCharacterCenter {
        require(center.x.isFinite() && center.y.isFinite()) { "角色位置必须是有效数值" }
        fun coordinate(value: Float): Float {
            val bounded = value.coerceIn(0f, 1f)
            return if (model == NovelAiImageModel.V4_5_FULL) {
                (floor(bounded * 5).toInt().coerceIn(0, 4) + 0.5f) / 5f
            } else bounded
        }
        return DesignedCharacterCenter(coordinate(center.x), coordinate(center.y))
    }

    fun center(character: NovelAiCharacterPromptDraft, index: Int, count: Int, model: NovelAiImageModel) =
        normalize(character.center ?: NovelAiPromptDesigner.fallbackCenter(index, count), model)
}

fun NovelAiStudioDraft.toPromptPlan(): NovelAiPromptPlan = NovelAiPromptPlan(
    baseCaption = effectiveBasePrompt(),
    stylePrompt = stylePrompt,
    characterCaptions = characters.mapIndexed { index, character ->
        NovelAiCharacterCaption(
            prompt = character.prompt,
            center = character.center ?: NovelAiPromptDesigner.fallbackCenter(index, characters.size),
            negativePrompt = character.negativePrompt
        )
    },
    sizePreset = NovelAiImageSizePreset.PORTRAIT,
    negativePrompt = negativePrompt
)
