package com.example.chatbar.domain.model

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelConfigurationMode
import com.example.chatbar.data.local.entity.PresetChatModel
import com.example.chatbar.data.local.entity.ThemeMode
import com.example.chatbar.data.local.entity.normalized
import com.example.chatbar.data.local.entity.resolveDarkTheme
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConfigurationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun legacySettingsDefaultToDefaultMode() {
        val settings = json.decodeFromString(
            AppSettings.serializer(),
            """{"defaultModelId":"legacy-model","defaultEmbeddingId":"legacy-embedding"}"""
        )

        assertEquals(ModelConfigurationMode.DEFAULT, settings.modelConfigurationMode)
        assertEquals("legacy-model", settings.defaultModelId)
        assertEquals("legacy-embedding", settings.defaultEmbeddingId)
        assertEquals("", settings.siliconFlowApiKey)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertEquals(0, settings.tutorialVersion)
    }

    @Test fun presetPlaceholdersAreNotConfigured() {
        assertFalse(PresetModelPolicy.isConfigured(""))
        assertFalse(PresetModelPolicy.isConfigured("TODO_FILL_MODEL_ID"))
        assertTrue(PresetModelPolicy.isConfigured("Qwen/Qwen3-8B"))
    }

    @Test fun themeModesResolveAgainstSystemTheme() {
        assertFalse(ThemeMode.SYSTEM.resolveDarkTheme(false))
        assertTrue(ThemeMode.SYSTEM.resolveDarkTheme(true))
        assertFalse(ThemeMode.LIGHT.resolveDarkTheme(true))
        assertTrue(ThemeMode.DARK.resolveDarkTheme(false))
    }

    @Test fun oldPresetChatModelRemainsSelectable() {
        val model = json.decodeFromString(
            PresetChatModel.serializer(),
            """{"modelKey":"chat","displayName":"Chat","modelName":"provider/model"}"""
        )

        assertTrue(model.selectableForChat)
    }

    @Test fun fullCustomModeNormalizesToCustomApi() {
        assertEquals(ModelConfigurationMode.CUSTOM_API, ModelConfigurationMode.FULL_CUSTOM.normalized())
        assertEquals(ModelConfigurationMode.DEFAULT, ModelConfigurationMode.DEFAULT.normalized())
    }

    @Test fun oldModelConfigDecodesWithVisibleModelDefaults() {
        val model = json.decodeFromString(
            ModelConfig.serializer(),
            """{"id":"m1","displayName":"M","baseUrl":"https://example.test/v1","apiKey":"","modelName":"provider/model","createdAt":1}"""
        )

        assertTrue(model.selectableForChat)
        assertEquals(null, model.sourcePresetKey)
        assertEquals(null, model.sourcePresetVersion)
    }
}
