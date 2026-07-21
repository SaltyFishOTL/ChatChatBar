package com.example.chatbar.ui.imageprompt

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.image.ImageFileEncoder
import com.example.chatbar.domain.image.NovelAiImageEvent
import com.example.chatbar.domain.image.NovelAiImageRegenerationDraft
import com.example.chatbar.domain.image.NovelAiImageSizePolicy
import com.example.chatbar.domain.image.NOVEL_AI_MAX_BATCH_SIZE
import com.example.chatbar.domain.image.emptyNovelAiImageRegenerationDraft
import com.example.chatbar.domain.image.toRegenerationDraft
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ImagePromptToolPhase {
    IDLE,
    DESIGNING,
    READY,
    GENERATING,
    STREAMING,
    SAVING,
    FINISHED,
    FAILED,
    CANCELLED
}

data class ImagePromptToolUiState(
    val imageDescription: String = "",
    val stylePrompt: String = "",
    val characterPrompt: String = "",
    val imagePromptPreference: String = "",
    val referenceImagePath: String? = null,
    val characterCards: List<CharacterCard> = emptyList(),
    val selectedCharacterCardId: String? = null,
    val models: List<ModelConfig> = emptyList(),
    val selectedModelId: String? = null,
    val modelErrors: List<String> = emptyList(),
    val modelUsable: Boolean = false,
    val phase: ImagePromptToolPhase = ImagePromptToolPhase.IDLE,
    val designStatus: String = "",
    val imageAnalysisStream: String = "",
    val reasoningStream: String = "",
    val resultStream: String = "",
    val promptDraft: NovelAiImageRegenerationDraft = emptyNovelAiImageRegenerationDraft(),
    val promptRevision: Int = 0,
    val imagePreview: ByteArray? = null,
    val imagePaths: List<String> = emptyList(),
    val imageProgress: Float = 0f,
    val error: String? = null
) {
    val imagePath: String? get() = imagePaths.firstOrNull()
    val isDesigning: Boolean get() = phase == ImagePromptToolPhase.DESIGNING
    val isGeneratingImage: Boolean
        get() = phase == ImagePromptToolPhase.GENERATING ||
            phase == ImagePromptToolPhase.STREAMING ||
            phase == ImagePromptToolPhase.SAVING
    val isBusy: Boolean get() = isDesigning || isGeneratingImage
    val canDesign: Boolean
        get() = !isBusy &&
            modelUsable &&
            selectedModelId != null &&
            (referenceImagePath != null ||
                listOf(imageDescription, stylePrompt, characterPrompt).any { it.isNotBlank() })
}

class ImagePromptToolViewModel : ViewModel() {
    private val settingsRepository = ChatBarApp.instance.settingsRepository
    private val characterRepository = ChatBarApp.instance.characterRepository
    private val modelResolver = ChatBarApp.instance.effectiveModelResolver
    private val promptDesigner = ChatBarApp.instance.novelAiPromptDesigner
    private val novelAiCredentials = ChatBarApp.instance.novelAiCredentialStore
    private val imageService = ChatBarApp.instance.novelAiImageService
    private val imageStorage = ChatBarApp.instance.novelAiImageStorage
    private val imageUnderstandingService = ChatBarApp.instance.imageUnderstandingService
    private val draftAssetService = ChatBarApp.instance.editorDraftAssetService

    private val _uiState = MutableStateFlow(ImagePromptToolUiState())
    val uiState: StateFlow<ImagePromptToolUiState> = _uiState.asStateFlow()
    val novelAiConfigured: StateFlow<Boolean> = novelAiCredentials.configured

    private var designJob: Job? = null
    private var imageJob: Job? = null
    private var completedImageCheckpoint: Pair<Int, List<ByteArray>>? = null

    init {
        observeCharacterCards()
        observeModelConfiguration()
    }

    fun updateImageDescription(value: String) {
        updateInput { it.copy(imageDescription = value) }
    }

    fun updateStylePrompt(value: String) {
        updateInput { it.copy(stylePrompt = value) }
    }

    fun updateCharacterPrompt(value: String) {
        updateInput { it.copy(characterPrompt = value) }
    }

    fun updateImagePromptPreference(value: String) {
        updateInput { it.copy(imagePromptPreference = value) }
        viewModelScope.launch {
            val settings = settingsRepository.getAppSettings()
            if (settings.imagePromptToolPreference != value) {
                settingsRepository.saveAppSettings(settings.copy(imagePromptToolPreference = value))
            }
        }
    }

    fun selectReferenceImage(uri: Uri) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            val previousPath = _uiState.value.referenceImagePath
            try {
                val extension = when (ChatBarApp.instance.contentResolver.getType(uri)) {
                    "image/png" -> "png"
                    "image/gif" -> "gif"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }
                val newPath = draftAssetService.copyImageToDraft(
                    draftSessionId = PROMPT_TOOL_DRAFT_SESSION_ID,
                    uri = uri,
                    extension = extension
                )
                updateInput { it.copy(referenceImagePath = newPath) }
                previousPath?.takeIf(draftAssetService::isDraftAsset)?.let { oldPath ->
                    draftAssetService.deleteFiles(listOf(oldPath))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(error = "读取参考图片失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun removeReferenceImage() {
        if (_uiState.value.isBusy) return
        val oldPath = _uiState.value.referenceImagePath ?: return
        updateInput { it.copy(referenceImagePath = null) }
        if (draftAssetService.isDraftAsset(oldPath)) {
            viewModelScope.launch { draftAssetService.deleteFiles(listOf(oldPath)) }
        }
    }

    fun updatePromptDraft(draft: NovelAiImageRegenerationDraft) {
        if (_uiState.value.isBusy) return
        completedImageCheckpoint = null
        _uiState.update {
            it.copy(
                phase = if (draft.canRegenerate) ImagePromptToolPhase.READY else ImagePromptToolPhase.IDLE,
                promptDraft = draft,
                imagePreview = null,
                imagePaths = emptyList(),
                imageProgress = 0f,
                error = null
            )
        }
    }

    fun importCharacterCardPrompts(cardId: String) {
        if (_uiState.value.isBusy) return
        val card = _uiState.value.characterCards.firstOrNull { it.id == cardId } ?: return
        updateInput {
            it.copy(
                selectedCharacterCardId = card.id,
                stylePrompt = card.defaultImagePrompt.trim(),
                characterPrompt = card.characterImagePromptText()
            )
        }
    }

    fun designPrompt() {
        if (_uiState.value.isBusy) return
        val snapshot = _uiState.value
        val model = snapshot.models.firstOrNull { it.id == snapshot.selectedModelId }
        if (model == null || !snapshot.modelUsable) {
            _uiState.update { it.copy(error = "默认生图模型/API Key 未配置") }
            return
        }
        completedImageCheckpoint = null
        designJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    phase = ImagePromptToolPhase.DESIGNING,
                    designStatus = if (snapshot.referenceImagePath == null) "正在设计提示词" else "正在读取参考图片",
                    imageAnalysisStream = "",
                    reasoningStream = "",
                    resultStream = "",
                    imagePreview = null,
                    imagePaths = emptyList(),
                    imageProgress = 0f,
                    error = null
                )
            }
            try {
                var imageDescription = snapshot.imageDescription
                var directImageBase64s = emptyList<String>()
                snapshot.referenceImagePath?.let { imagePath ->
                    val imageBase64 = ImageFileEncoder.encodeToJpegBase64(imagePath)
                    val understanding = imageUnderstandingService.prepare(
                        imageBase64s = listOf(imageBase64),
                        generationModel = model,
                        requireUnderstanding = true,
                        announceDirect = true,
                        onStatus = { status ->
                            _uiState.update { it.copy(designStatus = status) }
                        },
                        onDescriptionText = { _, text ->
                            _uiState.update { it.copy(imageAnalysisStream = text) }
                        }
                    )
                    directImageBase64s = understanding.directImageBase64s
                    imageDescription = listOf(
                        snapshot.imageDescription,
                        understanding.descriptions.joinToString("\n")
                    ).filter(String::isNotBlank).joinToString("\n\n")
                }
                _uiState.update { it.copy(designStatus = "正在设计 NovelAI 提示词") }
                val plan = promptDesigner.designForPromptTool(
                    imageDescription = imageDescription,
                    stylePrompt = snapshot.stylePrompt,
                    characterPrompt = snapshot.characterPrompt,
                    finalPromptRequirement = snapshot.imagePromptPreference,
                    imageBase64s = directImageBase64s,
                    referenceImageProvided = snapshot.referenceImagePath != null,
                    model = model,
                    onContentDelta = { text ->
                        _uiState.update { it.copy(resultStream = text) }
                    },
                    onReasoningDelta = { text ->
                        _uiState.update { it.copy(reasoningStream = text) }
                    }
                )
                _uiState.update {
                    it.copy(
                        phase = ImagePromptToolPhase.READY,
                        designStatus = "提示词设计完成",
                        promptDraft = plan.toRegenerationDraft(),
                        promptRevision = it.promptRevision + 1,
                        error = null
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    _uiState.update { it.copy(phase = ImagePromptToolPhase.CANCELLED, error = null) }
                    throw error
                }
                _uiState.update {
                    it.copy(
                        phase = ImagePromptToolPhase.FAILED,
                        error = "提示词生成失败：${error.message ?: "未知错误"}"
                    )
                }
            }
        }
        designJob?.invokeOnCompletion {
            designJob = null
        }
    }

    fun generateImage(batchSize: Int = 1) {
        if (_uiState.value.isBusy) return
        if (batchSize !in 1..NOVEL_AI_MAX_BATCH_SIZE) {
            _uiState.update { it.copy(error = "批量生图数量必须在 1..$NOVEL_AI_MAX_BATCH_SIZE 之间") }
            return
        }
        val draft = _uiState.value.promptDraft
        if (!draft.canRegenerate) {
            _uiState.update { it.copy(error = "主提示词不能为空，已添加的角色提示词也必须填写") }
            return
        }
        val plan = draft.toPromptPlan()
        imageJob = viewModelScope.launch {
            val token = withContext(Dispatchers.IO) { novelAiCredentials.load() }
            if (token == null) {
                _uiState.update {
                    it.copy(
                        phase = ImagePromptToolPhase.FAILED,
                        error = "缺少 NovelAI Token，无法生图"
                    )
                }
                return@launch
            }
            val settings = settingsRepository.getAppSettings()
            val ratioError = NovelAiImageSizePolicy.validationError(settings.novelAiImageAspectRatio)
            if (ratioError != null) {
                _uiState.update { it.copy(phase = ImagePromptToolPhase.FAILED, error = ratioError) }
                return@launch
            }
            val imageSize = NovelAiImageSizePolicy.resolve(settings.novelAiImageAspectRatio, plan.sizePreset)
            val seed = imageService.newSeed()
            val resumeImages = completedImageCheckpoint
                ?.takeIf { checkpoint ->
                    _uiState.value.phase == ImagePromptToolPhase.FAILED && checkpoint.first == batchSize
                }
                ?.second
            if (resumeImages == null) completedImageCheckpoint = null
            _uiState.update {
                it.copy(
                    phase = ImagePromptToolPhase.GENERATING,
                    imagePreview = null,
                    imagePaths = emptyList(),
                    imageProgress = 0f,
                    error = null
                )
            }
            try {
                val completedImages = resumeImages ?: run {
                    val finalImages = mutableListOf<ByteArray>()
                    var streamError: String? = null
                    imageService.generate(
                        token = token,
                        prompt = plan,
                        seed = seed,
                        imageSize = imageSize,
                        batchSize = batchSize
                    ).collect { event ->
                        when (event) {
                            is NovelAiImageEvent.Intermediate -> {
                                _uiState.update {
                                    it.copy(
                                        phase = ImagePromptToolPhase.STREAMING,
                                        imagePreview = event.image,
                                        imageProgress = ((finalImages.size + event.progress) / batchSize)
                                            .coerceIn(0f, 1f)
                                    )
                                }
                            }
                            is NovelAiImageEvent.Final -> {
                                finalImages += event.image
                                _uiState.update {
                                    it.copy(
                                        phase = ImagePromptToolPhase.STREAMING,
                                        imagePreview = event.image,
                                        imageProgress = (finalImages.size / batchSize.toFloat())
                                            .coerceIn(0f, 1f)
                                    )
                                }
                            }
                            is NovelAiImageEvent.Error -> streamError = event.message
                        }
                    }
                    check(streamError == null) { streamError.orEmpty() }
                    check(finalImages.size == batchSize) {
                        "NovelAI 批量生图返回数量异常：请求 $batchSize 张，收到 ${finalImages.size} 张"
                    }
                    finalImages.toList().also { images ->
                        completedImageCheckpoint = batchSize to images
                    }
                }
                _uiState.update {
                    it.copy(
                        phase = ImagePromptToolPhase.SAVING,
                        imagePreview = completedImages.last(),
                        imageProgress = 1f
                    )
                }
                val paths = saveGeneratedImages(completedImages)
                _uiState.update {
                    it.copy(
                        phase = ImagePromptToolPhase.FINISHED,
                        imagePreview = completedImages.last(),
                        imagePaths = paths,
                        imageProgress = 1f,
                        error = null
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    _uiState.update { it.copy(phase = ImagePromptToolPhase.CANCELLED, error = null) }
                    throw error
                }
                _uiState.update {
                    it.copy(
                        phase = ImagePromptToolPhase.FAILED,
                        error = "生图失败：${error.message ?: "未知错误"}"
                    )
                }
            }
        }
        imageJob?.invokeOnCompletion {
            imageJob = null
        }
    }

    private suspend fun saveGeneratedImages(images: List<ByteArray>): List<String> =
        withContext(Dispatchers.IO) {
            val saved = mutableListOf<String>()
            try {
                images.forEach { image ->
                    saved += imageStorage.save(PROMPT_TOOL_IMAGE_SESSION_ID, image)
                }
                saved.toList()
            } catch (error: Throwable) {
                saved.forEach { path -> imageStorage.deleteIfOwned(path) }
                throw error
            }
        }

    fun cancelActiveTask() {
        val active = _uiState.value
        if (!active.isBusy) return
        designJob?.cancel(CancellationException("用户停止跑图工具任务"))
        imageJob?.cancel(CancellationException("用户停止跑图工具任务"))
        _uiState.update { it.copy(phase = ImagePromptToolPhase.CANCELLED, error = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        designJob?.cancel()
        imageJob?.cancel()
        _uiState.value.referenceImagePath
            ?.takeIf(draftAssetService::isDraftAsset)
            ?.let { path -> runCatching { java.io.File(path).delete() } }
        super.onCleared()
    }

    private fun observeModelConfiguration() {
        viewModelScope.launch {
            settingsRepository.initialize()
            settingsRepository.appSettings.collect { settings ->
                val models = modelResolver.availableChatModels(settings)
                val defaultModel = modelResolver.defaultImageModel(settings)
                val imageModelErrors = buildList {
                    if (defaultModel == null) {
                        add("未配置可用默认生图模型")
                    } else if (!defaultModel.hasConfiguredAuthentication(settings)) {
                        add("默认生图模型/API Key 未配置")
                    }
                }
                _uiState.update {
                    it.copy(
                        models = models,
                        selectedModelId = defaultModel?.id,
                        modelErrors = imageModelErrors,
                        modelUsable = imageModelErrors.isEmpty(),
                        imagePromptPreference = settings.imagePromptToolPreference
                    )
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
                        selectedCharacterCardId = state.selectedCharacterCardId
                            ?.takeIf { selected -> cards.any { it.id == selected } }
                    )
                }
            }
        }
    }

    private fun updateInput(transform: (ImagePromptToolUiState) -> ImagePromptToolUiState) {
        completedImageCheckpoint = null
        _uiState.update {
            transform(it).copy(
                phase = ImagePromptToolPhase.IDLE,
                designStatus = "",
                imageAnalysisStream = "",
                reasoningStream = "",
                resultStream = "",
                promptDraft = emptyNovelAiImageRegenerationDraft(),
                imagePreview = null,
                imagePaths = emptyList(),
                imageProgress = 0f,
                error = null
            )
        }
    }

    private fun CharacterCard.characterImagePromptText(): String =
        characters
            .mapNotNull { character ->
                character.imagePrompt.trim()
                    .takeIf(String::isNotBlank)
                    ?.let { prompt -> "${character.name}:\n$prompt" }
            }
            .joinToString("\n\n")

    private companion object {
        const val PROMPT_TOOL_IMAGE_SESSION_ID = "image-prompt-tool"
        const val PROMPT_TOOL_DRAFT_SESSION_ID = "image-prompt-tool-reference"
    }
}
