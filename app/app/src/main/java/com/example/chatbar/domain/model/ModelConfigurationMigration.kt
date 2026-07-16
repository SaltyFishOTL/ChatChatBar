package com.example.chatbar.domain.model

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.PRESET_MODEL_ID_PREFIX
import com.example.chatbar.data.local.entity.normalized
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.data.repository.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

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
        private const val CURRENT_VERSION = 5
    }

    private val mutex = Mutex()

    suspend fun run() = mutex.withLock {
        val state = storage.loadSingleton(STATE_TYPE, ModelConfigurationMigrationState.serializer())
            ?: ModelConfigurationMigrationState()
        if (state.version >= CURRENT_VERSION) return@withLock

        if (state.version < 1) {
            migrateLegacySettings()
        }
        if (state.version < 2) {
            importVisiblePresetModels()
        }
        if (state.version < 3) {
            normalizeOnlyApiMode()
        }
        if (state.version < 4) {
            importPresetSupportModels()
        }
        storage.saveSingleton(
            STATE_TYPE,
            ModelConfigurationMigrationState(CURRENT_VERSION),
            ModelConfigurationMigrationState.serializer()
        )
    }

    private suspend fun migrateLegacySettings() {
        val current = settings.getAppSettings()
        models.migrateEmbeddingsToSingleton(current.defaultEmbeddingId)
        val presetDefault = current.presetDefaultModelKey
            ?.takeIf { key -> presets.catalog.chatModels.any { it.modelKey == key } }
            ?: presets.catalog.chatModels.firstOrNull { it.selectableForChat }?.modelKey
        settings.saveAppSettings(
            current.copy(
                modelConfigurationMode = current.modelConfigurationMode.normalized(),
                presetDefaultModelKey = presetDefault,
                defaultEmbeddingId = null
            )
        )
    }

    private suspend fun importVisiblePresetModels() {
        val version = presets.entries().firstOrNull()?.version ?: presets.catalog.schemaVersion
        val imported = models.ensurePresetChatModels(presets.catalog, version)
        models.ensurePresetSupportModels(presets.catalog, version)
        val current = settings.getAppSettings()
        val defaultFromPreset = current.presetDefaultModelKey?.let { PRESET_MODEL_ID_PREFIX + it }
        val defaultModelId = current.defaultModelId
            ?: defaultFromPreset?.takeIf { id -> imported.any { it.id == id } }
            ?: imported.firstOrNull { it.selectableForChat }?.id
        val next = current.copy(
            modelConfigurationMode = current.modelConfigurationMode.normalized(),
            defaultModelId = defaultModelId,
            presetDefaultModelKey = null,
            defaultEmbeddingId = null
        )
        if (next != current) settings.saveAppSettings(next)
    }

    private suspend fun normalizeOnlyApiMode() {
        val current = settings.getAppSettings()
        val next = current.copy(modelConfigurationMode = current.modelConfigurationMode.normalized())
        if (next != current) settings.saveAppSettings(next)
    }

    private suspend fun importPresetSupportModels() {
        val version = presets.entries().firstOrNull()?.version ?: presets.catalog.schemaVersion
        models.ensurePresetSupportModels(presets.catalog, version)
    }
}
