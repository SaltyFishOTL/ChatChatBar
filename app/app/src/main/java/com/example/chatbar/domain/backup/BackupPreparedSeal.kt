package com.example.chatbar.domain.backup

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.serialization.encodeToString

/** Checks the mapped, staged bytes again on cold start, before any current data is moved. */
internal object BackupPreparedSeal {
    private const val INVENTORY = "ready-inventory.jsonl"
    private const val DIGEST = "ready-inventory.sha256"

    fun write(workspace: File, checkActive: () -> Unit, syncDirectory: (File) -> Unit = {}) {
        val inventory = File(workspace, INVENTORY)
        FileOutputStream(inventory).use { stream ->
            val writer = stream.bufferedWriter()
            val files = sequence {
                yield(File(workspace, "preferences.json"))
                yield(File(workspace, "manifest.json"))
                yieldAll(File(workspace, "files").walkTopDown().filter(File::isFile))
            }
            files.forEach { file ->
                checkActive()
                RandomAccessFile(file, "rw").use { it.fd.sync() }
                writer.write(backupJson.encodeToString(AppBackupFileRecord(
                    file.relativeTo(workspace).invariantSeparatorsPath, file.length(), hash(file, checkActive)
                )))
                writer.newLine()
            }
            writer.flush()
            stream.fd.sync()
        }
        FileOutputStream(File(workspace, DIGEST)).use {
            it.write(hash(inventory, checkActive).toByteArray(Charsets.US_ASCII))
            it.fd.sync()
        }
        File(workspace, "files").walkBottomUp().filter(File::isDirectory).forEach(syncDirectory)
        syncDirectory(workspace)
        workspace.parentFile?.let(syncDirectory)
    }

    fun validate(workspace: File) {
        val inventory = File(workspace, INVENTORY)
        require(inventory.isFile && File(workspace, DIGEST).isFile && File(workspace, DIGEST).length() == 64L &&
            hash(inventory) == File(workspace, DIGEST).readText()) { "恢复暂存校验清单损坏，请重新导入" }
        val seen = hashSetOf<String>()
        inventory.strictUtf8Reader().use { reader ->
            while (true) {
                val line = boundedLine(reader) ?: break
                val record = backupJson.decodeFromString<AppBackupFileRecord>(line)
                require(seen.add(record.path)) { "恢复暂存清单重复" }
                val file = when {
                    record.path.startsWith("files/") ->
                        BackupDataRegistry.target(File(workspace, "files"), record.path.removePrefix("files/"))
                    record.path in setOf("manifest.json", "preferences.json") -> File(workspace, record.path)
                    else -> error("恢复暂存清单路径无效")
                }
                require(file.isFile && file.length() == record.size && hash(file) == record.sha256) {
                    "恢复暂存资源已损坏，请重新导入：" + record.path
                }
            }
        }
        require("manifest.json" in seen && "preferences.json" in seen) { "恢复暂存摘要缺失" }
        val actual = File(workspace, "files").walkTopDown().count(File::isFile)
        require(seen.size == actual + 2) { "恢复暂存包含未登记资源" }
    }

    private fun hash(file: File, checkActive: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun boundedLine(reader: java.io.BufferedReader): String? {
        val line = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return line.toString().takeIf(String::isNotEmpty)
            if (c == 10) return line.toString()
            require(line.length < 65536) { "恢复暂存记录过长" }
            line.append(c.toChar())
        }
    }
}
