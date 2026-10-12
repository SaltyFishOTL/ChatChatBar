package com.example.chatbar.domain.backup

import kotlinx.serialization.Serializable

internal val backupJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
}

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class AppBackupSummary(
    val formatVersion: Int = 1,
    val appVersionCode: Int,
    val appVersionName: String,
    val createdAt: Long,
    val sourceFilesRoot: String,
    val counts: Map<String, Int>,
    val fileCount: Int,
    val totalBytes: Long,
    val preferencesSha256: String,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val scope: AppBackupExportScope? = null
) {
    fun requiresRestorePreview(mode: AppBackupRestoreMode): Boolean =
        mode != AppBackupRestoreMode.REPLACE_ALL || scope != null

    internal fun requireCompatibleWith(versionCode: Int) {
        require(formatVersion in 1..2) { "存档格式不支持，请升级 APP" }
        require(appVersionCode <= versionCode) { "该存档来自更新版本，请先升级 APP" }
    }
}

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class AppBackupExportScope(
    val globalOptions: Set<String>, val characters: Int, val sessions: Int,
    val sessionsWithImages: Int, val sessionsWithVoice: Int, val sessionsWithArchives: Int,
    val studioSettings: Boolean, val studioHistory: Boolean, val studioHistoryImages: Boolean,
    val moments: Boolean,
    val momentsImages: Boolean = true,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val sessionChoices: Map<String, BackupSessionChoice> = emptyMap(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val sessionDefaultChoice: BackupSessionChoice? = null
) {
    companion object {
        fun from(selection: AppBackupSelection): AppBackupExportScope {
            val default = selection.sessions.values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            return AppBackupExportScope(
                selection.globalOptions, selection.characterIds.size, selection.sessions.size,
                selection.sessions.values.count { it.images }, selection.sessions.values.count { it.voice },
                selection.sessions.values.count { it.archives }, selection.studioSettings, selection.studioHistory,
                selection.studioHistoryImages, selection.moments, selection.momentsImages,
                selection.sessions.filterValues { it != default }, default
            )
        }
    }
}

@Serializable
internal data class AppBackupFileRecord(val path: String, val size: Long, val sha256: String)

@Serializable
internal data class AppBackupEnvelope(
    val version: Int = 1,
    val encrypted: Boolean = false,
    val salt: String? = null,
    val wrappedKey: String? = null
)

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal data class BackupPreferences(
    val novelAiToken: String? = null,
    val fishAudioKey: String? = null,
    val communitySession: String? = null,
    val brushSize: Float = 36f,
    val brushType: String = "Mosaic",
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val retainedPreferences: Set<String> = emptySet()
)

enum class AppBackupPhase {
    IDLE, PREPARING, EXPORTING, VALIDATING, AWAITING_CONFIRMATION, READY_TO_RESTART, COMPLETE, FAILED
}

@Serializable
enum class AppBackupRestoreMode { REPLACE_ALL, UPDATE_EXISTING, ADD_COPIES }

@Serializable
data class BackupMergeCounts(val added: Int = 0, val updated: Int = 0, val copies: Int = 0, val removed: Int = 0)

@Serializable
data class BackupRestorePreview(
    val mode: AppBackupRestoreMode,
    val counts: Map<String, BackupMergeCounts>,
    val warnings: List<String> = emptyList()
)

@Serializable
data class AppBackupState(
    val phase: AppBackupPhase = AppBackupPhase.IDLE,
    val label: String = "",
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val doneFiles: Int = 0,
    val totalFiles: Int = 0,
    val summary: AppBackupSummary? = null,
    val error: String? = null,
    val warnings: List<String> = emptyList(),
    val restorePreview: BackupRestorePreview? = null
) {
    val busy: Boolean get() = phase == AppBackupPhase.PREPARING ||
        phase == AppBackupPhase.EXPORTING || phase == AppBackupPhase.VALIDATING
}
