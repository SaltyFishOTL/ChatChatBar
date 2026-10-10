package com.example.chatbar.domain.backup

import kotlinx.serialization.Serializable

internal val backupJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
}

@Serializable
data class AppBackupSummary(
    val formatVersion: Int = 1,
    val appVersionCode: Int,
    val appVersionName: String,
    val createdAt: Long,
    val sourceFilesRoot: String,
    val counts: Map<String, Int>,
    val fileCount: Int,
    val totalBytes: Long,
    val preferencesSha256: String
)

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
internal data class BackupPreferences(
    val novelAiToken: String? = null,
    val fishAudioKey: String? = null,
    val communitySession: String? = null,
    val brushSize: Float = 36f,
    val brushType: String = "Mosaic"
)

enum class AppBackupPhase {
    IDLE, PREPARING, EXPORTING, VALIDATING, AWAITING_CONFIRMATION, READY_TO_RESTART, COMPLETE, FAILED
}

@Serializable
data class AppBackupState(
    val phase: AppBackupPhase = AppBackupPhase.IDLE,
    val label: String = "",
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val doneFiles: Int = 0,
    val totalFiles: Int = 0,
    val summary: AppBackupSummary? = null,
    val error: String? = null
) {
    val busy: Boolean get() = phase == AppBackupPhase.PREPARING ||
        phase == AppBackupPhase.EXPORTING || phase == AppBackupPhase.VALIDATING
}
