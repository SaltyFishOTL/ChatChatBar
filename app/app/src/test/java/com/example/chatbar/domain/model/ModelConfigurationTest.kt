package com.example.chatbar.domain.model

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelConfigurationMode
import com.example.chatbar.data.local.entity.PresetChatModel
import com.example.chatbar.data.local.entity.ThemeMode
import com.example.chatbar.data.local.entity.normalized
import com.example.chatbar.data.local.entity.resolveDarkTheme
import com.example.chatbar.data.local.entity.withCurrentWebSearchDefaults
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConfigurationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun legacySettingsDefaultToOnlyApiMode() {
        val settings = json.decodeFromString(
            AppSettings.serializer(),
            """{"defaultModelId":"legacy-model","defaultEmbeddingId":"legacy-embedding"}"""
        )

        assertEquals(ModelConfigurationMode.CUSTOM_API, settings.modelConfigurationMode)
        assertEquals("legacy-model", settings.defaultModelId)
        assertEquals("legacy-embedding", settings.defaultEmbeddingId)
        assertEquals("", settings.siliconFlowApiKey)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertEquals(0, settings.tutorialVersion)
        assertTrue(settings.webSearchEnabled)
        assertEquals(0, settings.webSearchSettingsVersion)
        assertEquals(1, settings.webSearchMaxResultsPerQuery)
        assertEquals("", settings.novelAiImageAspectRatio)
    }

    @Test fun unversionedSearchSettingsMigrateToWikiEnabled() {
        val settings = json.decodeFromString(
            AppSettings.serializer(),
            """{"webSearchEnabled":false}"""
        ).withCurrentWebSearchDefaults()

        assertTrue(settings.webSearchEnabled)
        assertEquals(3, settings.webSearchSettingsVersion)
        assertEquals(1, settings.webSearchMaxResultsPerQuery)
    }

    @Test fun currentSearchSettingsPreserveUserDisabledState() {
        val settings = AppSettings(
            webSearchSettingsVersion = 3,
            webSearchEnabled = false
        ).withCurrentWebSearchDefaults()

        assertFalse(settings.webSearchEnabled)
        assertEquals(3, settings.webSearchSettingsVersion)
    }

    @Test fun unselectedDefaultImageModelFallsBackToDefaultChatModel() {
        val chat = model(apiKey = "key", id = "chat-model")
        val settings = AppSettings(defaultModelId = chat.id)

        assertEquals(chat, selectDefaultImageModel(settings, listOf(chat)))
        assertEquals(null, settings.defaultImageModelId)
    }

    @Test fun explicitDefaultImageModelOverridesDefaultChatModel() {
        val chat = model(apiKey = "key", id = "chat-model")
        val image = model(apiKey = "key", id = "image-model")
        val settings = AppSettings(
            defaultModelId = "chat-model",
            defaultImageModelId = "image-model"
        )

        assertEquals(image, selectDefaultImageModel(settings, listOf(chat, image)))
    }

    @Test fun unselectedFormatRepairModelFallsBackToDefaultChatModel() {
        val chat = model(apiKey = "key", id = "chat-model")

        assertEquals(chat, selectFormatRepairModel(null, emptyList(), chat))
        assertEquals(chat, selectFormatRepairModel("", emptyList(), chat))
    }

    @Test fun invalidExplicitFormatRepairModelDoesNotHideConfigurationFailure() {
        val chat = model(apiKey = "key", id = "chat-model")

        assertEquals(null, selectFormatRepairModel("missing", emptyList(), chat))
    }

    @Test fun v2SearchSettingsMigrateResultCountToOneWithoutReenabling() {
        val settings = AppSettings(
            webSearchSettingsVersion = 2,
            webSearchEnabled = false,
            webSearchMaxResultsPerQuery = 3
        ).withCurrentWebSearchDefaults()

        assertFalse(settings.webSearchEnabled)
        assertEquals(3, settings.webSearchSettingsVersion)
        assertEquals(1, settings.webSearchMaxResultsPerQuery)
    }

    @Test fun oldSearchMaxQueriesFieldIsIgnored() {
        val settings = json.decodeFromString(
            AppSettings.serializer(),
            """{"webSearchSettingsVersion":3,"webSearchMaxQueries":99,"webSearchMaxResultsPerQuery":1}"""
        )

        assertEquals(3, settings.webSearchSettingsVersion)
        assertEquals(1, settings.webSearchMaxResultsPerQuery)
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
        assertEquals(ModelConfigurationMode.CUSTOM_API, ModelConfigurationMode.DEFAULT.normalized())
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

    @Test fun chatModelKeyKeepsConfigurationUsableWhenSupportModelsMissing() {
        val status = modelConfigurationStatus(
            default = model(apiKey = "model-key"),
            retrieval = null,
            embedding = null
        )

        assertTrue(status.isUsable)
        assertEquals(emptyList<String>(), status.errors)
        assertEquals(
            listOf(
                "检索规划模型未配置，RAG 检索规划将回退到对话模型",
                "向量模型未配置，RAG 将不可用"
            ),
            status.warnings
        )
    }

    @Test fun blankChatModelKeyStillBlocksChat() {
        val status = modelConfigurationStatus(
            default = model(apiKey = ""),
            retrieval = null,
            embedding = null
        )

        assertFalse(status.isUsable)
        assertEquals(listOf("默认对话模型/API Key 未配置"), status.errors)
    }

    @Test fun optedInHttpModelAllowsBlankAuthentication() {
        val status = modelConfigurationStatus(
            default = model(apiKey = "", baseUrl = "http://127.0.0.1:8080/v1"),
            retrieval = null,
            embedding = null,
            allowCleartextModelApi = true
        )

        assertTrue(status.isUsable)
        assertEquals(emptyList<String>(), status.errors)
    }

    @Test fun optedInHttpModelDoesNotInheritGlobalApiKey() {
        val settings = AppSettings(
            siliconFlowApiKey = "global-key",
            allowCleartextModelApi = true
        )

        assertEquals(
            "",
            resolveEffectiveModelApiKey("", "http://127.0.0.1:8080/v1", settings)
        )
    }

    @Test fun optedInHttpModelKeepsItsOwnApiKey() {
        val settings = AppSettings(
            siliconFlowApiKey = "global-key",
            allowCleartextModelApi = true
        )

        assertEquals(
            "local-key",
            resolveEffectiveModelApiKey(" local-key ", "http://127.0.0.1:8080/v1", settings)
        )
    }

    @Test fun httpsModelStillInheritsGlobalApiKey() {
        val settings = AppSettings(
            siliconFlowApiKey = "global-key",
            allowCleartextModelApi = true
        )

        assertEquals(
            "global-key",
            resolveEffectiveModelApiKey("", "https://example.test/v1", settings)
        )
    }

    private fun model(
        apiKey: String,
        id: String = "m1",
        baseUrl: String = "https://example.test/v1"
    ): ModelConfig = ModelConfig(
        id = id,
        displayName = "M",
        baseUrl = baseUrl,
        apiKey = apiKey,
        modelName = "provider/model",
        createdAt = 1L
    )
}
