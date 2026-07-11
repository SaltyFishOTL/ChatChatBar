package com.example.chatbar.ui.imageprompt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.image.NovelAiImageEvent
import com.example.chatbar.domain.image.NovelAiImageSizePolicy
import com.example.chatbar.domain.image.NovelAiPromptPlan
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
    val characterCards: List<CharacterCard> = emptyList(),
    val selectedCharacterCardId: String? = null,
    val models: List<ModelConfig> = emptyList(),
    val selectedModelId: String? = null,
    val modelErrors: List<String> = emptyList(),
    val modelUsable: Boolean = false,
    val phase: ImagePromptToolPhase = ImagePromptToolPhase.IDLE,
    val reasoningStream: String = "",
    val resultStream: String = "",
    val finalPrompt: String = "",
    val finalPromptParts: List<ImagePromptToolPromptPart> = emptyList(),
    val promptPlan: NovelAiPromptPlan? = null,
    val imagePreview: ByteArray? = null,
    val imagePath: String? = null,
    val imageProgress: Float = 0f,
    val error: String? = null
) {
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
            listOf(imageDescription, stylePrompt, characterPrompt).any { it.isNotBlank() }
}

data class ImagePromptToolPromptPart(
    val title: String,
    val text: String
)

class ImagePromptToolViewModel : ViewModel() {
    private val settingsRepository = ChatBarApp.instance.settingsRepository
    private val characterRepository = ChatBarApp.instance.characterRepository
    private val modelResolver = ChatBarApp.instance.effectiveModelResolver
    private val promptDesigner = ChatBarApp.instance.novelAiPromptDesigner
    private val novelAiCredentials = ChatBarApp.instance.novelAiCredentialStore
    private val imageService = ChatBarApp.instance.novelAiImageService
    private val imageStorage = ChatBarApp.instance.novelAiImageStorage

    private val _uiState = MutableStateFlow(ImagePromptToolUiState())
    val uiState: StateFlow<ImagePromptToolUiState> = _uiState.asStateFlow()
    val novelAiConfigured: StateFlow<Boolean> = novelAiCredentials.configured

    private var designJob: Job? = null
    private var imageJob: Job? = null

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
        if (model == null || model.apiKey.isBlank()) {
            _uiState.update { it.copy(error = "默认生图模型/API Key 未配置") }
            return
        }
        designJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    phase = ImagePromptToolPhase.DESIGNING,
                    reasoningStream = "",
                    resultStream = "",
                    finalPrompt = "",
                    finalPromptParts = emptyList(),
                    promptPlan = null,
                    imagePreview = null,
                    imagePath = null,
                    imageProgress = 0f,
                    error = null
                )
            }
            try {
                val plan = promptDesigner.designForPromptTool(
                    imageDescription = snapshot.imageDescription,
                    stylePrompt = snapshot.stylePrompt,
                    characterPrompt = snapshot.characterPrompt,
                    finalPromptRequirement = snapshot.imagePromptPreference,
                    model = model,
                    onContentDelta = { text ->
                        _uiState.update { it.copy(resultStream = text) }
                    },
                    onReasoningDelta = { text ->
                        _uiState.update { it.copy(reasoningStream = text) }
                    }
                )
                val promptParts = plan.toPromptParts()
                _uiState.update {
                    it.copy(
                        phase = ImagePromptToolPhase.READY,
                        promptPlan = plan,
                        finalPrompt = promptParts.toClipboardText(),
                        finalPromptParts = promptParts,
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

    fun generateImage() {
        if (_uiState.value.isBusy) return
        val plan = _uiState.value.promptPlan ?: return
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
            _uiState.update {
                it.copy(
                    phase = ImagePromptToolPhase.GENERATING,
                    imagePreview = null,
                    imagePath = null,
                    imageProgress = 0f,
                    error = null
                )
            }
            try {
                imageService.generate(token, plan, seed, imageSize).collect { event ->
                    when (event) {
                        is NovelAiImageEvent.Intermediate -> {
                            _uiState.update {
                                it.copy(
                                    phase = ImagePromptToolPhase.STREAMING,
                                    imagePreview = event.image,
                                    imageProgress = event.progress
                                )
                            }
                        }
                        is NovelAiImageEvent.Final -> {
                            _uiState.update {
                                it.copy(
                                    phase = ImagePromptToolPhase.SAVING,
                                    imagePreview = event.image,
                                    imageProgress = 1f
                                )
                            }
                            val path = withContext(Dispatchers.IO) {
                                imageStorage.save(PROMPT_TOOL_IMAGE_SESSION_ID, event.image)
                            }
                            _uiState.update {
                                it.copy(
                                    phase = ImagePromptToolPhase.FINISHED,
                                    imagePreview = event.image,
                                    imagePath = path,
                                    imageProgress = 1f,
                                    error = null
                                )
                            }
                        }
                        is NovelAiImageEvent.Error -> {
                            _uiState.update {
                                it.copy(
                                    phase = ImagePromptToolPhase.FAILED,
                                    error = event.message
                                )
                            }
                        }
                    }
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
                    } else if (defaultModel.apiKey.isBlank()) {
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
        _uiState.update {
            transform(it).copy(
                phase = ImagePromptToolPhase.IDLE,
                reasoningStream = "",
                resultStream = "",
                finalPrompt = "",
                finalPromptParts = emptyList(),
                promptPlan = null,
                imagePreview = null,
                imagePath = null,
                imageProgress = 0f,
                error = null
            )
        }
    }

    private fun NovelAiPromptPlan.toPromptParts(): List<ImagePromptToolPromptPart> =
        buildList {
            add(ImagePromptToolPromptPart("Base", baseCaption))
            characterCaptions.forEachIndexed { index, caption ->
                add(ImagePromptToolPromptPart("Char ${index + 1}", caption.prompt))
            }
        }

    private fun List<ImagePromptToolPromptPart>.toClipboardText(): String =
        joinToString("\n\n") { part ->
            "${part.title}:\n${part.text}"
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
    }
}
