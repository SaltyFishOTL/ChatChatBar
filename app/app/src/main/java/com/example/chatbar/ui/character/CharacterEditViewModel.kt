package com.example.chatbar.ui.character

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
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
import com.example.chatbar.domain.image.ImageCropFractionRect
import com.example.chatbar.domain.image.ImageFileEncoder
import com.example.chatbar.domain.image.NovelAiImageEvent
import com.example.chatbar.domain.image.NovelAiImageSizePolicy
import com.example.chatbar.domain.image.hasImageDesignSource
import com.example.chatbar.domain.search.ResearchDebugSnapshot
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
import kotlin.math.roundToInt

data class CharacterCoverImageUiState(
    val isGenerating: Boolean = false,
    val preview: ByteArray? = null,
    val progress: Float = 0f,
    val path: String? = null,
    val promptText: String = "",
    val error: String? = null,
    val statusText: String = ""
)

data class CharacterAiOutputUiState(
    val key: String,
    val title: String,
    val text: String
)

data class CharacterAutoFillUiState(
    val isGenerating: Boolean = false,
    val draft: CharacterAutoFillDraft? = null,
    val error: String? = null,
    val streamingText: String = "",
    val statusText: String = "",
    val progressLines: List<String> = emptyList(),
    val researchDebug: ResearchDebugSnapshot? = null,
    val visibleOutputs: List<CharacterAiOutputUiState> = emptyList(),
    val modelId: String? = null,
    val coverImage: CharacterCoverImageUiState = CharacterCoverImageUiState()
)

data class CharacterRewriteUiState(
    val isGenerating: Boolean = false,
    val draft: CharacterRewriteDraft? = null,
    val error: String? = null,
    val streamingText: String = "",
    val statusText: String = "",
    val progressLines: List<String> = emptyList(),
    val researchDebug: ResearchDebugSnapshot? = null,
    val visibleOutputs: List<CharacterAiOutputUiState> = emptyList(),
    val coverImage: CharacterCoverImageUiState = CharacterCoverImageUiState()
)

private enum class CoverImageTarget { Current, AutoFill, Rewrite }

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
    private var autoFillGenerationToken = 0
    private var rewriteJob: Job? = null
    private var rewriteGenerationToken = 0
    private var coverImageJob: Job? = null
    private var coverImageGenerationToken = 0
    private var coverImageTarget: CoverImageTarget? = null

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

    private val _coverImageState = MutableStateFlow(CharacterCoverImageUiState())
    val coverImageState: StateFlow<CharacterCoverImageUiState> = _coverImageState.asStateFlow()

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
    var momentsEnabled by mutableStateOf(false)
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
                    momentsEnabled = card.momentsEnabled
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
        if (_characterCard.value?.isCommunityDownload == true) {
            _indexingStatus.value = "下载角色卡只读，请复制后编辑"
            return
        }
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
            if (card.momentsEnabled) ChatBarApp.instance.momentScheduler.kick("character-save")
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

    fun generateAutoFillDraft(userInput: String, modelId: String? = null, imagePath: String? = null) {
        if (editMode != CharacterEditMode.STRUCTURED) {
            _autoFillState.value = CharacterAutoFillUiState(error = "AI 自动填充仅支持分段模式")
            return
        }
        val sourceImagePath = imagePath?.takeIf(String::isNotBlank)
        if (userInput.isBlank() && sourceImagePath == null) {
            _autoFillState.value = CharacterAutoFillUiState(error = "请输入角色信息或上传图片")
            return
        }
        val selectedModel = modelId?.let { id -> _autoFillModels.value.firstOrNull { it.id == id } }
        if (modelId != null && selectedModel == null) {
            _autoFillState.value = CharacterAutoFillUiState(error = "所选模型不可用，请重新选择")
            refreshAutoFillModels()
            return
        }
        val selectedModelId = selectedModel?.id
        autoFillGenerationToken += 1
        autoFillJob?.cancel()
        deleteAutoFillCandidateImage(_autoFillState.value.coverImage.path)
        val generationToken = autoFillGenerationToken
        val statusText = selectedModel?.autoFillLabel()
            ?.let { "正在使用 $it 生成角色卡候选" }
            ?: "正在使用默认模型生成角色卡候选"
        _autoFillState.value = CharacterAutoFillUiState(
            isGenerating = true,
            statusText = statusText,
            progressLines = listOf(statusText),
            modelId = selectedModelId
        )
        autoFillJob = viewModelScope.launch {
            var latestRawText = ""
            var currentStatusText = statusText
            var progressLines = listOf(statusText)
            var latestResearchDebug: ResearchDebugSnapshot? = null
            var latestVisibleOutputs = emptyList<CharacterAiOutputUiState>()
            fun rememberVisibleOutput(key: String, title: String, text: String) {
                latestVisibleOutputs = latestVisibleOutputs.upsertAiOutput(key, title, text)
                updateAutoFillStateIfCurrent(generationToken) {
                    it.copy(visibleOutputs = latestVisibleOutputs)
                }
            }
            try {
                val imageBase64s = sourceImagePath?.let { path ->
                    currentStatusText = "正在读取上传图片"
                    progressLines = progressLines.appendProgressLine(currentStatusText)
                    updateAutoFillStateIfCurrent(generationToken) {
                        it.copy(
                            isGenerating = true,
                            streamingText = latestRawText,
                            statusText = currentStatusText,
                            progressLines = progressLines,
                            researchDebug = latestResearchDebug,
                            visibleOutputs = latestVisibleOutputs,
                            modelId = selectedModelId
                        )
                    }
                    listOf(ImageFileEncoder.encodeToJpegBase64(path))
                }.orEmpty()
                val draft = characterAutoFillService.generateStreaming(
                    userInput = userInput,
                    currentCard = buildCurrentCard(markDirty = false),
                    modelOverride = selectedModel,
                    imageBase64s = imageBase64s,
                    onStatus = { nextStatus ->
                        currentStatusText = nextStatus
                        progressLines = progressLines.appendProgressLine(nextStatus)
                        if (generationToken == autoFillGenerationToken) {
                            _autoFillState.value = CharacterAutoFillUiState(
                                isGenerating = true,
                                streamingText = latestRawText,
                                statusText = nextStatus,
                                progressLines = progressLines,
                                researchDebug = latestResearchDebug,
                                visibleOutputs = latestVisibleOutputs,
                                modelId = selectedModelId
                            )
                        }
                    },
                    onResearchDebug = { snapshot ->
                        latestResearchDebug = snapshot
                        if (generationToken == autoFillGenerationToken) {
                            _autoFillState.value = _autoFillState.value.copy(researchDebug = snapshot)
                        }
                    },
                    onVisibleOutput = { key, title, text ->
                        rememberVisibleOutput(key, title, text)
                    },
                    onRawText = { rawText ->
                        latestRawText = rawText
                        if (generationToken == autoFillGenerationToken) {
                            _autoFillState.value = CharacterAutoFillUiState(
                                isGenerating = true,
                                streamingText = rawText,
                                statusText = currentStatusText,
                                progressLines = progressLines,
                                researchDebug = latestResearchDebug,
                                visibleOutputs = latestVisibleOutputs,
                                modelId = selectedModelId
                            )
                        }
                    }
                )
                if (generationToken == autoFillGenerationToken) {
                    _autoFillState.value = CharacterAutoFillUiState(
                        draft = draft,
                        streamingText = latestRawText,
                        progressLines = progressLines.appendProgressLine("角色卡候选已生成"),
                        researchDebug = latestResearchDebug,
                        visibleOutputs = latestVisibleOutputs,
                        modelId = selectedModelId
                    )
                }
            } catch (_: CancellationException) {
                if (generationToken == autoFillGenerationToken) {
                    _autoFillState.value = CharacterAutoFillUiState(
                        error = "已取消生成",
                        streamingText = latestRawText,
                        statusText = "已取消生成",
                        progressLines = progressLines.appendProgressLine("已取消生成"),
                        researchDebug = latestResearchDebug,
                        visibleOutputs = latestVisibleOutputs,
                        modelId = selectedModelId
                    )
                }
            } catch (error: Throwable) {
                if (generationToken == autoFillGenerationToken) {
                    _autoFillState.value = CharacterAutoFillUiState(
                        error = error.message ?: "AI 自动填充失败",
                        streamingText = latestRawText,
                        progressLines = progressLines.appendProgressLine("生成失败"),
                        researchDebug = latestResearchDebug,
                        visibleOutputs = latestVisibleOutputs,
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
        autoFillGenerationToken += 1
        autoFillJob?.cancel()
        deleteAutoFillCandidateImage(state.coverImage.path)
        _autoFillState.value = state.copy(
            isGenerating = false,
            draft = null,
            error = "已取消生成",
            statusText = "已取消生成",
            progressLines = state.progressLines.appendProgressLine("已取消生成"),
            coverImage = CharacterCoverImageUiState()
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
        name = merged.name
        greeting = merged.greeting
        basicSetting = merged.basicSetting
        defaultImagePrompt = merged.defaultImagePrompt
        charactersList.clear()
        charactersList.addAll(merged.characters)
        state.coverImage.path?.takeIf(String::isNotBlank)?.let { path ->
            avatar = path
            chatBackground = path
        }
        _autoFillState.value = CharacterAutoFillUiState()
    }

    fun generateCurrentCoverImage(modelId: String? = null) {
        val (selectedModel, selectedModelId) = resolveCoverModelSelection(modelId) { error ->
            _coverImageState.value = CharacterCoverImageUiState(error = error)
        } ?: return
        startCoverImageGeneration(
            target = CoverImageTarget.Current,
            card = buildCurrentCard(markDirty = false),
            modelOverride = selectedModel,
            selectedModelId = selectedModelId
        )
    }

    fun generateAutoFillCoverImageCandidate(modelId: String? = null) {
        val state = _autoFillState.value
        val draft = state.draft ?: run {
            _autoFillState.value = state.copy(
                coverImage = CharacterCoverImageUiState(error = "请先生成角色卡候选")
            )
            return
        }
        val (selectedModel, selectedModelId) = resolveCoverModelSelection(modelId) { error ->
            _autoFillState.value = _autoFillState.value.copy(
                coverImage = CharacterCoverImageUiState(error = error)
            )
        } ?: return
        val mergedCard = characterAutoFillService.mergeInto(buildCurrentCard(markDirty = false), draft)
        startCoverImageGeneration(
            target = CoverImageTarget.AutoFill,
            card = mergedCard,
            modelOverride = selectedModel,
            selectedModelId = selectedModelId,
            previousCandidatePath = state.coverImage.path
        )
    }

    fun generateRewriteCoverImageCandidate(modelId: String? = null) {
        val state = _rewriteState.value
        val draft = state.draft ?: run {
            _rewriteState.value = state.copy(
                coverImage = CharacterCoverImageUiState(error = "请先生成改写候选")
            )
            return
        }
        val (selectedModel, selectedModelId) = resolveCoverModelSelection(modelId) { error ->
            _rewriteState.value = _rewriteState.value.copy(
                coverImage = CharacterCoverImageUiState(error = error)
            )
        } ?: return
        val mergedCard = characterRewriteService.mergeInto(buildCurrentCard(markDirty = false), draft)
        startCoverImageGeneration(
            target = CoverImageTarget.Rewrite,
            card = mergedCard,
            modelOverride = selectedModel,
            selectedModelId = selectedModelId,
            previousCandidatePath = state.coverImage.path
        )
    }

    fun cancelCoverImageGeneration() {
        val target = coverImageTarget ?: return
        coverImageGenerationToken += 1
        coverImageJob?.cancel()
        coverImageJob = null
        putCoverImageState(
            target,
            currentCoverImageState(target).copy(
                isGenerating = false,
                error = "已取消封面设计",
                statusText = "已取消封面设计"
            )
        )
        coverImageTarget = null
    }

    private fun resolveCoverModelSelection(
        modelId: String?,
        onError: (String) -> Unit
    ): Pair<ModelConfig?, String?>? {
        if (modelId == null) return null to null
        val selectedModel = _autoFillModels.value.firstOrNull { it.id == modelId }
        if (selectedModel == null) {
            onError("所选模型不可用，请重新选择")
            refreshAutoFillModels()
            return null
        }
        return selectedModel to selectedModel.id
    }

    private fun startCoverImageGeneration(
        target: CoverImageTarget,
        card: CharacterCard,
        modelOverride: ModelConfig?,
        selectedModelId: String?,
        previousCandidatePath: String? = null
    ) {
        if (!card.hasImageDesignSource()) {
            putCoverImageState(
                target,
                CharacterCoverImageUiState(
                    error = "请先填写角色卡内容，再设计封面",
                    statusText = "封面未生成"
                )
            )
            return
        }
        coverImageGenerationToken += 1
        val generationToken = coverImageGenerationToken
        coverImageJob?.cancel()
        if (target != CoverImageTarget.Current) {
            deleteAutoFillCandidateImage(previousCandidatePath)
        }
        coverImageTarget = target
        putCoverImageState(
            target,
            CharacterCoverImageUiState(
                isGenerating = true,
                statusText = "正在设计封面 Prompt"
            )
        )
        coverImageJob = viewModelScope.launch {
            try {
                val token = withContext(Dispatchers.IO) { novelAiCredentials.load() }
                val settings = settingsRepository.getAppSettings()
                val model = modelOverride ?: modelResolver.resolveChatModel(selectedModelId, settings)
                val imageRatioError = NovelAiImageSizePolicy.validationError(settings.novelAiImageAspectRatio)
                if (token == null || model == null || model.apiKey.isBlank()) {
                    val missing = mutableListOf<String>()
                    if (token == null) missing += "NovelAI Token"
                    if (model == null || model.apiKey.isBlank()) missing += "对话模型/API Key"
                    updateCoverImageStateIfCurrent(generationToken, target) {
                        it.copy(
                            isGenerating = false,
                            error = "缺少${missing.joinToString("、")}，未生成封面",
                            statusText = "封面未生成"
                        )
                    }
                    return@launch
                }
                if (imageRatioError != null) {
                    updateCoverImageStateIfCurrent(generationToken, target) {
                        it.copy(
                            isGenerating = false,
                            error = imageRatioError,
                            statusText = "封面未生成"
                        )
                    }
                    return@launch
                }
                AiBackgroundWorkManager.run(card.id) {
                    updateCoverImageStateIfCurrent(generationToken, target) {
                        it.copy(
                            isGenerating = true,
                            statusText = "正在设计封面 Prompt"
                        )
                    }
                    val prompt = novelAiPromptDesigner.designForCharacterCard(card, model) { promptDraft ->
                        updateCoverImageStateIfCurrent(generationToken, target) {
                            it.copy(
                                promptText = promptDraft,
                                statusText = "正在设计封面 Prompt"
                            )
                        }
                    }
                    val imageSize = NovelAiImageSizePolicy.resolve(settings.novelAiImageAspectRatio, prompt.sizePreset)
                    updateCoverImageStateIfCurrent(generationToken, target) {
                        it.copy(statusText = "正在调用 NovelAI 生成封面")
                    }
                    val seed = novelAiImageService.newSeed()
                    var finalImage: ByteArray? = null
                    novelAiImageService.generate(token, prompt, seed, imageSize).collect { event ->
                        when (event) {
                            is NovelAiImageEvent.Intermediate -> {
                                finalImage = event.image
                                updateCoverImageStateIfCurrent(generationToken, target) {
                                    it.copy(
                                        preview = event.image,
                                        progress = event.progress,
                                        statusText = "正在流式生成封面"
                                    )
                                }
                            }
                            is NovelAiImageEvent.Final -> {
                                finalImage = event.image
                                updateCoverImageStateIfCurrent(generationToken, target) {
                                    it.copy(
                                        preview = event.image,
                                        progress = 1f,
                                        statusText = "正在保存封面"
                                    )
                                }
                            }
                            is NovelAiImageEvent.Error -> error(event.message)
                        }
                    }
                    if (generationToken != coverImageGenerationToken) return@run
                    val bytes = finalImage ?: error("NovelAI 未返回最终图片")
                    val path = withContext(Dispatchers.IO) {
                        novelAiImageStorage.save(card.id, bytes)
                    }
                    if (generationToken != coverImageGenerationToken) {
                        withContext(Dispatchers.IO) {
                            novelAiImageStorage.deleteIfOwned(path)
                        }
                        return@run
                    }
                    if (target == CoverImageTarget.Current) {
                        avatar = path
                        chatBackground = path
                    }
                    updateCoverImageStateIfCurrent(generationToken, target) {
                        it.copy(
                            isGenerating = false,
                            path = path,
                            preview = bytes,
                            progress = 1f,
                            statusText = if (target == CoverImageTarget.Current) {
                                "封面已生成并设为头像和聊天背景"
                            } else {
                                "封面候选已生成"
                            }
                        )
                    }
                }
            } catch (_: CancellationException) {
                // State is updated by cancelCoverImageGeneration or the next generation.
            } catch (error: Throwable) {
                updateCoverImageStateIfCurrent(generationToken, target) {
                    it.copy(
                        isGenerating = false,
                        error = error.message ?: "封面生成失败",
                        statusText = "封面生成失败"
                    )
                }
            } finally {
                if (generationToken == coverImageGenerationToken) {
                    coverImageJob = null
                    coverImageTarget = null
                }
            }
        }
    }

    private fun currentCoverImageState(target: CoverImageTarget): CharacterCoverImageUiState =
        when (target) {
            CoverImageTarget.Current -> _coverImageState.value
            CoverImageTarget.AutoFill -> _autoFillState.value.coverImage
            CoverImageTarget.Rewrite -> _rewriteState.value.coverImage
        }

    private fun putCoverImageState(target: CoverImageTarget, state: CharacterCoverImageUiState) {
        when (target) {
            CoverImageTarget.Current -> _coverImageState.value = state
            CoverImageTarget.AutoFill -> {
                _autoFillState.value = _autoFillState.value.copy(coverImage = state)
            }
            CoverImageTarget.Rewrite -> {
                _rewriteState.value = _rewriteState.value.copy(coverImage = state)
            }
        }
    }

    private fun updateCoverImageStateIfCurrent(
        generationToken: Int,
        target: CoverImageTarget,
        transform: (CharacterCoverImageUiState) -> CharacterCoverImageUiState
    ) {
        if (generationToken == coverImageGenerationToken) {
            putCoverImageState(target, transform(currentCoverImageState(target)))
        }
    }

    fun clearAutoFillDraft() {
        val coverImagePath = _autoFillState.value.coverImage.path
        autoFillGenerationToken += 1
        autoFillJob?.cancel()
        autoFillJob = null
        if (coverImageTarget == CoverImageTarget.AutoFill) {
            cancelCoverImageGeneration()
        }
        deleteAutoFillCandidateImage(coverImagePath)
        _autoFillState.value = CharacterAutoFillUiState()
    }

    fun deleteTransientImage(path: String?) {
        if (path.isNullOrBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val file = File(path)
                val imagesDir = File(ChatBarApp.instance.filesDir, "images")
                val filePath = file.canonicalPath
                val imagesDirPath = imagesDir.canonicalPath
                if (file.exists() && filePath.startsWith(imagesDirPath + File.separator)) {
                    file.delete()
                }
            }
        }
    }

    private fun updateAutoFillStateIfCurrent(
        generationToken: Int,
        transform: (CharacterAutoFillUiState) -> CharacterAutoFillUiState
    ) {
        if (generationToken == autoFillGenerationToken) {
            _autoFillState.value = transform(_autoFillState.value)
        }
    }

    private fun deleteAutoFillCandidateImage(path: String?) {
        if (path.isNullOrBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            novelAiImageStorage.deleteIfOwned(path)
        }
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
            statusText = statusText,
            progressLines = listOf(statusText)
        )
        rewriteJob = viewModelScope.launch {
            var latestRawText = ""
            var currentStatusText = statusText
            var progressLines = listOf(statusText)
            var latestResearchDebug: ResearchDebugSnapshot? = null
            var latestVisibleOutputs = emptyList<CharacterAiOutputUiState>()
            fun rememberVisibleOutput(key: String, title: String, text: String) {
                latestVisibleOutputs = latestVisibleOutputs.upsertAiOutput(key, title, text)
                if (generationToken == rewriteGenerationToken) {
                    _rewriteState.value = _rewriteState.value.copy(visibleOutputs = latestVisibleOutputs)
                }
            }
            try {
                val draft = characterRewriteService.rewriteStreaming(
                    userInput = userInput,
                    currentCard = buildCurrentCard(markDirty = false),
                    modelOverride = selectedModel,
                    onStatus = { nextStatus ->
                        currentStatusText = nextStatus
                        progressLines = progressLines.appendProgressLine(nextStatus)
                        if (generationToken == rewriteGenerationToken) {
                            _rewriteState.value = CharacterRewriteUiState(
                                isGenerating = true,
                                streamingText = latestRawText,
                                statusText = nextStatus,
                                progressLines = progressLines,
                                researchDebug = latestResearchDebug,
                                visibleOutputs = latestVisibleOutputs
                            )
                        }
                    },
                    onResearchDebug = { snapshot ->
                        latestResearchDebug = snapshot
                        if (generationToken == rewriteGenerationToken) {
                            _rewriteState.value = _rewriteState.value.copy(researchDebug = snapshot)
                        }
                    },
                    onVisibleOutput = { key, title, text ->
                        rememberVisibleOutput(key, title, text)
                    },
                    onRawText = { rawText ->
                        latestRawText = rawText
                        if (generationToken == rewriteGenerationToken) {
                            _rewriteState.value = CharacterRewriteUiState(
                                isGenerating = true,
                                streamingText = rawText,
                                statusText = currentStatusText,
                                progressLines = progressLines,
                                researchDebug = latestResearchDebug,
                                visibleOutputs = latestVisibleOutputs
                            )
                        }
                    }
                )
                if (generationToken == rewriteGenerationToken) {
                    _rewriteState.value = CharacterRewriteUiState(
                        draft = draft,
                        streamingText = latestRawText,
                        progressLines = progressLines.appendProgressLine("改写候选已生成"),
                        researchDebug = latestResearchDebug,
                        visibleOutputs = latestVisibleOutputs
                    )
                }
            } catch (_: CancellationException) {
                if (generationToken == rewriteGenerationToken) {
                    _rewriteState.value = CharacterRewriteUiState(
                        error = "已取消生成",
                        streamingText = latestRawText,
                        statusText = "已取消生成",
                        progressLines = progressLines.appendProgressLine("已取消生成"),
                        researchDebug = latestResearchDebug,
                        visibleOutputs = latestVisibleOutputs
                    )
                }
            } catch (error: Throwable) {
                if (generationToken == rewriteGenerationToken) {
                    _rewriteState.value = CharacterRewriteUiState(
                        error = error.message ?: "AI 自动改写失败",
                        streamingText = latestRawText,
                        progressLines = progressLines.appendProgressLine("改写失败"),
                        researchDebug = latestResearchDebug,
                        visibleOutputs = latestVisibleOutputs
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
            statusText = "已取消生成",
            progressLines = state.progressLines.appendProgressLine("已取消生成")
        )
    }

    fun applyRewriteDraft() {
        val state = _rewriteState.value
        val draft = state.draft ?: return
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
        state.coverImage.path?.takeIf(String::isNotBlank)?.let { path ->
            avatar = path
            chatBackground = path
        }
        _rewriteState.value = CharacterRewriteUiState()
    }

    fun clearRewriteDraft() {
        rewriteGenerationToken += 1
        rewriteJob?.cancel()
        rewriteJob = null
        if (coverImageTarget == CoverImageTarget.Rewrite) {
            cancelCoverImageGeneration()
        }
        deleteAutoFillCandidateImage(_rewriteState.value.coverImage.path)
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
            momentsEnabled = momentsEnabled,
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
            momentsEnabled = momentsEnabled,
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

                val localFile = File(imagesDir, "img_${java.util.UUID.randomUUID()}.$extension")
                withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri)
                        ?: error("无法读取所选图片")
                    input.use { inputStream ->
                        localFile.outputStream().use { output -> inputStream.copyTo(output) }
                    }
                    check(localFile.exists() && localFile.length() > 0L) { "图片复制失败" }
                }
                onSuccess(localFile.absolutePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _indexingStatus.value = "拷贝图片文件失败：${e.message ?: "未知错误"}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun cropUriToLocalFile(
        uri: Uri,
        cropRect: ImageCropFractionRect,
        outputWidth: Int,
        outputHeight: Int,
        onSuccess: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val context = ChatBarApp.instance
                val imagesDir = File(context.filesDir, "images")
                if (!imagesDir.exists()) imagesDir.mkdirs()
                val localFile = File(imagesDir, "img_${java.util.UUID.randomUUID()}.jpg")

                withContext(Dispatchers.IO) {
                    val source = decodeBitmapFromUri(uri)
                    val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                    try {
                        val sourceRect = cropRect.toBitmapRect(source.width, source.height)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                        Canvas(output).drawBitmap(
                            source,
                            sourceRect,
                            RectF(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat()),
                            paint
                        )
                        localFile.outputStream().use { stream ->
                            check(output.compress(Bitmap.CompressFormat.JPEG, 92, stream)) { "图片裁剪保存失败" }
                        }
                        check(localFile.exists() && localFile.length() > 0L) { "图片裁剪保存失败" }
                    } finally {
                        source.recycle()
                        output.recycle()
                    }
                }
                onSuccess(localFile.absolutePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _indexingStatus.value = "裁剪图片失败：${e.message ?: "未知错误"}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    private fun decodeBitmapFromUri(uri: Uri): Bitmap {
        val context = ChatBarApp.instance
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("无法读取所选图片")
            input.use { stream ->
                BitmapFactory.decodeStream(stream) ?: error("图片文件无法读取")
            }
        }
    }
}

private fun ImageCropFractionRect.toBitmapRect(width: Int, height: Int): Rect {
    val safeLeft = left.coerceIn(0f, 1f)
    val safeTop = top.coerceIn(0f, 1f)
    val safeRight = right.coerceIn(safeLeft, 1f)
    val safeBottom = bottom.coerceIn(safeTop, 1f)
    val l = (safeLeft * width).roundToInt().coerceIn(0, width - 1)
    val t = (safeTop * height).roundToInt().coerceIn(0, height - 1)
    val r = (safeRight * width).roundToInt().coerceIn(l + 1, width)
    val b = (safeBottom * height).roundToInt().coerceIn(t + 1, height)
    return Rect(l, t, r, b)
}

private fun List<String>.appendProgressLine(line: String): List<String> {
    val normalized = line.replace(Regex("\\s+"), " ").trim()
    if (normalized.isBlank() || lastOrNull() == normalized) return this
    return (this + normalized).takeLast(14)
}

private fun List<CharacterAiOutputUiState>.upsertAiOutput(
    key: String,
    title: String,
    text: String
): List<CharacterAiOutputUiState> {
    if (text.isBlank()) return this
    val normalized = text.trim()
    val next = CharacterAiOutputUiState(key, title, normalized)
    val index = indexOfFirst { it.key == key }
    return if (index >= 0) {
        toMutableList().also { it[index] = next }
    } else {
        this + next
    }
}

private fun ModelConfig.autoFillLabel(): String =
    displayName.ifBlank { modelName.ifBlank { id } }
