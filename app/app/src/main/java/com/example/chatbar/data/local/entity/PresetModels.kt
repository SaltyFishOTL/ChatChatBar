package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class PresetManifest(
    val entries: List<PresetEntry> = emptyList()
)

@Serializable
data class PresetEntry(
    val presetKey: String,
    val type: PresetType,
    val version: Int,
    val file: String,
    val displayName: String
)

@Serializable
enum class PresetType { CHARACTER, FORMAT, MODEL_CATALOG }

@Serializable
data class PresetImportState(
    val seenVersions: Map<String, Int> = emptyMap()
)

@Serializable
data class PresetModelCatalog(
    val schemaVersion: Int = 1,
    val provider: String = "SILICONFLOW",
    val baseUrl: String = "https://api.siliconflow.cn/v1",
    val chatModels: List<PresetChatModel> = emptyList(),
    val retrievalModel: PresetChatModel? = null,
    val embeddingModel: PresetEmbeddingModel? = null
)

@Serializable
data class PresetChatModel(
    val modelKey: String,
    val displayName: String,
    val modelName: String,
    val selectableForChat: Boolean = true,
    val isMultimodal: Boolean = false,
    val visionModelKey: String? = null,
    val templateType: ModelTemplate = ModelTemplate.OPENAI,
    val customParams: Map<String, ParamValue> = emptyMap(),
    val reasoningEffort: String? = null,
    val enableThinking: Boolean? = null,
    val maxOutputTokens: Int? = null
)

@Serializable
data class PresetEmbeddingModel(
    val modelKey: String,
    val displayName: String,
    val modelName: String,
    val dimensions: Int = 1536
)
