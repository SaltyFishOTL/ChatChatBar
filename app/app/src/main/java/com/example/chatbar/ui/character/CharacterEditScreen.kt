package com.example.chatbar.ui.character

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UploadFile
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.ui.home.CharacterAvatar
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbDivider
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbProgress
import com.example.chatbar.ui.kit.CbScaffold
import com.example.chatbar.ui.kit.CbSpinner
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.kit.FullscreenTextEditor

@Composable
fun CharacterEditScreen(
    characterId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CharacterEditViewModel = viewModel(key = characterId, factory = CharacterEditViewModelFactory(characterId))
) {
    val isSaving by viewModel.isSaving.collectAsState()
    val card by viewModel.characterCard.collectAsState()
    val indexingStatus by viewModel.indexingStatus.collectAsState()
    val context = LocalContext.current

    var editCharacter by remember { mutableStateOf<CharacterInfo?>(null) }
    var showCharacterDialog by remember { mutableStateOf(false) }
    var showDocumentDialog by remember { mutableStateOf(false) }
    var validationErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var deleteCharacter by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var deleteDocument by remember { mutableStateOf<DocumentInfo?>(null) }
    var confirmClearDocuments by remember { mutableStateOf(false) }
    var imageCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var pendingEditMode by remember { mutableStateOf<CharacterEditMode?>(null) }
    var fullscreenField by remember { mutableStateOf<Pair<String, String>?>(null) }
    var fullscreenOnChange by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var editDocument by remember { mutableStateOf<DocumentInfo?>(null) }
    var editDocName by remember { mutableStateOf("") }
    var editDocContent by remember { mutableStateOf("") }
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
        uri?.let { viewModel.copyUriToLocalFile(it) { path -> imageCallback?.invoke(path) } }
    }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let(viewModel::importDocumentsFromFolder)
    }
    fun pickImage(callback: (String) -> Unit) {
        imageCallback = callback
        imagePicker.launch("image/*")
    }

    CbScaffold(
        modifier = modifier,
        topBar = {
            CbTopBar(
                title = if (characterId == null) "新建角色卡" else "编辑角色卡",
                statusBarInset = true,
                navigation = { CbIconButton(Icons.Default.ArrowBack, "返回", onBack) },
                actions = {
                    CbIconButton(
                        Icons.Default.Save,
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
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ChatBarTheme.colors.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionTitle("角色卡信息")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(64.dp).clickable { pickImage { viewModel.avatar = it } },
                    contentAlignment = Alignment.Center
                ) {
                    CharacterAvatar(viewModel.avatar, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.Black.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) {
                        CbIcon(Icons.Default.PhotoCamera, "更换头像", Modifier.size(18.dp), Color.White)
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
                    onPick = { pickImage { viewModel.chatBackground = it } },
                    onClear = { viewModel.chatBackground = null }
                )
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
            CbField("作者备注", description = "仅供用户参考，不会注入AI提示词。") {
                CbInput(viewModel.creatorNotes, { viewModel.creatorNotes = it }, placeholder = "例如：推荐温度 1.4", singleLine = false, minLines = 2)
            }

            CbDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("参考文档 (${viewModel.documentsList.size})")
                Row {
                    CbIconButton(Icons.Default.Add, "新建文档", { showDocumentDialog = true }, tint = ChatBarTheme.colors.primary)
                    CbIconButton(Icons.Default.UploadFile, "批量导入", { directoryPicker.launch(null) }, tint = ChatBarTheme.colors.primary)
                    if (viewModel.documentsList.isNotEmpty()) {
                        CbIconButton(Icons.Default.DeleteSweep, "清空文档", { confirmClearDocuments = true }, tint = ChatBarTheme.colors.destructive)
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
            CbIcon(Icons.Default.PhotoCamera, "选择图片", tint = Color.White)
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
            CbIconButton(Icons.Default.Edit, "编辑", onEdit, tint = ChatBarTheme.colors.primary)
            if (canDelete) CbIconButton(Icons.Default.Delete, "删除", onDelete, tint = ChatBarTheme.colors.destructive)
        }
    }
}

@Composable
private fun DocumentRow(document: DocumentInfo, onEdit: () -> Unit, onDelete: () -> Unit) {
    CbSurface(Modifier.fillMaxWidth().clickable(onClick = onEdit), border = BorderStroke(1.dp, ChatBarTheme.colors.border)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CbIcon(Icons.Default.Article, null, tint = ChatBarTheme.colors.mutedForeground)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                CbText(document.fileName, style = ChatBarTheme.typography.heading)
                CbText(document.filePath, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption, maxLines = 1)
            }
            CbIconButton(Icons.Default.Edit, "编辑", onEdit, tint = ChatBarTheme.colors.primary)
            CbIconButton(Icons.Default.Delete, "删除", onDelete, tint = ChatBarTheme.colors.destructive)
        }
    }
}

@Composable
private fun CharacterDialog(
    original: CharacterInfo?,
    onDismiss: () -> Unit,
    onPickImage: (((String) -> Unit) -> Unit),
    onSave: (CharacterInfo) -> Unit
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
            CbField("简介") { CbInput(profile, { profile = it }, placeholder = "一句话概括身份设定") }
            Box(
                Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp)).background(ChatBarTheme.colors.muted).clickable { onPickImage { image = it } },
                contentAlignment = Alignment.Center
            ) {
                if (image.isNotBlank()) AsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CbIcon(Icons.Default.AddPhotoAlternate, null, tint = ChatBarTheme.colors.mutedForeground)
                    CbText("上传设定图", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                }
            }
            CbField("外貌特征") { CbInput(appearance, { appearance = it }, singleLine = false, minLines = 2) }
            CbField("服装") { CbInput(clothing, { clothing = it }, singleLine = false, minLines = 2) }
            CbField("能力") { CbInput(abilities, { abilities = it }, singleLine = false, minLines = 2) }
            CbField("习惯，爱好") { CbInput(habits, { habits = it }, singleLine = false, minLines = 2) }
            CbField("背景经历") { CbInput(background, { background = it }, singleLine = false, minLines = 3) }
            CbField("人际关系") { CbInput(relationships, { relationships = it }, singleLine = false, minLines = 2) }
            CbField("语气与口癖") { CbInput(speaking, { speaking = it }, singleLine = false, minLines = 2) }
            CbField(
                "NovelAI 人物提示词",
                description = "固定外貌与身份标签。当前服装、动作和表情会由对话 AI 按情景补充。"
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
        CbIconButton(Icons.Default.Close, "退出", onDismiss, Modifier.align(Alignment.BottomStart).padding(16.dp).size(56.dp).background(colors.card, CircleShape))
        CbIconButton(Icons.Default.Check, "保存", onSave, Modifier.align(Alignment.BottomEnd).padding(16.dp).size(56.dp).background(colors.primary, CircleShape), enabled = name.isNotBlank() && content.isNotBlank(), tint = colors.primaryForeground)
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
