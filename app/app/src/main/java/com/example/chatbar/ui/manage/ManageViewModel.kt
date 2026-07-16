package com.example.chatbar.ui.manage

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.EditorDraft
import com.example.chatbar.data.local.entity.RagIndexStatus
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.ModelConfigurationMode
import com.example.chatbar.data.local.entity.PRESET_MODEL_ID_PREFIX
import com.example.chatbar.data.local.entity.ParamValue
import com.example.chatbar.data.local.entity.PlayerSetting
import com.example.chatbar.data.local.entity.ThemeMode
import com.example.chatbar.data.local.entity.normalized
import com.example.chatbar.domain.card.CharacterCardPngExportOptions
import com.example.chatbar.domain.community.CommunityItem
import com.example.chatbar.domain.community.CommunityItemType
import com.example.chatbar.domain.moment.MomentAlarmScheduler
import com.example.chatbar.domain.moment.MomentBackgroundReliability
import com.example.chatbar.domain.moment.MomentDebugGenerationResult
import com.example.chatbar.domain.moment.MomentPolicy
import com.example.chatbar.domain.moment.MomentReliabilityState
import com.example.chatbar.data.local.entity.MomentTaskStatus
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.rag.EmbeddingService
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@Serializable
private data class ExportedModelTemplate(
    val schemaVersion: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val displayName: String,
    val baseUrl: String,
    val modelName: String,
    val isMultimodal: Boolean,
    val templateType: com.example.chatbar.data.local.entity.ModelTemplate,
    val customParams: Map<String, ParamValue>,
    val reasoningEffort: String? = null,
    val enableThinking: Boolean? = null,
    val maxOutputTokens: Int? = null
)

private val characterImportProbeJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

data class MomentDebugUiState(
    val isRunning: Boolean = false,
    val result: MomentDebugGenerationResult? = null
)

data class MomentSchedulePreviewItem(
    val taskId: String,
    val cardName: String,
    val sessionTitle: String,
    val scheduledAt: Long,
    val statusLabel: String
)

data class MomentSchedulePreviewUiState(
    val isLoading: Boolean = false,
    val generatedAt: Long = 0L,
    val items: List<MomentSchedulePreviewItem> = emptyList(),
    val message: String? = null
)

/**
 * 管理界面 ViewModel - 提供角色、格式卡、模型及系统参数的管理功能
 */
class ManageViewModel : ViewModel() {

    private val characterRepository = ChatBarApp.instance.characterRepository
    private val formatCardRepository = ChatBarApp.instance.formatCardRepository
    private val worldBookRepository = ChatBarApp.instance.worldBookRepository
    private val editorDraftRepository = ChatBarApp.instance.editorDraftRepository
    private val editorDraftAssetService = ChatBarApp.instance.editorDraftAssetService
    private val modelRepository = ChatBarApp.instance.modelRepository
    private val settingsRepository = ChatBarApp.instance.settingsRepository
    private val chatRepository = ChatBarApp.instance.chatRepository
    private val momentRepository = ChatBarApp.instance.momentRepository
    private val momentGenerationService = ChatBarApp.instance.momentGenerationService
    private val characterTransfers = ChatBarApp.instance.characterCardTransferService
    private val formatTransfers = ChatBarApp.instance.formatCardTransferService
    private val worldBookTransfers = ChatBarApp.instance.worldBookTransferService
    private val communityService = ChatBarApp.instance.communityService
    private val presetCatalog = ChatBarApp.instance.presetCatalogService
    private val presetModelCatalog = ChatBarApp.instance.presetModelCatalogService
    private val modelResolver = ChatBarApp.instance.effectiveModelResolver
    private val novelAiCredentials = ChatBarApp.instance.novelAiCredentialStore
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    // 数据流绑定到 Compose State
    val characterCards: StateFlow<List<CharacterCard>> = characterRepository.characters
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val formatCards: StateFlow<List<FormatCard>> = formatCardRepository.formatCards
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val worldBooks: StateFlow<List<WorldBook>> = worldBookRepository.worldBooks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _editorDrafts = MutableStateFlow<List<EditorDraft>>(emptyList())
    val editorDrafts: StateFlow<List<EditorDraft>> = _editorDrafts

    val modelConfigs: StateFlow<List<ModelConfig>> = modelRepository.models
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val embeddingConfigs: StateFlow<List<EmbeddingConfig>> = modelRepository.embeddings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val retrievalModelConfig: StateFlow<ModelConfig?> = modelRepository.retrievalModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val embeddingModelConfig: StateFlow<EmbeddingConfig?> = modelRepository.embeddingModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _effectiveChatModels = MutableStateFlow<List<ModelConfig>>(emptyList())
    val effectiveChatModels: StateFlow<List<ModelConfig>> = _effectiveChatModels
    private val _modelConfigurationErrors = MutableStateFlow<List<String>>(emptyList())
    val modelConfigurationErrors: StateFlow<List<String>> = _modelConfigurationErrors
    private val _modelConfigurationWarnings = MutableStateFlow<List<String>>(emptyList())
    val modelConfigurationWarnings: StateFlow<List<String>> = _modelConfigurationWarnings
    private val _isModelConfigurationUsable = MutableStateFlow(false)
    val isModelConfigurationUsable: StateFlow<Boolean> = _isModelConfigurationUsable
    private val _apiTestStatus = MutableStateFlow<String?>(null)
    val apiTestStatus: StateFlow<String?> = _apiTestStatus
    private val _importProgress = MutableStateFlow<String?>(null)
    val importProgress: StateFlow<String?> = _importProgress
    private val _communityCharacterUpdates = MutableStateFlow<Map<String, CommunityItem>>(emptyMap())
    val communityCharacterUpdates: StateFlow<Map<String, CommunityItem>> = _communityCharacterUpdates
    val novelAiConfigured: StateFlow<Boolean> = novelAiCredentials.configured

    private val _momentsReliability = MutableStateFlow(MomentReliabilityState())
    val momentsReliability: StateFlow<MomentReliabilityState> = _momentsReliability
    private val _momentDebug = MutableStateFlow(MomentDebugUiState())
    val momentDebug: StateFlow<MomentDebugUiState> = _momentDebug
    private val _momentSchedulePreview = MutableStateFlow(MomentSchedulePreviewUiState())
    val momentSchedulePreview: StateFlow<MomentSchedulePreviewUiState> = _momentSchedulePreview

    val appSettings: StateFlow<AppSettings> = settingsRepository.appSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val playerSetting: StateFlow<PlayerSetting> = settingsRepository.playerSetting
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlayerSetting())

    private val _characterPresets = kotlinx.coroutines.flow.MutableStateFlow<List<com.example.chatbar.data.local.entity.PresetEntry>>(emptyList())
    val characterPresets: StateFlow<List<com.example.chatbar.data.local.entity.PresetEntry>> = _characterPresets
    private val _formatPresets = kotlinx.coroutines.flow.MutableStateFlow<List<com.example.chatbar.data.local.entity.PresetEntry>>(emptyList())
    val formatPresets: StateFlow<List<com.example.chatbar.data.local.entity.PresetEntry>> = _formatPresets
    private val _worldBookPresets = kotlinx.coroutines.flow.MutableStateFlow<List<com.example.chatbar.data.local.entity.PresetEntry>>(emptyList())
    val worldBookPresets: StateFlow<List<com.example.chatbar.data.local.entity.PresetEntry>> = _worldBookPresets
    private val _modelPresets = kotlinx.coroutines.flow.MutableStateFlow<List<com.example.chatbar.data.local.entity.PresetEntry>>(emptyList())
    val modelPresets: StateFlow<List<com.example.chatbar.data.local.entity.PresetEntry>> = _modelPresets

    init {
        refreshAll()
    }

    fun refreshAll() {
        viewModelScope.launch {
            characterRepository.initialize()
            formatCardRepository.initialize()
            worldBookRepository.initialize()
            editorDraftRepository.initialize()
            modelRepository.initialize()
            settingsRepository.initialize()
            presetCatalog.initialize()
            _characterPresets.value = presetCatalog.entries(com.example.chatbar.data.local.entity.PresetType.CHARACTER)
            _formatPresets.value = presetCatalog.entries(com.example.chatbar.data.local.entity.PresetType.FORMAT)
            _worldBookPresets.value = presetCatalog.entries(com.example.chatbar.data.local.entity.PresetType.WORLD_BOOK)
            _modelPresets.value = presetModelCatalog.entries()
            refreshDraftsNow()
            refreshEffectiveModels()
            refreshCommunityCharacterUpdates()
            refreshMomentsReliability()
            refreshMomentSchedulePreview()
        }
    }

    fun refreshDrafts() {
        viewModelScope.launch {
            editorDraftRepository.initialize()
            refreshDraftsNow()
        }
    }

    private suspend fun refreshDraftsNow() {
        _editorDrafts.value = editorDraftRepository.getAll().sortedByDescending { it.updatedAt }
    }

    fun discardEditorDraft(draft: EditorDraft) {
        viewModelScope.launch {
            editorDraftRepository.delete(draft.id)
            editorDraftAssetService.deleteDraft(draft.draftSessionId)
            refreshDraftsNow()
        }
    }

    private suspend fun refreshEffectiveModels() {
        val settings = settingsRepository.getAppSettings()
        _effectiveChatModels.value = modelResolver.availableChatModels(settings)
        val status = modelResolver.status(settings)
        _modelConfigurationErrors.value = status.errors
        _modelConfigurationWarnings.value = status.warnings
        _isModelConfigurationUsable.value = status.isUsable
    }

    // ===== 角色卡 CRUD =====
    fun deleteCharacterCard(id: String) {
        ChatBarApp.instance.applicationScope.launch {
            ChatBarApp.instance.deletionCoordinator.deleteCharacter(id)
        }
    }

    fun duplicateCharacterCard(id: String, onDone: (String) -> Unit) {
        viewModelScope.launch { onDone(characterTransfers.duplicate(id).id) }
    }

    fun createSessionForCharacter(id: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            if (modelResolver.status().isUsable) {
                onDone(ChatBarApp.instance.characterSessionService.createSessionForCharacter(id))
            }
        }
    }

    fun characterHasUpdate(card: CharacterCard): Boolean = presetCatalog.hasUpdate(card.sourcePresetKey, card.sourcePresetVersion)
    fun characterCommunityUpdate(card: CharacterCard): CommunityItem? = _communityCharacterUpdates.value[card.id]

    fun updateCommunityCharacter(id: String, onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            _importProgress.value = "正在更新社区角色卡…"
            runCatching {
                val card = updateCommunityCharacterInternal(id)
                refreshCommunityCharacterUpdates()
                card
            }.fold(
                onSuccess = { card ->
                    _importProgress.value = null
                    onResult("已更新：${card.name}")
                },
                onFailure = { error ->
                    _importProgress.value = null
                    onResult("更新失败：${error.message}")
                }
            )
        }
    }

    suspend fun decodeCharacterImport(rawJson: String) =
        com.example.chatbar.domain.card.CharacterCardImportRequest(characterTransfers.decode(rawJson))

    suspend fun decodeCharacterImport(uri: android.net.Uri, context: android.content.Context): com.example.chatbar.domain.card.CharacterCardImportRequest {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes != null) {
            characterTransfers.decodePng(bytes)?.let {
                return com.example.chatbar.domain.card.CharacterCardImportRequest(it)
            }
        }

        val rawJson = bytes?.toString(Charsets.UTF_8)
            ?: runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()

        if (rawJson != null) {
            runCatching {
                val doc = characterImportProbeJson.parseToJsonElement(rawJson).jsonObject
                if (doc.containsKey("schemaVersion") && doc.containsKey("card")) {
                    return decodeCharacterImport(rawJson)
                }
            }
        }

        val st = com.example.chatbar.domain.card.SillyTavernCardParser.parseUri(context, uri)
        val packageData = com.example.chatbar.domain.card.SillyTavernCardMapper.toCharacterCardPackage(st)
        return com.example.chatbar.domain.card.CharacterCardImportRequest(packageData)
    }
    suspend fun findCharacterImportConflict(data: com.example.chatbar.domain.card.CharacterCardImportRequest): CharacterCard? {
        val all = characterRepository.getAll()
        data.presetKey
            ?.let { key -> all.firstOrNull { it.sourcePresetKey == key } }
            ?.let { return it }
        return all.firstOrNull {
            com.example.chatbar.domain.card.NamePolicy.isSame(it.name, data.packageData.card.name)
        }
    }

    suspend fun findCharacterNameConflict(name: String) = characterRepository.getAll().firstOrNull { com.example.chatbar.domain.card.NamePolicy.isSame(it.name, name) }
    suspend fun importCharacterAsNew(data: com.example.chatbar.domain.card.CharacterCardImportRequest): CharacterCard {
        val card = characterTransfers.importNew(data.packageData, presetKey = data.presetKey, presetVersion = data.presetVersion)
        startBackgroundRebuild(card)
        return card
    }
    suspend fun overwriteCharacter(id: String, data: com.example.chatbar.domain.card.CharacterCardImportRequest): CharacterCard {
        val existing = characterRepository.getById(id)
        require(existing?.isCommunityDownload != true) { "下载角色卡不能被本地导入覆盖，请复制后编辑" }
        val card = characterTransfers.overwrite(id, data.packageData, data.presetKey, data.presetVersion)
        startBackgroundRebuild(card)
        return card
    }
    suspend fun recoverCharacterPreset(entry: com.example.chatbar.data.local.entity.PresetEntry) =
        com.example.chatbar.domain.card.CharacterCardImportRequest(
            packageData = presetCatalog.characterPackage(entry),
            presetKey = entry.presetKey,
            presetVersion = entry.version
        )

    private fun startBackgroundRebuild(card: CharacterCard) {
        viewModelScope.launch {
            val repairedCard = presetCatalog.repairPresetCharacterResources(card)
            startBackgroundRebuildAfterRepair(repairedCard)
        }
    }

    private fun startBackgroundRebuildAfterRepair(card: CharacterCard) {
        if (card.customDocuments.isEmpty()) {
            val done = card.copy(
                ragIndexStatus = RagIndexStatus.COMPLETE.name,
                ragIndexDone = 0,
                ragIndexTotal = 0,
                ragIndexMessage = "无参考文档",
                ragIndexedAt = System.currentTimeMillis()
            )
            viewModelScope.launch { characterRepository.save(done) }
            return
        }
        _importProgress.value = "${card.name}：准备 RAG 索引…"
        ChatBarApp.instance.applicationScope.launch {
            try {
                AiBackgroundWorkManager.run(card.id) {
                val settings = settingsRepository.getAppSettings()
                val embedding = modelResolver.embeddingModel(settings)
                if (embedding == null) {
                    _importProgress.value = "${card.name}：未配置嵌入模型，跳过索引"
                    return@run
                }
                val indexed = mutableListOf<com.example.chatbar.data.local.entity.DocumentInfo>()
                val total = card.customDocuments.size
                for ((i, doc) in card.customDocuments.withIndex()) {
                    val result = runCatching {
                        val r = ChatBarApp.instance.ragManager.indexDocument(doc, File(doc.filePath).readText(), card.id, embedding)
                        doc.copy(
                            contentHash = r.contentHash,
                            indexedHash = r.contentHash,
                            ragStatus = com.example.chatbar.data.local.entity.DocumentRagStatus.INDEXED.name,
                            ragChunkCount = r.chunkCount,
                            ragIndexedAt = System.currentTimeMillis(),
                            ragError = null
                        )
                    }.getOrElse { doc.copy(ragStatus = com.example.chatbar.data.local.entity.DocumentRagStatus.FAILED.name, ragError = it.message) }
                    indexed.add(result)
                    _importProgress.value = "${card.name}：索引进度 ${i + 1}/$total"
                    if ((i + 1) % 3 == 0 || i == total - 1) {
                        val current = characterRepository.getById(card.id)
                        if (current != null) {
                            val failed = indexed.count { it.ragStatus == com.example.chatbar.data.local.entity.DocumentRagStatus.FAILED.name }
                            val mergedDocs = current.customDocuments.map { old -> indexed.firstOrNull { it.id == old.id } ?: old }
                            characterRepository.save(current.copy(
                                customDocuments = mergedDocs,
                                ragIndexStatus = if (failed > 0) RagIndexStatus.FAILED.name else RagIndexStatus.INDEXING.name,
                                ragIndexDone = i + 1,
                                ragIndexTotal = total,
                                ragIndexMessage = "索引进度：${i + 1}/$total",
                                ragIndexedAt = null
                            ))
                        }
                    }
                }
                val failed = indexed.count { it.ragStatus == com.example.chatbar.data.local.entity.DocumentRagStatus.FAILED.name }
                val updated = characterRepository.getById(card.id) ?: return@run
                val finalDocs = updated.customDocuments.map { old -> indexed.firstOrNull { it.id == old.id } ?: old }
                val final = updated.copy(
                    customDocuments = finalDocs,
                    ragIndexStatus = if (failed == 0) RagIndexStatus.COMPLETE.name else RagIndexStatus.FAILED.name,
                    ragIndexDone = total,
                    ragIndexTotal = total,
                    ragIndexMessage = if (failed == 0) "参考文档索引完成" else "$failed 份文档索引失败",
                    ragIndexedAt = System.currentTimeMillis()
                )
                characterRepository.save(final)
                _importProgress.value = if (failed == 0) null else "${card.name}：$failed 份文档索引失败"
                }
            } catch (e: Exception) {
                _importProgress.value = "${card.name}：索引失败 - ${e.message}"
            }
        }
    }

    suspend fun exportCharacterCardJson(id: String): String = characterTransfers.exportJson(id)
    suspend fun exportCharacterCardPng(id: String, options: CharacterCardPngExportOptions): ByteArray =
        characterTransfers.exportPng(id, options)

    // ===== 格式卡 CRUD =====
    fun deleteFormatCard(id: String) {
        viewModelScope.launch {
            formatCardRepository.delete(id)
        }
    }

    fun duplicateFormatCard(id: String) { viewModelScope.launch { formatTransfers.duplicate(id) } }
    fun formatHasUpdate(card: FormatCard): Boolean = presetCatalog.hasUpdate(card.sourcePresetKey, card.sourcePresetVersion)
    suspend fun exportFormatCardJson(id: String): String = formatTransfers.exportJson(id)
    suspend fun decodeFormatImport(rawJson: String) = formatTransfers.decode(rawJson)
    suspend fun findFormatNameConflict(name: String) = formatCardRepository.getAll().firstOrNull { com.example.chatbar.domain.card.NamePolicy.isSame(it.name, name) }
    suspend fun importFormatAsNew(data: com.example.chatbar.domain.card.FormatCardPackage) = formatTransfers.importNew(data)
    suspend fun overwriteFormat(id: String, data: com.example.chatbar.domain.card.FormatCardPackage) = formatTransfers.overwrite(id, data)
    suspend fun recoverFormatPreset(entry: com.example.chatbar.data.local.entity.PresetEntry): com.example.chatbar.domain.card.FormatCardPackage {
        val data = presetCatalog.formatPackage(entry)
        return data.copy(sourcePresetKey = entry.presetKey, sourcePresetVersion = entry.version)
    }

    private suspend fun refreshCommunityCharacterUpdates() {
        if (!communityService.configured) {
            _communityCharacterUpdates.value = emptyMap()
            return
        }
        val downloads = characterRepository.getAll().filter { it.isCommunityDownload }
        if (downloads.isEmpty()) {
            _communityCharacterUpdates.value = emptyMap()
            return
        }
        runCatching {
            val ids = downloads.mapNotNull { it.communityItemId }.toSet()
            communityService.listItems()
                .filter { it.type == CommunityItemType.CHARACTER && it.id in ids }
                .associateBy { it.id }
        }.onSuccess { remoteById ->
            _communityCharacterUpdates.value = downloads.mapNotNull { card ->
                val item = remoteById[card.communityItemId] ?: return@mapNotNull null
                val sameSha = item.sha256.isNotBlank() && item.sha256 == card.communityItemSha256
                val sameUpdated = item.updatedAt.isNotBlank() && item.updatedAt == card.communityItemUpdatedAt
                if (sameSha && sameUpdated) null else card.id to item
            }.toMap()
        }
    }

    private suspend fun updateCommunityCharacterInternal(id: String): CharacterCard {
        val existing = characterRepository.getById(id) ?: error("角色卡不存在")
        val itemId = existing.communityItemId?.takeIf(String::isNotBlank) ?: error("不是社区下载角色卡")
        val item = _communityCharacterUpdates.value[id]
            ?: communityService.listItems().firstOrNull { it.id == itemId && it.type == CommunityItemType.CHARACTER }
            ?: error("社区条目不存在或不可访问")
        val raw = communityService.downloadPackage(item)
        val packageData = characterTransfers.decode(raw)
        val updated = characterTransfers.overwrite(existing.id, packageData).withCommunitySource(item)
        characterRepository.save(updated)
        startBackgroundRebuild(updated)
        return updated
    }

    private fun CharacterCard.withCommunitySource(item: CommunityItem): CharacterCard =
        copy(
            communityItemId = item.id,
            communityItemUpdatedAt = item.updatedAt,
            communityItemSha256 = item.sha256,
            communityItemTitle = item.title
        )

    // ===== 世界书 CRUD =====
    fun duplicateWorldBook(id: String) { viewModelScope.launch { worldBookTransfers.duplicate(id) } }
    fun worldBookHasUpdate(book: WorldBook): Boolean = presetCatalog.hasUpdate(book.sourcePresetKey, book.sourcePresetVersion)
    suspend fun exportWorldBookJson(id: String): String = worldBookTransfers.exportJson(id)
    suspend fun exportWorldBookSillyTavernJson(id: String): String = worldBookTransfers.exportSillyTavernJson(id)
    fun decodeWorldBookImport(rawJson: String, fallbackName: String = "导入世界书") = worldBookTransfers.decode(rawJson, fallbackName)
    suspend fun findWorldBookNameConflict(name: String) =
        worldBookRepository.getAll().firstOrNull { com.example.chatbar.domain.card.NamePolicy.isSame(it.name, name) }
    suspend fun importWorldBookAsNew(data: com.example.chatbar.domain.card.WorldBookPackage) = worldBookTransfers.importNew(data)
    suspend fun overwriteWorldBook(id: String, data: com.example.chatbar.domain.card.WorldBookPackage) = worldBookTransfers.overwrite(id, data)
    suspend fun recoverWorldBookPreset(entry: com.example.chatbar.data.local.entity.PresetEntry): com.example.chatbar.domain.card.WorldBookPackage =
        presetCatalog.worldBookPackage(entry).let {
            it.copy(book = it.book.copy(sourcePresetKey = entry.presetKey, sourcePresetVersion = entry.version))
        }

    fun deleteWorldBook(id: String, onResult: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val characterRefs = characterRepository.getAll().filter { id in it.worldBookIds }.map { it.name }
            val sessionRefs = ChatBarApp.instance.chatRepository.getAllSessions().filter { id in it.extraWorldBookIds }.map { it.title }
            if (characterRefs.isNotEmpty() || sessionRefs.isNotEmpty()) {
                onResult(
                    buildString {
                        append("世界书仍被引用，请先解绑。")
                        if (characterRefs.isNotEmpty()) append(" 角色：${characterRefs.take(3).joinToString("、")}")
                        if (sessionRefs.isNotEmpty()) append(" 会话：${sessionRefs.take(3).joinToString("、")}")
                    }
                )
                return@launch
            }
            worldBookRepository.delete(id)
            onResult(null)
        }
    }

    fun setFormatCardDefault(id: String) {
        viewModelScope.launch {
            formatCardRepository.setDefault(id)
            val current = settingsRepository.getAppSettings()
            settingsRepository.saveAppSettings(current.copy(defaultFormatCardId = id))
        }
    }

    fun setDefaultModel(id: String) {
        viewModelScope.launch {
            val current = settingsRepository.getAppSettings()
            settingsRepository.saveAppSettings(current.copy(defaultModelId = id, presetDefaultModelKey = null))
            refreshEffectiveModels()
        }
    }

    fun setDefaultImageModel(id: String) {
        viewModelScope.launch {
            val current = settingsRepository.getAppSettings()
            settingsRepository.saveAppSettings(current.copy(defaultImageModelId = id))
            refreshEffectiveModels()
        }
    }

    fun importPresetModelCatalog(onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                val version = presetModelCatalog.entries().firstOrNull()?.version
                    ?: presetModelCatalog.catalog.schemaVersion
                modelRepository.restorePresetChatModels(presetModelCatalog.catalog, version)
                modelRepository.restorePresetSupportModels(presetModelCatalog.catalog, version)
                val current = settingsRepository.getAppSettings()
                if (current.defaultModelId == null && current.presetDefaultModelKey != null) {
                    val presetModelId = PRESET_MODEL_ID_PREFIX + current.presetDefaultModelKey
                    settingsRepository.saveAppSettings(
                        current.copy(
                            defaultModelId = presetModelId,
                            presetDefaultModelKey = null
                        )
                    )
                }
                refreshEffectiveModels()
            }.fold(
                onSuccess = { onDone(null) },
                onFailure = { onDone(it.message ?: "导入失败") }
            )
        }
    }

    fun saveFormatCard(name: String, content: String, onResult: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val normalized = com.example.chatbar.domain.card.NamePolicy.normalize(name)
            val conflict = formatCardRepository.getAll().firstOrNull { com.example.chatbar.domain.card.NamePolicy.isSame(it.name, normalized) }
            if (conflict != null) {
                onResult("格式卡名称与“${conflict.name}”冲突")
            } else {
                formatCardRepository.save(FormatCard.create(normalized, content))
                onResult(null)
            }
        }
    }

    // ===== 模型与嵌入模型 CRUD =====
    fun deleteModelConfig(id: String) {
        viewModelScope.launch {
            modelRepository.deleteModel(id)
            refreshEffectiveModels()
        }
    }

    fun duplicateModelConfig(id: String, onDone: (String) -> Unit = {}) {
        viewModelScope.launch {
            val copy = modelRepository.duplicateModel(id)
            refreshEffectiveModels()
            onDone(copy.id)
        }
    }

    suspend fun exportModelTemplateJson(id: String): String = withContext(Dispatchers.IO) {
        val model = modelRepository.getModel(id) ?: error("Model not found")
        json.encodeToString(
            ExportedModelTemplate.serializer(),
            ExportedModelTemplate(
                displayName = model.displayName,
                baseUrl = model.baseUrl,
                modelName = model.modelName,
                isMultimodal = model.isMultimodal,
                templateType = model.templateType,
                customParams = model.customParams,
                reasoningEffort = model.reasoningEffort,
                enableThinking = model.enableThinking,
                maxOutputTokens = model.maxOutputTokens
            )
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun importModelTemplateJson(rawJson: String): String = withContext(Dispatchers.IO) {
        val template = json.decodeFromString(ExportedModelTemplate.serializer(), rawJson)
        val model = ModelConfig(
            id = Uuid.random().toString(),
            displayName = "${template.displayName} (Imported Template)",
            baseUrl = template.baseUrl,
            apiKey = "",
            modelName = template.modelName,
            isMultimodal = template.isMultimodal,
            visionModelId = null,
            templateType = template.templateType,
            customParams = template.customParams,
            reasoningEffort = template.reasoningEffort,
            enableThinking = template.enableThinking,
            maxOutputTokens = template.maxOutputTokens,
            createdAt = System.currentTimeMillis()
        )
        modelRepository.saveModel(model)
        model.id
    }

    fun deleteEmbeddingConfig(id: String) {
        viewModelScope.launch {
            modelRepository.deleteEmbeddingModel()
            refreshEffectiveModels()
        }
    }

    fun saveEmbeddingConfig(config: EmbeddingConfig) {
        viewModelScope.launch {
            modelRepository.saveEmbeddingModel(config)
            refreshEffectiveModels()
        }
    }

    fun saveRetrievalModelConfig(model: ModelConfig) {
        viewModelScope.launch {
            modelRepository.saveRetrievalModel(model)
            refreshEffectiveModels()
        }
    }

    fun deleteRetrievalModelConfig() {
        viewModelScope.launch {
            modelRepository.deleteRetrievalModel()
            refreshEffectiveModels()
        }
    }

    // ===== 全局设置更新 =====
    fun updateAppSettings(settings: AppSettings) {
        viewModelScope.launch {
            val previous = settingsRepository.getAppSettings()
            val momentFrequencyChanged =
                previous.momentsMinDelayHours != settings.momentsMinDelayHours ||
                    previous.momentsMaxDelayHours != settings.momentsMaxDelayHours
            settingsRepository.saveAppSettings(settings)
            if (momentFrequencyChanged) {
                momentRepository.deletePendingFutureTasks(System.currentTimeMillis())
            }
            refreshEffectiveModels()
            refreshMomentsReliability()
            if (settings.momentsEnabled) {
                ChatBarApp.instance.momentScheduler.kick("settings")
            } else {
                MomentAlarmScheduler.cancel(ChatBarApp.instance)
            }
            refreshMomentSchedulePreview()
        }
    }

    fun refreshMomentsReliability() {
        viewModelScope.launch {
            val settings = settingsRepository.getAppSettings()
            _momentsReliability.value = MomentBackgroundReliability.check(
                context = ChatBarApp.instance,
                autoStartConfirmed = settings.momentsAutoStartConfirmed
            )
        }
    }

    fun openMomentsAutoStartSettings(context: Context) {
        MomentBackgroundReliability.openAutoStartSettings(context)
    }

    fun openMomentsBatterySettings(context: Context) {
        MomentBackgroundReliability.openBatterySettings(context)
    }

    fun openMomentsNotificationSettings(context: Context) {
        MomentBackgroundReliability.openNotificationSettings(context)
    }

    fun confirmMomentsAutoStart() {
        viewModelScope.launch {
            val current = settingsRepository.getAppSettings()
            settingsRepository.saveAppSettings(current.copy(momentsAutoStartConfirmed = true))
            refreshMomentsReliability()
        }
    }

    fun generateDebugMoment(cardId: String) {
        viewModelScope.launch {
            _momentDebug.value = MomentDebugUiState(isRunning = true)
            val result = runCatching {
                characterRepository.initialize()
                chatRepository.initialize()
                momentRepository.initialize()
                settingsRepository.initialize()
                val card = characterRepository.getById(cardId) ?: error("角色卡不存在")
                val session = chatRepository.getAllSessions()
                    .filter { it.characterCardId == cardId }
                    .maxByOrNull { it.lastMessageTime ?: it.updatedAt }
                    ?: error("该角色卡还没有会话")
                val messages = chatRepository.getMessages(session.id)
                val settings = settingsRepository.getAppSettings()
                val model = modelResolver.defaultChatModel(settings)
                    ?: error("未配置可用默认对话模型")
                require(model.apiKey.isNotBlank()) { "默认对话模型/API Key 未配置" }
                val imageModel = modelResolver.defaultImageModel(settings)
                val latestPost = momentRepository.latestPostForCard(cardId)
                AiBackgroundWorkManager.run("moments_debug_$cardId") {
                    momentGenerationService.debugGenerateNow(
                        card = card,
                        session = session,
                        messages = messages,
                        latestPost = latestPost,
                        model = model,
                        imageModel = imageModel,
                        scheduledAt = System.currentTimeMillis(),
                        finalPromptRequirement = settings.imagePromptToolPreference
                    )
                }.also { debugResult ->
                    debugResult.post?.let { momentRepository.savePost(it) }
                }
            }.getOrElse { error ->
                MomentDebugGenerationResult(errorMessage = error.message ?: error.javaClass.simpleName)
            }
            _momentDebug.value = MomentDebugUiState(result = result)
            refreshMomentSchedulePreview()
        }
    }

    fun clearMomentDebug() {
        _momentDebug.value = MomentDebugUiState()
    }

    fun refreshMomentSchedulePreview() {
        viewModelScope.launch {
            _momentSchedulePreview.value = _momentSchedulePreview.value.copy(isLoading = true, message = null)
            _momentSchedulePreview.value = runCatching {
                loadMomentSchedulePreview()
            }.getOrElse { error ->
                MomentSchedulePreviewUiState(
                    generatedAt = System.currentTimeMillis(),
                    message = "读取排程失败：${error.message ?: error.javaClass.simpleName}"
                )
            }
        }
    }

    private suspend fun loadMomentSchedulePreview(now: Long = System.currentTimeMillis()): MomentSchedulePreviewUiState {
        settingsRepository.initialize()
        characterRepository.initialize()
        chatRepository.initialize()
        momentRepository.initialize()
        val settings = settingsRepository.getAppSettings()
        if (!settings.momentsEnabled) {
            return MomentSchedulePreviewUiState(
                generatedAt = now,
                message = "全局朋友圈未开启，暂无后续生成安排。"
            )
        }

        val cards = characterRepository.getAll()
        val enabledCards = cards.filter { it.momentsEnabled }
        val enabledCardIds = enabledCards.map { it.id }.toSet()
        val sessions = chatRepository.getAllSessions()
        ChatBarApp.instance.momentScheduler.ensureFutureSchedules("manage-preview", now)
        val horizon = now + MOMENT_SCHEDULE_PREVIEW_WINDOW_MS
        val cardsById = cards.associateBy { it.id }
        val sessionsById = sessions.associateBy { it.id }
        val allTasks = momentRepository.getAllTasks()
        val items = allTasks
            .filter { it.status == MomentTaskStatus.PENDING && it.scheduledAt in now..horizon }
            .sortedBy { it.scheduledAt }
            .map { task ->
                val card = cardsById[task.characterCardId]
                val session = sessionsById[task.sessionId]
                MomentSchedulePreviewItem(
                    taskId = task.id,
                    cardName = card?.name?.ifBlank { "未命名角色卡" } ?: "已删除角色卡",
                    sessionTitle = session?.title?.ifBlank { "未命名会话" } ?: "会话已删除",
                    scheduledAt = task.scheduledAt,
                    statusLabel = when {
                        card == null -> "将跳过"
                        !card.momentsEnabled -> "将跳过"
                        else -> "待生成"
                    }
                )
            }
        val activeEnabledCardIds = sessions
            .filter { session ->
                session.characterCardId in enabledCardIds &&
                    MomentPolicy.isRecentlyActive(session.lastMessageTime, now)
            }
            .map { it.characterCardId }
            .toSet()
        val nextPendingOutsideWindow = allTasks
            .filter {
                it.characterCardId in enabledCardIds &&
                    it.status == MomentTaskStatus.PENDING &&
                    it.scheduledAt > horizon
            }
            .minByOrNull { it.scheduledAt }

        return MomentSchedulePreviewUiState(
            generatedAt = now,
            items = items,
            message = when {
                items.isNotEmpty() -> null
                enabledCards.isEmpty() -> "全局朋友圈已开启，但还没有角色卡开启朋友圈。请在角色卡编辑页开启。"
                activeEnabledCardIds.isEmpty() -> "已开启朋友圈的角色暂无 48 小时内活跃会话，暂不排程。"
                nextPendingOutsideWindow != null -> "未来 12 小时暂无已排到的朋友圈；下一条任务在 12 小时窗口之外。"
                else -> "未来 12 小时暂无已排到的朋友圈。"
            }
        )
    }

    fun updateModelConfigurationMode(mode: ModelConfigurationMode) {
        viewModelScope.launch {
            val current = settingsRepository.getAppSettings()
            settingsRepository.saveAppSettings(current.copy(modelConfigurationMode = mode.normalized()))
            refreshEffectiveModels()
        }
    }

    fun updateThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            val current = settingsRepository.getAppSettings()
            settingsRepository.saveAppSettings(current.copy(themeMode = mode))
        }
    }

    fun updateBubbleFontScale(scale: Float) {
        viewModelScope.launch {
            val current = settingsRepository.getAppSettings()
            settingsRepository.saveAppSettings(current.copy(chatBubbleFontScale = scale))
        }
    }

    fun saveSiliconFlowApiKey(apiKey: String) {
        viewModelScope.launch {
            val current = settingsRepository.getAppSettings()
            settingsRepository.saveAppSettings(current.copy(siliconFlowApiKey = apiKey.trim()))
            _apiTestStatus.value = "API Key 已保存"
            refreshEffectiveModels()
        }
    }

    fun saveNovelAiToken(token: String) {
        novelAiCredentials.save(token)
    }

    fun clearNovelAiToken() {
        novelAiCredentials.clear()
    }

    fun testSiliconFlowApi(apiKey: String, allowCleartextModelApi: Boolean) {
        ChatBarApp.instance.applicationScope.launch {
            _apiTestStatus.value = "正在测试对话与向量接口…"
            val current = settingsRepository.getAppSettings().copy(
                modelConfigurationMode = ModelConfigurationMode.CUSTOM_API,
                siliconFlowApiKey = apiKey.trim(),
                allowCleartextModelApi = allowCleartextModelApi
            )
            val chat = modelResolver.defaultChatModel(current)
            val embedding = modelResolver.embeddingModel(current)
            val chatService = StreamingChatService { allowCleartextModelApi }
            val embeddingService = EmbeddingService { allowCleartextModelApi }
            val chatResult = if (chat == null) "对话：预制型号未配置" else runCatching {
                AiBackgroundWorkManager.run("api-test") {
                    chatService.completeText(
                    listOf(com.example.chatbar.domain.chat.ChatApiMessage.text("user", "Reply with OK")),
                    chat,
                    maxTokens = 8
                )
                }
                "对话：成功"
            }.getOrElse { "对话：失败 ${it.message}" }
            val embeddingResult = if (embedding == null) "向量：预制型号未配置" else runCatching {
                AiBackgroundWorkManager.run("api-test") {
                    embeddingService.getEmbedding("test", embedding)
                }
                "向量：成功"
            }.getOrElse { "向量：失败 ${it.message}" }
            _apiTestStatus.value = "$chatResult\n$embeddingResult"
        }
    }

    fun updatePlayerSetting(playerName: String, persona: String) {
        viewModelScope.launch {
            val current = settingsRepository.getPlayerSetting()
            settingsRepository.savePlayerSetting(current.copy(playerName = playerName, globalPersona = persona))
        }
    }

    private companion object {
        const val MOMENT_SCHEDULE_PREVIEW_WINDOW_MS: Long = 12L * 60L * 60L * 1000L
    }
}
