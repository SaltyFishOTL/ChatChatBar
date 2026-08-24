package com.example.chatbar.ui.imageprompt

import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.byValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.chatbar.domain.image.NovelAiAspectRatio
import com.example.chatbar.domain.image.NovelAiCharacterPromptDraft
import com.example.chatbar.domain.image.NovelAiGenerationSettings
import com.example.chatbar.domain.image.NovelAiGenerationChargeKind
import com.example.chatbar.domain.image.NovelAiHistoryApplyMode
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiSampler
import com.example.chatbar.domain.image.NovelAiSeedMode
import com.example.chatbar.domain.image.NovelAiSizeTier
import com.example.chatbar.domain.image.NovelAiTagCompletion
import com.example.chatbar.ui.kit.AppIcons
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
import com.example.chatbar.ui.kit.ChatBarShape
import com.example.chatbar.ui.kit.ChatBarSpacing
import com.example.chatbar.ui.kit.ChatBarElevation
import com.example.chatbar.ui.kit.ChatBarTheme

private data class StudioFullscreenEditRequest(
    val title: String,
    val text: String,
    val onApply: (String) -> Unit
)

@Composable
fun ImagePromptToolScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: ImagePromptToolViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val configured by viewModel.novelAiConfigured.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var fullscreenEdit by remember { mutableStateOf<StudioFullscreenEditRequest?>(null) }
    var previewPath by remember { mutableStateOf<String?>(null) }
    val previewPaths = (state.recentHistoryItems.map { it.image.path } + state.imagePaths).distinct()
    val accountUsage = state.account.usage
    val anlasLabel = accountUsage?.anlas?.toString() ?: if (state.account.loading) "…" else "—"
    val v5Label = accountUsage?.approximateV5Images?.let { "约${it}张" }
        ?: if (state.account.loading) "…" else "—"
    val generationCost = state.generationCost
    BackHandler(enabled = fullscreenEdit == null && previewPath == null) {
        viewModel.persistDraftNow()
        onBack()
    }
    BackHandler(enabled = fullscreenEdit != null) { fullscreenEdit = null }

    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
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
                CbButton("历史", { viewModel.persistDraftNow(); onOpenHistory() }, variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
            }
        )
        OutputPanel(
            state = state,
            onToggle = viewModel::toggleOutputExpanded,
            onSelect = viewModel::selectOutput,
            onSelectRecent = viewModel::selectRecentImage,
            onApplyRecent = viewModel::applySelectedRecentHistory,
            expandedMaxHeight = expandedOutputMaxHeight,
            onOpenImage = { path -> previewPath = path }
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
                PromptSection(state, viewModel) { title, text, onApply ->
                    fullscreenEdit = StudioFullscreenEditRequest(title, text, onApply)
                }
            }
            item {
                GenerationSettingsSection(state.draft.activeSettings, state.draft.advancedExpanded, viewModel)
            }
            item { Spacer(Modifier.height(ChatBarSpacing.sm)) }
        }
        CbSurface(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            shape = RoundedCornerShape(topStart = ChatBarShape.lg, topEnd = ChatBarShape.lg),
            border = BorderStroke(1.dp, ChatBarTheme.colors.border),
            elevation = 4.dp
        ) {
            Column(Modifier.fillMaxWidth()) {
                PromptTokenBudget(state.promptTokens)
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
                            state.isBusy -> "停止当前任务"
                            !configured -> "未配置 Token"
                            generationCost.kind == NovelAiGenerationChargeKind.V5_ALLOWANCE -> "生成免费"
                            generationCost.kind == NovelAiGenerationChargeKind.FREE -> "生成免费"
                            else -> "生成消耗 ${generationCost.anlas} Anlas"
                        },
                        onClick = when {
                            state.applyingHistory -> ({})
                            state.isBusy -> viewModel::cancelActiveTask
                            else -> viewModel::generateImage
                        },
                        modifier = Modifier.weight(0.58f),
                        enabled = !state.applyingHistory && configured && (state.canGenerate || state.isBusy),
                        variant = if (state.isBusy) ButtonVariant.Destructive else ButtonVariant.Default,
                        supportingText = if (
                            !state.applyingHistory &&
                            !state.isBusy &&
                            configured &&
                            generationCost.kind == NovelAiGenerationChargeKind.V5_ALLOWANCE
                        ) {
                            "消耗 V5 额度"
                        } else {
                            null
                        }
                    )
                }
            }
        }
        }
        fullscreenEdit?.let { request ->
            FullscreenTextEditor(
                title = request.title,
                text = request.text,
                onTextChange = request.onApply,
                visible = true,
                onDismiss = { fullscreenEdit = null }
            )
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

    state.error?.let { error ->
        CbDialog(
            onDismissRequest = viewModel::dismissError,
            title = "操作失败",
            confirm = { CbButton("知道了", viewModel::dismissError) }
        ) { CbText(error) }
    }
}

@Composable
private fun PromptTokenBudget(state: NovelAiPromptTokenState) {
    Column(
        Modifier.fillMaxWidth().padding(
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
private fun OutputPanel(
    state: ImagePromptToolUiState,
    onToggle: () -> Unit,
    onSelect: (Int) -> Unit,
    onSelectRecent: (String) -> Unit,
    onApplyRecent: (NovelAiHistoryApplyMode) -> Unit,
    expandedMaxHeight: Dp,
    onOpenImage: (String) -> Unit
) {
    val selectedPath = state.selectedOutputPath
    val selectedModel: Any? = selectedPath ?: state.imagePreview
    val selectedRecent = state.selectedRecentHistoryItem
    val expanded = state.draft.outputExpanded
    CbSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(ChatBarSpacing.md)
            .then(if (expanded) Modifier.height(expandedMaxHeight) else Modifier),
        color = ChatBarTheme.colors.card,
        shape = RoundedCornerShape(ChatBarShape.xl),
        border = BorderStroke(1.dp, ChatBarTheme.colors.border),
        elevation = ChatBarElevation.xhigh
    ) {
        Column(
            modifier = (if (expanded) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .padding(ChatBarSpacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).clickable(onClick = onToggle),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CbText(
                        text = when (state.phase) {
                            ImagePromptToolPhase.GENERATING -> "正在连接 NovelAI"
                            ImagePromptToolPhase.STREAMING -> "生成中 ${(state.imageProgress * 100).toInt()}%"
                            ImagePromptToolPhase.SAVING -> "正在保存批次"
                            ImagePromptToolPhase.FINISHED -> "已完成 ${state.imagePaths.size} 张"
                            ImagePromptToolPhase.FAILED -> "生成失败"
                            else -> "输出"
                        },
                        modifier = Modifier.weight(1f),
                        style = ChatBarTheme.typography.label
                    )
                    if (!expanded && selectedModel != null) {
                        AsyncImage(
                            model = selectedModel,
                            contentDescription = "当前输出缩略图",
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(ChatBarShape.sm)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(ChatBarSpacing.sm))
                    }
                }
                selectedRecent?.let {
                    CbButton(
                        "应用设置",
                        { onApplyRecent(NovelAiHistoryApplyMode.NEW_SEED) },
                        enabled = !state.applyingHistory && !state.isBusy,
                        variant = ButtonVariant.Outline,
                        size = ButtonSize.Xs
                    )
                    Spacer(Modifier.width(ChatBarSpacing.xs))
                    CbButton(
                        "应用 Seed",
                        { onApplyRecent(NovelAiHistoryApplyMode.SEED_ONLY) },
                        enabled = !state.applyingHistory && !state.isBusy,
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Xs
                    )
                    Spacer(Modifier.width(ChatBarSpacing.xs))
                }
                CbIconButton(
                    if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                    if (expanded) "折叠输出" else "展开输出",
                    onToggle
                )
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
    onFullscreenEdit: (String, String, (String) -> Unit) -> Unit
) {
    val draft = state.draft
    Column(
        Modifier.fillMaxWidth().padding(horizontal = ChatBarSpacing.md),
        verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.md)
    ) {
        SectionCard("角色卡") {
            CharacterCardImport(state, viewModel)
        }
        SectionCard("Prompt") {
            TagPromptInput(
                label = "画风 Prompt",
                value = draft.stylePrompt,
                field = NovelAiPromptFieldKey("style"),
                state = state.tagSuggestions,
                minLines = 3,
                editorHeight = 104.dp,
                onValueChange = { viewModel.updateDraft { draft -> draft.copy(stylePrompt = it) } },
                onSuggest = viewModel::requestTagSuggestions,
                onFullscreenEdit = onFullscreenEdit
            )
            TagPromptInput(
                label = "基础 Prompt",
                value = draft.basePrompt,
                field = NovelAiPromptFieldKey("base"),
                state = state.tagSuggestions,
                minLines = 3,
                editorHeight = 150.dp,
                onValueChange = { viewModel.updateDraft { draft -> draft.copy(basePrompt = it) } },
                onSuggest = viewModel::requestTagSuggestions,
                onFullscreenEdit = onFullscreenEdit
            )
            if (draft.selectedModel == NovelAiImageModel.V5_FULL) {
                CbText(
                    "V5：正向 Prompt 中直接写引号内容会自动生成 Text: 块；手写 Text: 后自动功能停用",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
            if (draft.naturalLanguageMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                    CbButton("AI 转化", viewModel::convertNaturalLanguagePrompt, enabled = !state.isBusy, size = ButtonSize.Sm)
                    if (draft.conversionSnapshot != null) {
                        CbButton("还原", viewModel::restoreConvertedPrompt, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
                    }
                }
            }
            draft.characters.forEachIndexed { index, character ->
                CharacterPromptEditor(index, character, state, viewModel, onFullscreenEdit)
            }
            CbButton("添加角色 Prompt", viewModel::addCharacter, variant = ButtonVariant.Outline)
            CollapsibleHeader(
                title = "基础负面 Prompt",
                summary = if (draft.negativeExpanded) "收起" else "已设置",
                expanded = draft.negativeExpanded,
                onClick = { viewModel.updateDraft { it.copy(negativeExpanded = !it.negativeExpanded) } }
            )
            if (draft.negativeExpanded) {
                TagPromptInput(
                    label = "基础负面 Prompt",
                    value = draft.negativePrompt,
                    field = NovelAiPromptFieldKey("negative"),
                    state = state.tagSuggestions,
                    minLines = 3,
                    onValueChange = { viewModel.updateDraft { draft -> draft.copy(negativePrompt = it) } },
                    onSuggest = viewModel::requestTagSuggestions,
                    onFullscreenEdit = onFullscreenEdit
                )
            }
        }
        SectionCard("AI 设计") {
            CollapsibleHeader(
                title = "AI 设计面板",
                summary = if (draft.aiPanelExpanded) "收起" else if (draft.naturalLanguageMode) "自然语言规划" else "Tag 设计",
                expanded = draft.aiPanelExpanded,
                onClick = { viewModel.updateDraft { it.copy(aiPanelExpanded = !it.aiPanelExpanded) } }
            )
            if (draft.aiPanelExpanded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CbText("自然语言模式", Modifier.weight(1f))
                    CbSwitch(
                        draft.naturalLanguageMode,
                        { checked -> viewModel.updateDraft { it.copy(naturalLanguageMode = checked) } }
                    )
                }
                StudioMultilineInput(
                    label = "画面内容",
                    value = draft.imageDescription,
                    minLines = 3,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(imageDescription = value) } },
                    onFullscreenEdit = onFullscreenEdit
                )
                StudioMultilineInput(
                    label = "额外要求",
                    value = draft.extraRequirement,
                    minLines = 2,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(extraRequirement = value) } },
                    onFullscreenEdit = onFullscreenEdit
                )
                CbButton(
                    if (state.isDesigning) "停止 AI" else "开始 AI 设计",
                    if (state.isDesigning) viewModel::cancelActiveTask else viewModel::designPrompt,
                    enabled = state.canDesign || state.isDesigning,
                    variant = if (state.isDesigning) ButtonVariant.Destructive else ButtonVariant.Default
                )
                if (state.designStatus.isNotBlank()) CbText(state.designStatus, color = ChatBarTheme.colors.mutedForeground)
                state.modelErrors.forEach { message -> CbText(message, color = ChatBarTheme.colors.destructive) }
                if (state.reasoningStream.isNotBlank()) CbText("推理\n${state.reasoningStream}", color = ChatBarTheme.colors.mutedForeground)
                if (state.resultStream.isNotBlank()) CbText(state.resultStream)
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
    CbField("导入角色卡 Prompt", description = "仅作为 AI 组装素材，不覆盖当前角色 Prompt") {
        CbSelect(
            value = state.characterCards.firstOrNull { it.id == state.selectedCharacterCardId },
            options = state.characterCards,
            optionLabel = { it.name },
            onValueChange = { viewModel.importCharacterCardPrompts(it.id) },
            placeholder = "选择角色卡"
        )
    }
}

@Composable
private fun CharacterPromptEditor(
    index: Int,
    character: NovelAiCharacterPromptDraft,
    state: ImagePromptToolUiState,
    viewModel: ImagePromptToolViewModel,
    onFullscreenEdit: (String, String, (String) -> Unit) -> Unit
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
            TagPromptInput(
                label = "角色正向",
                value = character.prompt,
                field = NovelAiPromptFieldKey("character", character.id),
                state = state.tagSuggestions,
                minLines = 2,
                editorHeight = 104.dp,
                onValueChange = { value -> viewModel.updateCharacter(character.id) { it.copy(prompt = value) } },
                onSuggest = viewModel::requestTagSuggestions,
                onFullscreenEdit = onFullscreenEdit
            )
            CollapsibleHeader(
                title = "角色负面",
                summary = if (character.negativeExpanded) "收起" else if (character.negativePrompt.isBlank()) "空" else "已设置",
                expanded = character.negativeExpanded,
                onClick = { viewModel.updateCharacter(character.id) { it.copy(negativeExpanded = !it.negativeExpanded) } }
            )
            if (character.negativeExpanded) {
                TagPromptInput(
                    label = "角色负面",
                    value = character.negativePrompt,
                    field = NovelAiPromptFieldKey("character_negative", character.id),
                    state = state.tagSuggestions,
                    minLines = 2,
                    editorHeight = 104.dp,
                    onValueChange = { value -> viewModel.updateCharacter(character.id) { it.copy(negativePrompt = value) } },
                    onSuggest = viewModel::requestTagSuggestions,
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
    viewModel: ImagePromptToolViewModel
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = ChatBarSpacing.md)) {
        SectionCard("生成设置") {
            CbField("模型") {
                CbSelect(settings.model, NovelAiImageModel.entries, { it.displayName }, viewModel::selectImageModel)
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
                title = "高级设置",
                summary = "${settings.steps} Steps · CFG ${"%.1f".format(settings.guidance)} · ${settings.sampler.displayName} · ${if (settings.seedMode == NovelAiSeedMode.RANDOM) "随机 Seed" else settings.seed}",
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
        CbSlider(settings.steps.toFloat(), { value -> viewModel.updateGenerationSettings { it.copy(steps = value.toInt()) } }, 1f..50f, steps = 48)
    }
    CbField("Guidance · ${"%.1f".format(settings.guidance)}") {
        CbSlider(settings.guidance, { value -> viewModel.updateGenerationSettings { it.copy(guidance = (value * 10).toInt() / 10f) } }, 1f..10f, steps = 89)
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
                    viewModel.updateGenerationSettings { it.copy(seed = text.toLong()) }
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
private fun TagPromptInput(
    label: String,
    value: String,
    field: NovelAiPromptFieldKey,
    state: NovelAiTagSuggestionState,
    minLines: Int = 1,
    editorHeight: Dp = 150.dp,
    onValueChange: (String) -> Unit,
    onSuggest: (NovelAiPromptFieldKey, String, Int) -> Unit,
    onFullscreenEdit: (String, String, (String) -> Unit) -> Unit
) {
    var fieldValue by remember(field) { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) {
        if (value != fieldValue.text) fieldValue = TextFieldValue(value, TextRange(value.length))
    }
    val applyFullscreenText: (String) -> Unit = { text ->
        fieldValue = TextFieldValue(text, TextRange(text.length))
        onValueChange(text)
    }
    CbField(
        label,
        modifier = Modifier.fillMaxWidth(),
        onFullscreenEdit = { onFullscreenEdit(label, fieldValue.text, applyFullscreenText) }
    ) {
        CbInput(
            value = fieldValue,
            onValueChange = { next ->
                fieldValue = next
                onValueChange(next.text)
                onSuggest(field, next.text, next.selection.end)
            },
            modifier = Modifier.height(editorHeight),
            singleLine = false,
            minLines = minLines,
            expand = true
        )
        if (state.field == field) {
            when {
                state.loading -> CbText("正在补全…", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                state.error != null -> CbText(state.error, color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.caption)
                state.candidates.isNotEmpty() -> Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = ChatBarSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)
                ) {
                    state.candidates.forEach { candidate ->
                        val labelText = buildString {
                            append(candidate.name)
                            if (candidate.translatedName.isNotBlank()) append(" · ${candidate.translatedName}")
                            append(" · ${candidate.category.label}")
                            if (candidate.count > 0) append(" ${candidate.count}")
                        }
                        CbButton(labelText, {
                            val inserted = NovelAiTagCompletion.insert(fieldValue.text, fieldValue.selection.end, candidate.name)
                            fieldValue = TextFieldValue(inserted.text, TextRange(inserted.cursor))
                            onValueChange(inserted.text)
                        }, variant = ButtonVariant.Outline, size = ButtonSize.Xs)
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioMultilineInput(
    label: String,
    value: String,
    minLines: Int,
    onValueChange: (String) -> Unit,
    onFullscreenEdit: (String, String, (String) -> Unit) -> Unit
) {
    CbField(label, onFullscreenEdit = { onFullscreenEdit(label, value, onValueChange) }) {
        CbInput(value, onValueChange, singleLine = false, minLines = minLines)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    CbSurface(
        Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(Modifier.fillMaxWidth().padding(ChatBarSpacing.md), verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.md)) {
            CbText(title, style = ChatBarTheme.typography.title)
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
        CbText(title, Modifier.weight(1f), style = ChatBarTheme.typography.label)
        CbText(summary, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
        Spacer(Modifier.width(ChatBarSpacing.xs))
        CbIconButton(if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore, null, onClick)
    }
}
