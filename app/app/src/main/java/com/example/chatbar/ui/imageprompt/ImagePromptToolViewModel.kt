package com.example.chatbar.ui.imageprompt

import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.NovelAiPromptTranslationConsent
import com.example.chatbar.domain.image.NovelAiCharacterCaption
import com.example.chatbar.domain.image.NovelAiCharacterPromptDraft
import com.example.chatbar.domain.image.NovelAiCharacterPromptSource
import com.example.chatbar.domain.image.NovelAiAccountUsage
import com.example.chatbar.domain.image.NovelAiAspectRatio
import com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry
import com.example.chatbar.domain.image.NovelAiGenerationHistoryImage
import com.example.chatbar.domain.image.NovelAiGenerationCost
import com.example.chatbar.domain.image.NovelAiGenerationSettings
import com.example.chatbar.domain.image.NovelAiHistoryApplyMode
import com.example.chatbar.domain.image.ImageProcessingService
import com.example.chatbar.domain.image.ImportedProcessImage
import com.example.chatbar.domain.image.ProcessedImage
import com.example.chatbar.domain.image.FullImagePatchOperation
import com.example.chatbar.domain.image.ImageFileEncoder
import com.example.chatbar.domain.image.NovelAiImageEvent
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiImageCostEstimator
import com.example.chatbar.domain.image.NovelAiGenerationAction
import com.example.chatbar.domain.image.NovelAiFocusedInpaintPlan
import com.example.chatbar.domain.image.NovelAiFocusedInpaintProcessor
import com.example.chatbar.domain.image.NovelAiImageGuidanceDraft
import com.example.chatbar.domain.image.NovelAiImageUseTarget
import com.example.chatbar.domain.image.NovelAiInpaintResultComposer
import com.example.chatbar.domain.image.NovelAiPreparedImageGuidance
import com.example.chatbar.domain.image.NovelAiPreparedVibeReference
import com.example.chatbar.domain.image.NovelAiReferenceMode
import com.example.chatbar.domain.image.NovelAiStudioAssetRef
import com.example.chatbar.domain.image.NovelAiVibeReferenceDraft
import com.example.chatbar.domain.image.NovelAiImageSizePreset
import com.example.chatbar.domain.image.NovelAiPromptDesigner
import com.example.chatbar.domain.image.NovelAiPromptPlan
import com.example.chatbar.domain.image.NovelAiPromptAnnotation
import com.example.chatbar.domain.image.NovelAiPromptTranslationParser
import com.example.chatbar.domain.image.NovelAiPromptTranslationSegment
import com.example.chatbar.domain.image.NovelAiSeedMode
import com.example.chatbar.domain.image.NovelAiStudioDraft
import com.example.chatbar.domain.image.NovelAiStudioMetadataSelection
import com.example.chatbar.domain.image.NovelAiStudioPngMetadata
import com.example.chatbar.domain.image.NovelAiTagCandidate
import com.example.chatbar.domain.image.NovelAiTagCompletion
import com.example.chatbar.domain.image.copyPositivePrompt
import com.example.chatbar.domain.image.applyImportedMetadata
import com.example.chatbar.domain.image.novelAiHistoryImages
import com.example.chatbar.domain.image.ownedAssetPaths
import com.example.chatbar.domain.image.NovelAiPngMetadataReader
import com.example.chatbar.domain.image.toRecipe
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import com.example.chatbar.domain.prompt.PromptTemplates
import java.util.UUID
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PreparedImageGuidanceResult(
    val guidance: NovelAiPreparedImageGuidance,
    val updatedDraft: NovelAiImageGuidanceDraft,
    val focusedInpaintPlan: NovelAiFocusedInpaintPlan? = null,
    val focusedInpaintBlendMask: ByteArray? = null
)

enum class ImagePromptToolPhase {
    IDLE, DESIGNING, READY, GENERATING, STREAMING, SAVING, CANCELLING, FINISHED, FAILED, CANCELLED
}

data class NovelAiPromptFieldKey(val kind: String, val characterId: String? = null)

data class NovelAiTagSuggestionState(
    val field: NovelAiPromptFieldKey? = null,
    val candidates: List<NovelAiTagCandidate> = emptyList(),
    val error: String? = null,
    val loading: Boolean = false
)

private data class NovelAiPromptTranslationInput(
    val field: NovelAiPromptFieldKey,
    val text: String,
    val naturalLanguage: Boolean
) {
    val segments: List<NovelAiPromptTranslationSegment>
        get() = NovelAiPromptTranslationParser.parse(text, naturalLanguage)
}

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
    val error: String? = null,
    val localAnlasSpent: Long = 0L,
    val anlasBaseline: Long? = null,
    val localV5AllowanceSpent: Int = 0,
    val allowanceBaselineImages: Int? = null
) {
    val displayAnlas: Long?
        get() = usage?.anlas?.minus(localAnlasSpent)?.coerceAtLeast(0L)

    val approximateV5Images: Int?
        get() = usage?.approximateV5Images?.let { (it - localV5AllowanceSpent).coerceAtLeast(0) }

    val effectiveUsage: NovelAiAccountUsage?
        get() = usage?.copy(
            anlas = displayAnlas ?: usage.anlas,
            v5AllowanceExhausted = usage.v5AllowanceExhausted || approximateV5Images == 0
        )

    fun recordAnlasGeneration(cost: Long): NovelAiAccountUiState {
        val current = usage?.anlas ?: return this
        return copy(
            localAnlasSpent = localAnlasSpent + cost.coerceAtLeast(0L),
            anlasBaseline = anlasBaseline ?: current
        )
    }

    fun recordV5Generation(count: Int): NovelAiAccountUiState {
        val current = usage?.approximateV5Images ?: return this
        return copy(
            localV5AllowanceSpent = localV5AllowanceSpent + count.coerceAtLeast(0),
            allowanceBaselineImages = allowanceBaselineImages ?: current
        )
    }

    fun reconcile(serverUsage: NovelAiAccountUsage): NovelAiAccountUiState {
        val acknowledgedAnlas = anlasBaseline?.let { baseline ->
            (baseline - serverUsage.anlas).coerceAtLeast(0L)
        } ?: 0L
        val remainingLocalAnlas = (localAnlasSpent - acknowledgedAnlas).coerceAtLeast(0L)
        val serverImages = serverUsage.approximateV5Images
        val acknowledged = if (allowanceBaselineImages != null && serverImages != null) {
            (allowanceBaselineImages - serverImages).coerceAtLeast(0)
        } else 0
        val remainingLocal = (localV5AllowanceSpent - acknowledged).coerceAtLeast(0)
        return copy(
            usage = serverUsage,
            loading = false,
            error = null,
            localAnlasSpent = remainingLocalAnlas,
            anlasBaseline = serverUsage.anlas.takeIf { remainingLocalAnlas > 0L },
            localV5AllowanceSpent = remainingLocal,
            allowanceBaselineImages = serverImages.takeIf { remainingLocal > 0 }
        )
    }
}

private fun NovelAiStudioDraft.hasSamePromptEditorContent(other: NovelAiStudioDraft): Boolean =
    stylePrompt == other.stylePrompt &&
        basePrompt == other.basePrompt &&
        negativePrompt == other.negativePrompt &&
        characters == other.characters

data class NovelAiStudioImageImportUiState(
    val loading: Boolean = false,
    val source: ImportedProcessImage? = null,
    val metadata: NovelAiStudioPngMetadata? = null,
    val toolBusy: Boolean = false,
    val toolResult: ProcessedImage? = null
)

data class ImagePromptToolUiState(
    val draft: NovelAiStudioDraft = NovelAiStudioDraft(),
    val draftLoaded: Boolean = false,
    val promptEditorRevision: Int = 0,
    val canUndoDraft: Boolean = false,
    val canRedoDraft: Boolean = false,
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
    val promptAnnotations: Map<NovelAiPromptFieldKey, List<NovelAiPromptAnnotation>> = emptyMap(),
    val promptTranslationConsent: NovelAiPromptTranslationConsent = NovelAiPromptTranslationConsent.DISABLED,
    val promptTranslationPreferenceLoaded: Boolean = false,
    val promptTranslationNotice: String? = null,
    val promptTokens: NovelAiPromptTokenState = NovelAiPromptTokenState(),
    val account: NovelAiAccountUiState = NovelAiAccountUiState(),
    val imageImport: NovelAiStudioImageImportUiState = NovelAiStudioImageImportUiState(),
    val guidanceCheckpoint: NovelAiImageGuidanceDraft? = null,
    val guidanceBusy: Boolean = false,
    val guidanceEditorRequest: NovelAiImageUseTarget? = null,
    val vibeCacheMisses: Int = 0,
    val error: String? = null
) {
    val isDesigning: Boolean get() = phase == ImagePromptToolPhase.DESIGNING
    val isGeneratingImage: Boolean get() = phase in setOf(
        ImagePromptToolPhase.GENERATING,
        ImagePromptToolPhase.STREAMING,
        ImagePromptToolPhase.SAVING,
        ImagePromptToolPhase.CANCELLING
    )
    val isBusy: Boolean get() = isDesigning || isGeneratingImage || imageImport.loading || imageImport.toolBusy || guidanceBusy
    val selectedRecentHistoryItem: NovelAiRecentHistoryItem?
        get() = recentHistoryItems.firstOrNull { it.image.path == selectedOutputPath }
    val canImportCharacterCard: Boolean get() = draftLoaded && !isBusy && !applyingHistory
    val canGenerate: Boolean get() = !isBusy && !applyingHistory && draft.basePrompt.isNotBlank() &&
        draft.imageGuidance.validationError(draft.selectedModel) == null
    val generationCost: NovelAiGenerationCost
        get() = NovelAiImageCostEstimator.estimate(
            draft.activeSettings,
            account.effectiveUsage,
            draft.imageGuidance,
            vibeCacheMisses
        )
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
    private val danbooruTagCatalog = app.novelAiDanbooruTagCatalog
    private val promptTranslationService = app.novelAiPromptTranslationService
    private val promptTokenCounter = app.novelAiPromptTokenCounter
    private val guidanceAssets = app.novelAiStudioAssetStorage
    private val vibeEncoder = app.novelAiVibeEncodingService
    private val imageProcessingService = ImageProcessingService(app)

    private val _uiState = MutableStateFlow(ImagePromptToolUiState())
    val uiState: StateFlow<ImagePromptToolUiState> = _uiState.asStateFlow()
    val novelAiConfigured: StateFlow<Boolean> = credentials.configured

    private var designJob: Job? = null
    private var imageJob: Job? = null
    private var draftSaveJob: Job? = null
    private var tagJob: Job? = null
    private var promptTranslationJob: Job? = null
    private var tokenCountJob: Job? = null
    private var accountJob: Job? = null
    private var imageImportJob: Job? = null
    private var guidanceCheckpointJob: Job? = null
    private var guidanceEditorActive = false
    private val draftUndo = mutableListOf<NovelAiStudioDraft>()
    private val draftRedo = mutableListOf<NovelAiStudioDraft>()
    private var lastDraftHistoryKey: String? = null
    private var lastDraftHistoryAt = 0L
    private var tokenCountRevision = 0L
    private var promptTranslationRevision = 0L
    private var promptTranslationFailureShown = false
    private var lastTokenCountRequest: Pair<NovelAiImageModel, NovelAiPromptPlan>? = null

    init {
        viewModelScope.launch {
            repository.initialize()
            repository.loadDraft()
            val guidanceCheckpoint = repository.loadGuidanceCheckpoint()
            _uiState.update { it.copy(guidanceCheckpoint = guidanceCheckpoint) }
            repository.draft.collect { draft ->
                draft ?: return@collect
                val currentState = _uiState.value
                if (currentState.draftLoaded &&
                    !currentState.draft.hasSamePromptEditorContent(draft)
                ) {
                    draftSaveJob?.cancel()
                    resetDraftCoalescing()
                }
                val latestGuidanceCheckpoint = repository.loadGuidanceCheckpoint()
                _uiState.update { state ->
                    val resetPromptEditors = state.draftLoaded &&
                        !state.draft.hasSamePromptEditorContent(draft)
                    state.copy(
                        draft = draft,
                        draftLoaded = true,
                        promptEditorRevision = if (resetPromptEditors) {
                            state.promptEditorRevision + 1
                        } else {
                            state.promptEditorRevision
                        },
                        guidanceCheckpoint = latestGuidanceCheckpoint,
                        vibeCacheMisses = countVibeCacheMisses(draft),
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
        viewModelScope.launch {
            _uiState
                .map { state ->
                    Triple(
                        state.draft,
                        state.promptTranslationConsent,
                        state.draftLoaded && state.promptTranslationPreferenceLoaded
                    )
                }
                .distinctUntilChanged()
                .collect { (draft, consent, ready) ->
                    if (ready && consent == NovelAiPromptTranslationConsent.ENABLED) {
                        schedulePromptAnnotations(draft)
                    } else {
                        cancelPromptAnnotations(clear = true)
                    }
                }
        }
        viewModelScope.launch {
            repository.pendingGuidanceEditorTarget.collect { target ->
                _uiState.update { it.copy(guidanceEditorRequest = target) }
            }
        }
    }

    fun updateDraft(
        historyKey: String? = null,
        transform: (NovelAiStudioDraft) -> NovelAiStudioDraft
    ) {
        val state = _uiState.value
        if (!state.draftLoaded || state.applyingHistory || state.isBusy && !state.isGeneratingImage) return
        val transformed = transform(state.draft)
        if (transformed == state.draft) return
        recordDraftChange(state.draft, historyKey)
        val next = transformed.copy(
            updatedAt = maxOf(System.currentTimeMillis(), state.draft.updatedAt + 1L)
        )
        _uiState.update {
            it.copy(
                draft = next,
                canUndoDraft = draftUndo.isNotEmpty(),
                canRedoDraft = draftRedo.isNotEmpty(),
                vibeCacheMisses = countVibeCacheMisses(next),
                phase = if (state.isGeneratingImage) state.phase
                else if (next.basePrompt.isBlank()) ImagePromptToolPhase.IDLE
                else ImagePromptToolPhase.READY,
                error = null
            )
        }
        scheduleDraftSave()
        scheduleTokenCount(_uiState.value.draft)
    }

    fun updatePromptDraft(
        expectedEditorRevision: Int,
        historyKey: String,
        transform: (NovelAiStudioDraft) -> NovelAiStudioDraft
    ) {
        if (_uiState.value.promptEditorRevision != expectedEditorRevision) return
        updateDraft(historyKey, transform)
    }

    fun undoDraftChange() {
        val state = _uiState.value
        if (state.applyingHistory || state.isBusy && !state.isGeneratingImage || draftUndo.isEmpty()) return
        val previous = draftUndo.removeAt(draftUndo.lastIndex)
        draftRedo += state.draft
        trimDraftHistory(draftRedo)
        resetDraftCoalescing()
        applyDraftHistoryState(previous)
    }

    fun redoDraftChange() {
        val state = _uiState.value
        if (state.applyingHistory || state.isBusy && !state.isGeneratingImage || draftRedo.isEmpty()) return
        val next = draftRedo.removeAt(draftRedo.lastIndex)
        draftUndo += state.draft
        trimDraftHistory(draftUndo)
        resetDraftCoalescing()
        applyDraftHistoryState(next)
    }

    fun importImage(uri: Uri) {
        launchImageImport(import = { imageProcessingService.importImage(uri) })
    }

    fun importSharedImage(path: String, displayName: String, onResult: (Result<Unit>) -> Unit) {
        launchImageImport(
            import = { imageProcessingService.importFile(path, displayName) },
            onResult = onResult,
            showError = false
        )
    }

    private fun launchImageImport(
        import: suspend () -> ImportedProcessImage,
        onResult: (Result<Unit>) -> Unit = {},
        showError: Boolean = true
    ) {
        if (_uiState.value.isBusy || _uiState.value.applyingHistory) {
            onResult(Result.failure(IllegalStateException("生图工作室正忙，请稍后重试")))
            return
        }
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
                    val imported = import()
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
                onResult(Result.success(Unit))
            } catch (error: CancellationException) {
                _uiState.update { it.copy(imageImport = NovelAiStudioImageImportUiState()) }
                onResult(Result.failure(error))
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        imageImport = NovelAiStudioImageImportUiState(),
                        error = if (showError) "导入图片失败：${error.message ?: "未知错误"}" else null
                    )
                }
                onResult(Result.failure(error))
            }
        }.also { job -> job.invokeOnCompletion { imageImportJob = null } }
    }

    fun applyImportedMetadata(selection: NovelAiStudioMetadataSelection) {
        val metadata = _uiState.value.imageImport.metadata ?: return
        val embedded = metadata.imageGuidance
        if (!selection.imageGuidance || listOf(
                embedded.baseImageBase64,
                embedded.maskBase64,
                embedded.preciseImageBase64
            ).all { it.isNullOrBlank() }
        ) {
            updateDraft { draft -> draft.applyImportedMetadata(metadata, selection) }
            clearImportedImage()
            return
        }
        val current = _uiState.value.draft
        viewModelScope.launch {
            _uiState.update { it.copy(guidanceBusy = true, error = null) }
            try {
                var applied = current.applyImportedMetadata(metadata, selection)
                val tier = applied.activeSettings.sizeTier
                val base = embedded.baseImageBase64?.let { encoded ->
                    withContext(Dispatchers.IO) { guidanceAssets.importBase64(encoded, tier, true) }
                }
                val mask = embedded.maskBase64?.let { encoded ->
                    withContext(Dispatchers.IO) { guidanceAssets.importBase64(encoded, tier, true).copy(containsPaint = true) }
                }
                val precise = embedded.preciseImageBase64?.let { encoded ->
                    withContext(Dispatchers.IO) { guidanceAssets.importBase64(encoded, tier, false) }
                }
                applied = applied.copy(
                    imageGuidance = applied.imageGuidance.copy(
                        baseImage = base,
                        maskImage = mask,
                        action = embedded.action.takeIf {
                            base != null && (it != NovelAiGenerationAction.INPAINT || mask != null)
                        } ?: NovelAiGenerationAction.TEXT_TO_IMAGE,
                        preciseReference = applied.imageGuidance.preciseReference.copy(asset = precise),
                        referenceMode = when {
                            precise != null -> NovelAiReferenceMode.PRECISE
                            applied.imageGuidance.vibes.isNotEmpty() -> NovelAiReferenceMode.VIBE
                            else -> NovelAiReferenceMode.NONE
                        }
                    )
                )
                repository.saveDraft(applied)
                repository.clearGuidanceCheckpoint()
                if (applied != current) recordDraftChange(current, null)
                cleanupGuidanceAssets(applied)
                _uiState.update {
                    it.copy(
                        draft = applied,
                        canUndoDraft = draftUndo.isNotEmpty(),
                        canRedoDraft = draftRedo.isNotEmpty(),
                        guidanceCheckpoint = null,
                        guidanceBusy = false,
                        vibeCacheMisses = countVibeCacheMisses(applied),
                        imageImport = NovelAiStudioImageImportUiState()
                    )
                }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(guidanceBusy = false, error = "元数据图像引导导入失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun clearImportedImage() {
        imageImportJob?.cancel()
        _uiState.update { it.copy(imageImport = NovelAiStudioImageImportUiState()) }
    }

    fun restoreImportedPatch() {
        val source = _uiState.value.imageImport.source ?: return
        if (_uiState.value.isBusy) return
        imageImportJob = viewModelScope.launch {
            _uiState.update { state ->
                state.copy(imageImport = state.imageImport.copy(toolBusy = true, toolResult = null), error = null)
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    imageProcessingService.process(source.path, FullImagePatchOperation.Restore)
                }
                _uiState.update { state ->
                    state.copy(imageImport = state.imageImport.copy(toolBusy = false, toolResult = result))
                }
            } catch (error: CancellationException) {
                _uiState.update { state -> state.copy(imageImport = state.imageImport.copy(toolBusy = false)) }
                throw error
            } catch (error: Throwable) {
                _uiState.update { state ->
                    state.copy(
                        imageImport = state.imageImport.copy(toolBusy = false),
                        error = "还原贴片失败：${error.message ?: "未知错误"}"
                    )
                }
            }
        }.also { job -> job.invokeOnCompletion { imageImportJob = null } }
    }

    fun reverseImportedPrompt() {
        val snapshot = _uiState.value
        val source = snapshot.imageImport.source ?: return
        if (snapshot.isBusy) return
        val model = snapshot.models.firstOrNull { it.id == snapshot.selectedModelId }
        if (model == null || !snapshot.modelUsable) {
            _uiState.update { it.copy(error = snapshot.modelErrors.firstOrNull() ?: "生图辅助模型/API Key 未配置") }
            return
        }
        val draft = snapshot.draft
        designJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    phase = ImagePromptToolPhase.DESIGNING,
                    designStatus = "正在反推 NovelAI 提示词",
                    reasoningStream = "",
                    resultStream = "",
                    error = null
                )
            }
            try {
                val imageBase64 = ImageFileEncoder.encodeToJpegBase64(source.path)
                val playerName = settingsRepository.getPlayerSetting().playerName
                val plan = promptDesigner.designForPromptTool(
                    imageDescription = "",
                    characterPrompt = "",
                    characterImagePrompts = draft.importedCharacterPromptSources.map { it.name to it.prompt },
                    imageBase64s = listOf(imageBase64),
                    referenceImageProvided = true,
                    model = model,
                    playerName = playerName,
                    finalPromptRequirement = draft.extraRequirement,
                    targetImageModel = draft.selectedModel,
                    referenceImageInstruction = PromptTemplates.novelAiImageReversePromptUser(draft.selectedModel.displayName),
                    excludeStyle = false,
                    onContentDelta = { text -> _uiState.update { it.copy(resultStream = text) } },
                    onReasoningDelta = { text -> _uiState.update { it.copy(reasoningStream = text) } }
                )
                updateDraftAfterDesign(
                    mergeAiPlan(draft, plan).copy(conversionSnapshot = null)
                )
                _uiState.update { it.copy(designStatus = "反推完成") }
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    _uiState.update { it.copy(phase = ImagePromptToolPhase.CANCELLED) }
                    throw error
                }
                _uiState.update {
                    it.copy(phase = ImagePromptToolPhase.FAILED, error = "提示词反推失败：${error.message ?: "未知错误"}")
                }
            }
        }.also { job -> job.invokeOnCompletion { designJob = null } }
    }

    fun selectImageModel(model: NovelAiImageModel) {
        updateDraft { draft -> draft.copy(selectedModel = model) }
    }

    fun updateImageGuidance(transform: (NovelAiImageGuidanceDraft) -> NovelAiImageGuidanceDraft) {
        updateDraft { draft -> draft.copy(imageGuidance = transform(draft.imageGuidance)) }
    }

    fun importGuidanceImage(uri: Uri, target: NovelAiImageUseTarget) {
        launchGuidanceImport(
            target = target,
            copy = { tier, fit -> guidanceAssets.importUri(uri, tier, fit) }
        )
    }

    fun useImage(path: String, target: NovelAiImageUseTarget) {
        launchGuidanceImport(
            target = target,
            copy = { tier, fit -> guidanceAssets.copyExisting(path, tier, fit) }
        )
    }

    fun useSharedImage(
        path: String,
        target: NovelAiImageUseTarget,
        onResult: (Result<Unit>) -> Unit
    ) {
        launchGuidanceImport(
            target = target,
            copy = { tier, fit -> guidanceAssets.copyExisting(path, tier, fit) },
            onResult = onResult,
            showError = false
        )
    }

    fun consumeGuidanceEditorRequest() = repository.consumeGuidanceEditorRequest()

    fun stageGuidanceImage(
        uri: Uri,
        target: NovelAiImageUseTarget,
        onResult: (NovelAiStudioAssetRef, NovelAiStudioAssetRef?) -> Unit
    ) {
        val snapshot = _uiState.value
        if (snapshot.isBusy || snapshot.applyingHistory) return
        viewModelScope.launch {
            _uiState.update { it.copy(guidanceBusy = true, error = null) }
            try {
                val fit = target == NovelAiImageUseTarget.IMAGE_TO_IMAGE || target == NovelAiImageUseTarget.INPAINT
                val asset = withContext(Dispatchers.IO) {
                    guidanceAssets.importUri(uri, snapshot.draft.activeSettings.sizeTier, fit)
                }
                val mask = if (target == NovelAiImageUseTarget.INPAINT) {
                    withContext(Dispatchers.IO) { guidanceAssets.createEmptyMask(asset.width, asset.height) }
                } else null
                _uiState.update { it.copy(guidanceBusy = false) }
                onResult(asset, mask)
            } catch (error: CancellationException) {
                _uiState.update { it.copy(guidanceBusy = false) }
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(guidanceBusy = false, error = "图像引导导入失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun commitImageGuidance(guidance: NovelAiImageGuidanceDraft) {
        guidanceEditorActive = false
        guidanceCheckpointJob?.cancel()
        val current = _uiState.value.draft
        var next = current.copy(imageGuidance = guidance, updatedAt = System.currentTimeMillis())
        guidance.baseImage?.takeIf(NovelAiStudioAssetRef::isUsable)?.let { asset ->
            next = next.withActiveSettings(matchSettingsToAsset(next.activeSettings, asset))
        }
        viewModelScope.launch {
            repository.saveDraft(next)
            repository.clearGuidanceCheckpoint()
            if (next != current) recordDraftChange(current, null)
            cleanupGuidanceAssets(next)
            _uiState.update {
                it.copy(
                    draft = next,
                    canUndoDraft = draftUndo.isNotEmpty(),
                    canRedoDraft = draftRedo.isNotEmpty(),
                    guidanceCheckpoint = null,
                    vibeCacheMisses = countVibeCacheMisses(next),
                    error = null
                )
            }
        }
    }

    fun saveEditedGuidanceBitmap(
        bitmap: android.graphics.Bitmap,
        isMask: Boolean,
        onResult: (NovelAiStudioAssetRef) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(guidanceBusy = true, error = null) }
            try {
                val asset = withContext(Dispatchers.IO) {
                    guidanceAssets.saveBitmap(bitmap, if (isMask) "mask-edited" else "canvas-edited")
                        .copy(containsPaint = if (isMask) true else bitmap.width > 0)
                }
                _uiState.update { it.copy(guidanceBusy = false) }
                onResult(asset)
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(guidanceBusy = false, error = "画布保存失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun saveGuidanceCheckpoint(guidance: NovelAiImageGuidanceDraft) {
        if (!guidanceEditorActive) return
        guidanceCheckpointJob?.cancel()
        guidanceCheckpointJob = viewModelScope.launch {
            delay(350)
            if (!guidanceEditorActive) return@launch
            repository.saveGuidanceCheckpoint(guidance)
            _uiState.update { it.copy(guidanceCheckpoint = guidance) }
        }
    }

    fun clearGuidanceCheckpoint() {
        guidanceEditorActive = false
        guidanceCheckpointJob?.cancel()
        _uiState.update { it.copy(guidanceCheckpoint = null) }
        viewModelScope.launch {
            repository.clearGuidanceCheckpoint()
            cleanupGuidanceAssets(_uiState.value.draft)
        }
    }

    fun beginGuidanceEditor() {
        guidanceEditorActive = true
    }

    private fun launchGuidanceImport(
        target: NovelAiImageUseTarget,
        copy: (com.example.chatbar.domain.image.NovelAiSizeTier, Boolean) -> NovelAiStudioAssetRef,
        onResult: (Result<Unit>) -> Unit = {},
        showError: Boolean = true
    ) {
        val snapshot = _uiState.value
        if (snapshot.isBusy || snapshot.applyingHistory) {
            onResult(Result.failure(IllegalStateException("生图工作室正忙，请稍后重试")))
            return
        }
        if (snapshot.draft.selectedModel == NovelAiImageModel.V5_FULL &&
            target in setOf(NovelAiImageUseTarget.PRECISE_REFERENCE, NovelAiImageUseTarget.VIBE_REFERENCE)
        ) {
            _uiState.update {
                it.copy(error = if (showError) "V5 Full 暂不支持精确参考或氛围参考" else null)
            }
            onResult(Result.failure(IllegalArgumentException("V5 Full 暂不支持精确参考或氛围参考")))
            return
        }
        if (target == NovelAiImageUseTarget.VIBE_REFERENCE &&
            snapshot.draft.imageGuidance.vibes.size >= NovelAiImageGuidanceDraft.MAX_VIBES
        ) {
            _uiState.update {
                it.copy(error = if (showError) "氛围参考已满；请进入图像引导管理" else null)
            }
            onResult(Result.failure(IllegalStateException("氛围参考已满；请进入图像引导管理")))
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(guidanceBusy = true, error = null) }
            try {
                val fit = target == NovelAiImageUseTarget.IMAGE_TO_IMAGE || target == NovelAiImageUseTarget.INPAINT
                val asset = withContext(Dispatchers.IO) { copy(snapshot.draft.activeSettings.sizeTier, fit) }
                val mask = if (target == NovelAiImageUseTarget.INPAINT) {
                    withContext(Dispatchers.IO) { guidanceAssets.createEmptyMask(asset.width, asset.height) }
                } else null
                val nextGuidance = when (target) {
                    NovelAiImageUseTarget.IMAGE_TO_IMAGE -> snapshot.draft.imageGuidance.copy(
                        action = NovelAiGenerationAction.IMAGE_TO_IMAGE,
                        baseImage = asset,
                        maskImage = null
                    )
                    NovelAiImageUseTarget.INPAINT -> snapshot.draft.imageGuidance.copy(
                        action = NovelAiGenerationAction.INPAINT,
                        baseImage = asset,
                        maskImage = mask
                    )
                    NovelAiImageUseTarget.PRECISE_REFERENCE -> snapshot.draft.imageGuidance.copy(
                        referenceMode = NovelAiReferenceMode.PRECISE,
                        preciseReference = snapshot.draft.imageGuidance.preciseReference.copy(asset = asset)
                    )
                    NovelAiImageUseTarget.VIBE_REFERENCE -> snapshot.draft.imageGuidance.copy(
                        referenceMode = NovelAiReferenceMode.VIBE,
                        vibes = snapshot.draft.imageGuidance.vibes + NovelAiVibeReferenceDraft(asset = asset)
                    )
                }
                var nextDraft = snapshot.draft.copy(imageGuidance = nextGuidance, updatedAt = System.currentTimeMillis())
                if (fit) nextDraft = nextDraft.withActiveSettings(matchSettingsToAsset(nextDraft.activeSettings, asset))
                repository.saveDraft(nextDraft)
                repository.clearGuidanceCheckpoint()
                if (nextDraft != snapshot.draft) recordDraftChange(snapshot.draft, null)
                cleanupGuidanceAssets(nextDraft)
                repository.requestGuidanceEditor(target)
                _uiState.update {
                    it.copy(
                        draft = nextDraft,
                        canUndoDraft = draftUndo.isNotEmpty(),
                        canRedoDraft = draftRedo.isNotEmpty(),
                        guidanceCheckpoint = null,
                        guidanceBusy = false,
                        vibeCacheMisses = countVibeCacheMisses(nextDraft),
                        error = null
                    )
                }
                onResult(Result.success(Unit))
            } catch (error: CancellationException) {
                _uiState.update { it.copy(guidanceBusy = false) }
                onResult(Result.failure(error))
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        guidanceBusy = false,
                        error = if (showError) "图像引导导入失败：${error.message ?: "未知错误"}" else null
                    )
                }
                onResult(Result.failure(error))
            }
        }
    }

    fun toggleOutputExpanded() {
        updateDraft { it.copy(outputExpanded = !it.outputExpanded) }
    }

    fun updateGenerationSettings(
        historyKey: String? = null,
        transform: (NovelAiGenerationSettings) -> NovelAiGenerationSettings
    ) {
        updateDraft(historyKey) { draft ->
            draft.withActiveSettings(transform(draft.activeSettings).copy(model = draft.selectedModel).normalized())
        }
    }

    fun addCharacter() {
        val draft = _uiState.value.draft
        if (draft.characters.size >= draft.selectedModel.maxCharacters) {
            _uiState.update { it.copy(error = "${draft.selectedModel.displayName} 最多支持 ${draft.selectedModel.maxCharacters} 个角色") }
            return
        }
        updateDraft { it.copy(characters = it.characters + NovelAiCharacterPromptDraft()) }
    }

    fun updateCharacter(
        id: String,
        historyKey: String? = null,
        transform: (NovelAiCharacterPromptDraft) -> NovelAiCharacterPromptDraft
    ) =
        updateDraft(historyKey) { draft ->
            draft.copy(characters = draft.characters.map { if (it.id == id) transform(it) else it })
        }

    fun updateCharacterPrompt(
        id: String,
        expectedEditorRevision: Int,
        historyKey: String,
        transform: (NovelAiCharacterPromptDraft) -> NovelAiCharacterPromptDraft
    ) {
        if (_uiState.value.promptEditorRevision != expectedEditorRevision) return
        updateCharacter(id, historyKey, transform)
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
        val current = _uiState.value.draft
        if (draft != current) recordDraftChange(current, null)
        _uiState.update {
            it.copy(
                draft = draft,
                canUndoDraft = draftUndo.isNotEmpty(),
                canRedoDraft = draftRedo.isNotEmpty(),
                phase = ImagePromptToolPhase.READY,
                designStatus = "完成",
                error = null
            )
        }
        scheduleDraftSave()
        scheduleTokenCount(draft)
    }

    fun generateImage() {
        if (_uiState.value.isBusy || imageJob?.isActive == true) return
        val draft = _uiState.value.draft
        val configured = draft.activeSettings
        configured.validationError(draft.characters.size)?.let { message ->
            _uiState.update { it.copy(error = message) }
            return
        }
        draft.imageGuidance.validationError(draft.selectedModel)?.let { message ->
            _uiState.update { it.copy(error = message) }
            return
        }
        if (draft.basePrompt.isBlank() || draft.characters.any { it.prompt.isBlank() }) {
            _uiState.update { it.copy(error = "基础 Prompt 与已添加角色 Prompt 不能为空") }
            return
        }
        val estimatedCost = NovelAiImageCostEstimator.estimate(
            configured,
            _uiState.value.account.effectiveUsage,
            draft.imageGuidance,
            _uiState.value.vibeCacheMisses
        )
        _uiState.update {
            it.copy(
                phase = ImagePromptToolPhase.GENERATING,
                completedPreviews = emptyList(),
                imageProgress = 0f,
                error = null
            )
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
            try {
                val preparedResult = prepareImageGuidance(token, draft, requestSettings.model)
                val preparedGuidance = preparedResult.guidance
                val generationDraft = draft.copy(imageGuidance = preparedResult.updatedDraft)
                val requestImageSize = preparedResult.focusedInpaintPlan?.requestSize
                    ?: requestSettings.imageSize()
                val images = mutableListOf<ByteArray>()
                var streamError: String? = null
                imageService.generate(
                    token,
                    plan,
                    requestImageSize,
                    requestSettings,
                    preparedGuidance
                ).collect { event ->
                    when (event) {
                        is NovelAiImageEvent.Intermediate -> _uiState.update {
                            it.copy(
                                phase = ImagePromptToolPhase.STREAMING,
                                imagePreview = event.image,
                                imagePaths = emptyList(),
                                selectedOutputPath = null,
                                selectedOutputIndex = 0,
                                imageProgress = ((images.size + event.progress) / requestSettings.count).coerceIn(0f, 1f)
                            )
                        }
                        is NovelAiImageEvent.Final -> {
                            val finalImage = if (preparedResult.focusedInpaintPlan != null) {
                                withContext(Dispatchers.Default) {
                                    NovelAiInpaintResultComposer.compose(
                                        generatedPng = event.image,
                                        baseImage = requireNotNull(generationDraft.imageGuidance.baseImage),
                                        focusedPlan = preparedResult.focusedInpaintPlan,
                                        blendMaskAlpha = requireNotNull(preparedResult.focusedInpaintBlendMask)
                                    )
                                }
                            } else {
                                event.image
                            }
                            images += finalImage
                            _uiState.update {
                                it.copy(
                                    phase = ImagePromptToolPhase.STREAMING,
                                    imagePreview = finalImage,
                                    completedPreviews = images.toList(),
                                    imagePaths = emptyList(),
                                    selectedOutputPath = null,
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
                        recipe = generationDraft.toRecipe(requestSettings),
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
                        imageProgress = 1f,
                        vibeCacheMisses = countVibeCacheMisses(it.draft)
                    )
                }
                if (estimatedCost.anlas > 0) {
                    _uiState.update { state ->
                        state.copy(account = state.account.recordAnlasGeneration(estimatedCost.anlas.toLong()))
                    }
                }
                if (generationDraft.selectedModel == NovelAiImageModel.V5_FULL &&
                    estimatedCost.kind == com.example.chatbar.domain.image.NovelAiGenerationChargeKind.V5_ALLOWANCE
                ) {
                    _uiState.update { state ->
                        state.copy(account = state.account.recordV5Generation(requestSettings.count))
                    }
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
        }.also { job -> job.invokeOnCompletion { if (imageJob === job) imageJob = null } }
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
                if (appliedDraft != snapshot.draft) recordDraftChange(snapshot.draft, null)
                _uiState.update {
                    it.copy(
                        draft = appliedDraft,
                        canUndoDraft = draftUndo.isNotEmpty(),
                        canRedoDraft = draftRedo.isNotEmpty(),
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
                val result = danbooruTagCatalog.search(fragment.query)
                _uiState.update {
                    it.copy(tagSuggestions = NovelAiTagSuggestionState(field = field, candidates = result.candidates.take(8)))
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(tagSuggestions = NovelAiTagSuggestionState(field = field, error = "补全不可用：${error.message ?: "词条库错误"}"))
                }
            }
        }
    }

    fun clearTagSuggestions() {
        tagJob?.cancel()
        _uiState.update { it.copy(tagSuggestions = NovelAiTagSuggestionState()) }
    }

    fun setPromptTranslationEnabled(enabled: Boolean) {
        val consent = if (enabled) {
            NovelAiPromptTranslationConsent.ENABLED
        } else {
            NovelAiPromptTranslationConsent.DISABLED
        }
        _uiState.update { it.copy(promptTranslationConsent = consent) }
        if (enabled) {
            schedulePromptAnnotations(_uiState.value.draft, delayMillis = 0L)
        } else {
            cancelPromptAnnotations(clear = true)
        }
        viewModelScope.launch {
            settingsRepository.initialize()
            val current = settingsRepository.currentAppSettings
            settingsRepository.saveAppSettings(current.copy(novelAiPromptTranslationConsent = consent))
        }
    }

    fun consumePromptTranslationNotice() {
        _uiState.update { it.copy(promptTranslationNotice = null) }
    }

    fun requestFullscreenPromptAnnotations(
        field: NovelAiPromptFieldKey,
        text: String,
        naturalLanguage: Boolean
    ) {
        if (_uiState.value.promptTranslationConsent != NovelAiPromptTranslationConsent.ENABLED) return
        schedulePromptAnnotations(
            draft = _uiState.value.draft,
            only = NovelAiPromptTranslationInput(field, text, naturalLanguage)
        )
    }

    fun restoreDraftPromptAnnotations() {
        if (_uiState.value.promptTranslationConsent == NovelAiPromptTranslationConsent.ENABLED) {
            schedulePromptAnnotations(_uiState.value.draft, delayMillis = 0L)
        }
    }

    fun undoHistoryApply() {
        viewModelScope.launch {
            val previous = repository.loadUndoDraft() ?: return@launch
            val current = _uiState.value.draft
            repository.saveDraft(previous, overwriteNewer = true)
            repository.clearUndoDraft()
            if (previous != current) recordDraftChange(current, null)
            _uiState.update {
                it.copy(
                    draft = previous,
                    canUndoDraft = draftUndo.isNotEmpty(),
                    canRedoDraft = draftRedo.isNotEmpty(),
                    hasHistoryUndo = false
                )
            }
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
        if (_uiState.value.isGeneratingImage) {
            _uiState.update { it.copy(phase = ImagePromptToolPhase.CANCELLING) }
        }
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

    fun openAiDesign(onPersisted: () -> Unit) {
        if (_uiState.value.isBusy || _uiState.value.applyingHistory) return
        draftSaveJob?.cancel()
        val draft = _uiState.value.draft
        draftSaveJob = viewModelScope.launch {
            try {
                repository.saveDraft(draft)
                onPersisted()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(error = "打开 AI 设计前保存工作室失败：${error.message ?: "未知错误"}")
                }
            }
        }
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

    private suspend fun prepareImageGuidance(
        token: String,
        draft: NovelAiStudioDraft,
        model: NovelAiImageModel
    ): PreparedImageGuidanceResult = withContext(Dispatchers.IO) {
        val guidance = draft.imageGuidance
        fun encodedFile(asset: NovelAiStudioAssetRef?): String? = asset?.takeIf(NovelAiStudioAssetRef::isUsable)?.let {
            val file = File(it.path)
            require(file.isFile) { "图像引导文件不存在" }
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        }
        val effectiveMode = guidance.effectiveReferenceMode(model)
        val usableVibes = if (effectiveMode == NovelAiReferenceMode.VIBE) {
            guidance.vibes.filter(NovelAiVibeReferenceDraft::isUsable)
        } else emptyList()
        val strengths = guidance.copy(vibes = usableVibes).effectiveVibeStrengths()
        val encodedById = mutableMapOf<String, String>()
        val preparedVibes = usableVibes.mapIndexed { index, vibe ->
            val encoding = vibe.encodedVibe?.takeIf(String::isNotBlank) ?: vibe.asset?.let { asset ->
                vibeEncoder.resolve(token, asset, model, vibe.informationExtracted).also { encodedById[vibe.id] = it }
            } ?: error("氛围参考缺少原图或编码")
            NovelAiPreparedVibeReference(encoding, vibe.informationExtracted, strengths[index])
        }
        val updated = if (encodedById.isEmpty()) guidance else guidance.copy(
            vibes = guidance.vibes.map { vibe ->
                encodedById[vibe.id]?.let { vibe.copy(encodedVibe = it) } ?: vibe
            }
        )
        val focusedInpaint = if (guidance.action == NovelAiGenerationAction.INPAINT) {
            NovelAiFocusedInpaintProcessor.prepare(
                baseImage = requireNotNull(guidance.baseImage) { "聚焦重绘缺少原图" },
                originalMask = guidance.maskImage,
                region = requireNotNull(guidance.focusedInpaintRegion) { "聚焦重绘缺少聚焦区域" },
                minimumContextPixels = guidance.focusedInpaintMinimumContext
            )
        } else {
            null
        }
        PreparedImageGuidanceResult(
            guidance = NovelAiPreparedImageGuidance(
                action = guidance.action,
                imageBase64 = focusedInpaint?.imageBase64 ?: encodedFile(guidance.baseImage),
                maskBase64 = focusedInpaint?.maskBase64,
                imageToImageStrength = guidance.imageToImageStrength,
                imageToImageNoise = guidance.imageToImageNoise,
                inpaintStrength = guidance.inpaintStrength,
                preciseReferenceBase64 = if (effectiveMode == NovelAiReferenceMode.PRECISE) {
                    guidanceAssets.encodePreciseReference(
                        requireNotNull(guidance.preciseReference.asset) { "精确参考图片不可用" }
                    )
                } else null,
                preciseReferenceType = guidance.preciseReference.type,
                preciseReferenceStrength = guidance.preciseReference.strength,
                preciseReferenceFidelity = guidance.preciseReference.fidelity,
                vibes = preparedVibes
            ),
            updatedDraft = updated,
            focusedInpaintPlan = focusedInpaint?.plan,
            focusedInpaintBlendMask = focusedInpaint?.blendMaskAlpha
        )
    }

    private fun countVibeCacheMisses(draft: NovelAiStudioDraft): Int {
        if (draft.imageGuidance.effectiveReferenceMode(draft.selectedModel) != NovelAiReferenceMode.VIBE) return 0
        return draft.imageGuidance.vibes.count { vibe ->
            vibe.isUsable && vibe.encodedVibe.isNullOrBlank() && vibe.asset?.let { asset ->
                !vibeEncoder.isCached(asset.sha256, draft.selectedModel, vibe.informationExtracted)
            } == true
        }
    }

    private fun matchSettingsToAsset(
        settings: NovelAiGenerationSettings,
        asset: NovelAiStudioAssetRef
    ): NovelAiGenerationSettings {
        val aspect = NovelAiAspectRatio.entries.firstOrNull { candidate ->
            val size = settings.copy(aspectRatio = candidate).normalized().imageSize()
            size.width == asset.width && size.height == asset.height
        } ?: settings.aspectRatio
        return settings.copy(aspectRatio = aspect).normalized()
    }

    private suspend fun cleanupGuidanceAssets(draft: NovelAiStudioDraft) {
        val undo = repository.loadUndoDraft()
        val retainedHistoryPaths = (draftUndo + draftRedo)
            .flatMap { it.imageGuidance.ownedAssetPaths() }
        withContext(Dispatchers.IO) {
            guidanceAssets.cleanupOrphans(
                draft.imageGuidance.ownedAssetPaths() +
                    undo?.imageGuidance?.ownedAssetPaths().orEmpty() +
                    retainedHistoryPaths
            )
        }
    }

    private fun recordDraftChange(before: NovelAiStudioDraft, historyKey: String?) {
        val now = System.currentTimeMillis()
        val coalesced = historyKey != null &&
            historyKey == lastDraftHistoryKey &&
            now - lastDraftHistoryAt <= DRAFT_HISTORY_COALESCE_MS &&
            draftUndo.isNotEmpty()
        if (!coalesced) {
            draftUndo += before
            trimDraftHistory(draftUndo)
        }
        draftRedo.clear()
        lastDraftHistoryKey = historyKey
        lastDraftHistoryAt = now
    }

    private fun resetDraftCoalescing() {
        lastDraftHistoryKey = null
        lastDraftHistoryAt = 0L
    }

    private fun trimDraftHistory(history: MutableList<NovelAiStudioDraft>) {
        while (history.size > MAX_DRAFT_HISTORY) history.removeAt(0)
    }

    private fun applyDraftHistoryState(draft: NovelAiStudioDraft) {
        val restored = draft.copy(updatedAt = System.currentTimeMillis())
        _uiState.update {
            it.copy(
                draft = restored,
                canUndoDraft = draftUndo.isNotEmpty(),
                canRedoDraft = draftRedo.isNotEmpty(),
                vibeCacheMisses = countVibeCacheMisses(restored),
                phase = if (it.isGeneratingImage) it.phase
                else if (restored.basePrompt.isBlank()) ImagePromptToolPhase.IDLE
                else ImagePromptToolPhase.READY,
                tagSuggestions = NovelAiTagSuggestionState(),
                error = null
            )
        }
        scheduleDraftSave()
        scheduleTokenCount(restored)
        viewModelScope.launch { cleanupGuidanceAssets(restored) }
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

    private fun schedulePromptAnnotations(
        draft: NovelAiStudioDraft,
        delayMillis: Long = PROMPT_TRANSLATION_DEBOUNCE_MS,
        only: NovelAiPromptTranslationInput? = null
    ) {
        if (_uiState.value.promptTranslationConsent != NovelAiPromptTranslationConsent.ENABLED) {
            cancelPromptAnnotations(clear = true)
            return
        }
        val inputs = only?.let(::listOf) ?: draft.promptTranslationInputs()
        val textByField = inputs.associate { it.field to it.text }
        _uiState.update { state ->
            val retained = if (only == null) {
                state.promptAnnotations.mapNotNull { (field, annotations) ->
                    val text = textByField[field] ?: return@mapNotNull null
                    field to annotations.filter { annotation -> annotation.matches(text) }
                }.toMap()
            } else {
                state.promptAnnotations + (
                    only.field to state.promptAnnotations[only.field].orEmpty()
                        .filter { annotation -> annotation.matches(only.text) }
                    )
            }
            state.copy(promptAnnotations = retained)
        }
        promptTranslationRevision++
        val revision = promptTranslationRevision
        promptTranslationJob?.cancel()
        if (inputs.all { it.segments.isEmpty() }) {
            _uiState.update { state ->
                state.copy(
                    promptAnnotations = if (only == null) emptyMap()
                    else state.promptAnnotations + (only.field to emptyList())
                )
            }
            return
        }
        promptTranslationJob = viewModelScope.launch {
            try {
                val segmentsByField = inputs.associate { it.field to it.segments }
                val flattenedSegments = segmentsByField.values.flatten()
                val cached = promptTranslationService.immediateTranslations(flattenedSegments)
                if (revision != promptTranslationRevision) return@launch
                applyPromptTranslations(segmentsByField, cached, only)
                delay(delayMillis)
                val result = promptTranslationService.resolve(segmentsByField.values.flatten())
                if (revision != promptTranslationRevision) return@launch
                applyPromptTranslations(segmentsByField, result.translations, only)
                publishPromptTranslationWarning(result.warning)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (revision == promptTranslationRevision) {
                    publishPromptTranslationWarning(
                        "Prompt 中文注释不可用：${error.message ?: "网络错误"}"
                    )
                }
            }
        }
    }

    private fun applyPromptTranslations(
        segmentsByField: Map<NovelAiPromptFieldKey, List<NovelAiPromptTranslationSegment>>,
        translations: Map<String, String>,
        only: NovelAiPromptTranslationInput?
    ) {
        val resolvedByField = segmentsByField.mapValues { (_, segments) ->
            segments.mapNotNull { segment ->
                translations[segment.cacheKey]?.let { translation ->
                    NovelAiPromptAnnotation(
                        start = segment.start,
                        end = segment.end,
                        source = segment.source,
                        translation = translation
                    )
                }
            }
        }
        _uiState.update { state ->
            state.copy(
                promptAnnotations = if (only == null) resolvedByField
                else state.promptAnnotations + resolvedByField
            )
        }
    }

    private fun cancelPromptAnnotations(clear: Boolean) {
        promptTranslationRevision++
        promptTranslationJob?.cancel()
        promptTranslationJob = null
        if (clear && _uiState.value.promptAnnotations.isNotEmpty()) {
            _uiState.update { it.copy(promptAnnotations = emptyMap()) }
        }
    }

    private fun publishPromptTranslationWarning(warning: String?) {
        if (warning.isNullOrBlank() || promptTranslationFailureShown) return
        promptTranslationFailureShown = true
        _uiState.update { it.copy(promptTranslationNotice = warning) }
    }

    private fun NovelAiPromptAnnotation.matches(text: String): Boolean =
        start >= 0 && end in start..text.length && text.substring(start, end) == source

    private fun NovelAiStudioDraft.promptTranslationInputs(): List<NovelAiPromptTranslationInput> =
        buildList {
            add(NovelAiPromptTranslationInput(NovelAiPromptFieldKey("style"), stylePrompt, false))
            add(NovelAiPromptTranslationInput(NovelAiPromptFieldKey("base"), basePrompt, false))
            add(NovelAiPromptTranslationInput(NovelAiPromptFieldKey("negative"), negativePrompt, false))
            characters.forEach { character ->
                add(
                    NovelAiPromptTranslationInput(
                        NovelAiPromptFieldKey("character", character.id),
                        character.prompt,
                        false
                    )
                )
                add(
                    NovelAiPromptTranslationInput(
                        NovelAiPromptFieldKey("character_negative", character.id),
                        character.negativePrompt,
                        false
                    )
                )
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
                _uiState.update { state -> state.copy(account = state.account.reconcile(usage)) }
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
            combine(settingsRepository.appSettings, repository.draft) { settings, draft -> settings to draft }
                .collect { (settings, draft) ->
                val models = modelResolver.availableChatModels(settings)
                val defaultModel = modelResolver.defaultImageModel(settings)
                val explicitModelId = draft?.aiDesignModelId
                val selectedModel = if (explicitModelId == null) {
                    defaultModel
                } else {
                    models.firstOrNull { it.id == explicitModelId }
                }
                val errors = buildList {
                    if (explicitModelId != null && selectedModel == null) {
                        add("已选择的生图辅助模型不可用，请在 AI 设计设置中重新选择")
                    } else if (selectedModel == null) {
                        add("未配置可用默认生图辅助模型")
                    } else if (!selectedModel.hasConfiguredAuthentication(settings)) {
                        add("生图辅助模型/API Key 未配置")
                    }
                }
                _uiState.update {
                    it.copy(
                        models = models,
                        selectedModelId = selectedModel?.id,
                        modelErrors = errors,
                        modelUsable = errors.isEmpty(),
                        promptTranslationConsent = settings.novelAiPromptTranslationConsent,
                        promptTranslationPreferenceLoaded = true
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
        promptTranslationJob?.cancel()
        tokenCountJob?.cancel()
        designJob?.cancel()
        imageJob?.cancel()
        imageImportJob?.cancel()
        guidanceCheckpointJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAX_DRAFT_HISTORY = 80
        const val DRAFT_HISTORY_COALESCE_MS = 800L
        const val PROMPT_TRANSLATION_DEBOUNCE_MS = 200L
    }
}
