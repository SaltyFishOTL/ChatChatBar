package com.example.chatbar.ui.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.RagIndexStatus
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelConfigurationMode
import com.example.chatbar.data.local.entity.ParamValue
import com.example.chatbar.data.local.entity.PlayerSetting
import com.example.chatbar.data.local.entity.ThemeMode
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

/**
 * 管理界面 ViewModel - 提供角色、格式卡、模型及系统参数的管理功能
 */
class ManageViewModel : ViewModel() {

    private val characterRepository = ChatBarApp.instance.characterRepository
    private val formatCardRepository = ChatBarApp.instance.formatCardRepository
    private val modelRepository = ChatBarApp.instance.modelRepository
    private val settingsRepository = ChatBarApp.instance.settingsRepository
    private val characterTransfers = ChatBarApp.instance.characterCardTransferService
    private val formatTransfers = ChatBarApp.instance.formatCardTransferService
    private val presetCatalog = ChatBarApp.instance.presetCatalogService
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
    private val _isModelConfigurationUsable = MutableStateFlow(false)
    val isModelConfigurationUsable: StateFlow<Boolean> = _isModelConfigurationUsable
    private val _apiTestStatus = MutableStateFlow<String?>(null)
    val apiTestStatus: StateFlow<String?> = _apiTestStatus
    val novelAiConfigured: StateFlow<Boolean> = novelAiCredentials.configured

    val appSettings: StateFlow<AppSettings> = settingsRepository.appSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val playerSetting: StateFlow<PlayerSetting> = settingsRepository.playerSetting
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlayerSetting())

    private val _characterPresets = kotlinx.coroutines.flow.MutableStateFlow<List<com.example.chatbar.data.local.entity.PresetEntry>>(emptyList())
    val characterPresets: StateFlow<List<com.example.chatbar.data.local.entity.PresetEntry>> = _characterPresets
    private val _formatPresets = kotlinx.coroutines.flow.MutableStateFlow<List<com.example.chatbar.data.local.entity.PresetEntry>>(emptyList())
    val formatPresets: StateFlow<List<com.example.chatbar.data.local.entity.PresetEntry>> = _formatPresets

    init {
        refreshAll()
    }

    fun refreshAll() {
        viewModelScope.launch {
            characterRepository.initialize()
            formatCardRepository.initialize()
            modelRepository.initialize()
            settingsRepository.initialize()
            presetCatalog.initialize()
            _characterPresets.value = presetCatalog.entries(com.example.chatbar.data.local.entity.PresetType.CHARACTER)
            _formatPresets.value = presetCatalog.entries(com.example.chatbar.data.local.entity.PresetType.FORMAT)
            refreshEffectiveModels()
        }
    }

    private suspend fun refreshEffectiveModels() {
        val settings = settingsRepository.getAppSettings()
        _effectiveChatModels.value = modelResolver.availableChatModels(settings)
        val status = modelResolver.status(settings)
        _modelConfigurationErrors.value = status.errors
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

    suspend fun decodeCharacterImport(rawJson: String) =
        com.example.chatbar.domain.card.CharacterCardImportRequest(characterTransfers.decode(rawJson))
    suspend fun findCharacterNameConflict(name: String) = characterRepository.getAll().firstOrNull { com.example.chatbar.domain.card.NamePolicy.isSame(it.name, name) }
    suspend fun importCharacterAsNew(data: com.example.chatbar.domain.card.CharacterCardImportRequest): CharacterCard {
        val card = characterTransfers.importNew(data.packageData, presetKey = data.presetKey, presetVersion = data.presetVersion)
        return rebuildImportedDocuments(card)
    }
    suspend fun overwriteCharacter(id: String, data: com.example.chatbar.domain.card.CharacterCardImportRequest): CharacterCard {
        val card = characterTransfers.overwrite(id, data.packageData, data.presetKey, data.presetVersion)
        return rebuildImportedDocuments(card)
    }
    suspend fun recoverCharacterPreset(entry: com.example.chatbar.data.local.entity.PresetEntry) =
        com.example.chatbar.domain.card.CharacterCardImportRequest(
            packageData = presetCatalog.characterPackage(entry),
            presetKey = entry.presetKey,
            presetVersion = entry.version
        )

    private suspend fun rebuildImportedDocuments(card: CharacterCard): CharacterCard {
        if (card.customDocuments.isEmpty()) {
            return card.copy(
                ragIndexStatus = RagIndexStatus.COMPLETE.name,
                ragIndexDone = 0,
                ragIndexTotal = 0,
                ragIndexMessage = "无参考文档"
            ).also { characterRepository.save(it) }
        }
        val settings = settingsRepository.getAppSettings()
        val embedding = modelResolver.embeddingModel(settings) ?: return card
        val indexed = card.customDocuments.map { doc ->
            runCatching {
                val result = ChatBarApp.instance.ragManager.indexDocument(doc, File(doc.filePath).readText(), card.id, embedding)
                doc.copy(
                    contentHash = result.contentHash,
                    indexedHash = result.contentHash,
                    ragStatus = com.example.chatbar.data.local.entity.DocumentRagStatus.INDEXED.name,
                    ragChunkCount = result.chunkCount,
                    ragIndexedAt = System.currentTimeMillis(),
                    ragError = null
                )
            }.getOrElse { doc.copy(ragStatus = com.example.chatbar.data.local.entity.DocumentRagStatus.FAILED.name, ragError = it.message) }
        }
        val failed = indexed.count { it.ragStatus == com.example.chatbar.data.local.entity.DocumentRagStatus.FAILED.name }
        return card.copy(
            customDocuments = indexed,
            ragIndexStatus = if (failed == 0) RagIndexStatus.COMPLETE.name else RagIndexStatus.FAILED.name,
            ragIndexDone = indexed.size,
            ragIndexTotal = indexed.size,
            ragIndexMessage = if (failed == 0) "参考文档索引完成" else "$failed 份文档索引失败",
            ragIndexedAt = System.currentTimeMillis()
        ).also { characterRepository.save(it) }
    }

    suspend fun exportCharacterCardJson(id: String): String = characterTransfers.exportJson(id)

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

    fun setFormatCardDefault(id: String) {
        viewModelScope.launch {
            formatCardRepository.setDefault(id)
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
        }
    }

    fun saveEmbeddingConfig(config: EmbeddingConfig) {
        viewModelScope.launch {
            modelRepository.saveEmbeddingModel(config)
        }
    }

    fun saveRetrievalModelConfig(model: ModelConfig) {
        viewModelScope.launch {
            modelRepository.saveRetrievalModel(model)
        }
    }

    fun deleteRetrievalModelConfig() {
        viewModelScope.launch {
            modelRepository.deleteRetrievalModel()
        }
    }

    // ===== 全局设置更新 =====
    fun updateAppSettings(settings: AppSettings) {
        viewModelScope.launch {
            settingsRepository.saveAppSettings(settings)
            refreshEffectiveModels()
        }
    }

    fun updateModelConfigurationMode(mode: ModelConfigurationMode) {
        viewModelScope.launch {
            val current = settingsRepository.getAppSettings()
            settingsRepository.saveAppSettings(current.copy(modelConfigurationMode = mode))
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

    fun testSiliconFlowApi(apiKey: String) {
        viewModelScope.launch {
            _apiTestStatus.value = "正在测试对话与向量接口…"
            val current = settingsRepository.getAppSettings().copy(
                modelConfigurationMode = ModelConfigurationMode.CUSTOM_API,
                siliconFlowApiKey = apiKey.trim()
            )
            val chat = modelResolver.defaultChatModel(current)
            val embedding = modelResolver.embeddingModel(current)
            val chatResult = if (chat == null) "对话：预制型号未配置" else runCatching {
                ChatBarApp.instance.streamingChatService.completeText(
                    listOf(com.example.chatbar.domain.chat.ChatApiMessage.text("user", "Reply with OK")),
                    chat,
                    maxTokens = 8
                )
                "对话：成功"
            }.getOrElse { "对话：失败 ${it.message}" }
            val embeddingResult = if (embedding == null) "向量：预制型号未配置" else runCatching {
                ChatBarApp.instance.embeddingService.getEmbedding("test", embedding)
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
}
