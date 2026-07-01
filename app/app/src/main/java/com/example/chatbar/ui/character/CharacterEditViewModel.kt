package com.example.chatbar.ui.character

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.data.local.entity.DocumentRagStatus
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.RagIndexStatus
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.domain.card.NamePolicy
import com.example.chatbar.domain.card.CharacterAutoFillDraft
import com.example.chatbar.domain.card.CharacterRewriteDraft
import com.example.chatbar.domain.image.NovelAiImageEvent
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

data class CharacterAutoFillUiState(
    val isGenerating: Boolean = false,
    val draft: CharacterAutoFillDraft? = null,
    val error: String? = null,
    val streamingText: String = "",
    val statusText: String = "",
    val modelId: String? = null
)

data class CharacterRewriteUiState(
    val isGenerating: Boolean = false,
    val draft: CharacterRewriteDraft? = null,
    val error: String? = null,
    val streamingText: String = "",
    val statusText: String = ""
)

class CharacterEditViewModel(private val characterId: String?) : ViewModel() {
    private val characterRepository = ChatBarApp.instance.characterRepository
    private val worldBookRepository = ChatBarApp.instance.worldBookRepository
    private val settingsRepository = ChatBarApp.instance.settingsRepository
    private val ragManager = ChatBarApp.instance.ragManager
    private val modelResolver = ChatBarApp.instance.effectiveModelResolver
    private val characterAutoFillService = ChatBarApp.instance.characterAutoFillService
    private val characterRewriteService = ChatBarApp.instance.characterRewriteService
    private val novelAiCredentials = ChatBarApp.instance.novelAiCredentialStore
    private val novelAiPromptDesigner = ChatBarApp.instance.novelAiPromptDesigner
    private val novelAiImageService = ChatBarApp.instance.novelAiImageService
    private val novelAiImageStorage = ChatBarApp.instance.novelAiImageStorage
    private var indexingJob: Job? = null
    private var autoFillJob: Job? = null
    private var autoFillImageJob: Job? = null
    private var autoFillGenerationToken = 0
    private var rewriteJob: Job? = null
    private var rewriteGenerationToken = 0

    private val _characterCard = MutableStateFlow<CharacterCard?>(null)
    val characterCard: StateFlow<CharacterCard?> = _characterCard.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _indexingStatus = MutableStateFlow<String?>(null)
    val indexingStatus: StateFlow<String?> = _indexingStatus.asStateFlow()

    private val _autoFillState = MutableStateFlow(CharacterAutoFillUiState())
    val autoFillState: StateFlow<CharacterAutoFillUiState> = _autoFillState.asStateFlow()

    private val _rewriteState = MutableStateFlow(CharacterRewriteUiState())
    val rewriteState: StateFlow<CharacterRewriteUiState> = _rewriteState.asStateFlow()

    private val _autoFillModels = MutableStateFlow<List<ModelConfig>>(emptyList())
    val autoFillModels: StateFlow<List<ModelConfig>> = _autoFillModels.asStateFlow()

    private val _autoFillDefaultModelId = MutableStateFlow<String?>(null)
    val autoFillDefaultModelId: StateFlow<String?> = _autoFillDefaultModelId.asStateFlow()

    private val _availableWorldBooks = MutableStateFlow<List<com.example.chatbar.data.local.entity.WorldBook>>(emptyList())
    val availableWorldBooks: StateFlow<List<com.example.chatbar.data.local.entity.WorldBook>> = _availableWorldBooks.asStateFlow()

    var name by mutableStateOf("")
    var greeting by mutableStateOf("")
    var alternateGreetings by mutableStateOf(listOf<String>())
    var avatar by mutableStateOf<String?>(null)
    var chatBackground by mutableStateOf<String?>(null)
    var editMode by mutableStateOf(CharacterEditMode.STRUCTURED)
    var basicSetting by mutableStateOf("")
    var freeformCharacterText by mutableStateOf("")
    var defaultImagePrompt by mutableStateOf("")
    var systemPrompt by mutableStateOf("")
    var postHistoryInstructions by mutableStateOf("")
    var mesExample by mutableStateOf("")
    var creatorNotes by mutableStateOf("")
    val charactersList = mutableStateListOf<CharacterInfo>()
    val documentsList = mutableStateListOf<DocumentInfo>()
    val selectedWorldBookIds = mutableStateListOf<String>()
    val worldBookEntries = mutableStateListOf<com.example.chatbar.data.local.entity.WorldBookEntry>()

    init {
        loadCharacterCard()
        refreshAutoFillModels()
    }

    private fun refreshAutoFillModels() {
        viewModelScope.launch {
            runCatching {
                val settings = settingsRepository.getAppSettings()
                _autoFillModels.value = modelResolver.availableChatModels(settings)
                _autoFillDefaultModelId.value = modelResolver.resolveChatModel(null, settings)?.id
            }.onFailure {
                _autoFillModels.value = emptyList()
                _autoFillDefaultModelId.value = null
            }
        }
    }

    private fun loadCharacterCard() {
        viewModelScope.launch {
            _availableWorldBooks.value = worldBookRepository.getAll()
            if (characterId != null) {
                characterRepository.getById(characterId)?.let { card ->
                    _characterCard.value = card
                    name = card.name
                    greeting = card.greeting
                    alternateGreetings = card.alternateGreetings
                    avatar = card.avatar
                    chatBackground = card.chatBackground
                    editMode = card.editMode
                    basicSetting = card.basicSetting
                    freeformCharacterText = card.freeformCharacterText
                    defaultImagePrompt = card.defaultImagePrompt
                    systemPrompt = card.systemPrompt
                    postHistoryInstructions = card.postHistoryInstructions
                    mesExample = card.mesExample
                    creatorNotes = card.creatorNotes
                    charactersList.clear()
                    charactersList.addAll(card.characters)
                    documentsList.clear()
                    documentsList.addAll(card.customDocuments)
                    selectedWorldBookIds.clear()
                    selectedWorldBookIds.addAll(
                        (card.worldBookIds + listOfNotNull(card.boundWorldBookId, card.characterBook?.id))
                            .filter { it.isNotBlank() }
                            .distinct()
                    )
                    worldBookEntries.clear()
                    card.characterBook?.entries?.let { worldBookEntries.addAll(it) }
                }
            } else {
                charactersList.add(CharacterInfo.create(""))
            }
        }
    }

    fun saveCharacterCard(onSuccess: () -> Unit) {
        if (validateForSave().isNotEmpty()) return
        _isSaving.value = true

        viewModelScope.launch {
            name = NamePolicy.normalize(name)
            val conflict = characterRepository.getAll().firstOrNull {
                it.id != characterId && NamePolicy.isSame(it.name, name)
            }
            if (conflict != null) {
                _indexingStatus.value = "角色卡名称与“${conflict.name}”冲突"
                _isSaving.value = false
                return@launch
            }
            val card = buildCurrentCard(markDirty = true)
            characterRepository.save(card)
            _characterCard.value = card
            _isSaving.value = false
            startBackgroundIndex(card)
            onSuccess()
        }
    }

    fun validateForSave(): List<String> {
        val errors = mutableListOf<String>()
        if (name.isBlank()) errors += "角色卡名称不能为空。"
        if (greeting.isBlank()) errors += "开场白不能为空。"
        return errors
    }

    fun switchEditMode(target: CharacterEditMode) {
        if (target == editMode) return
        editMode = target
    }

    fun generateAutoFillDraft(userInput: String, modelId: String? = null) {
        if (editMode != CharacterEditMode.STRUCTURED) {
            _autoFillState.value = CharacterAutoFillUiState(error = "AI 自动填充仅支持分段模式")
            return
        }
        if (userInput.isBlank()) {
            _autoFillState.value = CharacterAutoFillUiState(error = "请输入角色信息或想玩的角色")
            return
        }
        val selectedModel = modelId?.let { id -> _autoFillModels.value.firstOrNull { it.id == id } }
        if (modelId != null && selectedModel == null) {
            _autoFillState.value = CharacterAutoFillUiState(error = "所选模型不可用，请重新选择")
            refreshAutoFillModels()
            return
        }
        val selectedModelId = selectedModel?.id
        autoFillJob?.cancel()
        autoFillGenerationToken += 1
        val generationToken = autoFillGenerationToken
        val statusText = selectedModel?.autoFillLabel()
            ?.let { "正在使用 $it 生成角色卡候选" }
            ?: "正在使用默认模型生成角色卡候选"
        _autoFillState.value = CharacterAutoFillUiState(
            isGenerating = true,
            statusText = statusText,
            modelId = selectedModelId
        )
        autoFillJob = viewModelScope.launch {
            var latestRawText = ""
            try {
                val draft = characterAutoFillService.generateStreaming(
                    userInput = userInput,
                    currentCard = buildCurrentCard(markDirty = false),
                    modelOverride = selectedModel,
                    onRawText = { rawText ->
                        latestRawText = rawText
                        if (generationToken == autoFillGenerationToken) {
                            _autoFillState.value = CharacterAutoFillUiState(
                                isGenerating = true,
                                streamingText = rawText,
                                statusText = statusText,
                                modelId = selectedModelId
                            )
                        }
                    }
                )
                if (generationToken == autoFillGenerationToken) {
                    _autoFillState.value = CharacterAutoFillUiState(
                        draft = draft,
                        streamingText = latestRawText,
                        modelId = selectedModelId
                    )
                }
            } catch (_: CancellationException) {
                if (generationToken == autoFillGenerationToken) {
                    _autoFillState.value = CharacterAutoFillUiState(
                        error = "已取消生成",
                        streamingText = latestRawText,
                        statusText = "已取消生成",
                        modelId = selectedModelId
                    )
                }
            } catch (error: Throwable) {
                if (generationToken == autoFillGenerationToken) {
                    _autoFillState.value = CharacterAutoFillUiState(
                        error = error.message ?: "AI 自动填充失败",
                        streamingText = latestRawText,
                        modelId = selectedModelId
                    )
                }
            } finally {
                if (generationToken == autoFillGenerationToken) {
                    autoFillJob = null
                }
            }
        }
    }

    fun cancelAutoFillGeneration() {
        val state = _autoFillState.value
        if (!state.isGenerating) return
        autoFillJob?.cancel()
        _autoFillState.value = state.copy(
            isGenerating = false,
            draft = null,
            error = "已取消生成",
            statusText = "已取消生成"
        )
    }

    fun applyAutoFillDraft() {
        val state = _autoFillState.value
        val draft = state.draft ?: return
        if (editMode != CharacterEditMode.STRUCTURED) {
            _autoFillState.value = CharacterAutoFillUiState(error = "AI 自动填充仅支持分段模式")
            return
        }
        val merged = characterAutoFillService.mergeInto(buildCurrentCard(markDirty = false), draft)
        val shouldGenerateCoverImage = avatar.isNullOrBlank() && chatBackground.isNullOrBlank()
        name = merged.name
        greeting = merged.greeting
        basicSetting = merged.basicSetting
        defaultImagePrompt = merged.defaultImagePrompt
        charactersList.clear()
        charactersList.addAll(merged.characters)
        if (shouldGenerateCoverImage) {
            generateAutoFillCoverImage(buildCurrentCard(markDirty = false), state.modelId)
        }
        _autoFillState.value = CharacterAutoFillUiState()
    }

    private fun generateAutoFillCoverImage(card: CharacterCard, modelId: String?) {
        autoFillImageJob?.cancel()
        autoFillImageJob = viewModelScope.launch {
            if (!avatar.isNullOrBlank() || !chatBackground.isNullOrBlank()) return@launch
            try {
                val token = withContext(Dispatchers.IO) { novelAiCredentials.load() }
                val settings = settingsRepository.getAppSettings()
                val model = modelResolver.resolveChatModel(modelId, settings)
                if (token == null || model == null || model.apiKey.isBlank()) {
                    val missing = mutableListOf<String>()
                    if (token == null) missing += "NovelAI Token"
                    if (model == null || model.apiKey.isBlank()) missing += "对话模型/API Key"
                    _indexingStatus.value = "AI 自动填充已应用；缺少${missing.joinToString("、")}，未生成头像和背景"
                    return@launch
                }
                AiBackgroundWorkManager.run(card.id) {
                    _indexingStatus.value = "正在根据角色卡生成头像和聊天背景"
                    val prompt = novelAiPromptDesigner.designForCharacterCard(card, model) {
                        _indexingStatus.value = "正在设计头像和背景 Prompt"
                    }
                    _indexingStatus.value = "正在调用 NovelAI 生成头像和聊天背景"
                    val seed = novelAiImageService.newSeed()
                    var finalImage: ByteArray? = null
                    novelAiImageService.generate(token, prompt, seed).collect { event ->
                        when (event) {
                            is NovelAiImageEvent.Intermediate -> Unit
                            is NovelAiImageEvent.Final -> {
                                finalImage = event.image
                            }
                            is NovelAiImageEvent.Error -> error(event.message)
                        }
                    }
                    val bytes = finalImage ?: error("NovelAI 未返回最终图片")
                    val path = withContext(Dispatchers.IO) {
                        novelAiImageStorage.save(card.id, bytes)
                    }
                    if (avatar.isNullOrBlank() && chatBackground.isNullOrBlank()) {
                        avatar = path
                        chatBackground = path
                        _indexingStatus.value = "已生成头像和聊天背景"
                    } else {
                        withContext(Dispatchers.IO) {
                            novelAiImageStorage.deleteIfOwned(path)
                        }
                        _indexingStatus.value = "头像或背景已存在，已跳过自动写入"
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _indexingStatus.value = "AI 自动填充已应用；头像/背景生成失败：${error.message ?: "未知错误"}"
            } finally {
                autoFillImageJob = null
            }
        }
    }

    fun clearAutoFillDraft() {
        autoFillGenerationToken += 1
        autoFillJob?.cancel()
        autoFillJob = null
        _autoFillState.value = CharacterAutoFillUiState()
    }

    fun generateRewriteDraft(userInput: String, modelId: String? = null) {
        if (userInput.isBlank()) {
            _rewriteState.value = CharacterRewriteUiState(error = "请输入改写要求")
            return
        }
        val selectedModel = modelId?.let { id -> _autoFillModels.value.firstOrNull { it.id == id } }
        if (modelId != null && selectedModel == null) {
            _rewriteState.value = CharacterRewriteUiState(error = "所选模型不可用，请重新选择")
            refreshAutoFillModels()
            return
        }
        rewriteJob?.cancel()
        rewriteGenerationToken += 1
        val generationToken = rewriteGenerationToken
        val statusText = selectedModel?.autoFillLabel()
            ?.let { "正在使用 $it 改写角色卡候选" }
            ?: "正在使用默认模型改写角色卡候选"
        _rewriteState.value = CharacterRewriteUiState(
            isGenerating = true,
            statusText = statusText
        )
        rewriteJob = viewModelScope.launch {
            var latestRawText = ""
            try {
                val draft = characterRewriteService.rewriteStreaming(
                    userInput = userInput,
                    currentCard = buildCurrentCard(markDirty = false),
                    modelOverride = selectedModel,
                    onRawText = { rawText ->
                        latestRawText = rawText
                        if (generationToken == rewriteGenerationToken) {
                            _rewriteState.value = CharacterRewriteUiState(
                                isGenerating = true,
                                streamingText = rawText,
                                statusText = statusText
                            )
                        }
                    }
                )
                if (generationToken == rewriteGenerationToken) {
                    _rewriteState.value = CharacterRewriteUiState(
                        draft = draft,
                        streamingText = latestRawText
                    )
                }
            } catch (_: CancellationException) {
                if (generationToken == rewriteGenerationToken) {
                    _rewriteState.value = CharacterRewriteUiState(
                        error = "已取消生成",
                        streamingText = latestRawText,
                        statusText = "已取消生成"
                    )
                }
            } catch (error: Throwable) {
                if (generationToken == rewriteGenerationToken) {
                    _rewriteState.value = CharacterRewriteUiState(
                        error = error.message ?: "AI 自动改写失败",
                        streamingText = latestRawText
                    )
                }
            } finally {
                if (generationToken == rewriteGenerationToken) {
                    rewriteJob = null
                }
            }
        }
    }

    fun cancelRewriteGeneration() {
        val state = _rewriteState.value
        if (!state.isGenerating) return
        rewriteJob?.cancel()
        _rewriteState.value = state.copy(
            isGenerating = false,
            draft = null,
            error = "已取消生成",
            statusText = "已取消生成"
        )
    }

    fun applyRewriteDraft() {
        val draft = _rewriteState.value.draft ?: return
        val merged = characterRewriteService.mergeInto(buildCurrentCard(markDirty = false), draft)
        name = merged.name
        greeting = merged.greeting
        basicSetting = merged.basicSetting
        defaultImagePrompt = merged.defaultImagePrompt
        freeformCharacterText = merged.freeformCharacterText
        if (editMode == CharacterEditMode.STRUCTURED) {
            charactersList.clear()
            charactersList.addAll(merged.characters)
        }
        _rewriteState.value = CharacterRewriteUiState()
    }

    fun clearRewriteDraft() {
        rewriteGenerationToken += 1
        rewriteJob?.cancel()
        rewriteJob = null
        _rewriteState.value = CharacterRewriteUiState()
    }

    private fun startBackgroundIndex(card: CharacterCard) {
        indexingJob?.cancel()
        indexingJob = ChatBarApp.instance.applicationScope.launch {
            val cardForIndex = ChatBarApp.instance.presetCatalogService.repairPresetCharacterResources(card)
            val total = cardForIndex.customDocuments.size
            if (total == 0) {
                ChatBarApp.instance.ragRepository.deleteChunksBySource(ChunkSourceType.DOCUMENT, cardForIndex.id)
                persistRagIndexState(cardForIndex.id, RagIndexStatus.COMPLETE, 0, 0, "无参考文档，已跳过文档 RAG")
                return@launch
            }
            val embeddingConfig = modelResolver.embeddingModel(settingsRepository.getAppSettings())
            if (embeddingConfig == null) {
                persistRagIndexState(cardForIndex.id, RagIndexStatus.FAILED, 0, total, "未配置全局嵌入模型，无法建立 RAG 索引")
                return@launch
            }

            try {
                AiBackgroundWorkManager.run(cardForIndex.id) {
                persistRagIndexState(cardForIndex.id, RagIndexStatus.INDEXING, 0, total, "正在检查文档索引状态")
                val existingChunks = ChatBarApp.instance.ragRepository
                    .getAllChunksForCharacter(cardForIndex.id)
                    .filter { it.sourceType == ChunkSourceType.DOCUMENT }
                    .groupBy { it.metadata["originalDocId"] }

                val initialDocs = cardForIndex.customDocuments.map { doc ->
                    val file = File(doc.filePath)
                    if (!file.exists()) {
                        doc.copy(ragStatus = DocumentRagStatus.FAILED.name, ragError = "文件不存在")
                    } else {
                        val contentHash = ragManager.hashContent(file.readText())
                        val indexedHash = existingChunks[doc.id]?.firstOrNull()?.metadata?.get("contentHash")
                            ?: doc.indexedHash
                        val indexedEmbeddingKey = existingChunks[doc.id]?.firstOrNull()?.metadata?.get("embeddingKey")
                        val currentEmbeddingKey = ragManager.embeddingKey(embeddingConfig)
                        if (indexedHash == contentHash && indexedEmbeddingKey == currentEmbeddingKey && !existingChunks[doc.id].isNullOrEmpty()) {
                            doc.copy(
                                contentHash = contentHash,
                                indexedHash = contentHash,
                                ragStatus = DocumentRagStatus.INDEXED.name,
                                ragChunkCount = existingChunks[doc.id]?.size ?: doc.ragChunkCount,
                                ragError = null
                            )
                        } else {
                            doc.copy(
                                contentHash = contentHash,
                                ragStatus = DocumentRagStatus.PENDING.name,
                                ragError = null
                            )
                        }
                    }
                }

                val docsById = initialDocs.associateBy { it.id }.toMutableMap()
                val docsToIndex = initialDocs.filter { it.ragStatus != DocumentRagStatus.INDEXED.name }
                val skipped = total - docsToIndex.size
                val progressMutex = Mutex()
                val semaphore = Semaphore(2)
                var done = skipped
                var failed = initialDocs.count { it.ragStatus == DocumentRagStatus.FAILED.name }

                persistRagIndexSnapshot(
                    cardForIndex.id,
                    docsById.values.toList(),
                    RagIndexStatus.INDEXING,
                    done,
                    total,
                    "增量索引：跳过 $skipped 个未变化文档，待重建 ${docsToIndex.size} 个"
                )

                docsToIndex.map { doc ->
                    async {
                        semaphore.withPermit {
                            val resultDoc = try {
                                val file = File(doc.filePath)
                                if (!file.exists()) {
                                    doc.copy(ragStatus = DocumentRagStatus.FAILED.name, ragError = "文件不存在")
                                } else {
                                    val result = ragManager.indexDocument(doc, file.readText(), cardForIndex.id, embeddingConfig)
                                    doc.copy(
                                        contentHash = result.contentHash,
                                        indexedHash = result.contentHash,
                                        ragStatus = DocumentRagStatus.INDEXED.name,
                                        ragChunkCount = result.chunkCount,
                                        ragIndexedAt = System.currentTimeMillis(),
                                        ragError = null
                                    )
                                }
                            } catch (e: Exception) {
                                doc.copy(ragStatus = DocumentRagStatus.FAILED.name, ragError = e.message)
                            }

                            progressMutex.withLock {
                                docsById[resultDoc.id] = resultDoc
                                done++
                                if (resultDoc.ragStatus == DocumentRagStatus.FAILED.name) failed++
                                val status = if (failed > 0) RagIndexStatus.FAILED else RagIndexStatus.INDEXING
                                val message = "索引进度：$done/$total，跳过 $skipped，失败 $failed"
                                _indexingStatus.value = message
                                if (done % 3 == 0 || done == total || resultDoc.ragStatus == DocumentRagStatus.FAILED.name) {
                                    persistRagIndexSnapshot(cardForIndex.id, docsById.values.toList(), status, done, total, message)
                                }
                            }
                        }
                    }
                }.awaitAll()

                val finalStatus = if (failed > 0) RagIndexStatus.FAILED else RagIndexStatus.COMPLETE
                persistRagIndexSnapshot(
                    cardForIndex.id,
                    docsById.values.toList(),
                    finalStatus,
                    done,
                    total,
                    if (failed > 0) "RAG 索引完成但有 $failed 个文档失败" else "RAG 索引完成：跳过 $skipped 个未变化文档"
                )
                }
            } catch (e: Exception) {
                persistRagIndexState(cardForIndex.id, RagIndexStatus.FAILED, 0, total, "RAG 索引失败: ${e.message}")
            }
        }
    }

    private suspend fun persistRagIndexState(
        cardId: String,
        status: RagIndexStatus,
        done: Int,
        total: Int,
        message: String
    ) {
        val current = characterRepository.getById(cardId) ?: return
        val updated = current.copy(
            ragIndexStatus = status.name,
            ragIndexDone = done,
            ragIndexTotal = total,
            ragIndexMessage = message,
            ragIndexedAt = if (status == RagIndexStatus.COMPLETE) System.currentTimeMillis() else current.ragIndexedAt
        )
        characterRepository.save(updated)
        _characterCard.value = updated
        _indexingStatus.value = message
    }

    private suspend fun persistRagIndexSnapshot(
        cardId: String,
        docs: List<DocumentInfo>,
        status: RagIndexStatus,
        done: Int,
        total: Int,
        message: String
    ) {
        val current = characterRepository.getById(cardId) ?: return
        val orderedDocs = current.customDocuments.map { old -> docs.firstOrNull { it.id == old.id } ?: old }
        val updated = current.copy(
            customDocuments = orderedDocs,
            ragIndexStatus = status.name,
            ragIndexDone = done,
            ragIndexTotal = total,
            ragIndexMessage = message,
            ragIndexedAt = if (status == RagIndexStatus.COMPLETE) System.currentTimeMillis() else current.ragIndexedAt
        )
        characterRepository.save(updated)
        _characterCard.value = updated
        _indexingStatus.value = message
        withContext(Dispatchers.Main) {
            if (_characterCard.value?.id == cardId) {
                documentsList.clear()
                documentsList.addAll(orderedDocs)
            }
        }
    }

    fun addDocument(fileName: String, content: String) {
        viewModelScope.launch {
            val docDir = File(ChatBarApp.instance.filesDir, "documents")
            if (!docDir.exists()) docDir.mkdirs()

            val localFile = File(docDir, "${System.currentTimeMillis()}_$fileName")
            localFile.writeText(content)

            documentsList.add(DocumentInfo.create(fileName, localFile.absolutePath, "txt"))
            markRagIndexDirty("文档已添加，保存角色卡后将重建 RAG 索引")
        }
    }

    fun addWorldBookEntry(entry: WorldBookEntry) {
        worldBookEntries.add(entry)
    }

    fun updateWorldBookEntry(index: Int, entry: WorldBookEntry) {
        if (index in worldBookEntries.indices) worldBookEntries[index] = entry
    }

    fun deleteWorldBookEntry(index: Int) {
        if (index in worldBookEntries.indices) worldBookEntries.removeAt(index)
    }

    fun toggleWorldBookEntry(index: Int) {
        if (index in worldBookEntries.indices) {
            val e = worldBookEntries[index]
            worldBookEntries[index] = e.copy(enabled = !e.enabled)
        }
    }

    fun toggleWorldBookBinding(id: String) {
        if (id in selectedWorldBookIds) selectedWorldBookIds.remove(id) else selectedWorldBookIds.add(id)
    }

    fun updateDocument(doc: DocumentInfo, newName: String, newContent: String) {
        viewModelScope.launch {
            val file = File(doc.filePath)
            if (newName != doc.fileName) {
                val parent = file.parentFile ?: return@launch
                val extension = doc.fileName.substringAfterLast('.', "txt")
                val newFile = File(parent, "${System.currentTimeMillis()}_$newName")
                newFile.writeText(newContent)
                file.delete()
                val index = documentsList.indexOfFirst { it.id == doc.id }
                if (index >= 0) {
                    documentsList[index] = doc.copy(
                        fileName = newName,
                        filePath = newFile.absolutePath,
                        fileType = extension,
                        ragStatus = DocumentRagStatus.PENDING.name,
                        ragChunkCount = 0,
                        ragIndexedAt = null,
                        ragError = null
                    )
                }
            } else {
                file.writeText(newContent)
                val index = documentsList.indexOfFirst { it.id == doc.id }
                if (index >= 0) {
                    documentsList[index] = doc.copy(
                        ragStatus = DocumentRagStatus.PENDING.name,
                        ragChunkCount = 0,
                        ragIndexedAt = null,
                        ragError = null
                    )
                }
            }
            markRagIndexDirty("文档已编辑，保存角色卡后将重建 RAG 索引")
        }
    }

    private fun buildCurrentCard(markDirty: Boolean): CharacterCard {
        val now = System.currentTimeMillis()
        val base = _characterCard.value
        val dirtyStatus = if (markDirty) RagIndexStatus.NOT_INDEXED.name else base?.ragIndexStatus ?: RagIndexStatus.NOT_INDEXED.name
        val dirtyMessage = if (markDirty) "RAG 索引待重建" else base?.ragIndexMessage
        return base?.copy(
            name = name,
            greeting = greeting,
            alternateGreetings = alternateGreetings,
            avatar = avatar,
            chatBackground = chatBackground,
            editMode = editMode,
            basicSetting = basicSetting,
            freeformCharacterText = freeformCharacterText,
            defaultImagePrompt = defaultImagePrompt,
            systemPrompt = systemPrompt,
            postHistoryInstructions = postHistoryInstructions,
            mesExample = mesExample,
            creatorNotes = creatorNotes,
            worldBookIds = selectedWorldBookIds.distinct(),
            characterBook = null,
            boundWorldBookId = null,
            characters = charactersList.toList(),
            customDocuments = documentsList.toList(),
            ragIndexStatus = dirtyStatus,
            ragIndexDone = if (markDirty) 0 else base.ragIndexDone,
            ragIndexTotal = documentsList.size,
            ragIndexMessage = dirtyMessage,
            ragIndexedAt = if (markDirty) null else base.ragIndexedAt,
            updatedAt = now
        ) ?: CharacterCard(
            id = characterId ?: java.util.UUID.randomUUID().toString(),
            name = name,
            greeting = greeting,
            alternateGreetings = alternateGreetings,
            avatar = avatar,
            chatBackground = chatBackground,
            editMode = editMode,
            basicSetting = basicSetting,
            freeformCharacterText = freeformCharacterText,
            defaultImagePrompt = defaultImagePrompt,
            systemPrompt = systemPrompt,
            postHistoryInstructions = postHistoryInstructions,
            mesExample = mesExample,
            creatorNotes = creatorNotes,
            worldBookIds = selectedWorldBookIds.distinct(),
            characterBook = null,
            boundWorldBookId = null,
            characters = charactersList.toList(),
            customDocuments = documentsList.toList(),
            ragIndexStatus = dirtyStatus,
            ragIndexDone = 0,
            ragIndexTotal = documentsList.size,
            ragIndexMessage = dirtyMessage,
            ragIndexedAt = null,
            createdAt = now,
            updatedAt = now
        )
    }

    private suspend fun markRagIndexDirty(message: String) {
        val current = _characterCard.value ?: return
        val updated = current.copy(
            ragIndexStatus = RagIndexStatus.NOT_INDEXED.name,
            ragIndexDone = 0,
            ragIndexTotal = documentsList.size,
            ragIndexMessage = message,
            ragIndexedAt = null
        )
        _characterCard.value = updated
        _indexingStatus.value = message
    }

    fun deleteDocument(doc: DocumentInfo) {
        viewModelScope.launch {
            File(doc.filePath).delete()
            documentsList.remove(doc)
            ChatBarApp.instance.ragRepository.deleteChunksByDocumentId(doc.id)
            markRagIndexDirty("文档已删除，保存角色卡后将重建 RAG 索引")
        }
    }

    fun clearAllDocuments() {
        viewModelScope.launch {
            indexingJob?.cancel()
            val docs = documentsList.toList()
            documentsList.clear()
            val now = System.currentTimeMillis()
            val current = _characterCard.value
            if (current != null) {
                val updated = current.copy(
                    customDocuments = emptyList(),
                    ragIndexStatus = RagIndexStatus.COMPLETE.name,
                    ragIndexDone = 0,
                    ragIndexTotal = 0,
                    ragIndexMessage = "已清空当前角色卡文档，保存角色卡后将清理 RAG 索引",
                    ragIndexedAt = now,
                    updatedAt = now
                )
                _characterCard.value = updated
            } else {
                _characterCard.value = buildCurrentCard(markDirty = true).copy(
                    customDocuments = emptyList(),
                    ragIndexStatus = RagIndexStatus.COMPLETE.name,
                    ragIndexDone = 0,
                    ragIndexTotal = 0,
                    ragIndexMessage = "已清空当前角色卡文档，保存角色卡后将清理 RAG 索引",
                    ragIndexedAt = now
                )
            }
            _indexingStatus.value = "已清空当前角色卡文档，正在后台清理 RAG 索引"

            ChatBarApp.instance.applicationScope.launch {
                for (doc in docs) {
                    File(doc.filePath).delete()
                    ChatBarApp.instance.ragRepository.deleteChunksByDocumentId(doc.id)
                }
                _indexingStatus.value = "当前角色卡文档和对应 RAG 索引已清空"
            }
        }
    }

    fun importDocumentsFromFolder(treeUri: Uri) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val context = ChatBarApp.instance
                val contentResolver = context.contentResolver
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )
                val filesToProcess = mutableListOf<Pair<Uri, String>>()

                contentResolver.query(childrenUri, projection, null, null, null)?.use {
                    val idCol = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (it.moveToNext()) {
                        val mimeType = it.getString(mimeCol)
                        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) continue
                        val displayName = it.getString(nameCol) ?: "unknown"
                        val lowerName = displayName.lowercase()
                        if (lowerName.endsWith(".txt") || lowerName.endsWith(".md") || lowerName.endsWith(".json")) {
                            val childId = it.getString(idCol)
                            filesToProcess.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, childId) to displayName)
                        }
                    }
                }

                if (filesToProcess.isNotEmpty()) {
                    val docDir = File(context.filesDir, "documents")
                    if (!docDir.exists()) docDir.mkdirs()

                    filesToProcess.forEachIndexed { index, (fileUri, displayName) ->
                        try {
                            _indexingStatus.value = "正在导入 ${index + 1}/${filesToProcess.size}: $displayName"
                            val content = contentResolver.openInputStream(fileUri)?.bufferedReader()?.use { it.readText() }
                            if (!content.isNullOrBlank()) {
                                val localFile = File(docDir, "${System.currentTimeMillis()}_$displayName")
                                localFile.writeText(content)
                                val extension = displayName.substringAfterLast('.', "txt")
                                documentsList.add(DocumentInfo.create(displayName, localFile.absolutePath, extension))
                            }
                        } catch (e: Exception) {
                            _indexingStatus.value = "读取文档 [$displayName] 失败: ${e.message}"
                        }
                    }

                    markRagIndexDirty("已导入 ${filesToProcess.size} 个文档，保存角色卡后将重建 RAG 索引")
                }
            } catch (e: Exception) {
                _indexingStatus.value = "批量导入失败: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun copyUriToLocalFile(uri: Uri, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val context = ChatBarApp.instance
                val contentResolver = context.contentResolver
                val imagesDir = File(context.filesDir, "images")
                if (!imagesDir.exists()) imagesDir.mkdirs()

                val extension = when (contentResolver.getType(uri)) {
                    "image/png" -> "png"
                    "image/gif" -> "gif"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }

                val localFile = File(imagesDir, "img_${System.currentTimeMillis()}.$extension")
                contentResolver.openInputStream(uri)?.use { input ->
                    localFile.outputStream().use { output -> input.copyTo(output) }
                }
                onSuccess(localFile.absolutePath)
            } catch (_: Exception) {
            } finally {
                _isSaving.value = false
            }
        }
    }
}

private fun ModelConfig.autoFillLabel(): String =
    displayName.ifBlank { modelName.ifBlank { id } }
