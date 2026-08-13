package com.example.chatbar.domain.image

import com.example.chatbar.domain.prompt.PromptTemplates

object CharacterAvatarImagePolicy {
    val imageSize = NovelAiImageSize(
        width = 512,
        height = 512,
        label = "Small Square"
    )

    fun promptPlan(
        stylePrompt: String,
        characterPrompt: String,
        negativePrompt: String
    ): NovelAiPromptPlan = NovelAiPromptPlan(
        baseCaption = PromptTemplates.novelAiCharacterAvatarPositivePrompt(
            stylePrompt,
            characterPrompt
        ),
        characterCaptions = emptyList(),
        sizePreset = NovelAiImageSizePreset.SQUARE,
        negativePrompt = PromptTemplates.effectiveCharacterNaiNegativePrompt(negativePrompt)
    )
}
