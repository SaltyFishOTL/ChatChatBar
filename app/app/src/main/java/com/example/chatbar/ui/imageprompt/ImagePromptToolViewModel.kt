package com.example.chatbar.ui.imageprompt

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.image.NovelAiCharacterCaption
import com.example.chatbar.domain.image.NovelAiCharacterPromptDraft
import com.example.chatbar.domain.image.NovelAiCharacterPromptSource
import com.example.chatbar.domain.image.NovelAiAccountUsage
import com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry
import com.example.chatbar.domain.image.NovelAiGenerationHistoryImage
import com.example.chatbar.domain.image.NovelAiGenerationCost
import com.example.chatbar.domain.image.NovelAiGenerationSettings
import com.example.chatbar.domain.image.NovelAiHistoryApplyMode
import com.example.chatbar.domain.image.ImageProcessingService
import com.example.chatbar.domain.image.ImportedProcessImage
import com.example.chatbar.domain.image.NovelAiImageEvent
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiImageCostEstimator
import com.example.chatbar.domain.image.NovelAiImageSizePreset
import com.example.chatbar.domain.image.NovelAiPositivePromptSnapshot
import com.example.chatbar.domain.image.NovelAiPromptDesigner
import com.example.chatbar.domain.image.NovelAiPromptPlan
import com.example.chatbar.domain.image.NovelAiSeedMode
import com.example.chatbar.domain.image.NovelAiStudioDraft
import com.example.chatbar.domain.image.NovelAiStudioMetadataSelection
import com.example.chatbar.domain.image.NovelAiStudioPngMetadata
import com.example.chatbar.domain.image.NovelAiTagCandidate
import com.example.chatbar.domain.image.NovelAiTagCompletion
import com.example.chatbar.domain.image.copyPositivePrompt
import com.example.chatbar.domain.image.applyImportedMetadata
import com.example.chatbar.domain.image.novelAiHistoryImages
import com.example.chatbar.domain.image.NovelAiPngMetadataReader
import com.example.chatbar.domain.image.toRecipe
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ImagePromptToolPhase {
    IDLE, DESIGNING, READY, GENERATING, STREAMING, SAVING, FINISHED, FAILED, CANCELLED
}

data class NovelAiPromptFieldKey(val kind: String, val characterId: String? = null)

data class NovelAiTagSuggestionState(
    val field: NovelAiPromptFieldKey? = null,
    val candidates: List<NovelAiTagCandidate> = emptyList(),
    val error: String? = null,
    val loading: Boolean = false
)

data class NovelAiRecentHistoryItem(
    val entry: NovelAiGenerationHistoryEntry,
    val image: NovelAiGenerationHistoryImage
)

data class NovelAiPromptTokenState(
    val positive: Int? = null,
    val negative: Int? = null,
    val limit: Int = NovelAiImageModel.V4_5_FULL.promptTokenLimit,
    val loading: Boolean = true,
    val error: String? = null
)

data class NovelAiAccountUiState(
    val usage: NovelAiAccountUsage? = null,
    val loading: Boolean = true,
    val error: String? = null
)

data class NovelAiStudioImageImportUiState(
    val loading: Boolean = false,
    val source: ImportedProcessImage? = null,
    val metadata: NovelAiStudioPngMetadata? = null
)

data class ImagePromptToolUiState(
    val draft: NovelAiStudioDraft = NovelAiStudioDraft(),
    val draftLoaded: Boolean = false,
    val hasHistoryUndo: Boolean = false,
    val characterCards: List<CharacterCard> = emptyList(),
    val selectedCharacterCardId: String? = null,
    val models: List<ModelConfig> = emptyList(),
    val selectedModelId: String? = null,
    val modelErrors: List<String> = emptyList(),
    val modelUsable: Boolean = false,
    val phase: ImagePromptToolPhase = ImagePromptToolPhase.IDLE,
    val designStatus: String = "",
    val reasoningStream: String = "",
    val resultStream: String = "",
    val imagePreview: ByteArray? = null,
    val completedPreviews: List<ByteArray> = emptyList(),
    val imagePaths: List<String> = emptyList(),
    val recentHistoryItems: List<NovelAiRecentHistoryItem> = emptyList(),
    val selectedOutputPath: String? = null,
    val selectedOutputIndex: Int = 0,
    val imageProgress: Float = 0f,
    val applyingHistory: Boolean = false,
    val tagSuggestions: NovelAiTagSuggestionState = NovelAiTagSuggestionState(),
    val promptTokens: NovelAiPromptTokenState = NovelAiPromptTokenState(),
    val account: NovelAiAccountUiState = NovelAiAccountUiState(),
    val imageImport: NovelAiStudioImageImportUiState = NovelAiStudioImageImportUiState(),
    val error: String? = null
) {
    val isDesigning: Boolean get() = phase == ImagePromptToolPhase.DESIGNING
    val isGeneratingImage: Boolean get() = phase in setOf(
        ImagePromptToolPhase.GENERATING,
        ImagePromptToolPhase.STREAMING,
        ImagePromptToolPhase.SAVING
    )
    val isBusy: Boolean get() = isDesigning || isGeneratingImage || imageImport.loading
    val selectedRecentHistoryItem: NovelAiRecentHistoryItem?
        get() = recentHistoryItems.firstOrNull { it.image.path == selectedOutputPath }
    val canImportCharacterCard: Boolean get() = draftLoaded && !isBusy && !applyingHistory
    val canDesign: Boolean get() = !isBusy && !applyingHistory && modelUsable && selectedModelId != null && draft.imageDescription.isNotBlank()
    val canGenerate: Boolean get() = !isBusy && !applyingHistory && draft.basePrompt.isNotBlank()
    val generationCost: NovelAiGenerationCost
        get() = NovelAiImageCostEstimator.estimate(draft.activeSettings, account.usage)
}

class ImagePromptToolViewModel : ViewModel() {
    private val app = ChatBarApp.instance
    private val repository = app.novelAiStudioRepository
    private val settingsRepository = app.settingsRepository
    private val characterRepository = app.characterRepository
    private val modelResolver = app.effectiveModelResolver
    private val promptDesigner = app.novelAiPromptDesigner
    private val credentials = app.novelAiCredentialStore
    private val imageService = app.novelAiImageService
    private val accountService = app.novelAiAccountService
    private val imageStorage = app.novelAiImageStorage
    private val tagSuggestClient = app.novelAiTagSuggestClient
    private val promptTokenCounter = app.novelAiPromptTokenCounter
    private val imageProcessingService = ImageProcessingService(app)

    private val _uiState = MutableStateFlow(ImagePromptToolUiState())
    val uiState: StateFlow<ImagePromptToolUiState> = _uiState.asStateFlow()
    val novelAiConfigured: StateFlow<Boolean> = credentials.configured

    private var designJob: Job? = null
    private var imageJob: Job? = null
    private var draftSaveJob: Job? = null
    private var tagJob: Job? = null
    private var tokenCountJob: Job? = null
    private var accountJob: Job? = null
    private var imageImportJob: Job? = null
    private var tokenCountRevision = 0L
    private var lastTokenCountRequest: Pair<NovelAiImageModel, NovelAiPromptPlan>? = null

    init {
        viewModelScope.launch {
            repository.initialize()
            repository.loadDraft()
            repository.draft.collect { draft ->
                draft ?: return@collect
                _uiState.update {
                    it.copy(
                        draft = draft,
                        draftLoaded = true,
                        hasHistoryUndo = repository.loadUndoDraft() != null,
                        selectedCharacterCardId = draft.importedCharacterCardId,
                        phase = if (draft.basePrompt.isBlank()) ImagePromptToolPhase.IDLE else ImagePromptToolPhase.READY
                    )
                }
                scheduleTokenCount(draft)
            }
        }
        observeCharacterCards()
        observeModelConfiguration()
        observeRecentImages()
        observeAccountUsage()
    }

    fun updateDraft(transform: (NovelAiStudioDraft) -> NovelAiStudioDraft) {
        if (!_uiState.value.draftLoaded || _uiState.value.isBusy || _uiState.value.applyingHistory) return
        _uiState.update { state ->
            val next = transform(state.draft).copy(updatedAt = System.currentTimeMillis())
            state.copy(
                draft = next,
                phase = if (next.basePrompt.isBlank()) ImagePromptToolPhase.IDLE else ImagePromptToolPhase.READY,
                error = null
            )
        }
        scheduleDraftSave()
        scheduleTokenCount(_uiState.value.draft)
    }

    fun importImage(uri: Uri) {
        if (_uiState.value.isBusy || _uiState.value.applyingHistory) return
        imageImportJob?.cancel()
        imageImportJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    imageImport = NovelAiStudioImageImportUiState(loading = true),
                    error = null
                )
            }
            try {
                val (source, metadata) = withContext(Dispatchers.IO) {
                    val imported = imageProcessingService.importImage(uri)
                    imported to NovelAiPngMetadataReader.readStudio(imported.path)
                }
                _uiState.update {
                    it.copy(
                        imageImport = NovelAiStudioImageImportUiState(
                            source = source,
                            metadata = metadata
                        )
                    )
                }
            } catch (error: CancellationException) {
                _uiState.update { it.copy(imageImport = NovelAiStudioImageImportUiState()) }
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        imageImport = NovelAiStudioImageImportUiState(),
                        error = "导入图片失败：${error.message ?: "未知错误"}"
                    )
                }
            }
        }.also { job -> job.invokeOnCompletion { imageImportJob = null } }
    }

    fun applyImportedMetadata(selection: NovelAiStudioMetadataSelection) {
        val metadata = _uiState.value.imageImport.metadata ?: return
        updateDraft { draft -> draft.applyImportedMetadata(metadata, selection) }
        clearImportedImage()
    }

    fun clearImportedImage() {
        imageImportJob?.cancel()
        _uiState.update { it.copy(imageImport = NovelAiStudioImageImportUiState()) }
    }

    fun selectImageModel(model: NovelAiImageModel) {
        updateDraft { draft -> draft.copy(selectedModel = model) }
    }

    fun toggleOutputExpanded() {
        if (!_uiState.value.draftLoaded) return
        _uiState.update { state -> state.copy(draft = state.draft.copy(outputExpanded = !state.draft.outputExpanded)) }
        scheduleDraftSave()
    }

    fun updateGenerationSettings(transform: (NovelAiGenerationSettings) -> NovelAiGenerationSettings) {
        updateDraft { draft -> draft.withActiveSettings(transform(draft.activeSettings).copy(model = draft.selectedModel).normalized()) }
    }

    fun addCharacter() {
        val draft = _uiState.value.draft
        if (draft.characters.size >= draft.selectedModel.maxCharacters) {
            _uiState.update { it.copy(error = "${draft.selectedModel.displayName} 最多支持 ${draft.selectedModel.maxCharacters} 个角色") }
            return
        }
        updateDraft { it.copy(characters = it.characters + NovelAiCharacterPromptDraft()) }
    }

    fun updateCharacter(id: String, transform: (NovelAiCharacterPromptDraft) -> NovelAiCharacterPromptDraft) =
        updateDraft { draft ->
            draft.copy(characters = draft.characters.map { if (it.id == id) transform(it) else it })
        }

    fun removeCharacter(id: String) = updateDraft { draft ->
        draft.copy(characters = draft.characters.filterNot { it.id == id })
    }

    fun moveCharacter(id: String, delta: Int) = updateDraft { draft ->
        val source = draft.characters.indexOfFirst { it.id == id }
        val target = source + delta
        if (source < 0 || target !in draft.characters.indices) return@updateDraft draft
        val reordered = draft.characters.toMutableList()
        val item = reordered.removeAt(source)
        reordered.add(target, item)
        draft.copy(characters = reordered)
    }

    fun importCharacterCardPrompts(cardId: String) {
        if (!_uiState.value.canImportCharacterCard) return
        val card = _uiState.value.characterCards.firstOrNull { it.id == cardId } ?: return
        val sources = card.characters.mapIndexedNotNull { index, character ->
            character.imagePrompt.trim().takeIf(String::isNotBlank)?.let { prompt ->
                NovelAiCharacterPromptSource(
                    name = character.name.trim().ifBlank { "角色 ${index + 1}" },
                    prompt = prompt
                )
            }
        }
        updateDraft { draft ->
            draft.importCharacterCardPromptSources(
                cardId = cardId,
                cardStylePrompt = card.defaultImagePrompt,
                sources = sources
            )
        }
        _uiState.update { it.copy(selectedCharacterCardId = cardId) }
    }

    fun designPrompt() = startDesign(conversion = false)

    fun convertNaturalLanguagePrompt() = startDesign(conversion = true)

    private fun startDesign(conversion: Boolean) {
        if (_uiState.value.isBusy) return
        val snapshot = _uiState.value
        val model = snapshot.models.firstOrNull { it.id == snapshot.selectedModelId }
        if (model == null || !snapshot.modelUsable) {
            _uiState.update { it.copy(error = "默认生图辅助模型/API Key 未配置") }
            return
        }
        val draft = snapshot.draft
        val cardCharacterPrompts = draft.importedCharacterPromptSources.map { it.name to it.prompt }
        val sourceText = if (conversion) draft.basePrompt else draft.imageDescription
        if (sourceText.isBlank()) {
            _uiState.update { it.copy(error = if (conversion) "基础 Prompt 为空" else "请输入画面内容") }
            return
        }
        designJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    phase = ImagePromptToolPhase.DESIGNING,
                    designStatus = if (conversion) "正在转化为 NovelAI Tags" else "正在设计画面",
                    reasoningStream = "",
                    resultStream = "",
                    error = null
                )
            }
            try {
                val playerName = settingsRepository.getPlayerSetting().playerName
                val characterText = draft.characters.joinToString("\n\n") { it.prompt }
                if (draft.naturalLanguageMode && !conversion) {
                    val scene = promptDesigner.planNaturalLanguageForPromptTool(
                        imageDescription = sourceText,
                        characterPrompt = characterText,
                        characterImagePrompts = cardCharacterPrompts,
                        finalPromptRequirement = draft.extraRequirement,
                        model = model,
                        targetImageModel = draft.selectedModel,
                        playerName = playerName,
                        onContentDelta = { text -> _uiState.update { it.copy(resultStream = text) } }
                    )
                    updateDraftAfterDesign(draft.copy(basePrompt = scene))
                } else {
                    val plan = promptDesigner.designForPromptTool(
                        imageDescription = sourceText,
                        characterPrompt = characterText,
                        characterImagePrompts = cardCharacterPrompts,
                        finalPromptRequirement = draft.extraRequirement,
                        model = model,
                        targetImageModel = draft.selectedModel,
                        playerName = playerName,
                        onContentDelta = { text -> _uiState.update { it.copy(resultStream = text) } },
                        onReasoningDelta = { text -> _uiState.update { it.copy(reasoningStream = text) } }
                    )
                    val before = if (conversion) NovelAiPositivePromptSnapshot(
                        basePrompt = draft.basePrompt,
                        characterPrompts = draft.characters.map(NovelAiCharacterPromptDraft::prompt)
                    ) else null
                    updateDraftAfterDesign(mergeAiPlan(draft, plan).copy(conversionSnapshot = before))
                }
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    _uiState.update { it.copy(phase = ImagePromptToolPhase.CANCELLED) }
                    throw error
                }
                _uiState.update {
                    it.copy(phase = ImagePromptToolPhase.FAILED, error = "AI 设计失败：${error.message ?: "未知错误"}")
                }
            }
        }.also { job -> job.invokeOnCompletion { designJob = null } }
    }

    private fun mergeAiPlan(draft: NovelAiStudioDraft, plan: NovelAiPromptPlan): NovelAiStudioDraft {
        val merged = buildList {
            plan.characterCaptions.forEachIndexed { index, caption ->
                val old = draft.characters.getOrNull(index)
                add((old ?: NovelAiCharacterPromptDraft()).copy(prompt = caption.prompt))
            }
            addAll(draft.characters.drop(plan.characterCaptions.size))
        }
        return draft.copy(basePrompt = plan.baseCaption, characters = merged)
    }

    private fun updateDraftAfterDesign(draft: NovelAiStudioDraft) {
        _uiState.update {
            it.copy(draft = draft, phase = ImagePromptToolPhase.READY, designStatus = "完成", error = null)
        }
        scheduleDraftSave()
        scheduleTokenCount(draft)
    }

    fun restoreConvertedPrompt() {
        val snapshot = _uiState.value.draft.conversionSnapshot ?: return
        updateDraft { draft ->
            val restored = draft.characters.mapIndexed { index, character ->
                snapshot.characterPrompts.getOrNull(index)?.let { character.copy(prompt = it) } ?: character
            }
            draft.copy(basePrompt = snapshot.basePrompt, characters = restored, conversionSnapshot = null)
        }
    }

    fun generateImage() {
        if (_uiState.value.isBusy) return
        val draft = _uiState.value.draft
        val configured = draft.activeSettings
        configured.validationError(draft.characters.size)?.let { message ->
            _uiState.update { it.copy(error = message) }
            return
        }
        if (draft.basePrompt.isBlank() || draft.characters.any { it.prompt.isBlank() }) {
            _uiState.update { it.copy(error = "基础 Prompt 与已添加角色 Prompt 不能为空") }
            return
        }
        imageJob = viewModelScope.launch {
            val token = withContext(Dispatchers.IO) { credentials.load() }
            if (token == null) {
                _uiState.update { it.copy(phase = ImagePromptToolPhase.FAILED, error = "缺少 NovelAI Token") }
                return@launch
            }
            val seed = if (configured.seedMode == NovelAiSeedMode.RANDOM) {
                Random.nextLong(NovelAiGenerationSettings.MIN_SEED, configured.maxAllowedBaseSeed + 1)
            } else configured.seed
            val requestSettings = configured.copy(seed = seed, seedMode = NovelAiSeedMode.FIXED)
            val plan = draft.toPromptPlan()
            val historyId = UUID.randomUUID().toString()
            _uiState.update {
                it.copy(
                    phase = ImagePromptToolPhase.GENERATING,
                    imagePreview = null,
                    completedPreviews = emptyList(),
                    imagePaths = emptyList(),
                    selectedOutputPath = null,
                    selectedOutputIndex = 0,
                    imageProgress = 0f,
                    error = null
                )
            }
            try {
                val images = mutableListOf<ByteArray>()
                var streamError: String? = null
                imageService.generate(token, plan, requestSettings.imageSize(), requestSettings).collect { event ->
                    when (event) {
                        is NovelAiImageEvent.Intermediate -> _uiState.update {
                            it.copy(
                                phase = ImagePromptToolPhase.STREAMING,
                                imagePreview = event.image,
                                imageProgress = ((images.size + event.progress) / requestSettings.count).coerceIn(0f, 1f)
                            )
                        }
                        is NovelAiImageEvent.Final -> {
                            images += event.image
                            _uiState.update {
                                it.copy(
                                    phase = ImagePromptToolPhase.STREAMING,
                                    imagePreview = event.image,
                                    completedPreviews = images.toList(),
                                    selectedOutputIndex = images.lastIndex,
                                    imageProgress = images.size / requestSettings.count.toFloat()
                                )
                            }
                        }
                        is NovelAiImageEvent.Error -> streamError = event.message
                    }
                }
                check(streamError == null) { streamError.orEmpty() }
                check(images.size == requestSettings.count) {
                    "批量返回数量异常：请求 ${requestSettings.count}，收到 ${images.size}"
                }
                _uiState.update { it.copy(phase = ImagePromptToolPhase.SAVING) }
                val paths = withContext(Dispatchers.IO) { images.map { imageStorage.save(historyId, it) } }
                repository.saveHistory(
                    NovelAiGenerationHistoryEntry(
                        id = historyId,
                        images = novelAiHistoryImages(paths, seed),
                        recipe = draft.toRecipe(requestSettings),
                        createdAt = System.currentTimeMillis()
                    )
                )
                _uiState.update {
                    it.copy(
                        phase = ImagePromptToolPhase.FINISHED,
                        imagePreview = images.last(),
                        completedPreviews = images,
                        imagePaths = paths,
                        selectedOutputPath = paths.last(),
                        selectedOutputIndex = images.lastIndex,
                        imageProgress = 1f
                    )
                }
                refreshAccountUsage()
            } catch (error: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) { imageStorage.deleteSession(historyId) }
                if (error is CancellationException) {
                    _uiState.update { it.copy(phase = ImagePromptToolPhase.CANCELLED) }
                    throw error
                }
                _uiState.update {
                    it.copy(phase = ImagePromptToolPhase.FAILED, error = "生图失败：${error.message ?: "未知错误"}")
                }
            }
        }.also { job -> job.invokeOnCompletion { imageJob = null } }
    }

    fun selectOutput(index: Int) {
        val preview = _uiState.value.completedPreviews.getOrNull(index) ?: return
        _uiState.update {
            it.copy(
                selectedOutputIndex = index,
                selectedOutputPath = it.imagePaths.getOrNull(index),
                imagePreview = preview
            )
        }
    }

    fun selectRecentImage(path: String) {
        _uiState.update { it.copy(selectedOutputPath = path, imagePreview = null) }
    }

    fun applySelectedRecentHistory(mode: NovelAiHistoryApplyMode) {
        val snapshot = _uiState.value
        if (snapshot.isBusy || snapshot.applyingHistory) return
        val selected = snapshot.selectedRecentHistoryItem ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(applyingHistory = true, error = null) }
            runCatching {
                repository.applyHistory(selected.entry, selected.image, mode)
            }.onSuccess { appliedDraft ->
                _uiState.update {
                    it.copy(
                        draft = appliedDraft,
                        hasHistoryUndo = true,
                        applyingHistory = false,
                        phase = if (appliedDraft.basePrompt.isBlank()) ImagePromptToolPhase.IDLE else ImagePromptToolPhase.READY
                    )
                }
                scheduleTokenCount(appliedDraft)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        applyingHistory = false,
                        error = "应用历史失败：${error.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    fun requestTagSuggestions(field: NovelAiPromptFieldKey, text: String, cursor: Int) {
        tagJob?.cancel()
        val fragment = NovelAiTagCompletion.activeFragment(text, cursor)
        if (fragment == null || fragment.query.length < 2) {
            _uiState.update { it.copy(tagSuggestions = NovelAiTagSuggestionState()) }
            return
        }
        tagJob = viewModelScope.launch {
            delay(250)
            _uiState.update { it.copy(tagSuggestions = NovelAiTagSuggestionState(field = field, loading = true)) }
            try {
                val result = tagSuggestClient.search(fragment.query)
                _uiState.update {
                    it.copy(tagSuggestions = NovelAiTagSuggestionState(field = field, candidates = result.candidates.take(8)))
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(tagSuggestions = NovelAiTagSuggestionState(field = field, error = "补全不可用：${error.message ?: "网络错误"}"))
                }
            }
        }
    }

    fun clearTagSuggestions() {
        tagJob?.cancel()
        _uiState.update { it.copy(tagSuggestions = NovelAiTagSuggestionState()) }
    }

    fun undoHistoryApply() {
        viewModelScope.launch {
            val previous = repository.loadUndoDraft() ?: return@launch
            repository.saveDraft(previous)
            repository.clearUndoDraft()
            _uiState.update { it.copy(draft = previous, hasHistoryUndo = false) }
            scheduleTokenCount(previous)
        }
    }

    fun clearHistoryUndo() {
        viewModelScope.launch {
            repository.clearUndoDraft()
            _uiState.update { it.copy(hasHistoryUndo = false) }
        }
    }

    fun cancelActiveTask() {
        designJob?.cancel(CancellationException("用户取消"))
        imageJob?.cancel(CancellationException("用户取消"))
        imageImportJob?.cancel(CancellationException("用户取消"))
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun positivePromptForClipboard(): String = _uiState.value.draft.copyPositivePrompt()

    fun persistDraftNow() {
        draftSaveJob?.cancel()
        val draft = _uiState.value.draft
        draftSaveJob = viewModelScope.launch { repository.saveDraft(draft) }
    }

    private fun NovelAiStudioDraft.toPromptPlan(): NovelAiPromptPlan {
        val count = characters.size
        return NovelAiPromptPlan(
            baseCaption = NovelAiPromptDesigner.prependStylePrompt(stylePrompt, basePrompt),
            characterCaptions = characters.mapIndexed { index, character ->
                NovelAiCharacterCaption(
                    prompt = character.prompt,
                    center = NovelAiPromptDesigner.fallbackCenter(index, count),
                    negativePrompt = character.negativePrompt
                )
            },
            sizePreset = NovelAiImageSizePreset.PORTRAIT,
            negativePrompt = negativePrompt
        )
    }

    private fun scheduleDraftSave() {
        if (!_uiState.value.draftLoaded) return
        draftSaveJob?.cancel()
        val draft = _uiState.value.draft
        draftSaveJob = viewModelScope.launch {
            delay(400)
            repository.saveDraft(draft)
        }
    }

    private fun scheduleTokenCount(draft: NovelAiStudioDraft) {
        if (!_uiState.value.draftLoaded) return
        val request = draft.selectedModel to draft.toPromptPlan()
        if (request == lastTokenCountRequest && _uiState.value.promptTokens.error == null) return
        lastTokenCountRequest = request
        val revision = ++tokenCountRevision
        tokenCountJob?.cancel()
        _uiState.update { state ->
            val modelChanged = state.promptTokens.limit != draft.selectedModel.promptTokenLimit
            state.copy(
                promptTokens = state.promptTokens.copy(
                    positive = state.promptTokens.positive.takeUnless { modelChanged },
                    negative = state.promptTokens.negative.takeUnless { modelChanged },
                    limit = draft.selectedModel.promptTokenLimit,
                    loading = true,
                    error = null
                )
            )
        }
        tokenCountJob = viewModelScope.launch {
            delay(50)
            try {
                val usage = withContext(Dispatchers.Default) {
                    promptTokenCounter.count(request.second, request.first)
                }
                if (revision != tokenCountRevision) return@launch
                _uiState.update {
                    it.copy(
                        promptTokens = NovelAiPromptTokenState(
                            positive = usage.positive,
                            negative = usage.negative,
                            limit = usage.limit,
                            loading = false
                        )
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (revision != tokenCountRevision) return@launch
                _uiState.update {
                    it.copy(
                        promptTokens = it.promptTokens.copy(
                            loading = false,
                            error = "Token 计数不可用：${error.message ?: "资产读取失败"}"
                        )
                    )
                }
            }
        }
    }

    private fun observeAccountUsage() {
        viewModelScope.launch {
            credentials.configured.collect { configured ->
                if (configured) {
                    refreshAccountUsage()
                } else {
                    accountJob?.cancel()
                    _uiState.update {
                        it.copy(account = NovelAiAccountUiState(loading = false, error = "未配置 Token"))
                    }
                }
            }
        }
    }

    fun refreshAccountUsage() {
        accountJob?.cancel()
        accountJob = viewModelScope.launch {
            _uiState.update { state ->
                state.copy(account = state.account.copy(loading = true, error = null))
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val token = credentials.load() ?: error("未配置 Token")
                    accountService.fetch(token)
                }
            }
            result.onSuccess { usage ->
                _uiState.update { it.copy(account = NovelAiAccountUiState(usage = usage, loading = false)) }
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                _uiState.update { state ->
                    state.copy(
                        account = state.account.copy(
                            loading = false,
                            error = error.message ?: "账户信息获取失败"
                        )
                    )
                }
            }
        }
    }

    private fun observeModelConfiguration() {
        viewModelScope.launch {
            settingsRepository.initialize()
            settingsRepository.appSettings.collect { settings ->
                val models = modelResolver.availableChatModels(settings)
                val defaultModel = modelResolver.defaultImageModel(settings)
                val errors = buildList {
                    if (defaultModel == null) add("未配置可用默认生图辅助模型")
                    else if (!defaultModel.hasConfiguredAuthentication(settings)) add("默认生图辅助模型/API Key 未配置")
                }
                _uiState.update {
                    it.copy(models = models, selectedModelId = defaultModel?.id, modelErrors = errors, modelUsable = errors.isEmpty())
                }
            }
        }
    }

    private fun observeCharacterCards() {
        viewModelScope.launch {
            characterRepository.initialize()
            characterRepository.characters.collect { cards ->
                _uiState.update { state ->
                    state.copy(
                        characterCards = cards,
                        selectedCharacterCardId = state.selectedCharacterCardId?.takeIf { id -> cards.any { it.id == id } }
                    )
                }
            }
        }
    }

    private fun observeRecentImages() {
        viewModelScope.launch {
            repository.initialize()
            repository.history.collect { entries ->
                val recent = entries.flatMap { entry ->
                    entry.images.asReversed().map { image -> NovelAiRecentHistoryItem(entry, image) }
                }.take(12)
                _uiState.update { state ->
                    val availablePaths = recent.map { it.image.path }.toSet() + state.imagePaths
                    state.copy(
                        recentHistoryItems = recent,
                        selectedOutputPath = state.selectedOutputPath
                            ?.takeIf(availablePaths::contains)
                            ?: state.imagePaths.lastOrNull()
                            ?: recent.firstOrNull()?.image?.path
                    )
                }
            }
        }
    }

    override fun onCleared() {
        val finalDraft = _uiState.value.draft
        app.applicationScope.launch { repository.saveDraft(finalDraft) }
        draftSaveJob?.cancel()
        tagJob?.cancel()
        tokenCountJob?.cancel()
        designJob?.cancel()
        imageJob?.cancel()
        imageImportJob?.cancel()
        super.onCleared()
    }
}
