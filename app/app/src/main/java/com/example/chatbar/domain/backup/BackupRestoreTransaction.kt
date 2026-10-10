package com.example.chatbar.domain.backup

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Bootstrap-only directory transaction. Intent is synced before each rename, and recovery
 * examines source/backup existence so a kill between a rename and its log write is safe.
 */
internal class BackupRestoreTransaction(
    private val filesRoot: File,
    private val preferenceRoot: File,
    private val workspace: File,
    private val syncDirectory: (File) -> Unit = {}
) {
    @Serializable
    private data class Journal(
        val state: String,
        val originalRoots: Set<String>,
        val originalPreferences: Set<String>,
        val replacementRoots: Set<String> = emptySet(),
        val movingRoot: String? = null,
        val installedRoots: Set<String> = emptySet(),
        val rollbackMovingRoot: String? = null,
        val restoredRoots: Set<String> = emptySet()
    )

    private val journal = File(workspace, "restore.json")
    private val staged = File(workspace, "prepared/files")
    private val old = File(workspace, "rollback/files")
    private val oldPreferences = File(workspace, "rollback/preferences")
    var recoveredRollback: Boolean = false
        private set

    fun schedule() {
        check(!journal.exists()) { "已有待恢复事务" }
        check(File(workspace, "prepared/manifest.json").isFile) { "恢复暂存数据不存在，请重新导入" }
        save(Journal("PREPARED", emptySet(), emptySet()))
    }

    /** Returns true only when new data is installed and awaits complete application initialization. */
    fun bootstrap(restorePreferences: (File) -> Unit, validatePrepared: (File) -> Unit = {}): Boolean {
        if (!journal.exists()) return false
        val current = read()
        if (current.state == "COMMITTED" || current.state == "ROLLED_BACK") {
            recoveredRollback = current.state == "ROLLED_BACK"
            cleanup()
            return false
        }
        if (current.state != "PREPARED") {
            rollback()
            recoveredRollback = true
            return false
        }
        require(current.movingRoot == null && current.installedRoots.isEmpty()) { "恢复事务无效" }
        try {
            check(staged.isDirectory) { "恢复暂存目录丢失" }
            validatePrepared(staged.parentFile!!)
        } catch (error: Throwable) {
            rollback()
            throw error
        }
        old.mkdirs()
        oldPreferences.mkdirs()
        syncDirectory(old)
        syncDirectory(oldPreferences)
        syncDirectory(old.parentFile!!)
        syncDirectory(workspace)
        val originalRoots = BackupDataRegistry.roots.filterTo(linkedSetOf()) { File(filesRoot, it).exists() }
        val originalPrefs = BackupDataRegistry.preferences.filterTo(linkedSetOf()) {
            File(preferenceRoot, it + ".xml").exists() || File(preferenceRoot, it + ".xml.bak").exists()
        }
        // Copy device-local ciphertext, never portable plaintext, for rollback.
        for (name in originalPrefs) {
            val original = File(preferenceRoot, name + ".xml")
            val backup = File(preferenceRoot, name + ".xml.bak")
            val source = if (backup.isFile) backup else original
            FileOutputStream(File(oldPreferences, original.name)).use { output ->
                source.inputStream().use { it.copyTo(output) }
                output.fd.sync()
            }
        }
        syncDirectory(oldPreferences)
        var state = Journal("APPLYING", originalRoots, originalPrefs,
            replacementRoots = BackupDataRegistry.roots.filterTo(linkedSetOf()) { File(staged, it).exists() })
        save(state)
        try {
            for (name in BackupDataRegistry.roots) {
                state = state.copy(movingRoot = name)
                save(state)
                val target = File(filesRoot, name)
                val previous = File(old, name)
                if (target.exists()) move(target, previous)
                val replacement = File(staged, name)
                if (replacement.exists()) move(replacement, target)
                state = state.copy(movingRoot = null, installedRoots = state.installedRoots + name)
                save(state)
            }
            restorePreferences(File(workspace, "prepared/preferences.json"))
            save(state.copy(state = "APPLIED"))
            return true
        } catch (error: Throwable) {
            try { rollback() } catch (failure: Throwable) { error.addSuppressed(failure) }
            throw error
        }
    }

    fun commit() {
        if (!journal.exists()) return
        val state = read()
        check(state.state == "APPLIED") { "恢复尚未完成" }
        save(state.copy(state = "COMMITTED"))
    }

    fun cleanupCommitted() = cleanup()

    fun rollback() {
        var state = read()
        check(state.state != "COMMITTED") { "已提交的恢复不能回滚" }
        if (state.state == "ROLLED_BACK") {
            cleanup()
            return
        }
        if (state.state != "PREPARED") {
            val touched = state.installedRoots + listOfNotNull(state.movingRoot)
            for (name in touched) {
                if (name in state.restoredRoots) continue
                require(name in BackupDataRegistry.roots) { "恢复日志目录无效" }
                val target = File(filesRoot, name)
                val previous = File(old, name)
                val renameAlreadyRecovered = state.state == "ROLLING_BACK" &&
                    state.rollbackMovingRoot == name && !previous.exists() && target.exists()
                val originalWasNeverMoved = name == state.movingRoot &&
                    name !in state.installedRoots && !previous.exists() && target.exists() &&
                    (File(staged, name).exists() || name !in state.replacementRoots)
                if (name in state.originalRoots) {
                    check(previous.exists() || renameAlreadyRecovered || originalWasNeverMoved) {
                        "原数据回滚目录丢失：" + name
                    }
                }
                state = state.copy(state = "ROLLING_BACK", rollbackMovingRoot = name)
                save(state)
                if (previous.exists()) {
                    check(!target.exists() || target.deleteRecursively()) { "无法移除未提交数据：" + name }
                    move(previous, target)
                } else if (name !in state.originalRoots) {
                    check(!target.exists() || target.deleteRecursively()) { "无法回滚新目录：" + name }
                }
                state = state.copy(rollbackMovingRoot = null, restoredRoots = state.restoredRoots + name)
                save(state)
            }
            preferenceRoot.mkdirs()
            for (name in BackupDataRegistry.preferences) {
                val target = File(preferenceRoot, name + ".xml")
                val previous = File(oldPreferences, target.name)
                if (name in state.originalPreferences) {
                    check(previous.isFile) { "原密钥回滚文件丢失" }
                    Files.copy(previous.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    java.io.RandomAccessFile(target, "rw").use { it.fd.sync() }
                } else check(!target.exists() || target.delete()) { "无法回滚本地偏好" }
                File(preferenceRoot, name + ".xml.bak").delete()
            }
            syncDirectory(preferenceRoot)
        }
        save(state.copy(state = "ROLLED_BACK", movingRoot = null, rollbackMovingRoot = null))
        cleanup()
    }

    fun pending(): Boolean = journal.exists()

    private fun read(): Journal {
        val value = backupJson.decodeFromString<Journal>(journal.readText())
        require(value.state in setOf("PREPARED", "APPLYING", "APPLIED", "COMMITTED", "ROLLING_BACK", "ROLLED_BACK") &&
            value.originalRoots.all { it in BackupDataRegistry.roots } &&
            value.replacementRoots.all { it in BackupDataRegistry.roots } &&
            value.installedRoots.all { it in BackupDataRegistry.roots } &&
            value.originalPreferences.all { it in BackupDataRegistry.preferences } &&
            value.restoredRoots.all { it in BackupDataRegistry.roots } &&
            (value.rollbackMovingRoot == null || value.rollbackMovingRoot in BackupDataRegistry.roots) &&
            (value.movingRoot == null || value.movingRoot in BackupDataRegistry.roots)) { "恢复事务日志无效" }
        return value
    }

    private fun save(value: Journal) {
        workspace.mkdirs()
        val temporary = File(workspace, "restore.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(backupJson.encodeToString(value).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        move(temporary, journal, replace = true)
    }

    private fun cleanup() {
        // Keep journal until cleanup succeeds, so failure can be retried on next startup.
        check(!File(workspace, "rollback").exists() || File(workspace, "rollback").deleteRecursively()) {
            "恢复数据已保留，旧数据清理需重试"
        }
        check(!File(workspace, "prepared").exists() || File(workspace, "prepared").deleteRecursively()) {
            "恢复暂存清理需重试"
        }
        check(!journal.exists() || journal.delete()) { "恢复日志清理失败" }
    }

    private fun move(source: File, target: File, replace: Boolean = false) {
        target.parentFile!!.mkdirs()
        if (replace) Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        else Files.move(source.toPath(), target.toPath())
        syncDirectory(source.parentFile!!)
        if (source.parentFile != target.parentFile) syncDirectory(target.parentFile!!)
    }
}
