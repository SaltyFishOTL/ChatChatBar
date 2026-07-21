package com.example.chatbar.ui.imageprompt

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.image.NOVEL_AI_MAX_CHARACTER_PROMPTS
import com.example.chatbar.domain.image.NOVEL_AI_MAX_BATCH_SIZE
import com.example.chatbar.domain.image.NovelAiImageRegenerationDraft
import com.example.chatbar.domain.image.parseNovelAiBatchSize
import com.example.chatbar.ui.components.ImagePreviewDialog
import com.example.chatbar.ui.components.ImagePreviewItem
import com.example.chatbar.ui.components.NovelAiBatchSizeInput
import com.example.chatbar.ui.kit.AppIcons
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbSelect
import com.example.chatbar.ui.kit.CbSpinner
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarShape
import com.example.chatbar.ui.kit.ChatBarSpacing
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.kit.FullscreenTextEditor
import java.io.File

@Composable
fun ImagePromptToolScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImagePromptToolViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val novelAiConfigured by viewModel.novelAiConfigured.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val selectedModel = state.models.firstOrNull { it.id == state.selectedModelId }
    val selectedCharacterCard = state.characterCards.firstOrNull { it.id == state.selectedCharacterCardId }
    var fullscreenField by remember { mutableStateOf<Pair<String, String>?>(null) }
    var fullscreenOnChange by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var promptExpanded by remember { mutableStateOf(false) }
    var imageBatchSizeInput by remember { mutableStateOf("1") }
    var expandedImageIndex by remember { mutableStateOf<Int?>(null) }
    val referenceImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let(viewModel::selectReferenceImage)
    }

    LaunchedEffect(state.promptRevision) {
        if (state.promptRevision > 0) promptExpanded = true
    }

    Box(modifier.fillMaxSize().background(ChatBarTheme.colors.background)) {
        Column(Modifier.fillMaxSize()) {
            CbTopBar(
                title = "跑图工具",
                navigation = { CbIconButton(AppIcons.ArrowBack, "返回", onBack) },
                actions = {
                    if (state.isBusy) {
                        CbButton(
                            "停止",
                            viewModel::cancelActiveTask,
                            variant = ButtonVariant.Outline
                        )
                    }
                }
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .imePadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    PromptInputPanel(
                        state = state,
                        selectedModel = selectedModel,
                        onDescription = viewModel::updateImageDescription,
                        onStyle = viewModel::updateStylePrompt,
                        onCharacter = viewModel::updateCharacterPrompt,
                        onPreference = viewModel::updateImagePromptPreference,
                        onSelectReferenceImage = {
                            referenceImagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onRemoveReferenceImage = viewModel::removeReferenceImage,
                        onFullscreenEdit = { title, text, onChange ->
                            fullscreenField = title to text
                            fullscreenOnChange = onChange
                        },
                        selectedCharacterCard = selectedCharacterCard,
                        onImportCharacterCard = { viewModel.importCharacterCardPrompts(it.id) },
                        onGeneratePrompt = viewModel::designPrompt
                    )
                }
                item {
                    PromptEditorPanel(
                        draft = state.promptDraft,
                        expanded = promptExpanded,
                        enabled = !state.isBusy,
                        canGenerateImage = novelAiConfigured && state.promptDraft.canRegenerate && !state.isBusy,
                        imageGenerating = state.isGeneratingImage,
                        batchSizeInput = imageBatchSizeInput,
                        onExpandedChange = { promptExpanded = it },
                        onDraftChange = viewModel::updatePromptDraft,
                        onBatchSizeInputChange = { imageBatchSizeInput = it },
                        onFullscreenEdit = { title, text, onChange ->
                            fullscreenField = title to text
                            fullscreenOnChange = onChange
                        },
                        onCopy = {
                            clipboard.setText(AnnotatedString(state.promptDraft.toClipboardText()))
                            Toast.makeText(context, "已复制提示词", Toast.LENGTH_SHORT).show()
                        },
                        onGenerateImage = {
                            parseNovelAiBatchSize(imageBatchSizeInput)?.let(viewModel::generateImage)
                        }
                    )
                }
                if (state.isDesigning && state.designStatus.isNotBlank()) {
                    item {
                        StreamPanel(
                            title = "处理进度",
                            text = state.designStatus,
                            active = true
                        )
                    }
                }
                if (state.imageAnalysisStream.isNotBlank()) {
                    item {
                        StreamPanel(
                            title = "图片解析",
                            text = state.imageAnalysisStream,
                            active = state.isDesigning
                        )
                    }
                }
                if (state.reasoningStream.isNotBlank() || state.isDesigning) {
                    item {
                        StreamPanel(
                            title = "思维流",
                            text = state.reasoningStream,
                            active = state.isDesigning
                        )
                    }
                }
                if (state.resultStream.isNotBlank() || state.isDesigning) {
                    item {
                        StreamPanel(
                            title = "结果流",
                            text = state.resultStream,
                            active = state.isDesigning
                        )
                    }
                }
                if (state.imagePreview != null || state.imagePath != null || state.isGeneratingImage) {
                    item {
                        ImagePreviewPanel(
                            state = state,
                            onOpenImage = { expandedImageIndex = it }
                        )
                    }
                }
                state.error?.let { error ->
                    item {
                        ErrorPanel(error, viewModel::dismissError)
                    }
                }
            }
        }
        fullscreenField?.let { (title, text) ->
            FullscreenTextEditor(
                title = title,
                text = text,
                onTextChange = { newValue ->
                    fullscreenOnChange?.invoke(newValue)
                    fullscreenField = title to newValue
                },
                visible = true,
                onDismiss = {
                    fullscreenField = null
                    fullscreenOnChange = null
                }
            )
        }
        expandedImageIndex?.let { initialIndex ->
            ImagePreviewDialog(
                items = state.imagePaths.map { path -> ImagePreviewItem(messageId = "", path = path) },
                initialIndex = initialIndex,
                onDismiss = { expandedImageIndex = null }
            )
        }
    }
}

@Composable
private fun PromptInputPanel(
    state: ImagePromptToolUiState,
    selectedModel: ModelConfig?,
    onDescription: (String) -> Unit,
    onStyle: (String) -> Unit,
    onCharacter: (String) -> Unit,
    onPreference: (String) -> Unit,
    onSelectReferenceImage: () -> Unit,
    onRemoveReferenceImage: () -> Unit,
    onFullscreenEdit: (title: String, text: String, onChange: (String) -> Unit) -> Unit,
    selectedCharacterCard: CharacterCard?,
    onImportCharacterCard: (CharacterCard) -> Unit,
    onGeneratePrompt: () -> Unit
) {
    CbSurface(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CbField(
                label = "参考图片",
                description = "上传后，AI 会根据画面内容反推 NovelAI 提示词。"
            ) {
                state.referenceImagePath?.let { path ->
                    AsyncImage(
                        model = File(path),
                        contentDescription = "提示词参考图片",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(ChatBarShape.sm)),
                        contentScale = ContentScale.Fit
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CbButton(
                        if (state.referenceImagePath == null) "上传图片" else "替换图片",
                        onSelectReferenceImage,
                        modifier = Modifier.weight(1f),
                        enabled = !state.isBusy,
                        variant = ButtonVariant.Outline
                    )
                    if (state.referenceImagePath != null) {
                        CbIconButton(
                            AppIcons.Delete,
                            "移除参考图片",
                            onRemoveReferenceImage,
                            enabled = !state.isBusy,
                            tint = ChatBarTheme.colors.destructive
                        )
                    }
                }
            }
            CbField("图片描述", onFullscreenEdit = {
                onFullscreenEdit("图片描述", state.imageDescription, onDescription)
            }) {
                CbInput(
                    value = state.imageDescription,
                    onValueChange = onDescription,
                    placeholder = "想要生成的画面",
                    enabled = !state.isBusy,
                    singleLine = false,
                    minLines = 1
                )
            }
            CbField("导入角色卡提示词") {
                CbSelect(
                    value = selectedCharacterCard,
                    options = state.characterCards,
                    optionLabel = { it.name },
                    onValueChange = onImportCharacterCard,
                    placeholder = if (state.characterCards.isEmpty()) "暂无角色卡" else "选择角色卡"
                )
            }
            CbField("画风", onFullscreenEdit = {
                onFullscreenEdit("画风", state.stylePrompt, onStyle)
            }) {
                CbInput(
                    value = state.stylePrompt,
                    onValueChange = onStyle,
                    placeholder = "风格、镜头、质感",
                    enabled = !state.isBusy,
                    singleLine = false,
                    minLines = 1
                )
            }
            CbField("角色提示词", onFullscreenEdit = {
                onFullscreenEdit("角色提示词", state.characterPrompt, onCharacter)
            }) {
                CbInput(
                    value = state.characterPrompt,
                    onValueChange = onCharacter,
                    placeholder = "角色外貌、服装、Danbooru 标签",
                    enabled = !state.isBusy,
                    singleLine = false,
                    minLines = 1
                )
            }
            CbField(
                label = "生图偏好",
                description = "约束最终 NovelAI Prompt 的格式、标签、权重或构图取舍；不会单独作为画面来源。",
                onFullscreenEdit = {
                    onFullscreenEdit("生图偏好", state.imagePromptPreference, onPreference)
                }
            ) {
                CbInput(
                    value = state.imagePromptPreference,
                    onValueChange = onPreference,
                    placeholder = "例如：更重视镜头构图，避免长串无关 tags",
                    enabled = !state.isBusy,
                    singleLine = false,
                    minLines = 1
                )
            }
            CbField("默认生图模型") {
                CbText(
                    selectedModel?.displayName ?: "未配置默认生图模型",
                    color = if (selectedModel == null) ChatBarTheme.colors.destructive else ChatBarTheme.colors.foreground,
                    style = ChatBarTheme.typography.body
                )
            }
            state.modelErrors.firstOrNull()?.let {
                CbText(it, color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.caption)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CbButton(
                    "生成 NAI 提示词",
                    onGeneratePrompt,
                    modifier = Modifier.weight(1f),
                    enabled = state.canDesign
                )
                if (state.isDesigning) CbSpinner(Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun StreamPanel(
    title: String,
    text: String,
    active: Boolean
) {
    CbSurface(
        modifier = Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.card,
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbText(title, style = ChatBarTheme.typography.heading)
                if (active) CbSpinner(Modifier.size(18.dp))
            }
            StreamText(text.ifBlank { "等待流式输出" })
        }
    }
}

@Composable
private fun PromptEditorPanel(
    draft: NovelAiImageRegenerationDraft,
    expanded: Boolean,
    enabled: Boolean,
    canGenerateImage: Boolean,
    imageGenerating: Boolean,
    batchSizeInput: String,
    onExpandedChange: (Boolean) -> Unit,
    onDraftChange: (NovelAiImageRegenerationDraft) -> Unit,
    onBatchSizeInputChange: (String) -> Unit,
    onFullscreenEdit: (title: String, text: String, onChange: (String) -> Unit) -> Unit,
    onCopy: () -> Unit,
    onGenerateImage: () -> Unit
) {
    CbSurface(
        modifier = Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.card,
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onExpandedChange(!expanded) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    CbText("NovelAI 提示词", style = ChatBarTheme.typography.heading)
                    CbText(
                        if (draft.baseCaption.isBlank()) "未填写；展开后可手写或让 AI 设计" else "已填写；可编辑后直接生图",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                }
                CbIconButton(
                    if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                    if (expanded) "收起提示词" else "展开提示词",
                    { onExpandedChange(!expanded) },
                    tint = ChatBarTheme.colors.mutedForeground
                )
            }
            if (expanded) {
                CbField(
                    label = "主提示词",
                    description = "场景、构图、画质和全局风格标签",
                    error = if (draft.baseCaption.isBlank()) "主提示词不能为空" else null,
                    onFullscreenEdit = {
                        onFullscreenEdit("编辑主提示词", draft.baseCaption) {
                            onDraftChange(draft.copy(baseCaption = it))
                        }
                    }
                ) {
                    CbInput(
                        value = draft.baseCaption,
                        onValueChange = { onDraftChange(draft.copy(baseCaption = it)) },
                        enabled = enabled,
                        singleLine = false,
                        minLines = 1,
                        isError = draft.baseCaption.isBlank()
                    )
                }
                CbField(
                    label = "负面提示词",
                    description = "不希望图片出现的内容或质量问题",
                    onFullscreenEdit = {
                        onFullscreenEdit("编辑负面提示词", draft.negativePrompt) {
                            onDraftChange(draft.copy(negativePrompt = it))
                        }
                    }
                ) {
                    CbInput(
                        value = draft.negativePrompt,
                        onValueChange = { onDraftChange(draft.copy(negativePrompt = it)) },
                        enabled = enabled,
                        singleLine = false,
                        minLines = 1
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        CbText("角色提示词", style = ChatBarTheme.typography.label)
                        CbText(
                            "${draft.characterPrompts.size}/$NOVEL_AI_MAX_CHARACTER_PROMPTS，可按需添加或删除",
                            color = ChatBarTheme.colors.mutedForeground,
                            style = ChatBarTheme.typography.caption
                        )
                    }
                    CbButton(
                        "添加角色",
                        { onDraftChange(draft.addCharacterPrompt()) },
                        enabled = enabled && draft.characterPrompts.size < NOVEL_AI_MAX_CHARACTER_PROMPTS,
                        variant = ButtonVariant.Outline
                    )
                }
                draft.characterPrompts.forEachIndexed { index, characterPrompt ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        CbField(
                            label = "角色提示词 ${index + 1}",
                            modifier = Modifier.weight(1f),
                            error = if (characterPrompt.prompt.isBlank()) "角色提示词不能为空；不需要时可删除" else null,
                            onFullscreenEdit = {
                                onFullscreenEdit("编辑角色提示词 ${index + 1}", characterPrompt.prompt) { value ->
                                    onDraftChange(
                                        draft.copy(
                                            characterPrompts = draft.characterPrompts.mapIndexed { itemIndex, item ->
                                                if (itemIndex == index) item.copy(prompt = value) else item
                                            }
                                        )
                                    )
                                }
                            }
                        ) {
                            CbInput(
                                value = characterPrompt.prompt,
                                onValueChange = { value ->
                                    onDraftChange(
                                        draft.copy(
                                            characterPrompts = draft.characterPrompts.mapIndexed { itemIndex, item ->
                                                if (itemIndex == index) item.copy(prompt = value) else item
                                            }
                                        )
                                    )
                                },
                                enabled = enabled,
                                singleLine = false,
                                minLines = 1,
                                isError = characterPrompt.prompt.isBlank()
                            )
                        }
                        CbIconButton(
                            AppIcons.Delete,
                            "删除角色提示词 ${index + 1}",
                            { onDraftChange(draft.removeCharacterPrompt(index)) },
                            modifier = Modifier.size(48.dp),
                            enabled = enabled,
                            tint = ChatBarTheme.colors.destructive
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    CbButton(
                        "复制全部",
                        onCopy,
                        modifier = Modifier.weight(1f),
                        enabled = draft.baseCaption.isNotBlank(),
                        variant = ButtonVariant.Secondary
                    )
                    NovelAiBatchSizeInput(
                        value = batchSizeInput,
                        onValueChange = onBatchSizeInputChange,
                        enabled = enabled
                    )
                    CbButton(
                        if (imageGenerating) "生成中" else "用此提示词生图",
                        onGenerateImage,
                        modifier = Modifier.weight(1f),
                        enabled = canGenerateImage && parseNovelAiBatchSize(batchSizeInput) != null
                    )
                }
                CbText(
                    "批量范围 1–$NOVEL_AI_MAX_BATCH_SIZE；多图会额外消耗 Anlas。",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
        }
    }
}

@Composable
internal fun ImagePreviewPanel(
    state: ImagePromptToolUiState,
    onOpenImage: (Int) -> Unit
) {
    val label = when (state.phase) {
        ImagePromptToolPhase.GENERATING -> "NovelAI 正在生成"
        ImagePromptToolPhase.STREAMING -> "流式预览 ${(state.imageProgress * 100).toInt()}%"
        ImagePromptToolPhase.SAVING -> "正在保存图片"
        ImagePromptToolPhase.FINISHED -> "生图完成（${state.imagePaths.size} 张）"
        ImagePromptToolPhase.FAILED -> "生图失败"
        ImagePromptToolPhase.CANCELLED -> "已停止"
        else -> "图片预览"
    }
    CbSurface(
        modifier = Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.card,
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.isGeneratingImage) CbSpinner(Modifier.size(18.dp))
                CbText(label, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
            }
            when {
                state.imagePaths.size > 1 -> Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.imagePaths.forEachIndexed { index, path ->
                        AsyncImage(
                            model = File(path),
                            contentDescription = "NovelAI 生图结果 ${index + 1}",
                            modifier = Modifier
                                .width(220.dp)
                                .height(300.dp)
                                .clip(RoundedCornerShape(ChatBarShape.sm))
                                .clickable { onOpenImage(index) },
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                state.imagePaths.size == 1 -> AsyncImage(
                    model = File(state.imagePaths.first()),
                    contentDescription = "NovelAI 生图结果",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .clip(RoundedCornerShape(ChatBarShape.sm))
                        .clickable { onOpenImage(0) },
                    contentScale = ContentScale.Fit
                )
                state.imagePreview != null -> AsyncImage(
                    model = state.imagePreview,
                    contentDescription = "NovelAI 流式生图预览",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .clip(RoundedCornerShape(ChatBarShape.sm)),
                    contentScale = ContentScale.Fit
                )
                else -> Box(
                    Modifier.fillMaxWidth().height(180.dp).background(ChatBarTheme.colors.muted, RoundedCornerShape(ChatBarShape.sm)),
                    contentAlignment = Alignment.Center
                ) {
                    CbText("等待图片流", color = ChatBarTheme.colors.mutedForeground)
                }
            }
            if (state.imagePaths.isNotEmpty()) {
                CbText(
                    "点击查看大图；大图中长按可打码、保存或分享",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
        }
    }
}

@Composable
private fun ErrorPanel(
    error: String,
    onDismiss: () -> Unit
) {
    CbSurface(
        modifier = Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.muted,
        border = BorderStroke(1.dp, ChatBarTheme.colors.destructive)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CbText(error, color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.caption)
            CbButton("关闭", onDismiss, variant = ButtonVariant.Ghost)
        }
    }
}

@Composable
private fun StreamText(text: String) {
    CbSurface(
        modifier = Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.muted,
        shape = RoundedCornerShape(ChatBarShape.sm)
    ) {
        SelectionContainer {
            CbText(
                text,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(ChatBarSpacing.sm),
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
        }
    }
}

private fun NovelAiImageRegenerationDraft.toClipboardText(): String = buildString {
    appendLine("Base:")
    appendLine(baseCaption)
    characterPrompts.forEachIndexed { index, character ->
        appendLine()
        appendLine("Char ${index + 1}:")
        appendLine(character.prompt)
    }
    appendLine()
    appendLine("Negative:")
    append(negativePrompt)
}
