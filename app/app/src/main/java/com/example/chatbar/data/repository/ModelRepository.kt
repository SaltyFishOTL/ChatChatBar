package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.PendingVisionModelLink
import com.example.chatbar.data.local.entity.PRESET_MODEL_ID_PREFIX
import com.example.chatbar.data.local.entity.PresetChatModel
import com.example.chatbar.data.local.entity.PresetEmbeddingModel
import com.example.chatbar.data.local.entity.PresetModelCatalog
import com.example.chatbar.data.local.entity.VISION_LINK_TRANSACTION_TYPE
import com.example.chatbar.data.local.entity.uncommittedVisionModels
import com.example.chatbar.domain.backup.LocalDataMaintenance
import com.example.chatbar.domain.card.NamePolicy
import com.example.chatbar.domain.model.matchesVisionConnection
import com.example.chatbar.domain.model.sameVisionApiBase
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 模型配置仓库 - 管理LLM和Embedding模型配置
 */
class ModelRepository(private val storage: JsonFileStorage) {

    companion object {
        private const val MODEL_TYPE = "model_configs"
        private const val EMBEDDING_TYPE = "embedding_configs"
        private const val EMBEDDING_SINGLETON_TYPE = "embedding_model_config"
        private const val EMBEDDING_SINGLETON_ID = "default"
    }

    private val _models = MutableStateFlow<List<ModelConfig>>(emptyList())
    val models: Flow<List<ModelConfig>> = _models.asStateFlow()

    private val _embeddings = MutableStateFlow<List<EmbeddingConfig>>(emptyList())
    val embeddings: Flow<List<EmbeddingConfig>> = _embeddings.asStateFlow()
    private val _embeddingModel = MutableStateFlow<EmbeddingConfig?>(null)
    val embeddingModel: Flow<EmbeddingConfig?> = _embeddingModel.asStateFlow()

    private val stateMutex = Mutex()
    private var initialized = false

    suspend fun initialize() = stateMutex.withLock {
        if (initialized) return@withLock
        migrateLegacyPlanningModel()
        refreshModelCache()
        refreshEmbeddingCache()
        refreshEmbeddingModelCache()
        initialized = true
    }

    // Serialize persistence and publication. Cancellation after a successful disk write
    // must not leave selectors and request resolution on an older in-memory version.
    private suspend fun <T> mutate(block: suspend () -> T): T {
        initialize()
        return stateMutex.withLock { withContext(NonCancellable) { block() } }
    }

    private suspend fun refreshModelCache() {
        val durable = storage.loadAll(MODEL_TYPE, ModelConfig.serializer())
        val transactions = storage.loadAll(VISION_LINK_TRANSACTION_TYPE, PendingVisionModelLink.serializer())
        val uncommitted = uncommittedVisionModels(durable, transactions)
        for (transaction in transactions) {
            try {
                if (transaction.visionId in uncommitted) {
                    storage.deleteEntity<ModelConfig>(MODEL_TYPE, transaction.visionId, requireSuccess = true)
                }
                storage.deleteEntity<PendingVisionModelLink>(
                    VISION_LINK_TRANSACTION_TYPE, transaction.visionId, requireSuccess = true
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Retain the marker for later recovery and keep an uncommitted child out of selectors.
            }
        }
        _models.value = durable.filterNot { it.id in uncommitted }.sortedBy { it.displayName }
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

    /** Preserve old explicit auxiliary bindings while retiring the dedicated model slot. */
    private suspend fun migrateLegacyPlanningModel() {
        val legacyType = "retrieval_model_config"
        val legacy = storage.loadEntity(legacyType, "default", ModelConfig.serializer()) ?: return
        val migrated = legacy.copy(
            displayName = if (legacy.displayName == "检索规划模型") legacy.modelName else legacy.displayName,
            selectableForChat = true,
            sourcePresetKey = null,
            sourcePresetVersion = null
        )
        val existing = storage.loadEntity(MODEL_TYPE, legacy.id, ModelConfig.serializer())
        if (existing == null) {
            storage.saveEntity(MODEL_TYPE, migrated.id, migrated, ModelConfig.serializer())
        } else if (existing != migrated) {
            // Ordinary models already took precedence for colliding IDs. Keep that binding,
            // and preserve the retired configuration under a stable separate ID.
            val preserved = migrated.copy(id = "legacy-planning-${legacy.id}")
            if (storage.loadEntity(MODEL_TYPE, preserved.id, ModelConfig.serializer()) == null) {
                storage.saveEntity(MODEL_TYPE, preserved.id, preserved, ModelConfig.serializer())
            }
        }
        // A restart after saving can safely repeat this without overwriting an edited model.
        storage.deleteEntity<ModelConfig>(legacyType, "default")
    }

    // ===== LLM模型 =====

    suspend fun getAllModels(): List<ModelConfig> {
        initialize()
        return _models.value
    }

    suspend fun getModel(id: String): ModelConfig? {
        initialize()
        return _models.value.firstOrNull { it.id == id }
    }

    suspend fun saveModel(model: ModelConfig, expectedVision: ModelConfig? = null) = mutate {
        if (expectedVision != null) {
            require(expectedVision.id == model.visionModelId && expectedVision.isMultimodal &&
                _models.value.any { it == expectedVision }
            ) { "所选视觉模型已变化，请重新选择" }
        }
        storage.saveEntity(MODEL_TYPE, model.id, model, ModelConfig.serializer())
        refreshModelCache()
    }

    /** Commit a staged visual connection only when its owning text model is saved. */
    suspend fun saveModelWithVision(model: ModelConfig, vision: ModelConfig) = mutate {
        require(model.id != vision.id && model.visionModelId == vision.id && vision.isMultimodal)
        val oldWithId = _models.value.firstOrNull { it.id == vision.id }
        check(oldWithId == null || oldWithId == vision) { "视觉模型记录已变化，请重新选择" }
        val existing = oldWithId ?: _models.value.firstOrNull {
            it.id != model.id && it.matchesVisionConnection(vision)
        }
        val linked = model.copy(visionModelId = existing?.id ?: vision.id)
        LocalDataMaintenance.access {
            try {
                if (existing == null) {
                    // Keep the old owner intact until the new vision record is durable.
                    storage.saveEntity(
                        VISION_LINK_TRANSACTION_TYPE, vision.id, PendingVisionModelLink(vision.id, vision.createdAt),
                        PendingVisionModelLink.serializer()
                    )
                    storage.saveEntity(MODEL_TYPE, vision.id, vision, ModelConfig.serializer())
                }
                storage.saveEntity(MODEL_TYPE, linked.id, linked, ModelConfig.serializer())
            } finally {
                refreshModelCache()
            }
        }
    }

    suspend fun duplicateModel(id: String): ModelConfig = mutate {
        val source = _models.value.firstOrNull { it.id == id } ?: error("Model not found")
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            displayName = NamePolicy.nextCopyName(source.displayName, _models.value.map { it.displayName }),
            sourcePresetKey = null,
            sourcePresetVersion = null,
            createdAt = System.currentTimeMillis()
        )
        storage.saveEntity(MODEL_TYPE, copy.id, copy, ModelConfig.serializer())
        refreshModelCache()
        copy
    }

    suspend fun ensurePresetChatModels(catalog: PresetModelCatalog, catalogVersion: Int): List<ModelConfig> =
        upsertPresetChatModels(catalog, catalogVersion, overwriteExisting = false)

    suspend fun restorePresetChatModels(catalog: PresetModelCatalog, catalogVersion: Int): List<ModelConfig> =
        upsertPresetChatModels(catalog, catalogVersion, overwriteExisting = true)

    suspend fun ensurePresetEmbeddingModel(catalog: PresetModelCatalog) {
        upsertPresetEmbeddingModel(catalog, overwriteExisting = false)
    }

    suspend fun restorePresetEmbeddingModel(catalog: PresetModelCatalog) {
        upsertPresetEmbeddingModel(catalog, overwriteExisting = true)
    }

    private suspend fun upsertPresetChatModels(
        catalog: PresetModelCatalog,
        catalogVersion: Int,
        overwriteExisting: Boolean
    ): List<ModelConfig> = mutate {
        val current = _models.value
        val saved = mutableListOf<ModelConfig>()
        try {
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
        } finally {
            // A partially written preset batch still has to publish its durable state.
            refreshModelCache()
        }
        saved
    }

    private fun PresetChatModel.toModelConfig(
        catalog: PresetModelCatalog,
        catalogVersion: Int,
        existing: ModelConfig?
    ): ModelConfig = ModelConfig(
        id = existing?.id ?: PRESET_MODEL_ID_PREFIX + modelKey,
        displayName = displayName,
        baseUrl = catalog.baseUrl,
        apiKey = existing?.takeIf { sameVisionApiBase(it.baseUrl, catalog.baseUrl) }?.apiKey.orEmpty(),
        modelName = modelName,
        selectableForChat = selectableForChat,
        isMultimodal = isMultimodal,
        visionModelId = visionModelKey?.let { PRESET_MODEL_ID_PREFIX + it },
        templateType = templateType,
        customParams = customParams,
        reasoningEffort = reasoningEffort,
        enableThinking = enableThinking,
        maxOutputTokens = maxOutputTokens,
        formatPromptPosition = existing?.formatPromptPosition ?: FormatPromptPosition.BOTH,
        sourcePresetKey = modelKey,
        sourcePresetVersion = catalogVersion,
        createdAt = existing?.createdAt ?: 0L
    )

    private suspend fun upsertPresetEmbeddingModel(
        catalog: PresetModelCatalog,
        overwriteExisting: Boolean
    ) = mutate {
        catalog.embeddingModel?.let { preset ->
            val existing = _embeddingModel.value
            if (existing == null || overwriteExisting) {
                val next = preset.toEmbeddingConfig(catalog, existing)
                if (next != existing) {
                    storage.saveEntity(
                        EMBEDDING_SINGLETON_TYPE, EMBEDDING_SINGLETON_ID,
                        next.copy(id = EMBEDDING_SINGLETON_ID), EmbeddingConfig.serializer()
                    )
                    refreshEmbeddingModelCache()
                }
            }
        }
    }

    private fun PresetEmbeddingModel.toEmbeddingConfig(
        catalog: PresetModelCatalog,
        existing: EmbeddingConfig?
    ): EmbeddingConfig = EmbeddingConfig(
        id = existing?.id ?: EMBEDDING_SINGLETON_ID,
        displayName = displayName,
        baseUrl = catalog.baseUrl,
        apiKey = existing?.apiKey.orEmpty(),
        modelName = modelName,
        dimensions = dimensions
    )

    suspend fun deleteModel(id: String) = mutate {
        storage.deleteEntity<ModelConfig>(MODEL_TYPE, id)
        refreshModelCache()
    }

    // ===== Embedding模型 =====

    suspend fun getAllEmbeddings(): List<EmbeddingConfig> {
        initialize()
        return _embeddings.value
    }

    suspend fun getEmbedding(id: String): EmbeddingConfig? {
        initialize()
        return _embeddings.value.firstOrNull { it.id == id }
    }

    suspend fun saveEmbedding(config: EmbeddingConfig) = mutate {
        storage.saveEntity(EMBEDDING_TYPE, config.id, config, EmbeddingConfig.serializer())
        refreshEmbeddingCache()
    }

    suspend fun deleteEmbedding(id: String) = mutate {
        storage.deleteEntity<EmbeddingConfig>(EMBEDDING_TYPE, id)
        refreshEmbeddingCache()
    }

    suspend fun getEmbeddingModel(): EmbeddingConfig? {
        initialize()
        return _embeddingModel.value
    }

    suspend fun saveEmbeddingModel(config: EmbeddingConfig) = mutate {
        storage.saveEntity(
            EMBEDDING_SINGLETON_TYPE,
            EMBEDDING_SINGLETON_ID,
            config.copy(id = EMBEDDING_SINGLETON_ID),
            EmbeddingConfig.serializer()
        )
        refreshEmbeddingModelCache()
    }

    suspend fun deleteEmbeddingModel() = mutate {
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

}
