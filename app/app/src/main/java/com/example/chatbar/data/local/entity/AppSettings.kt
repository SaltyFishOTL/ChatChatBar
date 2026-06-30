package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable

/**
 * 应用全局设置
 */
@Serializable
data class AppSettings(
    val defaultModelId: String? = null,
    val modelConfigurationMode: ModelConfigurationMode = ModelConfigurationMode.DEFAULT,
    val presetDefaultModelKey: String? = null,
    val siliconFlowApiKey: String = "",
    val defaultEmbeddingId: String? = null,
    val defaultFormatCardId: String? = null,
    val memoryRagTopK: Int = 3,
    val memoryRagSimilarityThreshold: Float = 0.35f,
    val docRagTopK: Int = 3,
    val docRagSimilarityThreshold: Float = 0.55f,
    val ragInjectionMode: String = "STANDARD",
    val defaultContextWindowSize: Int = 20,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val chatBubbleFontScale: Float = 1.0f,
    val tutorialVersion: Int = 0
)

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

fun ModelConfigurationMode.normalized(): ModelConfigurationMode = when (this) {
    ModelConfigurationMode.FULL_CUSTOM -> ModelConfigurationMode.CUSTOM_API
    else -> this
}
