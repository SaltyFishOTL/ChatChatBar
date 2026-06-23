package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.PlayerSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设置仓库 - 管理应用全局设置和玩家角色设定（以单例形式存储）
 */
class SettingsRepository(private val storage: JsonFileStorage) {

    companion object {
        private const val APP_SETTINGS_TYPE = "app_settings"
        private const val PLAYER_SETTING_TYPE = "player_setting"
    }

    private val _appSettings = MutableStateFlow(AppSettings())
    val appSettings: Flow<AppSettings> = _appSettings.asStateFlow()

    private val _playerSetting = MutableStateFlow(PlayerSetting())
    val playerSetting: Flow<PlayerSetting> = _playerSetting.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: Flow<Boolean> = _isInitialized.asStateFlow()

    private var initialized = false

    suspend fun initialize() {
        if (initialized) return
        _appSettings.value = getAppSettings()
        _playerSetting.value = getPlayerSetting()
        initialized = true
        _isInitialized.value = true
    }

    suspend fun getAppSettings(): AppSettings {
        return storage.loadSingleton(APP_SETTINGS_TYPE, AppSettings.serializer())
            ?: AppSettings().also { saveAppSettings(it) }
    }

    suspend fun saveAppSettings(settings: AppSettings) {
        storage.saveSingleton(APP_SETTINGS_TYPE, settings, AppSettings.serializer())
        _appSettings.value = settings
    }

    suspend fun completeTutorial(version: Int) {
        val current = getAppSettings()
        if (current.tutorialVersion < version) {
            saveAppSettings(current.copy(tutorialVersion = version))
        }
    }

    suspend fun getPlayerSetting(): PlayerSetting {
        return storage.loadSingleton(PLAYER_SETTING_TYPE, PlayerSetting.serializer())
            ?: PlayerSetting().also { savePlayerSetting(it) }
    }

    suspend fun savePlayerSetting(setting: PlayerSetting) {
        val updated = setting.copy(updatedAt = System.currentTimeMillis())
        storage.saveSingleton(PLAYER_SETTING_TYPE, updated, PlayerSetting.serializer())
        _playerSetting.value = updated
    }
}
