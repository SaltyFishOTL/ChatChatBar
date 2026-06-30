package com.example.chatbar.data.local.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val PRESET_MODEL_ID_PREFIX = "preset:"

/**
 * 模型模板类型
 */
@Serializable
enum class ModelTemplate {
    OPENAI,
    CLAUDE,
    GEMINI,
    CUSTOM
}

/**
 * 自定义参数值 - 支持多种类型
 */
@Serializable
sealed class ParamValue {
    @Serializable
    @SerialName("number")
    data class NumberValue(val value: Double) : ParamValue()

    @Serializable
    @SerialName("boolean")
    data class BooleanValue(val value: Boolean) : ParamValue()

    @Serializable
    @SerialName("string")
    data class StringValue(val value: String) : ParamValue()
}

/**
 * LLM模型配置
 */
@Serializable
data class ModelConfig(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val apiKey: String,
    val modelName: String,
    val selectableForChat: Boolean = true,
    val isMultimodal: Boolean = false,
    val visionModelId: String? = null, // 文本模型关联的视觉模型
    val templateType: ModelTemplate = ModelTemplate.OPENAI,
    val customParams: Map<String, ParamValue> = emptyMap(),
    val reasoningEffort: String? = null,
    val enableThinking: Boolean? = null,
    val maxOutputTokens: Int? = null,
    val sourcePresetKey: String? = null,
    val sourcePresetVersion: Int? = null,
    val createdAt: Long
) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun create(
            displayName: String,
            baseUrl: String,
            apiKey: String,
            modelName: String,
            templateType: ModelTemplate = ModelTemplate.OPENAI,
            isMultimodal: Boolean = false
        ): ModelConfig = ModelConfig(
            id = Uuid.random().toString(),
            displayName = displayName,
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelName = modelName,
            isMultimodal = isMultimodal,
            templateType = templateType,
            createdAt = System.currentTimeMillis()
        )
    }
}

/**
 * Embedding模型配置
 */
@Serializable
data class EmbeddingConfig(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val apiKey: String,
    val modelName: String,
    val dimensions: Int = 1536
) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun create(
            displayName: String,
            baseUrl: String,
            apiKey: String,
            modelName: String,
            dimensions: Int = 1536
        ): EmbeddingConfig = EmbeddingConfig(
            id = Uuid.random().toString(),
            displayName = displayName,
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelName = modelName,
            dimensions = dimensions
        )
    }
}
