package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.GeneratedImageCharacterPrompt
import com.example.chatbar.data.local.entity.GeneratedImageMetadata

data class NovelAiImageRegenerationDraft(
    val baseCaption: String,
    val characterPrompts: List<GeneratedImageCharacterPrompt>,
    val negativePrompt: String,
    val sizePreset: String,
    val width: Int,
    val height: Int
) {
    val canRegenerate: Boolean
        get() = baseCaption.isNotBlank() && characterPrompts.all { it.prompt.isNotBlank() }

    fun addCharacterPrompt(): NovelAiImageRegenerationDraft {
        if (characterPrompts.size >= NOVEL_AI_MAX_CHARACTER_PROMPTS) return this
        val newCount = characterPrompts.size + 1
        val center = NovelAiPromptDesigner.fallbackCenter(characterPrompts.size, newCount)
        return copy(
            characterPrompts = characterPrompts + GeneratedImageCharacterPrompt(
                prompt = "",
                centerX = center.x,
                centerY = center.y
            )
        )
    }

    fun removeCharacterPrompt(index: Int): NovelAiImageRegenerationDraft {
        if (index !in characterPrompts.indices) return this
        return copy(characterPrompts = characterPrompts.filterIndexed { itemIndex, _ -> itemIndex != index })
    }

    fun toPromptPlan(): NovelAiPromptPlan = NovelAiPromptPlan(
        baseCaption = baseCaption,
        characterCaptions = characterPrompts.map {
            NovelAiCharacterCaption(
                prompt = it.prompt,
                center = DesignedCharacterCenter(it.centerX, it.centerY)
            )
        },
        sizePreset = NovelAiImageSizePreset.from(sizePreset),
        negativePrompt = negativePrompt
    )
}

const val NOVEL_AI_MAX_CHARACTER_PROMPTS = 6

fun GeneratedImageMetadata.toRegenerationDraft(): NovelAiImageRegenerationDraft =
    NovelAiImageRegenerationDraft(
        baseCaption = baseCaption,
        characterPrompts = characterPrompts,
        negativePrompt = negativePrompt,
        sizePreset = sizePreset,
        width = width,
        height = height
    )

fun NovelAiPromptPlan.toGeneratedImageMetadata(
    imagePath: String,
    imageSize: NovelAiImageSize
): GeneratedImageMetadata = GeneratedImageMetadata(
    imagePath = imagePath,
    baseCaption = baseCaption,
    characterPrompts = characterCaptions.map {
        GeneratedImageCharacterPrompt(
            prompt = it.prompt,
            centerX = it.center.x,
            centerY = it.center.y
        )
    },
    negativePrompt = effectiveNegativePrompt,
    sizePreset = sizePreset.name,
    width = imageSize.width,
    height = imageSize.height
)
