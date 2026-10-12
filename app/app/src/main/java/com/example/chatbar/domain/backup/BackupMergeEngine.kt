package com.example.chatbar.domain.backup

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.withCurrentWebSearchDefaults
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int

/** Builds a complete replacement tree privately; existing bootstrap rollback stays unchanged. */
internal class BackupMergeEngine(
    private val local: File,
    private val incoming: File,
    private val work: File,
    private val summary: AppBackupSummary,
    private val mode: AppBackupRestoreMode,
    private val checkActive: () -> Unit,
    private val progress: (Long, Int, String) -> Unit = { _, _, _ -> }
) {
    private val source = File(incoming, "files")
    private val target = File(work, "files")
    private val identity = BackupMergeIdentity(mode == AppBackupRestoreMode.ADD_COPIES)
    private val warnings = linkedSetOf<String>()
    private var hiddenWarnings = 0
    private val targets = mutableMapOf<String, BackupMergeRecord>()
    private val renamed = mutableMapOf<String, String>()
    private val removed = mutableSetOf<String>()
    private val retainedDependencies = mutableSetOf<String>()
    private val counts = linkedMapOf<String, BackupMergeCounts>()
    private val media = mutableMapOf<String, String>()
    private val packages = mutableMapOf<String, Pair<String, Long>>()
    private val batch = UUID.randomUUID().toString()
    private val digests = mutableMapOf<String, String>()
    private val fingerprints = mutableMapOf<Pair<String, Boolean>, String>()
    private var bytes = 0L
    private var files = 0
    private lateinit var current: List<BackupMergeRecord>
    private lateinit var imported: List<BackupMergeRecord>
    private lateinit var currentById: Map<Pair<String, String>, BackupMergeRecord>
    private lateinit var importedByType: Map<String, List<BackupMergeRecord>>
    private lateinit var memory: BackupMergeMemory

    fun build(): BackupRestorePreview {
        require(mode != AppBackupRestoreMode.REPLACE_ALL || summary.scope != null) { "旧版全量存档没有选择范围" }
        require(!target.exists()) { "恢复暂存目录必须为空" }
        current = BackupMergeCatalog.read(local, checkActive)
        imported = BackupMergeCatalog.read(source, checkActive)
        currentById = current.filter { it.id != null }.sortedBy { it.type != identity.space(it.type) }
            .groupBy { identity.space(it.type) to requireNotNull(it.id) }.mapValues { it.value.first() }
        val normalized = imported.filter { it.type in setOf("model_configs", "retrieval_model_config", "embedding_configs", "embedding_model_config") }
            .sortedBy { it.type != identity.space(it.type) }.groupBy { identity.space(it.type) to it.id }.values.map { it.first() }.toSet()
        imported = imported.filter { it.type !in setOf("model_configs", "retrieval_model_config", "embedding_configs", "embedding_model_config") || it in normalized }
        importedByType = imported.groupBy { it.type }
        verifyChoices()
        plan()
        memory = BackupMergeMemory(imported, identity, checkActive, File(work, "memory-merge")) { id ->
            val existing = currentById["memory_nodes" to id]
            if (existing != null && existing.file.canonicalPath !in removed && identity.value("memory_nodes", id) == id)
                identity.bind("memory_nodes", id, identity.fresh("memory_nodes", id))
        }
        memory.prepare()
        val localFiles = BackupDataRegistry.files(local).toList()
        val incomingFiles = BackupDataRegistry.files(source).toList()
        val needed = (localFiles + incomingFiles).fold(0L) { total, file -> Math.addExact(total, file.length()) }
        BackupArchive.ensureSpace(work, Math.multiplyExact(needed, 2L))
        target.mkdirs()
        localFiles.filterNot { it.canonicalPath in removed }.forEach { file ->
            copyFile(file, BackupDataRegistry.target(target, file.relativeTo(local).invariantSeparatorsPath))
        }
        incomingFiles.filterNot { it.relativeTo(source).invariantSeparatorsPath.startsWith("entities/") }.forEach { file ->
            val relative = file.relativeTo(source).invariantSeparatorsPath
            when {
                relative.startsWith("save_slot_packages/") -> Unit // Each SaveSlot gets its own original-byte media package.
                relative.startsWith("danbooru_catalog/") -> if (selected("tagCatalog")) copyFile(file, BackupDataRegistry.target(target, relative))
                relative.startsWith("novelai-vibe-payloads/") -> {
                    val destination = BackupDataRegistry.target(target, relative)
                    if (destination.exists()) require(hash(file) == hash(destination)) { "氛围载荷摘要冲突" }
                    else copyFile(file, destination)
                }
                else -> copyFile(file, BackupDataRegistry.target(target, mediaRelative(relative)))
            }
        }
        imported.filter { it.type == "save_slots" }.forEach { slot ->
            slot.text("packageRef.fileName")?.let { old ->
                val name = identity.value("save_slots", requireNotNull(slot.id)) + ".cbsave"
                val destination = BackupDataRegistry.target(target, "save_slot_packages/$name")
                rewritePackage(File(source, "save_slot_packages/$old"), destination, slot)
                packages[slot.relative] = name to destination.length()
            }
        }
        imported.forEach { record ->
            checkActive()
            if (record.type == "pending_deletions" || record.relative in retainedDependencies) return@forEach
            val destination = BackupDataRegistry.target(target, destination(record))
            if (record.type == "novelai_history_fold_preferences" && destination.exists()) return@forEach
            destination.parentFile!!.mkdirs()
            retainedMedia(record).use { retained ->
                record.file.strictUtf8Reader().use { reader ->
                    destination.bufferedWriter().use { writer -> transform(record, reader, writer, retained?.streams.orEmpty()).copy() }
                }
            }
            if (record.type == "preset_import_state") mergePresetState(destination)
            if (record.type == "model_configuration_migration") mergeVersionState(destination)
            files++
            progress(bytes, files, "正在合并本地记录")
        }
        mergeSettings(File(target, "entities/app_settings.json"))
        val preferences = backupJson.decodeFromString<BackupPreferences>(File(incoming, "preferences.json").readText())
        val retained = mapOf("novelAiCredentials" to "novelai_credentials", "fishAudioCredentials" to "fish_audio_credentials",
            "community" to "community_session", "imagePreferences" to "image_mask_preferences")
            .filterKeys { !selected(it) }.values.toSet()
        File(work, "preferences.json").writeText(backupJson.encodeToString(preferences.copy(retainedPreferences = retained)))
        File(incoming, "manifest.json").copyTo(File(work, "manifest.json"), overwrite = true)
        memory.close()
        File(work, "retained-fields").deleteRecursively()
        return BackupRestorePreview(mode, counts, warnings.toList() + if (hiddenWarnings > 0) listOf("另有 $hiddenWarnings 项匹配提示；新增、更新及副本数量已计入预览。") else emptyList())
    }

    private fun notice(text: String) {
        if (text in warnings) return
        if (warnings.size < 20) warnings.add(text.take(240)) else hiddenWarnings++
    }

    private fun selected(option: String) = summary.scope?.globalOptions?.contains(option) ?: true

    private fun verifyChoices() {
        summary.scope?.let { scope ->
            require(scope.characters == imported.count { it.type == "character_cards" } &&
                scope.sessions == imported.count { it.type == "chat_sessions" }) { "存档选择范围与角色/会话数据不一致" }
            require(scope.moments || imported.none { it.type in setOf("moment_posts", "moment_tasks") }) { "存档含未选朋友圈数据" }
            require(scope.studioHistory || imported.none { it.type == "novelai_generation_history" }) { "存档含未选工作室历史" }
            require(scope.studioSettings || imported.none { isStudioSettings(it.type) }) { "存档含未选工作室设置" }
        }
        val choices = summary.scope?.sessionChoices.orEmpty()
        val default = summary.scope?.sessionDefaultChoice
        if (choices.isEmpty() && default == null) return
        val sessionIds = imported.filter { it.type == "chat_sessions" }.map { requireNotNull(it.id) }.toSet()
        require(if (default == null) choices.keys == sessionIds else sessionIds.containsAll(choices.keys)) { "存档会话选择范围与数据不一致" }
        val all = sessionIds.map { choices[it] ?: requireNotNull(default) }
        require(all.count { it.images } == summary.scope?.sessionsWithImages && all.count { it.voice } == summary.scope?.sessionsWithVoice &&
            all.count { it.archives } == summary.scope?.sessionsWithArchives) { "存档会话媒体范围无效" }
    }

    private fun plan() {
        val named = listOf("character_cards", "format_cards", "world_books", "model_configs", "retrieval_model_config",
            "embedding_configs", "embedding_model_config", "chat_sessions")
        named.forEach { type -> matchType(type, byName = true) }
        bindEmbeddedIdentities()
        listOf("moment_posts", "novelai_generation_history", "moment_tasks").forEach { matchType(it, byName = false) }
        val importedPaths = imported.associateBy { it.relative }
        val replacedSessions = targets.filterKeys { key -> importedPaths[key]?.type == "chat_sessions" }
            .values.mapNotNull { it.id }.toSet()
        val currentMessages = current.filter { it.type == "chat_messages" }.associateBy { it.id }
        fun owner(record: BackupMergeRecord): String? = record.sessionId?.takeIf(String::isNotBlank)
            ?: if (record.type == "voice_anchor_states") currentMessages[record.text("messageId")]?.sessionId else null
        val sourceSessions = imported.filter { it.type == "chat_sessions" }.associateBy { identity.value("chat_sessions", requireNotNull(it.id)) }
        val incomingFamilies = imported.mapNotNull { record -> record.sessionId?.let { record.type to it } }.toSet()
        fun includeFamily(type: String, session: String): Boolean {
            val original = sourceSessions[session]?.id ?: return false
            val choice = summary.scope?.sessionChoices?.get(original) ?: summary.scope?.sessionDefaultChoice
            if (type == "save_slots") return choice?.archives ?: ((type to original) in incomingFamilies)
            if (type in setOf("generated_voice_messages", "voice_anchor_states")) return choice?.voice
                ?: ("generated_voice_messages" to original in incomingFamilies || "voice_anchor_states" to original in incomingFamilies)
            return true
        }
        val incomingCards = importedByType["character_cards"].orEmpty().map { identity.value("character_cards", requireNotNull(it.id)) }.toSet()
        val replacedDocuments = current.filter { it.type == "character_cards" && it.id in incomingCards }
            .flatMap { it.values["customDocuments.id"].orEmpty() }.toSet()
        val retainedMessages = mutableSetOf<String>()
        if (mode == AppBackupRestoreMode.REPLACE_ALL) {
            val incomingMessages = importedByType["chat_messages"].orEmpty().groupBy { it.sessionId }
                .mapValues { (_, records) -> records.mapNotNull { it.id }.toSet() }
            val retainedVoiceMessages = current.filter { it.type in setOf("generated_voice_messages", "voice_anchor_states") &&
                owner(it) in replacedSessions && !includeFamily(it.type, requireNotNull(owner(it))) }
                .mapNotNull { it.text("messageId") }.toSet()
            current.filter { it.type == "chat_messages" && it.sessionId in replacedSessions }.forEach { message ->
                val original = sourceSessions[message.sessionId]?.id ?: return@forEach
                val absent = message.id !in incomingMessages[original].orEmpty()
                val images = choice(original)?.images ?: (summary.scope!!.sessionsWithImages == summary.scope.sessions)
                if (absent && (message.id in retainedVoiceMessages || !images && message.values["images"].orEmpty().isNotEmpty()))
                    retainedMessages.add(message.file.canonicalPath)
            }
        }
        current.forEach { record ->
            val session = owner(record)
            if (session in replacedSessions && record.type !in setOf("moment_posts", "moment_tasks") &&
                record.file.canonicalPath !in retainedMessages && includeFamily(record.type, requireNotNull(session)))
                removed.add(record.file.canonicalPath)
            if (record.type == "vector_chunks" && record.text("sourceType") != "CHAT_MEMORY" &&
                (record.text("sourceId") in incomingCards || record.text("sourceId") in replacedDocuments))
                removed.add(record.file.canonicalPath)
            if ((summary.scope?.studioSettings ?: true) && isStudioSettings(record.type)) removed.add(record.file.canonicalPath)
        }
        if (mode == AppBackupRestoreMode.REPLACE_ALL) planSelectedCollections()
        val primary = imported.filter { it.id != null && !it.singleton && it.relative !in targets && it.type !in named &&
            it.type !in setOf("moment_posts", "novelai_generation_history", "moment_tasks") }
        primary.forEach { record ->
            val old = requireNotNull(record.id)
            val existing = currentById[identity.space(record.type) to old]
            val targetId = if (mode == AppBackupRestoreMode.ADD_COPIES || existing != null && existing.file.canonicalPath !in removed)
                identity.fresh(record.type, old) else old
            identity.bind(record.type, old, targetId)
        }
        importedByType["edit_drafts"].orEmpty().forEach { record ->
            val old = requireNotNull(record.id)
            val type = record.text("entityType").orEmpty()
            val targetId = record.text("targetId")?.let { identity.string(record, it, listOf("targetId")) }
            val target = if (targetId == null) identity.value("edit_drafts", old) else "${type.lowercase(java.util.Locale.ROOT)}_$targetId"
            identity.bind("edit_drafts", old, target)
        }
        // A copied/rebound archive or journal must never reference or delete a different live session's rows.
        val scoped = setOf("chat_messages", "generated_voice_messages", "memory_nodes", "memory_tier_revisions",
            "memory_compression_transactions", "memory_commits", "memory_commit_journals")
        val incomingMessages = importedByType["chat_messages"].orEmpty().associateBy { it.id }
        imported.forEach recordLoop@ { record ->
            val session = record.sessionId?.takeIf(String::isNotBlank) ?: incomingMessages[record.text("messageId")]?.sessionId
            if (session == null) return@recordLoop
            val owner = identity.value("chat_sessions", session)
            record.values.forEach fieldLoop@ { (field, values) ->
                val space = identity.fieldSpace(record, field.split('.')) ?: return@fieldLoop
                if (space !in scoped) return@fieldLoop
                values.forEach { value ->
                    val existing = currentById[space to value]
                    if (existing != null && existing.file.canonicalPath !in removed && existing.sessionId != owner && identity.value(space, value) == value)
                        identity.bind(space, value, identity.fresh(space, value))
                }
            }
        }
        // Avoid a restored owner being deleted later by an old interrupted cleanup task.
        val cards = imported.filter { it.type == "character_cards" }.map { identity.value("character_cards", requireNotNull(it.id)) }.toSet()
        val sessions = imported.filter { it.type == "chat_sessions" }.map { identity.value("chat_sessions", requireNotNull(it.id)) }.toSet()
        current.filter { it.type == "pending_deletions" && (it.text("ownerId") in cards || it.text("ownerId") in sessions) }
            .forEach { removed.add(it.file.canonicalPath) }
    }

    private fun matchType(type: String, byName: Boolean) {
        val incomingRecords = importedByType[type].orEmpty()
        val candidates = current.filter { identity.space(it.type) == identity.space(type) }
        val candidatesById = candidates.groupBy { it.id }
        val claimed = targets.values.filter { identity.space(it.type) == identity.space(type) }.mapNotNull { it.id }.toMutableSet()
        fun group(record: BackupMergeRecord, original: Boolean): String = if (type == "chat_sessions") {
            val owner = record.text("characterCardId").orEmpty()
            if (original) identity.value("character_cards", owner) else owner
        } else ""
        val usedNames = candidates.groupBy { group(it, false) }.mapValues { (_, records) ->
            records.mapNotNull { it.name }.map(BackupMergeNames::normalize).toMutableSet()
        }.toMutableMap()
        val sourceNames = incomingRecords.groupBy { group(it, true) to BackupMergeNames.normalize(it.name.orEmpty()) }
        val candidatesByName = candidates.groupBy { group(it, false) to BackupMergeNames.normalize(it.name.orEmpty()) }
        var localMetadata: Map<String, List<BackupMergeRecord>>? = null
        fun identical(record: BackupMergeRecord): List<BackupMergeRecord> {
            if (localMetadata == null) localMetadata = candidates.groupBy { fingerprint(it, local, includeMedia = false) }
            val plausible = localMetadata.orEmpty()[fingerprint(record, source, includeMedia = false)].orEmpty()
            if (plausible.isEmpty()) return emptyList()
            val complete = fingerprint(record, source, includeMedia = true)
            return plausible.filter { fingerprint(it, local, includeMedia = true) == complete }
        }
        incomingRecords.forEach { record ->
            checkActive()
            val old = requireNotNull(record.id) { "导入记录缺少 ID：${record.relative}" }
            val scope = group(record, true)
            var existing: BackupMergeRecord? = null
            var ambiguous = false
            if (mode != AppBackupRestoreMode.ADD_COPIES) {
                existing = candidatesById[old].orEmpty().firstOrNull { group(it, false) == scope }
                if (mode == AppBackupRestoreMode.UPDATE_EXISTING && existing == null && byName && !record.name.isNullOrBlank()) {
                    val name = BackupMergeNames.normalize(requireNotNull(record.name))
                    val same = candidatesByName[scope to name].orEmpty()
                    ambiguous = same.size > 1 || same.isNotEmpty() && sourceNames[scope to name].orEmpty().size > 1
                    if (!ambiguous) existing = same.singleOrNull()
                }
                if (mode == AppBackupRestoreMode.UPDATE_EXISTING && existing == null && !byName) {
                    val same = identical(record)
                    ambiguous = same.size > 1
                    if (!ambiguous) existing = same.singleOrNull()
                }
                if (existing?.id in claimed) { existing = null; ambiguous = true }
            }
            if (existing != null) {
                if (existing.id != old && byName) notice("按唯一名称更新${typeLabel(type)}：${record.name}")
                identity.bind(type, old, requireNotNull(existing.id))
                targets[record.relative] = existing
                val dependencyOption = when (identity.space(type)) {
                    "model_configs" -> "models"; "embedding_configs" -> "embedding"
                    "format_cards", "world_books" -> "resources"; else -> null
                }
                if (mode == AppBackupRestoreMode.REPLACE_ALL && dependencyOption != null && !selected(dependencyOption)) {
                    retainedDependencies.add(record.relative)
                    notice("保留未勾选的本机${typeLabel(identity.space(type))}：${existing.name ?: old}")
                    return@forEach
                }
                removed.add(existing.file.canonicalPath)
                claimed.add(requireNotNull(existing.id))
                counts[type] = counts.getOrDefault(type, BackupMergeCounts()).let { it.copy(updated = it.updated + 1) }
            } else {
                val collision = old in candidatesById
                val id = if (mode == AppBackupRestoreMode.ADD_COPIES || collision || ambiguous) identity.fresh(type, old) else old
                identity.bind(type, old, id)
                val names = usedNames.getOrPut(scope) { mutableSetOf() }
                val duplicate = record.name?.let { BackupMergeNames.normalize(it) in names } == true
                var duplicateContent = false
                if (mode == AppBackupRestoreMode.ADD_COPIES && !byName && !collision && candidates.isNotEmpty()) {
                    duplicateContent = identical(record).isNotEmpty()
                }
                if (mode != AppBackupRestoreMode.REPLACE_ALL && (duplicate || ambiguous || mode == AppBackupRestoreMode.ADD_COPIES && collision))
                    record.name?.let { renamed[record.relative] = BackupMergeNames.copyName(it, names) }
                else record.name?.let { names.add(BackupMergeNames.normalize(it)) }
                val copied = mode == AppBackupRestoreMode.ADD_COPIES && (collision || duplicate || duplicateContent) || ambiguous
                counts[type] = counts.getOrDefault(type, BackupMergeCounts()).let {
                    if (copied) it.copy(copies = it.copies + 1) else it.copy(added = it.added + 1)
                }
                if (ambiguous) notice("${typeLabel(type)}存在重名或重复指纹，已保留原记录并创建副本：${record.name ?: old}")
            }
        }
    }

    /** Selected collections may be empty; absence alone never selects a collection. */
    private fun planSelectedCollections() {
        val scope = requireNotNull(summary.scope)
        val types = buildSet {
            if (scope.moments) addAll(listOf("moment_posts", "moment_tasks"))
            if (scope.studioHistory) addAll(listOf("novelai_generation_history", "novelai_history_fold_preferences"))
            if (selected("models")) addAll(listOf("model_configs", "retrieval_model_config"))
            if (selected("embedding")) addAll(listOf("embedding_configs", "embedding_model_config"))
            if (selected("resources")) addAll(listOf("format_cards", "world_books"))
            if (selected("editorDrafts")) add("edit_drafts")
            if (selected("player")) add("player_setting")
        }
        val dependencyTypes = setOf("model_configs", "embedding_configs", "format_cards", "world_books")
        val required = mutableSetOf<Pair<String, String>>()
        val settingFields = BackupSettingOptions.fields(scope.globalOptions)
        current.filter { it.file.canonicalPath !in removed && it.type !in types }.forEach { record ->
            record.values.forEach { (field, values) ->
                if (record.type != "app_settings" || field !in settingFields) {
                    val type = identity.fieldSpace(record, field.split('.'))
                    if (type in dependencyTypes) values.forEach { required.add(requireNotNull(type) to it) }
                }
            }
        }
        val matched = targets.values.map { identity.space(it.type) to it.id }.toSet()
        val counted = mutableSetOf<Pair<String, String?>>()
        val incomingIds = imported.groupBy { it.type }.mapValues { (_, records) -> records.mapNotNull { it.id }.toSet() }
        current.filter { it.type in types }.forEach { record ->
            val key = identity.space(record.type) to record.id
            val omittedImages = when (record.type) {
                "moment_posts" -> !scope.momentsImages && record.values["imagePath"].orEmpty().any { it.isNotBlank() }
                "novelai_generation_history" -> !scope.studioHistoryImages && record.values["images.path"].orEmpty()
                    .any { it.isNotBlank() && !it.startsWith("chatbar-save-slot-omitted-image:") }
                else -> false
            }
            if (omittedImages && record.id !in incomingIds[record.type].orEmpty()) {
                notice("保留未勾选原图及其${typeLabel(record.type)}记录：${record.name ?: record.id}")
                return@forEach
            }
            if (key !in required) {
                removed.add(record.file.canonicalPath)
                if (key !in matched && counted.add(key)) counts[key.first] = counts.getOrDefault(key.first, BackupMergeCounts())
                    .let { it.copy(removed = it.removed + 1) }
            } else notice("保留未选内容仍引用的${typeLabel(key.first)}：${record.name ?: record.id}")
        }
        if (selected("tagCatalog")) listOf("danbooru_catalog/tag.sqlite", "danbooru_catalog/catalog.json").forEach {
            removed.add(File(local, it).canonicalPath)
        }
    }

    private fun choice(session: String) = summary.scope?.sessionChoices?.get(session) ?: summary.scope?.sessionDefaultChoice

    private fun retainedMedia(record: BackupMergeRecord): BackupRetainedMedia? {
        if (mode != AppBackupRestoreMode.REPLACE_ALL) return null
        val scope = requireNotNull(summary.scope)
        val old = currentById[identity.space(record.type) to record.id] ?: return null
        val fields = when (record.type) {
            "chat_messages" -> {
                if (old.sessionId != record.sessionId) return null
                val images = record.sessionId?.let(::choice)?.images
                    ?: (scope.sessionsWithImages == scope.sessions || scope.sessionsWithImages > 0 && record.values["images"].orEmpty().isNotEmpty())
                if (images) return null
                setOf("images", "generatedImageMetadata")
            }
            "moment_posts" -> if (scope.momentsImages) return null else setOf("imagePath", "generatedImageMetadata")
            "novelai_generation_history" -> {
                if (scope.studioHistoryImages || old.values["images.path"].orEmpty()
                    .none { it.isNotBlank() && !it.startsWith("chatbar-save-slot-omitted-image:") }) return null
                setOf("images")
            }
            else -> return null
        }
        return BackupRetainedMedia(old.file, File(work, "retained-fields/${UUID.randomUUID()}"), fields, checkActive)
    }

    private fun bindEmbeddedIdentities() {
        val groups = mapOf("characters" to "characters", "customDocuments" to "documents", "entries" to "world_entries",
            "characterBook.entries" to "world_entries", "characterBook" to "world_books")
        val reserved = groups.values.distinct().associateWith { mutableSetOf<String>() }
        current.filter { it.type in setOf("character_cards", "world_books") }.forEach { record ->
            groups.forEach { (path, space) -> reserved.getValue(space).addAll(record.values["$path.id"].orEmpty()) }
        }
        imported.filter { it.type in setOf("character_cards", "world_books") }.forEach { record ->
            val existing = targets[record.relative]
            groups.forEach { (path, space) ->
                val ids = record.values["$path.id"].orEmpty()
                val oldIds = existing?.values?.get("$path.id").orEmpty()
                val label = if (path == "customDocuments") "fileName" else "name"
                val names = record.values["$path.$label"].orEmpty()
                val oldNames = existing?.values?.get("$path.$label").orEmpty()
                val byName = if (oldIds.size == oldNames.size) oldIds.zip(oldNames).groupBy { BackupMergeNames.normalize(it.second) } else emptyMap()
                val counts = if (ids.size == names.size) names.groupingBy(BackupMergeNames::normalize).eachCount() else emptyMap()
                ids.forEachIndexed { index, id ->
                    val same = if (index < names.size && counts[BackupMergeNames.normalize(names[index])] == 1)
                        byName[BackupMergeNames.normalize(names[index])].orEmpty().singleOrNull()?.first else null
                    val target = when {
                        mode == AppBackupRestoreMode.ADD_COPIES -> identity.fresh(space, id)
                        id in oldIds -> id
                        same != null -> same
                        id in reserved.getValue(space) -> identity.fresh(space, id)
                        else -> id
                    }
                    identity.bind(space, id, target)
                }
            }
        }
    }

    private fun destination(record: BackupMergeRecord): String {
        if (record.type == "chat_messages") return "entities/chat_messages/${identity.value("chat_sessions", requireNotNull(record.sessionId))}_${identity.value(record.type, requireNotNull(record.id))}.json"
        if (record.type == "voice_anchor_states") {
            val message = identity.value("chat_messages", requireNotNull(record.text("messageId")))
            val version = record.text("messageVersionId")
            val id = if (version == null) message else UUID.nameUUIDFromBytes("$message\u0000$version".toByteArray(Charsets.UTF_8)).toString()
            return "entities/${record.type}/$id.json"
        }
        if (record.singleton && record.type !in setOf("retrieval_model_config", "embedding_model_config")) return record.relative
        val id = record.id?.let { identity.value(record.type, it) } ?: record.sessionId?.let { identity.value("chat_sessions", it) }
            ?: record.file.nameWithoutExtension
        return "entities/${identity.space(record.type)}/$id.json"
    }

    private fun mediaRelative(relative: String): String = media.getOrPut(relative) {
        val prefix = when {
            relative.startsWith("images/generated/") -> "images/generated"
            relative.startsWith("audio/generated/") -> "audio/generated"
            else -> relative.substringBefore('/')
        }
        "$prefix/migrated_backup/$batch/" + relative.removePrefix("$prefix/")
    }

    private fun resource(path: String): String {
        if (path.isBlank() || path.startsWith("http:") || path.startsWith("https:") || path.startsWith("chatbar-save-slot-") || path.startsWith("chatbar-vibe-file:")) return path
        val file = if (path.startsWith("file:")) File(java.net.URI(path)) else File(path)
        val relative = when {
            !file.isAbsolute && path.substringBefore('/') in BackupDataRegistry.roots -> path
            file.canonicalPath.startsWith(local.canonicalPath + File.separator) -> file.canonicalFile.relativeTo(local.canonicalFile).invariantSeparatorsPath
            else -> error("存档包含未迁入私有目录的资源")
        }
        require(relative.substringBefore('/') !in setOf("entities", "danbooru_catalog", "save_slot_packages")) { "资源字段指向非媒体数据" }
        return BackupDataRegistry.target(local, mediaRelative(relative)).path
    }

    private fun transform(record: BackupMergeRecord, reader: java.io.Reader, writer: Writer,
        retained: Map<String, (Writer) -> Unit> = emptyMap()): BackupJsonStream {
        val added = renamed[record.relative]?.let { mapOf((if (record.type == "chat_sessions") "displayTitleOverride" else
            if (record.type in setOf("model_configs", "retrieval_model_config", "embedding_configs", "embedding_model_config")) "displayName" else "name") to backupJson.encodeToString(it)) }.orEmpty()
        val proof = memory.rewriter(record)
        return BackupJsonStream(reader, writer, checkActive = checkActive, mappedFields = BackupMergeIdentity.fields,
            observeObject = proof::objectEvent, capturedFields = proof.observedFields, observeField = proof::fieldEvent,
            mapString = { value, fields -> proof.string(value, fields) ?: identity.string(record, value, fields) },
            mapKey = identity::key,
            replaceValueStream = { fields, output -> retained[fields.singleOrNull()]?.let { it(output); true } ?: false },
            replaceValue = { fields -> added[fields.singleOrNull()] ?: proof.replacement(fields) ?: when {
                fields == listOf("packageRef", "fileName") -> (packages[record.relative]?.first ?: record.text("__packageName"))?.let { backupJson.encodeToString(it) }
                fields == listOf("packageRef", "byteLength") -> packages[record.relative]?.second?.toString() ?: "0".takeIf { record.text("__packageName") != null }
                record.type == "chat_message_indexes" && fields == listOf("fileFingerprint") -> "0"
                else -> null
            } }, extraRootFields = added, extraRootStreams = retained
        ) { path, _ -> resource(path) }
    }

    private fun mergeSettings(destination: File) {
        val options = summary.scope?.globalOptions ?: return
        val settingsFields = BackupSettingOptions.fields(options)
        val hasIncoming = imported.any { it.type == "app_settings" }
        if (!hasIncoming && settingsFields.isEmpty() && !summary.scope.moments && imported.none { it.type == "chat_sessions" }) return
        val old = current.firstOrNull { it.type == "app_settings" }?.file
        val defaults = backupJson.encodeToJsonElement(AppSettings()).jsonObject
        // Migrate each side independently; a source compatibility marker must not reset unselected local fields.
        val incomingSettings = normalizedSearchSettings(if (hasIncoming) backupJson.parseToJsonElement(destination.readText()).jsonObject else defaults)
        val currentSettings = normalizedSearchSettings(old?.let { backupJson.parseToJsonElement(it.readText()).jsonObject } ?: defaults)
        val fields = settingsFields.toMutableSet()
        if (imported.any { it.type == "chat_sessions" }) fields.add("lastSeenChatAt")
        if (summary.scope.moments) fields.add("lastSeenMomentsAt")
        val values = fields.associateWith { incomingSettings[it] ?: defaults.getValue(it) }
        val deviceConfirmation = if (summary.scope.moments || settingsFields.any { it.startsWith("moments") })
            mapOf("momentsAutoStartConfirmed" to JsonPrimitive(false)) else emptyMap()
        val merged = currentSettings + values + deviceConfirmation
        destination.parentFile!!.mkdirs()
        destination.writeText(JsonObject(merged).toString())
    }

    private fun normalizedSearchSettings(settings: JsonObject): JsonObject {
        val original = backupJson.decodeFromString<AppSettings>(settings.toString())
        val normalized = original.withCurrentWebSearchDefaults()
        if (normalized === original) return settings
        val migratedFields = setOf("webSearchSettingsVersion", "webSearchEnabled", "characterAutoFillWebSearchEnabled",
            "characterRewriteWebSearchEnabled", "characterAutoFillResearchSourceMode", "characterRewriteResearchSourceMode",
            "webSearchMaxResultsPerQuery")
        return JsonObject(settings + backupJson.encodeToJsonElement(normalized).jsonObject.filterKeys { it in migratedFields })
    }

    private fun mergePresetState(destination: File) {
        val old = current.firstOrNull { it.type == "preset_import_state" }?.file ?: return
        val before = backupJson.parseToJsonElement(old.readText()).jsonObject["seenVersions"]?.jsonObject.orEmpty()
        val after = backupJson.parseToJsonElement(destination.readText()).jsonObject["seenVersions"]?.jsonObject.orEmpty()
        val seen = (before.keys + after.keys).associateWith { key ->
            JsonPrimitive(maxOf(before[key]?.jsonPrimitive?.int ?: 0, after[key]?.jsonPrimitive?.int ?: 0))
        }
        destination.writeText(JsonObject(mapOf("seenVersions" to JsonObject(seen))).toString())
    }

    private fun mergeVersionState(destination: File) {
        val old = current.firstOrNull { it.type == "model_configuration_migration" }?.file ?: return
        val before = backupJson.parseToJsonElement(old.readText()).jsonObject["version"]?.jsonPrimitive?.int ?: 0
        val after = backupJson.parseToJsonElement(destination.readText()).jsonObject["version"]?.jsonPrimitive?.int ?: 0
        destination.writeText(JsonObject(mapOf("version" to JsonPrimitive(maxOf(before, after)))).toString())
    }

    private fun rewritePackage(source: File, destination: File, slot: BackupMergeRecord) {
        destination.parentFile!!.mkdirs()
        ZipFile(source).use { zip ->
            ZipOutputStream(destination.outputStream().buffered()).use { out ->
                val seen = mutableSetOf<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    checkActive()
                    val entry = entries.nextElement()
                    require(!entry.isDirectory && seen.add(entry.name) && !entry.name.contains('\\') && entry.name.split('/').none { it in setOf("", ".", "..") }) { "会话存档包条目无效" }
                    out.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).use { input ->
                        val type = when (entry.name) {
                            "manifest.json" -> "save_slots"; "messages.jsonl" -> "chat_messages"; "rag.jsonl" -> "vector_chunks"; "voices.jsonl" -> "generated_voice_messages"; else -> null
                        }
                        if (type == null) transfer(input, out) else {
                            val record = slot.copy(type = type, file = source,
                                relative = slot.relative + if (entry.name == "manifest.json") "!manifest.json" else "!${entry.name}",
                                values = slot.values + ("sourceType" to listOf("CHAT_MEMORY")) + ("__packageName" to listOf(destination.name)))
                            val reader = input.strictUtf8Reader()
                            val writer = OutputStreamWriter(out, Charsets.UTF_8)
                            transform(record, reader, writer).copySequence()
                        }
                    }
                    out.closeEntry()
                }
            }
        }
        files++
    }

    private fun fingerprint(record: BackupMergeRecord, root: File, includeMedia: Boolean): String {
        fingerprints[record.file.canonicalPath to includeMedia]?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256")
        val writer = CompactJsonWriter(OutputStreamWriter(DigestOutputStream(NullOutputStream, digest), Charsets.UTF_8))
        record.file.strictUtf8Reader().use { reader ->
            BackupJsonStream(reader, writer, checkActive = checkActive, mappedFields = BackupMergeIdentity.fields,
                mapString = { text, fields -> if (root == source && mode == AppBackupRestoreMode.UPDATE_EXISTING) identity.string(record, text, fields) else text },
                replaceValue = { fields -> "null".takeIf { fields.size == 1 && fields.single() in setOf("id", "updatedAt") } }
            ) { path, _ ->
                if (path.isBlank() || path.startsWith("http:") || path.startsWith("https:") || path.startsWith("chatbar-save-slot-")) path
                else if (!includeMedia) "local-media" else {
                    val file = if (path.startsWith("chatbar-vibe-file:")) File(root, "novelai-vibe-payloads/" + path.substringAfter(':'))
                        else if (path.startsWith("file:")) File(java.net.URI(path)) else File(path)
                    val relative = if (!file.isAbsolute) path else if (file.canonicalPath.startsWith(local.canonicalPath + File.separator))
                        file.canonicalFile.relativeTo(local.canonicalFile).invariantSeparatorsPath else null
                    val actual = relative?.let { File(root, it) } ?: file
                    if (actual.isFile) "sha256:" + hash(actual) else "missing:" + (relative ?: path)
                }
            }.copy()
        }
        writer.close()
        return hex(digest.digest()).also { fingerprints[record.file.canonicalPath to includeMedia] = it }
    }

    private fun hash(file: File): String = digests.getOrPut(file.canonicalPath) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { checkActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        hex(digest.digest())
    }
    private fun copyFile(source: File, target: File) {
        checkActive()
        target.parentFile!!.mkdirs()
        source.inputStream().buffered().use { input -> target.outputStream().buffered().use { transfer(input, it) } }
        files++
        progress(bytes, files, "正在准备增量数据")
    }
    private fun transfer(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            bytes = Math.addExact(bytes, count.toLong())
            progress(bytes, files, "正在准备增量资源")
        }
    }
    companion object {
        private val NullOutputStream = object : OutputStream() { override fun write(value: Int) {} }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun isStudioSettings(type: String) = type.startsWith("novelai_studio_") || type.startsWith("novelai_design_")
        fun typeLabel(type: String) = when (type) {
            "character_cards" -> "角色卡"; "chat_sessions" -> "会话"; "moment_posts" -> "朋友圈"; "novelai_generation_history" -> "生图历史"
            "model_configs" -> "模型"; "embedding_configs" -> "向量模型"; "format_cards" -> "格式卡"; "world_books" -> "世界书"; else -> "关联数据"
        }
    }
}

/** Formatting whitespace is excluded; quoted text is always retained verbatim. */
private class CompactJsonWriter(private val destination: Writer) : Writer() {
    private var quoted = false
    private var escaped = false
    override fun write(chars: CharArray, offset: Int, length: Int) {
        for (index in offset until offset + length) {
            val char = chars[index]
            if (quoted || !char.isWhitespace()) destination.write(char.code)
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else if (char == '"') quoted = true
        }
    }
    override fun flush() = destination.flush()
    override fun close() = destination.close()
}
