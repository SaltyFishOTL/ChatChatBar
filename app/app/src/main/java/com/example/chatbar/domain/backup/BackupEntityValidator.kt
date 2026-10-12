package com.example.chatbar.domain.backup

import android.content.Context
import android.content.ContextWrapper
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.SaveSlotPackageStorage
import com.example.chatbar.domain.image.*
import java.io.File
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

internal object BackupEntityValidator {
    private val entityJson = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun validateTree(
        context: Context,
        filesRoot: File,
        exportSelectionOnly: Boolean = false,
        selection: BackupExportPlan? = null,
        checkActive: () -> Unit
    ): BackupValidationReport {
        val scratch = File(AppBackupBootstrap.workspace(context), "validation-" + java.util.UUID.randomUUID()).also(File::mkdirs)
        val stagedContext = object : ContextWrapper(context) {
            override fun getFilesDir(): File = filesRoot
        }
        val identities = hashSetOf<Pair<String, String>>()
        var missingHistoricalAvatars = 0
        try {
            if (selection == null || "tagCatalog" in selection.selection.globalOptions) validateCatalog(filesRoot, checkActive)
            val entities = if (selection != null) selection.records.asSequence().map { it.file }
                else if (exportSelectionOnly) BackupDataRegistry.entityFiles(filesRoot)
                else File(filesRoot, "entities").walkTopDown().filter { it.isFile && it.extension == "json" }
            entities.forEach { file ->
                checkActive()
                val relative = file.relativeTo(filesRoot).invariantSeparatorsPath
                if (relative == "entities/novelai_prompt_translation_cache.json") return@forEach
                val type = relative.removePrefix("entities/").substringBefore('/').removeSuffix(".json")
                val serializer = requireNotNull(BackupDataRegistry.entitySerializers[type]) { "存档包含不支持的数据类型：" + type }
                val skeleton = File(scratch, "entity.json")
                val recordIds = mutableMapOf<String, MutableSet<String>>()
                try {
                    file.strictUtf8Reader().use { input ->
                        skeleton.bufferedWriter().use { output ->
                            BackupJsonStream(input, output,
                                validationSkeleton = true,
                                validateRecord = { collection, record ->
                                    when (collection) {
                                        "messages" -> entityJson.decodeFromString(ChatMessage.serializer(), record).also {
                                            require(it.id.isNotBlank() && it.sessionId.isNotBlank()) { "存档消息 ID 无效" }
                                            require(recordIds.getOrPut(collection) { hashSetOf() }.add(it.id)) { "存档消息 ID 重复" }
                                        }
                                        "vectorChunks" -> entityJson.decodeFromString(VectorChunk.serializer(), record).also {
                                            require(it.id.isNotBlank() && recordIds.getOrPut(collection) { hashSetOf() }.add(it.id)) { "存档向量 ID 无效或重复" }
                                        }
                                        "voiceMessages" -> entityJson.decodeFromString(GeneratedVoiceMessage.serializer(), record).also {
                                            require(it.id.isNotBlank() && recordIds.getOrPut(collection) { hashSetOf() }.add(it.id)) { "存档语音 ID 无效或重复" }
                                        }
                                        "imageResources" -> entityJson.decodeFromString(SaveSlotImageResource.serializer(), record).also {
                                            require(it.fileName.isNotBlank()) { "内联图片文件名无效" }
                                        }
                                        "audioResources" -> entityJson.decodeFromString(SaveSlotAudioResource.serializer(), record).also {
                                            require(it.fileName.isNotBlank()) { "内联音频文件名无效" }
                                        }
                                        "turns" -> entityJson.decodeFromString(NovelAiDesignTurn.serializer(), record)
                                    }
                                }, observeId = { id ->
                                    require(id.isNotBlank() && identities.add(type to id)) { "实体 ID 无效或重复" }
                                }, checkActive = checkActive,
                                replaceValue = { fields -> selection?.replacement(file, fields) },
                                extraRootFields = selection?.extraFields(file).orEmpty()
                            ) { path, fields ->
                                val projected = selection?.resourcePath(file, path, fields) ?: path
                                validatePath(context, filesRoot, type, projected, fields, exportSelectionOnly) {
                                    missingHistoricalAvatars = Math.incrementExact(missingHistoricalAvatars)
                                }
                                projected
                            }.copy()
                        }
                    }
                    val decoded = skeleton.inputStream().buffered().use { entityJson.decodeFromStream(serializer, it) }
                    if (decoded is SaveSlot) {
                        require(decoded.schemaVersion in 1..SaveSlotPackageStorage.SCHEMA_VERSION) { "会话存档版本不支持" }
                        if (decoded.packageRef != null) {
                            val packages = File(filesRoot, "save_slot_packages").canonicalFile
                            val name = decoded.packageRef.fileName
                            require(name.isNotBlank() && !name.contains('/') && !name.contains('\\')) {
                                "会话存档包路径无效"
                            }
                            validatePackageCrc(File(packages, name), packages, checkActive)
                            SaveSlotPackageStorage(stagedContext).openReader(decoded).use { it.validate() }
                        }
                    }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    val reason = if (error is kotlinx.serialization.SerializationException) "记录格式损坏" else error.message
                    throw IllegalStateException("数据校验失败：" + relative + "；" + reason, error)
                } finally { skeleton.delete() }
            }
        } finally { scratch.deleteRecursively() }
        return BackupValidationReport(missingHistoricalAvatars)
    }

    private fun validateCatalog(root: File, checkActive: () -> Unit) {
        val directory = File(root, "danbooru_catalog")
        val database = File(directory, "tag.sqlite")
        val manifest = File(directory, "catalog.json")
        if (!database.exists() && !manifest.exists()) return
        require(database.isFile && manifest.isFile && manifest.length() <= 1024 * 1024) {
            "已安装标签库数据不完整"
        }
        val metadata = manifest.strictUtf8Reader().use {
            entityJson.decodeFromString(DanbooruCatalogMetadata.serializer(), it.readText())
        }
        require(metadata.sourceSha.isNotBlank() && metadata.sourceSizeBytes == database.length() &&
            metadata.rowCount >= 0) { "已安装标签库清单损坏" }
        checkActive()
        android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()) {
                    "已安装标签库损坏"
                }
            }
        }
        checkActive()
    }

    private fun validatePath(
        context: Context, root: File, type: String, path: String, fields: List<String>, exportSelectionOnly: Boolean,
        missingHistoricalAvatar: () -> Unit
    ) {
        if (path.startsWith("chatbar-vibe-file:")) {
            val hash = path.removePrefix("chatbar-vibe-file:")
            require(hash.matches(Regex("[a-f0-9]{64}")) && File(root, "novelai-vibe-payloads/" + hash).isFile) {
                "氛围编码资源缺失"
            }
            return
        }
        if (type == "save_slots" || type == "pending_deletions" || fields.lastOrNull() == "pendingDeletedAssets") return
        if (path.isBlank() || path.startsWith("chatbar-save-slot-") || path.startsWith("http:") || path.startsWith("https:")) return
        val prefix = context.filesDir.canonicalPath + File.separator
        val file = when {
            path.startsWith("file:") -> File(java.net.URI(path))
            !File(path).isAbsolute && path.substringBefore('/') in BackupDataRegistry.roots -> BackupDataRegistry.target(context.filesDir, path)
            else -> File(path)
        }
        fun checkFile(resource: File, reason: String) {
            if (!resource.exists() && BackupHistoricalResourcePolicy.allowsMissingAvatar(type, fields)) {
                missingHistoricalAvatar()
                return
            }
            require(resource.isFile) { reason + "；字段：" + fields.joinToString(".") }
        }
        if (file.canonicalPath.startsWith(prefix)) {
            val relative = file.canonicalPath.removePrefix(prefix).replace(File.separatorChar, '/')
            if (!exportSelectionOnly || BackupDataRegistry.isSelectedFile(relative)) {
                checkFile(BackupDataRegistry.target(root, relative), "本地资源缺失：" + relative)
            } else checkFile(file, "外部资源文件缺失：" + path)
        } else if (path.startsWith(BACKUP_PATH_PREFIX)) {
            checkFile(BackupDataRegistry.target(root, path.removePrefix(BACKUP_PATH_PREFIX)), "存档资源引用缺失")
        } else if (file.isAbsolute) checkFile(file, "外部资源文件缺失：" + path)
    }

    private fun validatePackageCrc(file: File, packages: File, checkActive: () -> Unit) {
        require(file.canonicalFile.parentFile == packages && file.isFile) {
            "会话存档包路径无效"
        }
        val seen = hashSetOf<String>()
        java.util.zip.ZipFile(file).use { zip ->
            val entries = zip.entries()
            val buffer = ByteArray(64 * 1024)
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(seen.add(entry.name)) { "会话存档包条目重复" }
                if (entry.isDirectory) continue
                require(entry.name.split('/').none { it == ".." || it == "." || it.isEmpty() }) {
                    "会话存档包路径越界"
                }
                val crc = java.util.zip.CRC32()
                var count = 0L
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        checkActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        count = Math.addExact(count, n.toLong())
                        require(count <= entry.size) { "会话存档包资源大小无效" }
                        crc.update(buffer, 0, n)
                    }
                }
                require(count == entry.size && crc.value == entry.crc) { "会话存档包资源已损坏" }
            }
        }
    }
}
