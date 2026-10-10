package com.example.chatbar.domain.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString

internal const val BACKUP_PATH_PREFIX = "chatbar-backup-file:"

internal class BackupArchive(
    private val checkActive: () -> Unit,
    private val progress: (Long, Int, String) -> Unit,
    private val openExternal: (String) -> InputStream
) {
    private var bytes = 0L
    private var count = 0

    fun create(
        filesRoot: File,
        workspace: File,
        output: OutputStream,
        password: CharArray?,
        appVersionCode: Int,
        appVersionName: String,
        preferences: BackupPreferences
    ): AppBackupSummary {
        workspace.mkdirs()
        val inventory = File(workspace, "inventory.jsonl")
        val external = linkedMapOf<String, String>()
        val counts = linkedMapOf<String, Int>()
        val payload = AppBackupCodec.encrypting(output, password)
        ZipOutputStream(payload).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            inventory.bufferedWriter().use { index ->
                fun append(file: File, relative: String) {
                    checkActive()
                    BackupDataRegistry.target(filesRoot, relative)
                    zip.putNextEntry(ZipEntry("files/" + relative))
                    val digest = MessageDigest.getInstance("SHA-256")
                    val size = file.inputStream().buffered().use { copy(it, zip, digest, relative) }
                    zip.closeEntry()
                    val hash = hex(digest.digest())
                    if (relative.startsWith("novelai-vibe-payloads/")) {
                        require(relative.substringAfter('/') == hash) { "氛围编码资源已损坏" }
                    }
                    index.write(backupJson.encodeToString(AppBackupFileRecord(relative, size, hash)))
                    index.newLine()
                    count++
                    if (relative.startsWith("entities/")) {
                        val type = relative.removePrefix("entities/").substringBefore('/').removeSuffix(".json")
                        counts[type] = (counts[type] ?: 0) + 1
                    }
                }
                BackupDataRegistry.files(filesRoot).forEach { source ->
                    val relative = source.relativeTo(filesRoot).invariantSeparatorsPath
                    val beforeLength = source.length()
                    val beforeModified = source.lastModified()
                    if (relative.startsWith("entities/") && source.extension == "json") {
                        val converted = File(workspace, "entity.tmp")
                        try {
                            source.strictUtf8Reader().use { reader ->
                                converted.bufferedWriter().use { writer ->
                                    BackupJsonStream(reader, writer, checkActive = checkActive) { path, fields ->
                                        portablePath(path, fields, relative, filesRoot, external)
                                    }.copy()
                                }
                            }
                            append(converted, relative)
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            throw IllegalStateException("本地数据无法完整导出：" + relative + "；" + error.message, error)
                        } finally { converted.delete() }
                    } else append(source, relative)
                    check(source.length() == beforeLength && source.lastModified() == beforeModified) {
                        "导出期间源数据发生变化，请重试"
                    }
                }
                for ((source, relative) in external) {
                    val temporary = File(workspace, "external.tmp")
                    try {
                        openExternal(source).use { input ->
                            temporary.outputStream().buffered().use { copy(input, it, null, "读取外部资源") }
                        }
                        append(temporary, relative)
                    } finally { temporary.delete() }
                }
            }
            val preferenceBytes = backupJson.encodeToString(preferences).toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry("preferences.json"))
            zip.write(preferenceBytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("inventory.jsonl"))
            inventory.inputStream().use { copy(it, zip, null, "写入校验清单") }
            zip.closeEntry()
            val summary = AppBackupSummary(
                appVersionCode = appVersionCode, appVersionName = appVersionName,
                createdAt = System.currentTimeMillis(), sourceFilesRoot = filesRoot.absolutePath,
                counts = counts, fileCount = count, totalBytes = bytes,
                preferencesSha256 = hex(MessageDigest.getInstance("SHA-256").digest(preferenceBytes))
            )
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(backupJson.encodeToString(summary).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.finish()
            return summary
        }
    }

    fun extract(
        input: InputStream,
        password: CharArray?,
        workspace: File,
        targetFilesRoot: File,
        appVersionCode: Int,
        validateOnly: Boolean = false
    ): AppBackupSummary {
        workspace.mkdirs()
        val archive = File(workspace, "payload.zip")
        AppBackupCodec.decrypting(input, password).use { plain ->
            archive.outputStream().buffered().use { output ->
                copy(plain, output, null, "读取存档", workspace.usableSpace - 32L * 1024 * 1024)
            }
        }
        val stage = File(workspace, "files").also(File::mkdirs)
        val summary = ZipFile(archive).use { zip ->
            val names = hashSetOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(!entry.isDirectory && names.add(entry.name)) { "存档包含重复或无效条目" }
                require(entry.size >= 0L) { "存档条目大小无效" }
                if (entry.name.startsWith("files/")) {
                    BackupDataRegistry.target(stage, entry.name.removePrefix("files/"))
                } else require(entry.name in setOf("manifest.json", "inventory.jsonl", "preferences.json")) {
                    "存档包含未知条目"
                }
            }
            fun small(name: String): String {
                val entry = requireNotNull(zip.getEntry(name)) { "存档缺少 " + name }
                require(entry.size in 1..1024 * 1024) { "存档摘要大小无效" }
                val crc = java.util.zip.CRC32()
                val checked = java.util.zip.CheckedInputStream(zip.getInputStream(entry), crc)
                val textBytes = java.io.ByteArrayOutputStream()
                val actual = checked.use { copy(it, textBytes, null, "读取存档摘要", entry.size) }
                require(actual == entry.size) { "存档摘要大小不匹配" }
                val text = textBytes.toByteArray().inputStream().strictUtf8Reader().use { it.readText() }
                require(crc.value == entry.crc) { "存档摘要校验失败：" + name }
                return text
            }
            val manifest = backupJson.decodeFromString<AppBackupSummary>(small("manifest.json"))
            require(manifest.formatVersion == 1) { "存档格式不支持，请升级 APP" }
            require(manifest.appVersionCode <= appVersionCode) { "该存档来自更新版本，请先升级 APP" }
            require(manifest.fileCount >= 0 && manifest.totalBytes >= 0L &&
                manifest.counts.values.all { it >= 0 }) { "存档数量无效" }
            val preferenceText = small("preferences.json")
            require(hex(MessageDigest.getInstance("SHA-256").digest(preferenceText.toByteArray(Charsets.UTF_8))) ==
                manifest.preferencesSha256) { "存档偏好与密钥校验失败" }
            val preferences = backupJson.decodeFromString<BackupPreferences>(preferenceText)
            preferences.communitySession?.let {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString<com.example.chatbar.domain.community.CommunitySession>(it)
            }
            require(preferences.novelAiToken == null || preferences.novelAiToken.isNotBlank()) { "NovelAI 密钥无效" }
            require(preferences.fishAudioKey == null || preferences.fishAudioKey.isNotBlank()) { "Fish Audio 密钥无效" }
            require(preferences.brushSize.isFinite() && preferences.brushType.length <= 100) { "图片偏好无效" }
            val records = requireNotNull(zip.getEntry("inventory.jsonl")) { "存档缺少校验清单" }
            var actualBytes = 0L
            var actualCount = 0
            val seen = hashSetOf<String>()
            val counts = linkedMapOf<String, Int>()
            val inventoryCrc = java.util.zip.CRC32()
            java.util.zip.CheckedInputStream(zip.getInputStream(records), inventoryCrc).strictUtf8Reader().use { reader ->
                while (true) {
                    checkActive()
                    val line = boundedLine(reader) ?: break
                    val record = backupJson.decodeFromString<AppBackupFileRecord>(line)
                    require(record.size >= 0 && record.sha256.matches(Regex("[a-f0-9]{64}"))) { "校验清单无效" }
                    require(seen.add(record.path)) { "校验清单条目重复" }
                    val target = BackupDataRegistry.target(stage, record.path)
                    val entry = requireNotNull(zip.getEntry("files/" + record.path)) { "存档缺少资源：" + record.path }
                    require(entry.size == record.size) { "存档资源大小不匹配：" + record.path }
                    if (!validateOnly) {
                        ensureSpace(workspace, record.size)
                        target.parentFile!!.mkdirs()
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    val actual = zip.getInputStream(entry).use { source ->
                        val sink = if (validateOnly) discardOutput else target.outputStream().buffered()
                        sink.use { copy(source, it, digest, record.path, record.size) }
                    }
                    require(actual == record.size && hex(digest.digest()) == record.sha256) {
                        "存档资源校验失败：" + record.path
                    }
                    if (record.path.startsWith("novelai-vibe-payloads/")) {
                        require(record.path.substringAfter('/') == record.sha256) { "氛围编码资源已损坏" }
                    }
                    actualCount++
                    require(actualCount <= manifest.fileCount) { "存档校验清单数量超出声明" }
                    actualBytes = Math.addExact(actualBytes, actual)
                    require(actualBytes <= manifest.totalBytes) { "存档资源总大小超出声明" }
                    if (record.path.startsWith("entities/")) {
                        val type = record.path.removePrefix("entities/").substringBefore('/').removeSuffix(".json")
                        counts[type] = (counts[type] ?: 0) + 1
                    }
                }
            }
            require(inventoryCrc.value == records.crc) { "存档校验清单已损坏" }
            require(actualCount == manifest.fileCount && counts == manifest.counts) { "存档数据数量不完整" }
            // Manifest byte count includes only files, not archive metadata/transport overhead.
            require(actualBytes == manifest.totalBytes) { "存档资源总大小不匹配" }
            require(names.size == actualCount + 3) { "存档存在未登记的资源" }
            File(workspace, "preferences.json").writeText(backupJson.encodeToString(preferences))
            manifest
        }
        stage.walkTopDown().filter { it.isFile && it.extension == "json" &&
            it.relativeTo(stage).invariantSeparatorsPath.startsWith("entities/") }.forEach { entity ->
            checkActive()
            val converted = File(workspace, "mapped.tmp")
            val relative = entity.relativeTo(stage).invariantSeparatorsPath
            entity.strictUtf8Reader().use { reader ->
                converted.bufferedWriter().use { writer ->
                    BackupJsonStream(reader, writer, checkActive = checkActive) { path, fields ->
                        if (!path.startsWith(BACKUP_PATH_PREFIX)) {
                            require(!File(path).isAbsolute && !path.startsWith("file:") && !path.startsWith("content:")) {
                                "存档含未转换的旧机资源路径"
                            }
                            path
                        } else {
                            val resource = path.removePrefix(BACKUP_PATH_PREFIX)
                            val stagedResource = BackupDataRegistry.target(stage, resource)
                            val inline = relative.startsWith("entities/save_slots/") ||
                                relative.startsWith("entities/pending_deletions/") || fields.lastOrNull() == "pendingDeletedAssets"
                            require(inline || stagedResource.isFile) { "存档引用资源缺失：" + resource }
                            BackupDataRegistry.target(targetFilesRoot, resource).absolutePath
                        }
                    }.copy()
                }
            }
            java.nio.file.Files.move(converted.toPath(), entity.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        File(workspace, "manifest.json").writeText(backupJson.encodeToString(summary))
        archive.delete()
        return summary
    }

    private fun portablePath(
        path: String, fields: List<String>, entity: String, root: File, external: MutableMap<String, String>
    ): String {
        if (path.isBlank() || path.startsWith("http://") || path.startsWith("https://") ||
            path.startsWith("chatbar-save-slot-")) return path
        if (path.startsWith("chatbar-vibe-file:")) {
            val hash = path.removePrefix("chatbar-vibe-file:")
            require(hash.matches(Regex("[a-f0-9]{64}")) && File(root, "novelai-vibe-payloads/" + hash).isFile) {
                "氛围编码资源缺失"
            }
            return path
        }
        if (path.startsWith("content:")) {
            val relative = external.getOrPut(path) { "images/migrated_external/" + UUID.randomUUID() + ".bin" }
            return BACKUP_PATH_PREFIX + relative
        }
        val file = if (path.startsWith("file:")) File(java.net.URI(path)) else File(path)
        val canonicalRoot = root.canonicalFile
        val canonical = file.canonicalFile
        if (canonical.path.startsWith(canonicalRoot.path + File.separator)) {
            val relative = canonical.relativeTo(canonicalRoot).invariantSeparatorsPath
            BackupDataRegistry.target(root, relative)
            require(entity.startsWith("entities/save_slots/") || entity.startsWith("entities/pending_deletions/") ||
                fields.lastOrNull() == "pendingDeletedAssets" || canonical.isFile) {
                "本地资源缺失：" + relative
            }
            return BACKUP_PATH_PREFIX + relative
        }
        if (!file.isAbsolute) return path
        if (entity.startsWith("entities/save_slots/") || (entity.startsWith("entities/pending_deletions/") && !file.exists())) {
            // Legacy inline resources carry their bytes in the same JSON; remap keys and references together.
            val digest = hex(MessageDigest.getInstance("SHA-256").digest(path.toByteArray()))
            return BACKUP_PATH_PREFIX + "images/migrated_external/inline-" + digest
        }
        val relative = external.getOrPut(path) {
            val extension = file.extension.takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) } ?: "bin"
            "images/migrated_external/" + UUID.randomUUID() + "." + extension
        }
        return BACKUP_PATH_PREFIX + relative
    }

    private fun copy(input: InputStream, output: OutputStream, digest: MessageDigest?, label: String, maximum: Long = Long.MAX_VALUE): Long {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            checkActive()
            val n = input.read(buffer)
            if (n < 0) break
            require(n.toLong() <= maximum - copied) { "存档资源超出声明大小或可用空间" }
            output.write(buffer, 0, n)
            digest?.update(buffer, 0, n)
            copied = Math.addExact(copied, n.toLong())
            if (digest != null) bytes = Math.addExact(bytes, n.toLong())
            progress(if (digest == null) copied else bytes, count, label)
        }
        return copied
    }

    companion object {
        private val discardOutput = object : OutputStream() {
            override fun write(value: Int) {}
            override fun write(bytes: ByteArray, offset: Int, length: Int) {}
        }
        fun ensureSpace(directory: File, required: Long) {
            require(required >= 0 && required <= directory.usableSpace - 32L * 1024 * 1024) {
                "存储空间不足，请释放空间后重试"
            }
        }
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
        private fun boundedLine(reader: java.io.BufferedReader): String? {
            val line = StringBuilder()
            while (true) {
                val c = reader.read()
                if (c < 0) return line.toString().takeIf { it.isNotEmpty() }
                if (c == 10) return line.toString()
                require(line.length < 65536) { "校验清单记录过长" }
                line.append(c.toChar())
            }
        }
    }
}
