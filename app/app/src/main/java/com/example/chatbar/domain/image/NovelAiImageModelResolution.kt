package com.example.chatbar.domain.image

object NovelAiImageModelResolution {
    fun resolve(
        explicitOverride: NovelAiImageModel?,
        characterDefault: NovelAiImageModel?,
        globalDefault: NovelAiImageModel
    ): NovelAiImageModel = explicitOverride ?: characterDefault ?: globalDefault
}
