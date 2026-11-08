package com.example.chatbar.ui.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.data.local.entity.ParamValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import com.example.chatbar.domain.model.ModelDiscoveryService
import com.example.chatbar.domain.model.resolveEffectiveModelApiKey

/**
 * 大模型连接设置编辑器 ViewModel
 */
class ModelEditViewModel(private val modelId: String?) : ViewModel() {
    private val modelRepository = ChatBarApp.instance.modelRepository

    private val _modelConfig = MutableStateFlow<ModelConfig?>(null)
    val modelConfig: StateFlow<ModelConfig?> = _modelConfig.asStateFlow()

    val availableMultimodalModels: StateFlow<List<ModelConfig>> =
        ChatBarApp.instance.effectiveModelResolver.observeConfiguration()
            .map { configuration -> configuration.multimodalModels.filter { it.id != modelId } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // 编辑器表单状态
    var displayName by mutableStateOf("")
    private var baseUrlDraft by mutableStateOf("")
    var baseUrl: String
        get() = baseUrlDraft
        set(value) {
            if (value != baseUrlDraft) invalidateModelDiscovery()
            baseUrlDraft = value
        }
    private var apiKeyDraft by mutableStateOf("")
    var apiKey: String
        get() = apiKeyDraft
        set(value) {
            if (value != apiKeyDraft) invalidateModelDiscovery()
            apiKeyDraft = value
        }
    var modelName by mutableStateOf("")
    var templateType by mutableStateOf(ModelTemplate.OPENAI)
    var isMultimodal by mutableStateOf(false)
    var visionModelId by mutableStateOf<String?>(null)
    var formatPromptPosition by mutableStateOf(FormatPromptPosition.BOTH)
    val customParamsMap = mutableStateMapOf<String, ParamValue>()

    var discoveredModelIds by mutableStateOf<List<String>>(emptyList())
        private set
    var isDiscoveringModels by mutableStateOf(false)
        private set
    var modelDiscoveryError by mutableStateOf<String?>(null)
        private set
    var showModelPicker by mutableStateOf(false)
    private var discoveryJob: Job? = null
    private var discoveryGeneration = 0L
    private val discoveryService = ModelDiscoveryService()

    private fun invalidateModelDiscovery() {
        discoveryGeneration++
        discoveryJob?.cancel()
        discoveredModelIds = emptyList()
        isDiscoveringModels = false
        modelDiscoveryError = null
        showModelPicker = false
    }

    fun openModelPicker() {
        showModelPicker = true
        if (discoveredModelIds.isEmpty() && !isDiscoveringModels) fetchAvailableModels()
    }

    fun fetchAvailableModels() {
        if (isDiscoveringModels) return
        discoveryGeneration++
        modelDiscoveryError = null
        showModelPicker = true
        val generation = discoveryGeneration
        val url = baseUrl.trim()
        val key = apiKey
        isDiscoveringModels = true
        discoveryJob = viewModelScope.launch {
            try {
                val settings = ChatBarApp.instance.settingsRepository.getAppSettings()
                val ids = discoveryService.fetch(
                    url, resolveEffectiveModelApiKey(key, url, settings), settings.allowCleartextModelApi
                )
                if (generation == discoveryGeneration) {
                    discoveredModelIds = ids
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == discoveryGeneration) {
                    modelDiscoveryError = e.message ?: "获取模型列表失败，请重试"
                }
            } finally {
                if (generation == discoveryGeneration) isDiscoveringModels = false
            }
        }
    }

    fun selectDiscoveredModel(id: String) {
        if (id !in discoveredModelIds) return
        modelName = id
        if (displayName.isBlank()) displayName = id
        showModelPicker = false
    }

    init {
        loadModelConfig()
    }

    private fun loadModelConfig() {
        viewModelScope.launch {
            if (modelId != null) {
                val config = modelRepository.getModel(modelId)
                _modelConfig.value = config
                if (config != null) {
                    displayName = config.displayName
                    baseUrl = config.baseUrl
                    apiKey = config.apiKey
                    modelName = config.modelName
                    templateType = config.templateType
                    isMultimodal = config.isMultimodal
                    visionModelId = config.visionModelId
                    formatPromptPosition = config.formatPromptPosition
                    customParamsMap.putAll(config.customParams)
                }
            } else {
                applyTemplateDefaults(ModelTemplate.OPENAI)
            }
        }
    }

    /**
     * 根据选择的模板，自动填充默认参数
     */
    fun applyTemplateDefaults(template: ModelTemplate) {
        templateType = template
        customParamsMap.clear()
        
        // 默认通用参数配置
        customParamsMap["temperature"] = ParamValue.NumberValue(1.0)

        when (template) {
            ModelTemplate.OPENAI -> {
                baseUrl = "https://api.openai.com/v1"
                modelName = "gpt-4o-mini"
            }
            ModelTemplate.CLAUDE -> {
                baseUrl = "https://api.anthropic.com/v1"
                modelName = "claude-3-5-sonnet-latest"
            }
            ModelTemplate.GEMINI -> {
                baseUrl = "https://generativelanguage.googleapis.com/v1beta"
                modelName = "gemini-2.5-flash"
            }
            ModelTemplate.CUSTOM -> {
                // 自定义保持空白
                baseUrl = ""
                modelName = ""
            }
        }
    }

    /**
     * 保存模型配置
     */
    fun saveModelConfig(onSuccess: () -> Unit) {
        if (displayName.isBlank() || baseUrl.isBlank() || modelName.isBlank()) return

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val config = _modelConfig.value?.copy(
                displayName = displayName,
                baseUrl = baseUrl,
                apiKey = apiKey.trim(),
                modelName = modelName,
                templateType = templateType,
                isMultimodal = isMultimodal,
                visionModelId = visionModelId.takeIf { !isMultimodal },
                formatPromptPosition = formatPromptPosition,
                customParams = customParamsMap.toMap()
            ) ?: ModelConfig(
                id = modelId ?: java.util.UUID.randomUUID().toString(),
                displayName = displayName,
                baseUrl = baseUrl,
                apiKey = apiKey.trim(),
                modelName = modelName,
                templateType = templateType,
                isMultimodal = isMultimodal,
                visionModelId = visionModelId.takeIf { !isMultimodal },
                formatPromptPosition = formatPromptPosition,
                customParams = customParamsMap.toMap(),
                createdAt = now
            )

            modelRepository.saveModel(config)
            onSuccess()
        }
    }
}
