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
import androidx.compose.runtime.mutableStateMapOf
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
import com.example.chatbar.data.local.entity.EditorDraft
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.RagIndexStatus
import com.example.chatbar.data.local.entity.SpeakerTagRename
import com.example.chatbar.data.local.entity.SpeakerTagRenameTask
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.domain.card.CharacterSpeakerNamePolicy
import com.example.chatbar.domain.card.NamePolicy
import com.example.chatbar.domain.card.CharacterAutoFillDraft
import com.example.chatbar.domain.card.CharacterRewriteDraft
import com.example.chatbar.domain.draft.CharacterOpenModalState
import com.example.chatbar.domain.image.ImageCropFractionRect
import com.example.chatbar.domain.image.ImageFileEncoder
import com.example.chatbar.domain.image.NovelAiImageEvent
import com.example.chatbar.domain.image.NovelAiImageSizePolicy
import com.example.chatbar.domain.image.NovelAiImageSizePreset
import com.example.chatbar.domain.image.NovelAiPromptPlan
import com.example.chatbar.domain.image.hasImageDesignSource
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.domain.search.ResearchDebugSnapshot
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
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

data class CharacterAvatarImageUiState(
    val characterId: String? = null,
    val isGenerating: Boolean = false,
    val preview: ByteArray? = null,
    val progress: Float = 0f,
    val path: String? = null,
    val promptInputText: String = "",
    val promptDesignReasoningText: String = "",
    val promptDesignOutputText: String = "",
    val promptText: String = "",
    val error: String? = null,
    val statusText: String = ""
)

data class CharacterRewriteDiffUiState(
    val sections: List<CharacterRewriteDiffSection> = emptyList()
)

data class CharacterRewriteDiffSection(
    val title: String,
    val kind: CharacterRewriteDiffKind,
    val rows: List<CharacterRewriteDiffRow>
)

data class CharacterRewriteDiffRow(
    val label: String,
    val before: String,
    val after: String,
    val kind: CharacterRewriteDiffKind,
    val beforeFragments: List<CharacterRewriteDiffFragment> = emptyList(),
    val afterFragments: List<CharacterRewriteDiffFragment> = emptyList()
)

data class CharacterRewriteDiffFragment(
    val text: String,
    val kind: CharacterRewriteTextDiffKind
)

enum class CharacterRewriteDiffKind {
    Added,
    Removed,
    Modified
}

enum class CharacterRewriteTextDiffKind {
    Unchanged,
    Added,
    Removed
}

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
    val diff: CharacterRewriteDiffUiState = CharacterRewriteDiffUiState(),
    val error: String? = null,
    val streamingText: String = "",
    val statusText: String = "",
    val progressLines: List<String> = emptyList(),
    val researchDebug: ResearchDebugSnapshot? = null,
    val visibleOutputs: List<CharacterAiOutputUiState> = emptyList(),
    val coverImage: CharacterCoverImageUiState = CharacterCoverImageUiState()
)

private enum class CoverImageTarget { Current, AutoFill, Rewrite }

private data class CharacterAvatarPromptInput(
    val imageDescription: String,
    val stylePrompt: String,
    val characterPrompt: String,
    val previewText: String
)

class CharacterEditViewModel(
    private val characterId: String?,
    routeDraftId: String
) : ViewModel() {
    private val characterRepository = ChatBarApp.instance.characterRepository
    private val worldBookRepository = ChatBarApp.instance.worldBookRepository
    private val settingsRepository = ChatBarApp.instance.settingsRepository
    private val draftRepository = ChatBarApp.instance.editorDraftRepository
    private val draftAssetService = ChatBarApp.instance.editorDraftAssetService
    private val ragManager = ChatBarApp.instance.ragManager
    private val modelResolver = ChatBarApp.instance.effectiveModelResolver
    private val characterAutoFillService = ChatBarApp.instance.characterAutoFillService
    private val characterRewriteService = ChatBarApp.instance.characterRewriteService
    private val novelAiCredentials = ChatBarApp.instance.novelAiCredentialStore
    private val novelAiPromptDesigner = ChatBarApp.instance.novelAiPromptDesigner
    private val novelAiImageService = ChatBarApp.instance.novelAiImageService
    private val novelAiImageStorage = ChatBarApp.instance.novelAiImageStorage
    private val speakerTagHistoryService = ChatBarApp.instance.speakerTagHistoryService
    private val draftJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var indexingJob: Job? = null
    private var autoFillJob: Job? = null
    private var autoFillGenerationToken = 0
    private var rewriteJob: Job? = null
    private var rewriteGenerationToken = 0
    private var coverImageJob: Job? = null
    private var coverImageGenerationToken = 0
    private var coverImageTarget: CoverImageTarget? = null
    private var avatarImageJob: Job? = null
    private var avatarImageGenerationToken = 0
    private var baseCard: CharacterCard? = null
    private var loadedDraft: EditorDraft? = null
    private var draftJob: Job? = null
    private val draftSessionId = routeDraftId.ifBlank { UUID.randomUUID().toString() }
    private val pendingDeletedAssets = mutableSetOf<String>()
    private val pendingDeletedDocumentIds = mutableSetOf<String>()
    private val transientGeneratedAvatarPaths = mutableSetOf<String>()

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

    private val _avatarImageState = MutableStateFlow(CharacterAvatarImageUiState())
    val avatarImageState: StateFlow<CharacterAvatarImageUiState> = _avatarImageState.asStateFlow()

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
    var defaultImageNegativePrompt by mutableStateOf(PromptTemplates.defaultCharacterNaiNegativePrompt())
    var systemPrompt by mutableStateOf("")
    var postHistoryInstructions by mutableStateOf("")
    var mesExample by mutableStateOf("")
    var creatorNotes by mutableStateOf("")
    var momentsEnabled by mutableStateOf(true)
    val charactersList = mutableStateListOf<CharacterInfo>()
    private val freeformAvatarPromptDrafts = mutableStateMapOf<String, String>()
    val documentsList = mutableStateListOf<DocumentInfo>()
    val selectedWorldBookIds = mutableStateListOf<String>()
    val worldBookEntries = mutableStateListOf<com.example.chatbar.data.local.entity.WorldBookEntry>()
    var draftSavedAt by mutableStateOf<Long?>(null)
        private set
    var draftReady by mutableStateOf(false)
        private set
    var hasLocalChanges by mutableStateOf(false)
        private set
    var restoreDraft by mutableStateOf<EditorDraft?>(null)
        private set
    var restoreConflict by mutableStateOf(false)
        private set
    var saveConflict by mutableStateOf(false)
    var sourceDeleted by mutableStateOf(false)
        private set
    var restoredOpenModalState by mutableStateOf<CharacterOpenModalState?>(null)
        private set
    var openModalState by mutableStateOf<CharacterOpenModalState?>(null)
        private set

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
            val draft = draftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, characterId)
            if (characterId != null) {
                val card = characterRepository.getById(characterId)
                baseCard = card
                if (card != null) {
                    applyCard(card)
                } else {
                    sourceDeleted = draft != null
                }
                if (draft != null) {
                    if (card == null) {
                        loadedDraft = draft.copy(targetId = null)
                        draft.characterPayload?.let(::applyCard)
                        restoreOpenModal(draft.openModalState)
                        pendingDeletedAssets.addAll(draft.pendingDeletedAssets)
                        pendingDeletedDocumentIds.addAll(draft.pendingDeletedDocumentIds)
                        hasLocalChanges = true
                    } else {
                        restoreDraft = draft
                        restoreConflict = draftRepository.isChanged(card, draft)
                    }
                }
            } else {
                val newDraft = draft ?: draftRepository.getLatestNew(EditorDraftType.CHARACTER_CARD)
                if (newDraft?.characterPayload != null) {
                    loadedDraft = newDraft
                    applyCard(newDraft.characterPayload)
                    restoreOpenModal(newDraft.openModalState)
                    pendingDeletedAssets.addAll(newDraft.pendingDeletedAssets)
                    pendingDeletedDocumentIds.addAll(newDraft.pendingDeletedDocumentIds)
                    hasLocalChanges = true
                } else {
                    charactersList.add(CharacterInfo.create(""))
                }
            }
            draftReady = true
        }
    }

    fun restoreDraft() {
        restoreDraft?.let { draft ->
            loadedDraft = draft
            draft.characterPayload?.let(::applyCard)
            restoreOpenModal(draft.openModalState)
            pendingDeletedAssets.clear()
            pendingDeletedAssets.addAll(draft.pendingDeletedAssets)
            pendingDeletedDocumentIds.clear()
            pendingDeletedDocumentIds.addAll(draft.pendingDeletedDocumentIds)
            hasLocalChanges = true
        }
        restoreDraft = null
        restoreConflict = false
    }

    fun keepOriginal() {
        restoreDraft = null
        restoreConflict = false
    }

    fun discardDraft(onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            draftJob?.cancel()
            loadedDraft?.draftSessionId?.let { draftAssetService.deleteDraft(it) } ?: draftAssetService.deleteDraft(draftSessionId)
            loadedDraft?.id?.let { draftRepository.delete(it) }
            draftRepository.deleteForTarget(EditorDraftType.CHARACTER_CARD, characterId)
            loadedDraft = null
            pendingDeletedAssets.clear()
            pendingDeletedDocumentIds.clear()
            openModalState = null
            restoredOpenModalState = null
            hasLocalChanges = false
            restoreDraft = null
            restoreConflict = false
            onDone?.invoke()
        }
    }

    fun saveCharacterCard(onSuccess: () -> Unit, forceOverwrite: Boolean = false, saveAsNew: Boolean = false) {
        if (_characterCard.value?.isCommunityDownload == true) {
            _indexingStatus.value = "下载角色卡只读，请复制后编辑"
            return
        }
        if (validateForSave().isNotEmpty()) return
        _isSaving.value = true

        viewModelScope.launch {
            name = NamePolicy.normalize(name)
            val targetId = if (saveAsNew || sourceDeleted) null else characterId
            if (!forceOverwrite && targetId != null && loadedDraft != null && draftRepository.isChanged(characterRepository.getById(targetId), loadedDraft!!)) {
                saveConflict = true
                _isSaving.value = false
                return@launch
            }
            val conflict = characterRepository.getAll().firstOrNull {
                it.id != targetId && NamePolicy.isSame(it.name, name)
            }
            if (conflict != null && targetId != null) {
                _indexingStatus.value = "角色卡名称与“${conflict.name}”冲突"
                _isSaving.value = false
                return@launch
            }
            if (targetId == null && conflict != null) {
                name = NamePolicy.nextCopyName(name, characterRepository.getAll().map { it.name })
            }
            val oldCard = targetId?.let { characterRepository.getById(it) }
            val rawCard = buildCurrentCard(markDirty = true).let { card ->
                if (targetId == null) {
                    val now = System.currentTimeMillis()
                    card.copy(id = UUID.randomUUID().toString(), createdAt = now, updatedAt = now)
                } else {
                    card
                }
            }
            val materializedCard = draftAssetService.materializeCharacterAssets(rawCard)
            val speakerRenames = buildSpeakerRenames(oldCard, materializedCard)
            val speakerRenameTask = speakerTagHistoryService.createTask(
                characterCardId = materializedCard.id,
                expectedCardUpdatedAt = materializedCard.updatedAt,
                renames = speakerRenames
            )
            val card = materializedCard.copy(
                pendingSpeakerRenameTasks = materializedCard.pendingSpeakerRenameTasks +
                    listOfNotNull(speakerRenameTask)
            )
            try {
                characterRepository.save(card)
            } catch (error: Throwable) {
                _indexingStatus.value = "角色卡保存失败：${error.message}"
                _isSaving.value = false
                return@launch
            }
            transientGeneratedAvatarPaths.removeAll(
                card.characters.mapNotNull(CharacterInfo::appearanceImage).toSet()
            )
            _characterCard.value = card
            var speakerRenameFailure: String? = null
            for (task in card.pendingSpeakerRenameTasks.sortedBy(SpeakerTagRenameTask::createdAt)) {
                val failure = runCatching { speakerTagHistoryService.execute(card.id, task.id) }.exceptionOrNull()
                if (failure != null) {
                    speakerRenameFailure = "角色名已保存，历史消息改名待重试：${failure.message}"
                    break
                }
            }
            val effectiveCard = characterRepository.getById(card.id) ?: card
            _characterCard.value = effectiveCard
            cleanupAfterCharacterSave(oldCard, effectiveCard)
            loadedDraft?.id?.let { draftRepository.delete(it) }
            draftRepository.deleteForTarget(EditorDraftType.CHARACTER_CARD, characterId)
            loadedDraft?.draftSessionId?.let { draftAssetService.deleteDraft(it) } ?: draftAssetService.deleteDraft(draftSessionId)
            pendingDeletedAssets.clear()
            pendingDeletedDocumentIds.clear()
            loadedDraft = null
            openModalState = null
            restoredOpenModalState = null
            hasLocalChanges = false
            _isSaving.value = false
            startBackgroundIndex(effectiveCard)
            if (effectiveCard.momentsEnabled) ChatBarApp.instance.momentScheduler.kick("character-save")
            if (speakerRenameFailure == null) {
                onSuccess()
            } else {
                _indexingStatus.value = speakerRenameFailure
            }
        }
    }

    fun validateForSave(): List<String> {
        val errors = mutableListOf<String>()
        if (name.isBlank()) errors += "角色卡名称不能为空。"
        if (greeting.isBlank()) errors += "开场白不能为空。"
        val duplicateNames = CharacterSpeakerNamePolicy.duplicateNames(charactersList.toList())
        if (duplicateNames.isNotEmpty()) {
            errors += "人物名称不能重复：${duplicateNames.distinct().joinToString("、")}"
        }
        return errors
    }

    fun scheduleDraftSave() {
        if (!draftReady || restoreDraft != null) return
        hasLocalChanges = true
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(700)
            saveDraftNow()
        }
    }

    fun saveDraftAndExit(onDone: () -> Unit) {
        viewModelScope.launch {
            draftJob?.cancel()
            saveDraftNow()
            onDone()
        }
    }

    fun updateOpenModalState(state: CharacterOpenModalState?) {
        openModalState = state
        scheduleDraftSave()
    }

    fun consumeRestoredOpenModalState() {
        restoredOpenModalState = null
    }

    private suspend fun saveDraftNow() {
        if (!draftReady || restoreDraft != null) return
        val payload = buildCurrentCard(markDirty = false)
        val draftAssetPaths = draftAssetService.ownedAssetPaths(payload)
            .filter { draftAssetService.isDraftAsset(it) }
        val draft = draftRepository.characterDraft(
            targetId = if (sourceDeleted) null else characterId,
            draftSessionId = draftSessionId,
            payload = payload,
            base = baseCard,
            draftAssetPaths = draftAssetPaths,
            pendingDeletedAssets = pendingDeletedAssets.toList(),
            pendingDeletedDocumentIds = pendingDeletedDocumentIds.toList(),
            openModalState = openModalState?.let {
                draftJson.encodeToString(CharacterOpenModalState.serializer(), it)
            }
        )
        loadedDraft = draftRepository.save(draft)
        draftSavedAt = loadedDraft?.updatedAt
    }

    private fun restoreOpenModal(raw: String?) {
        val state = raw?.let {
            runCatching { draftJson.decodeFromString(CharacterOpenModalState.serializer(), it) }.getOrNull()
        }
        openModalState = state
        restoredOpenModalState = state
    }

    fun switchEditMode(target: CharacterEditMode) {
        if (target == editMode) return
        editMode = target
        scheduleDraftSave()
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
        defaultImageNegativePrompt = PromptTemplates.effectiveCharacterNaiNegativePrompt(
            merged.defaultImageNegativePrompt
        )
        charactersList.clear()
        charactersList.addAll(merged.characters)
        state.coverImage.path?.takeIf(String::isNotBlank)?.let { path ->
            avatar = path
            chatBackground = path
        }
        _autoFillState.value = CharacterAutoFillUiState()
    }

    fun freeformAvatarPrompt(characterId: String): String =
        freeformAvatarPromptDrafts[characterId].orEmpty()

    fun updateFreeformAvatarPrompt(characterId: String, value: String) {
        if (value.isEmpty()) {
            freeformAvatarPromptDrafts.remove(characterId)
        } else {
            freeformAvatarPromptDrafts[characterId] = value
        }
    }

    fun generateCharacterAvatar(character: CharacterInfo) {
        val currentState = _avatarImageState.value
        if (currentState.isGenerating || !currentState.path.isNullOrBlank()) {
            _avatarImageState.value = currentState.copy(error = "请先处理当前头像候选")
            return
        }
        val promptInput = if (editMode == CharacterEditMode.FREEFORM) {
            val manualPrompt = freeformAvatarPrompt(character.id).trim()
            if (manualPrompt.isEmpty()) {
                _avatarImageState.value = CharacterAvatarImageUiState(
                    characterId = character.id,
                    error = "请先输入完整 NovelAI 正向 Prompt",
                    statusText = "头像未生成"
                )
                return
            }
            CharacterAvatarPromptInput(
                imageDescription = character.name.trim(),
                stylePrompt = "",
                characterPrompt = manualPrompt,
                previewText = PromptTemplates.novelAiCharacterAvatarPositivePrompt(manualPrompt)
            )
        } else {
            val characterPrompt = character.imagePrompt.trim()
            if (characterPrompt.isEmpty()) {
                _avatarImageState.value = CharacterAvatarImageUiState(
                    characterId = character.id,
                    error = "请先填写 NovelAI 人物提示词",
                    statusText = "头像未生成"
                )
                return
            }
            CharacterAvatarPromptInput(
                imageDescription = character.name.trim(),
                stylePrompt = defaultImagePrompt,
                characterPrompt = characterPrompt,
                previewText = PromptTemplates.novelAiCharacterAvatarPositivePrompt(defaultImagePrompt, characterPrompt)
            )
        }
        startCharacterAvatarGeneration(character.id, promptInput)
    }

    fun applyCharacterAvatarCandidate(characterId: String): String? {
        val state = _avatarImageState.value
        if (state.characterId != characterId) return null
        val path = state.path?.takeIf(String::isNotBlank) ?: return null
        transientGeneratedAvatarPaths += path
        _avatarImageState.value = CharacterAvatarImageUiState()
        return path
    }

    fun discardTransientGeneratedAvatar(path: String?, preservedPath: String? = null) {
        if (path.isNullOrBlank() || path == preservedPath) return
        if (transientGeneratedAvatarPaths.remove(path)) {
            novelAiImageStorage.deleteIfOwned(path)
        }
    }

    fun clearCharacterAvatarCandidate(characterId: String? = null) {
        val state = _avatarImageState.value
        if (characterId != null && state.characterId != characterId) return
        if (state.isGenerating) {
            cancelCharacterAvatarGeneration()
            _avatarImageState.value = CharacterAvatarImageUiState()
            return
        }
        state.path?.let(novelAiImageStorage::deleteIfOwned)
        _avatarImageState.value = CharacterAvatarImageUiState()
    }

    fun cancelCharacterAvatarGeneration() {
        if (!_avatarImageState.value.isGenerating) return
        avatarImageGenerationToken += 1
        avatarImageJob?.cancel()
        avatarImageJob = null
        _avatarImageState.value = _avatarImageState.value.copy(
            isGenerating = false,
            error = "已取消头像生成",
            statusText = "头像生成已取消"
        )
    }

    fun removeCharacterTransientState(characterId: String) {
        freeformAvatarPromptDrafts.remove(characterId)
        clearCharacterAvatarCandidate(characterId)
        charactersList.firstOrNull { it.id == characterId }?.appearanceImage?.let { path ->
            discardTransientGeneratedAvatar(path)
        }
    }

    private fun startCharacterAvatarGeneration(characterId: String, promptInput: CharacterAvatarPromptInput) {
        avatarImageGenerationToken += 1
        val generationToken = avatarImageGenerationToken
        avatarImageJob?.cancel()
        _avatarImageState.value = CharacterAvatarImageUiState(
            characterId = characterId,
            isGenerating = true,
            promptInputText = promptInput.previewText,
            statusText = "正在设计头像 Prompt"
        )
        avatarImageJob = viewModelScope.launch {
            try {
                val token = withContext(Dispatchers.IO) { novelAiCredentials.load() }
                val settings = settingsRepository.getAppSettings()
                val model = modelResolver.defaultImageModel(settings)
                if (token == null || model == null || model.apiKey.isBlank()) {
                    val missing = mutableListOf<String>()
                    if (token == null) missing += "NovelAI Token"
                    if (model == null || model.apiKey.isBlank()) missing += "默认生图模型/API Key"
                    _avatarImageState.value = _avatarImageState.value.copy(
                        isGenerating = false,
                        error = "缺少${missing.joinToString("、")}，未生成头像",
                        statusText = "头像未生成"
                    )
                    return@launch
                }
                val card = buildCurrentCard(markDirty = false)
                AiBackgroundWorkManager.run(card.id) {
                    val finalPromptRequirement = listOf(
                        settings.imagePromptToolPreference,
                        PromptTemplates.CHARACTER_AVATAR_NAI_COMPOSITION_TAGS
                    )
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .joinToString("\n")
                    val designedPlan = novelAiPromptDesigner.designForPromptTool(
                        imageDescription = promptInput.imageDescription,
                        stylePrompt = promptInput.stylePrompt,
                        characterPrompt = promptInput.characterPrompt,
                        finalPromptRequirement = finalPromptRequirement,
                        model = model,
                        onContentDelta = { promptDraft ->
                            if (generationToken == avatarImageGenerationToken) {
                                _avatarImageState.value = _avatarImageState.value.copy(
                                    promptDesignOutputText = promptDraft,
                                    statusText = "正在设计头像 Prompt"
                                )
                            }
                        },
                        onReasoningDelta = { reasoning ->
                            if (generationToken == avatarImageGenerationToken) {
                                _avatarImageState.value = _avatarImageState.value.copy(
                                    promptDesignReasoningText = reasoning,
                                    statusText = "正在设计头像 Prompt"
                                )
                            }
                        }
                    )
                    if (generationToken != avatarImageGenerationToken) return@run
                    val plan = designedPlan.asCharacterAvatarPlan(defaultImageNegativePrompt)
                    _avatarImageState.value = _avatarImageState.value.copy(
                        promptText = plan.debugPromptText(),
                        statusText = "正在调用 NovelAI 生成头像"
                    )
                    val seed = novelAiImageService.newSeed()
                    var finalImage: ByteArray? = null
                    novelAiImageService.generate(
                        token = token,
                        prompt = plan,
                        seed = seed,
                        imageSize = NovelAiImageSizePreset.SQUARE.imageSize
                    ).collect { event ->
                        if (generationToken != avatarImageGenerationToken) return@collect
                        when (event) {
                            is NovelAiImageEvent.Intermediate -> {
                                finalImage = event.image
                                _avatarImageState.value = _avatarImageState.value.copy(
                                    preview = event.image,
                                    progress = event.progress,
                                    statusText = "正在流式生成头像"
                                )
                            }
                            is NovelAiImageEvent.Final -> {
                                finalImage = event.image
                                _avatarImageState.value = _avatarImageState.value.copy(
                                    preview = event.image,
                                    progress = 1f,
                                    statusText = "正在保存头像候选"
                                )
                            }
                            is NovelAiImageEvent.Error -> error(event.message)
                        }
                    }
                    if (generationToken != avatarImageGenerationToken) return@run
                    val bytes = finalImage ?: error("NovelAI 未返回最终图片")
                    val path = withContext(Dispatchers.IO) {
                        novelAiImageStorage.save("${card.id}_avatar_$characterId", bytes)
                    }
                    if (generationToken != avatarImageGenerationToken) {
                        withContext(Dispatchers.IO) { novelAiImageStorage.deleteIfOwned(path) }
                        return@run
                    }
                    _avatarImageState.value = _avatarImageState.value.copy(
                        isGenerating = false,
                        path = path,
                        preview = bytes,
                        progress = 1f,
                        statusText = "头像候选已生成，等待应用"
                    )
                }
            } catch (_: CancellationException) {
                // State is updated by cancellation or the next generation.
            } catch (error: Throwable) {
                if (generationToken == avatarImageGenerationToken) {
                    _avatarImageState.value = _avatarImageState.value.copy(
                        isGenerating = false,
                        error = error.message ?: "头像生成失败",
                        statusText = "头像生成失败"
                    )
                }
            } finally {
                if (generationToken == avatarImageGenerationToken) avatarImageJob = null
            }
        }
    }

    fun generateCurrentCoverImage(_modelId: String? = null) {
        startCoverImageGeneration(
            target = CoverImageTarget.Current,
            card = buildCurrentCard(markDirty = false),
            previousCandidatePath = _coverImageState.value.path
        )
    }

    fun applyCurrentCoverImageCandidate() {
        val path = _coverImageState.value.path?.takeIf(String::isNotBlank) ?: run {
            _coverImageState.value = _coverImageState.value.copy(error = "没有可应用的封面候选")
            return
        }
        avatar = path
        chatBackground = path
        _coverImageState.value = CharacterCoverImageUiState()
    }

    fun clearCurrentCoverImageCandidate() {
        if (coverImageTarget == CoverImageTarget.Current && _coverImageState.value.isGenerating) {
            cancelCoverImageGeneration()
            return
        }
        deleteAutoFillCandidateImage(_coverImageState.value.path)
        _coverImageState.value = CharacterCoverImageUiState()
    }

    fun generateAutoFillCoverImageCandidate(_modelId: String? = null) {
        val state = _autoFillState.value
        val draft = state.draft ?: run {
            _autoFillState.value = state.copy(
                coverImage = CharacterCoverImageUiState(error = "请先生成角色卡候选")
            )
            return
        }
        val mergedCard = characterAutoFillService.mergeInto(buildCurrentCard(markDirty = false), draft)
        startCoverImageGeneration(
            target = CoverImageTarget.AutoFill,
            card = mergedCard,
            previousCandidatePath = state.coverImage.path
        )
    }

    fun generateRewriteCoverImageCandidate(_modelId: String? = null) {
        val state = _rewriteState.value
        val draft = state.draft ?: run {
            _rewriteState.value = state.copy(
                coverImage = CharacterCoverImageUiState(error = "请先生成改写候选")
            )
            return
        }
        val mergedCard = characterRewriteService.mergeInto(buildCurrentCard(markDirty = false), draft)
        startCoverImageGeneration(
            target = CoverImageTarget.Rewrite,
            card = mergedCard,
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

    private fun startCoverImageGeneration(
        target: CoverImageTarget,
        card: CharacterCard,
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
        deleteAutoFillCandidateImage(previousCandidatePath)
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
                val model = modelResolver.defaultImageModel(settings)
                val imageRatioError = NovelAiImageSizePolicy.validationError(settings.novelAiImageAspectRatio)
                if (token == null || model == null || model.apiKey.isBlank()) {
                    val missing = mutableListOf<String>()
                    if (token == null) missing += "NovelAI Token"
                    if (model == null || model.apiKey.isBlank()) missing += "默认生图模型/API Key"
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
                    val prompt = novelAiPromptDesigner.designForCharacterCard(
                        card = card,
                        model = model,
                        finalPromptRequirement = settings.imagePromptToolPreference
                    ) { promptDraft ->
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
                    updateCoverImageStateIfCurrent(generationToken, target) {
                        it.copy(
                            isGenerating = false,
                            path = path,
                            preview = bytes,
                            progress = 1f,
                            statusText = if (target == CoverImageTarget.Current) {
                                "封面候选已生成，等待应用"
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
            val currentCard = buildCurrentCard(markDirty = false)
            fun rememberVisibleOutput(key: String, title: String, text: String) {
                latestVisibleOutputs = latestVisibleOutputs.upsertAiOutput(key, title, text)
                if (generationToken == rewriteGenerationToken) {
                    _rewriteState.value = _rewriteState.value.copy(visibleOutputs = latestVisibleOutputs)
                }
            }
            try {
                val draft = characterRewriteService.rewriteStreaming(
                    userInput = userInput,
                    currentCard = currentCard,
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
                        diff = buildRewriteDiff(currentCard, draft),
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
        defaultImageNegativePrompt = PromptTemplates.effectiveCharacterNaiNegativePrompt(
            merged.defaultImageNegativePrompt
        )
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

    private fun buildRewriteDiff(
        current: CharacterCard,
        draft: CharacterRewriteDraft
    ): CharacterRewriteDiffUiState {
        var nextNewCharacterIndex = 1
        val merged = characterRewriteService.mergeInto(current, draft) {
            "__rewrite_new_${nextNewCharacterIndex++}"
        }
        val sections = mutableListOf<CharacterRewriteDiffSection>()
        val cardRows = buildCardDiffRows(current, merged)
        if (cardRows.isNotEmpty()) {
            sections += CharacterRewriteDiffSection(
                title = "角色卡字段",
                kind = CharacterRewriteDiffKind.Modified,
                rows = cardRows
            )
        }
        if (current.editMode == CharacterEditMode.STRUCTURED) {
            sections += buildCharacterDiffSections(current.characters, merged.characters)
        }
        return CharacterRewriteDiffUiState(sections)
    }

    private fun buildCardDiffRows(
        current: CharacterCard,
        merged: CharacterCard
    ): List<CharacterRewriteDiffRow> {
        val rows = mutableListOf<CharacterRewriteDiffRow>()
        rows.addChangedDiff("角色卡名称", current.name, merged.name)
        rows.addChangedDiff("开场白", current.greeting, merged.greeting)
        rows.addChangedDiff("基本设定", current.basicSetting, merged.basicSetting)
        rows.addChangedDiff("NovelAI 默认风格", current.defaultImagePrompt, merged.defaultImagePrompt)
        rows.addChangedDiff(
            "NovelAI 默认负面",
            current.defaultImageNegativePrompt,
            merged.defaultImageNegativePrompt
        )
        if (current.editMode == CharacterEditMode.FREEFORM) {
            rows.addChangedDiff("自由人物设定", current.freeformCharacterText, merged.freeformCharacterText)
        }
        return rows
    }

    private fun buildCharacterDiffSections(
        currentCharacters: List<CharacterInfo>,
        mergedCharacters: List<CharacterInfo>
    ): List<CharacterRewriteDiffSection> {
        val currentById = currentCharacters.associateBy { it.id }
        val mergedById = mergedCharacters.associateBy { it.id }
        val sections = mutableListOf<CharacterRewriteDiffSection>()
        mergedCharacters.forEach { merged ->
            val current = currentById[merged.id]
            if (current == null) {
                val rows = buildAddedCharacterRows(merged)
                sections += CharacterRewriteDiffSection(
                    title = "新增人物：${merged.name.ifBlank { "未命名" }}",
                    kind = CharacterRewriteDiffKind.Added,
                    rows = rows
                )
            } else {
                val rows = buildChangedCharacterRows(current, merged)
                if (rows.isNotEmpty()) {
                    val title = if (current.name != merged.name) {
                        "人物：${current.name.ifBlank { current.id }} -> ${merged.name.ifBlank { merged.id }}"
                    } else {
                        "人物：${merged.name.ifBlank { merged.id }}"
                    }
                    sections += CharacterRewriteDiffSection(
                        title = title,
                        kind = CharacterRewriteDiffKind.Modified,
                        rows = rows
                    )
                }
            }
        }
        currentCharacters
            .filter { it.id !in mergedById }
            .forEach { removed ->
                sections += CharacterRewriteDiffSection(
                    title = "删除人物：${removed.name.ifBlank { removed.id }}",
                    kind = CharacterRewriteDiffKind.Removed,
                    rows = buildRemovedCharacterRows(removed)
                )
            }
        return sections
    }

    private fun buildChangedCharacterRows(
        current: CharacterInfo,
        merged: CharacterInfo
    ): List<CharacterRewriteDiffRow> {
        val rows = mutableListOf<CharacterRewriteDiffRow>()
        rows.addChangedDiff("姓名", current.name, merged.name)
        rows.addChangedDiff("简介", current.profile, merged.profile)
        rows.addChangedDiff("外貌", current.appearance, merged.appearance)
        rows.addChangedDiff("服装", current.clothing, merged.clothing)
        rows.addChangedDiff("能力", current.abilities, merged.abilities)
        rows.addChangedDiff("习惯/爱好", current.habits, merged.habits)
        rows.addChangedDiff("背景", current.background, merged.background)
        rows.addChangedDiff("关系", current.relationships, merged.relationships)
        rows.addChangedDiff("语气", current.speakingStyle, merged.speakingStyle)
        rows.addChangedDiff("NAI 人物提示词", current.imagePrompt, merged.imagePrompt)
        return rows
    }

    private fun buildAddedCharacterRows(character: CharacterInfo): List<CharacterRewriteDiffRow> =
        buildCharacterRows(character, CharacterRewriteDiffKind.Added)

    private fun buildRemovedCharacterRows(character: CharacterInfo): List<CharacterRewriteDiffRow> =
        buildCharacterRows(character, CharacterRewriteDiffKind.Removed)

    private fun buildCharacterRows(
        character: CharacterInfo,
        kind: CharacterRewriteDiffKind
    ): List<CharacterRewriteDiffRow> {
        val rows = mutableListOf<CharacterRewriteDiffRow>()
        rows.addVisibleDiff("姓名", character.name, kind)
        rows.addVisibleDiff("简介", character.profile, kind)
        rows.addVisibleDiff("外貌", character.appearance, kind)
        rows.addVisibleDiff("服装", character.clothing, kind)
        rows.addVisibleDiff("能力", character.abilities, kind)
        rows.addVisibleDiff("习惯/爱好", character.habits, kind)
        rows.addVisibleDiff("背景", character.background, kind)
        rows.addVisibleDiff("关系", character.relationships, kind)
        rows.addVisibleDiff("语气", character.speakingStyle, kind)
        rows.addVisibleDiff("NAI 人物提示词", character.imagePrompt, kind)
        return rows
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
            val localPath = draftAssetService.writeDocumentToDraft(draftSessionId, fileName, content)
            documentsList.add(DocumentInfo.create(fileName, localPath, fileName.substringAfterLast('.', "txt")))
            markRagIndexDirty("文档已添加，保存角色卡后将重建 RAG 索引")
            scheduleDraftSave()
        }
    }

    fun addWorldBookEntry(entry: WorldBookEntry) {
        worldBookEntries.add(entry)
        scheduleDraftSave()
    }

    fun updateWorldBookEntry(index: Int, entry: WorldBookEntry) {
        if (index in worldBookEntries.indices) {
            worldBookEntries[index] = entry
            scheduleDraftSave()
        }
    }

    fun deleteWorldBookEntry(index: Int) {
        if (index in worldBookEntries.indices) {
            worldBookEntries.removeAt(index)
            scheduleDraftSave()
        }
    }

    fun toggleWorldBookEntry(index: Int) {
        if (index in worldBookEntries.indices) {
            val e = worldBookEntries[index]
            worldBookEntries[index] = e.copy(enabled = !e.enabled)
            scheduleDraftSave()
        }
    }

    fun toggleWorldBookBinding(id: String) {
        if (id in selectedWorldBookIds) selectedWorldBookIds.remove(id) else selectedWorldBookIds.add(id)
        scheduleDraftSave()
    }

    fun updateDocument(doc: DocumentInfo, newName: String, newContent: String) {
        viewModelScope.launch {
            val effectiveName = newName.ifBlank { doc.fileName }
            val newPath = draftAssetService.writeDocumentToDraft(draftSessionId, effectiveName, newContent)
            if (!draftAssetService.isDraftAsset(doc.filePath)) {
                pendingDeletedAssets += doc.filePath
                pendingDeletedDocumentIds += doc.id
            }
            val index = documentsList.indexOfFirst { it.id == doc.id }
            if (index >= 0) {
                documentsList[index] = doc.copy(
                    fileName = effectiveName,
                    filePath = newPath,
                    fileType = effectiveName.substringAfterLast('.', doc.fileType),
                    ragStatus = DocumentRagStatus.PENDING.name,
                    ragChunkCount = 0,
                    ragIndexedAt = null,
                    ragError = null
                )
            }
            markRagIndexDirty("文档已编辑，保存角色卡后将重建 RAG 索引")
            scheduleDraftSave()
        }
    }

    private fun applyCard(card: CharacterCard) {
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
        defaultImageNegativePrompt = PromptTemplates.effectiveCharacterNaiNegativePrompt(
            card.defaultImageNegativePrompt
        )
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
        card.pendingSpeakerRenameTasks.firstNotNullOfOrNull { task -> task.lastError }?.let { error ->
            _indexingStatus.value = "历史消息人物改名待重试：$error"
        }
    }

    private suspend fun cleanupAfterCharacterSave(oldCard: CharacterCard?, newCard: CharacterCard) {
        val oldAssets = oldCard?.let(draftAssetService::ownedAssetPaths).orEmpty().toSet()
        val newAssets = draftAssetService.ownedAssetPaths(newCard).toSet()
        val removedAssets = oldAssets - newAssets + pendingDeletedAssets
        draftAssetService.deleteFiles(removedAssets)

        val newDocsById = newCard.customDocuments.associateBy { it.id }
        val staleDocIds = oldCard?.customDocuments.orEmpty()
            .filter { oldDoc -> newDocsById[oldDoc.id]?.filePath != oldDoc.filePath }
            .map { it.id }
        (pendingDeletedDocumentIds + staleDocIds).distinct().forEach { docId ->
            ChatBarApp.instance.ragRepository.deleteChunksByDocumentId(docId)
        }
    }

    private fun buildSpeakerRenames(
        oldCard: CharacterCard?,
        newCard: CharacterCard
    ): List<SpeakerTagRename> {
        val oldById = oldCard?.characters.orEmpty().associateBy(CharacterInfo::id)
        return newCard.characters.mapNotNull { character ->
            val old = oldById[character.id] ?: return@mapNotNull null
            val oldName = old.name.trim()
            val newName = character.name.trim()
            if (oldName.isEmpty() || newName.isEmpty() || oldName == newName) return@mapNotNull null
            SpeakerTagRename(
                characterId = character.id,
                oldName = oldName,
                newName = newName
            )
        }
    }

    private fun buildCurrentCard(markDirty: Boolean): CharacterCard {
        val now = System.currentTimeMillis()
        val base = _characterCard.value
        val normalizedCharacters = charactersList.map { character ->
            character.copy(name = NamePolicy.normalize(character.name))
        }
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
            defaultImageNegativePrompt = PromptTemplates.effectiveCharacterNaiNegativePrompt(defaultImageNegativePrompt),
            systemPrompt = systemPrompt,
            postHistoryInstructions = postHistoryInstructions,
            mesExample = mesExample,
            creatorNotes = creatorNotes,
            momentsEnabled = momentsEnabled,
            worldBookIds = selectedWorldBookIds.distinct(),
            characterBook = null,
            boundWorldBookId = null,
            characters = normalizedCharacters,
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
            defaultImageNegativePrompt = PromptTemplates.effectiveCharacterNaiNegativePrompt(defaultImageNegativePrompt),
            systemPrompt = systemPrompt,
            postHistoryInstructions = postHistoryInstructions,
            mesExample = mesExample,
            creatorNotes = creatorNotes,
            momentsEnabled = momentsEnabled,
            worldBookIds = selectedWorldBookIds.distinct(),
            characterBook = null,
            boundWorldBookId = null,
            characters = normalizedCharacters,
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
        val current = _characterCard.value
        if (current == null) {
            _indexingStatus.value = message
            scheduleDraftSave()
            return
        }
        val updated = current.copy(
            ragIndexStatus = RagIndexStatus.NOT_INDEXED.name,
            ragIndexDone = 0,
            ragIndexTotal = documentsList.size,
            ragIndexMessage = message,
            ragIndexedAt = null
        )
        _characterCard.value = updated
        _indexingStatus.value = message
        scheduleDraftSave()
    }

    fun deleteDocument(doc: DocumentInfo) {
        viewModelScope.launch {
            if (!draftAssetService.isDraftAsset(doc.filePath)) {
                pendingDeletedAssets += doc.filePath
                pendingDeletedDocumentIds += doc.id
            } else {
                draftAssetService.deleteFiles(listOf(doc.filePath))
            }
            documentsList.remove(doc)
            markRagIndexDirty("文档已删除，保存角色卡后将重建 RAG 索引")
            scheduleDraftSave()
        }
    }

    fun clearAllDocuments() {
        viewModelScope.launch {
            indexingJob?.cancel()
            val docs = documentsList.toList()
            docs.forEach { doc ->
                if (!draftAssetService.isDraftAsset(doc.filePath)) {
                    pendingDeletedAssets += doc.filePath
                    pendingDeletedDocumentIds += doc.id
                }
            }
            draftAssetService.deleteFiles(docs.map { it.filePath }.filter { draftAssetService.isDraftAsset(it) })
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
            _indexingStatus.value = "已清空当前角色卡文档，保存角色卡后将清理 RAG 索引"
            scheduleDraftSave()
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
                    filesToProcess.forEachIndexed { index, (fileUri, displayName) ->
                        try {
                            _indexingStatus.value = "正在导入 ${index + 1}/${filesToProcess.size}: $displayName"
                            val content = contentResolver.openInputStream(fileUri)?.bufferedReader()?.use { it.readText() }
                            if (!content.isNullOrBlank()) {
                                val localPath = draftAssetService.writeDocumentToDraft(draftSessionId, displayName, content)
                                val extension = displayName.substringAfterLast('.', "txt")
                                documentsList.add(DocumentInfo.create(displayName, localPath, extension))
                            }
                        } catch (e: Exception) {
                            _indexingStatus.value = "读取文档 [$displayName] 失败: ${e.message}"
                        }
                    }

                    markRagIndexDirty("已导入 ${filesToProcess.size} 个文档，保存角色卡后将重建 RAG 索引")
                    scheduleDraftSave()
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
                val extension = when (contentResolver.getType(uri)) {
                    "image/png" -> "png"
                    "image/gif" -> "gif"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }
                val path = draftAssetService.copyImageToDraft(draftSessionId, uri, extension)
                onSuccess(path)
                scheduleDraftSave()
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
                val localFile = draftAssetService.newDraftFile(draftSessionId, "img_${UUID.randomUUID()}.jpg")

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
                scheduleDraftSave()
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

    override fun onCleared() {
        avatarImageGenerationToken += 1
        avatarImageJob?.cancel()
        _avatarImageState.value.path?.let(novelAiImageStorage::deleteIfOwned)
        transientGeneratedAvatarPaths.forEach(novelAiImageStorage::deleteIfOwned)
        transientGeneratedAvatarPaths.clear()
        freeformAvatarPromptDrafts.clear()
        super.onCleared()
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

private fun NovelAiPromptPlan.asCharacterAvatarPlan(negativePrompt: String): NovelAiPromptPlan =
    copy(
        baseCaption = appendNovelAiPromptTags(
            baseCaption,
            PromptTemplates.CHARACTER_AVATAR_NAI_COMPOSITION_TAGS
        ),
        sizePreset = NovelAiImageSizePreset.SQUARE,
        negativePrompt = PromptTemplates.effectiveCharacterNaiNegativePrompt(negativePrompt)
    )

private fun NovelAiPromptPlan.debugPromptText(): String = buildString {
    appendLine(baseCaption)
    characterCaptions.forEachIndexed { index, caption ->
        appendLine()
        appendLine("Character ${index + 1}: ${caption.prompt}")
    }
}.trim()

private fun appendNovelAiPromptTags(prompt: String, tags: String): String {
    val existing = prompt.split(',')
        .map { it.trim().lowercase() }
        .filter(String::isNotBlank)
        .toSet()
    val missing = tags.split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .filter { it.lowercase() !in existing }
    return (listOf(prompt.trim()) + missing)
        .filter(String::isNotBlank)
        .joinToString(", ")
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

private const val MAX_INLINE_DIFF_TOKENS = 1600

private fun MutableList<CharacterRewriteDiffRow>.addChangedDiff(
    label: String,
    before: String,
    after: String
) {
    if (before == after) return
    val textDiff = buildTextDiffFragments(before, after)
    add(
        CharacterRewriteDiffRow(
            label = label,
            before = before,
            after = after,
            kind = CharacterRewriteDiffKind.Modified,
            beforeFragments = textDiff.before,
            afterFragments = textDiff.after
        )
    )
}

private fun MutableList<CharacterRewriteDiffRow>.addVisibleDiff(
    label: String,
    value: String,
    kind: CharacterRewriteDiffKind
) {
    if (value.isBlank()) return
    add(
        CharacterRewriteDiffRow(
            label = label,
            before = if (kind == CharacterRewriteDiffKind.Removed) value else "",
            after = if (kind == CharacterRewriteDiffKind.Added) value else "",
            kind = kind,
            beforeFragments = if (kind == CharacterRewriteDiffKind.Removed) {
                listOf(CharacterRewriteDiffFragment(value, CharacterRewriteTextDiffKind.Removed))
            } else {
                emptyList()
            },
            afterFragments = if (kind == CharacterRewriteDiffKind.Added) {
                listOf(CharacterRewriteDiffFragment(value, CharacterRewriteTextDiffKind.Added))
            } else {
                emptyList()
            }
        )
    )
}

private data class TextDiffFragments(
    val before: List<CharacterRewriteDiffFragment>,
    val after: List<CharacterRewriteDiffFragment>
)

private fun buildTextDiffFragments(before: String, after: String): TextDiffFragments {
    val prefixLength = commonPrefixLength(before, after)
    val suffixLength = commonSuffixLength(before, after, prefixLength)
    val beforePrefix = before.take(prefixLength)
    val afterPrefix = after.take(prefixLength)
    val beforeMiddle = before.substring(prefixLength, before.length - suffixLength)
    val afterMiddle = after.substring(prefixLength, after.length - suffixLength)
    val beforeSuffix = before.takeLast(suffixLength)
    val afterSuffix = after.takeLast(suffixLength)

    val beforeFragments = mutableListOf<CharacterRewriteDiffFragment>()
    val afterFragments = mutableListOf<CharacterRewriteDiffFragment>()
    beforeFragments.addFragment(beforePrefix, CharacterRewriteTextDiffKind.Unchanged)
    afterFragments.addFragment(afterPrefix, CharacterRewriteTextDiffKind.Unchanged)

    val beforeTokens = beforeMiddle.diffTokens()
    val afterTokens = afterMiddle.diffTokens()
    if (beforeTokens.size + afterTokens.size <= MAX_INLINE_DIFF_TOKENS) {
        appendTokenDiff(beforeTokens, afterTokens, beforeFragments, afterFragments)
    } else {
        beforeFragments.addFragment(beforeMiddle, CharacterRewriteTextDiffKind.Removed)
        afterFragments.addFragment(afterMiddle, CharacterRewriteTextDiffKind.Added)
    }

    beforeFragments.addFragment(beforeSuffix, CharacterRewriteTextDiffKind.Unchanged)
    afterFragments.addFragment(afterSuffix, CharacterRewriteTextDiffKind.Unchanged)
    return TextDiffFragments(beforeFragments, afterFragments)
}

private fun appendTokenDiff(
    beforeTokens: List<String>,
    afterTokens: List<String>,
    beforeFragments: MutableList<CharacterRewriteDiffFragment>,
    afterFragments: MutableList<CharacterRewriteDiffFragment>
) {
    val n = beforeTokens.size
    val m = afterTokens.size
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = if (beforeTokens[i] == afterTokens[j]) {
                dp[i + 1][j + 1] + 1
            } else {
                maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
    }

    var i = 0
    var j = 0
    while (i < n && j < m) {
        if (beforeTokens[i] == afterTokens[j]) {
            beforeFragments.addFragment(beforeTokens[i], CharacterRewriteTextDiffKind.Unchanged)
            afterFragments.addFragment(afterTokens[j], CharacterRewriteTextDiffKind.Unchanged)
            i += 1
            j += 1
        } else if (dp[i + 1][j] >= dp[i][j + 1]) {
            beforeFragments.addFragment(beforeTokens[i], CharacterRewriteTextDiffKind.Removed)
            i += 1
        } else {
            afterFragments.addFragment(afterTokens[j], CharacterRewriteTextDiffKind.Added)
            j += 1
        }
    }
    while (i < n) {
        beforeFragments.addFragment(beforeTokens[i], CharacterRewriteTextDiffKind.Removed)
        i += 1
    }
    while (j < m) {
        afterFragments.addFragment(afterTokens[j], CharacterRewriteTextDiffKind.Added)
        j += 1
    }
}

private fun MutableList<CharacterRewriteDiffFragment>.addFragment(
    text: String,
    kind: CharacterRewriteTextDiffKind
) {
    if (text.isEmpty()) return
    val last = lastOrNull()
    if (last?.kind == kind) {
        this[lastIndex] = last.copy(text = last.text + text)
    } else {
        add(CharacterRewriteDiffFragment(text, kind))
    }
}

private fun String.diffTokens(): List<String> {
    val tokens = mutableListOf<String>()
    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            char == '\r' && getOrNull(index + 1) == '\n' -> {
                tokens += "\r\n"
                index += 2
            }
            char == '\n' || char == '\r' -> {
                tokens += char.toString()
                index += 1
            }
            char.isWhitespace() -> {
                val start = index
                while (index < length && this[index].isWhitespace() && this[index] != '\n' && this[index] != '\r') {
                    index += 1
                }
                tokens += substring(start, index)
            }
            char.isAsciiWordChar() -> {
                val start = index
                while (index < length && this[index].isAsciiWordChar()) {
                    index += 1
                }
                tokens += substring(start, index)
            }
            Character.isHighSurrogate(char) && index + 1 < length -> {
                tokens += substring(index, index + 2)
                index += 2
            }
            else -> {
                tokens += char.toString()
                index += 1
            }
        }
    }
    return tokens
}

private fun Char.isAsciiWordChar(): Boolean =
    this == '_' || this in '0'..'9' || this in 'A'..'Z' || this in 'a'..'z'

private fun commonPrefixLength(left: String, right: String): Int {
    val max = minOf(left.length, right.length)
    var index = 0
    while (index < max && left[index] == right[index]) {
        index += 1
    }
    return index
}

private fun commonSuffixLength(left: String, right: String, prefixLength: Int): Int {
    val max = minOf(left.length, right.length) - prefixLength
    var count = 0
    while (count < max && left[left.lastIndex - count] == right[right.lastIndex - count]) {
        count += 1
    }
    return count
}
