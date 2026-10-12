package com.example.chatbar.domain.backup

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.image.*
import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/** Registered user stores define export selection and restore-owned directories. */
object BackupDataRegistry {
    val roots = setOf(
        "entities", "images", "audio", "documents", "draft_assets",
        "save_slot_packages", "novelai-vibe-payloads", "danbooru_catalog"
    )
    val preferences = setOf(
        "novelai_credentials", "fish_audio_credentials", "community_session", "image_mask_preferences"
    )
    // These serializers also define the only JSON stores selected for export.
    internal val entitySerializers: Map<String, KSerializer<*>> = mapOf(
        "app_settings" to AppSettings.serializer(), "player_setting" to PlayerSetting.serializer(),
        "character_cards" to CharacterCard.serializer(), "format_cards" to FormatCard.serializer(),
        "world_books" to WorldBook.serializer(), "model_configs" to ModelConfig.serializer(),
        "embedding_configs" to EmbeddingConfig.serializer(), "embedding_model_config" to EmbeddingConfig.serializer(),
        "retrieval_model_config" to ModelConfig.serializer(),
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
    private val mediaRoots = roots - setOf("entities", "danbooru_catalog")
    private val catalogFiles = setOf("tag.sqlite", "catalog.json")
    private val resourceFields = setOf(
        "avatar", "appearanceImage", "chatBackground", "senderAvatar", "imagePath",
        "audioPath", "filePath", "filePaths", "draftAssetPaths", "pendingDeletedAssets", "images", "path"
    )

    fun isResourceField(field: String, ancestors: List<String>): Boolean =
        field in resourceFields && ancestors.none { it == "extensions" || it == "metadata" }

    /** Visit registered user stores directly; never enumerate SDK/runtime siblings in filesDir. */
    fun files(directory: File): Sequence<File> = sequence {
        require(directory.isDirectory) { "无法读取本地数据目录" }
        yieldAll(entityFiles(directory))
        for (root in mediaRoots) {
            val parent = File(directory, root)
            if (!parent.exists()) continue
            checkedDirectory(directory, parent)
            val tree = parent.walkTopDown().onEnter { child ->
                if (child.name.startsWith(".")) false
                else {
                    checkedDirectory(directory, child)
                    true
                }
            }.onFail { file, error ->
                throw IllegalStateException("无法读取用户数据目录：" + file.relativeTo(directory).invariantSeparatorsPath, error)
            }
            for (file in tree) {
                if (file.isDirectory) continue
                val relative = file.relativeTo(directory).invariantSeparatorsPath
                if (!isSelectedFile(relative)) continue
                require(file.isFile) { "用户资源文件损坏：" + relative }
                target(directory, relative)
                yield(file)
            }
        }
        val catalog = File(directory, "danbooru_catalog")
        if (catalog.exists()) {
            checkedDirectory(directory, catalog)
            for (name in catalogFiles) {
                val file = File(catalog, name)
                if (!file.exists()) continue
                require(file.isFile) { "标签库文件损坏：" + name }
                target(directory, "danbooru_catalog/$name")
                yield(file)
            }
        }
    }

    internal fun entityFiles(directory: File): Sequence<File> = sequence {
        val parent = File(directory, "entities")
        if (!parent.exists()) return@sequence
        checkedDirectory(directory, parent)
        for (type in entitySerializers.keys) {
            val singleton = File(parent, "$type.json")
            if (singleton.exists()) {
                require(singleton.isFile) { "用户数据文件损坏：entities/$type.json" }
                target(directory, "entities/$type.json")
                yield(singleton)
            }
            val collection = File(parent, type)
            if (!collection.exists()) continue
            checkedDirectory(directory, collection)
            val records = requireNotNull(collection.listFiles()) { "无法读取用户数据目录：entities/$type" }
            for (file in records) {
                if (file.name.startsWith(".") || file.extension != "json") continue
                val relative = "entities/$type/${file.name}"
                require(file.isFile) { "用户数据文件损坏：" + relative }
                target(directory, relative)
                yield(file)
            }
        }
    }

    internal fun isSelectedFile(relative: String): Boolean {
        val parts = relative.split('/')
        if (parts.any { it.isBlank() || it.startsWith(".") }) return false
        return when (parts.first()) {
            "entities" -> (parts.size == 2 && parts[1].endsWith(".json") &&
                parts[1].removeSuffix(".json") in entitySerializers) ||
                (parts.size == 3 && parts[1] in entitySerializers && parts[2].endsWith(".json"))
            "danbooru_catalog" -> parts.size == 2 && parts[1] in catalogFiles
            in mediaRoots -> parts.size >= 2 &&
                (parts.first() in setOf("documents", "draft_assets") ||
                    parts.last().let { !it.endsWith(".part") && !it.endsWith(".tmp") && !it.endsWith(".backup") })
            else -> false
        }
    }

    private fun checkedDirectory(directory: File, child: File) {
        val relative = child.relativeTo(directory).invariantSeparatorsPath
        require(child.isDirectory) { "用户数据目录损坏：" + relative }
        target(directory, relative)
    }

    fun target(directory: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith("/") && !relative.contains('\\')) {
            "存档路径无效"
        }
        val parts = relative.split('/')
        require(parts.first() in roots && parts.none { it.isBlank() || it == "." || it == ".." }) {
            "存档路径越界：" + relative
        }
        val root = directory.canonicalFile
        val file = File(root, relative).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "存档路径越界" }
        return file
    }

    @Serializable private data class VersionState(val version: Int = 0)
    @Serializable private data class SpeakerMigrationState(val lastRunAt: Long, val migratedCards: Int)
    @Serializable private data class WorldBookMigrationState(val lastRunAt: Long, val migratedCharacters: Int)
    @Serializable private data class DeletionState(val id: String, val type: DeletionType, val ownerId: String,
        val filePaths: List<String> = emptyList(), val createdAt: Long = 0)
    @Serializable private enum class DeletionType { CHARACTER, SESSION }
}
