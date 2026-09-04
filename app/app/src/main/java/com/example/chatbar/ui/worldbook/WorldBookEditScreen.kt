package com.example.chatbar.ui.worldbook

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.byValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chatbar.data.local.entity.CharacterResearchSourceMode
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.WorldBookPosition
import com.example.chatbar.data.local.entity.WorldBookSelectiveLogic
import com.example.chatbar.domain.draft.WorldBookEntryModalState
import com.example.chatbar.domain.draft.hasMeaningfulEntryData
import com.example.chatbar.domain.search.CharacterReferenceDocument
import com.example.chatbar.domain.search.CharacterResearchOptions
import com.example.chatbar.domain.search.ManualResearchUrlValidation
import com.example.chatbar.domain.search.usesManualUrls
import com.example.chatbar.domain.search.validateManualResearchUrls
import com.example.chatbar.ui.kit.AppIcons
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbCheckbox
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbDivider
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbProgress
import com.example.chatbar.ui.kit.CbScaffold
import com.example.chatbar.ui.kit.CbSelect
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbSwitch
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.kit.FullscreenTextEditor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WorldBookEditScreen(
    worldBookId: String?,
    draftId: String = "",
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorldBookEditViewModel = viewModel(
        key = worldBookId?.let { "edit:$it" } ?: "new:${draftId.ifBlank { "default" }}",
        factory = WorldBookEditViewModelFactory(worldBookId, draftId)
    )
) {
    var deleteIndex by remember { mutableStateOf<Int?>(null) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showTutorial by remember { mutableStateOf(false) }
    var descriptionFullscreen by remember { mutableStateOf(false) }
    var aiOperation by remember { mutableStateOf<WorldBookAiOperation?>(null) }
    var pendingReferenceDocumentPick by remember {
        mutableStateOf<((Result<CharacterReferenceDocument>) -> Unit)?>(null)
    }
    val aiModels by viewModel.aiModels.collectAsState()
    val aiDefaultModelId by viewModel.aiDefaultModelId.collectAsState()
    val aiResearchMode by viewModel.aiResearchSourceMode.collectAsState()
    val createAiState by viewModel.createAiState.collectAsState()
    val fillAiState by viewModel.fillAiState.collectAsState()
    val createAiFormState by viewModel.createAiFormState.collectAsState()
    val fillAiFormState by viewModel.fillAiFormState.collectAsState()
    val referenceDocumentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val callback = pendingReferenceDocumentPick
        pendingReferenceDocumentPick = null
        if (uri != null && callback != null) viewModel.readReferenceDocument(uri, callback)
    }

    fun pickReferenceDocument(callback: (Result<CharacterReferenceDocument>) -> Unit) {
        pendingReferenceDocumentPick = callback
        referenceDocumentPicker.launch(arrayOf("text/*", "application/json", "application/octet-stream"))
    }

    fun requestExit() {
        if (viewModel.hasLocalChanges) showExitDialog = true else onBack()
    }

    BackHandler {
        requestExit()
    }

    CbScaffold(
        modifier = modifier,
        topBar = {
            CbTopBar(
                title = if (worldBookId == null) "新建世界书" else "编辑世界书",
                statusBarInset = true,
                navigation = { CbIconButton(AppIcons.ArrowBack, "返回", ::requestExit) },
                actions = {
                    CbIconButton(
                        AppIcons.HelpOutline,
                        "世界书教程",
                        { showTutorial = true }
                    )
                    CbIconButton(
                        AppIcons.Save,
                        "保存",
                        { viewModel.save(onBack) },
                        enabled = viewModel.name.isNotBlank(),
                        tint = ChatBarTheme.colors.primary,
                        dirty = viewModel.hasLocalChanges
                    )
                }
            )
        }
    ) { bottomInset ->
        Column(
            Modifier
                .fillMaxSize()
                .background(ChatBarTheme.colors.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CbText("基础信息", color = ChatBarTheme.colors.primary, style = ChatBarTheme.typography.heading)
            viewModel.draftSavedAt?.let { savedAt ->
                CbText(
                    "草稿已保存 ${formatDraftSavedAt(savedAt)}",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
            CbField("名称") { CbInput(viewModel.name, { viewModel.name = it; viewModel.scheduleDraftSave() }, placeholder = "世界书名称") }
            CbField("描述", onFullscreenEdit = { descriptionFullscreen = true }) {
                CbInput(
                    viewModel.description,
                    { viewModel.description = it; viewModel.scheduleDraftSave() },
                    singleLine = false,
                    minLines = 2
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CbField("扫描深度", Modifier.weight(1f)) {
                    CbInput(
                        viewModel.scanDepth.toString(),
                        {
                            viewModel.scanDepth = it.toInt().coerceAtLeast(0)
                            viewModel.scheduleDraftSave()
                        },
                        inputTransformation = InputTransformation.byValue { current, proposed ->
                            proposed.takeIf { value ->
                                value.isNotEmpty() && value.all(Char::isDigit) && value.toString().toIntOrNull() != null
                            } ?: current
                        }
                    )
                }
                CbField("Token 预算", Modifier.weight(1f)) {
                    CbInput(viewModel.tokenBudget, { viewModel.tokenBudget = it; viewModel.scheduleDraftSave() }, placeholder = "空 = 不限制")
                }
            }
            ToggleRow("递归扫描", viewModel.recursiveScanning) { viewModel.recursiveScanning = it; viewModel.scheduleDraftSave() }
            ToggleRow("大小写敏感", viewModel.caseSensitive) { viewModel.caseSensitive = it; viewModel.scheduleDraftSave() }
            ToggleRow("整词匹配", viewModel.matchWholeWords) { viewModel.matchWholeWords = it; viewModel.scheduleDraftSave() }

            CbDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                CbText("条目 (${viewModel.entries.size})", color = ChatBarTheme.colors.primary, style = ChatBarTheme.typography.heading)
                CbButton("添加条目", { viewModel.openEntryDialog(null) }, variant = ButtonVariant.Ghost)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbButton(
                    "AI 创建条目",
                    { aiOperation = WorldBookAiOperation.CREATE },
                    modifier = Modifier.weight(1f),
                    variant = ButtonVariant.Outline,
                    enabled = !createAiState.isGenerating && !fillAiState.isGenerating
                )
                CbButton(
                    "AI 填充内容（${viewModel.emptyContentCount}）",
                    { aiOperation = WorldBookAiOperation.FILL },
                    modifier = Modifier.weight(1f),
                    variant = ButtonVariant.Outline,
                    enabled = viewModel.emptyContentCount > 0 && !createAiState.isGenerating && !fillAiState.isGenerating
                )
            }
            if (viewModel.emptyContentCount == 0) {
                CbText("没有正文为空的条目，AI 填充内容暂不可用。", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
            }
            viewModel.entries.forEachIndexed { index, entry ->
                CbSurface(Modifier.fillMaxWidth().clickable { viewModel.openEntryDialog(index) }) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            CbText(entry.name.ifBlank { "未命名条目" }, style = ChatBarTheme.typography.heading)
                            CbText(entry.keys.joinToString(", ").ifBlank { "无触发词" }, color = ChatBarTheme.colors.mutedForeground, maxLines = 1)
                        }
                        CbIconButton(AppIcons.Edit, "编辑", { viewModel.openEntryDialog(index) }, tint = ChatBarTheme.colors.primary)
                        CbIconButton(AppIcons.Delete, "删除", { deleteIndex = index }, tint = ChatBarTheme.colors.destructive)
                    }
                }
            }
            Spacer(Modifier.height(24.dp + bottomInset))
        }
    }

    FullscreenTextEditor(
        title = "编辑世界书描述",
        text = viewModel.description,
        onTextChange = {
            viewModel.description = it
            viewModel.scheduleDraftSave()
        },
        visible = descriptionFullscreen,
        onDismiss = { descriptionFullscreen = false }
    )

    if (showTutorial) {
        WorldBookTutorialDialog(onDismiss = { showTutorial = false })
    }

    aiOperation?.let { operation ->
        WorldBookAiDialog(
            operation = operation,
            createState = createAiState,
            fillState = fillAiState,
            formState = if (operation == WorldBookAiOperation.CREATE) createAiFormState else fillAiFormState,
            onFormStateChange = if (operation == WorldBookAiOperation.CREATE) {
                viewModel::updateCreateAiForm
            } else {
                viewModel::updateFillAiForm
            },
            targetCount = viewModel.emptyContentCount,
            models = aiModels,
            defaultModelId = aiDefaultModelId,
            researchMode = aiResearchMode,
            onResearchModeChange = viewModel::setAiResearchSourceMode,
            onPickDocument = ::pickReferenceDocument,
            onGenerate = { request, modelId, document, options, resume ->
                when (operation) {
                    WorldBookAiOperation.CREATE -> viewModel.generateCreateCandidates(request, modelId, document, options, resume)
                    WorldBookAiOperation.FILL -> viewModel.generateFillCandidates(request, modelId, document, options, resume)
                }
            },
            onCancel = viewModel::cancelAiGeneration,
            onToggleCandidate = { id ->
                when (operation) {
                    WorldBookAiOperation.CREATE -> viewModel.toggleCreateCandidate(id)
                    WorldBookAiOperation.FILL -> viewModel.toggleFillCandidate(id)
                }
            },
            onSelectAll = { selected ->
                when (operation) {
                    WorldBookAiOperation.CREATE -> viewModel.selectAllCreateCandidates(selected)
                    WorldBookAiOperation.FILL -> viewModel.selectAllFillCandidates(selected)
                }
            },
            onApply = {
                when (operation) {
                    WorldBookAiOperation.CREATE -> viewModel.applyCreateCandidates()
                    WorldBookAiOperation.FILL -> viewModel.applyFillCandidates()
                }
                aiOperation = null
            },
            onDismiss = {
                if (createAiState.isGenerating || fillAiState.isGenerating) viewModel.cancelAiGeneration()
                aiOperation = null
            }
        )
    }

    viewModel.entryModalState?.let { state ->
        WorldBookEntryEditDialog(
            state = state,
            onStateChange = viewModel::updateEntryDialog,
            onDismiss = viewModel::dismissEntryDialog,
            onSave = viewModel::saveEntryDialog
        )
    }
    deleteIndex?.let { index ->
        CbDialog(
            onDismissRequest = { deleteIndex = null },
            title = "删除世界书条目",
            dismiss = { CbButton("取消", { deleteIndex = null }, variant = ButtonVariant.Ghost) },
            confirm = { CbButton("删除", { viewModel.deleteEntry(index); deleteIndex = null }, variant = ButtonVariant.Destructive) }
        ) { CbText("确定删除该条目？", color = ChatBarTheme.colors.mutedForeground) }
    }

    viewModel.restoreDraft?.let { draft ->
        CbDialog(
            onDismissRequest = viewModel::keepOriginal,
            title = if (viewModel.sourceDeleted) "源世界书已删除" else "发现未保存草稿",
            dismissOnClickOutside = false
        ) {
            CbText(
                when {
                    viewModel.sourceDeleted -> "原世界书已不存在，可将草稿转为新世界书继续保存。"
                    viewModel.restoreConflict -> "“${draft.title}”有未保存草稿，且原世界书在草稿创建后已更新。恢复草稿不会覆盖原世界书，正式保存时需要选择覆盖或另存为新世界书。"
                    else -> "“${draft.title}”有未保存草稿。恢复草稿不会覆盖原世界书，只有点保存才会写入正式内容。"
                },
                color = ChatBarTheme.colors.mutedForeground
            )
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CbButton("恢复草稿", viewModel::restoreDraft, modifier = Modifier.fillMaxWidth())
                CbButton("查看原内容", viewModel::keepOriginal, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
                CbButton("清除草稿", { viewModel.discardDraft() }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Destructive)
            }
        }
    }

    if (showExitDialog) {
        CbDialog(
            onDismissRequest = { showExitDialog = false },
            title = "退出编辑",
            dismissOnClickOutside = false
        ) {
            CbText("当前修改已自动保存为草稿。", color = ChatBarTheme.colors.mutedForeground)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CbButton("保存草稿并退出", { viewModel.saveDraftAndExit(onBack) }, modifier = Modifier.fillMaxWidth())
                CbButton("继续编辑", { showExitDialog = false }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
                CbButton("清除草稿并退出", { viewModel.discardDraft(onBack) }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Destructive)
            }
        }
    }

    if (viewModel.saveConflict) {
        CbDialog(
            onDismissRequest = { viewModel.saveConflict = false },
            title = "原世界书已更新",
            dismissOnClickOutside = false
        ) {
            CbText("草稿创建后，原世界书已有新改动。请选择覆盖原书或另存为新世界书。", color = ChatBarTheme.colors.mutedForeground)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CbButton("覆盖原书", { viewModel.saveConflict = false; viewModel.save(onBack, forceOverwrite = true) }, modifier = Modifier.fillMaxWidth())
                CbButton("另存为新书", { viewModel.saveConflict = false; viewModel.save(onBack, saveAsNew = true) }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
                CbButton("取消", { viewModel.saveConflict = false }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Ghost)
            }
        }
    }
}

private enum class WorldBookAiOperation { CREATE, FILL }

private data class WorldBookAiModelOption(val id: String?, val label: String)

@Composable
private fun WorldBookAiDialog(
    operation: WorldBookAiOperation,
    createState: WorldBookCreateUiState,
    fillState: WorldBookFillUiState,
    formState: WorldBookAiFormUiState,
    onFormStateChange: (WorldBookAiFormUiState) -> Unit,
    targetCount: Int,
    models: List<ModelConfig>,
    defaultModelId: String?,
    researchMode: CharacterResearchSourceMode,
    onResearchModeChange: (CharacterResearchSourceMode) -> Unit,
    onPickDocument: (((Result<CharacterReferenceDocument>) -> Unit) -> Unit),
    onGenerate: (String, String?, CharacterReferenceDocument?, CharacterResearchOptions, Boolean) -> Unit,
    onCancel: () -> Unit,
    onToggleCandidate: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    var referenceDocumentError by remember(operation) { mutableStateOf<String?>(null) }
    var requestFullscreen by remember(operation) { mutableStateOf(false) }
    val request = formState.request
    val manualUrlsText = formState.manualUrlsText
    val referenceDocument = formState.referenceDocument
    val selectedModelId = formState.selectedModelId
    if (requestFullscreen) {
        FullscreenTextEditor(
            title = if (operation == WorldBookAiOperation.CREATE) "编辑创建需求" else "编辑填充要求",
            text = request,
            onTextChange = { onFormStateChange(formState.copy(request = it)) },
            visible = true,
            onDismiss = { requestFullscreen = false }
        )
        return
    }

    val modelOptions = remember(models, defaultModelId) {
        val defaultLabel = models.firstOrNull { it.id == defaultModelId }?.displayName
        listOf(WorldBookAiModelOption(null, defaultLabel?.let { "默认模型：$it" } ?: "默认模型")) +
            models.map { WorldBookAiModelOption(it.id, it.displayName.ifBlank { it.modelName }) }
    }
    val selectedModel = modelOptions.firstOrNull { it.id == selectedModelId } ?: modelOptions.first()
    val manualValidation = remember(manualUrlsText) { validateManualResearchUrls(manualUrlsText) }
    val options = CharacterResearchOptions(
        mode = researchMode,
        urls = if (researchMode.usesManualUrls()) manualValidation.urls else emptyList()
    )
    val isCreate = operation == WorldBookAiOperation.CREATE
    val busy = if (isCreate) createState.isGenerating else fillState.isGenerating
    val candidatesCount = if (isCreate) createState.candidates.size else fillState.candidates.size
    val previewReady = if (isCreate) createState.isComplete else fillState.isComplete
    val selectedIds = if (isCreate) createState.selectedIds else fillState.selectedIds
    val status = if (isCreate) createState.statusText else fillState.statusText
    val error = if (isCreate) createState.error else fillState.error
    val warning = if (isCreate) createState.warning else fillState.warning
    val progressLines = if (isCreate) createState.progressLines else fillState.progressLines
    val outputs = if (isCreate) createState.outputs else fillState.outputs
    val debug = if (isCreate) createState.researchDebug else fillState.researchDebug
    val hasCheckpoint = if (isCreate) createState.checkpoint != null else fillState.checkpoint != null
    val manualReady = !researchMode.usesManualUrls() || (manualValidation.isValid && manualValidation.urls.isNotEmpty())
    val inputReady = if (isCreate) {
        request.isNotBlank() || referenceDocument != null || options.urls.isNotEmpty()
    } else {
        targetCount > 0
    }

    CbDialog(
        onDismissRequest = onDismiss,
        title = if (isCreate) "AI 创建世界书条目" else "AI 填充世界书内容",
        modifier = Modifier.heightIn(max = 760.dp),
        dismissOnClickOutside = !busy,
        dismissOnBackPress = !busy,
        dismiss = { CbButton("关闭", onDismiss, variant = ButtonVariant.Ghost, enabled = !busy) },
        confirm = {
            if (!busy && previewReady && candidatesCount > 0) {
                CbButton(
                    if (isCreate) "应用所选条目" else "应用所选正文",
                    onApply,
                    enabled = selectedIds.isNotEmpty()
                )
            }
        }
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 590.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CbText(
                if (isCreate) "每批最多创建 5 条，AI 自动判断是否继续；单次最多 50 条。" else "只处理当前正文为空的 $targetCount 个条目，每批最多 5 条。",
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
            CbField("本次使用模型") {
                CbSelect(
                    value = selectedModel,
                    options = modelOptions,
                    optionLabel = WorldBookAiModelOption::label,
                    onValueChange = { onFormStateChange(formState.copy(selectedModelId = it.id)) },
                    enabled = !busy,
                    placeholder = "选择模型"
                )
            }
            WorldBookResearchSourceSelector(
                mode = researchMode,
                onModeChange = onResearchModeChange,
                manualUrlsText = manualUrlsText,
                onManualUrlsTextChange = {
                    onFormStateChange(formState.copy(manualUrlsText = it))
                },
                validation = manualValidation,
                busy = busy
            )
            CbField(
                if (isCreate) "世界书需求" else "填充要求（可选）",
                description = if (isCreate) "说明需要覆盖的世界、主题、玩法和边界。" else "留空时根据条目名称、触发词和资料直接填写。",
                onFullscreenEdit = { requestFullscreen = true }
            ) {
                CbInput(
                    request,
                    { onFormStateChange(formState.copy(request = it)) },
                    placeholder = if (isCreate) "例如：为某作品规划地点、组织、能力体系和关键人物条目……" else "例如：优先采用原作设定，正文简洁且适合直接注入……",
                    singleLine = false,
                    minLines = 4,
                    enabled = !busy
                )
            }
            CbField("参考文档") {
                WorldBookReferenceDocumentPanel(
                    document = referenceDocument,
                    error = referenceDocumentError,
                    busy = busy,
                    onPick = {
                        onPickDocument { result ->
                            result.fold(
                                onSuccess = {
                                    onFormStateChange(formState.copy(referenceDocument = it))
                                    referenceDocumentError = null
                                },
                                onFailure = { referenceDocumentError = it.message ?: "读取参考文档失败" }
                            )
                        }
                    },
                    onClear = {
                        onFormStateChange(formState.copy(referenceDocument = null))
                        referenceDocumentError = null
                    }
                )
            }

            if (busy) {
                CbProgress(0.35f, Modifier.fillMaxWidth())
                CbButton("取消生成", onCancel, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Destructive)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbButton(
                        if (candidatesCount == 0) "生成候选" else "重新生成",
                        { onGenerate(request, selectedModel.id, referenceDocument, options, false) },
                        modifier = Modifier.weight(1f),
                        enabled = inputReady && manualReady,
                        variant = ButtonVariant.Secondary
                    )
                    if (hasCheckpoint && error != null) {
                        CbButton(
                            "继续生成",
                            { onGenerate(request, selectedModel.id, referenceDocument, options, true) },
                            modifier = Modifier.weight(1f),
                            enabled = inputReady && manualReady,
                            variant = ButtonVariant.Outline
                        )
                    }
                }
            }

            status.takeIf(String::isNotBlank)?.let {
                CbText(it, color = ChatBarTheme.colors.mutedForeground)
            }
            error?.let { CbText(it, color = ChatBarTheme.colors.destructive) }
            warning.takeIf(String::isNotBlank)?.let { CbText(it, color = ChatBarTheme.colors.warning) }
            if (progressLines.isNotEmpty()) {
                CbSurface(Modifier.fillMaxWidth(), color = ChatBarTheme.colors.muted) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CbText("流程进度", style = ChatBarTheme.typography.heading)
                        progressLines.takeLast(10).forEach { CbText(it, style = ChatBarTheme.typography.caption) }
                    }
                }
            }
            debug?.takeIf { it.hasContent() }?.let { research ->
                CbSurface(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, ChatBarTheme.colors.border)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CbText("资料状态", style = ChatBarTheme.typography.heading)
                        research.plan?.let { plan ->
                            CbText(
                                if (plan.needSearch) "搜索：${plan.queries.joinToString("、") { it.query }}" else "本批无需百科搜索",
                                color = ChatBarTheme.colors.mutedForeground,
                                style = ChatBarTheme.typography.caption
                            )
                        }
                        CbText("来源 ${research.sources.size} · 资料简报 ${if (research.brief?.hasContent() == true) "已完成" else "未完成"}", style = ChatBarTheme.typography.caption)
                    }
                }
            }

            if (previewReady && candidatesCount > 0) {
                CbDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    CbText("候选预览（$candidatesCount）", style = ChatBarTheme.typography.heading)
                    CbButton(
                        if (selectedIds.size == candidatesCount) "取消全选" else "全选",
                        { onSelectAll(selectedIds.size != candidatesCount) },
                        variant = ButtonVariant.Ghost
                    )
                }
                if (isCreate) {
                    createState.candidates.forEach { candidate ->
                        WorldBookCandidateRow(
                            id = candidate.candidateId,
                            title = candidate.name,
                            subtitle = candidate.keys.joinToString("、"),
                            content = "",
                            checked = candidate.candidateId in selectedIds,
                            onToggle = onToggleCandidate
                        )
                    }
                } else {
                    fillState.candidates.forEach { candidate ->
                        WorldBookCandidateRow(
                            id = candidate.targetId,
                            title = candidate.name.ifBlank { "未命名条目" },
                            subtitle = candidate.keys.joinToString("、"),
                            content = candidate.content,
                            checked = candidate.targetId in selectedIds,
                            onToggle = onToggleCandidate
                        )
                    }
                }
            }

            outputs.asReversed().forEach { output ->
                CbSurface(Modifier.fillMaxWidth(), color = ChatBarTheme.colors.muted) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CbText(output.title, style = ChatBarTheme.typography.heading)
                        CbText(output.text, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorldBookResearchSourceSelector(
    mode: CharacterResearchSourceMode,
    onModeChange: (CharacterResearchSourceMode) -> Unit,
    manualUrlsText: String,
    onManualUrlsTextChange: (String) -> Unit,
    validation: ManualResearchUrlValidation,
    busy: Boolean
) {
    CbField("资料来源", description = mode.worldBookSourceDescription()) {
        CbSelect(
            value = mode,
            options = CharacterResearchSourceMode.entries,
            optionLabel = CharacterResearchSourceMode::worldBookSourceLabel,
            onValueChange = onModeChange,
            enabled = !busy,
            placeholder = "选择资料来源"
        )
    }
    if (mode.usesManualUrls()) {
        CbField("指定网址", description = "每行一个完整 HTTP(S) 地址，最多 5 个。") {
            CbInput(
                manualUrlsText,
                onManualUrlsTextChange,
                placeholder = "https://example.com/page",
                singleLine = false,
                minLines = 3,
                enabled = !busy,
                isError = validation.errors.isNotEmpty()
            )
        }
        validation.errors.forEach { CbText(it, color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.caption) }
        if (validation.hasCleartextHttp) {
            CbText("HTTP 未加密，页面内容可能被中途篡改。", color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.caption)
        }
    }
}

@Composable
private fun WorldBookReferenceDocumentPanel(
    document: CharacterReferenceDocument?,
    error: String?,
    busy: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    CbSurface(
        Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.muted,
        border = BorderStroke(1.dp, if (error == null) ChatBarTheme.colors.border else ChatBarTheme.colors.destructive)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CbText(document?.fileName ?: "未选择文档", style = ChatBarTheme.typography.heading)
            document?.let {
                CbText("${it.content.length} 字符；仅本次生成使用，不会保存到世界书。", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                if (it.content.length > WORLD_BOOK_REFERENCE_DOCUMENT_WARNING_CHARS) {
                    CbText("文档超过 100 万字符，临时向量化可能耗时较长。", color = ChatBarTheme.colors.warning, style = ChatBarTheme.typography.caption)
                }
            }
            CbText("支持 TXT、MD、JSON，最多 500 万字符。", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbButton(if (document == null) "选择文档" else "更换文档", onPick, modifier = Modifier.weight(1f), enabled = !busy, variant = ButtonVariant.Outline)
                if (document != null) CbButton("移除", onClear, enabled = !busy, variant = ButtonVariant.Ghost)
            }
            error?.let { CbText(it, color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.caption) }
        }
    }
}

@Composable
private fun WorldBookCandidateRow(
    id: String,
    title: String,
    subtitle: String,
    content: String,
    checked: Boolean,
    onToggle: (String) -> Unit
) {
    CbSurface(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, ChatBarTheme.colors.border)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Top) {
            CbCheckbox(
                checked = checked,
                onCheckedChange = { onToggle(id) },
                contentDescription = "选择候选：$title"
            )
            Column(Modifier.weight(1f).padding(top = 6.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CbText(title, style = ChatBarTheme.typography.heading)
                CbText(subtitle.ifBlank { "无触发词" }, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                content.takeIf(String::isNotBlank)?.let { CbText(it) }
            }
        }
    }
}

private fun CharacterResearchSourceMode.worldBookSourceLabel(): String = when (this) {
    CharacterResearchSourceMode.NONE -> "不联网"
    CharacterResearchSourceMode.ENCYCLOPEDIA_SEARCH -> "百科搜索"
    CharacterResearchSourceMode.MANUAL_URLS -> "指定网址"
    CharacterResearchSourceMode.ENCYCLOPEDIA_SEARCH_AND_MANUAL_URLS -> "百科搜索 + 指定网址"
}

private fun CharacterResearchSourceMode.worldBookSourceDescription(): String = when (this) {
    CharacterResearchSourceMode.NONE -> "不联网；仍可使用上传参考文档。"
    CharacterResearchSourceMode.ENCYCLOPEDIA_SEARCH -> "AI 规划萌娘百科/Wikipedia 搜索；可叠加参考文档。"
    CharacterResearchSourceMode.MANUAL_URLS -> "只读取指定页面；可叠加参考文档。"
    CharacterResearchSourceMode.ENCYCLOPEDIA_SEARCH_AND_MANUAL_URLS -> "百科搜索与指定页面合并整理；可叠加参考文档。"
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onValue: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        CbText(label)
        CbSwitch(value, onValue)
    }
}

@Composable
private fun WorldBookEntryEditDialog(
    state: WorldBookEntryModalState,
    onStateChange: (WorldBookEntryModalState) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    var contentFullscreen by remember(state.editingIndex) { mutableStateOf(false) }
    val canSave = state.hasMeaningfulEntryData()
    if (contentFullscreen) {
        FullscreenTextEditor(
            title = "编辑条目内容",
            text = state.content,
            onTextChange = { onStateChange(state.copy(content = it)) },
            visible = true,
            onDismiss = { contentFullscreen = false }
        )
        return
    }

    CbDialog(
        onDismissRequest = onDismiss,
        title = if (state.editingIndex == null) "添加世界书条目" else "编辑世界书条目",
        modifier = Modifier.heightIn(max = 760.dp),
        dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) },
        confirm = {
            CbButton("保存", onSave, enabled = canSave)
        }
    ) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!canSave) {
                CbText(
                    "名称、主触发词和正文不能同时为空。",
                    color = ChatBarTheme.colors.destructive,
                    style = ChatBarTheme.typography.caption
                )
            }
            CbField("条目名称") { CbInput(state.name, { onStateChange(state.copy(name = it)) }) }
            CbField("主触发词", description = "多个用英文逗号分隔。") { CbInput(state.keys, { onStateChange(state.copy(keys = it)) }) }
            CbField("二级触发词") { CbInput(state.secondary, { onStateChange(state.copy(secondary = it)) }) }
            CbField("二级逻辑") {
                CbSelect(
                    state.logic,
                    listOf(0, 1, 2, 3),
                    {
                        when (it) {
                            1 -> "NOT ALL"
                            2 -> "NOT ANY"
                            3 -> "AND ALL"
                            else -> "AND ANY"
                        }
                    },
                    { onStateChange(state.copy(logic = it)) }
                )
            }
            CbField("插入位置") {
                CbSelect(
                    state.position,
                    listOf(WorldBookPosition.BEFORE_CHAR, WorldBookPosition.AFTER_CHAR, WorldBookPosition.OUTLET),
                    {
                        when (it) {
                            WorldBookPosition.BEFORE_CHAR -> "角色设定之前"
                            WorldBookPosition.AFTER_CHAR -> "角色设定之后"
                            WorldBookPosition.OUTLET -> "Outlet"
                        }
                    },
                    { onStateChange(state.copy(position = it)) }
                )
            }
            if (state.position == WorldBookPosition.OUTLET) CbField("Outlet 名称") { CbInput(state.outlet, { onStateChange(state.copy(outlet = it)) }) }
            CbField("内容", onFullscreenEdit = { contentFullscreen = true }) {
                CbInput(state.content, { onStateChange(state.copy(content = it)) }, singleLine = false, minLines = 5)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CbField("顺序", Modifier.weight(1f)) { CbInput(state.order, { onStateChange(state.copy(order = it)) }) }
                CbField("触发概率", Modifier.weight(1f)) { CbInput(state.probability, { onStateChange(state.copy(probability = it)) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CbField("扫描深度覆盖", Modifier.weight(1f)) { CbInput(state.scanDepth, { onStateChange(state.copy(scanDepth = it)) }, placeholder = "空 = 使用书设置") }
                CbField("分组权重", Modifier.weight(1f)) { CbInput(state.groupWeight, { onStateChange(state.copy(groupWeight = it)) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CbField("Sticky", Modifier.weight(1f)) { CbInput(state.sticky, { onStateChange(state.copy(sticky = it)) }) }
                CbField("Cooldown", Modifier.weight(1f)) { CbInput(state.cooldown, { onStateChange(state.copy(cooldown = it)) }) }
                CbField("Delay", Modifier.weight(1f)) { CbInput(state.delay, { onStateChange(state.copy(delay = it)) }) }
            }
            CbField("分组") { CbInput(state.group, { onStateChange(state.copy(group = it)) }) }
            ToggleRow("启用", state.enabled) { onStateChange(state.copy(enabled = it)) }
            ToggleRow("常驻", state.constant) { onStateChange(state.copy(constant = it)) }
            ToggleRow("Regex 触发词", state.useRegex) { onStateChange(state.copy(useRegex = it)) }
            ToggleRow("整词匹配", state.wholeWords) { onStateChange(state.copy(wholeWords = it)) }
            ToggleRow("大小写敏感", state.caseSensitive) { onStateChange(state.copy(caseSensitive = it)) }
            ToggleRow("忽略 Token 预算", state.ignoreBudget) { onStateChange(state.copy(ignoreBudget = it)) }
            ToggleRow("递归时排除", state.excludeRecursion) { onStateChange(state.copy(excludeRecursion = it)) }
            ToggleRow("阻止由本条目递归", state.preventRecursion) { onStateChange(state.copy(preventRecursion = it)) }
            ToggleRow("仅递归触发", state.delayUntilRecursion) { onStateChange(state.copy(delayUntilRecursion = it)) }
        }
    }
}

private data class WorldBookHelpSection(
    val title: String,
    val items: List<Pair<String, String>>
)

private val worldBookHelpSections = listOf(
    WorldBookHelpSection(
        title = "整本世界书",
        items = listOf(
            "扫描深度" to "每次回复前，往回查看最近多少条聊天消息来找触发词。数字越大，越容易找到较早提过的内容，也会多做一些检查。一般保持默认；短对话可小些，长线剧情可适当加大。",
            "Token 预算" to "这本世界书一次最多带多少内容给模型，可理解为“内容长度上限”。留空表示不限制。设得太小，部分已触发条目可能放不进去。",
            "递归扫描" to "一个条目触发后，再检查它的内容能否带出其他条目。适合有关联的设定；关系复杂时可能一次带出更多内容。",
            "大小写敏感、整词匹配" to "这两个书级开关会随世界书保存和导出。当前实际匹配以每个条目里的同名开关为准；需要控制效果时，请在条目中设置。"
        )
    ),
    WorldBookHelpSection(
        title = "触发与放置",
        items = listOf(
            "主触发词" to "最近聊天里出现任意一个主触发词，条目才有机会生效。多个词用英文逗号分开。",
            "二级触发词与二级逻辑" to "用于给主触发词再加一道条件。AND ANY 表示二级词出现任意一个；AND ALL 表示全部出现；NOT ANY 表示一个都不能出现；NOT ALL 表示不能全部同时出现。",
            "常驻" to "不检查触发词，每次都带上这条内容。适合始终有效的核心规则，但常驻太多会挤占对话空间。",
            "插入位置" to "“角色设定之前/之后”决定内容放在角色设定哪边。Outlet 是指定位置，只有格式卡预留了同名位置时才使用；普通用户选前两项即可。",
            "顺序" to "同一位置有多条内容时，数字较小的排在前面。预算不够时，数字较大的条目会优先保留。"
        )
    ),
    WorldBookHelpSection(
        title = "范围与次数",
        items = listOf(
            "触发概率" to "条件满足后真正生效的机会。100 表示每次生效，50 大约一半机会，0 表示不会生效。",
            "扫描深度覆盖" to "只给当前条目单独设置回看条数。留空就使用整本世界书的扫描深度；填 0 表示这条不会靠触发词生效。",
            "分组与分组权重" to "同一分组里一次只选一个条目。权重数字最大的胜出；适合互相排斥的天气、地点或状态。",
            "Sticky" to "条目生效后，接下来多少条消息继续保持，不必再次命中触发词。0 表示不保持。",
            "Cooldown" to "条目保持结束后，多少条消息内暂时不再触发。0 表示不冷却。",
            "Delay" to "聊天总消息数达到这个数字后，条目才允许生效。0 表示从一开始就可以。"
        )
    ),
    WorldBookHelpSection(
        title = "匹配与递归",
        items = listOf(
            "Regex 触发词" to "给熟悉表达式规则的用户使用。普通关键词保持关闭，输入什么就按什么找，更不容易出错。",
            "整词匹配" to "只匹配完整词，避免短词藏在长词中也误触发。中文没有天然空格分词，通常保持关闭更稳妥。",
            "大小写敏感" to "开启后，英文大写和小写必须一致；关闭时 Dragon 和 dragon 会被当作同一个词。",
            "忽略 Token 预算" to "即使世界书已达到内容上限，也要保留这条。只给绝不能遗漏的规则开启。",
            "递归时排除" to "聊天可以直接触发本条目，但其他条目的内容不能顺带触发它。",
            "阻止由本条目递归" to "本条目可以生效，但不再用它的内容继续寻找其他条目。",
            "仅递归触发" to "聊天不能直接触发本条目；只有其他条目生效后，才可能顺带带出它。"
        )
    )
)

@Composable
private fun WorldBookTutorialDialog(onDismiss: () -> Unit) {
    CbDialog(
        onDismissRequest = onDismiss,
        title = "世界书教程",
        confirm = { CbButton("知道了", onDismiss) }
    ) {
        Column(
            Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CbText(
                "世界书会在聊天中找到触发词，再把对应设定交给模型。拿不准时先用默认值，只填写触发词和内容也能正常使用。",
                color = ChatBarTheme.colors.mutedForeground
            )
            worldBookHelpSections.forEach { section ->
                CbSurface(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CbText(
                            section.title,
                            color = ChatBarTheme.colors.primary,
                            style = ChatBarTheme.typography.heading
                        )
                        section.items.forEach { (label, explanation) ->
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                CbText(label, style = ChatBarTheme.typography.label)
                                CbText(
                                    explanation,
                                    color = ChatBarTheme.colors.mutedForeground,
                                    style = ChatBarTheme.typography.caption
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDraftSavedAt(timeMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMs))
