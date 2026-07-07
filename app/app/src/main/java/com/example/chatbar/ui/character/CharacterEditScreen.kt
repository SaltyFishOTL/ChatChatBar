package com.example.chatbar.ui.character

import com.example.chatbar.ui.kit.AppIcons

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.local.entity.WorldBookPosition
import com.example.chatbar.domain.card.CharacterAutoFillDraft
import com.example.chatbar.domain.card.CharacterRewriteDraft
import com.example.chatbar.domain.image.ImageCropFractionRect
import com.example.chatbar.domain.image.ImageCropOffset
import com.example.chatbar.domain.image.ImageCropSize
import com.example.chatbar.domain.image.clampCropOffset
import com.example.chatbar.domain.image.coverDisplaySize
import com.example.chatbar.domain.image.imageCropFractionRect
import com.example.chatbar.domain.search.ResearchDebugSnapshot
import com.example.chatbar.ui.home.CharacterAvatar
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbChoiceChip
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbDivider
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbProgress
import com.example.chatbar.ui.kit.CbScaffold
import com.example.chatbar.ui.kit.CbSelect
import com.example.chatbar.ui.kit.CbSpinner
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbSwitch
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.kit.FullscreenTextEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PendingImagePick(
    val cropTarget: CharacterImageCropTarget?,
    val onImage: (String) -> Unit
)

private data class PendingImageCrop(
    val uri: Uri,
    val target: CharacterImageCropTarget,
    val onImage: (String) -> Unit
)

private enum class CharacterImageCropTarget(
    val title: String,
    val aspectRatio: Float,
    val outputWidth: Int,
    val outputHeight: Int,
    val circular: Boolean
) {
    Avatar("裁剪头像", 1f, 512, 512, true),
    Background("裁剪聊天背景", 9f / 16f, 1080, 1920, false)
}

@Composable
fun CharacterEditScreen(
    characterId: String?,
    draftId: String = "",
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CharacterEditViewModel = viewModel(
        key = characterId?.let { "edit:$it" } ?: "new:${draftId.ifBlank { "default" }}",
        factory = CharacterEditViewModelFactory(characterId)
    )
) {
    val isSaving by viewModel.isSaving.collectAsState()
    val card by viewModel.characterCard.collectAsState()
    val indexingStatus by viewModel.indexingStatus.collectAsState()
    val autoFillState by viewModel.autoFillState.collectAsState()
    val rewriteState by viewModel.rewriteState.collectAsState()
    val coverImageState by viewModel.coverImageState.collectAsState()
    val autoFillModels by viewModel.autoFillModels.collectAsState()
    val autoFillDefaultModelId by viewModel.autoFillDefaultModelId.collectAsState()
    val availableWorldBooks by viewModel.availableWorldBooks.collectAsState()
    val context = LocalContext.current

    var editCharacter by remember { mutableStateOf<CharacterInfo?>(null) }
    var showCharacterDialog by remember { mutableStateOf(false) }
    var showAutoFillDialog by remember { mutableStateOf(false) }
    var showRewriteDialog by remember { mutableStateOf(false) }
    var showDocumentDialog by remember { mutableStateOf(false) }
    var validationErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var deleteCharacter by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var deleteDocument by remember { mutableStateOf<DocumentInfo?>(null) }
    var confirmClearDocuments by remember { mutableStateOf(false) }
    var pendingImagePick by remember { mutableStateOf<PendingImagePick?>(null) }
    var pendingImageCrop by remember { mutableStateOf<PendingImageCrop?>(null) }
    var pendingEditMode by remember { mutableStateOf<CharacterEditMode?>(null) }
    var fullscreenField by remember { mutableStateOf<Pair<String, String>?>(null) }
    var fullscreenOnChange by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var charDlgFullscreen by remember { mutableStateOf<Triple<String, String, (String) -> Unit>?>(null) }
    var editDocument by remember { mutableStateOf<DocumentInfo?>(null) }
    var editDocName by remember { mutableStateOf("") }
    var editDocContent by remember { mutableStateOf("") }
    var editWorldBookEntryIndex by remember { mutableStateOf<Int?>(null) }
    var deleteWorldBookEntryIndex by remember { mutableStateOf<Int?>(null) }
    var showWorldBookEntryDialog by remember { mutableStateOf(false) }
    var lastBackPressAt by remember { mutableStateOf(0L) }

    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackPressAt <= 2000L) {
            onBack()
        } else {
            lastBackPressAt = now
            Toast.makeText(context, "再按一次退出编辑（不会保存修改）", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(indexingStatus) {
        indexingStatus?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val pick = pendingImagePick
        pendingImagePick = null
        if (uri != null && pick != null) {
            val target = pick.cropTarget
            if (target == null) {
                viewModel.copyUriToLocalFile(uri, pick.onImage)
            } else {
                pendingImageCrop = PendingImageCrop(uri, target, pick.onImage)
            }
        }
    }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let(viewModel::importDocumentsFromFolder)
    }
    fun pickImage(callback: (String) -> Unit) {
        pendingImagePick = PendingImagePick(null, callback)
        imagePicker.launch("image/*")
    }
    fun pickCroppedImage(target: CharacterImageCropTarget, callback: (String) -> Unit) {
        pendingImagePick = PendingImagePick(target, callback)
        imagePicker.launch("image/*")
    }
    fun openAutoFillDialog() {
        if (viewModel.editMode == CharacterEditMode.STRUCTURED) {
            showAutoFillDialog = true
        } else {
            Toast.makeText(context, "AI 自动填充仅支持分段模式", Toast.LENGTH_SHORT).show()
        }
    }
    fun openRewriteDialog() {
        showRewriteDialog = true
    }

    CbScaffold(
        modifier = modifier,
        topBar = {
            CbTopBar(
                title = if (characterId == null) "新建角色卡" else "编辑角色卡",
                statusBarInset = true,
                navigation = { CbIconButton(AppIcons.ArrowBack, "返回", onBack) },
                actions = {
                    CbIconButton(
                        AppIcons.Star,
                        "AI 自动填充",
                        { openAutoFillDialog() },
                        enabled = !isSaving,
                        tint = ChatBarTheme.colors.primary
                    )
                    CbIconButton(
                        AppIcons.Edit,
                        "AI 自动改写",
                        { openRewriteDialog() },
                        enabled = !isSaving,
                        tint = ChatBarTheme.colors.primary
                    )
                    CbIconButton(
                        AppIcons.Save,
                        "保存",
                        {
                            val errors = viewModel.validateForSave()
                            if (errors.isEmpty()) viewModel.saveCharacterCard(onBack) else validationErrors = errors
                        },
                        enabled = !isSaving,
                        tint = ChatBarTheme.colors.primary
                    )
                }
            )
        }
    ) { bottomInset ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ChatBarTheme.colors.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionTitle("角色卡信息")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbButton(
                        if (viewModel.editMode == CharacterEditMode.STRUCTURED) "AI 自动填充" else "AI 自动填充（仅分段模式）",
                        { openAutoFillDialog() },
                        modifier = Modifier.weight(1f),
                        enabled = !isSaving && viewModel.editMode == CharacterEditMode.STRUCTURED,
                        variant = if (viewModel.editMode == CharacterEditMode.STRUCTURED) ButtonVariant.Default else ButtonVariant.Outline
                    )
                    CbButton(
                        "AI 自动改写",
                        { openRewriteDialog() },
                        modifier = Modifier.weight(1f),
                        enabled = !isSaving,
                        variant = ButtonVariant.Secondary
                    )
                }
                CbButton(
                    "AI设计封面",
                    { viewModel.generateCurrentCoverImage() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving && !coverImageState.isGenerating,
                    variant = ButtonVariant.Outline
                )
            }
            CoverImagePreview(coverImageState, title = "当前封面")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(64.dp).clickable {
                        pickCroppedImage(CharacterImageCropTarget.Avatar) { viewModel.avatar = it }
                    },
                    contentAlignment = Alignment.Center
                ) {
                    CharacterAvatar(viewModel.avatar, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.Black.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) {
                        CbIcon(AppIcons.PhotoCamera, "更换头像", Modifier.size(18.dp), Color.White)
                    }
                }
                Spacer(Modifier.width(16.dp))
                CbField(
                    "角色卡名称",
                    Modifier.weight(1f),
                    description = "所有 Prompt 中的 ${'$'}botname 将替换为此名称。"
                ) {
                    CbInput(viewModel.name, { viewModel.name = it }, placeholder = "例如：西幻冒险小队")
                }
            }
            CbField("起始台词", description = "创建新会话时由角色发送。", onFullscreenEdit = {
                fullscreenField = "起始台词" to viewModel.greeting; fullscreenOnChange = { viewModel.greeting = it }
            }) {
                CbInput(viewModel.greeting, { viewModel.greeting = it }, placeholder = "输入开场白…", singleLine = false, minLines = 2)
            }
            CbField("默认聊天背景") {
                ImagePickerPanel(
                    imagePath = viewModel.chatBackground,
                    height = 112.dp,
                    onPick = {
                        pickCroppedImage(CharacterImageCropTarget.Background) { viewModel.chatBackground = it }
                    },
                    onClear = { viewModel.chatBackground = null }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    CbText("启用朋友圈", style = ChatBarTheme.typography.label)
                    CbText(
                        "默认关闭；开启后全局朋友圈会为此角色预排动态。",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                }
                CbSwitch(viewModel.momentsEnabled, { viewModel.momentsEnabled = it })
            }
            CbField("基本设定", description = "世界观、扮演要求等共同设定；两种编辑模式均会生效。", onFullscreenEdit = {
                fullscreenField = "基本设定" to viewModel.basicSetting; fullscreenOnChange = { viewModel.basicSetting = it }
            }) {
                CbInput(
                    viewModel.basicSetting,
                    { viewModel.basicSetting = it },
                    placeholder = "输入不属于具体人物字段的设定…",
                    singleLine = false,
                    minLines = 4
                )
            }
            CbField(
                "NovelAI 默认风格提示词",
                description = "英文标签或自然语言。会固定加入每次生图的基础 Prompt；自由模式可在此直接写角色提示词。",
                onFullscreenEdit = {
                    fullscreenField = "NovelAI 默认风格提示词" to viewModel.defaultImagePrompt; fullscreenOnChange = { viewModel.defaultImagePrompt = it }
                }
            ) {
                CbInput(
                    viewModel.defaultImagePrompt,
                    { viewModel.defaultImagePrompt = it },
                    placeholder = "例如：anime screencap, cinematic lighting, detailed background",
                    singleLine = false,
                    minLines = 3
                )
            }

            CbDivider()
            SectionTitle("人物编辑模式")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbButton(
                    "分段模式",
                    { pendingEditMode = CharacterEditMode.STRUCTURED },
                    variant = if (viewModel.editMode == CharacterEditMode.STRUCTURED) ButtonVariant.Default else ButtonVariant.Outline
                )
                CbButton(
                    "自由模式",
                    { pendingEditMode = CharacterEditMode.FREEFORM },
                    variant = if (viewModel.editMode == CharacterEditMode.FREEFORM) ButtonVariant.Default else ButtonVariant.Outline
                )
            }
            if (viewModel.editMode == CharacterEditMode.STRUCTURED) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("人物设定 (${viewModel.charactersList.size})")
                    CbButton("添加人物", {
                        editCharacter = null
                        showCharacterDialog = true
                    }, variant = ButtonVariant.Ghost)
                }
                viewModel.charactersList.forEachIndexed { index, character ->
                    CharacterRow(
                        character = character,
                        canDelete = true,
                        onEdit = {
                            editCharacter = character
                            showCharacterDialog = true
                        },
                        onDelete = { deleteCharacter = index to (character.name.ifBlank { "未命名角色" }) }
                    )
                }
            } else {
                CbField("自由人物设定", description = "原文替换整个结构化人物设定部分。", onFullscreenEdit = {
                    fullscreenField = "自由人物设定" to viewModel.freeformCharacterText; fullscreenOnChange = { viewModel.freeformCharacterText = it }
                }) {
                    CbInput(
                        viewModel.freeformCharacterText,
                        { viewModel.freeformCharacterText = it },
                        placeholder = "自由编写人物名称、简介、外貌、服装、能力、习惯、经历、人际关系等…",
                        singleLine = false,
                        minLines = 10
                    )
                }
            }

            CbDivider()
            SectionTitle("高级设定")
            CbField(
                "覆盖系统提示词",
                description = "为空时使用全局默认。支持 {{original}} 占位符回退到默认值。",
                onFullscreenEdit = { fullscreenField = "覆盖系统提示词" to viewModel.systemPrompt; fullscreenOnChange = { viewModel.systemPrompt = it } }
            ) {
                CbInput(viewModel.systemPrompt, { viewModel.systemPrompt = it }, placeholder = "留空使用默认…", singleLine = false, minLines = 3)
            }
            CbField(
                "后置强制指令 (Jailbreak)",
                description = "沉底于系统提示词最底部，对模型有最强的约束效果。为空时使用全局默认。",
                onFullscreenEdit = { fullscreenField = "后置强制指令" to viewModel.postHistoryInstructions; fullscreenOnChange = { viewModel.postHistoryInstructions = it } }
            ) {
                CbInput(viewModel.postHistoryInstructions, { viewModel.postHistoryInstructions = it }, placeholder = "留空使用默认…", singleLine = false, minLines = 3)
            }
            CbField("作者备注", description = "仅供用户参考，不会注入AI提示词。", onFullscreenEdit = {
                fullscreenField = "作者备注" to viewModel.creatorNotes; fullscreenOnChange = { viewModel.creatorNotes = it }
            }) {
                CbInput(viewModel.creatorNotes, { viewModel.creatorNotes = it }, placeholder = "例如：推荐温度 1.4", singleLine = false, minLines = 2)
            }

            CbDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("参考文档 (${viewModel.documentsList.size})")
                Row {
                    CbIconButton(AppIcons.Add, "新建文档", { showDocumentDialog = true }, tint = ChatBarTheme.colors.primary)
                    CbIconButton(AppIcons.UploadFile, "批量导入", { directoryPicker.launch(null) }, tint = ChatBarTheme.colors.primary)
                    if (viewModel.documentsList.isNotEmpty()) {
                        CbIconButton(AppIcons.DeleteSweep, "清空文档", { confirmClearDocuments = true }, tint = ChatBarTheme.colors.destructive)
                    }
                }
            }
            card?.let {
                val progress = if (it.ragIndexTotal > 0) it.ragIndexDone.toFloat() / it.ragIndexTotal else if (it.ragIndexStatus == "COMPLETE") 1f else 0f
                CbProgress(progress, error = it.ragIndexStatus == "FAILED")
                CbText(
                    ragStatusText(it.ragIndexStatus, it.ragIndexDone, it.ragIndexTotal, it.ragIndexMessage),
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
            viewModel.documentsList.forEach { document ->
                DocumentRow(
                    document = document,
                    onEdit = {
                        editDocument = document
                        editDocName = document.fileName
                        editDocContent = try { java.io.File(document.filePath).readText() } catch (_: Exception) { "" }
                    },
                    onDelete = { deleteDocument = document }
                )
            }

            CbDivider()
            SectionTitle("世界书装配 (${viewModel.selectedWorldBookIds.size})")
            CbText(
                "从独立世界书模块选择，创建新对话和生成回复时自动注入。",
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
            if (availableWorldBooks.isEmpty()) {
                CbText("暂无世界书，可在管理页导入或新建。", color = ChatBarTheme.colors.mutedForeground)
            } else {
                availableWorldBooks.forEach { book ->
                    CbChoiceChip(
                        text = book.name,
                        selected = book.id in viewModel.selectedWorldBookIds,
                        onClick = { viewModel.toggleWorldBookBinding(book.id) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(bottomInset))
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
            onDismiss = { fullscreenField = null; fullscreenOnChange = null }
        )
    }

    charDlgFullscreen?.let { (title, text, setter) ->
        Dialog(onDismissRequest = { charDlgFullscreen = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            FullscreenTextEditor(
                title = title,
                text = text,
                onTextChange = { newValue ->
                    setter(newValue)
                    charDlgFullscreen = Triple(title, newValue, setter)
                },
                visible = true,
                onDismiss = { charDlgFullscreen = null }
            )
        }
    }

    editDocument?.let { doc ->
        DocumentEditScreen(
            title = "编辑参考文档",
            name = editDocName,
            onNameChange = { editDocName = it },
            content = editDocContent,
            onContentChange = { editDocContent = it },
            onDismiss = { editDocument = null },
            onSave = {
                viewModel.updateDocument(doc, editDocName, editDocContent)
                editDocument = null
            }
        )
    }

    if (showCharacterDialog) {
        CharacterDialog(
            original = editCharacter,
            onDismiss = { showCharacterDialog = false },
            onPickImage = { callback -> pickImage(callback) },
            onSave = { updated ->
                val index = viewModel.charactersList.indexOfFirst { it.id == updated.id }
                if (index >= 0) viewModel.charactersList[index] = updated else viewModel.charactersList.add(updated)
                showCharacterDialog = false
            },
            onFullscreen = { title, text, setter ->
                charDlgFullscreen = Triple(title, text, setter)
            }
        )
    }
    if (showAutoFillDialog) {
        CharacterAutoFillDialog(
            state = autoFillState,
            models = autoFillModels,
            defaultModelId = autoFillDefaultModelId,
            onDismiss = {
                showAutoFillDialog = false
                viewModel.clearAutoFillDraft()
            },
            onPickImage = { callback -> pickImage(callback) },
            onDeleteImage = viewModel::deleteTransientImage,
            onGenerate = viewModel::generateAutoFillDraft,
            onGenerateCover = viewModel::generateAutoFillCoverImageCandidate,
            onCancel = viewModel::cancelAutoFillGeneration,
            onCancelCover = viewModel::cancelCoverImageGeneration,
            onApply = {
                viewModel.applyAutoFillDraft()
                showAutoFillDialog = false
            }
        )
    }
    if (showRewriteDialog) {
        CharacterRewriteDialog(
            state = rewriteState,
            models = autoFillModels,
            defaultModelId = autoFillDefaultModelId,
            onDismiss = {
                showRewriteDialog = false
                viewModel.clearRewriteDraft()
            },
            onGenerate = viewModel::generateRewriteDraft,
            onGenerateCover = viewModel::generateRewriteCoverImageCandidate,
            onCancel = viewModel::cancelRewriteGeneration,
            onCancelCover = viewModel::cancelCoverImageGeneration,
            onApply = {
                viewModel.applyRewriteDraft()
                showRewriteDialog = false
            }
        )
    }
    if (showDocumentDialog) {
        DocumentDialog(
            onDismiss = { showDocumentDialog = false },
            onSave = { name, content ->
                viewModel.addDocument(name, content)
                showDocumentDialog = false
            }
        )
    }
    if (showWorldBookEntryDialog) {
        WorldBookEntryDialog(
            original = editWorldBookEntryIndex?.let { viewModel.worldBookEntries.getOrNull(it) },
            onDismiss = { showWorldBookEntryDialog = false; editWorldBookEntryIndex = null },
            onSave = { entry ->
                val idx = editWorldBookEntryIndex
                if (idx != null) viewModel.updateWorldBookEntry(idx, entry)
                else viewModel.addWorldBookEntry(entry)
                showWorldBookEntryDialog = false; editWorldBookEntryIndex = null
            }
        )
    }
    deleteWorldBookEntryIndex?.let { idx ->
        val name = viewModel.worldBookEntries.getOrNull(idx)?.name?.ifBlank { "未命名条目" } ?: "未命名条目"
        CbDialog(
            onDismissRequest = { deleteWorldBookEntryIndex = null },
            title = "删除世界书条目",
            dismiss = { CbButton("取消", { deleteWorldBookEntryIndex = null }, variant = ButtonVariant.Ghost) },
            confirm = {
                CbButton("删除", {
                    viewModel.deleteWorldBookEntry(idx)
                    deleteWorldBookEntryIndex = null
                }, variant = ButtonVariant.Destructive)
            }
        ) { CbText("确定删除[$name]？", color = ChatBarTheme.colors.mutedForeground) }
    }
    if (validationErrors.isNotEmpty()) {
        CbDialog(
            onDismissRequest = { validationErrors = emptyList() },
            title = "无法保存",
            confirm = { CbButton("知道了", { validationErrors = emptyList() }) }
        ) {
            validationErrors.forEach { CbText("• $it", color = ChatBarTheme.colors.mutedForeground) }
        }
    }
    pendingEditMode?.takeIf { it != viewModel.editMode }?.let { target ->
        CbDialog(
            onDismissRequest = { pendingEditMode = null },
            title = "切换编辑模式",
            confirm = {
                CbButton("确认切换", {
                    viewModel.switchEditMode(target)
                    pendingEditMode = null
                }, variant = ButtonVariant.Destructive)
            },
            dismiss = { CbButton("取消", { pendingEditMode = null }, variant = ButtonVariant.Ghost) }
        ) {
            CbText(
                if (target == CharacterEditMode.FREEFORM)
                    "切换到自由模式后，分段人物数据将保留但不会发送给AI。名称、开场白、基本设定、图片和参考文档不受影响。"
                else
                    "切换到分段模式后，自由人物数据将保留但不会发送给AI。名称、开场白、基本设定、图片和参考文档不受影响。",
                color = ChatBarTheme.colors.mutedForeground
            )
        }
    }
    deleteCharacter?.let { (index, name) ->
        ConfirmDeleteDialog("删除人物设定", "确定删除“$name”的设定？", { deleteCharacter = null }) {
            viewModel.charactersList.removeAt(index)
            deleteCharacter = null
        }
    }
    deleteDocument?.let { document ->
        ConfirmDeleteDialog("删除参考文档", "确定删除“${document.fileName}”？对应 RAG 索引也会清理。", { deleteDocument = null }) {
            viewModel.deleteDocument(document)
            deleteDocument = null
        }
    }
    if (confirmClearDocuments) {
        ConfirmDeleteDialog("清空参考文档", "确定清空全部文档和对应 RAG 索引？", { confirmClearDocuments = false }) {
            viewModel.clearAllDocuments()
            confirmClearDocuments = false
        }
    }
    pendingImageCrop?.let { request ->
        CharacterImageCropDialog(
            uri = request.uri,
            target = request.target,
            onDismiss = { pendingImageCrop = null },
            onConfirm = { rect ->
                viewModel.cropUriToLocalFile(
                    uri = request.uri,
                    cropRect = rect,
                    outputWidth = request.target.outputWidth,
                    outputHeight = request.target.outputHeight
                ) { path ->
                    request.onImage(path)
                    pendingImageCrop = null
                }
            }
        )
    }
    if (isSaving) {
        CbDialog(onDismissRequest = {}, title = "请稍候") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CbSpinner()
                Spacer(Modifier.height(12.dp))
                CbText(indexingStatus ?: "正在保存…", color = ChatBarTheme.colors.mutedForeground)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) = CbText(text, color = ChatBarTheme.colors.primary, style = ChatBarTheme.typography.heading)

@Composable
private fun CharacterImageCropDialog(
    uri: Uri,
    target: CharacterImageCropTarget,
    onDismiss: () -> Unit,
    onConfirm: (ImageCropFractionRect) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val colors = ChatBarTheme.colors
    var sourceSize by remember(uri) { mutableStateOf<ImageCropSize?>(null) }
    var loadFailed by remember(uri) { mutableStateOf(false) }
    var frameSize by remember(uri, target) { mutableStateOf(IntSize.Zero) }
    var scale by remember(uri, target) { mutableStateOf(1f) }
    var offset by remember(uri, target) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(uri) {
        loadFailed = false
        sourceSize = loadImageCropSize(context, uri)
        loadFailed = sourceSize == null
    }
    LaunchedEffect(sourceSize, frameSize.width, frameSize.height) {
        if (sourceSize != null && frameSize.width > 0 && frameSize.height > 0) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val size = sourceSize ?: return@rememberTransformableState
        if (frameSize.width <= 0 || frameSize.height <= 0) return@rememberTransformableState
        val nextScale = (scale * zoomChange).coerceIn(1f, 6f)
        val clamped = clampCropOffset(
            offset = ImageCropOffset(offset.x + panChange.x, offset.y + panChange.y),
            sourceWidth = size.width,
            sourceHeight = size.height,
            frameWidth = frameSize.width.toFloat(),
            frameHeight = frameSize.height.toFloat(),
            userScale = nextScale
        )
        scale = nextScale
        offset = Offset(clamped.x, clamped.y)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.background)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                CbIconButton(AppIcons.Close, "取消裁剪", onDismiss, tint = colors.foreground)
                CbText(target.title, style = ChatBarTheme.typography.heading)
                CbIconButton(
                    AppIcons.Check,
                    "确认裁剪",
                    {
                        val size = sourceSize ?: return@CbIconButton
                        if (frameSize.width <= 0 || frameSize.height <= 0) return@CbIconButton
                        onConfirm(
                            imageCropFractionRect(
                                sourceWidth = size.width,
                                sourceHeight = size.height,
                                frameWidth = frameSize.width.toFloat(),
                                frameHeight = frameSize.height.toFloat(),
                                userScale = scale,
                                offset = ImageCropOffset(offset.x, offset.y)
                            )
                        )
                    },
                    enabled = sourceSize != null && frameSize.width > 0 && frameSize.height > 0,
                    tint = colors.primary
                )
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val maxWidthPx = with(density) { maxWidth.toPx() }
                val maxHeightPx = with(density) { maxHeight.toPx() }
                val heightFromWidth = maxWidthPx / target.aspectRatio
                val cropWidthPx: Float
                val cropHeightPx: Float
                if (heightFromWidth <= maxHeightPx) {
                    cropWidthPx = maxWidthPx
                    cropHeightPx = heightFromWidth
                } else {
                    cropHeightPx = maxHeightPx
                    cropWidthPx = cropHeightPx * target.aspectRatio
                }
                val cropWidthDp = with(density) { cropWidthPx.toDp() }
                val cropHeightDp = with(density) { cropHeightPx.toDp() }
                val shape: Shape = if (target.circular) CircleShape else RoundedCornerShape(14.dp)
                Box(
                    Modifier
                        .size(width = cropWidthDp, height = cropHeightDp)
                        .clip(shape)
                        .background(Color.Black)
                        .border(1.dp, colors.border, shape)
                        .transformable(transformState)
                        .onSizeChanged { frameSize = it },
                    contentAlignment = Alignment.Center
                ) {
                    val size = sourceSize
                    if (size == null) {
                        if (loadFailed) {
                            CbText("图片无法读取", color = Color.White)
                        } else {
                            CbSpinner()
                        }
                    } else if (frameSize.width > 0 && frameSize.height > 0) {
                        val display = coverDisplaySize(
                            sourceWidth = size.width,
                            sourceHeight = size.height,
                            frameWidth = frameSize.width.toFloat(),
                            frameHeight = frameSize.height.toFloat()
                        )
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            modifier = Modifier
                                .requiredSize(
                                    width = with(density) { display.width.toDp() },
                                    height = with(density) { display.height.toDp() }
                                )
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                },
                            contentScale = ContentScale.FillBounds
                        )
                    } else {
                        CbSpinner()
                    }
                }
            }
        }
    }
}

private suspend fun loadImageCropSize(context: Context, uri: Uri): ImageCropSize? = withContext(Dispatchers.IO) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try {
                ImageCropSize(bitmap.width.toFloat(), bitmap.height.toFloat())
            } finally {
                bitmap.recycle()
            }
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
            if (options.outWidth > 0 && options.outHeight > 0) {
                ImageCropSize(options.outWidth.toFloat(), options.outHeight.toFloat())
            } else {
                null
            }
        }
    }.getOrNull()
}

@Composable
private fun ImagePickerPanel(imagePath: String?, height: androidx.compose.ui.unit.Dp, onPick: () -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(10.dp)).background(ChatBarTheme.colors.muted).clickable(onClick = onPick),
            contentAlignment = Alignment.Center
        ) {
            if (!imagePath.isNullOrBlank()) {
                AsyncImage(imagePath, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)))
            }
            CbIcon(AppIcons.PhotoCamera, "选择图片", tint = Color.White)
        }
        if (!imagePath.isNullOrBlank()) CbButton("清除图片", onClear, variant = ButtonVariant.Ghost)
    }
}

@Composable
private fun CharacterRow(character: CharacterInfo, canDelete: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    CbSurface(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, ChatBarTheme.colors.border)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!character.appearanceImage.isNullOrBlank()) {
                AsyncImage(character.appearanceImage, null, Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                CbText(character.name.ifBlank { "未命名角色" }, style = ChatBarTheme.typography.heading)
                if (character.profile.isNotBlank()) CbText(character.profile, color = ChatBarTheme.colors.mutedForeground, maxLines = 1)
            }
            CbIconButton(AppIcons.Edit, "编辑", onEdit, tint = ChatBarTheme.colors.primary)
            if (canDelete) CbIconButton(AppIcons.Delete, "删除", onDelete, tint = ChatBarTheme.colors.destructive)
        }
    }
}

@Composable
private fun DocumentRow(document: DocumentInfo, onEdit: () -> Unit, onDelete: () -> Unit) {
    CbSurface(Modifier.fillMaxWidth().clickable(onClick = onEdit), border = BorderStroke(1.dp, ChatBarTheme.colors.border)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CbIcon(AppIcons.Article, null, tint = ChatBarTheme.colors.mutedForeground)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                CbText(document.fileName, style = ChatBarTheme.typography.heading)
                CbText(document.filePath, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption, maxLines = 1)
            }
            CbIconButton(AppIcons.Edit, "编辑", onEdit, tint = ChatBarTheme.colors.primary)
            CbIconButton(AppIcons.Delete, "删除", onDelete, tint = ChatBarTheme.colors.destructive)
        }
    }
}

private fun ScrollState.isNearBottom(thresholdPx: Int): Boolean =
    maxValue - value <= thresholdPx

private fun Modifier.pauseStreamingAutoScrollOnUserDrag(enabled: Boolean, onUserDrag: () -> Unit): Modifier =
    if (!enabled) {
        this
    } else {
        pointerInput(enabled) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                awaitTouchSlopOrCancellation(down.id) { _, _ -> onUserDrag() }
            }
        }
    }

@Composable
private fun CharacterAutoFillDialog(
    state: CharacterAutoFillUiState,
    models: List<ModelConfig>,
    defaultModelId: String?,
    onDismiss: () -> Unit,
    onPickImage: (((String) -> Unit) -> Unit),
    onDeleteImage: (String?) -> Unit,
    onGenerate: (String, String?, String?) -> Unit,
    onGenerateCover: (String?) -> Unit,
    onCancel: () -> Unit,
    onCancelCover: () -> Unit,
    onApply: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var sourceImagePath by remember { mutableStateOf<String?>(null) }
    var selectedModelId by remember { mutableStateOf<String?>(null) }
    val modelOptions = remember(models, defaultModelId) {
        val defaultModelLabel = models.firstOrNull { it.id == defaultModelId }?.autoFillLabel()
        listOf(AutoFillModelOption(null, defaultModelLabel?.let { "默认模型：$it" } ?: "默认模型")) +
            models.map { AutoFillModelOption(it.id, it.autoFillLabel()) }
    }
    val selectedModel = modelOptions.firstOrNull { it.id == selectedModelId } ?: modelOptions.first()
    val bodyScroll = rememberScrollState()
    val autoScrollBottomThresholdPx = with(LocalDensity.current) { 48.dp.roundToPx() }
    var followStreamingOutput by remember { mutableStateOf(true) }
    val busy = state.isGenerating || state.coverImage.isGenerating
    fun clearSourceImage() {
        onDeleteImage(sourceImagePath)
        sourceImagePath = null
    }
    val retainedSourceImagePath = sourceImagePath
    DisposableEffect(retainedSourceImagePath) {
        onDispose { onDeleteImage(retainedSourceImagePath) }
    }
    LaunchedEffect(models) {
        if (selectedModelId != null && models.none { it.id == selectedModelId }) {
            selectedModelId = null
        }
    }
    LaunchedEffect(state.isGenerating) {
        if (state.isGenerating) {
            followStreamingOutput = true
        }
    }
    LaunchedEffect(bodyScroll.value, bodyScroll.maxValue, state.isGenerating) {
        if (!state.isGenerating || bodyScroll.isNearBottom(autoScrollBottomThresholdPx)) {
            followStreamingOutput = true
        }
    }
    LaunchedEffect(state.streamingText, state.visibleOutputs, state.progressLines.size) {
        if (followStreamingOutput && state.isGenerating && (state.streamingText.isNotBlank() || state.visibleOutputs.isNotEmpty() || state.progressLines.isNotEmpty())) {
            bodyScroll.animateScrollTo(bodyScroll.maxValue)
        }
    }
    CbDialog(
        onDismissRequest = {
            if (!busy) {
                clearSourceImage()
                onDismiss()
            }
        },
        title = "AI 自动填充",
        modifier = Modifier.heightIn(max = 760.dp),
        dismiss = {
            if (state.isGenerating) {
                CbButton("取消生成", onCancel, variant = ButtonVariant.Destructive)
            } else if (state.coverImage.isGenerating) {
                CbButton("取消封面设计", onCancelCover, variant = ButtonVariant.Destructive)
            } else {
                CbButton(
                    "关闭",
                    {
                        clearSourceImage()
                        onDismiss()
                    },
                    variant = ButtonVariant.Ghost
                )
            }
        },
        confirm = {
            CbButton(
                "应用",
                {
                    clearSourceImage()
                    onApply()
                },
                enabled = state.draft != null && !busy
            )
        },
        dismissOnClickOutside = false,
        dismissOnBackPress = false
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .pauseStreamingAutoScrollOnUserDrag(state.isGenerating) { followStreamingOutput = false }
                .verticalScroll(bodyScroll),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CbField("本次使用模型") {
                CbSelect(
                    value = selectedModel,
                    options = modelOptions,
                    optionLabel = { it.label },
                    onValueChange = { selectedModelId = it.id },
                    placeholder = "本次使用模型"
                )
            }
            CbField("角色信息与扮演要求") {
                CbInput(
                    input,
                    { input = it },
                    placeholder = "写下想玩的角色、世界观、玩法偏好、互动要求……",
                    singleLine = false,
                    minLines = 6
                )
            }
            CbField("参考图片") {
                ImagePickerPanel(
                    imagePath = sourceImagePath,
                    height = 112.dp,
                    onPick = {
                        if (!busy) {
                            onPickImage { path ->
                                clearSourceImage()
                                sourceImagePath = path
                            }
                        }
                    },
                    onClear = { if (!busy) clearSourceImage() }
                )
            }
            if (state.isGenerating) {
                CbButton(
                    "取消生成",
                    onCancel,
                    enabled = true,
                    variant = ButtonVariant.Destructive
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbButton(
                        "生成候选",
                        { onGenerate(input, selectedModel.id, sourceImagePath) },
                        modifier = Modifier.weight(1f),
                        enabled = (input.isNotBlank() || !sourceImagePath.isNullOrBlank()) && !state.coverImage.isGenerating,
                        variant = ButtonVariant.Secondary
                    )
                    CbButton(
                        "AI设计封面",
                        { onGenerateCover(selectedModel.id) },
                        modifier = Modifier.weight(1f),
                        enabled = state.draft != null && !state.coverImage.isGenerating,
                        variant = ButtonVariant.Outline
                    )
                }
            }
            if (state.progressLines.isNotEmpty()) {
                GenerationProgressPanel(
                    active = state.isGenerating,
                    statusText = state.statusText.ifBlank { "正在生成角色卡候选" },
                    progressLines = state.progressLines
                )
            }
            if (state.isGenerating) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbSpinner()
                    CbText(state.statusText.ifBlank { "正在生成角色卡候选" }, color = ChatBarTheme.colors.mutedForeground)
                }
            }
            state.visibleOutputs.forEach { output ->
                DebugTextBlock(output.title, output.text)
            }
            state.researchDebug?.takeIf(ResearchDebugSnapshot::hasContent)?.let { debug ->
                ResearchDebugPanel(debug)
            }
            if (state.streamingText.isNotBlank() && state.draft == null) {
                AutoFillRawStreamPreview(state.streamingText)
            }
            state.error?.takeIf(String::isNotBlank)?.let { error ->
                CbSurface(
                    Modifier.fillMaxWidth(),
                    color = ChatBarTheme.colors.muted,
                    border = BorderStroke(1.dp, ChatBarTheme.colors.destructive)
                ) {
                    CbText(
                        error,
                        Modifier.padding(12.dp),
                        color = ChatBarTheme.colors.destructive
                    )
                }
            }
            CoverImagePreview(state.coverImage, title = "封面候选")
            state.draft?.let { draft ->
                CbDivider()
                AutoFillDraftPreview(draft)
            }
        }
    }
}

@Composable
private fun CharacterRewriteDialog(
    state: CharacterRewriteUiState,
    models: List<ModelConfig>,
    defaultModelId: String?,
    onDismiss: () -> Unit,
    onGenerate: (String, String?) -> Unit,
    onGenerateCover: (String?) -> Unit,
    onCancel: () -> Unit,
    onCancelCover: () -> Unit,
    onApply: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var selectedModelId by remember { mutableStateOf<String?>(null) }
    val modelOptions = remember(models, defaultModelId) {
        val defaultModelLabel = models.firstOrNull { it.id == defaultModelId }?.autoFillLabel()
        listOf(AutoFillModelOption(null, defaultModelLabel?.let { "默认模型：$it" } ?: "默认模型")) +
            models.map { AutoFillModelOption(it.id, it.autoFillLabel()) }
    }
    val selectedModel = modelOptions.firstOrNull { it.id == selectedModelId } ?: modelOptions.first()
    val bodyScroll = rememberScrollState()
    val autoScrollBottomThresholdPx = with(LocalDensity.current) { 48.dp.roundToPx() }
    var followStreamingOutput by remember { mutableStateOf(true) }
    val busy = state.isGenerating || state.coverImage.isGenerating
    LaunchedEffect(models) {
        if (selectedModelId != null && models.none { it.id == selectedModelId }) {
            selectedModelId = null
        }
    }
    LaunchedEffect(state.isGenerating) {
        if (state.isGenerating) {
            followStreamingOutput = true
        }
    }
    LaunchedEffect(bodyScroll.value, bodyScroll.maxValue, state.isGenerating) {
        if (!state.isGenerating || bodyScroll.isNearBottom(autoScrollBottomThresholdPx)) {
            followStreamingOutput = true
        }
    }
    LaunchedEffect(state.streamingText, state.visibleOutputs, state.progressLines.size) {
        if (followStreamingOutput && state.isGenerating && (state.streamingText.isNotBlank() || state.visibleOutputs.isNotEmpty() || state.progressLines.isNotEmpty())) {
            bodyScroll.animateScrollTo(bodyScroll.maxValue)
        }
    }
    CbDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = "AI 自动改写",
        modifier = Modifier.heightIn(max = 760.dp),
        dismiss = {
            if (state.isGenerating) {
                CbButton("取消生成", onCancel, variant = ButtonVariant.Destructive)
            } else if (state.coverImage.isGenerating) {
                CbButton("取消封面设计", onCancelCover, variant = ButtonVariant.Destructive)
            } else {
                CbButton("关闭", onDismiss, variant = ButtonVariant.Ghost)
            }
        },
        confirm = {
            CbButton(
                "应用改写",
                onApply,
                enabled = state.draft != null && !busy
            )
        },
        dismissOnClickOutside = false,
        dismissOnBackPress = false
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .pauseStreamingAutoScrollOnUserDrag(state.isGenerating) { followStreamingOutput = false }
                .verticalScroll(bodyScroll),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CbField("本次使用模型") {
                CbSelect(
                    value = selectedModel,
                    options = modelOptions,
                    optionLabel = { it.label },
                    onValueChange = { selectedModelId = it.id },
                    placeholder = "本次使用模型"
                )
            }
            CbField("改写要求") {
                CbInput(
                    input,
                    { input = it },
                    placeholder = "写下要如何基于当前角色卡改写，例如更冷淡、新增助手、删掉某个配角、清空背景经历……",
                    singleLine = false,
                    minLines = 6
                )
            }
            if (state.isGenerating) {
                CbButton(
                    "取消生成",
                    onCancel,
                    enabled = true,
                    variant = ButtonVariant.Destructive
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbButton(
                        "生成改写",
                        { onGenerate(input, selectedModel.id) },
                        modifier = Modifier.weight(1f),
                        enabled = input.isNotBlank() && !state.coverImage.isGenerating,
                        variant = ButtonVariant.Secondary
                    )
                    CbButton(
                        "AI设计封面",
                        { onGenerateCover(selectedModel.id) },
                        modifier = Modifier.weight(1f),
                        enabled = state.draft != null && !state.coverImage.isGenerating,
                        variant = ButtonVariant.Outline
                    )
                }
            }
            if (state.progressLines.isNotEmpty()) {
                GenerationProgressPanel(
                    active = state.isGenerating,
                    statusText = state.statusText.ifBlank { "正在改写角色卡候选" },
                    progressLines = state.progressLines
                )
            }
            if (state.isGenerating) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbSpinner()
                    CbText(state.statusText.ifBlank { "正在改写角色卡候选" }, color = ChatBarTheme.colors.mutedForeground)
                }
            }
            state.visibleOutputs.forEach { output ->
                DebugTextBlock(output.title, output.text)
            }
            state.researchDebug?.takeIf(ResearchDebugSnapshot::hasContent)?.let { debug ->
                ResearchDebugPanel(debug)
            }
            if (state.streamingText.isNotBlank() && state.draft == null) {
                AutoFillRawStreamPreview(state.streamingText)
            }
            state.error?.takeIf(String::isNotBlank)?.let { error ->
                CbSurface(
                    Modifier.fillMaxWidth(),
                    color = ChatBarTheme.colors.muted,
                    border = BorderStroke(1.dp, ChatBarTheme.colors.destructive)
                ) {
                    CbText(
                        error,
                        Modifier.padding(12.dp),
                        color = ChatBarTheme.colors.destructive
                    )
                }
            }
            CoverImagePreview(state.coverImage, title = "封面候选")
            state.draft?.let { draft ->
                CbDivider()
                RewriteCandidatePreview(draft)
            }
        }
    }
}

@Composable
private fun ResearchDebugPanel(debug: ResearchDebugSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CbText("检索调试", style = ChatBarTheme.typography.heading)
        debug.plan?.let { plan ->
            DebugTextBlock(
                title = "搜索规划",
                text = buildString {
                    appendLine("needSearch=${plan.needSearch}")
                    if (plan.reason.isNotBlank()) appendLine("reason=${plan.reason}")
                    plan.queries.forEachIndexed { index, query ->
                        appendLine("${index + 1}. [P${query.priority}] ${query.query}")
                    }
                }.trim()
            )
        }
        debug.brief?.takeIf(ResearchBriefVisible::hasBriefText)?.let { brief ->
            DebugTextBlock(
                title = "整理后的资料",
                text = buildString {
                    if (brief.facts.isNotEmpty()) {
                        appendLine("facts:")
                        brief.facts.forEachIndexed { index, fact -> appendLine("${index + 1}. $fact") }
                    }
                    if (brief.notes.isNotEmpty()) {
                        if (brief.facts.isNotEmpty()) appendLine()
                        appendLine("notes:")
                        brief.notes.forEachIndexed { index, note -> appendLine("${index + 1}. $note") }
                    }
                }.trim()
            )
        }
        if (debug.briefFailureReason.isNotBlank() || debug.briefRawResponsePreview.isNotBlank()) {
            DebugTextBlock(
                title = "资料整理失败",
                text = buildString {
                    if (debug.briefFailureReason.isNotBlank()) {
                        appendLine("reason=${debug.briefFailureReason}")
                    }
                    if (debug.briefRawResponsePreview.isNotBlank()) {
                        if (isNotEmpty()) appendLine()
                        appendLine("raw:")
                        append(debug.briefRawResponsePreview)
                    }
                }.trim()
            )
        }
        if (debug.sources.isNotEmpty()) {
            CbText("搜索到并清洗后的内容", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
            debug.sources.forEach { source ->
                DebugTextBlock(
                    title = "[${source.sourceId}] ${source.title}",
                    text = buildString {
                        appendLine("type=${source.sourceType}; query=${source.query}")
                        appendLine(source.url)
                        appendLine()
                        append(source.excerpt)
                    }.trim()
                )
            }
        }
    }
}

private object ResearchBriefVisible {
    fun hasBriefText(brief: com.example.chatbar.domain.search.ResearchBrief): Boolean =
        brief.facts.isNotEmpty() || brief.notes.isNotEmpty()
}

@Composable
private fun DebugTextBlock(title: String, text: String) {
    if (text.isBlank()) return
    CbSurface(
        Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.muted,
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CbText(title, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
            CbText(text)
        }
    }
}

@Composable
private fun GenerationProgressPanel(
    active: Boolean,
    statusText: String,
    progressLines: List<String>
) {
    CbSurface(
        Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.muted,
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active) CbSpinner()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    CbText("执行进度", style = ChatBarTheme.typography.heading)
                    CbText(statusText, color = ChatBarTheme.colors.mutedForeground)
                }
            }
            progressLines.takeLast(8).forEachIndexed { index, line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbText(
                        "${progressLines.size - progressLines.takeLast(8).size + index + 1}.",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                    CbText(
                        line,
                        modifier = Modifier.weight(1f),
                        color = if (index == progressLines.takeLast(8).lastIndex) {
                            ChatBarTheme.colors.foreground
                        } else {
                            ChatBarTheme.colors.mutedForeground
                        },
                        style = ChatBarTheme.typography.caption
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoFillRawStreamPreview(rawText: String) {
    CbSurface(
        Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.muted,
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CbText("实时输出", style = ChatBarTheme.typography.heading)
            CbText(rawText, color = ChatBarTheme.colors.mutedForeground)
        }
    }
}

@Composable
private fun CoverImagePreview(state: CharacterCoverImageUiState, title: String) {
    val imageModel: Any? = state.preview ?: state.path
    val hasImageStatus = state.isGenerating ||
        imageModel != null ||
        state.error?.isNotBlank() == true ||
        state.promptText.isNotBlank()
    if (!hasImageStatus) return
    CbSurface(
        Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.muted,
        border = BorderStroke(
            1.dp,
            if (state.error.isNullOrBlank()) ChatBarTheme.colors.border else ChatBarTheme.colors.destructive
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CbText(title, style = ChatBarTheme.typography.heading)
            imageModel?.let { model ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ChatBarTheme.colors.background),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            if (state.isGenerating) {
                CbProgress(state.progress.coerceAtLeast(0.04f))
                CbText(
                    state.statusText.ifBlank { "正在生成封面" },
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
            state.error?.takeIf(String::isNotBlank)?.let { error ->
                CbText(error, color = ChatBarTheme.colors.destructive)
            }
            if (imageModel == null && state.promptText.isNotBlank()) {
                CbText(state.promptText, color = ChatBarTheme.colors.mutedForeground)
            }
        }
    }
}

@Composable
private fun RewriteCandidatePreview(draft: CharacterRewriteDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CbText("改写候选", style = ChatBarTheme.typography.heading)
        PreviewField("角色卡名称", draft.name.orEmpty())
        PreviewField("开场白", draft.greeting.orEmpty())
        PreviewField("基本设定", draft.basicSetting.orEmpty())
        PreviewField("NovelAI 默认风格", draft.defaultImagePrompt.orEmpty())
        PreviewField("自由人物设定", draft.freeformCharacterText.orEmpty())
        if (draft.deleteCharacterIds.isNotEmpty()) {
            CbSurface(
                Modifier.fillMaxWidth(),
                color = ChatBarTheme.colors.muted,
                border = BorderStroke(1.dp, ChatBarTheme.colors.destructive)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CbText("删除人物", color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.caption)
                    CbText(draft.deleteCharacterIds.joinToString("、"))
                }
            }
        }
        draft.characters.forEachIndexed { index, character ->
            CbSurface(
                Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, ChatBarTheme.colors.border)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CbText(
                        character.id?.takeIf(String::isNotBlank)?.let { "人物 ${index + 1}: ${character.name.orEmpty().ifBlank { it }}" } ?: "新增人物 ${index + 1}",
                        style = ChatBarTheme.typography.heading
                    )
                    CharacterPreviewField("姓名", character.name.orEmpty())
                    CharacterPreviewField("简介", character.profile.orEmpty())
                    CharacterPreviewField("外貌", character.appearance.orEmpty())
                    CharacterPreviewField("服装", character.clothing.orEmpty())
                    CharacterPreviewField("能力", character.abilities.orEmpty())
                    CharacterPreviewField("习惯/爱好", character.habits.orEmpty())
                    CharacterPreviewField("背景", character.background.orEmpty())
                    CharacterPreviewField("关系", character.relationships.orEmpty())
                    CharacterPreviewField("语气", character.speakingStyle.orEmpty())
                    CharacterPreviewField("NAI 人物提示词", character.imagePrompt.orEmpty())
                }
            }
        }
    }
}

@Composable
private fun AutoFillDraftPreview(draft: CharacterAutoFillDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CbText("候选预览", style = ChatBarTheme.typography.heading)
        PreviewField("角色卡名称", draft.name)
        PreviewField("开场白", draft.greeting)
        PreviewField("基本设定", draft.basicSetting)
        PreviewField("NovelAI 默认风格", draft.defaultImagePrompt)
        draft.characters.forEachIndexed { index, character ->
            CbSurface(
                Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, ChatBarTheme.colors.border)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CbText("人物 ${index + 1}: ${character.name.ifBlank { "未命名" }}", style = ChatBarTheme.typography.heading)
                    CharacterPreviewField("简介", character.profile)
                    CharacterPreviewField("外貌", character.appearance)
                    CharacterPreviewField("服装", character.clothing)
                    CharacterPreviewField("能力", character.abilities)
                    CharacterPreviewField("习惯/爱好", character.habits)
                    CharacterPreviewField("背景", character.background)
                    CharacterPreviewField("关系", character.relationships)
                    CharacterPreviewField("语气", character.speakingStyle)
                    CharacterPreviewField("NAI 人物提示词", character.imagePrompt)
                }
            }
        }
    }
}

@Composable
private fun PreviewPatchField(label: String, value: String?) {
    if (value == null) return
    CbSurface(
        Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.muted,
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CbText(label, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
            CbText(value.ifEmpty { "（清空）" })
        }
    }
}

@Composable
private fun PreviewPatchInline(label: String, value: String?) {
    if (value == null) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CbText(label, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
        CbText(value.ifEmpty { "（清空）" })
    }
}

@Composable
private fun PreviewField(label: String, value: String) {
    if (value.isBlank()) return
    CbSurface(
        Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.muted,
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CbText(label, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
            CbText(value)
        }
    }
}

@Composable
private fun CharacterPreviewField(label: String, value: String) {
    if (value.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CbText(label, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
        CbText(value)
    }
}

@Composable
private fun CharacterDialog(
    original: CharacterInfo?,
    onDismiss: () -> Unit,
    onPickImage: (((String) -> Unit) -> Unit),
    onSave: (CharacterInfo) -> Unit,
    onFullscreen: (String, String, (String) -> Unit) -> Unit
) {
    var name by remember { mutableStateOf(original?.name ?: "") }
    var profile by remember { mutableStateOf(original?.profile ?: "") }
    var appearance by remember { mutableStateOf(original?.appearance ?: "") }
    var image by remember { mutableStateOf(original?.appearanceImage ?: "") }
    var clothing by remember { mutableStateOf(original?.clothing ?: "") }
    var abilities by remember { mutableStateOf(original?.abilities ?: "") }
    var habits by remember { mutableStateOf(original?.habits ?: "") }
    var background by remember { mutableStateOf(original?.background ?: "") }
    var relationships by remember { mutableStateOf(original?.relationships ?: "") }
    var speaking by remember { mutableStateOf(original?.speakingStyle ?: "") }
    var imagePrompt by remember { mutableStateOf(original?.imagePrompt ?: "") }
    CbDialog(
        onDismissRequest = onDismiss,
        title = if (original == null) "添加人物设定" else "编辑人物设定",
        modifier = Modifier.heightIn(max = 760.dp),
        dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) },
        confirm = {
            CbButton("保存", {
                onSave(
                    CharacterInfo(
                        id = original?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name,
                        profile = profile,
                        appearance = appearance,
                        appearanceImage = image,
                        clothing = clothing,
                        abilities = abilities,
                        habits = habits,
                        background = background,
                        relationships = relationships,
                        speakingStyle = speaking,
                        imagePrompt = imagePrompt
                    )
                )
            }, enabled = name.isNotBlank())
        }
    ) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CbField("人物名称") { CbInput(name, { name = it }, placeholder = "例如：阿尔托莉雅") }
            CbField("简介", onFullscreenEdit = { onFullscreen("简介", profile, { profile = it }) }) { CbInput(profile, { profile = it }, placeholder = "一句话概括身份设定", singleLine = false, minLines = 2) }
            Box(
                Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp)).background(ChatBarTheme.colors.muted).clickable { onPickImage { image = it } },
                contentAlignment = Alignment.Center
            ) {
                if (image.isNotBlank()) AsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CbIcon(AppIcons.AddPhotoAlternate, null, tint = ChatBarTheme.colors.mutedForeground)
                    CbText("上传设定图", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                }
            }
            CbField("外貌特征", onFullscreenEdit = { onFullscreen("外貌特征", appearance, { appearance = it }) }) { CbInput(appearance, { appearance = it }, singleLine = false, minLines = 2) }
            CbField("服装", onFullscreenEdit = { onFullscreen("服装", clothing, { clothing = it }) }) { CbInput(clothing, { clothing = it }, singleLine = false, minLines = 2) }
            CbField("能力", onFullscreenEdit = { onFullscreen("能力", abilities, { abilities = it }) }) { CbInput(abilities, { abilities = it }, singleLine = false, minLines = 2) }
            CbField("习惯，爱好", onFullscreenEdit = { onFullscreen("习惯，爱好", habits, { habits = it }) }) { CbInput(habits, { habits = it }, singleLine = false, minLines = 2) }
            CbField("背景经历", onFullscreenEdit = { onFullscreen("背景经历", background, { background = it }) }) { CbInput(background, { background = it }, singleLine = false, minLines = 3) }
            CbField("人际关系", onFullscreenEdit = { onFullscreen("人际关系", relationships, { relationships = it }) }) { CbInput(relationships, { relationships = it }, singleLine = false, minLines = 2) }
            CbField("语气与口癖", onFullscreenEdit = { onFullscreen("语气与口癖", speaking, { speaking = it }) }) { CbInput(speaking, { speaking = it }, singleLine = false, minLines = 2) }
            CbField(
                "NovelAI 人物提示词",
                description = "固定外貌与身份标签。当前服装、动作和表情会由对话 AI 按情景补充。",
                onFullscreenEdit = { onFullscreen("NovelAI 人物提示词", imagePrompt, { imagePrompt = it }) }
            ) {
                CbInput(
                    imagePrompt,
                    { imagePrompt = it },
                    placeholder = "例如：girl, long black hair, blue eyes, hair ribbon",
                    singleLine = false,
                    minLines = 3
                )
            }
        }
    }
}

@Composable
private fun DocumentDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    CbDialog(
        onDismissRequest = onDismiss,
        title = "新建参考文档",
        dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) },
        confirm = { CbButton("保存", { onSave(name, content) }, enabled = name.isNotBlank() && content.isNotBlank()) }
    ) {
        CbField("文档名称") { CbInput(name, { name = it }, placeholder = "世界观设定.txt") }
        Spacer(Modifier.height(12.dp))
        CbField("文档内容") { CbInput(content, { content = it }, singleLine = false, minLines = 5) }
    }
}

@Composable
private fun ConfirmDeleteDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    CbDialog(
        onDismissRequest = onDismiss,
        title = title,
        dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) },
        confirm = { CbButton("确认", onConfirm, variant = ButtonVariant.Destructive) }
    ) { CbText(message, color = ChatBarTheme.colors.mutedForeground) }
}

@Composable
private fun DocumentEditScreen(
    title: String,
    name: String,
    onNameChange: (String) -> Unit,
    content: String,
    onContentChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val localView = LocalView.current
    DisposableEffect(Unit) {
        val window = (localView.context as? Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, localView) }
        controller?.let {
            it.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
    val colors = ChatBarTheme.colors
    val intSrc = remember { MutableInteractionSource() }
    val focused by intSrc.collectIsFocusedAsState()
    Box(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            CbText(title, style = ChatBarTheme.typography.title)
            Spacer(Modifier.size(12.dp))
            CbField("文档名称") {
                CbInput(name, onNameChange, placeholder = "文档名称.txt")
            }
            Spacer(Modifier.height(8.dp))
            CbField("文档内容") {
                Box(Modifier.fillMaxWidth().weight(1f).heightIn(min = 200.dp)) {
                    BasicTextField(
                        value = content,
                        onValueChange = onContentChange,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.input, RoundedCornerShape(8.dp))
                            .border(if (focused) 1.5.dp else 1.dp, if (focused) colors.primary else colors.border, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        singleLine = false,
                        textStyle = ChatBarTheme.typography.body.copy(color = colors.foreground),
                        cursorBrush = SolidColor(colors.primary),
                        interactionSource = intSrc,
                        decorationBox = { inner ->
                            if (content.isEmpty()) CbText("输入文档内容…", color = colors.mutedForeground)
                            inner()
                        }
                    )
                }
            }
        }
        CbIconButton(AppIcons.Close, "退出", onDismiss, Modifier.align(Alignment.BottomStart).padding(16.dp).size(56.dp).background(colors.card, CircleShape))
        CbIconButton(AppIcons.Check, "保存", onSave, Modifier.align(Alignment.BottomEnd).padding(16.dp).size(56.dp).background(colors.primary, CircleShape), enabled = name.isNotBlank() && content.isNotBlank(), tint = colors.primaryForeground)
    }
}

@Composable
private fun WorldBookEntryRow(entry: WorldBookEntry, onEdit: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    CbSurface(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, ChatBarTheme.colors.border)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CbIcon(AppIcons.Article, null, tint = if (entry.enabled) ChatBarTheme.colors.primary else ChatBarTheme.colors.mutedForeground)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                CbText(entry.name.ifBlank { "未命名条目" }, style = ChatBarTheme.typography.heading)
                CbText(entry.keys.joinToString(", ").ifBlank { "无触发词" }, color = ChatBarTheme.colors.mutedForeground, maxLines = 1)
            }
            CbIconButton(AppIcons.Edit, "编辑", onEdit, tint = ChatBarTheme.colors.primary)
            CbIconButton(AppIcons.Delete, "删除", onDelete, tint = ChatBarTheme.colors.destructive)
        }
    }
}

@Composable
private fun WorldBookEntryDialog(
    original: WorldBookEntry?,
    onDismiss: () -> Unit,
    onSave: (WorldBookEntry) -> Unit
) {
    var name by remember { mutableStateOf(original?.name ?: "") }
    var keys by remember { mutableStateOf(original?.keys?.joinToString(", ") ?: "") }
    var content by remember { mutableStateOf(original?.content ?: "") }
    var order by remember { mutableStateOf((original?.insertionOrder ?: 100).toString()) }
    var enabled by remember { mutableStateOf(original?.enabled ?: true) }
    var constant by remember { mutableStateOf(original?.constant ?: false) }
    var probability by remember { mutableStateOf((original?.probability ?: 100).toString()) }
    var sticky by remember { mutableStateOf((original?.sticky ?: 0).toString()) }
    var cooldown by remember { mutableStateOf((original?.cooldown ?: 0).toString()) }
    var delay by remember { mutableStateOf((original?.delay ?: 0).toString()) }
    var useRegex by remember { mutableStateOf(original?.useRegex ?: false) }
    var outletName by remember { mutableStateOf(original?.outletName ?: "") }
    var charFilter by remember { mutableStateOf(original?.characterFilter?.joinToString(", ") ?: "") }
    var position by remember { mutableStateOf(original?.position ?: WorldBookPosition.BEFORE_CHAR) }
    var contentFullscreen by remember { mutableStateOf(false) }
    CbDialog(
        onDismissRequest = onDismiss,
        title = if (original == null) "添加世界书条目" else "编辑世界书条目",
        modifier = Modifier.heightIn(max = 700.dp),
        dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) },
        confirm = {
            CbButton("保存", {
                onSave(
                    WorldBookEntry(
                        id = original?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name,
                        keys = keys.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        content = content,
                        enabled = enabled,
                        insertionOrder = order.toIntOrNull() ?: 100,
                        constant = constant,
                        position = position,
                        useRegex = useRegex,
                        outletName = outletName,
                        characterFilter = charFilter.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        probability = probability.toIntOrNull() ?: 100,
                        sticky = sticky.toIntOrNull() ?: 0,
                        cooldown = cooldown.toIntOrNull() ?: 0,
                        delay = delay.toIntOrNull() ?: 0
                    )
                )
            }, enabled = keys.isNotBlank() && content.isNotBlank())
        }
    ) {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CbField("条目名称", description = "可选，仅供备忘") { CbInput(name, { name = it }, placeholder = "可选") }
            CbField("触发词", description = "逗号分隔，大小写不敏感。任一词出现在最近消息中即触发") {
                CbInput(keys, { keys = it }, placeholder = "关键词1, 关键词2")
            }
            CbField("条目内容", description = "触发后注入到系统提示词中", onFullscreenEdit = {
                contentFullscreen = true
            }) {
                CbInput(content, { content = it }, singleLine = false, minLines = 3)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CbField("插入顺序", Modifier.weight(1f), description = "数值越大越靠后，对生成影响越强") {
                    CbInput(order, { order = it }, placeholder = "100")
                }
                CbField("触发概率", Modifier.weight(1f), description = "100=必定触发，50=一半概率，0=禁用") {
                    CbInput(probability, { probability = it }, placeholder = "100")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CbField("保持 (Sticky)", Modifier.weight(1f), description = "激活后持续N条消息，0=不保持") {
                    CbInput(sticky, { sticky = it }, placeholder = "0")
                }
                CbField("冷却 (Cooldown)", Modifier.weight(1f), description = "激活后冷却N条消息，0=无冷却") {
                    CbInput(cooldown, { cooldown = it }, placeholder = "0")
                }
            }
            CbField("延迟轮数", description = "聊天消息数达到N后才允许激活，0=无延迟") {
                CbInput(delay, { delay = it }, placeholder = "0")
            }
            CbField("出口名称 (Outlet)", description = "非空时条目不自动注入，通过 {{outlet::名称}} 宏手动放置") {
                CbInput(outletName, { outletName = it }, placeholder = "留空则自动注入")
            }
            CbField("角色过滤", description = "逗号分隔的角色名称。为空则对所有角色生效") {
                CbInput(charFilter, { charFilter = it }, placeholder = "角色A, 角色B")
            }
            CbField("插入位置", description = "BEFORE=角色设定前, AFTER=角色设定后, OUTLET=通过宏手动放置") {
                CbSelect(
                    value = position,
                    options = listOf(WorldBookPosition.BEFORE_CHAR, WorldBookPosition.AFTER_CHAR, WorldBookPosition.OUTLET),
                    optionLabel = {
                        when (it) {
                            WorldBookPosition.BEFORE_CHAR -> "角色设定之前"
                            WorldBookPosition.AFTER_CHAR -> "角色设定之后"
                            WorldBookPosition.OUTLET -> "出口 (Outlet)"
                        }
                    },
                    onValueChange = { position = it }
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                CbText("正则匹配", color = ChatBarTheme.colors.mutedForeground)
                CbSwitch(useRegex, { useRegex = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                CbText("启用")
                CbSwitch(enabled, { enabled = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                CbText("始终注入", color = ChatBarTheme.colors.mutedForeground)
                CbSwitch(constant, { constant = it })
            }
        }
    }

    if (contentFullscreen) {
        FullscreenTextEditor(
            title = "编辑条目内容",
            text = content,
            onTextChange = { content = it },
            visible = true,
            onDismiss = { contentFullscreen = false }
        )
    }
}

private fun ragStatusText(status: String, done: Int, total: Int, message: String?): String {
    val label = when (status) {
        "INDEXING" -> "RAG 索引中"
        "COMPLETE" -> "RAG 索引完成"
        "FAILED" -> "RAG 索引失败"
        else -> "RAG 索引待重建"
    }
    return "$label $done/$total${message?.let { " · $it" } ?: ""}"
}

private data class AutoFillModelOption(val id: String?, val label: String)

private fun ModelConfig.autoFillLabel(): String =
    displayName.ifBlank { modelName.ifBlank { id } }
