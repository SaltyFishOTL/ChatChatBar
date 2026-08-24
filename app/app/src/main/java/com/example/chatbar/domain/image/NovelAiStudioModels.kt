package com.example.chatbar.domain.image

import com.example.chatbar.domain.prompt.PromptTemplates
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class NovelAiImageModel(
    val apiId: String,
    val displayName: String,
    val maxCharacters: Int,
    val promptTokenLimit: Int,
    val tokenizerKind: NovelAiTokenizerKind
) {
    V4_5_FULL("nai-diffusion-4-5-full", "V4.5 Full", 6, 512, NovelAiTokenizerKind.T5),
    V5_FULL("nai-diffusion-5-full", "V5 Full", 22, 1471, NovelAiTokenizerKind.QWEN)
}

enum class NovelAiTokenizerKind { T5, QWEN }

@Serializable
enum class NovelAiSampler(val apiId: String, val displayName: String) {
    EULER_ANCESTRAL("k_euler_ancestral", "Euler Ancestral"),
    EULER("k_euler", "Euler"),
    DPM_PLUS_PLUS_2S_ANCESTRAL("k_dpmpp_2s_ancestral", "DPM++ 2S Ancestral"),
    DPM_PLUS_PLUS_2M("k_dpmpp_2m", "DPM++ 2M"),
    DPM_PLUS_PLUS_SDE("k_dpmpp_sde", "DPM++ SDE"),
    DDIM("ddim_v3", "DDIM")
}

@Serializable
enum class NovelAiSeedMode { RANDOM, FIXED }

@Serializable
enum class NovelAiSizeTier(val displayName: String) {
    SMALL("Small"), NORMAL("Normal"), LARGE("Large"), WALLPAPER("Wallpaper")
}

@Serializable
enum class NovelAiAspectRatio(val displayName: String) {
    PORTRAIT("Portrait"), SQUARE("Square"), LANDSCAPE("Landscape")
}

@Serializable
data class NovelAiGenerationSettings(
    val model: NovelAiImageModel = NovelAiImageModel.V4_5_FULL,
    val sizeTier: NovelAiSizeTier = NovelAiSizeTier.NORMAL,
    val aspectRatio: NovelAiAspectRatio = NovelAiAspectRatio.PORTRAIT,
    val count: Int = 1,
    val steps: Int = 28,
    val guidance: Float = 6f,
    val seedMode: NovelAiSeedMode = NovelAiSeedMode.RANDOM,
    val seed: Long = 0L,
    val sampler: NovelAiSampler = NovelAiSampler.EULER_ANCESTRAL
) {
    val maxAllowedBaseSeed: Long get() = MAX_SEED - (count.coerceIn(1, 4) - 1L)

    fun normalized(): NovelAiGenerationSettings = copy(
        aspectRatio = if (sizeTier == NovelAiSizeTier.WALLPAPER && aspectRatio == NovelAiAspectRatio.SQUARE) {
            NovelAiAspectRatio.PORTRAIT
        } else {
            aspectRatio
        }
    )

    fun imageSize(): NovelAiImageSize {
        val normalized = normalized()
        val dimensions = when (normalized.sizeTier) {
            NovelAiSizeTier.SMALL -> when (normalized.aspectRatio) {
                NovelAiAspectRatio.PORTRAIT -> 512 to 768
                NovelAiAspectRatio.SQUARE -> 640 to 640
                NovelAiAspectRatio.LANDSCAPE -> 768 to 512
            }
            NovelAiSizeTier.NORMAL -> when (normalized.aspectRatio) {
                NovelAiAspectRatio.PORTRAIT -> 832 to 1216
                NovelAiAspectRatio.SQUARE -> 1024 to 1024
                NovelAiAspectRatio.LANDSCAPE -> 1216 to 832
            }
            NovelAiSizeTier.LARGE -> when (normalized.aspectRatio) {
                NovelAiAspectRatio.PORTRAIT -> 1024 to 1536
                NovelAiAspectRatio.SQUARE -> 1472 to 1472
                NovelAiAspectRatio.LANDSCAPE -> 1536 to 1024
            }
            NovelAiSizeTier.WALLPAPER -> when (normalized.aspectRatio) {
                NovelAiAspectRatio.PORTRAIT, NovelAiAspectRatio.SQUARE -> 1088 to 1920
                NovelAiAspectRatio.LANDSCAPE -> 1920 to 1088
            }
        }
        return NovelAiImageSize(dimensions.first, dimensions.second, "${sizeTier.displayName} ${normalized.aspectRatio.displayName}")
    }

    fun validationError(characterCount: Int): String? = when {
        count !in 1..4 -> "生成数量必须在 1–4 之间"
        steps !in 1..50 -> "Steps 必须在 1–50 之间"
        guidance !in 1f..10f -> "Guidance 必须在 1.0–10.0 之间"
        seedMode == NovelAiSeedMode.FIXED && seed !in MIN_SEED..maxAllowedBaseSeed -> "当前数量下 Seed 必须在 $MIN_SEED–$maxAllowedBaseSeed 之间"
        characterCount > model.maxCharacters -> "${model.displayName} 最多支持 ${model.maxCharacters} 个角色；当前 $characterCount 个"
        else -> null
    }

    companion object {
        const val MIN_SEED = 0L
        const val MAX_SEED = 4_294_967_295L
        const val MAX_BASE_SEED = MAX_SEED - 3L

        fun legacy(
            seed: Int,
            count: Int = 1,
            model: NovelAiImageModel = NovelAiImageModel.V4_5_FULL
        ): NovelAiGenerationSettings = NovelAiGenerationSettings(
            model = model,
            count = count,
            steps = 28,
            guidance = 8f,
            seedMode = NovelAiSeedMode.FIXED,
            seed = seed.toLong(),
            sampler = NovelAiSampler.EULER_ANCESTRAL
        )
    }
}

@Serializable
data class NovelAiCharacterPromptDraft(
    val id: String = UUID.randomUUID().toString(),
    val prompt: String = "",
    val negativePrompt: String = "",
    val negativeExpanded: Boolean = false
)

@Serializable
data class NovelAiCharacterPromptSource(
    val name: String = "",
    val prompt: String = ""
)

@Serializable
data class NovelAiPositivePromptSnapshot(
    val basePrompt: String = "",
    val characterPrompts: List<String> = emptyList()
)

@Serializable
data class NovelAiStudioDraft(
    val stylePrompt: String = "",
    val basePrompt: String = "",
    val characters: List<NovelAiCharacterPromptDraft> = emptyList(),
    val importedCharacterCardId: String? = null,
    val importedCharacterPromptSources: List<NovelAiCharacterPromptSource> = emptyList(),
    val negativePrompt: String = PromptTemplates.defaultCharacterNaiNegativePrompt(),
    val naturalLanguageMode: Boolean = false,
    val imageDescription: String = "",
    val extraRequirement: String = "",
    val selectedModel: NovelAiImageModel = NovelAiImageModel.V4_5_FULL,
    val v45Settings: NovelAiGenerationSettings = NovelAiGenerationSettings(model = NovelAiImageModel.V4_5_FULL),
    val v5Settings: NovelAiGenerationSettings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL),
    val outputExpanded: Boolean = true,
    val negativeExpanded: Boolean = false,
    val advancedExpanded: Boolean = false,
    val aiPanelExpanded: Boolean = false,
    val conversionSnapshot: NovelAiPositivePromptSnapshot? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val activeSettings: NovelAiGenerationSettings
        get() = when (selectedModel) {
            NovelAiImageModel.V4_5_FULL -> v45Settings.copy(model = selectedModel).normalized()
            NovelAiImageModel.V5_FULL -> v5Settings.copy(model = selectedModel).normalized()
        }

    fun withActiveSettings(settings: NovelAiGenerationSettings): NovelAiStudioDraft = when (settings.model) {
        NovelAiImageModel.V4_5_FULL -> copy(selectedModel = settings.model, v45Settings = settings.normalized())
        NovelAiImageModel.V5_FULL -> copy(selectedModel = settings.model, v5Settings = settings.normalized())
    }

    fun importCharacterCardPromptSources(
        cardId: String,
        cardStylePrompt: String,
        sources: List<NovelAiCharacterPromptSource>
    ): NovelAiStudioDraft = copy(
        stylePrompt = cardStylePrompt.trim().ifBlank { stylePrompt },
        importedCharacterCardId = cardId,
        importedCharacterPromptSources = sources
    )
}

@Serializable
data class NovelAiStudioUndoDraft(val draft: NovelAiStudioDraft? = null)

@Serializable
data class NovelAiGenerationRecipe(
    val stylePrompt: String = "",
    val basePrompt: String = "",
    val characters: List<NovelAiCharacterPromptDraft> = emptyList(),
    val negativePrompt: String = PromptTemplates.defaultCharacterNaiNegativePrompt(),
    val naturalLanguageMode: Boolean = false,
    val settings: NovelAiGenerationSettings = NovelAiGenerationSettings()
)

@Serializable
data class NovelAiGenerationHistoryImage(
    val path: String = "",
    val seed: Long = 0L
)

@Serializable
data class NovelAiGenerationHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val images: List<NovelAiGenerationHistoryImage> = emptyList(),
    val recipe: NovelAiGenerationRecipe = NovelAiGenerationRecipe(),
    val createdAt: Long = System.currentTimeMillis()
)

fun novelAiHistoryImages(paths: List<String>, baseSeed: Long): List<NovelAiGenerationHistoryImage> =
    paths.mapIndexed { index, path -> NovelAiGenerationHistoryImage(path, baseSeed + index) }

enum class NovelAiHistoryApplyMode { FULL, NEW_SEED, SEED_ONLY }

fun NovelAiStudioDraft.applyHistoryRecipe(
    recipe: NovelAiGenerationRecipe,
    imageSeed: Long,
    mode: NovelAiHistoryApplyMode
): NovelAiStudioDraft = when (mode) {
    NovelAiHistoryApplyMode.FULL -> copy(
        stylePrompt = recipe.stylePrompt,
        basePrompt = recipe.basePrompt,
        characters = recipe.characters,
        negativePrompt = recipe.negativePrompt,
        selectedModel = recipe.settings.model,
        conversionSnapshot = null
    ).withActiveSettings(recipe.settings.copy(seedMode = NovelAiSeedMode.FIXED, seed = imageSeed))
    NovelAiHistoryApplyMode.NEW_SEED -> copy(
        stylePrompt = recipe.stylePrompt,
        basePrompt = recipe.basePrompt,
        characters = recipe.characters,
        negativePrompt = recipe.negativePrompt,
        selectedModel = recipe.settings.model,
        conversionSnapshot = null
    ).withActiveSettings(recipe.settings.copy(seedMode = NovelAiSeedMode.RANDOM))
    NovelAiHistoryApplyMode.SEED_ONLY -> withActiveSettings(
        activeSettings.copy(seedMode = NovelAiSeedMode.FIXED, seed = imageSeed)
    )
}

fun NovelAiStudioDraft.toRecipe(settings: NovelAiGenerationSettings = activeSettings): NovelAiGenerationRecipe =
    NovelAiGenerationRecipe(
        stylePrompt = stylePrompt,
        basePrompt = basePrompt,
        characters = characters,
        negativePrompt = negativePrompt,
        settings = settings
    )

fun NovelAiStudioDraft.copyPositivePrompt(): String {
    val characterBlock = characters.joinToString("\n") { character ->
        val lines = character.prompt.lines()
        buildString {
            append("- ")
            append(lines.firstOrNull().orEmpty())
            lines.drop(1).forEach { line ->
                append("\n  ")
                append(line)
            }
        }
    }
    return "$stylePrompt\n\n$basePrompt\n\n$characterBlock"
}
