package com.example.chatbar.data.local.entity

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
    val chatBubbleFontScale: Float = 1.0f,
    val chatBackgroundImageOpacity: Float = DEFAULT_CHAT_BACKGROUND_IMAGE_OPACITY,
    val assistantSegmentedBubblesEnabled: Boolean = true,
    val tutorialVersion: Int = 0,
    val webSearchSettingsVersion: Int = 0,
    val webSearchEnabled: Boolean = true,
    val webSearchMaxResultsPerQuery: Int = 1,
    val novelAiImageAspectRatio: String = "",
    val imagePromptToolPreference: String = "",
    val momentsEnabled: Boolean = false,
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

fun AppSettings.withNormalizedAppearance(): AppSettings {
    val normalizedOpacity = chatBackgroundImageOpacity
        .takeIf { it.isFinite() }
        ?.coerceIn(0f, 1f)
        ?: DEFAULT_CHAT_BACKGROUND_IMAGE_OPACITY
    val normalizedEpisodeTurns = episodeMaxSourceTurns.coerceIn(
        MIN_EPISODE_MAX_SOURCE_TURNS,
        MAX_EPISODE_MAX_SOURCE_TURNS
    )
    return if (
        normalizedOpacity == chatBackgroundImageOpacity &&
        normalizedEpisodeTurns == episodeMaxSourceTurns
    ) {
        this
    } else {
        copy(
            chatBackgroundImageOpacity = normalizedOpacity,
            episodeMaxSourceTurns = normalizedEpisodeTurns
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

const val CURRENT_WEB_SEARCH_SETTINGS_VERSION = 3

fun AppSettings.withCurrentWebSearchDefaults(): AppSettings =
    if (webSearchSettingsVersion >= CURRENT_WEB_SEARCH_SETTINGS_VERSION) {
        this
    } else {
        copy(
            webSearchSettingsVersion = CURRENT_WEB_SEARCH_SETTINGS_VERSION,
            webSearchEnabled = if (webSearchSettingsVersion == 0) true else webSearchEnabled,
            webSearchMaxResultsPerQuery = 1
        )
    }

fun ModelConfigurationMode.normalized(): ModelConfigurationMode = when (this) {
    ModelConfigurationMode.DEFAULT,
    ModelConfigurationMode.CUSTOM_API,
    ModelConfigurationMode.FULL_CUSTOM -> ModelConfigurationMode.CUSTOM_API
}
