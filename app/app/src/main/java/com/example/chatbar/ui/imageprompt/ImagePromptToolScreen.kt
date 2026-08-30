package com.example.chatbar.ui.imageprompt

import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.byValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlin.math.roundToInt
import com.example.chatbar.data.local.entity.NovelAiPromptTranslationConsent
import com.example.chatbar.domain.card.SharedImageDestination
import com.example.chatbar.domain.card.SharedImageImportRequest
import com.example.chatbar.domain.image.NovelAiAspectRatio
import com.example.chatbar.domain.image.NovelAiCharacterPromptDraft
import com.example.chatbar.domain.image.NovelAiGenerationSettings
import com.example.chatbar.domain.image.NovelAiGenerationChargeKind
import com.example.chatbar.domain.image.NovelAiHistoryApplyMode
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiImageUseTarget
import com.example.chatbar.domain.image.NovelAiStudioAssetRef
import com.example.chatbar.domain.image.NovelAiSampler
import com.example.chatbar.domain.image.NovelAiSeedMode
import com.example.chatbar.domain.image.NovelAiSizeTier
import com.example.chatbar.domain.image.NovelAiStudioMetadataSelection
import com.example.chatbar.domain.image.NovelAiStudioPngMetadata
import com.example.chatbar.domain.image.NovelAiTagCompletion
import com.example.chatbar.domain.image.NovelAiPromptAnnotation
import com.example.chatbar.domain.image.NovelAiPromptWrapPolicy
import com.example.chatbar.domain.image.requiresImageGuidanceReuseWarning
import com.example.chatbar.ui.kit.AppIcons
import com.example.chatbar.ui.components.ImageMosaicEditor
import com.example.chatbar.ui.components.ImagePreviewDialog
import com.example.chatbar.ui.components.ImagePreviewItem
import com.example.chatbar.ui.kit.ButtonSize
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbChoiceChip
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbDivider
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbSelect
import com.example.chatbar.ui.kit.CbSlider
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbSwitch
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.FullscreenTextEditor
import com.example.chatbar.ui.kit.rememberFullscreenTextEditorState
import com.example.chatbar.ui.kit.ChatBarShape
import com.example.chatbar.ui.kit.ChatBarSpacing
import com.example.chatbar.ui.kit.ChatBarElevation
import com.example.chatbar.ui.kit.ChatBarTheme

private data class StudioFullscreenEditRequest(
    val title: String,
    val value: TextFieldValue,
    val field: NovelAiPromptFieldKey?,
    val naturalLanguage: Boolean,
    val editorRevision: Int,
    val onApply: (TextFieldValue) -> Unit
)

private data class StudioTagEditTarget(
    val field: NovelAiPromptFieldKey,
    val insert: (String) -> Unit
)

internal fun isStudioFullscreenPromptSessionCurrent(
    openedEditorRevision: Int,
    currentEditorRevision: Int
): Boolean = openedEditorRevision == currentEditorRevision

@Composable
fun ImagePromptToolScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAiDesign: () -> Unit,
    sharedImageRequest: SharedImageImportRequest? = null,
    onSharedImageImported: (Long) -> Boolean = { false },
    onSharedImageFailed: (Long, String) -> Boolean = { _, _ -> false },
    viewModel: ImagePromptToolViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val configured by viewModel.novelAiConfigured.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var fullscreenEdit by remember { mutableStateOf<StudioFullscreenEditRequest?>(null) }
    var previewPath by remember { mutableStateOf<String?>(null) }
    var importedEditorPath by remember { mutableStateOf<String?>(null) }
    var importedPreviewPath by remember { mutableStateOf<String?>(null) }
    var showMetadataSelection by remember { mutableStateOf(false) }
    var showImageTools by remember { mutableStateOf(false) }
    var showGuidanceEditor by remember { mutableStateOf(false) }
    var guidancePickTarget by remember { mutableStateOf<NovelAiImageUseTarget?>(null) }
    var stagedGuidanceAsset by remember {
        mutableStateOf<Pair<NovelAiImageUseTarget, Pair<NovelAiStudioAssetRef, NovelAiStudioAssetRef?>>?>(null)
    }
    var useAsPath by remember { mutableStateOf<String?>(null) }
    var pendingRecentApply by remember { mutableStateOf<NovelAiHistoryApplyMode?>(null) }
    var activeTagEditTarget by remember { mutableStateOf<StudioTagEditTarget?>(null) }
    var claimedSharedImage by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            showImageTools = true
            viewModel.importImage(uri)
        }
    }
    val guidancePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = guidancePickTarget
        guidancePickTarget = null
        if (uri != null && target != null) {
            viewModel.stageGuidanceImage(uri, target) { asset, mask ->
                stagedGuidanceAsset = target to (asset to mask)
            }
        }
    }
    val previewPaths = (state.recentHistoryItems.map { it.image.path } + state.imagePaths).distinct()
    val accountUsage = state.account.usage
    val anlasLabel = state.account.displayAnlas?.toString()
        ?: if (state.account.loading) "…" else if (state.account.error != null) "获取失败" else "—"
    val v5Label = state.account.approximateV5Images?.let { "约${it}张" }
        ?: if (state.account.loading) "…" else if (state.account.error != null) "获取失败" else "—"
    val generationCost = state.generationCost
    val guidanceSummary = state.draft.imageGuidance.summary(state.draft.selectedModel)
    val openGuidanceEditor: () -> Unit = {
        viewModel.beginGuidanceEditor()
        showGuidanceEditor = true
    }
    val closeFullscreenEdit: () -> Unit = {
        fullscreenEdit = null
        activeTagEditTarget = null
        viewModel.clearTagSuggestions()
        viewModel.restoreDraftPromptAnnotations()
    }
    LaunchedEffect(Unit) { viewModel.refreshAccountUsage() }
    LaunchedEffect(state.promptTranslationNotice) {
        state.promptTranslationNotice?.let { notice ->
            Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
            viewModel.consumePromptTranslationNotice()
        }
    }
    LaunchedEffect(state.guidanceEditorRequest) {
        if (state.guidanceEditorRequest != null) {
            openGuidanceEditor()
            viewModel.consumeGuidanceEditorRequest()
        }
    }
    LaunchedEffect(
        sharedImageRequest?.queueId,
        sharedImageRequest?.attempt,
        state.draftLoaded,
        state.isBusy,
        state.applyingHistory
    ) {
        val request = sharedImageRequest
        if (request == null) {
            claimedSharedImage = null
            return@LaunchedEffect
        }
        if (!state.draftLoaded || state.isBusy || state.applyingHistory) return@LaunchedEffect
        val key = request.queueId to request.attempt
        if (claimedSharedImage == key) return@LaunchedEffect
        claimedSharedImage = key
        val finish: (Result<Unit>) -> Unit = { result ->
            result.fold(
                onSuccess = { onSharedImageImported(request.queueId) },
                onFailure = { error ->
                    onSharedImageFailed(
                        request.queueId,
                        error.message ?: "共享图片导入失败"
                    )
                }
            )
        }
        when (request.destination) {
            SharedImageDestination.GUIDANCE -> viewModel.useSharedImage(
                request.path,
                NovelAiImageUseTarget.IMAGE_TO_IMAGE,
                finish
            )
            SharedImageDestination.TOOLS -> {
                showImageTools = true
                viewModel.importSharedImage(request.path, request.displayName, finish)
            }
        }
    }
    LaunchedEffect(state.promptEditorRevision, fullscreenEdit?.editorRevision) {
        val request = fullscreenEdit
        if (request?.field != null && !isStudioFullscreenPromptSessionCurrent(
                request.editorRevision,
                state.promptEditorRevision
            )
        ) {
            closeFullscreenEdit()
            Toast.makeText(context, "Prompt 已由外部更新，旧全屏草稿已丢弃", Toast.LENGTH_SHORT).show()
        }
    }
    BackHandler(enabled = fullscreenEdit == null && previewPath == null && !showGuidanceEditor) {
        viewModel.persistDraftNow()
        onBack()
    }
    BackHandler(
        enabled = sharedImageRequest != null && fullscreenEdit == null && previewPath == null && !showGuidanceEditor
    ) {
        Toast.makeText(context, "共享图片仍在等待安全导入", Toast.LENGTH_SHORT).show()
    }
    BackHandler(enabled = fullscreenEdit != null) {
        closeFullscreenEdit()
    }

    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(imeVisible, fullscreenEdit) {
        if (!imeVisible && fullscreenEdit == null) viewModel.clearTagSuggestions()
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .then(if (fullscreenEdit == null) Modifier.imePadding() else Modifier)
    ) {
        val expandedOutputMaxHeight =
            (maxHeight * 0.5f - ChatBarSpacing.md * 2).coerceAtLeast(0.dp)
        Column(Modifier.fillMaxSize()) {
        CbTopBar(
            title = "",
            navigation = {
                CbIconButton(AppIcons.ArrowBack, "返回", { viewModel.persistDraftNow(); onBack() })
                Column(Modifier.padding(start = ChatBarSpacing.xs)) {
                    CbText("Anlas · $anlasLabel", style = ChatBarTheme.typography.label)
                    CbText(
                        "V5 · $v5Label",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                }
            },
            actions = {
                CbIconButton(
                    AppIcons.Bot,
                    "AI 设计",
                    { viewModel.openAiDesign(onOpenAiDesign) },
                    enabled = !state.isBusy && !state.applyingHistory
                )
                CbIconButton(
                    AppIcons.Image,
                    if (guidanceSummary.isBlank()) "图像引导" else "图像引导：已启用 $guidanceSummary",
                    openGuidanceEditor,
                    enabled = !state.isBusy && !state.applyingHistory,
                    tint = if (guidanceSummary.isNotBlank()) ChatBarTheme.colors.primary else ChatBarTheme.colors.foreground,
                    dirty = guidanceSummary.isNotBlank()
                )
                CbIconButton(
                    AppIcons.Tools,
                    "图片工具",
                    { imagePicker.launch(arrayOf("image/*")) },
                    enabled = !state.isBusy && !state.applyingHistory
                )
                CbButton("历史", { viewModel.persistDraftNow(); onOpenHistory() }, variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
            }
        )
        OutputPanel(
            state = state,
            onToggle = viewModel::toggleOutputExpanded,
            onSelect = viewModel::selectOutput,
            onSelectRecent = viewModel::selectRecentImage,
            onApplyRecent = { mode ->
                val selected = state.selectedRecentHistoryItem
                if (selected != null && selected.entry.recipe.requiresImageGuidanceReuseWarning(mode)) {
                    pendingRecentApply = mode
                } else {
                    viewModel.applySelectedRecentHistory(mode)
                }
            },
            expanded = state.draft.outputExpanded && !imeVisible,
            expandedMaxHeight = expandedOutputMaxHeight,
            onOpenImage = { path -> previewPath = path },
            onUseAs = { path -> useAsPath = path },
            compactTagSuggestions = activeTagEditTarget?.takeIf { imeVisible }?.let { target ->
                state.tagSuggestions.takeIf { it.field == target.field }
                    ?: NovelAiTagSuggestionState(field = target.field)
            },
            onInsertTag = { candidate ->
                activeTagEditTarget?.insert?.invoke(candidate)
                viewModel.clearTagSuggestions()
            }
        )
        if (state.hasHistoryUndo) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = ChatBarSpacing.md, vertical = ChatBarSpacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CbText("已应用历史配方", Modifier.weight(1f), color = ChatBarTheme.colors.mutedForeground)
                CbButton("撤销", viewModel::undoHistoryApply, variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
                CbButton("关闭", viewModel::clearHistoryUndo, variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.md)
        ) {
            item {
                key(state.promptEditorRevision) {
                    PromptSection(
                        state = state,
                        viewModel = viewModel,
                        onFullscreenEdit = { title, value, field, naturalLanguage, onApply ->
                            activeTagEditTarget = null
                            viewModel.clearTagSuggestions()
                            fullscreenEdit = StudioFullscreenEditRequest(
                                title = title,
                                value = value,
                                field = field,
                                naturalLanguage = naturalLanguage,
                                editorRevision = state.promptEditorRevision,
                                onApply = onApply
                            )
                        },
                        onTagEditTarget = { activeTagEditTarget = it },
                        onTagEditEnd = { field ->
                            if (activeTagEditTarget?.field == field) activeTagEditTarget = null
                            viewModel.clearTagSuggestions()
                        }
                    )
                }
            }
            item {
                GenerationSettingsSection(
                    state.draft.activeSettings,
                    state.draft.advancedExpanded,
                    guidanceSummary,
                    viewModel
                )
            }
            item { Spacer(Modifier.height(ChatBarSpacing.sm)) }
        }
        if (fullscreenEdit == null) CbSurface(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            shape = RoundedCornerShape(topStart = ChatBarShape.lg, topEnd = ChatBarShape.lg),
            border = BorderStroke(1.dp, ChatBarTheme.colors.border),
            elevation = 4.dp
        ) {
            Column(Modifier.fillMaxWidth()) {
                if (guidanceSummary.isNotBlank()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = openGuidanceEditor)
                            .padding(horizontal = ChatBarSpacing.md, vertical = ChatBarSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CbText(
                            "图像引导已启用 · $guidanceSummary",
                            Modifier.weight(1f),
                            color = ChatBarTheme.colors.primary,
                            style = ChatBarTheme.typography.caption
                        )
                        CbText("点击管理", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(end = ChatBarSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PromptTokenBudget(state.promptTokens, Modifier.weight(1f))
                    CbIconButton(
                        AppIcons.Undo,
                        "撤销工作室修改",
                        viewModel::undoDraftChange,
                        enabled = state.canUndoDraft &&
                            (!state.isBusy || state.isGeneratingImage) && !state.applyingHistory
                    )
                    CbIconButton(
                        AppIcons.Redo,
                        "重做工作室修改",
                        viewModel::redoDraftChange,
                        enabled = state.canRedoDraft &&
                            (!state.isBusy || state.isGeneratingImage) && !state.applyingHistory
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(
                        start = ChatBarSpacing.md,
                        top = ChatBarSpacing.sm,
                        end = ChatBarSpacing.md,
                        bottom = ChatBarSpacing.md
                    ),
                    horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
                ) {
                    CbButton(
                        "复制正向",
                        onClick = { clipboard.setText(buildAnnotatedString { append(viewModel.positivePromptForClipboard()) }) },
                        modifier = Modifier.weight(0.42f),
                        variant = ButtonVariant.Outline
                    )
                    CbButton(
                        text = when {
                            state.applyingHistory -> "正在应用历史"
                            state.phase == ImagePromptToolPhase.CANCELLING -> "正在停止"
                            state.isBusy -> "停止当前任务"
                            !configured -> "未配置 Token"
                            generationCost.anlas > 0 -> buildString {
                                append("生成消耗 ${generationCost.anlas} Anlas")
                                if (generationCost.encodingAnlas > 0) append("（含编码 ${generationCost.encodingAnlas}）")
                                if (generationCost.extraVibeAnlas > 0) append("（含额外 Vibe ${generationCost.extraVibeAnlas}）")
                            }
                            generationCost.kind == NovelAiGenerationChargeKind.V5_ALLOWANCE -> "生成免费"
                            generationCost.kind == NovelAiGenerationChargeKind.FREE -> "生成免费"
                            else -> "生成消耗 ${generationCost.anlas} Anlas"
                        },
                        onClick = when {
                            state.applyingHistory -> ({})
                            state.phase == ImagePromptToolPhase.CANCELLING -> ({})
                            state.isBusy -> viewModel::cancelActiveTask
                            else -> viewModel::generateImage
                        },
                        modifier = Modifier.weight(0.58f),
                        enabled = !state.applyingHistory && configured &&
                            state.phase != ImagePromptToolPhase.CANCELLING && (state.canGenerate || state.isBusy),
                        variant = if (state.isBusy) ButtonVariant.Destructive else ButtonVariant.Default,
                        supportingText = state.draft.imageGuidance.validationError(state.draft.selectedModel)
                            ?: if (!state.applyingHistory && !state.isBusy && configured &&
                                generationCost.kind == NovelAiGenerationChargeKind.V5_ALLOWANCE && generationCost.anlas == 0
                            ) "消耗 V5 额度" else null
                    )
                }
            }
        }
        }
        fullscreenEdit?.let { request ->
            key(request.field, request.editorRevision) {
                if (request.field == null) {
                    FullscreenTextEditor(
                        title = request.title,
                        value = request.value,
                        onValueChange = request.onApply,
                        visible = true,
                        onDismiss = closeFullscreenEdit
                    )
                } else {
                    StudioFullscreenPromptEditor(
                        request = request,
                        currentEditorRevision = state.promptEditorRevision,
                        translationEnabled = state.promptTranslationConsent ==
                            NovelAiPromptTranslationConsent.ENABLED,
                        annotations = state.promptAnnotations[request.field].orEmpty(),
                        suggestions = state.tagSuggestions.takeIf { it.field == request.field }
                            ?: NovelAiTagSuggestionState(field = request.field),
                        viewModel = viewModel,
                        onDismiss = closeFullscreenEdit,
                        onStaleSession = {
                            closeFullscreenEdit()
                            Toast.makeText(
                                context,
                                "Prompt 已由外部更新，旧全屏草稿已丢弃",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            }
        }
    }

    if (showGuidanceEditor) {
        NovelAiImageGuidanceEditor(
            initial = state.guidanceCheckpoint ?: state.draft.imageGuidance,
            model = state.draft.selectedModel,
            onDismiss = {
                showGuidanceEditor = false
                stagedGuidanceAsset = null
                viewModel.clearGuidanceCheckpoint()
            },
            onPickImage = { target ->
                guidancePickTarget = target
                guidancePicker.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
            },
            stagedAsset = stagedGuidanceAsset,
            onConsumeStagedAsset = { stagedGuidanceAsset = null },
            onSaveBitmap = viewModel::saveEditedGuidanceBitmap,
            onCheckpoint = viewModel::saveGuidanceCheckpoint,
            onSave = { guidance ->
                viewModel.commitImageGuidance(guidance)
                showGuidanceEditor = false
                stagedGuidanceAsset = null
            }
        )
    }

    useAsPath?.let { path ->
        ImageUseAsDialog(
            model = state.draft.selectedModel,
            onDismiss = { useAsPath = null },
            onSelect = { target ->
                useAsPath = null
                viewModel.useImage(path, target)
            }
        )
    }

    pendingRecentApply?.let { mode ->
        CbDialog(
            onDismissRequest = { pendingRecentApply = null },
            title = "无法准确复现",
            confirm = {
                CbButton("仍然应用", {
                    pendingRecentApply = null
                    viewModel.applySelectedRecentHistory(mode)
                })
            },
            dismiss = { CbButton("取消", { pendingRecentApply = null }, variant = ButtonVariant.Ghost) }
        ) {
            CbText("原图使用了图像参考，但历史未保存来源图片。当前操作只能恢复参数或 Seed，无法准确复现原图。")
        }
    }

    previewPath?.let { selectedPath ->
        val items = previewPaths.map { path -> ImagePreviewItem(messageId = "", path = path) }
        ImagePreviewDialog(
            items = items,
            initialIndex = previewPaths.indexOf(selectedPath).coerceAtLeast(0),
            onDismiss = { previewPath = null }
        )
    }

    importedPreviewPath?.let { selectedPath ->
        ImagePreviewDialog(
            items = listOf(ImagePreviewItem(messageId = "", path = selectedPath)),
            initialIndex = 0,
            onDismiss = { importedPreviewPath = null }
        )
    }

    importedEditorPath?.let { sourcePath ->
        ImageMosaicEditor(
            sourcePath = sourcePath,
            onDismiss = { importedEditorPath = null },
            onComplete = { outputPath ->
                importedEditorPath = null
                importedPreviewPath = outputPath
            }
        )
    }

    val importedMetadata = state.imageImport.metadata
    val importedSource = state.imageImport.source
    if (showImageTools && !showMetadataSelection && importedEditorPath == null && importedPreviewPath == null) {
        StudioImageToolsDialog(
            source = importedSource,
            metadata = importedMetadata,
            loading = state.imageImport.loading,
            toolBusy = state.imageImport.toolBusy,
            busy = state.isBusy,
            toolResultPath = state.imageImport.toolResult?.path,
            designStatus = state.designStatus,
            isDesigning = state.isDesigning,
            onDismiss = { showImageTools = false },
            onPickImage = { imagePicker.launch(arrayOf("image/*")) },
            onRemoveImage = viewModel::clearImportedImage,
            onParseMetadata = { showMetadataSelection = true },
            onMosaic = { importedSource?.let { importedEditorPath = it.path } },
            onRestorePatch = viewModel::restoreImportedPatch,
            onReversePrompt = viewModel::reverseImportedPrompt,
            onOpenResult = { path -> importedPreviewPath = path }
        )
    }

    if (importedMetadata != null && importedSource != null && showMetadataSelection) {
        ImportedMetadataSelectionDialog(
            metadata = importedMetadata,
            onDismiss = { showMetadataSelection = false },
            onConfirm = { selection ->
                showMetadataSelection = false
                viewModel.applyImportedMetadata(selection)
            }
        )
    }

    state.error?.let { error ->
        CbDialog(
            onDismissRequest = viewModel::dismissError,
            title = "操作失败",
            confirm = { CbButton("知道了", viewModel::dismissError) }
        ) { CbText(error) }
    }
}

@Composable
private fun StudioImageToolsDialog(
    source: com.example.chatbar.domain.image.ImportedProcessImage?,
    metadata: NovelAiStudioPngMetadata?,
    loading: Boolean,
    toolBusy: Boolean,
    busy: Boolean,
    toolResultPath: String?,
    designStatus: String,
    isDesigning: Boolean,
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onParseMetadata: () -> Unit,
    onMosaic: () -> Unit,
    onRestorePatch: () -> Unit,
    onReversePrompt: () -> Unit,
    onOpenResult: (String) -> Unit
) {
    CbDialog(
        onDismissRequest = onDismiss,
        title = "图片工具",
        dismiss = { CbButton("关闭", onDismiss, variant = ButtonVariant.Ghost) }
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())
        ) {
        CbButton(
            "更换图片文件",
            onPickImage,
            Modifier.fillMaxWidth(),
            enabled = !busy,
            variant = ButtonVariant.Outline
        )
        if (loading) {
            Spacer(Modifier.height(ChatBarSpacing.sm))
            CbText("正在读取图片并检查 NovelAI 元数据…", color = ChatBarTheme.colors.mutedForeground)
        }
        source?.let { imported ->
            Spacer(Modifier.height(ChatBarSpacing.sm))
            AsyncImage(
                model = imported.path,
                contentDescription = "导入图片预览",
                modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(ChatBarShape.md)),
                contentScale = ContentScale.Fit
            )
            CbText(
                "${imported.width}×${imported.height} · ${imported.displayName}",
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
            Spacer(Modifier.height(ChatBarSpacing.sm))
            CbText("工具栏", style = ChatBarTheme.typography.label)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                NovelAiImageAction(
                    AppIcons.Article,
                    "元数据",
                    if (metadata == null) "未检测到 NovelAI 元数据" else "提取 NovelAI 元数据",
                    !busy && metadata != null,
                    Modifier.weight(1f),
                    onParseMetadata
                )
                NovelAiImageAction(AppIcons.Edit, "打码", "打开打码工具", !busy, Modifier.weight(1f), onMosaic)
                NovelAiImageAction(AppIcons.Restore, "去贴片", "逆向还原 AI 贴片", !busy, Modifier.weight(1f), onRestorePatch)
                NovelAiImageAction(AppIcons.Search, "反推 Prompt", "使用多模态 AI 反推提示词", !busy, Modifier.weight(1f), onReversePrompt)
            }
            if (toolBusy || isDesigning) {
                Spacer(Modifier.height(ChatBarSpacing.sm))
                CbText(
                    if (toolBusy) "正在还原贴片…" else designStatus.ifBlank { "正在反推提示词…" },
                    color = ChatBarTheme.colors.primary,
                    style = ChatBarTheme.typography.caption
                )
            } else if (designStatus == "反推完成") {
                Spacer(Modifier.height(ChatBarSpacing.sm))
                CbText("反推完成；基础与角色正向 Prompt 已更新，可在工作室撤销还原。", color = ChatBarTheme.colors.primary, style = ChatBarTheme.typography.caption)
            }
            toolResultPath?.let { path ->
                Spacer(Modifier.height(ChatBarSpacing.sm))
                CbButton("查看还原结果", { onOpenResult(path) }, Modifier.fillMaxWidth(), variant = ButtonVariant.Secondary)
            }
            CbButton("移除当前图片", onRemoveImage, Modifier.fillMaxWidth(), variant = ButtonVariant.Ghost, enabled = !busy)
        }
        }
    }
}

@Composable
private fun ImageUseAsDialog(
    model: NovelAiImageModel,
    onDismiss: () -> Unit,
    onSelect: (NovelAiImageUseTarget) -> Unit
) {
    val targets = NovelAiImageUseTarget.entries.filter {
        model == NovelAiImageModel.V4_5_FULL || it in setOf(
            NovelAiImageUseTarget.IMAGE_TO_IMAGE,
            NovelAiImageUseTarget.INPAINT
        )
    }
    CbDialog(
        onDismissRequest = onDismiss,
        title = "用作图像引导",
        dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) }
    ) {
        targets.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                row.forEach { target ->
                    CbButton(
                        target.displayName,
                        { onSelect(target) },
                        Modifier.weight(1f),
                        variant = ButtonVariant.Outline
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(ChatBarSpacing.xs))
        }
    }
}

@Composable
private fun ImportedMetadataSelectionDialog(
    metadata: NovelAiStudioPngMetadata,
    onDismiss: () -> Unit,
    onConfirm: (NovelAiStudioMetadataSelection) -> Unit
) {
    var selection by remember(metadata.imagePath) { mutableStateOf(NovelAiStudioMetadataSelection()) }
    val negativeAvailable = metadata.negativePrompt != null
    val settingsAvailable = metadata.settings.hasAny
    val seedAvailable = metadata.seed != null
    val guidanceAvailable = metadata.imageGuidance.hasAny
    CbDialog(
        onDismissRequest = onDismiss,
        title = "解析 NovelAI 元数据",
        dismiss = { CbButton("返回", onDismiss, variant = ButtonVariant.Ghost) },
        confirm = { CbButton("确认解析", { onConfirm(selection) }) }
    ) {
        CbText(
            "仅开启项目会覆盖工作室对应内容；画风 Prompt 与自然语言模式不变。",
            color = ChatBarTheme.colors.mutedForeground,
            style = ChatBarTheme.typography.caption
        )
        Spacer(Modifier.height(ChatBarSpacing.sm))
        MetadataToggleRow("正向 Prompt", selection.positivePrompt, true) {
            selection = selection.copy(positivePrompt = it)
        }
        MetadataToggleRow("逆向 Prompt（基础负面）", selection.negativePrompt, negativeAvailable) {
            selection = selection.copy(negativePrompt = it)
        }
        MetadataToggleRow(
            "角色 Prompt（正向与负面）· ${metadata.characters.size} 个",
            selection.characterPrompts,
            metadata.hasCharacterPrompts
        ) {
            selection = selection.copy(characterPrompts = it)
        }
        MetadataToggleRow("生成设置", selection.generationSettings, settingsAvailable) {
            selection = selection.copy(generationSettings = it)
        }
        MetadataToggleRow("图像引导", selection.imageGuidance, guidanceAvailable) {
            selection = selection.copy(imageGuidance = it)
        }
        MetadataToggleRow(
            metadata.seed?.let { "种子 · $it" } ?: "种子",
            selection.seed,
            seedAvailable
        ) {
            selection = selection.copy(seed = it)
        }
    }
}

@Composable
private fun MetadataToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        CbText(
            label,
            Modifier.weight(1f),
            color = if (enabled) ChatBarTheme.colors.foreground else ChatBarTheme.colors.mutedForeground
        )
        CbSwitch(checked = checked && enabled, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun PromptTokenBudget(state: NovelAiPromptTokenState, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(
            start = ChatBarSpacing.md,
            top = ChatBarSpacing.xs,
            end = ChatBarSpacing.md
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (state.error != null) {
            CbText(
                state.error,
                color = ChatBarTheme.colors.destructive,
                style = ChatBarTheme.typography.caption
            )
        } else {
            PromptTokenBudgetRow("+", state.positive, state.limit, state.loading)
            PromptTokenBudgetRow("-", state.negative, state.limit, state.loading)
        }
    }
}

@Composable
private fun PromptTokenBudgetRow(symbol: String, count: Int?, limit: Int, loading: Boolean) {
    val progress = ((count ?: 0).toFloat() / limit.coerceAtLeast(1)).coerceIn(0f, 1f)
    val indicatorColor = when {
        count != null && count > limit -> ChatBarTheme.colors.destructive
        count != null && count >= limit * 0.85f -> ChatBarTheme.colors.warning
        else -> ChatBarTheme.colors.primary
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CbText(symbol, Modifier.width(14.dp), style = ChatBarTheme.typography.caption)
        Box(
            Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(ChatBarShape.full))
                .background(ChatBarTheme.colors.muted)
        ) {
            Box(
                Modifier.fillMaxWidth(progress).height(4.dp)
                    .background(indicatorColor)
            )
        }
        Spacer(Modifier.width(ChatBarSpacing.sm))
        CbText(
            when {
                count == null -> "…/$limit"
                loading -> "$count/$limit…"
                else -> "$count/$limit"
            },
            color = if (count != null && count > limit) {
                ChatBarTheme.colors.destructive
            } else {
                ChatBarTheme.colors.mutedForeground
            },
            style = ChatBarTheme.typography.caption
        )
    }
}

@Composable
private fun StudioFullscreenPromptEditor(
    request: StudioFullscreenEditRequest,
    currentEditorRevision: Int,
    translationEnabled: Boolean,
    annotations: List<NovelAiPromptAnnotation>,
    suggestions: NovelAiTagSuggestionState,
    viewModel: ImagePromptToolViewModel,
    onDismiss: () -> Unit,
    onStaleSession: () -> Unit
) {
    val field = request.field ?: return
    val editorState = rememberFullscreenTextEditorState(request.value)
    var lastTranslationText by remember { mutableStateOf<String?>(null) }
    var suppressSuggestionFor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val wrappingTransformation = promptTagWrappingOutputTransformation(request.naturalLanguage)
    val editorTextStyle = promptEditorTextStyle(translationEnabled)
    val visibleAnnotations = annotations.filter { annotation ->
        annotation.matches(editorState.value.text)
    }

    FullscreenTextEditor(
        state = editorState,
        title = request.title,
        visible = true,
        onDismiss = onDismiss,
        onConfirm = { value ->
            if (!isStudioFullscreenPromptSessionCurrent(
                    request.editorRevision,
                    currentEditorRevision
                )
            ) {
                onStaleSession()
            } else {
                request.onApply(value.copy(composition = null))
                onDismiss()
            }
        },
        confirmEnabled = isStudioFullscreenPromptSessionCurrent(
            request.editorRevision,
            currentEditorRevision
        ),
        outputTransformation = wrappingTransformation,
        textStyle = editorTextStyle,
        textOverlay = { layout, scrollOffset, rawText ->
            PromptTranslationOverlay(
                layout = layout,
                annotations = visibleAnnotations.filter { it.matches(rawText) },
                scrollOffsetPx = scrollOffset
            )
        },
        onDraftValueChange = { value ->
            if (lastTranslationText != value.text) {
                lastTranslationText = value.text
                if (translationEnabled) {
                    viewModel.requestFullscreenPromptAnnotations(
                        field = field,
                        text = value.text,
                        naturalLanguage = request.naturalLanguage
                    )
                }
            }
            val suggestionKey = value.text to value.selection.end
            if (suppressSuggestionFor == suggestionKey) {
                suppressSuggestionFor = null
            } else {
                viewModel.requestTagSuggestions(field, value.text, value.selection.end)
            }
        },
        topContent = {
            FullscreenTagSuggestionBar(
                suggestions = suggestions,
                onInsertTag = { candidate ->
                    val current = editorState.value
                    val inserted = NovelAiTagCompletion.insert(
                        current.text,
                        current.selection.end,
                        candidate
                    )
                    val next = TextFieldValue(
                        text = inserted.text,
                        selection = TextRange(inserted.cursor)
                    )
                    suppressSuggestionFor = next.text to next.selection.end
                    editorState.replace(next)
                    viewModel.clearTagSuggestions()
                }
            )
        }
    )
}

@Composable
private fun FullscreenTagSuggestionBar(
    suggestions: NovelAiTagSuggestionState,
    onInsertTag: (String) -> Unit
) {
    CbSurface(
        modifier = Modifier.fillMaxWidth().height(48.dp),
        color = ChatBarTheme.colors.card,
        shape = RoundedCornerShape(ChatBarShape.lg),
        border = BorderStroke(1.dp, ChatBarTheme.colors.border),
        elevation = ChatBarElevation.xhigh
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = ChatBarSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TagSuggestionContent(
                suggestions = suggestions,
                modifier = Modifier.weight(1f),
                onInsertTag = onInsertTag
            )
        }
    }
}

@Composable
private fun TagSuggestionContent(
    suggestions: NovelAiTagSuggestionState,
    modifier: Modifier = Modifier,
    onInsertTag: (String) -> Unit
) {
    when {
        suggestions.loading -> CbText(
            "预测中…",
            modifier,
            color = ChatBarTheme.colors.mutedForeground,
            style = ChatBarTheme.typography.caption
        )
        suggestions.error != null -> CbText(
            suggestions.error,
            modifier,
            color = ChatBarTheme.colors.destructive,
            style = ChatBarTheme.typography.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        suggestions.candidates.isEmpty() -> CbText(
            "输入 Tag 获取预测",
            modifier,
            color = ChatBarTheme.colors.mutedForeground,
            style = ChatBarTheme.typography.caption
        )
        else -> LazyRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)
        ) {
            items(
                suggestions.candidates,
                key = { candidate -> candidate.name }
            ) { candidate ->
                CbButton(
                    text = buildString {
                        append(candidate.name)
                        if (candidate.translatedName.isNotBlank()) {
                            append(" · ${candidate.translatedName}")
                        }
                    },
                    onClick = { onInsertTag(candidate.name) },
                    size = ButtonSize.Xs,
                    variant = ButtonVariant.Outline
                )
            }
        }
    }
}

@Composable
private fun OutputPanel(
    state: ImagePromptToolUiState,
    onToggle: () -> Unit,
    onSelect: (Int) -> Unit,
    onSelectRecent: (String) -> Unit,
    onApplyRecent: (NovelAiHistoryApplyMode) -> Unit,
    expanded: Boolean,
    expandedMaxHeight: Dp,
    onOpenImage: (String) -> Unit,
    onUseAs: (String) -> Unit,
    compactTagSuggestions: NovelAiTagSuggestionState?,
    onInsertTag: (String) -> Unit
) {
    val selectedPath = state.selectedOutputPath
    val selectedModel: Any? = selectedPath ?: state.imagePreview
    val selectedRecent = state.selectedRecentHistoryItem
    CbSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ChatBarSpacing.md,
                vertical = if (compactTagSuggestions != null) ChatBarSpacing.xs else ChatBarSpacing.md
            )
            .then(if (expanded) Modifier.height(expandedMaxHeight) else Modifier),
        color = ChatBarTheme.colors.card,
        shape = RoundedCornerShape(ChatBarShape.xl),
        border = BorderStroke(1.dp, ChatBarTheme.colors.border),
        elevation = ChatBarElevation.xhigh
    ) {
        Column(
            modifier = (if (expanded) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .padding(if (compactTagSuggestions != null) ChatBarSpacing.sm else ChatBarSpacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
            ) {
                selectedModel?.takeIf { !expanded }?.let { model ->
                    AsyncImage(
                        model = model,
                        contentDescription = "当前输出缩略图",
                        modifier = Modifier
                            .size(if (compactTagSuggestions != null) 40.dp else 48.dp)
                            .clip(RoundedCornerShape(ChatBarShape.sm))
                            .clickable(onClick = onToggle),
                        contentScale = ContentScale.Crop
                    )
                }
                if (compactTagSuggestions != null) {
                    TagSuggestionContent(
                        suggestions = compactTagSuggestions,
                        modifier = Modifier.weight(1f),
                        onInsertTag = onInsertTag
                    )
                } else {
                    if (expanded || selectedRecent == null) {
                        Row(
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClick = onToggle),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CbText(
                                text = when (state.phase) {
                                    ImagePromptToolPhase.GENERATING -> "正在连接 NovelAI"
                                    ImagePromptToolPhase.STREAMING -> "生成中 ${(state.imageProgress * 100).toInt()}%"
                                    ImagePromptToolPhase.SAVING -> "正在保存批次"
                                    ImagePromptToolPhase.CANCELLING -> "正在停止"
                                    ImagePromptToolPhase.FINISHED -> "已完成 ${state.imagePaths.size} 张"
                                    ImagePromptToolPhase.FAILED -> "生成失败"
                                    else -> "输出"
                                },
                                modifier = Modifier.weight(1f),
                                style = ChatBarTheme.typography.label
                            )
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    selectedRecent?.let {
                        Row(Modifier.width(112.dp)) {
                            NovelAiImageAction(
                                AppIcons.Tune,
                                "复用",
                                "复用设置并使用新 Seed",
                                !state.applyingHistory && !state.isBusy,
                                Modifier.weight(1f)
                            ) { onApplyRecent(NovelAiHistoryApplyMode.NEW_SEED) }
                            NovelAiImageAction(
                                AppIcons.Seed,
                                "Seed",
                                "仅复用 Seed",
                                !state.applyingHistory && !state.isBusy,
                                Modifier.weight(1f)
                            ) { onApplyRecent(NovelAiHistoryApplyMode.SEED_ONLY) }
                        }
                    }
                    selectedPath?.let { path ->
                        NovelAiImageAction(
                            AppIcons.AddPhotoAlternate,
                            "用作",
                            "用作图像引导",
                            !state.applyingHistory && !state.isBusy
                        ) { onUseAs(path) }
                    }
                    CbIconButton(
                        if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                        if (expanded) "折叠输出" else "展开输出",
                        onToggle
                    )
                }
            }
            if (expanded) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
                ) {
                    item {
                        if (selectedModel == null) {
                            Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                                CbText("生成结果会显示在这里", color = ChatBarTheme.colors.mutedForeground)
                            }
                        } else {
                            AsyncImage(
                                model = selectedModel,
                                contentDescription = "当前生成结果",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(ChatBarShape.md))
                                    .clickable(enabled = selectedPath != null) {
                                        selectedPath?.let(onOpenImage)
                                    },
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                    val showStreamingPreviews = state.isBusy && state.completedPreviews.isNotEmpty()
                    if (showStreamingPreviews || state.recentHistoryItems.isNotEmpty()) {
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                                if (showStreamingPreviews) {
                                    itemsIndexed(state.completedPreviews) { index, preview ->
                                        OutputThumbnail(
                                            model = preview,
                                            contentDescription = "本批第 ${index + 1} 张",
                                            selected = selectedPath == state.imagePaths.getOrNull(index),
                                            onClick = { onSelect(index) }
                                        )
                                    }
                                } else {
                                    itemsIndexed(
                                        state.recentHistoryItems,
                                        key = { _, item -> item.image.path }
                                    ) { index, item ->
                                        OutputThumbnail(
                                            model = item.image.path,
                                            contentDescription = "近期图片 ${index + 1}",
                                            selected = selectedPath == item.image.path,
                                            onClick = { onSelectRecent(item.image.path) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (selectedPath != null) {
                        item {
                            CbText(
                                "点击大图查看；全屏长按可打码、保存或分享",
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

@Composable
private fun OutputThumbnail(
    model: Any,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    CbSurface(
        modifier = Modifier.size(68.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(ChatBarShape.sm),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) ChatBarTheme.colors.primary else ChatBarTheme.colors.border
        )
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
internal fun ImagePreviewPanel(
    state: ImagePromptToolUiState,
    onOpenImage: (Int) -> Unit
) {
    val index = state.selectedOutputIndex.coerceIn(0, state.imagePaths.lastIndex.coerceAtLeast(0))
    val path = state.imagePaths.getOrNull(index) ?: return
    AsyncImage(
        model = path,
        contentDescription = "NovelAI 生图结果",
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp, max = 420.dp)
            .clickable { onOpenImage(index) },
        contentScale = ContentScale.Fit
    )
}

@Composable
private fun PromptSection(
    state: ImagePromptToolUiState,
    viewModel: ImagePromptToolViewModel,
    onFullscreenEdit: (String, TextFieldValue, NovelAiPromptFieldKey?, Boolean, (TextFieldValue) -> Unit) -> Unit,
    onTagEditTarget: (StudioTagEditTarget) -> Unit,
    onTagEditEnd: (NovelAiPromptFieldKey) -> Unit
) {
    val draft = state.draft
    Column(
        Modifier.fillMaxWidth().padding(horizontal = ChatBarSpacing.md),
        verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.md)
    ) {
        SectionCard("角色卡") {
            CharacterCardImport(state, viewModel)
        }
        val translationEnabled =
            state.promptTranslationConsent == NovelAiPromptTranslationConsent.ENABLED
        SectionCard(
            title = "Prompt",
            titleAction = {
                CbIconButton(
                    imageVector = AppIcons.Translate,
                    contentDescription = if (translationEnabled) {
                        "关闭 Prompt 中文翻译"
                    } else {
                        "开启 Prompt 中文翻译"
                    },
                    onClick = { viewModel.setPromptTranslationEnabled(!translationEnabled) },
                    modifier = Modifier.size(32.dp),
                    tint = if (translationEnabled) {
                        ChatBarTheme.colors.primary
                    } else {
                        ChatBarTheme.colors.mutedForeground
                    }
                )
            }
        ) {
            val styleField = NovelAiPromptFieldKey("style")
            TagPromptInput(
                label = "画风 Prompt",
                value = draft.stylePrompt,
                field = styleField,
                editorRevision = state.promptEditorRevision,
                annotations = state.promptAnnotations[styleField].orEmpty(),
                translationEnabled = translationEnabled,
                minLines = 3,
                editorHeight = 104.dp,
                onValueChange = { value ->
                    viewModel.updatePromptDraft(state.promptEditorRevision, "prompt:style") { draft ->
                        draft.copy(stylePrompt = value)
                    }
                },
                onSuggest = viewModel::requestTagSuggestions,
                onTagEditTarget = onTagEditTarget,
                onTagEditEnd = onTagEditEnd,
                onFullscreenEdit = onFullscreenEdit
            )
            val baseField = NovelAiPromptFieldKey("base")
            TagPromptInput(
                label = "基础 Prompt",
                value = draft.basePrompt,
                field = baseField,
                editorRevision = state.promptEditorRevision,
                annotations = state.promptAnnotations[baseField].orEmpty(),
                translationEnabled = translationEnabled,
                naturalLanguage = false,
                minLines = 3,
                editorHeight = 150.dp,
                onValueChange = { value ->
                    viewModel.updatePromptDraft(state.promptEditorRevision, "prompt:base") { draft ->
                        draft.copy(basePrompt = value)
                    }
                },
                onSuggest = viewModel::requestTagSuggestions,
                onTagEditTarget = onTagEditTarget,
                onTagEditEnd = onTagEditEnd,
                onFullscreenEdit = onFullscreenEdit
            )
            if (draft.selectedModel == NovelAiImageModel.V5_FULL) {
                CbText(
                    "V5：正向 Prompt 中直接写引号内容会自动生成 Text: 块；手写 Text: 后自动功能停用",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
            draft.characters.forEachIndexed { index, character ->
                CharacterPromptEditor(
                    index,
                    character,
                    state,
                    viewModel,
                    onFullscreenEdit,
                    onTagEditTarget,
                    onTagEditEnd
                )
            }
            CbButton("添加角色 Prompt", viewModel::addCharacter, variant = ButtonVariant.Outline)
            CollapsibleHeader(
                title = "基础负面 Prompt",
                summary = if (draft.negativeExpanded) "收起" else "已设置",
                expanded = draft.negativeExpanded,
                onClick = { viewModel.updateDraft { it.copy(negativeExpanded = !it.negativeExpanded) } }
            )
            if (draft.negativeExpanded) {
                val negativeField = NovelAiPromptFieldKey("negative")
                TagPromptInput(
                    label = "基础负面 Prompt",
                    value = draft.negativePrompt,
                    field = negativeField,
                    editorRevision = state.promptEditorRevision,
                    annotations = state.promptAnnotations[negativeField].orEmpty(),
                    translationEnabled = translationEnabled,
                    minLines = 3,
                    onValueChange = { value ->
                        viewModel.updatePromptDraft(state.promptEditorRevision, "prompt:negative") { draft ->
                            draft.copy(negativePrompt = value)
                        }
                    },
                    onSuggest = viewModel::requestTagSuggestions,
                    onTagEditTarget = onTagEditTarget,
                    onTagEditEnd = onTagEditEnd,
                    onFullscreenEdit = onFullscreenEdit
                )
            }
        }
    }
}

@Composable
private fun CharacterCardImport(state: ImagePromptToolUiState, viewModel: ImagePromptToolViewModel) {
    if (state.characterCards.isEmpty()) {
        CbText("暂无可选角色卡", color = ChatBarTheme.colors.mutedForeground)
        return
    }
    CbField("导入角色卡 Prompt", description = "填充画风；角色 Prompt 仅供 AI 设计参考，不参与实际生图") {
        CbSelect(
            value = state.characterCards.firstOrNull { it.id == state.selectedCharacterCardId },
            options = state.characterCards,
            optionLabel = { it.name },
            onValueChange = { viewModel.importCharacterCardPrompts(it.id) },
            placeholder = "选择角色卡",
            enabled = state.canImportCharacterCard
        )
    }
}

@Composable
private fun CharacterPromptEditor(
    index: Int,
    character: NovelAiCharacterPromptDraft,
    state: ImagePromptToolUiState,
    viewModel: ImagePromptToolViewModel,
    onFullscreenEdit: (String, TextFieldValue, NovelAiPromptFieldKey?, Boolean, (TextFieldValue) -> Unit) -> Unit,
    onTagEditTarget: (StudioTagEditTarget) -> Unit,
    onTagEditEnd: (NovelAiPromptFieldKey) -> Unit
) {
    CbSurface(
        Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.surfaceSubtle,
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(Modifier.fillMaxWidth().padding(ChatBarSpacing.md), verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CbText("角色 ${index + 1}", Modifier.weight(1f), style = ChatBarTheme.typography.label)
                CbButton("上移", { viewModel.moveCharacter(character.id, -1) }, enabled = index > 0, variant = ButtonVariant.Ghost, size = ButtonSize.Xs)
                CbButton("下移", { viewModel.moveCharacter(character.id, 1) }, enabled = index < state.draft.characters.lastIndex, variant = ButtonVariant.Ghost, size = ButtonSize.Xs)
                CbIconButton(AppIcons.Delete, "删除角色", { viewModel.removeCharacter(character.id) }, tint = ChatBarTheme.colors.destructive)
            }
            val characterField = NovelAiPromptFieldKey("character", character.id)
            TagPromptInput(
                label = "角色正向",
                value = character.prompt,
                field = characterField,
                editorRevision = state.promptEditorRevision,
                annotations = state.promptAnnotations[characterField].orEmpty(),
                translationEnabled = state.promptTranslationConsent == NovelAiPromptTranslationConsent.ENABLED,
                minLines = 2,
                editorHeight = 104.dp,
                onValueChange = { value ->
                    viewModel.updateCharacterPrompt(
                        character.id,
                        state.promptEditorRevision,
                        "prompt:character:${character.id}"
                    ) { it.copy(prompt = value) }
                },
                onSuggest = viewModel::requestTagSuggestions,
                onTagEditTarget = onTagEditTarget,
                onTagEditEnd = onTagEditEnd,
                onFullscreenEdit = onFullscreenEdit
            )
            CollapsibleHeader(
                title = "角色负面",
                summary = if (character.negativeExpanded) "收起" else if (character.negativePrompt.isBlank()) "空" else "已设置",
                expanded = character.negativeExpanded,
                onClick = { viewModel.updateCharacter(character.id) { it.copy(negativeExpanded = !it.negativeExpanded) } }
            )
            if (character.negativeExpanded) {
                val negativeField = NovelAiPromptFieldKey("character_negative", character.id)
                TagPromptInput(
                    label = "角色负面",
                    value = character.negativePrompt,
                    field = negativeField,
                    editorRevision = state.promptEditorRevision,
                    annotations = state.promptAnnotations[negativeField].orEmpty(),
                    translationEnabled = state.promptTranslationConsent == NovelAiPromptTranslationConsent.ENABLED,
                    minLines = 2,
                    editorHeight = 104.dp,
                    onValueChange = { value ->
                        viewModel.updateCharacterPrompt(
                            character.id,
                            state.promptEditorRevision,
                            "prompt:character_negative:${character.id}"
                        ) { it.copy(negativePrompt = value) }
                    },
                    onSuggest = viewModel::requestTagSuggestions,
                    onTagEditTarget = onTagEditTarget,
                    onTagEditEnd = onTagEditEnd,
                    onFullscreenEdit = onFullscreenEdit
                )
            }
        }
    }
}

@Composable
private fun GenerationSettingsSection(
    settings: NovelAiGenerationSettings,
    advancedExpanded: Boolean,
    guidanceSummary: String,
    viewModel: ImagePromptToolViewModel
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = ChatBarSpacing.md)) {
        SectionCard("生成设置") {
            CbField("模型") {
                CbSelect(settings.model, NovelAiImageModel.entries, { it.displayName }, viewModel::selectImageModel)
            }
            if (guidanceSummary.isNotBlank()) {
                CbText(
                    "图像引导已启用 · $guidanceSummary",
                    color = ChatBarTheme.colors.primary,
                    style = ChatBarTheme.typography.caption
                )
            }
            CbField("尺寸档") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                    NovelAiSizeTier.entries.forEach { tier ->
                        CbChoiceChip(tier.displayName, settings.sizeTier == tier, onClick = {
                            viewModel.updateGenerationSettings { it.copy(sizeTier = tier) }
                        })
                    }
                }
            }
            CbField("比例 · ${settings.imageSize().width}×${settings.imageSize().height}") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                    NovelAiAspectRatio.entries.filterNot { settings.sizeTier == NovelAiSizeTier.WALLPAPER && it == NovelAiAspectRatio.SQUARE }.forEach { ratio ->
                        CbChoiceChip(ratio.displayName, settings.aspectRatio == ratio, onClick = {
                            viewModel.updateGenerationSettings { it.copy(aspectRatio = ratio) }
                        })
                    }
                }
            }
            CbField("数量") {
                Row(horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                    (1..4).forEach { count ->
                        CbChoiceChip(count.toString(), settings.count == count, { viewModel.updateGenerationSettings { it.copy(count = count) } }, Modifier.weight(1f))
                    }
                }
            }
            CollapsibleHeader(
                title = "",
                summary = "${settings.steps} Steps · CFG ${"%.1f".format(settings.guidance)} / ${"%.2f".format(settings.cfgRescale)} · ${settings.sampler.displayName} · ${if (settings.seedMode == NovelAiSeedMode.RANDOM) "随机 Seed" else settings.seed}",
                expanded = advancedExpanded,
                onClick = { viewModel.updateDraft { it.copy(advancedExpanded = !it.advancedExpanded) } }
            )
            if (advancedExpanded) AdvancedSettings(settings, viewModel)
        }
    }
}

@Composable
private fun AdvancedSettings(settings: NovelAiGenerationSettings, viewModel: ImagePromptToolViewModel) {
    CbField("Steps · ${settings.steps}") {
        CbSlider(settings.steps.toFloat(), { value -> viewModel.updateGenerationSettings("settings:steps") { it.copy(steps = value.toInt()) } }, 1f..50f, steps = 48)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.md)) {
        CbField("CFG Scale · ${"%.1f".format(settings.guidance)}", Modifier.weight(1f)) {
            CbSlider(
                settings.guidance,
                { value -> viewModel.updateGenerationSettings("settings:guidance") { it.copy(guidance = (value * 10).toInt() / 10f) } },
                1f..10f,
                steps = 89,
                contentDescription = "CFG Scale"
            )
        }
        CbField("CFG Rescale · ${"%.2f".format(settings.cfgRescale)}", Modifier.weight(1f)) {
            CbSlider(
                settings.cfgRescale,
                { value -> viewModel.updateGenerationSettings("settings:cfg_rescale") { it.copy(cfgRescale = kotlin.math.round(value * 20f) / 20f) } },
                0f..1f,
                steps = 19,
                contentDescription = "CFG Rescale"
            )
        }
    }
    CbField("Sampler") {
        CbSelect(settings.sampler, NovelAiSampler.entries, { it.displayName }, { sampler -> viewModel.updateGenerationSettings { it.copy(sampler = sampler) } })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CbText("随机 Seed", Modifier.weight(1f))
        CbSwitch(settings.seedMode == NovelAiSeedMode.RANDOM, { random ->
            viewModel.updateGenerationSettings { it.copy(seedMode = if (random) NovelAiSeedMode.RANDOM else NovelAiSeedMode.FIXED) }
        })
    }
    if (settings.seedMode == NovelAiSeedMode.FIXED) {
        CbField("Seed", description = "0–${settings.maxAllowedBaseSeed}") {
            CbInput(
                value = settings.seed.toString(),
                onValueChange = { text ->
                    viewModel.updateGenerationSettings("settings:seed") { it.copy(seed = text.toLong()) }
                },
                singleLine = true,
                inputTransformation = InputTransformation.byValue { current, proposed ->
                    proposed.takeIf { value ->
                        value.isNotEmpty() && value.all(Char::isDigit) && value.toString().toLongOrNull() != null
                    } ?: current
                }
            )
        }
    }
}

@Composable
private fun promptEditorTextStyle(translationEnabled: Boolean): TextStyle =
    if (translationEnabled) {
        ChatBarTheme.typography.body.copy(
            lineHeight = 25.sp,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Top,
                trim = LineHeightStyle.Trim.None
            ),
            lineBreak = LineBreak.Paragraph
        )
    } else {
        ChatBarTheme.typography.body.copy(lineBreak = LineBreak.Paragraph)
    }

@Composable
private fun TagPromptInput(
    label: String,
    value: String,
    field: NovelAiPromptFieldKey,
    editorRevision: Int,
    annotations: List<NovelAiPromptAnnotation>,
    translationEnabled: Boolean,
    naturalLanguage: Boolean = false,
    minLines: Int = 1,
    editorHeight: Dp = 150.dp,
    onValueChange: (String) -> Unit,
    onSuggest: (NovelAiPromptFieldKey, String, Int) -> Unit,
    onTagEditTarget: (StudioTagEditTarget) -> Unit,
    onTagEditEnd: (NovelAiPromptFieldKey) -> Unit,
    onFullscreenEdit: (String, TextFieldValue, NovelAiPromptFieldKey?, Boolean, (TextFieldValue) -> Unit) -> Unit
) {
    var fieldValue by remember(field, editorRevision) {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }
    var inputGeneration by remember(field, editorRevision) { mutableIntStateOf(0) }
    fun insertTag(tag: String) {
        val inserted = NovelAiTagCompletion.insert(fieldValue.text, fieldValue.selection.end, tag)
        fieldValue = TextFieldValue(inserted.text, TextRange(inserted.cursor))
        onValueChange(inserted.text)
        onTagEditTarget(StudioTagEditTarget(field, ::insertTag))
    }
    fun publishEditTarget() {
        onTagEditTarget(StudioTagEditTarget(field, ::insertTag))
    }
    LaunchedEffect(value, editorRevision) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(value, TextRange(value.length))
            inputGeneration++
        }
    }
    DisposableEffect(field, editorRevision) {
        onDispose { onTagEditEnd(field) }
    }
    val applyFullscreenValue: (TextFieldValue) -> Unit = { next ->
        fieldValue = next.copy(composition = null)
        onValueChange(next.text)
    }
    CbField(
        label,
        modifier = Modifier.fillMaxWidth(),
        onFullscreenEdit = {
            val authoritativeValue = if (fieldValue.text == value) {
                fieldValue
            } else {
                TextFieldValue(value, TextRange(value.length))
            }
            onFullscreenEdit(label, authoritativeValue, field, naturalLanguage, applyFullscreenValue)
        }
    ) {
        Box(Modifier.fillMaxWidth()) {
            val visibleAnnotations = annotations.filter { annotation ->
                annotation.matches(fieldValue.text)
            }
            val wrappingTransformation = promptTagWrappingOutputTransformation(naturalLanguage)
            key(inputGeneration) {
                CbInput(
                    value = fieldValue,
                    onValueChange = { next ->
                        val textChanged = next.text != fieldValue.text
                        fieldValue = next
                        if (textChanged) onValueChange(next.text)
                        onSuggest(field, next.text, next.selection.end)
                        publishEditTarget()
                    },
                    modifier = Modifier.height(editorHeight),
                    singleLine = false,
                    minLines = minLines,
                    expand = true,
                    textStyle = promptEditorTextStyle(translationEnabled),
                    outputTransformation = wrappingTransformation,
                    textOverlay = { layout, scrollOffset ->
                        PromptTranslationOverlay(
                            layout = layout,
                            annotations = visibleAnnotations,
                            scrollOffsetPx = scrollOffset
                        )
                    },
                    onFocusChanged = { isFocused ->
                        if (isFocused) publishEditTarget() else onTagEditEnd(field)
                    }
                )
            }
        }
    }
}

private fun NovelAiPromptAnnotation.matches(text: String): Boolean =
    start >= 0 && end in start..text.length && text.substring(start, end) == source

@Composable
private fun promptTagWrappingOutputTransformation(
    naturalLanguage: Boolean
): OutputTransformation? {
    if (naturalLanguage) return null
    val commaStyle = remember {
        SpanStyle(textGeometricTransform = TextGeometricTransform(scaleX = 0.55f))
    }
    return remember(commaStyle) {
        OutputTransformation {
            val wrapPlan = NovelAiPromptWrapPolicy.plan(toString())
            wrapPlan.nonBreakingSpaceOffsets.forEach { offset ->
                replace(offset, offset + 1, "\u00A0")
            }
            wrapPlan.breakableCommaOffsets.forEach { offset ->
                replace(offset, offset + 1, "\uFF0C")
                addStyle(commaStyle, offset, offset + 1)
            }
        }
    }
}

@Composable
private fun PromptTranslationOverlay(
    layout: TextLayoutResult?,
    annotations: List<NovelAiPromptAnnotation>,
    scrollOffsetPx: Int
) {
    val textMeasurer = rememberTextMeasurer()
    val annotationColor = ChatBarTheme.colors.mutedForeground.copy(alpha = 0.55f)
    val annotationStyle = TextStyle(color = annotationColor, fontSize = 9.sp)
    Canvas(Modifier.fillMaxSize()) {
        val textLayout = layout ?: return@Canvas
        val textLength = textLayout.layoutInput.text.length
        val horizontalGap = 1f
        val verticalGap = 5f
        val annotationHeight = textMeasurer.measure(
            text = AnnotatedString("中"),
            style = annotationStyle,
            maxLines = 1
        ).size.height
        val placements = annotations
            .asSequence()
            .filter { it.start in 0..textLength && it.translation.isNotBlank() }
            .map { annotation ->
                val startOffset = annotation.start.coerceIn(0, textLength)
                val endOffset = annotation.end.coerceIn(startOffset, textLength)
                val startLine = textLayout.getLineForOffset(startOffset)
                val endLine = textLayout.getLineForOffset((endOffset - 1).coerceAtLeast(startOffset))
                PromptAnnotationPlacement(
                    annotation = annotation,
                    slots = (startLine..endLine).map { line ->
                        PromptAnnotationLineSlot(
                            line = line,
                            startX = if (line == startLine) {
                                textLayout.getCursorRect(startOffset).left
                            } else {
                                textLayout.getLineLeft(line)
                            },
                            endX = if (line == endLine && endOffset > startOffset) {
                                textLayout.getBoundingBox(endOffset - 1).right
                            } else {
                                textLayout.getLineRight(line)
                            }
                        )
                    }
                )
            }
            .sortedWith(
                compareBy<PromptAnnotationPlacement> { it.slots.first().line }
                    .thenBy { it.slots.first().startX }
            )
            .toList()

        placements.forEach { placement ->
            var remainingTranslation = placement.annotation.translation
            placement.slots.forEachIndexed { slotIndex, slot ->
                val y = textLayout.getLineBaseline(slot.line) + verticalGap - scrollOffsetPx
                val visible = y < size.height && y + annotationHeight > 0f
                val availableWidth = (slot.endX - slot.startX - horizontalGap)
                    .roundToInt()
                    .coerceAtLeast(1)
                if (remainingTranslation.isEmpty()) {
                    if (visible && slot.endX - slot.startX >= 2f) {
                        val centerY = y + annotationHeight * 0.55f
                        drawLine(
                            color = annotationColor,
                            start = Offset(slot.startX, centerY),
                            end = Offset(slot.endX, centerY),
                            strokeWidth = 1f
                        )
                    }
                    return@forEachIndexed
                }
                val lastSlot = slotIndex == placement.slots.lastIndex
                val measured = textMeasurer.measure(
                    text = AnnotatedString(remainingTranslation),
                    style = annotationStyle,
                    overflow = if (lastSlot) TextOverflow.Ellipsis else TextOverflow.Clip,
                    softWrap = true,
                    maxLines = 1,
                    constraints = Constraints(maxWidth = availableWidth)
                )
                if (visible) {
                    drawText(measured, topLeft = Offset(slot.startX, y))
                }
                val consumed = if (lastSlot) {
                    remainingTranslation.length
                } else {
                    measured.getLineEnd(0, visibleEnd = true)
                        .coerceIn(1, remainingTranslation.length)
                }
                remainingTranslation = remainingTranslation.substring(consumed)
                if (remainingTranslation.isEmpty() && !measured.hasVisualOverflow) {
                    val lineStartX = slot.startX + measured.getLineRight(0) + 1f
                    if (visible && slot.endX - lineStartX >= 2f) {
                        val centerY = y + measured.size.height * 0.55f
                        drawLine(
                            color = annotationColor,
                            start = Offset(lineStartX, centerY),
                            end = Offset(slot.endX, centerY),
                            strokeWidth = 1f
                        )
                    }
                }
            }
        }
    }
}

private data class PromptAnnotationPlacement(
    val annotation: NovelAiPromptAnnotation,
    val slots: List<PromptAnnotationLineSlot>
)

private data class PromptAnnotationLineSlot(
    val line: Int,
    val startX: Float,
    val endX: Float
)

@Composable
private fun StudioMultilineInput(
    label: String,
    value: String,
    minLines: Int,
    onValueChange: (String) -> Unit,
    onFullscreenEdit: (String, TextFieldValue, NovelAiPromptFieldKey?, Boolean, (TextFieldValue) -> Unit) -> Unit
) {
    CbField(label, onFullscreenEdit = {
        onFullscreenEdit(
            label,
            TextFieldValue(value, TextRange(value.length)),
            null,
            false
        ) { onValueChange(it.text) }
    }) {
        CbInput(value, onValueChange, singleLine = false, minLines = minLines)
    }
}

@Composable
private fun SectionCard(
    title: String,
    titleAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    CbSurface(
        Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(Modifier.fillMaxWidth().padding(ChatBarSpacing.md), verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.md)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CbText(title, style = ChatBarTheme.typography.title)
                if (titleAction != null) {
                    Spacer(Modifier.width(ChatBarSpacing.xs))
                    titleAction()
                }
                Spacer(Modifier.weight(1f))
            }
            CbDivider()
            content()
        }
    }
}

@Composable
private fun CollapsibleHeader(title: String, summary: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (title.isNotBlank()) {
            CbText(title, Modifier.weight(1f), style = ChatBarTheme.typography.label, maxLines = 1)
        }
        CbText(
            summary,
            modifier = if (title.isBlank()) Modifier.weight(1f) else Modifier,
            color = ChatBarTheme.colors.mutedForeground,
            style = ChatBarTheme.typography.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(ChatBarSpacing.xs))
        CbIconButton(if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore, null, onClick)
    }
}
