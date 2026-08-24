package com.example.chatbar.domain.image

data class NovelAiImportedCharacterPrompt(
    val prompt: String = "",
    val negativePrompt: String = ""
)

data class NovelAiImportedGenerationSettings(
    val model: NovelAiImageModel? = null,
    val sizeTier: NovelAiSizeTier? = null,
    val aspectRatio: NovelAiAspectRatio? = null,
    val count: Int? = null,
    val steps: Int? = null,
    val guidance: Float? = null,
    val sampler: NovelAiSampler? = null
) {
    val hasAny: Boolean
        get() = model != null || sizeTier != null || aspectRatio != null || count != null ||
            steps != null || guidance != null || sampler != null
}

data class NovelAiStudioPngMetadata(
    val imagePath: String,
    val positivePrompt: String,
    val negativePrompt: String? = null,
    val characters: List<NovelAiImportedCharacterPrompt> = emptyList(),
    val hasCharacterPrompts: Boolean = false,
    val settings: NovelAiImportedGenerationSettings = NovelAiImportedGenerationSettings(),
    val seed: Long? = null,
    val width: Int,
    val height: Int
)

data class NovelAiStudioMetadataSelection(
    val positivePrompt: Boolean = true,
    val negativePrompt: Boolean = true,
    val characterPrompts: Boolean = true,
    val generationSettings: Boolean = true,
    val seed: Boolean = true
)

fun NovelAiStudioDraft.applyImportedMetadata(
    metadata: NovelAiStudioPngMetadata,
    selection: NovelAiStudioMetadataSelection
): NovelAiStudioDraft {
    var result = copy(
        basePrompt = metadata.positivePrompt.takeIf { selection.positivePrompt } ?: basePrompt,
        negativePrompt = metadata.negativePrompt
            ?.takeIf { selection.negativePrompt }
            ?: negativePrompt,
        characters = if (selection.characterPrompts && metadata.hasCharacterPrompts) {
            metadata.characters.map { character ->
                NovelAiCharacterPromptDraft(
                    prompt = character.prompt,
                    negativePrompt = character.negativePrompt
                )
            }
        } else {
            characters
        },
        conversionSnapshot = if (selection.positivePrompt ||
            (selection.characterPrompts && metadata.hasCharacterPrompts)
        ) {
            null
        } else {
            conversionSnapshot
        }
    )

    if (selection.generationSettings && metadata.settings.hasAny) {
        val imported = metadata.settings
        val targetModel = imported.model ?: result.selectedModel
        val current = when (targetModel) {
            NovelAiImageModel.V4_5_FULL -> result.v45Settings
            NovelAiImageModel.V5_FULL -> result.v5Settings
        }
        result = result.withActiveSettings(
            current.copy(
                model = targetModel,
                sizeTier = imported.sizeTier ?: current.sizeTier,
                aspectRatio = imported.aspectRatio ?: current.aspectRatio,
                count = imported.count ?: current.count,
                steps = imported.steps ?: current.steps,
                guidance = imported.guidance ?: current.guidance,
                sampler = imported.sampler ?: current.sampler
            )
        )
    }

    if (selection.seed && metadata.seed != null) {
        result = result.withActiveSettings(
            result.activeSettings.copy(
                seedMode = NovelAiSeedMode.FIXED,
                seed = metadata.seed
            )
        )
    }
    return result
}
