package com.example.chatbar.ui.worldbook

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.EditorDraft
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.local.entity.CharacterResearchSourceMode
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.local.entity.WorldBookPosition
import com.example.chatbar.domain.card.NamePolicy
import com.example.chatbar.domain.card.CharacterSectionImportPolicy
import com.example.chatbar.domain.card.WorldBookCharacterImportResult
import com.example.chatbar.domain.draft.WorldBookEntryModalState
import com.example.chatbar.domain.draft.hasMeaningfulEntryData
import com.example.chatbar.domain.draft.materialize
import com.example.chatbar.domain.search.CharacterReferenceDocument
import com.example.chatbar.domain.search.CharacterResearchOptions
import com.example.chatbar.domain.search.ResearchDebugSnapshot
import com.example.chatbar.domain.search.sourceSignaturePart
import com.example.chatbar.domain.search.usesManualUrls
import com.example.chatbar.domain.search.validateManualResearchUrls
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import com.example.chatbar.domain.worldbook.WorldBookAiService
import com.example.chatbar.domain.worldbook.WorldBookContentCandidate
import com.example.chatbar.domain.worldbook.WorldBookCreateCheckpoint
import com.example.chatbar.domain.worldbook.WorldBookEntryPlanCandidate
import com.example.chatbar.domain.worldbook.WorldBookFillCheckpoint
import com.example.chatbar.domain.worldbook.isWorldBookAiCheckpointCompatible
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class WorldBookAiOutputUiState(
    val key: String,
    val title: String,
    val text: String
)

data class WorldBookAiFormUiState(
    val request: String = "",
    val manualUrlsText: String = "",
    val referenceDocument: CharacterReferenceDocument? = null,
    val selectedModelId: String? = null
)

data class WorldBookCreateUiState(
    val isGenerating: Boolean = false,
    val isComplete: Boolean = false,
    val candidates: List<WorldBookEntryPlanCandidate> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val error: String? = null,
    val warning: String = "",
    val statusText: String = "",
    val progressLines: List<String> = emptyList(),
    val streamingText: String = "",
    val researchDebug: ResearchDebugSnapshot? = null,
    val outputs: List<WorldBookAiOutputUiState> = emptyList(),
    val sourceSignature: String = "",
    val checkpoint: WorldBookCreateCheckpoint? = null
)

data class WorldBookFillUiState(
    val isGenerating: Boolean = false,
    val isComplete: Boolean = false,
    val candidates: List<WorldBookContentCandidate> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val error: String? = null,
    val warning: String = "",
    val statusText: String = "",
    val progressLines: List<String> = emptyList(),
    val streamingText: String = "",
    val researchDebug: ResearchDebugSnapshot? = null,
    val outputs: List<WorldBookAiOutputUiState> = emptyList(),
    val sourceSignature: String = "",
    val checkpoint: WorldBookFillCheckpoint? = null
)

internal const val WORLD_BOOK_REFERENCE_DOCUMENT_WARNING_CHARS = 1_000_000
internal const val MAX_WORLD_BOOK_REFERENCE_DOCUMENT_CHARS = 5_000_000

class WorldBookEditViewModel(
    private val worldBookId: String?,
    routeDraftId: String
) : ViewModel() {
    private val repository = ChatBarApp.instance.worldBookRepository
    private val characterRepository = ChatBarApp.instance.characterRepository
    private val draftRepository = ChatBarApp.instance.editorDraftRepository
    private val settingsRepository = ChatBarApp.instance.settingsRepository
    private val modelResolver = ChatBarApp.instance.effectiveModelResolver
    private val worldBookAiService = ChatBarApp.instance.worldBookAiService
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val draftSessionId = routeDraftId.ifBlank { UUID.randomUUID().toString() }
    private var baseBook: WorldBook? = null
    private var loadedDraft: EditorDraft? = null
    private var draftJob: Job? = null
    private var aiJob: Job? = null
    private var aiGenerationToken = 0

    private val _worldBook = MutableStateFlow<WorldBook?>(null)
    val worldBook: StateFlow<WorldBook?> = _worldBook.asStateFlow()

    private val _availableCharacterCards = MutableStateFlow<List<CharacterCard>>(emptyList())
    val availableCharacterCards: StateFlow<List<CharacterCard>> = _availableCharacterCards.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _aiModels = MutableStateFlow<List<ModelConfig>>(emptyList())
    val aiModels: StateFlow<List<ModelConfig>> = _aiModels.asStateFlow()
    private val _aiDefaultModelId = MutableStateFlow<String?>(null)
    val aiDefaultModelId: StateFlow<String?> = _aiDefaultModelId.asStateFlow()
    private val _aiResearchSourceMode = MutableStateFlow(
        settingsRepository.currentAppSettings.worldBookAiResearchSourceMode
    )
    val aiResearchSourceMode: StateFlow<CharacterResearchSourceMode> = _aiResearchSourceMode.asStateFlow()
    private val _createAiState = MutableStateFlow(WorldBookCreateUiState())
    val createAiState: StateFlow<WorldBookCreateUiState> = _createAiState.asStateFlow()
    private val _fillAiState = MutableStateFlow(WorldBookFillUiState())
    val fillAiState: StateFlow<WorldBookFillUiState> = _fillAiState.asStateFlow()
    private val _createAiFormState = MutableStateFlow(WorldBookAiFormUiState())
    val createAiFormState: StateFlow<WorldBookAiFormUiState> = _createAiFormState.asStateFlow()
    private val _fillAiFormState = MutableStateFlow(WorldBookAiFormUiState())
    val fillAiFormState: StateFlow<WorldBookAiFormUiState> = _fillAiFormState.asStateFlow()

    var name by mutableStateOf("")
    var description by mutableStateOf("")
    var scanDepth by mutableStateOf(10)
    var tokenBudget by mutableStateOf("")
    var recursiveScanning by mutableStateOf(false)
    var caseSensitive by mutableStateOf(false)
    var matchWholeWords by mutableStateOf(false)
    val entries = mutableStateListOf<WorldBookEntry>()
    var draftSavedAt by mutableStateOf<Long?>(null)
        private set
    var draftReady by mutableStateOf(false)
        private set
    var hasLocalChanges by mutableStateOf(false)
        private set
    var hasUnsavedDraftChanges by mutableStateOf(false)
        private set
    var restoreDraft by mutableStateOf<EditorDraft?>(null)
        private set
    var restoreConflict by mutableStateOf(false)
        private set
    var saveConflict by mutableStateOf(false)
    var sourceDeleted by mutableStateOf(false)
        private set
    var entryModalState by mutableStateOf<WorldBookEntryModalState?>(null)
        private set

    init {
        load()
        loadCharacterCards()
        refreshAiModels()
    }

    private fun loadCharacterCards() {
        viewModelScope.launch {
            _availableCharacterCards.value = characterRepository.getAll().filter { card ->
                card.editMode == CharacterEditMode.STRUCTURED && card.characters.any { it.name.isNotBlank() }
            }
        }
    }

    val emptyContentCount: Int
        get() = entries.count { it.content.isBlank() }

    private fun refreshAiModels() {
        viewModelScope.launch {
            runCatching {
                val settings = settingsRepository.getAppSettings()
                _aiModels.value = modelResolver.availableChatModels(settings)
                _aiDefaultModelId.value = modelResolver.resolveChatModel(null, settings)?.id
                _aiResearchSourceMode.value = settings.worldBookAiResearchSourceMode
            }.onFailure {
                _aiModels.value = emptyList()
                _aiDefaultModelId.value = null
            }
        }
    }

    fun setAiResearchSourceMode(mode: CharacterResearchSourceMode) {
        if (_aiResearchSourceMode.value == mode) return
        _aiResearchSourceMode.value = mode
        viewModelScope.launch(Dispatchers.IO) {
            val settings = settingsRepository.getAppSettings()
            settingsRepository.saveAppSettings(settings.copy(worldBookAiResearchSourceMode = mode))
        }
    }

    fun updateCreateAiForm(state: WorldBookAiFormUiState) {
        _createAiFormState.value = state
    }

    fun updateFillAiForm(state: WorldBookAiFormUiState) {
        _fillAiFormState.value = state
    }

    fun generateCreateCandidates(
        request: String,
        modelId: String?,
        referenceDocument: CharacterReferenceDocument?,
        researchOptions: CharacterResearchOptions,
        resume: Boolean = false
    ) {
        val validation = validateWorldBookAiInput(request, modelId, researchOptions, referenceDocument)
        if (validation != null) {
            _createAiState.value = _createAiState.value.copy(error = validation)
            return
        }
        val selectedModel = modelId?.let { id -> _aiModels.value.firstOrNull { it.id == id } }
        if (modelId != null && selectedModel == null) {
            _createAiState.value = _createAiState.value.copy(error = "所选模型不可用，请重新选择")
            refreshAiModels()
            return
        }
        val book = currentStableBook()
        val signature = buildAiSignature("create", request, modelId ?: _aiDefaultModelId.value, referenceDocument, researchOptions, book)
        val previous = _createAiState.value
        val resumeCheckpoint = previous.checkpoint.takeIf {
            resume && isWorldBookAiCheckpointCompatible(previous.sourceSignature, signature)
        }
        val resumeInvalidated = resume && previous.checkpoint != null && resumeCheckpoint == null
        startCreateJob(
            request,
            selectedModel,
            referenceDocument,
            researchOptions,
            book,
            signature,
            resumeCheckpoint,
            previous.takeIf { resumeCheckpoint != null },
            resumeInvalidated
        )
    }

    private fun startCreateJob(
        request: String,
        selectedModel: ModelConfig?,
        referenceDocument: CharacterReferenceDocument?,
        researchOptions: CharacterResearchOptions,
        book: WorldBook,
        signature: String,
        resumeCheckpoint: WorldBookCreateCheckpoint?,
        resumeState: WorldBookCreateUiState?,
        resumeInvalidated: Boolean
    ) {
        aiGenerationToken += 1
        aiJob?.cancel()
        val token = aiGenerationToken
        val initialCandidates = resumeCheckpoint?.candidates.orEmpty()
        _createAiState.value = WorldBookCreateUiState(
            isGenerating = true,
            candidates = initialCandidates,
            selectedIds = resumeState?.selectedIds?.intersect(initialCandidates.map { it.candidateId }.toSet())
                ?: initialCandidates.mapTo(mutableSetOf()) { it.candidateId },
            statusText = if (resumeCheckpoint == null) "准备创建世界书条目" else "从第 ${resumeCheckpoint.batchNumber} 批继续",
            progressLines = resumeState?.progressLines.orEmpty() +
                if (resumeCheckpoint == null) "开始新的创建任务" else "沿用已完成批次，继续创建",
            streamingText = resumeState?.streamingText.orEmpty(),
            outputs = resumeState?.outputs
                ?: resumeCheckpoint?.rawOutputs.orEmpty().map { WorldBookAiOutputUiState(it.key, it.title, it.text) },
            warning = if (resumeInvalidated) "输入、模型或目标已变化，原断点失效；已开始新任务" else resumeState?.warning.orEmpty(),
            sourceSignature = signature,
            checkpoint = resumeCheckpoint,
            researchDebug = resumeCheckpoint?.researchDebug ?: resumeState?.researchDebug
        )
        aiJob = viewModelScope.launch {
            try {
                val result = AiBackgroundWorkManager.run(draftSessionId) {
                    worldBookAiService.createEntriesStreaming(
                        request = request,
                        book = book,
                        modelOverride = selectedModel,
                        researchOptions = researchOptions,
                        referenceDocument = referenceDocument,
                        resumeFrom = resumeCheckpoint,
                        onCheckpoint = { checkpoint ->
                            updateCreateState(token) { state ->
                                state.copy(
                                    checkpoint = checkpoint,
                                    candidates = checkpoint.candidates,
                                    selectedIds = state.selectedIds +
                                        (checkpoint.candidates.map { it.candidateId } -
                                            state.candidates.map { it.candidateId }.toSet()),
                                    warning = checkpoint.warning
                                )
                            }
                        },
                        onStatus = { status ->
                            updateCreateState(token) { state ->
                                state.copy(statusText = status, progressLines = state.progressLines.appendStatus(status))
                            }
                        },
                        onRawText = { raw -> updateCreateState(token) { it.copy(streamingText = raw) } },
                        onResearchDebug = { debug -> updateCreateState(token) { it.copy(researchDebug = debug) } },
                        onVisibleOutput = { key, title, text ->
                            updateCreateState(token) { state -> state.copy(outputs = state.outputs.upsert(key, title, text)) }
                        }
                    )
                }
                updateCreateState(token) { state ->
                    state.copy(
                        isGenerating = false,
                        isComplete = true,
                        candidates = result.candidates,
                        selectedIds = state.selectedIds + result.candidates.map { it.candidateId },
                        checkpoint = result.checkpoint,
                        warning = result.warning,
                        statusText = "已生成 ${result.candidates.size} 条候选"
                    )
                }
            } catch (_: CancellationException) {
                updateCreateState(token) { state ->
                    state.copy(isGenerating = false, error = "已取消生成，可继续未完成批次", statusText = "生成已取消")
                }
            } catch (error: Throwable) {
                updateCreateState(token) { state ->
                    state.copy(
                        isGenerating = false,
                        error = error.message ?: "AI 创建条目失败",
                        statusText = "创建失败，可从断点继续",
                        progressLines = state.progressLines.appendStatus("创建失败")
                    )
                }
            } finally {
                if (token == aiGenerationToken) aiJob = null
            }
        }
    }

    fun generateFillCandidates(
        request: String,
        modelId: String?,
        referenceDocument: CharacterReferenceDocument?,
        researchOptions: CharacterResearchOptions,
        resume: Boolean = false
    ) {
        val targets = entries.filter { it.content.isBlank() }
        if (targets.isEmpty()) {
            _fillAiState.value = _fillAiState.value.copy(error = "当前没有正文为空的世界书条目")
            return
        }
        val validation = validateWorldBookAiInput(request, modelId, researchOptions, referenceDocument, allowEmptyRequest = true)
        if (validation != null) {
            _fillAiState.value = _fillAiState.value.copy(error = validation)
            return
        }
        val selectedModel = modelId?.let { id -> _aiModels.value.firstOrNull { it.id == id } }
        if (modelId != null && selectedModel == null) {
            _fillAiState.value = _fillAiState.value.copy(error = "所选模型不可用，请重新选择")
            refreshAiModels()
            return
        }
        val book = currentStableBook()
        val signature = buildAiSignature("fill", request, modelId ?: _aiDefaultModelId.value, referenceDocument, researchOptions, book) +
            targets.joinToString(separator = "|") { it.id }
        val previous = _fillAiState.value
        val resumeCheckpoint = previous.checkpoint.takeIf {
            resume && isWorldBookAiCheckpointCompatible(previous.sourceSignature, signature)
        }
        val resumeInvalidated = resume && previous.checkpoint != null && resumeCheckpoint == null
        startFillJob(
            request,
            selectedModel,
            referenceDocument,
            researchOptions,
            book,
            targets,
            signature,
            resumeCheckpoint,
            previous.takeIf { resumeCheckpoint != null },
            resumeInvalidated
        )
    }

    private fun startFillJob(
        request: String,
        selectedModel: ModelConfig?,
        referenceDocument: CharacterReferenceDocument?,
        researchOptions: CharacterResearchOptions,
        book: WorldBook,
        targets: List<WorldBookEntry>,
        signature: String,
        resumeCheckpoint: WorldBookFillCheckpoint?,
        resumeState: WorldBookFillUiState?,
        resumeInvalidated: Boolean
    ) {
        aiGenerationToken += 1
        aiJob?.cancel()
        val token = aiGenerationToken
        val initialCandidates = resumeCheckpoint?.candidates.orEmpty()
        _fillAiState.value = WorldBookFillUiState(
            isGenerating = true,
            candidates = initialCandidates,
            selectedIds = resumeState?.selectedIds?.intersect(initialCandidates.map { it.targetId }.toSet())
                ?: initialCandidates.mapTo(mutableSetOf()) { it.targetId },
            statusText = if (resumeCheckpoint == null) "准备填充 ${targets.size} 个空条目" else "从第 ${resumeCheckpoint.batchNumber} 批继续",
            progressLines = resumeState?.progressLines.orEmpty() +
                if (resumeCheckpoint == null) "开始新的填充任务" else "沿用已完成批次，继续填充",
            streamingText = resumeState?.streamingText.orEmpty(),
            outputs = resumeState?.outputs
                ?: resumeCheckpoint?.rawOutputs.orEmpty().map { WorldBookAiOutputUiState(it.key, it.title, it.text) },
            warning = if (resumeInvalidated) "输入、模型或目标已变化，原断点失效；已开始新任务" else resumeState?.warning.orEmpty(),
            sourceSignature = signature,
            checkpoint = resumeCheckpoint,
            researchDebug = resumeCheckpoint?.activeResearchDebug ?: resumeState?.researchDebug
        )
        aiJob = viewModelScope.launch {
            try {
                val result = AiBackgroundWorkManager.run(draftSessionId) {
                    worldBookAiService.fillEmptyContentsStreaming(
                        request = request,
                        book = book,
                        targets = targets,
                        modelOverride = selectedModel,
                        researchOptions = researchOptions,
                        referenceDocument = referenceDocument,
                        resumeFrom = resumeCheckpoint,
                        onCheckpoint = { checkpoint ->
                            updateFillState(token) { state ->
                                state.copy(
                                    checkpoint = checkpoint,
                                    candidates = checkpoint.candidates,
                                    selectedIds = state.selectedIds +
                                        (checkpoint.candidates.map { it.targetId } -
                                            state.candidates.map { it.targetId }.toSet())
                                )
                            }
                        },
                        onStatus = { status ->
                            updateFillState(token) { state ->
                                state.copy(statusText = status, progressLines = state.progressLines.appendStatus(status))
                            }
                        },
                        onRawText = { raw -> updateFillState(token) { it.copy(streamingText = raw) } },
                        onResearchDebug = { debug -> updateFillState(token) { it.copy(researchDebug = debug) } },
                        onVisibleOutput = { key, title, text ->
                            updateFillState(token) { state -> state.copy(outputs = state.outputs.upsert(key, title, text)) }
                        }
                    )
                }
                updateFillState(token) { state ->
                    state.copy(
                        isGenerating = false,
                        isComplete = true,
                        candidates = result.candidates,
                        selectedIds = state.selectedIds + result.candidates.map { it.targetId },
                        checkpoint = result.checkpoint,
                        statusText = "已生成 ${result.candidates.size} 条正文候选"
                    )
                }
            } catch (_: CancellationException) {
                updateFillState(token) { state ->
                    state.copy(isGenerating = false, error = "已取消生成，可继续未完成批次", statusText = "生成已取消")
                }
            } catch (error: Throwable) {
                updateFillState(token) { state ->
                    state.copy(
                        isGenerating = false,
                        error = error.message ?: "AI 填充内容失败",
                        statusText = "填充失败，可从断点继续",
                        progressLines = state.progressLines.appendStatus("填充失败")
                    )
                }
            } finally {
                if (token == aiGenerationToken) aiJob = null
            }
        }
    }

    fun cancelAiGeneration() {
        if (aiJob == null) return
        aiGenerationToken += 1
        aiJob?.cancel()
        aiJob = null
        if (_createAiState.value.isGenerating) {
            _createAiState.value = _createAiState.value.copy(
                isGenerating = false,
                error = "已取消生成，可继续未完成批次",
                statusText = "生成已取消"
            )
        }
        if (_fillAiState.value.isGenerating) {
            _fillAiState.value = _fillAiState.value.copy(
                isGenerating = false,
                error = "已取消生成，可继续未完成批次",
                statusText = "生成已取消"
            )
        }
    }

    fun toggleCreateCandidate(id: String) {
        _createAiState.value = _createAiState.value.let { state ->
            state.copy(selectedIds = state.selectedIds.toggle(id))
        }
    }

    fun selectAllCreateCandidates(selected: Boolean) {
        _createAiState.value = _createAiState.value.let { state ->
            state.copy(selectedIds = if (selected) state.candidates.mapTo(mutableSetOf()) { it.candidateId } else emptySet())
        }
    }

    fun toggleFillCandidate(id: String) {
        _fillAiState.value = _fillAiState.value.let { state ->
            state.copy(selectedIds = state.selectedIds.toggle(id))
        }
    }

    fun selectAllFillCandidates(selected: Boolean) {
        _fillAiState.value = _fillAiState.value.let { state ->
            state.copy(selectedIds = if (selected) state.candidates.mapTo(mutableSetOf()) { it.targetId } else emptySet())
        }
    }

    fun applyCreateCandidates() {
        val state = _createAiState.value
        if (state.selectedIds.isEmpty()) return
        val merged = WorldBookAiService.applyCreatedEntries(entries.toList(), state.candidates, state.selectedIds)
        entries.clear()
        entries.addAll(merged)
        _createAiState.value = WorldBookCreateUiState()
        scheduleDraftSave()
    }

    fun applyFillCandidates() {
        val state = _fillAiState.value
        if (state.selectedIds.isEmpty()) return
        val merged = WorldBookAiService.applyFilledContents(entries.toList(), state.candidates, state.selectedIds)
        entries.clear()
        entries.addAll(merged)
        _fillAiState.value = WorldBookFillUiState()
        scheduleDraftSave()
    }

    fun readReferenceDocument(uri: Uri, onResult: (Result<CharacterReferenceDocument>) -> Unit) {
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val resolver = ChatBarApp.instance.contentResolver
                    val fileName = resolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                    }?.takeIf(String::isNotBlank) ?: "参考文档.txt"
                    require(fileName.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "json")) {
                        "仅支持 TXT、MD、JSON 参考文档"
                    }
                    val content = resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        val output = StringBuilder()
                        val buffer = CharArray(8_192)
                        while (true) {
                            val read = reader.read(buffer)
                            if (read < 0) break
                            require(output.length + read <= MAX_WORLD_BOOK_REFERENCE_DOCUMENT_CHARS) {
                                "参考文档超过 500 万字符限制"
                            }
                            output.append(buffer, 0, read)
                        }
                        output.toString().trimStart('\uFEFF')
                    } ?: error("无法读取参考文档")
                    require(content.isNotBlank()) { "参考文档内容为空" }
                    CharacterReferenceDocument(fileName, content)
                }.let(Result.Companion::success)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            onResult(result)
        }
    }

    private fun validateWorldBookAiInput(
        request: String,
        modelId: String?,
        researchOptions: CharacterResearchOptions,
        referenceDocument: CharacterReferenceDocument?,
        allowEmptyRequest: Boolean = false
    ): String? {
        if (!allowEmptyRequest && request.isBlank() && referenceDocument == null && researchOptions.urls.isEmpty()) {
            return "请输入世界书需求，或提供参考文档、网址"
        }
        if (modelId != null && _aiModels.value.none { it.id == modelId }) return "所选模型不可用，请重新选择"
        if (researchOptions.mode.usesManualUrls()) {
            val validation = validateManualResearchUrls(researchOptions.urls)
            if (!validation.isValid || validation.urls.isEmpty()) {
                return validation.errors.firstOrNull() ?: "请至少输入一个有效网址"
            }
        }
        return null
    }

    private fun buildAiSignature(
        operation: String,
        request: String,
        modelId: String?,
        referenceDocument: CharacterReferenceDocument?,
        options: CharacterResearchOptions,
        book: WorldBook
    ): String = buildString {
        append(operation).append('\n')
        append(request.trim()).append('\n')
        append(modelId.orEmpty()).append('\n')
        append(referenceDocument?.fileName.orEmpty()).append('\n')
        append(referenceDocument?.content?.hashCode()).append('\n')
        append(options.sourceSignaturePart()).append('\n')
        append(book.hashCode())
    }

    private fun currentStableBook(): WorldBook = currentPayload().copy(
        id = worldBookId.orEmpty(),
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun updateCreateState(token: Int, transform: (WorldBookCreateUiState) -> WorldBookCreateUiState) {
        if (token == aiGenerationToken) _createAiState.value = transform(_createAiState.value)
    }

    private fun updateFillState(token: Int, transform: (WorldBookFillUiState) -> WorldBookFillUiState) {
        if (token == aiGenerationToken) _fillAiState.value = transform(_fillAiState.value)
    }

    private fun load() {
        viewModelScope.launch {
            val draft = draftRepository.getForTarget(EditorDraftType.WORLD_BOOK, worldBookId)
            if (worldBookId != null) {
                val book = repository.getById(worldBookId)
                baseBook = book
                if (book != null) {
                    applyBook(book)
                } else {
                    sourceDeleted = draft != null
                }
                if (draft != null) {
                    if (book == null) {
                        loadedDraft = draft.copy(targetId = null)
                        draft.worldBookPayload?.let(::applyBook)
                        restoreOpenModal(draft.openModalState)
                        refreshChangeState()
                    } else {
                        restoreDraft = draft
                        restoreConflict = draftRepository.isChanged(book, draft)
                    }
                }
            } else {
                val newDraft = draft ?: draftRepository.getLatestNew(EditorDraftType.WORLD_BOOK)
                if (newDraft?.worldBookPayload != null) {
                    loadedDraft = newDraft
                    applyBook(newDraft.worldBookPayload)
                    restoreOpenModal(newDraft.openModalState)
                    refreshChangeState()
                }
            }
            draftReady = true
        }
    }

    fun restoreDraft() {
        restoreDraft?.let { draft ->
            loadedDraft = draft
            draft.worldBookPayload?.let(::applyBook)
            restoreOpenModal(draft.openModalState)
            refreshChangeState()
            hasUnsavedDraftChanges = false
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
            draftJob?.cancelAndJoin()
            draftJob = null
            loadedDraft?.id?.let { draftRepository.delete(it) }
            draftRepository.deleteForTarget(EditorDraftType.WORLD_BOOK, worldBookId)
            loadedDraft = null
            hasLocalChanges = false
            hasUnsavedDraftChanges = false
            entryModalState = null
            restoreDraft = null
            restoreConflict = false
            onDone?.invoke()
        }
    }

    fun save(onSuccess: () -> Unit, forceOverwrite: Boolean = false, saveAsNew: Boolean = false) {
        if (name.isBlank()) return
        _isSaving.value = true
        viewModelScope.launch {
            draftJob?.cancelAndJoin()
            draftJob = null
            val targetId = if (saveAsNew || sourceDeleted) null else worldBookId
            if (!forceOverwrite && targetId != null && loadedDraft != null && draftRepository.isChanged(repository.getById(targetId), loadedDraft!!)) {
                saveConflict = true
                _isSaving.value = false
                return@launch
            }
            name = NamePolicy.normalize(name)
            val all = repository.getAll()
            if (targetId == null && all.any { NamePolicy.isSame(it.name, name) }) {
                name = NamePolicy.nextCopyName(name, all.map { it.name })
            }
            val conflict = all.firstOrNull { it.id != targetId && NamePolicy.isSame(it.name, name) }
            if (conflict != null) {
                _isSaving.value = false
                return@launch
            }
            val now = System.currentTimeMillis()
            val budget = tokenBudget.toIntOrNull()
            val book = targetId?.let { repository.getById(it) }?.copy(
                name = name,
                description = description,
                entries = entries.toList(),
                scanDepth = scanDepth,
                tokenBudget = budget,
                recursiveScanning = recursiveScanning,
                caseSensitive = caseSensitive,
                matchWholeWords = matchWholeWords,
                updatedAt = now
            ) ?: WorldBook(
                id = targetId ?: UUID.randomUUID().toString(),
                name = name,
                description = description,
                entries = entries.toList(),
                scanDepth = scanDepth,
                tokenBudget = budget,
                recursiveScanning = recursiveScanning,
                caseSensitive = caseSensitive,
                matchWholeWords = matchWholeWords,
                createdAt = now,
                updatedAt = now
            )
            repository.save(book)
            loadedDraft?.id?.let { draftRepository.delete(it) }
            draftRepository.deleteForTarget(EditorDraftType.WORLD_BOOK, worldBookId)
            _worldBook.value = book
            baseBook = book
            hasLocalChanges = false
            hasUnsavedDraftChanges = false
            _isSaving.value = false
            onSuccess()
        }
    }

    fun addEntry(entry: WorldBookEntry) {
        entries.add(entry)
        scheduleDraftSave()
    }

    fun importCharacterCardCharacters(
        sourceCardId: String,
        characterIds: Set<String>
    ): WorldBookCharacterImportResult? {
        val source = _availableCharacterCards.value.firstOrNull { it.id == sourceCardId } ?: return null
        val selected = source.characters.filter { it.id in characterIds }
        if (selected.isEmpty()) return null
        val result = CharacterSectionImportPolicy.importIntoWorldBook(entries.toList(), selected)
        entries.clear()
        entries.addAll(result.entries)
        scheduleDraftSave()
        return result
    }

    fun clearImportedCharacterSections(
        sourceCardId: String,
        characterIds: Set<String>,
        onResult: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            val result = runCatching {
                val source = characterRepository.getById(sourceCardId) ?: error("原角色卡已不存在")
                val cleared = CharacterSectionImportPolicy.clearWorldBookImportedSections(source, characterIds)
                characterRepository.update(cleared)
                _availableCharacterCards.value = characterRepository.getAll().filter { card ->
                    card.editMode == CharacterEditMode.STRUCTURED && card.characters.any { it.name.isNotBlank() }
                }
            }
            onResult(result)
        }
    }

    fun updateEntry(index: Int, entry: WorldBookEntry) {
        if (index in entries.indices) {
            entries[index] = entry
            scheduleDraftSave()
        }
    }

    fun deleteEntry(index: Int) {
        if (index in entries.indices) {
            entries.removeAt(index)
            scheduleDraftSave()
        }
    }

    fun toggleEntry(index: Int) {
        if (index in entries.indices) {
            val e = entries[index]
            entries[index] = e.copy(enabled = !e.enabled)
            scheduleDraftSave()
        }
    }

    fun openEntryDialog(index: Int?) {
        entryModalState = WorldBookEntryModalState.from(index, index?.let { entries.getOrNull(it) })
        scheduleDraftSave()
    }

    fun updateEntryDialog(state: WorldBookEntryModalState) {
        entryModalState = state
        scheduleDraftSave()
    }

    fun dismissEntryDialog() {
        entryModalState = null
        scheduleDraftSave()
    }

    fun saveEntryDialog() {
        val state = entryModalState ?: return
        if (!state.hasMeaningfulEntryData()) return
        val existing = state.editingIndex?.let(entries::getOrNull)
        val entry = state.materialize(existing)
        state.editingIndex?.let { updateEntry(it, entry) } ?: addEntry(entry)
        entryModalState = null
        scheduleDraftSave()
    }

    fun scheduleDraftSave() {
        if (!draftReady || restoreDraft != null) return
        refreshChangeState()
        if (!hasLocalChanges) {
            hasUnsavedDraftChanges = false
            draftJob?.cancel()
            return
        }
        hasUnsavedDraftChanges = true
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(600)
            saveDraftNow()
        }
    }

    fun saveDraftAndExit(onDone: () -> Unit) {
        viewModelScope.launch {
            draftJob?.cancelAndJoin()
            draftJob = null
            if (hasUnsavedDraftChanges) saveDraftNow()
            onDone()
        }
    }

    private suspend fun saveDraftNow() {
        if (!draftReady || restoreDraft != null) return
        val draft = draftRepository.worldBookDraft(
            targetId = if (sourceDeleted) null else worldBookId,
            draftSessionId = draftSessionId,
            payload = currentPayload(),
            base = baseBook,
            openModalState = entryModalState?.let { json.encodeToString(it) }
        )
        loadedDraft = draftRepository.save(draft)
        draftSavedAt = loadedDraft?.updatedAt
        hasUnsavedDraftChanges = false
    }

    private fun currentPayload(): WorldBook {
        val now = System.currentTimeMillis()
        return _worldBook.value?.copy(
            name = name,
            description = description,
            entries = entries.toList(),
            scanDepth = scanDepth,
            tokenBudget = tokenBudget.toIntOrNull(),
            recursiveScanning = recursiveScanning,
            caseSensitive = caseSensitive,
            matchWholeWords = matchWholeWords,
            updatedAt = now
        ) ?: WorldBook(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            entries = entries.toList(),
            scanDepth = scanDepth,
            tokenBudget = tokenBudget.toIntOrNull(),
            recursiveScanning = recursiveScanning,
            caseSensitive = caseSensitive,
            matchWholeWords = matchWholeWords,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun applyBook(book: WorldBook) {
        _worldBook.value = book
        name = book.name
        description = book.description
        scanDepth = book.scanDepth
        tokenBudget = book.tokenBudget?.toString() ?: ""
        recursiveScanning = book.recursiveScanning
        caseSensitive = book.caseSensitive
        matchWholeWords = book.matchWholeWords
        entries.clear()
        entries.addAll(book.entries)
    }

    private fun refreshChangeState() {
        val base = baseBook
        hasLocalChanges = if (base == null) {
            sourceDeleted || name.isNotBlank() || description.isNotBlank() || entries.isNotEmpty() ||
                scanDepth != 10 || tokenBudget.isNotBlank() || recursiveScanning || caseSensitive || matchWholeWords
        } else {
            currentPayload().copy(
                id = base.id,
                createdAt = base.createdAt,
                updatedAt = base.updatedAt
            ) != base
        }
    }

    private fun restoreOpenModal(raw: String?) {
        entryModalState = raw?.let {
            runCatching { json.decodeFromString(WorldBookEntryModalState.serializer(), it) }.getOrNull()
        }
    }
}

private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id

private fun List<String>.appendStatus(status: String): List<String> =
    if (lastOrNull() == status) this else (this + status).takeLast(80)

private fun List<WorldBookAiOutputUiState>.upsert(
    key: String,
    title: String,
    text: String
): List<WorldBookAiOutputUiState> {
    val replacement = WorldBookAiOutputUiState(key, title, text)
    val index = indexOfFirst { it.key == key }
    return if (index < 0) this + replacement else toMutableList().also { it[index] = replacement }
}
