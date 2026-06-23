package com.example.chatbar.domain.model

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.ModelConfigurationMode
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.data.repository.SettingsRepository
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Serializable
private data class ModelConfigurationMigrationState(val version: Int = 0)

class ModelConfigurationMigration(
    private val storage: JsonFileStorage,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val presets: PresetModelCatalogService
) {
    companion object {
        private const val STATE_TYPE = "model_configuration_migration"
        private const val CURRENT_VERSION = 1
    }

    private val mutex = Mutex()

    suspend fun run() = mutex.withLock {
        val state = storage.loadSingleton(STATE_TYPE, ModelConfigurationMigrationState.serializer())
            ?: ModelConfigurationMigrationState()
        if (state.version >= CURRENT_VERSION) return@withLock

        val current = settings.getAppSettings()
        models.migrateEmbeddingsToSingleton(current.defaultEmbeddingId)
        val presetDefault = current.presetDefaultModelKey
            ?.takeIf { key -> presets.catalog.chatModels.any { it.modelKey == key } }
            ?: presets.catalog.chatModels.firstOrNull()?.modelKey
        settings.saveAppSettings(
            current.copy(
                modelConfigurationMode = ModelConfigurationMode.DEFAULT,
                presetDefaultModelKey = presetDefault,
                defaultEmbeddingId = null
            )
        )
        storage.saveSingleton(
            STATE_TYPE,
            ModelConfigurationMigrationState(CURRENT_VERSION),
            ModelConfigurationMigrationState.serializer()
        )
    }
}
