package com.example.chatbar.domain.backup

import android.content.Context
import java.io.File

object AppBackupBootstrap {
    var recoveryNotice: String? = null
        private set
    internal fun workspace(context: Context): File =
        File(context.noBackupFilesDir, "app-backup").also(File::mkdirs)

    internal fun syncDirectory(directory: File) {
        val fd = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }

    internal fun transaction(context: Context): BackupRestoreTransaction =
        BackupRestoreTransaction(
            context.filesDir, File(context.applicationInfo.dataDir, "shared_prefs"), workspace(context),
            syncDirectory = ::syncDirectory
        )

    /** Call before constructing any repository or reading any SharedPreferences. */
    fun applyPending(context: Context): Boolean {
        val directory = workspace(context)
        val transaction = transaction(context)
        val restored = transaction.bootstrap(
            restorePreferences = { preferenceFile ->
                val snapshot = backupJson.decodeFromString<BackupPreferences>(preferenceFile.readText())
                BackupPreferenceTransfer.restore(context, snapshot)
            },
            validatePrepared = { prepared ->
                BackupPreparedSeal.validate(prepared)
                val summary = backupJson.decodeFromString<AppBackupSummary>(File(prepared, "manifest.json").readText())
                require(summary.formatVersion == 1 && summary.appVersionCode <= com.example.chatbar.BuildConfig.VERSION_CODE) {
                    "该存档来自更新版本，请先升级 APP"
                }
            }
        )
        recoveryNotice = if (transaction.recoveredRollback) "上次恢复已中断，已回滚原数据。请重新导入存档。" else null
        if (restored || transaction.recoveredRollback) File(directory, "last-operation.json").delete()
        if (!transaction.pending()) File(directory, "prepared").deleteRecursively()
        File(directory, "export").deleteRecursively()
        return restored
    }

    fun initializationSucceeded(context: Context) {
        transaction(context).commit()
        runCatching { transaction(context).cleanupCommitted() }.onFailure {
            android.util.Log.w("AppBackup", "恢复已完成，旧备份清理失败，将在下次启动重试")
        }
    }

    fun initializationFailed(context: Context) {
        transaction(context).rollback()
    }
}
