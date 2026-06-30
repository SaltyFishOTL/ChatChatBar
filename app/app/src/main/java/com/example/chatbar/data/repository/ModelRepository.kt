package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.PRESET_MODEL_ID_PREFIX
import com.example.chatbar.data.local.entity.PresetChatModel
import com.example.chatbar.data.local.entity.PresetModelCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 模型配置仓库 - 管理LLM和Embedding模型配置
 */
class ModelRepository(private val storage: JsonFileStorage) {

    companion object {
        private const val MODEL_TYPE = "model_configs"
        private const val EMBEDDING_TYPE = "embedding_configs"
        private const val EMBEDDING_SINGLETON_TYPE = "embedding_model_config"
        private const val EMBEDDING_SINGLETON_ID = "default"
        private const val RETRIEVAL_MODEL_TYPE = "retrieval_model_config"
        private const val RETRIEVAL_MODEL_ID = "default"
    }

    private val _models = MutableStateFlow<List<ModelConfig>>(emptyList())
    val models: Flow<List<ModelConfig>> = _models.asStateFlow()

    private val _embeddings = MutableStateFlow<List<EmbeddingConfig>>(emptyList())
    val embeddings: Flow<List<EmbeddingConfig>> = _embeddings.asStateFlow()
    private val _embeddingModel = MutableStateFlow<EmbeddingConfig?>(null)
    val embeddingModel: Flow<EmbeddingConfig?> = _embeddingModel.asStateFlow()

    private val _retrievalModel = MutableStateFlow<ModelConfig?>(null)
    val retrievalModel: Flow<ModelConfig?> = _retrievalModel.asStateFlow()

    private var initialized = false

    suspend fun initialize() {
        if (initialized) return
        refreshModelCache()
        refreshEmbeddingCache()
        refreshEmbeddingModelCache()
        refreshRetrievalModelCache()
        initialized = true
    }

    private suspend fun refreshModelCache() {
        _models.value = storage.loadAll(MODEL_TYPE, ModelConfig.serializer())
            .sortedBy { it.displayName }
    }

    private suspend fun refreshEmbeddingCache() {
        _embeddings.value = storage.loadAll(EMBEDDING_TYPE, EmbeddingConfig.serializer())
            .sortedBy { it.displayName }
    }

    private suspend fun refreshEmbeddingModelCache() {
        _embeddingModel.value = storage.loadEntity(
            EMBEDDING_SINGLETON_TYPE,
            EMBEDDING_SINGLETON_ID,
            EmbeddingConfig.serializer()
        )
    }

    private suspend fun refreshRetrievalModelCache() {
        _retrievalModel.value = storage.loadEntity(
            RETRIEVAL_MODEL_TYPE,
            RETRIEVAL_MODEL_ID,
            ModelConfig.serializer()
        )
    }

    // ===== LLM模型 =====

    suspend fun getAllModels(): List<ModelConfig> {
        initialize()
        return _models.value
    }

    suspend fun getModel(id: String): ModelConfig? {
        return storage.loadEntity(MODEL_TYPE, id, ModelConfig.serializer())
    }

    suspend fun saveModel(model: ModelConfig) {
        storage.saveEntity(MODEL_TYPE, model.id, model, ModelConfig.serializer())
        refreshModelCache()
    }

    suspend fun ensurePresetChatModels(catalog: PresetModelCatalog, catalogVersion: Int): List<ModelConfig> =
        upsertPresetChatModels(catalog, catalogVersion, overwriteExisting = false)

    suspend fun restorePresetChatModels(catalog: PresetModelCatalog, catalogVersion: Int): List<ModelConfig> =
        upsertPresetChatModels(catalog, catalogVersion, overwriteExisting = true)

    private suspend fun upsertPresetChatModels(
        catalog: PresetModelCatalog,
        catalogVersion: Int,
        overwriteExisting: Boolean
    ): List<ModelConfig> {
        initialize()
        val current = _models.value
        val saved = mutableListOf<ModelConfig>()
        catalog.chatModels.forEach { preset ->
            val stableId = PRESET_MODEL_ID_PREFIX + preset.modelKey
            val existing = current.firstOrNull { it.sourcePresetKey == preset.modelKey || it.id == stableId }
            val next = if (existing != null && !overwriteExisting) {
                existing
            } else {
                preset.toModelConfig(
                    catalog = catalog,
                    catalogVersion = catalogVersion,
                    existing = existing
                )
            }
            if (existing == null || next != existing) {
                storage.saveEntity(MODEL_TYPE, next.id, next, ModelConfig.serializer())
            }
            saved.add(next)
        }
        refreshModelCache()
        return saved
    }

    private fun PresetChatModel.toModelConfig(
        catalog: PresetModelCatalog,
        catalogVersion: Int,
        existing: ModelConfig?
    ): ModelConfig = ModelConfig(
        id = existing?.id ?: PRESET_MODEL_ID_PREFIX + modelKey,
        displayName = displayName,
        baseUrl = catalog.baseUrl,
        apiKey = existing?.apiKey.orEmpty(),
        modelName = modelName,
        selectableForChat = selectableForChat,
        isMultimodal = isMultimodal,
        visionModelId = visionModelKey?.let { PRESET_MODEL_ID_PREFIX + it },
        templateType = templateType,
        customParams = customParams,
        reasoningEffort = reasoningEffort,
        enableThinking = enableThinking,
        maxOutputTokens = maxOutputTokens,
        sourcePresetKey = modelKey,
        sourcePresetVersion = catalogVersion,
        createdAt = existing?.createdAt ?: 0L
    )

    suspend fun deleteModel(id: String) {
        storage.deleteEntity<ModelConfig>(MODEL_TYPE, id)
        refreshModelCache()
    }

    // ===== Embedding模型 =====

    suspend fun getAllEmbeddings(): List<EmbeddingConfig> {
        initialize()
        return _embeddings.value
    }

    suspend fun getEmbedding(id: String): EmbeddingConfig? {
        return storage.loadEntity(EMBEDDING_TYPE, id, EmbeddingConfig.serializer())
    }

    suspend fun saveEmbedding(config: EmbeddingConfig) {
        storage.saveEntity(EMBEDDING_TYPE, config.id, config, EmbeddingConfig.serializer())
        refreshEmbeddingCache()
    }

    suspend fun deleteEmbedding(id: String) {
        storage.deleteEntity<EmbeddingConfig>(EMBEDDING_TYPE, id)
        refreshEmbeddingCache()
    }

    suspend fun getEmbeddingModel(): EmbeddingConfig? {
        initialize()
        return _embeddingModel.value
    }

    suspend fun saveEmbeddingModel(config: EmbeddingConfig) {
        storage.saveEntity(
            EMBEDDING_SINGLETON_TYPE,
            EMBEDDING_SINGLETON_ID,
            config.copy(id = EMBEDDING_SINGLETON_ID),
            EmbeddingConfig.serializer()
        )
        refreshEmbeddingModelCache()
    }

    suspend fun deleteEmbeddingModel() {
        storage.deleteEntity<EmbeddingConfig>(EMBEDDING_SINGLETON_TYPE, EMBEDDING_SINGLETON_ID)
        refreshEmbeddingModelCache()
    }

    suspend fun migrateEmbeddingsToSingleton(preferredId: String?) {
        val legacy = getAllEmbeddings()
        if (getEmbeddingModel() == null) {
            val selected = legacy.firstOrNull { it.id == preferredId } ?: legacy.firstOrNull()
            if (selected != null) saveEmbeddingModel(selected)
        }
        legacy.forEach { deleteEmbedding(it.id) }
    }

    // ===== Retrieval LLM 单例 =====

    suspend fun getRetrievalModel(): ModelConfig? {
        initialize()
        return _retrievalModel.value
    }

    suspend fun saveRetrievalModel(model: ModelConfig) {
        storage.saveEntity(
            RETRIEVAL_MODEL_TYPE,
            RETRIEVAL_MODEL_ID,
            model.copy(id = RETRIEVAL_MODEL_ID),
            ModelConfig.serializer()
        )
        refreshRetrievalModelCache()
    }

    suspend fun deleteRetrievalModel() {
        storage.deleteEntity<ModelConfig>(RETRIEVAL_MODEL_TYPE, RETRIEVAL_MODEL_ID)
        refreshRetrievalModelCache()
    }
}
