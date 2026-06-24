package com.example.chatbar.ui.chat

import android.app.Activity
import android.net.Uri
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.chatbar.DebugConfig
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.ui.components.ChatBubble
import com.example.chatbar.ui.components.TypingIndicator
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbSpinner
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    sessionId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = viewModel(key = sessionId, factory = ChatViewModelFactory(sessionId))
) {
    val session by viewModel.session.collectAsState()
    val characterCard by viewModel.characterCard.collectAsState()
    val isArchived by viewModel.isArchived.collectAsState()
    val isModelUsable by viewModel.isModelUsable.collectAsState()
    val bubbleFontScale by viewModel.chatBubbleFontScale.collectAsState()
    val modelConfigurationErrors by viewModel.modelConfigurationErrors.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val isResponding by viewModel.isResponding.collectAsState()
    val streamingMessage by viewModel.streamingMessage.collectAsState()
    val deletingMemory by viewModel.isDeletingMemory.collectAsState()
    val contextWindowSize by viewModel.contextWindowSize.collectAsState()
    val novelAiConfigured by viewModel.novelAiConfigured.collectAsState()
    val imageGeneration by viewModel.imageGeneration.collectAsState()
    val showBatteryOptimizationHint by viewModel.showBatteryOptimizationHint.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val localView = LocalView.current

    var input by remember { mutableStateOf(TextFieldValue("")) }
    val selectedImages = remember { mutableStateListOf<String>() }
    var settingsOpen by remember { mutableStateOf(false) }
    var debugOpen by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var fullComposer by remember { mutableStateOf(false) }
    var actionMessageId by remember { mutableStateOf<String?>(null) }
    var expandedImagePath by remember { mutableStateOf<String?>(null) }
    var deleteImageTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var editingText by remember { mutableStateOf(TextFieldValue("")) }
    val editingImages = remember { mutableStateListOf<String>() }
    var viewportAnchor by remember { mutableStateOf(0 to 0) }
    var restoringViewport by remember { mutableStateOf(false) }
    var initialScrollDone by remember(sessionId) { mutableStateOf(false) }
    var awaitingCompletedReply by remember(sessionId) { mutableStateOf(false) }

    suspend fun scrollToBottom(animated: Boolean, expectedItemCount: Int = 0) {
        if (expectedItemCount > 0) {
            snapshotFlow { listState.layoutInfo.totalItemsCount }
                .first { it >= expectedItemCount }
        }
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last < 0) return
        if (animated) {
            listState.animateScrollToItem(last, Int.MAX_VALUE)
        } else {
            listState.scrollToItem(last, Int.MAX_VALUE)
        }
    }

    val chatImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { viewModel.copyUriToLocalFile(it) { path -> selectedImages.add(path) } }
    }
    val editImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { viewModel.copyUriToLocalFile(it) { path -> editingImages.add(path) } }
    }

    BackHandler(enabled = editingMessage != null) { editingMessage = null; editingImages.clear() }
    BackHandler(enabled = fullComposer) { fullComposer = false }
    val fullScreenEditor = editingMessage != null || fullComposer
    DisposableEffect(fullScreenEditor, localView) {
        val window = (localView.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, localView) }
        if (fullScreenEditor) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            info.totalItemsCount == 0 || (last != null && last.index == info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 16)
        }
    }
    val latestComplete = !isResponding && streamingMessage == null && messages.isNotEmpty()
    val backgroundPath = session?.chatBackground ?: characterCard?.chatBackground
    val alternativeIds = remember(messages, contextWindowSize) {
        messages.takeLast(contextWindowSize.coerceAtLeast(1)).filter { it.role == MessageRole.ASSISTANT && it.alternatives.size > 1 }.map { it.id }.toSet()
    }
    val regenerableId = messages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }?.id
    val imageGenerationRunning = imageGeneration != null && imageGeneration?.phase != ImageGenerationPhase.FAILED

    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress) {
        if (!restoringViewport) viewportAnchor = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
    }
    LaunchedEffect(messages.size) {
        if (!initialScrollDone && messages.isNotEmpty()) {
            initialScrollDone = true; restoringViewport = true
            scrollToBottom(animated = false, expectedItemCount = messages.size)
            viewportAnchor = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            restoringViewport = false
        }
    }
    LaunchedEffect(isResponding, streamingMessage, messages.lastOrNull()?.id) {
        if (isResponding) awaitingCompletedReply = true
        val completedReply = messages.lastOrNull()?.role == MessageRole.ASSISTANT
        if (awaitingCompletedReply && !isResponding && streamingMessage == null && completedReply) {
            awaitingCompletedReply = false
            scrollToBottom(animated = true, expectedItemCount = messages.size)
        }
    }
    LaunchedEffect(streamingMessage?.content?.length, streamingMessage?.reasoningContent?.length) {
        if (streamingMessage != null && !listState.isScrollInProgress) {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems == 0) return@LaunchedEffect
            val maxIndex = totalItems - 1
            restoringViewport = true
            listState.scrollToItem(viewportAnchor.first.coerceAtMost(maxIndex), viewportAnchor.second)
            restoringViewport = false
        }
    }

    if (editingMessage != null) {
        MessageEditFullScreen(
            title = "编辑消息",
            text = editingText,
            images = editingImages,
            onTextChange = { editingText = it },
            onAddImage = { editImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onRemoveImage = { editingImages.remove(it) },
            onCancel = { editingMessage = null; editingImages.clear() },
            onSave = {
                editingMessage?.let { viewModel.editMessage(it.id, editingText.text, editingImages.toList()) }
                editingMessage = null; editingImages.clear()
            },
            saveIcon = Icons.Default.Save
        )
        return
    }
    if (fullComposer && !isArchived) {
        MessageEditFullScreen(
            title = "撰写消息",
            text = input,
            images = selectedImages,
            onTextChange = { input = it },
            onAddImage = { chatImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onRemoveImage = { selectedImages.remove(it) },
            onCancel = { fullComposer = false },
            onSave = {
                viewModel.sendMessage(input.text, selectedImages.toList())
                input = TextFieldValue(""); selectedImages.clear(); fullComposer = false
            },
            saveIcon = Icons.Default.Send
        )
        return
    }

    Column(modifier.fillMaxSize().background(ChatBarTheme.colors.background)) {
        CbTopBar(
            title = session?.title ?: characterCard?.name ?: "聊天",
            navigation = { CbIconButton(Icons.Default.ArrowBack, "返回", onBack) },
            actions = {
                if (DebugConfig.SHOW_DEBUG_UI) CbIconButton(Icons.Default.BugReport, "调试日志", { debugOpen = true }, tint = ChatBarTheme.colors.primary)
                CbIconButton(Icons.Default.Tune, "会话设置", { settingsOpen = true })
            }
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!backgroundPath.isNullOrBlank()) {
                AsyncImage(File(backgroundPath), "聊天背景", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(ChatBarTheme.colors.background.copy(alpha = 0.84f)))
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(messages, key = { it.id }) { message ->
                    ChatBubble(
                        message = message,
                        fontScale = bubbleFontScale,
                        onLongPress = { if (!isResponding) actionMessageId = message.id },
                        onImageClick = { expandedImagePath = it },
                        onImageLongPress = { path ->
                            if (!isResponding) deleteImageTarget = message.id to path
                        },
                        onPreviousAlternative = if (message.id in alternativeIds) ({ viewModel.switchAssistantAlternative(message.id, -1) }) else null,
                        onNextAlternative = if (message.id in alternativeIds) ({ viewModel.switchAssistantAlternative(message.id, 1) }) else null,
                        onGenerateImage = if (
                            novelAiConfigured &&
                            !isArchived &&
                            isModelUsable &&
                            message.role == MessageRole.ASSISTANT &&
                            message.displayContent.isNotBlank()
                        ) ({ viewModel.generateNovelAiImage(message.id) }) else null,
                        imageGenerationEnabled = !imageGenerationRunning
                    )
                }
                streamingMessage?.let { item(key = "streaming-${it.id}") { ChatBubble(it, fontScale = bubbleFontScale) } }
                imageGeneration?.let { generation ->
                    item(key = "novelai-generation") {
                        NovelAiGenerationCard(generation) {
                            viewModel.generateNovelAiImage(generation.anchorMessageId)
                        }
                    }
                }
                if (isResponding && streamingMessage == null) item(key = "typing") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        CbSurface(color = ChatBarTheme.colors.card, shape = RoundedCornerShape(10.dp, 10.dp, 10.dp, 3.dp)) { TypingIndicator() }
                    }
                }
            }
            if (!isAtBottom) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(16.dp).size(48.dp).clip(CircleShape).background(ChatBarTheme.colors.primary).clickable {
                        scope.launch { scrollToBottom(animated = true) }
                    },
                    contentAlignment = Alignment.Center
                ) {
                    CbIcon(Icons.Default.KeyboardArrowDown, "跳到底部", tint = ChatBarTheme.colors.primaryForeground)
                    if (latestComplete) {
                        Box(Modifier.align(Alignment.TopEnd).size(16.dp).clip(CircleShape).background(ChatBarTheme.colors.success), contentAlignment = Alignment.Center) {
                            CbIcon(Icons.Default.Check, "最新消息已完成", Modifier.size(11.dp), Color.White)
                        }
                    }
                }
            }
        }
        if (isArchived) {
            CbSurface(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                color = ChatBarTheme.colors.muted,
                border = BorderStroke(1.dp, ChatBarTheme.colors.border)
            ) {
                CbText("角色卡不存在，本对话已被封存", Modifier.padding(12.dp), color = ChatBarTheme.colors.mutedForeground)
            }
        }
        if (!isArchived && !isModelUsable) {
            CbSurface(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                color = ChatBarTheme.colors.muted,
                border = BorderStroke(1.dp, ChatBarTheme.colors.destructive)
            ) {
                CbText(
                    modelConfigurationErrors.firstOrNull() ?: "模型配置不可用，请联系管理员",
                    Modifier.padding(12.dp),
                    color = ChatBarTheme.colors.destructive
                )
            }
        }
        if (selectedImages.isNotEmpty()) ImageStrip(selectedImages, { selectedImages.remove(it) })
        ChatComposer(
            input = input,
            onInput = { input = it },
            responding = isResponding,
            enabled = !isArchived && isModelUsable,
            onImage = { chatImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onFull = { fullComposer = true },
            onSend = {
                viewModel.sendMessage(input.text, selectedImages.toList())
                input = TextFieldValue(""); selectedImages.clear()
            }
        )
    }

    if (settingsOpen) ChatSettingsDialog(viewModel, { settingsOpen = false }, { clearConfirm = true })
    if (debugOpen) DebugLogDialog(sessionId, viewModel, { debugOpen = false })
    if (clearConfirm) {
        CbDialog(
            onDismissRequest = { clearConfirm = false },
            title = "清空记录",
            dismiss = { CbButton("取消", { clearConfirm = false }, variant = ButtonVariant.Ghost) },
            confirm = { CbButton("删除", { viewModel.clearHistoryAndMemory(); clearConfirm = false; settingsOpen = false }, variant = ButtonVariant.Destructive) }
        ) { CbText("确定删除全部聊天记录和 RAG 记忆？此操作不可撤销。", color = ChatBarTheme.colors.mutedForeground) }
    }
    if (showBatteryOptimizationHint) {
        val context = LocalContext.current
        CbDialog(
            onDismissRequest = { viewModel.dismissBatteryOptimizationHint() },
            title = "后台生成稳定性",
            dismiss = { CbButton("知道了", { viewModel.dismissBatteryOptimizationHint() }, variant = ButtonVariant.Ghost) },
            confirm = {
                CbButton("去设置", {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                    viewModel.dismissBatteryOptimizationHint()
                })
            }
        ) {
            CbText("当前开启了电池优化，切换到后台时 AI 生成可能被系统中断。建议关闭本应用的电池优化以获得稳定的后台生成体验。", color = ChatBarTheme.colors.mutedForeground)
        }
    }
    if (deletingMemory) {
        CbDialog(onDismissRequest = {}, title = "正在删除长期记忆") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CbSpinner(); CbText("消息已移除，正在清理对应 RAG 记忆。", color = ChatBarTheme.colors.mutedForeground)
            }
        }
    }
    expandedImagePath?.let { path ->
        FullImageDialog(path = path, onDismiss = { expandedImagePath = null })
    }
    deleteImageTarget?.let { (messageId, path) ->
        CbDialog(
            onDismissRequest = { deleteImageTarget = null },
            title = "删除图片",
            dismiss = {
                CbButton("取消", { deleteImageTarget = null }, variant = ButtonVariant.Ghost)
            },
            confirm = {
                CbButton(
                    "删除",
                    {
                        viewModel.deleteImage(messageId, path)
                        deleteImageTarget = null
                    },
                    variant = ButtonVariant.Destructive
                )
            }
        ) {
            CbText("确定删除这张图片？此操作不可撤销。", color = ChatBarTheme.colors.mutedForeground)
        }
    }
    actionMessageId?.let { id ->
        val target = messages.find { it.id == id }
        val canRegenerate = target?.id == regenerableId && !isResponding
        CbDialog(
            onDismissRequest = { actionMessageId = null },
            title = "消息操作",
            dismiss = { CbButton("关闭", { actionMessageId = null }, variant = ButtonVariant.Ghost) }
        ) {
            CbButton("编辑", {
                target?.let { editingMessage = it; editingText = TextFieldValue(it.displayContent); editingImages.clear(); editingImages.addAll(it.images) }
                actionMessageId = null
            }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Secondary)
            Spacer(Modifier.size(8.dp))
            target?.let {
                CbButton("删除", { viewModel.deleteMessage(it.id); actionMessageId = null }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Destructive)
            }
            if (canRegenerate) {
                Spacer(Modifier.size(8.dp))
                CbButton("重新生成", { actionMessageId = null; viewModel.regenerateLastResponse() }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
            }
        }
    }
}

@Composable
private fun NovelAiGenerationCard(state: ImageGenerationState, onRetry: () -> Unit) {
    val label = when (state.phase) {
        ImageGenerationPhase.DESIGNING -> "正在设计 NovelAI Prompt"
        ImageGenerationPhase.GENERATING -> "NovelAI 正在生成"
        ImageGenerationPhase.STREAMING -> "流式预览 ${(state.progress * 100).toInt()}%"
        ImageGenerationPhase.SAVING -> "正在保存图片"
        ImageGenerationPhase.FAILED -> "生图失败"
    }
    CbSurface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        color = ChatBarTheme.colors.card,
        border = BorderStroke(1.dp, if (state.phase == ImageGenerationPhase.FAILED) ChatBarTheme.colors.destructive else ChatBarTheme.colors.border)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.previewImage?.let { bytes ->
                AsyncImage(
                    model = bytes,
                    contentDescription = "NovelAI 流式生图预览",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.phase != ImageGenerationPhase.FAILED) CbSpinner(Modifier.size(18.dp))
                CbText(
                    label,
                    color = if (state.phase == ImageGenerationPhase.FAILED) ChatBarTheme.colors.destructive else ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
            if (state.promptDraft.isNotBlank()) {
                CbSurface(
                    modifier = Modifier.fillMaxWidth(),
                    color = ChatBarTheme.colors.muted,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    CbText(
                        state.promptDraft.takeLast(1600),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 140.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                }
            }
            state.error?.let { CbText(it, color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.caption) }
            if (state.phase == ImageGenerationPhase.FAILED) {
                CbButton("重试", onRetry, variant = ButtonVariant.Outline)
            }
        }
    }
}

@Composable
private fun FullImageDialog(path: String, onDismiss: () -> Unit) {
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    var showSaveDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (showSaveDialog) {
        CbDialog(
            onDismissRequest = { showSaveDialog = false },
            title = "保存图片",
            confirm = { CbButton("保存", { showSaveDialog = false; saveImageToGallery(context, path) }) },
            dismiss = { CbButton("取消", { showSaveDialog = false }, variant = ButtonVariant.Ghost) }
        ) {
            CbText("保存到相册？")
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AsyncImage(
                model = File(path),
                contentDescription = "查看大图",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .pointerInput(path) {
                        detectTapGestures(onLongPress = { showSaveDialog = true })
                    }
                    .pointerInput(path) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = newScale
                            offset = if (newScale == 1f) {
                                Offset.Zero
                            } else {
                                offset + pan
                            }
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit
            )
            CbIconButton(
                Icons.Default.Close,
                "关闭大图",
                onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                tint = Color.White
            )
        }
    }
}

private fun saveImageToGallery(context: android.content.Context, path: String) {
    try {
        val sourceFile = File(path)
        if (!sourceFile.exists()) {
            Toast.makeText(context, "图片文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = sourceFile.name
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ChatBar")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    sourceFile.inputStream().use { input -> input.copyTo(output) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                Toast.makeText(context, "已保存到 Pictures/ChatBar", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ChatBar")
            dir.mkdirs()
            val target = File(dir, fileName)
            sourceFile.copyTo(target, overwrite = true)
            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(target)))
            Toast.makeText(context, "已保存到 Pictures/ChatBar", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun ChatComposer(
    input: TextFieldValue,
    onInput: (TextFieldValue) -> Unit,
    responding: Boolean,
    enabled: Boolean,
    onImage: () -> Unit,
    onFull: () -> Unit,
    onSend: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(ChatBarTheme.colors.card)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CbIconButton(Icons.Default.AddPhotoAlternate, "添加图片", onImage, enabled = enabled, tint = ChatBarTheme.colors.primary)
        CbIconButton(Icons.Default.OpenInFull, "全屏编辑", onFull, enabled = enabled && !responding, tint = ChatBarTheme.colors.primary)
        CbInput(input, onInput, Modifier.weight(1f).heightIn(min = 44.dp, max = 104.dp), placeholder = if (enabled) "发送消息…" else "本对话已封存", enabled = enabled, minLines = 1)
        Spacer(Modifier.width(8.dp))
        CbIconButton(
            Icons.Default.Send,
            "发送",
            onSend,
            modifier = Modifier.background(ChatBarTheme.colors.primary, CircleShape),
            enabled = enabled && !responding,
            tint = ChatBarTheme.colors.primaryForeground
        )
    }
}

@Composable
private fun ImageStrip(images: List<String>, onRemove: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(ChatBarTheme.colors.card).horizontalScroll(rememberScrollState()).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        images.forEach { path ->
            Box(Modifier.size(60.dp).clip(RoundedCornerShape(8.dp))) {
                AsyncImage(File(path), "待发图片", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.align(Alignment.TopEnd).size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable { onRemove(path) }, contentAlignment = Alignment.Center) {
                    CbIcon(Icons.Default.Close, "删除图片", Modifier.size(14.dp), Color.White)
                }
            }
        }
    }
}

@Composable
private fun MessageEditFullScreen(
    title: String,
    text: TextFieldValue,
    images: List<String>,
    onTextChange: (TextFieldValue) -> Unit,
    onAddImage: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    saveIcon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Box(Modifier.fillMaxSize().background(ChatBarTheme.colors.background)) {
                Column(Modifier.fillMaxSize().padding(16.dp).padding(bottom = 64.dp)) {
            CbText(title, style = ChatBarTheme.typography.title)
            Spacer(Modifier.size(12.dp))
            if (images.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    images.forEach { path ->
                        Box(Modifier.size(96.dp).clip(RoundedCornerShape(8.dp))) {
                            AsyncImage(File(path), "消息图片", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Box(Modifier.align(Alignment.TopEnd).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable { onRemoveImage(path) }, contentAlignment = Alignment.Center) {
                                CbIcon(Icons.Default.Close, "删除图片", Modifier.size(16.dp), Color.White)
                            }
                        }
                    }
                }
            }
            CbInput(text, onTextChange, Modifier.fillMaxWidth().weight(1f), placeholder = "输入消息…", minLines = 8)
        }
        CbIconButton(Icons.Default.Close, "退出", onCancel, Modifier.align(Alignment.BottomStart).padding(16.dp).size(56.dp).background(ChatBarTheme.colors.card, CircleShape))
        Row(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CbIconButton(Icons.Default.AddPhotoAlternate, "插入图片", onAddImage, Modifier.size(56.dp).background(ChatBarTheme.colors.card, CircleShape), tint = ChatBarTheme.colors.primary)
            CbIconButton(saveIcon, "保存", onSave, Modifier.size(56.dp).background(ChatBarTheme.colors.primary, CircleShape), enabled = text.text.isNotBlank() || images.isNotEmpty(), tint = ChatBarTheme.colors.primaryForeground)
        }
    }
}
