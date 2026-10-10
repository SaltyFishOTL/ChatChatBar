package com.example.chatbar.domain.backup

import android.content.Context
import android.content.ContextWrapper
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.SaveSlotPackageStorage
import com.example.chatbar.domain.image.*
import java.io.File
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

internal object BackupEntityValidator {
    private val entityJson = Json { ignoreUnknownKeys = true }
    private val serializers: Map<String, KSerializer<*>> = mapOf(
        "app_settings" to AppSettings.serializer(), "player_setting" to PlayerSetting.serializer(),
        "character_cards" to CharacterCard.serializer(), "format_cards" to FormatCard.serializer(),
        "world_books" to WorldBook.serializer(), "model_configs" to ModelConfig.serializer(),
        "embedding_configs" to EmbeddingConfig.serializer(), "embedding_model_config" to EmbeddingConfig.serializer(),
        "chat_sessions" to ChatSession.serializer(), "chat_messages" to ChatMessage.serializer(),
        "chat_message_indexes" to ChatMessageIndex.serializer(), "chat_message_order_backups" to ChatMessageOrderBackup.serializer(),
        "chat_drafts" to ChatDraft.serializer(), "chat_scroll_positions" to ChatScrollPosition.serializer(),
        "vector_chunks" to VectorChunk.serializer(), "save_slots" to SaveSlot.serializer(),
        "edit_drafts" to EditorDraft.serializer(), "moment_posts" to MomentPost.serializer(), "moment_tasks" to MomentTask.serializer(),
        "memory_nodes" to MemoryNode.serializer(), "memory_states" to MemorySessionState.serializer(),
        "memory_tier_revisions" to MemoryTierRevision.serializer(), "memory_compression_transactions" to MemoryCompressionTransaction.serializer(),
        "memory_commits" to MemoryCommit.serializer(), "memory_commit_journals" to MemoryCommitJournal.serializer(),
        "generated_voice_messages" to GeneratedVoiceMessage.serializer(), "voice_anchor_states" to VoiceAnchorState.serializer(),
        "novelai_studio_draft" to NovelAiStudioDraft.serializer(), "novelai_studio_history_undo" to NovelAiStudioUndoDraft.serializer(),
        "novelai_studio_guidance_checkpoint" to NovelAiGuidanceEditorCheckpoint.serializer(),
        "novelai_generation_history" to NovelAiGenerationHistoryEntry.serializer(),
        "novelai_history_fold_preferences" to NovelAiHistoryFoldPreference.serializer(),
        "novelai_design_conversations" to NovelAiDesignConversation.serializer(), "novelai_design_current" to NovelAiDesignCurrentState.serializer(),
        "preset_import_state" to PresetImportState.serializer(),
        "model_configuration_migration" to VersionState.serializer(),
        "character_speaker_migration_state" to SpeakerMigrationState.serializer(),
        "world_book_migration_state" to WorldBookMigrationState.serializer(),
        "pending_deletions" to DeletionState.serializer()
    )

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun validateTree(context: Context, filesRoot: File, checkActive: () -> Unit) {
        val scratch = File(AppBackupBootstrap.workspace(context), "validation-" + java.util.UUID.randomUUID()).also(File::mkdirs)
        val stagedContext = object : ContextWrapper(context) {
            override fun getFilesDir(): File = filesRoot
        }
        val identities = hashSetOf<Pair<String, String>>()
        try {
            validateCatalog(filesRoot, checkActive)
            File(filesRoot, "entities").walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { file ->
                checkActive()
                val relative = file.relativeTo(filesRoot).invariantSeparatorsPath
                if (relative == "entities/novelai_prompt_translation_cache.json") return@forEach
                val type = relative.removePrefix("entities/").substringBefore('/').removeSuffix(".json")
                val serializer = requireNotNull(serializers[type]) { "尚未登记的数据类型：" + type }
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
                                }, checkActive = checkActive
                            ) { path, fields ->
                                validatePath(context, filesRoot, type, path, fields)
                                path
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

    private fun validatePath(context: Context, root: File, type: String, path: String, fields: List<String>) {
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
        val file = if (path.startsWith("file:")) File(java.net.URI(path)) else File(path)
        if (file.canonicalPath.startsWith(prefix)) {
            val relative = file.canonicalPath.removePrefix(prefix).replace(File.separatorChar, '/')
            require(BackupDataRegistry.target(root, relative).isFile) { "本地资源缺失：" + relative }
        } else if (path.startsWith(BACKUP_PATH_PREFIX)) {
            require(BackupDataRegistry.target(root, path.removePrefix(BACKUP_PATH_PREFIX)).isFile) { "存档资源引用缺失" }
        } else if (file.isAbsolute) require(file.isFile) { "外部资源文件缺失" }
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

    @Serializable private data class VersionState(val version: Int = 0)
    @Serializable private data class SpeakerMigrationState(val lastRunAt: Long, val migratedCards: Int)
    @Serializable private data class WorldBookMigrationState(val lastRunAt: Long, val migratedCharacters: Int)
    @Serializable private data class DeletionState(val id: String, val type: DeletionType, val ownerId: String,
        val filePaths: List<String> = emptyList(), val createdAt: Long = 0)
    @Serializable private enum class DeletionType { CHARACTER, SESSION }
}
