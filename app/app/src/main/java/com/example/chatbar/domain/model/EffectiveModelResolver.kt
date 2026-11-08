package com.example.chatbar.domain.model

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.PresetChatModel
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.data.repository.SettingsRepository
import java.net.URI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

data class ModelConfigurationStatus(
    val isUsable: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

class EffectiveModelResolver(
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val presets: PresetModelCatalogService
) {
    /** All long-lived model consumers subscribe here instead of caching one-off reads. */
    fun observeConfiguration(): Flow<EffectiveModelConfiguration> = flow {
        models.initialize()
        settings.initialize()
        emitAll(combine(models.models, models.embeddingModel, settings.appSettings) { stored, embedding, appSettings ->
            configuration(stored, embedding, appSettings)
        }.distinctUntilChanged())
    }

    suspend fun configuration(): EffectiveModelConfiguration = configuration(settings.getAppSettings())

    suspend fun configuration(appSettings: AppSettings): EffectiveModelConfiguration = configuration(
        models.getAllModels(), models.getEmbeddingModel(), appSettings
    )

    private fun configuration(
        stored: List<ModelConfig>,
        embedding: EmbeddingConfig?,
        appSettings: AppSettings
    ): EffectiveModelConfiguration {
        val configured = stored.filter { it.baseUrl.isNotBlank() && PresetModelPolicy.isConfigured(it.modelName) }
            .distinctBy(ModelConfig::id).map { it.withEffectiveApiKey(appSettings) }
        return EffectiveModelConfiguration(
            settings = appSettings,
            chatModels = configured.filter { it.selectableForChat }.ifEmpty { presetChatModels(appSettings) },
            auxiliaryTextModels = configured,
            multimodalModels = configured.filter { it.isMultimodal },
            embeddingModel = effectiveEmbeddingModel(embedding, appSettings)
        )
    }

    suspend fun availableChatModels(): List<ModelConfig> = availableChatModels(settings.getAppSettings())

    suspend fun availableChatModels(appSettings: AppSettings): List<ModelConfig> =
        configuration(appSettings).chatModels

    suspend fun resolveChatModel(
        requestedId: String?,
        appSettings: AppSettings
    ): ModelConfig? {
        return configuration(appSettings).chatModel(requestedId)
    }

    suspend fun resolveChatModel(requestedId: String?): ModelConfig? =
        resolveChatModel(requestedId, settings.getAppSettings())

    suspend fun defaultChatModel(): ModelConfig? = defaultChatModel(settings.getAppSettings())

    suspend fun defaultChatModel(appSettings: AppSettings): ModelConfig? =
        configuration(appSettings).defaultChatModel

    suspend fun resolveImageModel(
        requestedId: String?,
        appSettings: AppSettings
    ): ModelConfig? {
        return configuration(appSettings).imageModel(requestedId)
    }

    suspend fun resolveImageModel(requestedId: String?): ModelConfig? =
        resolveImageModel(requestedId, settings.getAppSettings())

    suspend fun defaultImageModel(): ModelConfig? = defaultImageModel(settings.getAppSettings())

    suspend fun defaultImageModel(appSettings: AppSettings): ModelConfig? =
        configuration(appSettings).defaultImageModel

    suspend fun resolveFormatRepairModel(
        requestedId: String?,
        appSettings: AppSettings
    ): ModelConfig? {
        val configuration = configuration(appSettings)
        return selectFormatRepairModel(requestedId, configuration.auxiliaryTextModels, configuration.defaultChatModel)
    }

    suspend fun resolveFormatRepairModel(requestedId: String?): ModelConfig? =
        resolveFormatRepairModel(requestedId, settings.getAppSettings())

    suspend fun auxiliaryChatModel(id: String?, appSettings: AppSettings): ModelConfig? {
        if (id == null) return null
        return models.getModel(id)?.withEffectiveApiKey(appSettings)
            ?: id.removePrefix(PresetModelCatalogService.PRESET_REF_PREFIX)
                .let { key -> presets.catalog.chatModels.firstOrNull { it.modelKey == key && isConfigured(it) } }
                ?.toModelConfig(appSettings)
    }

    suspend fun auxiliaryChatModel(id: String?): ModelConfig? =
        auxiliaryChatModel(id, settings.getAppSettings())

    /** 所有已配置文本模型，包括不在普通对话选择器显示的模型。 */
    suspend fun availableAuxiliaryTextModels(appSettings: AppSettings): List<ModelConfig> {
        return configuration(appSettings).auxiliaryTextModels
    }

    suspend fun availableAuxiliaryTextModels(): List<ModelConfig> =
        availableAuxiliaryTextModels(settings.getAppSettings())

    /** 精确解析专用文本模型；缺失时不回退。 */
    suspend fun resolveAuxiliaryTextModelExact(
        id: String?,
        appSettings: AppSettings
    ): ModelConfig? = id?.let { requestedId ->
        availableAuxiliaryTextModels(appSettings).firstOrNull { it.id == requestedId }
    }

    suspend fun resolveAuxiliaryTextModelExact(id: String?): ModelConfig? =
        resolveAuxiliaryTextModelExact(id, settings.getAppSettings())

    suspend fun embeddingModel(): EmbeddingConfig? = embeddingModel(settings.getAppSettings())

    suspend fun embeddingModel(appSettings: AppSettings): EmbeddingConfig? =
        effectiveEmbeddingModel(models.getEmbeddingModel(), appSettings)

    private fun effectiveEmbeddingModel(stored: EmbeddingConfig?, appSettings: AppSettings): EmbeddingConfig? =
        (stored?.withEffectiveApiKey(appSettings)
            ?: presets.catalog.embeddingModel?.takeIf { PresetModelPolicy.isConfigured(it.modelName) }?.let {
                EmbeddingConfig(
                    id = presetRef(it.modelKey),
                    displayName = it.displayName,
                    baseUrl = presets.catalog.baseUrl,
                    apiKey = effectivePresetApiKey(appSettings),
                    modelName = it.modelName,
                    dimensions = it.dimensions
                )
            }
        )?.takeIf { it.hasConfiguredAuthentication(appSettings) }

    suspend fun status(): ModelConfigurationStatus = status(null, settings.getAppSettings())

    suspend fun status(appSettings: AppSettings): ModelConfigurationStatus = status(null, appSettings)

    suspend fun status(
        requestedChatModelId: String?,
        appSettings: AppSettings
    ): ModelConfigurationStatus {
        return configuration(appSettings).status(requestedChatModelId)
    }

    fun effectivePresetApiKey(appSettings: AppSettings): String =
        appSettings.siliconFlowApiKey.trim()

    private fun presetChatModels(appSettings: AppSettings): List<ModelConfig> {
        val configured = presets.catalog.chatModels.filter { it.selectableForChat && isConfigured(it) }
        return configured.map { it.toModelConfig(appSettings, presets.catalog.chatModels.filter(::isConfigured)) }
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
        selectableForChat = selectableForChat,
        isMultimodal = isMultimodal,
        visionModelId = visionModelKey?.takeIf { key -> allChatModels.any { it.modelKey == key } }?.let(::presetRef),
        templateType = templateType,
        customParams = customParams,
        reasoningEffort = reasoningEffort,
        enableThinking = enableThinking,
        maxOutputTokens = maxOutputTokens,
        supportsJsonMode = presets.catalog.provider.equals("SILICONFLOW", ignoreCase = true),
        supportsDisableThinking = presets.catalog.provider.equals("SILICONFLOW", ignoreCase = true),
        sourcePresetKey = modelKey,
        sourcePresetVersion = presets.entries().firstOrNull()?.version,
        createdAt = 0L
    )

    private fun isConfigured(model: PresetChatModel): Boolean = PresetModelPolicy.isConfigured(model.modelName)
    private fun presetRef(key: String): String = PresetModelCatalogService.PRESET_REF_PREFIX + key

    private fun ModelConfig.withEffectiveApiKey(appSettings: AppSettings): ModelConfig =
        copy(apiKey = resolveEffectiveModelApiKey(apiKey, baseUrl, appSettings))

    private fun EmbeddingConfig.withEffectiveApiKey(appSettings: AppSettings): EmbeddingConfig =
        copy(apiKey = resolveEffectiveModelApiKey(apiKey, baseUrl, appSettings))

    private fun EmbeddingConfig.hasConfiguredAuthentication(appSettings: AppSettings): Boolean =
        isModelAuthenticationConfigured(baseUrl, apiKey, appSettings.allowCleartextModelApi)
}

internal fun ModelConfig.hasConfiguredAuthentication(appSettings: AppSettings): Boolean =
    hasConfiguredAuthentication(appSettings.allowCleartextModelApi)

internal fun ModelConfig.hasConfiguredAuthentication(allowCleartextModelApi: Boolean): Boolean =
    isModelAuthenticationConfigured(baseUrl, apiKey, allowCleartextModelApi)

internal fun selectFormatRepairModel(
    requestedId: String?,
    available: List<ModelConfig>,
    defaultChatModel: ModelConfig?
): ModelConfig? = if (requestedId.isNullOrBlank()) {
    defaultChatModel
} else {
    available.firstOrNull { it.id == requestedId }
}

internal fun selectChatModel(
    requestedId: String?,
    appSettings: AppSettings,
    available: List<ModelConfig>
): ModelConfig? = available.firstOrNull { it.id == requestedId }
    ?: selectDefaultChatModel(appSettings, available)

internal fun selectDefaultChatModel(
    appSettings: AppSettings,
    available: List<ModelConfig>
): ModelConfig? {
    val id = appSettings.defaultModelId
        ?: appSettings.presetDefaultModelKey?.let { PresetModelCatalogService.PRESET_REF_PREFIX + it }
    return available.firstOrNull { it.id == id } ?: available.firstOrNull()
}

internal fun selectDefaultImageModel(
    appSettings: AppSettings,
    available: List<ModelConfig>
): ModelConfig? {
    val id = appSettings.defaultImageModelId
    return available.firstOrNull { it.id == id } ?: selectDefaultChatModel(appSettings, available)
}

internal fun modelConfigurationStatus(
    default: ModelConfig?,
    embedding: EmbeddingConfig?,
    allowCleartextModelApi: Boolean = false
): ModelConfigurationStatus {
    val errors = buildList {
        if (default == null) add("未配置可用默认对话模型")
        else if (!isModelAuthenticationConfigured(default.baseUrl, default.apiKey, allowCleartextModelApi)) {
            add("默认对话模型/API Key 未配置")
        }
    }
    val warnings = buildList {
        if (embedding == null) add("向量模型未配置，RAG 将不可用")
    }
    return ModelConfigurationStatus(
        isUsable = errors.isEmpty(),
        errors = errors,
        warnings = warnings
    )
}

internal fun resolveEffectiveModelApiKey(
    modelApiKey: String,
    baseUrl: String,
    appSettings: AppSettings
): String {
    val localKey = modelApiKey.trim()
    if (localKey.isNotEmpty()) return localKey
    if (appSettings.allowCleartextModelApi && baseUrl.isCleartextHttpUrl()) return ""
    return appSettings.siliconFlowApiKey.trim()
}

internal fun isModelAuthenticationConfigured(
    baseUrl: String,
    apiKey: String,
    allowCleartextModelApi: Boolean
): Boolean = apiKey.isNotBlank() || (allowCleartextModelApi && baseUrl.isCleartextHttpUrl())

private fun String.isCleartextHttpUrl(): Boolean = runCatching {
    URI(trim()).scheme.equals("http", ignoreCase = true)
}.getOrDefault(false)
