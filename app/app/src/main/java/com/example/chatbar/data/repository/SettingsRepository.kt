package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.PlayerSetting
import com.example.chatbar.data.local.entity.withCurrentWebSearchDefaults
import com.example.chatbar.data.local.entity.withNormalizedAppearance
import com.example.chatbar.domain.backup.LocalDataMaintenance
import com.example.chatbar.domain.model.SILICON_FLOW_LLM_ENDPOINT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 设置仓库 - 管理应用全局设置和玩家角色设定（以单例形式存储）
 */
class SettingsRepository(
    private val storage: JsonFileStorage,
    private val llmAccounts: LlmApiAccountRepository? = null,
    private val canMigrateLlmKeys: () -> Boolean = { true }
) {
    private val settingsMutex = Mutex()

    companion object {
        private const val APP_SETTINGS_TYPE = "app_settings"
        private const val PLAYER_SETTING_TYPE = "player_setting"
    }

    private val _appSettings = MutableStateFlow(AppSettings())
    val appSettings: Flow<AppSettings> = _appSettings.asStateFlow()
    val currentAppSettings: AppSettings
        get() = _appSettings.value
    private val _siliconFlowMigrationPending = MutableStateFlow(false)
    val siliconFlowMigrationPending = _siliconFlowMigrationPending.asStateFlow()

    private val _playerSetting = MutableStateFlow(PlayerSetting())
    val playerSetting: Flow<PlayerSetting> = _playerSetting.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: Flow<Boolean> = _isInitialized.asStateFlow()

    private var initialized = false

    suspend fun initialize(forceReload: Boolean = false) = settingsMutex.withLock {
        if (initialized && !forceReload) return@withLock
        _appSettings.value = getAppSettingsLocked()
        _playerSetting.value = getPlayerSettingLocked()
        initialized = true
        _isInitialized.value = true
    }

    suspend fun getAppSettings(): AppSettings = settingsMutex.withLock { getAppSettingsLocked() }

    private suspend fun getAppSettingsLocked(): AppSettings {
        val loaded = storage.loadSingleton(APP_SETTINGS_TYPE, AppSettings.serializer())
            ?: AppSettings().also { saveAppSettingsLocked(it) }
        return migrateAppSettings(loaded).let { normalized ->
            if (llmAccounts == null || normalized.siliconFlowApiKey.isBlank() || !canMigrateLlmKeys()) {
                return@let normalized
            }
            try {
                saveAppSettingsLocked(normalized)
                _appSettings.value
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _siliconFlowMigrationPending.value = true
                normalized
            }
        }
    }

    suspend fun saveAppSettings(settings: AppSettings) {
        settingsMutex.withLock { saveAppSettingsLocked(settings) }
    }

    suspend fun updateAppSettings(transform: (AppSettings) -> AppSettings): AppSettings {
        initialize()
        return settingsMutex.withLock {
            saveAppSettingsLocked(transform(_appSettings.value))
            _appSettings.value
        }
    }

    suspend fun saveAppSettingsDraft(baseline: AppSettings, draft: AppSettings): AppSettings =
        updateAppSettings { latest ->
            val merged = mergeSettingsDraft(AppSettings.serializer(), baseline, draft, latest)
            if (baseline.siliconFlowApiKey != draft.siliconFlowApiKey) {
                merged.copy(siliconFlowApiKey = draft.siliconFlowApiKey, siliconFlowLlmAccountId = null)
            } else merged
        }

    suspend fun setSiliconFlowApiKey(apiKey: String) = updateAppSettings {
        it.copy(siliconFlowApiKey = apiKey, siliconFlowLlmAccountId = null)
    }

    suspend fun retrySiliconFlowKeyMigration() = settingsMutex.withLock {
        _appSettings.value = getAppSettingsLocked()
    }

    suspend fun removeLlmApiAccount(id: String, models: ModelRepository) = settingsMutex.withLock {
        val raw = storage.loadSingleton(APP_SETTINGS_TYPE, AppSettings.serializer())
        check(raw?.siliconFlowLlmAccountId != id) { "请先在模型与连接中更换或清除硅基流动默认密钥" }
        models.removeLlmApiAccount(id)
    }

    private suspend fun saveAppSettingsLocked(settings: AppSettings) = withContext(NonCancellable) {
        val normalized = settings.withNormalizedAppearance()
        val accounts = llmAccounts
        LocalDataMaintenance.enter().use {
            var migrationPending = false
            val persisted = if (accounts != null && normalized.siliconFlowApiKey.isNotBlank() && canMigrateLlmKeys()) {
                try {
                    val account = accounts.adoptLegacy(
                        "default:siliconflow", "硅基流动 · 旧默认账户", SILICON_FLOW_LLM_ENDPOINT, normalized.siliconFlowApiKey
                    )
                    normalized.copy(siliconFlowApiKey = "", siliconFlowLlmAccountId = account.id)
                } catch (error: Exception) {
                    // Unrelated settings can still be saved while a legacy key awaits migration.
                    // Never introduce a new plaintext key when the protected store is unavailable.
                    val old = storage.loadSingleton(APP_SETTINGS_TYPE, AppSettings.serializer())
                    if (old?.siliconFlowApiKey != normalized.siliconFlowApiKey) throw error
                    migrationPending = true
                    normalized
                }
            } else normalized
            storage.saveSingleton(APP_SETTINGS_TYPE, persisted, AppSettings.serializer())
            _appSettings.value = persisted
            _siliconFlowMigrationPending.value = migrationPending
        }
    }

    private suspend fun migrateAppSettings(settings: AppSettings): AppSettings {
        val migrated = settings
            .withCurrentWebSearchDefaults()
            .withNormalizedAppearance()
        if (migrated == settings) {
            return settings
        }
        // Apply ordinary compatibility defaults first. The protected-key migration owns its
        // separate write and can fail without losing this legacy input.
        storage.saveSingleton(APP_SETTINGS_TYPE, migrated, AppSettings.serializer())
        _appSettings.value = migrated
        return migrated
    }

    suspend fun completeTutorial(version: Int) {
        updateAppSettings { current ->
            if (current.tutorialVersion < version) current.copy(tutorialVersion = version) else current
        }
    }

    suspend fun getPlayerSetting(): PlayerSetting = settingsMutex.withLock { getPlayerSettingLocked() }

    private suspend fun getPlayerSettingLocked(): PlayerSetting {
        return storage.loadSingleton(PLAYER_SETTING_TYPE, PlayerSetting.serializer())
            ?: PlayerSetting().also { savePlayerSettingLocked(it) }
    }

    suspend fun savePlayerSetting(setting: PlayerSetting) = settingsMutex.withLock {
        savePlayerSettingLocked(setting)
    }

    private suspend fun savePlayerSettingLocked(setting: PlayerSetting) {
        val updated = setting.copy(updatedAt = System.currentTimeMillis())
        storage.saveSingleton(PLAYER_SETTING_TYPE, updated, PlayerSetting.serializer())
        _playerSetting.value = updated
    }
}
