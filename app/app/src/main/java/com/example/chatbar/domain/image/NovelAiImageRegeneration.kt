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
