package com.example.chatbar.domain.backup

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX
import java.io.File
import java.io.Writer
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject

data class BackupSettingOption(val id: String, val label: String, val group: String, val fields: Set<String> = emptySet())

/** Product choices; hidden compatibility flags travel with their owning settings. */
object BackupSettingOptions {
    private fun field(name: String, label: String, group: String, vararg related: String) =
        BackupSettingOption(name, label, group, setOf(name, *related))
    val all = listOf(
        field("defaultModelId", "默认聊天模型", "模型与连接"),
        field("defaultImageModelId", "默认视觉模型", "模型与连接"),
        field("modelConfigurationMode", "模型配置方式", "模型与连接", "presetDefaultModelKey"),
        field("siliconFlowApiKey", "硅基流动密钥", "模型与连接"),
        field("allowCleartextModelApi", "允许本地 HTTP 模型", "模型与连接"),
        field("defaultEmbeddingId", "默认向量模型", "模型与连接"),
        BackupSettingOption("models", "全部模型配置与密钥", "模型与连接"),
        BackupSettingOption("embedding", "全部向量模型配置", "模型与连接"),
        field("defaultFormatCardId", "默认格式卡", "回复与记忆"),
        field("automaticFormatCheckEnabled", "自动格式检查", "回复与记忆"),
        field("formatRepairModelId", "格式修复模型", "回复与记忆"),
        field("memoryRagTopK", "记忆检索数量", "回复与记忆"),
        field("memoryRagSimilarityThreshold", "记忆相似度阈值", "回复与记忆"),
        field("docRagTopK", "文档检索数量", "回复与记忆"),
        field("docRagSimilarityThreshold", "文档相似度阈值", "回复与记忆"),
        field("ragInjectionMode", "RAG 注入方式", "回复与记忆"),
        field("defaultContextWindowSize", "默认上下文长度", "回复与记忆"),
        field("episodeMaxSourceTurns", "长期记忆片段长度", "回复与记忆"),
        field("excludeAssistantStatusFromHistory", "历史省略状态栏", "回复与记忆"),
        BackupSettingOption("resources", "全部格式卡与世界书", "回复与记忆"),
        field("themeMode", "深浅色模式", "外观与显示"),
        field("themeColor", "主题颜色与历史", "外观与显示", "themeColorHistory"),
        field("chatBubbleFontScale", "聊天字号", "外观与显示"),
        field("chatBackgroundImageOpacity", "背景透明度", "外观与显示"),
        field("assistantSegmentedBubblesEnabled", "分段气泡", "外观与显示"),
        field("tutorialVersion", "教程进度", "外观与显示"),
        BackupSettingOption("player", "玩家名称与设定", "玩家与研究"),
        field("characterAutoFillWebSearchEnabled", "角色补全联网研究", "玩家与研究"),
        field("characterRewriteWebSearchEnabled", "角色改写联网研究", "玩家与研究"),
        field("characterAutoFillResearchSourceMode", "角色补全研究来源", "玩家与研究"),
        field("characterRewriteResearchSourceMode", "角色改写研究来源", "玩家与研究"),
        field("worldBookAiResearchSourceMode", "世界书研究来源", "玩家与研究"),
        field("webSearchMaxResultsPerQuery", "研究搜索数量", "玩家与研究"),
        field("novelAiImageModel", "默认生图模型", "图片生成"),
        field("novelAiImageAspectRatio", "默认生图比例", "图片生成"),
        field("novelAiPromptTranslationConsent", "提示词翻译偏好", "图片生成"),
        field("imagePromptToolPreference", "图片提示词偏好", "图片生成"),
        BackupSettingOption("novelAiCredentials", "NovelAI 密钥", "图片生成"),
        BackupSettingOption("imagePreferences", "图片处理偏好", "图片生成"),
        BackupSettingOption("tagCatalog", "已安装标签库", "图片生成"),
        field("fishAudioTtsModelId", "语音生成模型", "语音与朗读"),
        field("voiceTagModelId", "语音标记模型", "语音与朗读"),
        field("audiobookModeEnabled", "听书模式", "语音与朗读"),
        BackupSettingOption("fishAudioCredentials", "Fish Audio 密钥", "语音与朗读"),
        field("momentsEnabled", "朋友圈开关", "朋友圈与其他"),
        field("momentsImagesEnabled", "朋友圈自动配图", "朋友圈与其他"),
        field("momentsMinDelayHours", "朋友圈最短间隔", "朋友圈与其他"),
        field("momentsMaxDelayHours", "朋友圈最长间隔", "朋友圈与其他"),
        field("momentsBackgroundGuideDismissed", "朋友圈后台引导状态", "朋友圈与其他"),
        BackupSettingOption("community", "社区登录状态", "朋友圈与其他"),
        BackupSettingOption("editorDrafts", "角色、格式卡、世界书编辑草稿", "朋友圈与其他")
    )
    val ids = all.map { it.id }.toSet()
    fun fields(options: Set<String>) = all.filter { it.id in options }.flatMap { it.fields }.toSet()
}

@Serializable
data class BackupSessionChoice(val images: Boolean = true, val voice: Boolean = true, val archives: Boolean = true)

enum class BackupSessionContent { IMAGES, VOICE, ARCHIVES }

@Serializable
data class AppBackupSelection(
    val globalOptions: Set<String> = emptySet(),
    val characterIds: Set<String> = emptySet(),
    val sessions: Map<String, BackupSessionChoice> = emptyMap(),
    val studioSettings: Boolean = false,
    val studioHistory: Boolean = false,
    val studioHistoryImages: Boolean = true,
    val moments: Boolean = false,
    val momentsImages: Boolean = true,
    val sessionImagesDefault: Boolean = true,
    val sessionVoiceDefault: Boolean = true,
    val sessionArchivesDefault: Boolean = true
) {
    val isEmpty get() = globalOptions.isEmpty() && characterIds.isEmpty() && sessions.isEmpty() &&
        !studioSettings && !studioHistory && !moments

    fun newSessionChoice() = BackupSessionChoice(sessionImagesDefault, sessionVoiceDefault, sessionArchivesDefault)

    /** A bulk media change preserves selection, voice and independent archive choices. */
    fun withAllSessionImages(include: Boolean): AppBackupSelection = copy(
        sessionImagesDefault = include,
        sessions = sessions.mapValues { it.value.copy(images = include) }
    )

    fun withAllSessionContent(content: BackupSessionContent, include: Boolean): AppBackupSelection = when (content) {
        BackupSessionContent.IMAGES -> withAllSessionImages(include)
        BackupSessionContent.VOICE -> copy(sessionVoiceDefault = include,
            sessions = sessions.mapValues { it.value.copy(voice = include) })
        BackupSessionContent.ARCHIVES -> copy(sessionArchivesDefault = include,
            sessions = sessions.mapValues { it.value.copy(archives = include) })
    }

    fun withSessions(ids: Collection<String>, selected: Boolean): AppBackupSelection = copy(
        sessions = if (selected) sessions + ids.associateWith { sessions[it] ?: newSessionChoice() }
        else sessions - ids.toSet()
    )

    fun withAllSessions(catalog: BackupExportCatalog): AppBackupSelection = copy(
        characterIds = catalog.characters.map { it.id }.toSet()
    ).withSessions(catalog.sessions.filter { session -> catalog.characters.any { it.id == session.characterId } }.map { it.id }, true)

    fun withCharacter(id: String, selected: Boolean, catalog: BackupExportCatalog): AppBackupSelection = copy(
        characterIds = if (selected) characterIds + id else characterIds - id,
        sessions = if (selected) sessions else sessions.filterKeys { session ->
            catalog.sessions.firstOrNull { it.id == session }?.characterId != id
        }
    )
    fun normalized(catalog: BackupExportCatalog): AppBackupSelection = copy(
        globalOptions = globalOptions.intersect(BackupSettingOptions.ids),
        characterIds = characterIds.intersect(catalog.characters.map { it.id }.toSet()),
        sessions = sessions.filterKeys { id -> catalog.sessions.any { it.id == id && it.characterId in characterIds } }
    )
}

data class BackupCharacterItem(val id: String, val name: String)
data class BackupSessionItem(val id: String, val characterId: String, val title: String)
data class BackupSizeEstimate(val bytes: Long, val files: Int, val unknownResources: Int)
data class BackupCatalogState(val loading: Boolean = false, val catalog: BackupExportCatalog? = null, val error: String? = null)

internal data class BackupResourceReference(val path: String, val fields: List<String>)
internal data class BackupExportRecord(
    val file: File, val relative: String, val type: String,
    val values: Map<String, Set<String>>, val resources: List<BackupResourceReference>,
    val withoutChatImagesBytes: Long, val withoutStudioImagesBytes: Long, val withoutMomentImagesBytes: Long
) {
    val id get() = values["id"]?.firstOrNull() ?: file.nameWithoutExtension
    fun strings(field: String) = values[field].orEmpty()
    fun string(field: String) = strings(field).firstOrNull()
}

class BackupExportCatalog internal constructor(
    internal val root: File,
    internal val records: List<BackupExportRecord>
) {
    val characters = records.filter { it.type == "character_cards" }.map { BackupCharacterItem(it.id, it.string("name") ?: "未命名角色") }
        .sortedBy { it.name }
    val sessions = records.filter { it.type == "chat_sessions" }.map {
        BackupSessionItem(it.id, it.string("characterCardId").orEmpty(), it.string("displayTitleOverride") ?: it.string("title") ?: "未命名会话")
    }.sortedBy { it.title }
    val historyCount = records.count { it.type == "novelai_generation_history" }
    val momentCount = records.count { it.type == "moment_posts" }
    fun allSelection() = AppBackupSelection(
        BackupSettingOptions.ids, characters.map { it.id }.toSet(),
        sessions.filter { session -> characters.any { it.id == session.characterId } }.associate { it.id to BackupSessionChoice() },
        studioSettings = true, studioHistory = true, moments = true
    )
    fun lightSelection() = allSelection().let { all -> all.copy(
        sessions = all.sessions.mapValues { BackupSessionChoice(images = false, voice = false, archives = false) },
        studioHistoryImages = false, momentsImages = false, sessionImagesDefault = false,
        sessionVoiceDefault = false, sessionArchivesDefault = false
    ) }
    fun estimate(selection: AppBackupSelection): BackupSizeEstimate = plan(selection).estimate()

    internal fun plan(request: AppBackupSelection): BackupExportPlan {
        val selection = request.normalized(this)
        require(selection == request) { "选择内容已变化，请刷新清单后重新选择" }
        require(!selection.isEmpty) { "请至少选择一项数据" }
        val cards = records.filter { it.type == "character_cards" && it.id in selection.characterIds }
        val chats = records.filter { it.type == "chat_sessions" && it.id in selection.sessions }
        val settingsFields = BackupSettingOptions.fields(selection.globalOptions)
        val defaultSettings = backupJson.encodeToJsonElement(AppSettings.serializer(), AppSettings()) as JsonObject
        val settings = records.filter { it.type == "app_settings" }
        val sourceSettings = settings.firstOrNull()
        val cardsById = cards.associateBy { it.id }
        fun sessionFallbacks(record: BackupExportRecord): Map<String, String> = buildMap {
            fun fallback(field: String, global: String, value: String?) {
                if (record.string(field) == null && global !in settingsFields && value != null) put(field, backupJson.encodeToString(value))
            }
            fallback("modelId", "defaultModelId", sourceSettings?.string("defaultModelId"))
            fallback("imageModelId", "defaultImageModelId", sourceSettings?.string("defaultImageModelId"))
            fallback("formatCardId", "defaultFormatCardId", cardsById[record.string("characterCardId")]?.string("defaultFormatCardId")
                ?: sourceSettings?.string("defaultFormatCardId"))
        }
        val archives = records.filter { it.type == "save_slots" && selection.sessions[it.string("sessionId")]?.archives == true }
        val studio = if (selection.studioSettings) records.filter { it.type.startsWith("novelai_studio_") || it.type.startsWith("novelai_design_") } else emptyList()
        val drafts = records.filter { it.type == "edit_drafts" && ("editorDrafts" in selection.globalOptions || it.string("targetId") in selection.characterIds) }
        val dependencyRecords = cards + chats + archives + studio + drafts + if (selection.globalOptions.isNotEmpty()) settings else emptyList()
        fun dependencies(vararg fields: String): Set<String> = dependencyRecords.flatMap { record ->
            record.values.filterKeys { key -> key.substringAfterLast('.') in fields &&
                (record.type != "app_settings" || key in settingsFields) }.values.flatten()
        }.toSet()
        val formatIds = dependencies("defaultFormatCardId", "formatCardId") + if (chats.isNotEmpty()) sourceSettings?.strings("defaultFormatCardId").orEmpty() else emptySet()
        val worldIds = dependencies("worldBookIds", "boundWorldBookId", "extraWorldBookIds")
        val modelIds = dependencies("modelId", "imageModelId", "defaultModelId", "defaultImageModelId", "formatRepairModelId", "voiceTagModelId", "aiDesignModelId", "designModelId") +
            (if (chats.isNotEmpty() || selection.studioSettings || selection.moments) sourceSettings?.strings("defaultModelId").orEmpty() +
                sourceSettings?.strings("defaultImageModelId").orEmpty() else emptySet())
        val documentIds = cards.flatMap { it.strings("customDocuments.id") }.toSet()
        val selectedRecords = records.filter { record ->
            when (record.type) {
                "app_settings" -> settingsFields.isNotEmpty() || selection.sessions.isNotEmpty() || selection.moments
                "player_setting" -> "player" in selection.globalOptions
                "character_cards" -> record.id in selection.characterIds
                "chat_sessions" -> record.id in selection.sessions
                "model_configs", "retrieval_model_config" -> "models" in selection.globalOptions || record.id in modelIds
                "embedding_configs", "embedding_model_config" -> "embedding" in selection.globalOptions || cards.isNotEmpty() || chats.isNotEmpty() ||
                    record.id in dependencies("defaultEmbeddingId")
                "format_cards" -> "resources" in selection.globalOptions || record.id in formatIds
                "world_books" -> "resources" in selection.globalOptions || record.id in worldIds
                "generated_voice_messages", "voice_anchor_states" -> selection.sessions[record.string("sessionId")?.takeIf { it.isNotBlank() }
                    ?: records.firstOrNull { it.type == "chat_messages" && it.id == record.string("messageId") }?.string("sessionId")]?.voice == true
                "save_slots" -> selection.sessions[record.string("sessionId")]?.archives == true
                "vector_chunks" -> record.string("sourceId") in selection.sessions || record.string("sourceId") in selection.characterIds ||
                    record.string("sourceId") in documentIds
                "edit_drafts" -> "editorDrafts" in selection.globalOptions || record.string("targetId") in selection.characterIds
                "moment_posts", "moment_tasks" -> selection.moments
                "novelai_generation_history", "novelai_history_fold_preferences" -> selection.studioHistory
                "novelai_studio_draft", "novelai_studio_history_undo", "novelai_studio_guidance_checkpoint",
                "novelai_design_conversations", "novelai_design_current" -> selection.studioSettings
                "preset_import_state", "model_configuration_migration", "character_speaker_migration_state", "world_book_migration_state" -> true
                "pending_deletions" -> false
                else -> record.string("sessionId") in selection.sessions
            }
        }
        val replacements: (BackupExportRecord, List<String>) -> String? = { record, fields ->
            when {
                record.type == "chat_sessions" && fields.size == 1 -> sessionFallbacks(record)[fields.single()]
                record.type == "app_settings" && fields.size == 1 -> {
                    val field = fields.single()
                    val preserve = field in settingsFields || field == "webSearchSettingsVersion" || field == "webSearchEnabled" ||
                        field == "lastSeenChatAt" && selection.sessions.isNotEmpty() ||
                        field == "lastSeenMomentsAt" && selection.moments
                    if (preserve) null else defaultSettings[field]?.toString()
                }
                record.type == "chat_messages" && selection.sessions[record.string("sessionId")]?.images == false &&
                    fields.size == 1 && fields.single() in setOf("images", "generatedImageMetadata") -> "[]"
                record.type == "moment_posts" && !selection.momentsImages &&
                    fields.size == 1 && fields.single() in setOf("imagePath", "generatedImageMetadata") -> "null"
                record.type.startsWith("novelai_studio_") && fields.lastOrNull() == "importedCharacterCardId" &&
                    record.strings(fields.joinToString(".")).any { it !in selection.characterIds } -> "null"
                record.type.startsWith("novelai_studio_") && fields.lastOrNull() == "followDefaultNovelAiImageModel" &&
                    "novelAiImageModel" !in settingsFields -> "false"
                record.type.startsWith("novelai_studio_") && fields.lastOrNull() == "aiDesignModelId" &&
                    "defaultModelId" !in settingsFields && record.string(fields.joinToString(".")) == null ->
                    sourceSettings?.string("defaultModelId")?.let { backupJson.encodeToString(it) }
                else -> null
            }
        }
        val catalogFiles = if ("tagCatalog" in selection.globalOptions) listOf("danbooru_catalog/tag.sqlite", "danbooru_catalog/catalog.json")
            .mapNotNull { relative -> File(root, relative).takeIf { it.exists() }?.let { it to relative } } else emptyList()
        val packages = selectedRecords.filter { it.type == "save_slots" }.flatMap { record ->
            record.strings("packageRef.fileName").map { name ->
                require(name.isNotBlank() && !name.contains('/') && !name.contains('\\')) { "会话存档包路径无效" }
                BackupDataRegistry.target(root, "save_slot_packages/$name") to "save_slot_packages/$name"
            }
        }
        return BackupExportPlan(root, selection, selectedRecords, catalogFiles + packages, replacements,
            chats.associate { it.file.path to sessionFallbacks(it) })
    }

    companion object {
        internal fun read(root: File, checkActive: () -> Unit): BackupExportCatalog {
            val fields = setOf("id", "sessionId", "messageId", "characterCardId", "name", "title", "displayTitleOverride",
                "sourceId", "sourceType", "targetId", "modelId", "imageModelId", "defaultModelId", "defaultImageModelId",
                "defaultFormatCardId", "formatCardId", "worldBookIds", "boundWorldBookId", "extraWorldBookIds",
                "formatRepairModelId", "voiceTagModelId", "aiDesignModelId", "designModelId", "defaultEmbeddingId", "importedCharacterCardId", "fileName")
            val records = BackupDataRegistry.entityFiles(root).map { file ->
                checkActive()
                val relative = file.relativeTo(root).invariantSeparatorsPath
                val values = linkedMapOf<String, MutableSet<String>>()
                val resources = mutableListOf<BackupResourceReference>()
                try {
                    file.strictUtf8Reader().use { reader ->
                        BackupJsonStream(reader, discardWriter, capturedFields = fields,
                            observeField = { path, text -> values.getOrPut(path.joinToString(".")) { linkedSetOf() }.add(text) },
                            checkActive = checkActive
                        ) { path, ancestors -> resources.add(BackupResourceReference(path, ancestors)); path }.copy()
                    }
                    fun sizeWithout(replacements: (List<String>) -> String?): Long {
                        val counter = ByteCountingWriter()
                        file.strictUtf8Reader().use { reader ->
                            BackupJsonStream(reader, counter, replaceValue = replacements, checkActive = checkActive) { path, _ -> path }.copy()
                        }
                        return counter.bytes
                    }
                    val type = relative.removePrefix("entities/").substringBefore('/').removeSuffix(".json")
                    val withoutChatImages = if (type == "chat_messages") sizeWithout { path ->
                        "[]".takeIf { path.size == 1 && path.single() in setOf("images", "generatedImageMetadata") }
                    } else file.length()
                    val withoutStudioImages = if (type == "novelai_generation_history") sizeWithout { path ->
                        "\"chatbar-save-slot-omitted-image:metadata\"".takeIf { path == listOf("images", "path") }
                    } else file.length()
                    val withoutMomentImages = if (type == "moment_posts") sizeWithout { path ->
                        "null".takeIf { path.size == 1 && path.single() in setOf("imagePath", "generatedImageMetadata") }
                    } else file.length()
                    BackupExportRecord(file, relative, type, values, resources, withoutChatImages, withoutStudioImages, withoutMomentImages)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    throw IllegalStateException("无法读取导出清单：$relative；${error.message}", error)
                }
            }.toList()
            return BackupExportCatalog(root, records)
        }
        private val discardWriter = object : Writer() {
            override fun write(chars: CharArray, offset: Int, length: Int) {}
            override fun flush() {}
            override fun close() {}
        }
    }
}

internal class BackupExportPlan(
    val root: File, val selection: AppBackupSelection, val records: List<BackupExportRecord>,
    val additionalFiles: List<Pair<File, String>>,
    private val replacements: (BackupExportRecord, List<String>) -> String?,
    private val additions: Map<String, Map<String, String>> = emptyMap()
) {
    fun replacement(file: File, fields: List<String>): String? = replacements(recordsByPath.getValue(file.path), fields)
    private val recordsByPath = records.associateBy { it.file.path }
    fun extraFields(file: File): Map<String, String> = additions[file.path].orEmpty()
    fun resourcePath(file: File, path: String, fields: List<String>): String {
        if (recordsByPath.getValue(file.path).type != "novelai_generation_history" || selection.studioHistoryImages ||
            fields != listOf("images", "path")) return path
        if (path.startsWith(OMITTED_SAVE_SLOT_IMAGE_PREFIX)) return path
        val hash = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return OMITTED_SAVE_SLOT_IMAGE_PREFIX + hash
    }
    fun estimate(): BackupSizeEstimate {
        val paths = linkedMapOf<String, Long>()
        var unknown = 0
        for (record in records) {
            paths[record.file.canonicalPath] = when {
                record.type == "chat_messages" && selection.sessions[record.string("sessionId")]?.images == false -> record.withoutChatImagesBytes
                record.type == "novelai_generation_history" && !selection.studioHistoryImages -> record.withoutStudioImagesBytes
                record.type == "moment_posts" && !selection.momentsImages -> record.withoutMomentImagesBytes
                else -> record.file.length()
            }
            record.resources.filter { reference ->
                reference.fields.indices.none { index -> replacements(record, reference.fields.take(index + 1)) != null }
            }.forEach { reference ->
                val path = resourcePath(record.file, reference.path, reference.fields)
                if (path.isBlank() || path.startsWith("http:") || path.startsWith("https:") || path.startsWith("chatbar-save-slot-")) return@forEach
                if (path.startsWith("content:")) {
                    if (paths.putIfAbsent(path, 0L) == null) unknown++
                } else {
                    val file = when {
                        path.startsWith("chatbar-vibe-file:") -> File(root, "novelai-vibe-payloads/" + path.substringAfter(':'))
                        path.startsWith("file:") -> File(java.net.URI(path))
                        !File(path).isAbsolute && path.substringBefore('/') in BackupDataRegistry.roots -> BackupDataRegistry.target(root, path)
                        else -> File(path)
                    }
                    if (file.isAbsolute && file.isFile) paths[file.canonicalPath] = file.length()
                }
            }
        }
        additionalFiles.forEach { (file, _) -> if (file.isFile) paths[file.canonicalPath] = file.length() }
        val bytes = paths.values.fold(0L, Math::addExact)
        return BackupSizeEstimate(Math.addExact(bytes, 4096L + paths.size * 256L), paths.size, unknown)
    }
}

/** UTF-8 byte count without allocating whole JSON strings; handles surrogate pairs across writes. */
internal class ByteCountingWriter : Writer() {
    var bytes = 0L
        private set
    private var highSurrogate = false
    override fun write(chars: CharArray, offset: Int, length: Int) {
        for (index in offset until offset + length) {
            val char = chars[index]
            if (highSurrogate) {
                highSurrogate = false
                if (char.isLowSurrogate()) { bytes += 4; continue }
                bytes++
            }
            when {
                char.isHighSurrogate() -> highSurrogate = true
                char.code < 128 -> bytes++
                char.code < 2048 -> bytes += 2
                char.isLowSurrogate() -> bytes++
                else -> bytes += 3
            }
        }
    }
    override fun flush() { if (highSurrogate) { bytes++; highSurrogate = false } }
    override fun close() = flush()
}
