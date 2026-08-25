package com.example.chatbar.data.local.entity

import com.example.chatbar.domain.appearance.DefaultThemeColorHsv
import com.example.chatbar.domain.appearance.ThemeColorHistoryPolicy
import com.example.chatbar.domain.appearance.ThemeColorHsv
import com.example.chatbar.domain.image.NovelAiImageModel
import kotlinx.serialization.Serializable

/**
 * 应用全局设置
 */
@Serializable
data class AppSettings(
    val defaultModelId: String? = null,
    val defaultImageModelId: String? = null,
    val modelConfigurationMode: ModelConfigurationMode = ModelConfigurationMode.CUSTOM_API,
    val presetDefaultModelKey: String? = null,
    val siliconFlowApiKey: String = "",
    val allowCleartextModelApi: Boolean = false,
    val defaultEmbeddingId: String? = null,
    val defaultFormatCardId: String? = null,
    val automaticFormatCheckEnabled: Boolean = false,
    val formatRepairModelId: String? = null,
    val memoryRagTopK: Int = 3,
    val memoryRagSimilarityThreshold: Float = 0.35f,
    val docRagTopK: Int = 3,
    val docRagSimilarityThreshold: Float = 0.55f,
    val ragInjectionMode: String = "STANDARD",
    val defaultContextWindowSize: Int = 20,
    val episodeMaxSourceTurns: Int = DEFAULT_EPISODE_MAX_SOURCE_TURNS,
    val excludeAssistantStatusFromHistory: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val themeColor: ThemeColorHsv = DefaultThemeColorHsv,
    val themeColorHistory: List<ThemeColorHsv> = emptyList(),
    val chatBubbleFontScale: Float = 1.0f,
    val chatBackgroundImageOpacity: Float = DEFAULT_CHAT_BACKGROUND_IMAGE_OPACITY,
    val assistantSegmentedBubblesEnabled: Boolean = true,
    val tutorialVersion: Int = 0,
    val webSearchSettingsVersion: Int = 0,
    // Legacy shared switch retained for decoding and migrating older settings.
    val webSearchEnabled: Boolean = true,
    val characterAutoFillWebSearchEnabled: Boolean = true,
    val characterRewriteWebSearchEnabled: Boolean = true,
    val characterAutoFillResearchSourceMode: CharacterResearchSourceMode =
        CharacterResearchSourceMode.ENCYCLOPEDIA_SEARCH,
    val characterRewriteResearchSourceMode: CharacterResearchSourceMode =
        CharacterResearchSourceMode.ENCYCLOPEDIA_SEARCH,
    val webSearchMaxResultsPerQuery: Int = 1,
    val novelAiImageModel: NovelAiImageModel = NovelAiImageModel.V4_5_FULL,
    val novelAiImageAspectRatio: String = "",
    val novelAiPromptTranslationConsent: NovelAiPromptTranslationConsent =
        NovelAiPromptTranslationConsent.DISABLED,
    val imagePromptToolPreference: String = "",
    val fishAudioTtsModelId: String = DEFAULT_FISH_AUDIO_TTS_MODEL,
    val voiceTagModelId: String? = null,
    val audiobookModeEnabled: Boolean = false,
    val momentsEnabled: Boolean = false,
    val momentsImagesEnabled: Boolean = true,
    val momentsMinDelayHours: Int = 2,
    val momentsMaxDelayHours: Int = 13,
    val momentsBackgroundGuideDismissed: Boolean = false,
    val momentsAutoStartConfirmed: Boolean = false,
    val lastSeenMomentsAt: Long = 0L,
    val lastSeenChatAt: Long = 0L
)

const val DEFAULT_CHAT_BACKGROUND_IMAGE_OPACITY = 0.16f
const val DEFAULT_EPISODE_MAX_SOURCE_TURNS = 2
const val MIN_EPISODE_MAX_SOURCE_TURNS = 1
const val MAX_EPISODE_MAX_SOURCE_TURNS = 6
const val DEFAULT_FISH_AUDIO_TTS_MODEL = "s2.1-pro-free"

fun AppSettings.withNormalizedAppearance(): AppSettings {
    val normalizedOpacity = chatBackgroundImageOpacity
        .takeIf { it.isFinite() }
        ?.coerceIn(0f, 1f)
        ?: DEFAULT_CHAT_BACKGROUND_IMAGE_OPACITY
    val normalizedEpisodeTurns = episodeMaxSourceTurns.coerceIn(
        MIN_EPISODE_MAX_SOURCE_TURNS,
        MAX_EPISODE_MAX_SOURCE_TURNS
    )
    val normalizedThemeColor = themeColor.normalized()
    val normalizedThemeColorHistory = ThemeColorHistoryPolicy.normalize(
        current = normalizedThemeColor,
        history = themeColorHistory
    )
    return if (
        normalizedOpacity == chatBackgroundImageOpacity &&
        normalizedEpisodeTurns == episodeMaxSourceTurns &&
        normalizedThemeColor == themeColor &&
        normalizedThemeColorHistory == themeColorHistory
    ) {
        this
    } else {
        copy(
            chatBackgroundImageOpacity = normalizedOpacity,
            episodeMaxSourceTurns = normalizedEpisodeTurns,
            themeColor = normalizedThemeColor,
            themeColorHistory = normalizedThemeColorHistory
        )
    }
}

@Serializable
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

fun ThemeMode.resolveDarkTheme(systemDarkTheme: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDarkTheme
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Serializable
enum class ModelConfigurationMode {
    DEFAULT,
    CUSTOM_API,
    FULL_CUSTOM
}

@Serializable
enum class CharacterResearchSourceMode {
    NONE,
    ENCYCLOPEDIA_SEARCH,
    MANUAL_URLS,
    ENCYCLOPEDIA_SEARCH_AND_MANUAL_URLS
}

@Serializable
enum class NovelAiPromptTranslationConsent {
    UNDECIDED,
    ENABLED,
    DISABLED
}

const val CURRENT_WEB_SEARCH_SETTINGS_VERSION = 5

fun AppSettings.withCurrentWebSearchDefaults(): AppSettings =
    if (webSearchSettingsVersion >= CURRENT_WEB_SEARCH_SETTINGS_VERSION) {
        this
    } else {
        val migratedEnabled = if (webSearchSettingsVersion == 0) true else webSearchEnabled
        val autoFillEnabled = if (webSearchSettingsVersion >= 4) {
            characterAutoFillWebSearchEnabled
        } else {
            migratedEnabled
        }
        val rewriteEnabled = if (webSearchSettingsVersion >= 4) {
            characterRewriteWebSearchEnabled
        } else {
            migratedEnabled
        }
        copy(
            webSearchSettingsVersion = CURRENT_WEB_SEARCH_SETTINGS_VERSION,
            webSearchEnabled = migratedEnabled,
            characterAutoFillWebSearchEnabled = autoFillEnabled,
            characterRewriteWebSearchEnabled = rewriteEnabled,
            characterAutoFillResearchSourceMode = autoFillEnabled.toCharacterResearchSourceMode(),
            characterRewriteResearchSourceMode = rewriteEnabled.toCharacterResearchSourceMode(),
            webSearchMaxResultsPerQuery = 1
        )
    }

private fun Boolean.toCharacterResearchSourceMode(): CharacterResearchSourceMode =
    if (this) {
        CharacterResearchSourceMode.ENCYCLOPEDIA_SEARCH
    } else {
        CharacterResearchSourceMode.NONE
    }

fun ModelConfigurationMode.normalized(): ModelConfigurationMode = when (this) {
    ModelConfigurationMode.DEFAULT,
    ModelConfigurationMode.CUSTOM_API,
    ModelConfigurationMode.FULL_CUSTOM -> ModelConfigurationMode.CUSTOM_API
}
