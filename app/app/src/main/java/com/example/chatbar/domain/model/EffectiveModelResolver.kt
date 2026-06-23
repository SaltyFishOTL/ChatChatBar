package com.example.chatbar.domain.model

import com.example.chatbar.BuildConfig
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelConfigurationMode
import com.example.chatbar.data.local.entity.PresetChatModel
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.data.repository.SettingsRepository

data class ModelConfigurationStatus(
    val isUsable: Boolean,
    val errors: List<String> = emptyList()
)

class EffectiveModelResolver(
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val presets: PresetModelCatalogService
) {
    suspend fun availableChatModels(): List<ModelConfig> = availableChatModels(settings.getAppSettings())

    suspend fun availableChatModels(appSettings: AppSettings): List<ModelConfig> =
        when (appSettings.modelConfigurationMode) {
            ModelConfigurationMode.FULL_CUSTOM -> models.getAllModels()
            else -> presetChatModels(appSettings)
        }

    suspend fun resolveChatModel(
        requestedId: String?,
        appSettings: AppSettings
    ): ModelConfig? {
        val available = availableChatModels(appSettings)
        return available.firstOrNull { it.id == requestedId }
            ?: defaultChatModel(appSettings, available)
    }

    suspend fun resolveChatModel(requestedId: String?): ModelConfig? = resolveChatModel(requestedId, settings.getAppSettings())

    suspend fun defaultChatModel(): ModelConfig? = defaultChatModel(settings.getAppSettings())

    suspend fun defaultChatModel(appSettings: AppSettings): ModelConfig? =
        defaultChatModel(appSettings, availableChatModels(appSettings))

    suspend fun auxiliaryChatModel(id: String?, appSettings: AppSettings): ModelConfig? {
        if (id == null) return null
        return when (appSettings.modelConfigurationMode) {
            ModelConfigurationMode.FULL_CUSTOM -> models.getModel(id)
            else -> id.removePrefix(PresetModelCatalogService.PRESET_REF_PREFIX)
                .let { key -> presets.catalog.chatModels.firstOrNull { it.modelKey == key && isConfigured(it) } }
                ?.toModelConfig(appSettings)
        }
    }

    suspend fun retrievalModel(): ModelConfig? = retrievalModel(settings.getAppSettings())

    suspend fun retrievalModel(appSettings: AppSettings): ModelConfig? =
        when (appSettings.modelConfigurationMode) {
            ModelConfigurationMode.FULL_CUSTOM -> models.getRetrievalModel()
            else -> presets.catalog.retrievalModel?.takeIf(::isConfigured)?.toModelConfig(appSettings)
        }

    suspend fun embeddingModel(): EmbeddingConfig? = embeddingModel(settings.getAppSettings())

    suspend fun embeddingModel(appSettings: AppSettings): EmbeddingConfig? =
        when (appSettings.modelConfigurationMode) {
            ModelConfigurationMode.FULL_CUSTOM -> models.getEmbeddingModel()
            else -> presets.catalog.embeddingModel?.takeIf { PresetModelPolicy.isConfigured(it.modelName) }?.let {
                EmbeddingConfig(
                    id = presetRef(it.modelKey),
                    displayName = it.displayName,
                    baseUrl = presets.catalog.baseUrl,
                    apiKey = effectivePresetApiKey(appSettings),
                    modelName = it.modelName,
                    dimensions = it.dimensions
                )
            }
        }

    suspend fun status(): ModelConfigurationStatus = status(settings.getAppSettings())

    suspend fun status(appSettings: AppSettings): ModelConfigurationStatus {
        if (appSettings.modelConfigurationMode == ModelConfigurationMode.FULL_CUSTOM) {
            return ModelConfigurationStatus(
                isUsable = defaultChatModel(appSettings) != null,
                errors = buildList {
                    if (defaultChatModel(appSettings) == null) add("未配置可用的默认对话模型")
                    if (models.getRetrievalModel() == null) add("未配置检索规划模型")
                    if (models.getEmbeddingModel() == null) add("未配置向量模型，RAG 将不可用")
                }
            )
        }
        val errors = buildList {
            if (effectivePresetApiKey(appSettings).isBlank()) {
                add(if (appSettings.modelConfigurationMode == ModelConfigurationMode.CUSTOM_API) "请配置硅基流动 API Key" else "内置共享 API Key 未配置")
            }
            if (presets.catalog.chatModels.none { it.selectableForChat && isConfigured(it) }) add("预制对话模型未配置")
            if (presets.catalog.retrievalModel?.let(::isConfigured) != true) add("预制检索规划模型未配置")
            if (presets.catalog.embeddingModel?.modelName?.let(PresetModelPolicy::isConfigured) != true) add("预制向量模型未配置")
        }
        return ModelConfigurationStatus(errors.isEmpty(), errors)
    }

    fun effectivePresetApiKey(appSettings: AppSettings): String =
        if (appSettings.modelConfigurationMode == ModelConfigurationMode.CUSTOM_API) {
            appSettings.siliconFlowApiKey.trim()
        } else BuildConfig.SILICONFLOW_API_KEY.trim()

    private fun presetChatModels(appSettings: AppSettings): List<ModelConfig> {
        val configured = presets.catalog.chatModels.filter { it.selectableForChat && isConfigured(it) }
        return configured.map { it.toModelConfig(appSettings, presets.catalog.chatModels.filter(::isConfigured)) }
    }

    private fun defaultChatModel(appSettings: AppSettings, available: List<ModelConfig>): ModelConfig? {
        val id = when (appSettings.modelConfigurationMode) {
            ModelConfigurationMode.FULL_CUSTOM -> appSettings.defaultModelId
            else -> appSettings.presetDefaultModelKey?.let(::presetRef)
        }
        return available.firstOrNull { it.id == id } ?: available.firstOrNull()
    }

    private fun PresetChatModel.toModelConfig(
        appSettings: AppSettings,
        allChatModels: List<PresetChatModel> = presets.catalog.chatModels
    ): ModelConfig = ModelConfig(
        id = presetRef(modelKey),
        displayName = displayName,
        baseUrl = presets.catalog.baseUrl,
        apiKey = effectivePresetApiKey(appSettings),
        modelName = modelName,
        isMultimodal = isMultimodal,
        visionModelId = visionModelKey?.takeIf { key -> allChatModels.any { it.modelKey == key } }?.let(::presetRef),
        templateType = templateType,
        customParams = customParams,
        reasoningEffort = reasoningEffort,
        enableThinking = enableThinking,
        maxOutputTokens = maxOutputTokens,
        createdAt = 0L
    )

    private fun isConfigured(model: PresetChatModel): Boolean = PresetModelPolicy.isConfigured(model.modelName)
    private fun presetRef(key: String): String = PresetModelCatalogService.PRESET_REF_PREFIX + key
}
