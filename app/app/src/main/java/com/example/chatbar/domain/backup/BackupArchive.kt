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
        preferences: BackupPreferences,
        selection: BackupExportPlan? = null
    ): AppBackupSummary {
        workspace.mkdirs()
        val inventory = File(workspace, "inventory.jsonl")
        val external = linkedMapOf<String, String>()
        val counts = linkedMapOf<String, Int>()
        val referencedFiles = linkedMapOf<String, File>()
        val appended = hashSetOf<String>()
        val payload = AppBackupCodec.encrypting(output, password, if (selection == null) 1 else 2)
        ZipOutputStream(payload).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            inventory.bufferedWriter().use { index ->
                fun append(file: File, relative: String) {
                    checkActive()
                    BackupDataRegistry.target(filesRoot, relative)
                    if (!appended.add(relative)) return
                    require(file.isFile) { "本地资源缺失：" + relative }
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
                val selectedFiles = selection?.let { plan ->
                    (plan.records.map { it.file } + plan.additionalFiles.map { it.first }).asSequence()
                } ?: BackupDataRegistry.files(filesRoot)
                selectedFiles.forEach { source ->
                    val relative = source.relativeTo(filesRoot).invariantSeparatorsPath
                    val beforeLength = source.length()
                    val beforeModified = source.lastModified()
                    if (relative.startsWith("entities/") && source.extension == "json") {
                        val converted = File(workspace, "entity.tmp")
                        try {
                            source.strictUtf8Reader().use { reader ->
                                converted.bufferedWriter().use { writer ->
                                    BackupJsonStream(reader, writer, checkActive = checkActive,
                                        replaceValue = { fields -> selection?.replacement(source, fields) },
                                        extraRootFields = selection?.extraFields(source).orEmpty()
                                    ) { path, fields ->
                                        portablePath(selection?.resourcePath(source, path, fields) ?: path,
                                            fields, relative, filesRoot, external, referencedFiles)
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
                if (selection != null) referencedFiles.forEach { (relative, source) ->
                    val length = source.length()
                    val modified = source.lastModified()
                    append(source, relative)
                    check(source.length() == length && source.lastModified() == modified) { "导出期间源数据发生变化，请重试" }
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
                preferencesSha256 = hex(MessageDigest.getInstance("SHA-256").digest(preferenceBytes)),
                formatVersion = if (selection == null) 1 else 2,
                scope = selection?.selection?.let { AppBackupExportScope.from(it) }
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
            manifest.requireCompatibleWith(appVersionCode)
            require(manifest.fileCount >= 0 && manifest.totalBytes >= 0L &&
                manifest.counts.values.all { it >= 0 }) { "存档数量无效" }
            if (manifest.formatVersion == 2) {
                val scope = requireNotNull(manifest.scope) { "所选存档缺少数据范围" }
                require(scope.globalOptions.all { it in BackupSettingOptions.ids } &&
                    scope.characters == (manifest.counts["character_cards"] ?: 0) &&
                    scope.sessions == (manifest.counts["chat_sessions"] ?: 0) &&
                    scope.sessionsWithImages in 0..scope.sessions && scope.sessionsWithVoice in 0..scope.sessions &&
                    scope.sessionsWithArchives in 0..scope.sessions) { "存档选择范围无效" }
            }
            val preferenceText = small("preferences.json")
            require(hex(MessageDigest.getInstance("SHA-256").digest(preferenceText.toByteArray(Charsets.UTF_8))) ==
                manifest.preferencesSha256) { "存档偏好与密钥校验失败" }
            val preferences = backupJson.decodeFromString<BackupPreferences>(preferenceText)
            require(preferences.retainedPreferences.isEmpty()) { "存档包含无效的本机偏好指令" }
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
                            val missingAvatar = !stagedResource.exists() &&
                                BackupHistoricalResourcePolicy.allowsMissingAvatar(relative, fields)
                            require(inline || missingAvatar || stagedResource.isFile) {
                                "存档引用资源缺失：" + resource + "；字段：" + fields.joinToString(".")
                            }
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
        path: String, fields: List<String>, entity: String, root: File, external: MutableMap<String, String>,
        referencedFiles: MutableMap<String, File>
    ): String {
        if (path.isBlank() || path.startsWith("http://") || path.startsWith("https://") ||
            path.startsWith("chatbar-save-slot-")) return path
        if (path.startsWith("chatbar-vibe-file:")) {
            val hash = path.removePrefix("chatbar-vibe-file:")
            require(hash.matches(Regex("[a-f0-9]{64}")) && File(root, "novelai-vibe-payloads/" + hash).isFile) {
                "氛围编码资源缺失"
            }
            referencedFiles["novelai-vibe-payloads/$hash"] = File(root, "novelai-vibe-payloads/$hash")
            return path
        }
        if (path.startsWith("content:")) {
            val relative = external.getOrPut(path) { "images/migrated_external/" + UUID.randomUUID() + ".bin" }
            return BACKUP_PATH_PREFIX + relative
        }
        val file = when {
            path.startsWith("file:") -> File(java.net.URI(path))
            !File(path).isAbsolute && path.substringBefore('/') in BackupDataRegistry.roots -> BackupDataRegistry.target(root, path)
            else -> File(path)
        }
        val canonicalRoot = root.canonicalFile
        val canonical = file.canonicalFile
        if (canonical.path.startsWith(canonicalRoot.path + File.separator) &&
            BackupDataRegistry.isSelectedFile(canonical.relativeTo(canonicalRoot).invariantSeparatorsPath)) {
            val relative = canonical.relativeTo(canonicalRoot).invariantSeparatorsPath
            BackupDataRegistry.target(root, relative)
            require(entity.startsWith("entities/save_slots/") || entity.startsWith("entities/pending_deletions/") ||
                fields.lastOrNull() == "pendingDeletedAssets" || canonical.isFile ||
                (!canonical.exists() && BackupHistoricalResourcePolicy.allowsMissingAvatar(entity, fields))) {
                "本地资源缺失：" + relative + "；字段：" + fields.joinToString(".")
            }
            if (canonical.isFile) referencedFiles[relative] = canonical
            return BACKUP_PATH_PREFIX + relative
        }
        if (!file.isAbsolute) return path
        if (!file.exists() && BackupHistoricalResourcePolicy.allowsMissingAvatar(entity, fields)) {
            val digest = hex(MessageDigest.getInstance("SHA-256").digest(path.toByteArray()))
            return BACKUP_PATH_PREFIX + "images/migrated_external/missing-avatar-" + digest
        }
        if (entity.startsWith("entities/save_slots/") ||
            (!file.exists() && (entity.startsWith("entities/pending_deletions/") || fields.lastOrNull() == "pendingDeletedAssets"))) {
            // Legacy inline resources carry their bytes in the same JSON; remap keys and references together.
            val digest = hex(MessageDigest.getInstance("SHA-256").digest(path.toByteArray()))
            return BACKUP_PATH_PREFIX + "images/migrated_external/inline-" + digest
        }
        val relative = external.getOrPut(canonical.path) {
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
