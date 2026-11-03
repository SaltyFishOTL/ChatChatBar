package com.example.chatbar.domain.image

data class NovelAiImportedCharacterPrompt(
    val prompt: String = "",
    val negativePrompt: String = "",
    val center: DesignedCharacterCenter? = null
)

data class NovelAiImportedGenerationSettings(
    val model: NovelAiImageModel? = null,
    val sizeTier: NovelAiSizeTier? = null,
    val aspectRatio: NovelAiAspectRatio? = null,
    val count: Int? = null,
    val steps: Int? = null,
    val guidance: Float? = null,
    val cfgRescale: Float? = null,
    val varietyPlus: Boolean? = null,
    val varietyPlusSigma: Double? = null,
    val sampler: NovelAiSampler? = null,
    val customWidth: Int? = null,
    val customHeight: Int? = null,
    val useCharacterPositions: Boolean? = null,
    val effort: NovelAiEffort? = null,
    val transparentBackground: Boolean? = null
) {
    val hasAny: Boolean
        get() = model != null || sizeTier != null || aspectRatio != null || count != null ||
            steps != null || guidance != null || cfgRescale != null || varietyPlus != null || sampler != null ||
            customWidth != null || customHeight != null || useCharacterPositions != null || effort != null || transparentBackground != null
}

data class NovelAiImportedImageGuidance(
    val action: NovelAiGenerationAction = NovelAiGenerationAction.TEXT_TO_IMAGE,
    val baseImageBase64: String? = null,
    val maskBase64: String? = null,
    val imageToImageStrength: Float? = null,
    val imageToImageNoise: Float? = null,
    val inpaintStrength: Float? = null,
    val preciseImageBase64: String? = null,
    val preciseType: NovelAiPreciseReferenceType? = null,
    val preciseStrength: Float? = null,
    val preciseFidelity: Float? = null,
    val vibes: List<NovelAiVibeReferenceDraft> = emptyList()
) {
    val hasAny: Boolean get() = action != NovelAiGenerationAction.TEXT_TO_IMAGE ||
        baseImageBase64 != null || maskBase64 != null || preciseImageBase64 != null || vibes.isNotEmpty()
}

data class NovelAiStudioPngMetadata(
    val imagePath: String,
    val positivePrompt: String,
    val negativePrompt: String? = null,
    val characters: List<NovelAiImportedCharacterPrompt> = emptyList(),
    val hasCharacterPrompts: Boolean = false,
    val settings: NovelAiImportedGenerationSettings = NovelAiImportedGenerationSettings(),
    val imageGuidance: NovelAiImportedImageGuidance = NovelAiImportedImageGuidance(),
    val seed: Long? = null,
    val width: Int,
    val height: Int
) {
    // A hint is not provenance: the same bytes can contain a handwritten suffix.
    val hasAmbiguousTransparentSuffix: Boolean
        get() = settings.model == NovelAiImageModel.V5_FULL && settings.transparentBackground == true &&
            NovelAiV5TextPromptPolicy.withoutTransparentBackgroundSuffix(positivePrompt) != null
}

enum class NovelAiTransparentSuffixImport(val displayName: String) {
    KEEP_AS_PROMPT("保留在正文中"),
    MANAGE_WITH_SWITCH("交给透明背景开关管理")
}

enum class NovelAiCharacterImportMode(val displayName: String) {
    OFF("关"),
    REPLACE("覆盖"),
    APPEND("新增")
}

data class NovelAiStudioMetadataSelection(
    val positivePrompt: Boolean = true,
    val negativePrompt: Boolean = true,
    val characterPrompts: NovelAiCharacterImportMode = NovelAiCharacterImportMode.REPLACE,
    val generationSettings: Boolean = true,
    val seed: Boolean = true,
    val imageGuidance: Boolean = true,
    val transparentSuffix: NovelAiTransparentSuffixImport = NovelAiTransparentSuffixImport.KEEP_AS_PROMPT
)

fun NovelAiStudioDraft.applyImportedMetadata(
    metadata: NovelAiStudioPngMetadata,
    selection: NovelAiStudioMetadataSelection
): NovelAiStudioDraft {
    val importCharacters = metadata.hasCharacterPrompts && when (selection.characterPrompts) {
        NovelAiCharacterImportMode.OFF -> false
        NovelAiCharacterImportMode.REPLACE -> true
        NovelAiCharacterImportMode.APPEND -> metadata.characters.isNotEmpty()
    }
    // The raw PNG/metadata remain untouched. Only the explicitly selected studio
    // import removes one user-confirmed option suffix. Logical text then persists
    // normally in draft/undo/history; Enhance keeps its independent resolved caption.
    val importedPositive = if (metadata.hasAmbiguousTransparentSuffix &&
        selection.transparentSuffix == NovelAiTransparentSuffixImport.MANAGE_WITH_SWITCH) {
        requireNotNull(NovelAiV5TextPromptPolicy.withoutTransparentBackgroundSuffix(metadata.positivePrompt))
    } else metadata.positivePrompt
    var result = copy(
        basePrompt = importedPositive.takeIf { selection.positivePrompt } ?: basePrompt,
        extraPrompt = if (selection.positivePrompt) "" else extraPrompt,
        negativePrompt = metadata.negativePrompt
            ?.takeIf { selection.negativePrompt }
            ?: negativePrompt,
        characters = if (importCharacters) {
            val importedCharacters = metadata.characters.map { character ->
                NovelAiCharacterPromptDraft(
                    prompt = character.prompt,
                    negativePrompt = character.negativePrompt,
                    center = character.center
                )
            }
            if (selection.characterPrompts == NovelAiCharacterImportMode.APPEND) characters + importedCharacters
            else importedCharacters
        } else {
            characters
        },
        conversionSnapshot = if (selection.positivePrompt || importCharacters) {
            null
        } else {
            conversionSnapshot
        }
    )

    if (selection.generationSettings && metadata.settings.hasAny) {
        val imported = metadata.settings
        val targetModel = imported.model ?: result.selectedModel
        if (imported.model != null) {
            result = result.copy(followDefaultNovelAiImageModel = false)
        }
        val current = when (targetModel) {
            NovelAiImageModel.V4_5_FULL -> result.v45Settings
            NovelAiImageModel.V5_FULL -> result.v5Settings
        }
        result = result.withActiveSettings(
            current.copy(
                model = targetModel,
                sizeTier = imported.sizeTier ?: current.sizeTier,
                aspectRatio = imported.aspectRatio ?: current.aspectRatio,
                customWidth = if (imported.sizeTier != null || imported.customWidth != null) imported.customWidth else current.customWidth,
                customHeight = if (imported.sizeTier != null || imported.customHeight != null) imported.customHeight else current.customHeight,
                count = imported.count ?: current.count,
                steps = imported.steps ?: current.steps,
                guidance = imported.guidance ?: current.guidance,
                cfgRescale = imported.cfgRescale ?: current.cfgRescale,
                varietyPlus = imported.varietyPlus ?: current.varietyPlus,
                varietyPlusSigma = if (imported.varietyPlus != null) imported.varietyPlusSigma else current.varietyPlusSigma,
                sampler = imported.sampler ?: current.sampler,
                useCharacterPositions = imported.useCharacterPositions ?: current.useCharacterPositions,
                effort = imported.effort ?: current.effort,
                transparentBackground = imported.transparentBackground ?: current.transparentBackground
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
    if (selection.imageGuidance && metadata.imageGuidance.hasAny) {
        val imported = metadata.imageGuidance
        val activeAction = imported.action.takeIf {
            imported.baseImageBase64 != null && (it != NovelAiGenerationAction.INPAINT || imported.maskBase64 != null)
        } ?: NovelAiGenerationAction.TEXT_TO_IMAGE
        result = result.copy(
            imageGuidance = result.imageGuidance.copy(
                action = activeAction,
                imageToImageStrength = imported.imageToImageStrength ?: result.imageGuidance.imageToImageStrength,
                imageToImageNoise = imported.imageToImageNoise ?: result.imageGuidance.imageToImageNoise,
                inpaintStrength = imported.inpaintStrength ?: result.imageGuidance.inpaintStrength,
                referenceMode = when {
                    imported.preciseImageBase64 != null -> NovelAiReferenceMode.PRECISE
                    imported.vibes.isNotEmpty() -> NovelAiReferenceMode.VIBE
                    else -> NovelAiReferenceMode.NONE
                },
                preciseReference = result.imageGuidance.preciseReference.copy(
                    type = imported.preciseType ?: result.imageGuidance.preciseReference.type,
                    strength = imported.preciseStrength ?: result.imageGuidance.preciseReference.strength,
                    fidelity = imported.preciseFidelity ?: result.imageGuidance.preciseReference.fidelity
                ),
                vibes = imported.vibes
            )
        )
    }
    return result
}
