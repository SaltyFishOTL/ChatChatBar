package com.example.chatbar.ui.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.data.local.entity.ParamValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 大模型连接设置编辑器 ViewModel
 */
class ModelEditViewModel(private val modelId: String?) : ViewModel() {
    private val modelRepository = ChatBarApp.instance.modelRepository

    private val _modelConfig = MutableStateFlow<ModelConfig?>(null)
    val modelConfig: StateFlow<ModelConfig?> = _modelConfig.asStateFlow()

    private val _availableMultimodalModels = MutableStateFlow<List<ModelConfig>>(emptyList())
    val availableMultimodalModels: StateFlow<List<ModelConfig>> = _availableMultimodalModels.asStateFlow()

    // 编辑器表单状态
    var displayName by mutableStateOf("")
    var baseUrl by mutableStateOf("")
    var apiKey by mutableStateOf("")
    var modelName by mutableStateOf("")
    var templateType by mutableStateOf(ModelTemplate.OPENAI)
    var isMultimodal by mutableStateOf(false)
    var visionModelId by mutableStateOf<String?>(null)
    val customParamsMap = mutableStateMapOf<String, ParamValue>()

    init {
        loadModelConfig()
    }

    private fun loadModelConfig() {
        viewModelScope.launch {
            // 加载所有的多模态模型（用于在非多模态模型下选择关联的视觉模型）
            val all = modelRepository.getAllModels()
            _availableMultimodalModels.value = all.filter { it.isMultimodal && it.id != modelId }

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
        customParamsMap["temperature"] = ParamValue.NumberValue(0.7)
        customParamsMap["max_tokens"] = ParamValue.NumberValue(1500.0)

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
                customParams = customParamsMap.toMap(),
                createdAt = now
            )

            modelRepository.saveModel(config)
            onSuccess()
        }
    }
}
