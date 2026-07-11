package com.example.chatbar.ui.chat

import com.example.chatbar.ui.kit.AppIcons

import android.net.Uri
import android.content.Intent
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.chatbar.ChatBarApp
import com.example.chatbar.DebugConfig
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.PlayerSetting
import com.example.chatbar.domain.chat.PlaceholderRenderer
import com.example.chatbar.ui.components.ChatBubble
import com.example.chatbar.ui.components.ChatBubbleCharacterAvatar
import com.example.chatbar.ui.components.ChatBubbleSegmentAction
import com.example.chatbar.ui.components.ImagePreviewDialog
import com.example.chatbar.ui.components.TypingIndicator
import com.example.chatbar.ui.components.saveImageToGallery
import com.example.chatbar.ui.components.shareImage
import com.example.chatbar.domain.chat.roleplayScreenshotBlockIds
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbSpinner
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.kit.FullscreenTextEditor
import java.io.File
import kotlinx.coroutines.CancellationException
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
    val assistantSegmentedBubblesEnabled by viewModel.assistantSegmentedBubblesEnabled.collectAsState()
    val modelConfigurationErrors by viewModel.modelConfigurationErrors.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val isResponding by viewModel.isResponding.collectAsState()
    val streamingMessage by viewModel.streamingMessage.collectAsState()
    val deletingMemory by viewModel.isDeletingMemory.collectAsState()
    val contextWindowSize by viewModel.contextWindowSize.collectAsState()
    val novelAiConfigured by viewModel.novelAiConfigured.collectAsState()
    val imageGeneration by viewModel.imageGeneration.collectAsState()
    val showBatteryOptimizationHint by viewModel.showBatteryOptimizationHint.collectAsState()
    val draftInput by viewModel.draftInput.collectAsState()
    val appSettings by ChatBarApp.instance.settingsRepository.appSettings.collectAsState(initial = AppSettings())
    val playerSetting by ChatBarApp.instance.settingsRepository.playerSetting.collectAsState(initial = PlayerSetting())
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val rootView = LocalView.current

    var input by remember(sessionId) { mutableStateOf(TextFieldValue("")) }
    val selectedImages = remember { mutableStateListOf<String>() }
    var settingsOpen by remember { mutableStateOf(false) }
    var debugOpen by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var fullComposer by remember { mutableStateOf(false) }
    var actionMessageId by remember { mutableStateOf<String?>(null) }
    var actionSegment by remember { mutableStateOf<ChatBubbleSegmentAction?>(null) }
    var expandedImagePath by remember { mutableStateOf<String?>(null) }
    var deleteImageTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleteSegmentTarget by remember { mutableStateOf<ChatBubbleSegmentAction?>(null) }
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var editingText by remember { mutableStateOf(TextFieldValue("")) }
    val editingImages = remember { mutableStateListOf<String>() }
    var editingSegment by remember { mutableStateOf<ChatBubbleSegmentAction?>(null) }
    var editingSegmentText by remember { mutableStateOf(TextFieldValue("")) }
    var viewportAnchor by remember { mutableStateOf(0 to 0) }
    var restoringViewport by remember { mutableStateOf(false) }
    var initialScrollDone by remember(sessionId) { mutableStateOf(false) }
    var screenshotSelectionMode by remember(sessionId) { mutableStateOf(false) }
    var selectedScreenshotBlockIds by remember(sessionId) { mutableStateOf<Set<String>>(emptySet()) }
    var screenshotHeightPx by remember(sessionId) { mutableStateOf(0) }
    var screenshotHeightMeasuring by remember(sessionId) { mutableStateOf(false) }
    var screenshotGenerating by remember(sessionId) { mutableStateOf(false) }
    var screenshotPreviewPath by remember(sessionId) { mutableStateOf<String?>(null) }
    var screenshotPreviewName by remember(sessionId) { mutableStateOf<String?>(null) }
    var expandedStatusBlockIds by remember(sessionId) { mutableStateOf<Set<String>>(emptySet()) }
    var imagePromptTargetId by remember(sessionId) { mutableStateOf<String?>(null) }
    var imageContentHintDraft by remember(sessionId) { mutableStateOf("") }
    var imagePromptPreferenceDraft by remember(sessionId) { mutableStateOf("") }

    LaunchedEffect(draftInput) {
        if (input.text != draftInput) {
            input = TextFieldValue(draftInput, selection = TextRange(draftInput.length))
        }
    }
    
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

    suspend fun scrollToPreviousMessage() {
        val messageCount = messages.size
        if (messageCount <= 0) return
        val firstVisible = listState.firstVisibleItemIndex
        val lastMessageIndex = messageCount - 1
        val target = when {
            firstVisible > lastMessageIndex -> lastMessageIndex
            firstVisible > 0 -> firstVisible - 1
            listState.firstVisibleItemScrollOffset > 0 -> 0
            else -> 0
        }
        listState.animateScrollToItem(target)
    }

    suspend fun scrollToFirstMessage() {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    val chatImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { viewModel.copyUriToLocalFile(it) { path -> selectedImages.add(path) } }
    }
    val editImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { viewModel.copyUriToLocalFile(it) { path -> editingImages.add(path) } }
    }

    BackHandler(enabled = editingMessage != null) { editingMessage = null; editingImages.clear() }
    BackHandler(enabled = editingSegment != null) { editingSegment = null }
    BackHandler(enabled = fullComposer) { fullComposer = false }
    BackHandler(enabled = screenshotPreviewPath != null) {
        screenshotPreviewPath = null
        screenshotPreviewName = null
    }
    BackHandler(enabled = screenshotSelectionMode && screenshotPreviewPath == null) {
        screenshotSelectionMode = false
        selectedScreenshotBlockIds = emptySet()
    }

    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            info.totalItemsCount == 0 || (last != null && last.index == info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 16)
        }
    }
    val canJumpToEarlier by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    val latestComplete = !isResponding && streamingMessage == null && messages.isNotEmpty()
    val backgroundPath = session?.chatBackground ?: characterCard?.chatBackground
    val renderPlayerName = session?.playerName?.takeIf { it.isNotBlank() }
        ?: playerSetting.playerName.takeIf { it.isNotBlank() }
    val renderBotName = characterCard?.name ?: session?.title ?: ""
    val botAvatarPath = characterCard?.avatar
    val characterAvatars = remember(characterCard?.characters) {
        characterCard?.characters.orEmpty().map { character ->
            ChatBubbleCharacterAvatar(character.name, character.appearanceImage)
        }
    }
    val renderedTitle = PlaceholderRenderer.render(
        session?.title ?: characterCard?.name ?: "聊天",
        renderPlayerName,
        renderBotName
    )
    val alternativeIds = remember(messages, contextWindowSize) {
        messages.takeLast(contextWindowSize.coerceAtLeast(1)).filter { it.role == MessageRole.ASSISTANT && it.alternatives.size > 1 }.map { it.id }.toSet()
    }
    val regenerableId = latestRegenerableAssistantMessageId(messages)
    val imageGenerationRunning = imageGeneration?.isTerminal == false
    val imageGenerationAnchorExists = remember(messages, imageGeneration?.anchorMessageId) {
        val anchorId = imageGeneration?.anchorMessageId
        anchorId != null && messages.any { it.id == anchorId }
    }
    val selectableScreenshotIds = remember(messages, assistantSegmentedBubblesEnabled) {
        messages.flatMap { message ->
            roleplayScreenshotBlockIds(message, assistantSegmentedBubblesEnabled)
        }.toSet()
    }
    val screenshotHeightLimitReached = screenshotHeightPx >= CHAT_LONG_SCREENSHOT_SELECTION_HEIGHT_LIMIT_PX

    fun screenshotWidthPx(): Int =
        rootView.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels

    fun screenshotMessages(ids: Set<String>): List<ChatMessage> =
        orderedChatScreenshotMessages(messages, ids, assistantSegmentedBubblesEnabled)

    suspend fun measureScreenshotHeight(ids: Set<String>): Int {
        val screenshotMessages = screenshotMessages(ids)
        if (screenshotMessages.isEmpty()) return 0
        return measureChatLongScreenshotHeight(
            context = context,
            request = ChatLongScreenshotRequest(
                title = renderedTitle,
                messages = screenshotMessages,
                backgroundPath = backgroundPath,
                widthPx = screenshotWidthPx(),
                fontScale = bubbleFontScale,
                fileName = "measure.png",
                playerName = renderPlayerName,
                botName = renderBotName,
                botAvatarPath = botAvatarPath,
                characterAvatars = characterAvatars,
                assistantSegmentedBubblesEnabled = assistantSegmentedBubblesEnabled,
                selectedBlockIds = ids,
                expandedStatusBlockIds = expandedStatusBlockIds
            )
        )
    }

    fun showScreenshotHeightLimitToast(heightPx: Int) {
        Toast.makeText(
            context,
            "已选高度 ${heightPx}px 超过上限 ${CHAT_LONG_SCREENSHOT_SELECTION_HEIGHT_LIMIT_PX}px，请少选一点",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun applyScreenshotSelectionAfterHeightCheck(nextIds: Set<String>) {
        if (nextIds.isEmpty()) {
            selectedScreenshotBlockIds = emptySet()
            screenshotHeightPx = 0
            return
        }
        scope.launch {
            screenshotHeightMeasuring = true
            try {
                val height = measureScreenshotHeight(nextIds)
                if (height > CHAT_LONG_SCREENSHOT_SELECTION_HEIGHT_LIMIT_PX) {
                    showScreenshotHeightLimitToast(height)
                } else {
                    screenshotSelectionMode = true
                    selectedScreenshotBlockIds = nextIds
                    screenshotHeightPx = height
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Toast.makeText(context, "测量长截图高度失败: ${error.message}", Toast.LENGTH_SHORT).show()
            } finally {
                screenshotHeightMeasuring = false
            }
        }
    }

    fun toggleScreenshotSelection(blockId: String) {
        val nextIds = toggleChatScreenshotSelection(
            currentIds = selectedScreenshotBlockIds,
            blockId = blockId,
            selectableIds = selectableScreenshotIds
        )
        if (blockId in selectedScreenshotBlockIds) {
            selectedScreenshotBlockIds = nextIds
            if (nextIds.isEmpty()) screenshotHeightPx = 0
        } else {
            applyScreenshotSelectionAfterHeightCheck(nextIds)
        }
    }

    fun enterScreenshotSelection(blockId: String) {
        if (blockId !in selectableScreenshotIds) {
            Toast.makeText(context, "这一段不能加入长截图", Toast.LENGTH_SHORT).show()
            return
        }
        applyScreenshotSelectionAfterHeightCheck(setOf(blockId))
    }

    fun exitScreenshotSelection() {
        screenshotSelectionMode = false
        selectedScreenshotBlockIds = emptySet()
        screenshotHeightPx = 0
        screenshotHeightMeasuring = false
        screenshotPreviewPath = null
        screenshotPreviewName = null
    }

    fun previewLongScreenshot() {
        val selectedMessages = orderedChatScreenshotMessages(
            messages,
            selectedScreenshotBlockIds,
            assistantSegmentedBubblesEnabled
        )
        if (selectedMessages.isEmpty()) {
            Toast.makeText(context, "请选择至少一个片段", Toast.LENGTH_SHORT).show()
            return
        }
        val selectedScreenshotMessages = screenshotMessages(selectedScreenshotBlockIds)
        val widthPx = screenshotWidthPx()
        val fileName = buildChatScreenshotFileName(renderedTitle)
        scope.launch {
            screenshotGenerating = true
            try {
                val measuredHeight = measureScreenshotHeight(selectedScreenshotBlockIds)
                screenshotHeightPx = measuredHeight
                if (measuredHeight > CHAT_LONG_SCREENSHOT_SELECTION_HEIGHT_LIMIT_PX) {
                    showScreenshotHeightLimitToast(measuredHeight)
                    return@launch
                }
                val file = renderChatLongScreenshot(
                    context = context,
                    request = ChatLongScreenshotRequest(
                        title = renderedTitle,
                        messages = selectedScreenshotMessages,
                        backgroundPath = backgroundPath,
                        widthPx = widthPx,
                        fontScale = bubbleFontScale,
                        fileName = fileName,
                        playerName = renderPlayerName,
                        botName = renderBotName,
                        botAvatarPath = botAvatarPath,
                        characterAvatars = characterAvatars,
                        assistantSegmentedBubblesEnabled = assistantSegmentedBubblesEnabled,
                        selectedBlockIds = selectedScreenshotBlockIds,
                        expandedStatusBlockIds = expandedStatusBlockIds
                    )
                )
                screenshotPreviewPath = file.absolutePath
                screenshotPreviewName = file.name
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Toast.makeText(context, "长截图生成失败: ${error.message}", Toast.LENGTH_SHORT).show()
            } finally {
                screenshotGenerating = false
            }
        }
    }

    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress) {
        if (!restoringViewport) viewportAnchor = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
    }
    LaunchedEffect(messages, assistantSegmentedBubblesEnabled) {
        val cleaned = cleanChatScreenshotSelection(
            selectedScreenshotBlockIds,
            messages,
            assistantSegmentedBubblesEnabled
        )
        if (cleaned != selectedScreenshotBlockIds) {
            selectedScreenshotBlockIds = cleaned
            if (cleaned.isEmpty()) screenshotSelectionMode = false
        }
    }
    LaunchedEffect(
        screenshotSelectionMode,
        selectedScreenshotBlockIds,
        messages,
        renderedTitle,
        backgroundPath,
        bubbleFontScale,
        renderPlayerName,
        renderBotName,
        botAvatarPath,
        characterAvatars,
        assistantSegmentedBubblesEnabled,
        expandedStatusBlockIds
    ) {
        if (!screenshotSelectionMode || selectedScreenshotBlockIds.isEmpty()) {
            screenshotHeightPx = 0
            return@LaunchedEffect
        }
        screenshotHeightMeasuring = true
        try {
            screenshotHeightPx = measureScreenshotHeight(selectedScreenshotBlockIds)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Toast.makeText(context, "测量长截图高度失败: ${error.message}", Toast.LENGTH_SHORT).show()
        } finally {
            screenshotHeightMeasuring = false
        }
    }
    LaunchedEffect(messages.size) {
        if (!initialScrollDone && messages.isNotEmpty()) {
            initialScrollDone = true; restoringViewport = true
            scrollToBottom(animated = false, expectedItemCount = messages.size)
            viewportAnchor = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            restoringViewport = false
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
        FullscreenTextEditor(
            title = "编辑消息",
            value = editingText,
            onValueChange = { editingText = it },
            images = editingImages,
            onAddImage = { editImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onRemoveImage = { editingImages.remove(it) },
            onDismiss = { editingMessage = null; editingImages.clear() },
            onConfirm = {
                editingMessage?.let { viewModel.editMessage(it.id, editingText.text, editingImages.toList()) }
                editingMessage = null; editingImages.clear()
            },
            confirmIcon = AppIcons.Save,
            visible = true
        )
        return
    }
    if (editingSegment != null) {
        FullscreenTextEditor(
            title = "编辑片段",
            value = editingSegmentText,
            onValueChange = { editingSegmentText = it },
            images = emptyList(),
            onDismiss = { editingSegment = null },
            onConfirm = {
                editingSegment?.let {
                    viewModel.editMessageSegment(
                        messageId = it.messageId,
                        start = it.start,
                        endExclusive = it.endExclusive,
                        replacement = editingSegmentText.text
                    )
                }
                editingSegment = null
            },
            confirmIcon = AppIcons.Save,
            visible = true
        )
        return
    }
    if (fullComposer && !isArchived) {
        FullscreenTextEditor(
            title = "撰写消息",
            value = input,
            onValueChange = {
                input = it
                viewModel.updateDraftInput(it.text)
            },
            images = selectedImages,
            onAddImage = { chatImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onRemoveImage = { selectedImages.remove(it) },
            onDismiss = { fullComposer = false },
            onConfirm = {
                if (viewModel.sendMessage(input.text, selectedImages.toList())) {
                    input = TextFieldValue(""); selectedImages.clear(); fullComposer = false
                }
            },
            confirmIcon = AppIcons.Send,
            visible = true
        )
        return
    }

    Column(modifier.fillMaxSize().background(ChatBarTheme.colors.background)) {
        CbTopBar(
            title = renderedTitle,
            navigation = { CbIconButton(AppIcons.ArrowBack, "返回", onBack) },
            actions = {
                if (DebugConfig.SHOW_DEBUG_UI) CbIconButton(AppIcons.BugReport, "调试日志", { debugOpen = true }, tint = ChatBarTheme.colors.primary)
                CbIconButton(AppIcons.Tune, "会话设置", { settingsOpen = true })
            }
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!backgroundPath.isNullOrBlank()) {
                AsyncImage(
                    model = File(backgroundPath),
                    contentDescription = "聊天背景",
                    modifier = Modifier.fillMaxSize().alpha(appSettings.chatBackgroundImageOpacity),
                    contentScale = ContentScale.Crop
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(messages, key = { it.id }) { message ->
                    val selectableForScreenshot = message.isSelectableForChatScreenshot(assistantSegmentedBubblesEnabled)
                    val messageBlockIds = remember(message, assistantSegmentedBubblesEnabled) {
                        roleplayScreenshotBlockIds(message, assistantSegmentedBubblesEnabled)
                    }
                    val selectedForScreenshot = messageBlockIds.any { it in selectedScreenshotBlockIds }
                    val screenshotSelectionEnabled = selectedForScreenshot ||
                        (!screenshotHeightMeasuring && !screenshotHeightLimitReached)
                    ChatBubble(
                        message = message,
                        fontScale = bubbleFontScale,
                        renderPlayerName = renderPlayerName,
                        renderBotName = renderBotName,
                        botAvatarPath = botAvatarPath,
                        characterAvatars = characterAvatars,
                        assistantSegmentedBubblesEnabled = assistantSegmentedBubblesEnabled,
                        onLongPress = { if (!isResponding && !screenshotSelectionMode) actionMessageId = message.id },
                        onSegmentLongPress = if (!isResponding && !screenshotSelectionMode) ({ segment -> actionSegment = segment }) else null,
                        onImageClick = if (screenshotSelectionMode) null else ({ path -> expandedImagePath = path }),
                        onImageLongPress = { path ->
                            if (!isResponding && !screenshotSelectionMode) deleteImageTarget = message.id to path
                        },
                        onPreviousAlternative = if (!screenshotSelectionMode && message.id in alternativeIds) ({ viewModel.switchAssistantAlternative(message.id, -1) }) else null,
                        onNextAlternative = if (!screenshotSelectionMode && message.id in alternativeIds) ({ viewModel.switchAssistantAlternative(message.id, 1) }) else null,
                        onGenerateImage = if (
                            !screenshotSelectionMode &&
                            novelAiConfigured &&
                            !isArchived &&
                            isModelUsable &&
                            message.role == MessageRole.ASSISTANT &&
                            message.displayContent.isNotBlank()
                        ) ({
                            imagePromptTargetId = message.id
                            imageContentHintDraft = ""
                            imagePromptPreferenceDraft = session?.imagePromptPreference.orEmpty()
                        }) else null,
                        onGenerateImageLongPress = if (
                            !screenshotSelectionMode &&
                            novelAiConfigured &&
                            !isArchived &&
                            isModelUsable &&
                            message.role == MessageRole.ASSISTANT &&
                            message.displayContent.isNotBlank()
                        ) ({ viewModel.generateNovelAiImage(message.id) }) else null,
                        imageGenerationEnabled = !imageGenerationRunning,
                        selectionMode = screenshotSelectionMode && selectableForScreenshot,
                        selected = selectedForScreenshot,
                        selectionEnabled = screenshotSelectionEnabled,
                        onToggleSelected = null,
                        selectedBlockIds = selectedScreenshotBlockIds,
                        onToggleBlockSelected = if (selectableForScreenshot) ({ blockId -> toggleScreenshotSelection(blockId) }) else null,
                        expandedStatusBlockIds = expandedStatusBlockIds,
                        onStatusExpandedChange = { blockId, expanded ->
                            expandedStatusBlockIds = if (expanded) {
                                expandedStatusBlockIds + blockId
                            } else {
                                expandedStatusBlockIds - blockId
                            }
                        },
                        showActions = !screenshotSelectionMode
                    )
                    imageGeneration?.takeIf { it.anchorMessageId == message.id }?.let { generation ->
                        NovelAiGenerationCard(generation,
                            onRetry = {
                                viewModel.generateNovelAiImage(
                                    anchorMessageId = generation.anchorMessageId,
                                    imageContentHint = generation.imageContentHint,
                                    imagePromptPreference = generation.finalPromptRequirement
                                )
                            },
                            onDismiss = { viewModel.dismissNovelAiImageGeneration() },
                            onCancel = { viewModel.cancelNovelAiImageGeneration() }
                        )
                    }
                }
                streamingMessage?.let { message ->
                    item(key = "streaming-${message.id}") {
                        if (message.content == "..." && message.reasoningContent.isNullOrBlank()) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                CbSurface(color = ChatBarTheme.colors.card, shape = RoundedCornerShape(10.dp, 10.dp, 10.dp, 3.dp)) { TypingIndicator() }
                            }
                        } else {
                            ChatBubble(
                                message = message,
                                fontScale = bubbleFontScale,
                                renderPlayerName = renderPlayerName,
                                renderBotName = renderBotName,
                                botAvatarPath = botAvatarPath,
                                characterAvatars = characterAvatars,
                                assistantSegmentedBubblesEnabled = assistantSegmentedBubblesEnabled,
                                showActions = false
                            )
                        }
                    }
                }
                imageGeneration?.takeUnless { imageGenerationAnchorExists }?.let { generation ->
                    item(key = "novelai-generation") {
                        NovelAiGenerationCard(generation,
                            onRetry = {
                                viewModel.generateNovelAiImage(
                                    anchorMessageId = generation.anchorMessageId,
                                    imageContentHint = generation.imageContentHint,
                                    imagePromptPreference = generation.finalPromptRequirement
                                )
                            },
                            onDismiss = { viewModel.dismissNovelAiImageGeneration() },
                            onCancel = { viewModel.cancelNovelAiImageGeneration() }
                        )
                    }
                }
                if (isResponding && streamingMessage == null) item(key = "typing") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        CbSurface(color = ChatBarTheme.colors.card, shape = RoundedCornerShape(10.dp, 10.dp, 10.dp, 3.dp)) { TypingIndicator() }
                    }
                }
            }
            if (messages.isNotEmpty()) {
                ChatJumpControls(
                    canJump = canJumpToEarlier,
                    onPreviousMessage = { scope.launch { scrollToPreviousMessage() } },
                    onFirstMessage = { scope.launch { scrollToFirstMessage() } },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 8.dp)
                )
            }
            if (!isAtBottom) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(16.dp).size(48.dp).clip(CircleShape).background(ChatBarTheme.colors.primary).clickable {
                        scope.launch { scrollToBottom(animated = true) }
                    },
                    contentAlignment = Alignment.Center
                ) {
                    CbIcon(AppIcons.KeyboardArrowDown, "跳到底部", tint = ChatBarTheme.colors.primaryForeground)
                    if (latestComplete) {
                        Box(Modifier.align(Alignment.TopEnd).size(16.dp).clip(CircleShape).background(ChatBarTheme.colors.success), contentAlignment = Alignment.Center) {
                            CbIcon(AppIcons.Check, "最新消息已完成", Modifier.size(11.dp), Color.White)
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
        if (screenshotSelectionMode) {
            ChatScreenshotSelectionBar(
                selectedHeightPx = screenshotHeightPx,
                heightLimitPx = CHAT_LONG_SCREENSHOT_SELECTION_HEIGHT_LIMIT_PX,
                generating = screenshotGenerating,
                onPreview = { previewLongScreenshot() },
                onCancel = { exitScreenshotSelection() },
                previewEnabled = selectedScreenshotBlockIds.isNotEmpty() &&
                    screenshotHeightPx in 1..CHAT_LONG_SCREENSHOT_SELECTION_HEIGHT_LIMIT_PX &&
                    !screenshotHeightMeasuring
            )
        } else {
            if (selectedImages.isNotEmpty()) ImageStrip(selectedImages, { selectedImages.remove(it) })
            ChatComposer(
                input = input,
                onInput = {
                    input = it
                    viewModel.updateDraftInput(it.text)
                },
                responding = isResponding,
                enabled = !isArchived && isModelUsable,
                onImage = { chatImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onFull = { fullComposer = true },
                onCancel = viewModel::cancelResponseGeneration,
                onSend = {
                    if (viewModel.sendMessage(input.text, selectedImages.toList())) {
                        input = TextFieldValue(""); selectedImages.clear()
                    }
                }
            )
        }
    }

    imagePromptTargetId?.let { targetId ->
        CbDialog(
            onDismissRequest = { imagePromptTargetId = null },
            title = "生图要求",
            dismiss = {
                CbButton(
                    "取消",
                    { imagePromptTargetId = null },
                    variant = ButtonVariant.Ghost
                )
            },
            confirm = {
                CbButton(
                    "生图",
                    {
                        val contentHint = imageContentHintDraft
                        val preference = imagePromptPreferenceDraft
                        imagePromptTargetId = null
                        viewModel.generateNovelAiImage(
                            anchorMessageId = targetId,
                            imageContentHint = contentHint,
                            imagePromptPreference = preference,
                            persistPreference = true
                        )
                    },
                    enabled = !imageGenerationRunning
                )
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CbText(
                    "长按消息旁的生图按钮可直接生图，并会使用当前会话保存的生图偏好；图片内容提示只影响本次点击生成。",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
                CbField("图片内容提示") {
                    CbInput(
                        value = imageContentHintDraft,
                        onValueChange = { imageContentHintDraft = it },
                        placeholder = "本次画面要额外强调的场景、镜头、构图或取舍",
                        enabled = !imageGenerationRunning,
                        singleLine = false,
                        minLines = 3
                    )
                }
                CbField("生图偏好") {
                    CbInput(
                        value = imagePromptPreferenceDraft,
                        onValueChange = { imagePromptPreferenceDraft = it },
                        placeholder = "对最终 NovelAI Prompt 的格式、标签、权重或风格要求",
                        enabled = !imageGenerationRunning,
                        singleLine = false,
                        minLines = 3
                    )
                }
            }
        }
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
        ImagePreviewDialog(
            path = path,
            onDismiss = { expandedImagePath = null },
            onSetCardAvatar = if (!isArchived && characterCard != null) ({
                viewModel.replaceCharacterCardAvatarFromImage(path) { _, message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }) else null,
            onSetCardBackground = if (!isArchived && characterCard != null) ({
                viewModel.replaceCharacterCardBackgroundFromImage(path) { _, message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }) else null
        )
    }
    screenshotPreviewPath?.let { path ->
        ChatLongScreenshotPreviewDialog(
            path = path,
            onDismiss = {
                screenshotPreviewPath = null
                screenshotPreviewName = null
            },
            onSave = { saveImageToGallery(context, path, screenshotPreviewName) },
            onShare = { shareImage(context, path, "分享长截图") }
        )
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
    deleteSegmentTarget?.let { segment ->
        CbDialog(
            onDismissRequest = { deleteSegmentTarget = null },
            title = "删除此段",
            dismiss = {
                CbButton("取消", { deleteSegmentTarget = null }, variant = ButtonVariant.Ghost)
            },
            confirm = {
                CbButton(
                    "删除",
                    {
                        viewModel.editMessageSegment(
                            messageId = segment.messageId,
                            start = segment.start,
                            endExclusive = segment.endExclusive,
                            replacement = ""
                        )
                        deleteSegmentTarget = null
                    },
                    variant = ButtonVariant.Destructive
                )
            }
        ) {
            CbText("只删除这个片段，不会删除同条消息的其他片段或图片。", color = ChatBarTheme.colors.mutedForeground)
        }
    }
    actionSegment?.let { segment ->
        val target = messages.find { it.id == segment.messageId }
        val canRegenerate = target?.id == regenerableId && !isResponding
        CbDialog(
            onDismissRequest = { actionSegment = null },
            title = "片段操作",
            dismiss = { CbButton("关闭", { actionSegment = null }, variant = ButtonVariant.Ghost) }
        ) {
            CbButton("复制此段", {
                clipboardManager.setText(AnnotatedString(segment.copyText))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                actionSegment = null
            }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Secondary)
            Spacer(Modifier.size(8.dp))
            CbButton("编辑此段", {
                editingSegment = segment
                editingSegmentText = TextFieldValue(segment.rawText, selection = TextRange(segment.rawText.length))
                actionSegment = null
            }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
            Spacer(Modifier.size(8.dp))
            CbButton("加入长截图", {
                enterScreenshotSelection(segment.blockId)
                actionSegment = null
            }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
            Spacer(Modifier.size(8.dp))
            CbButton("删除此段", {
                deleteSegmentTarget = segment
                actionSegment = null
            }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Destructive)
            if (canRegenerate) {
                Spacer(Modifier.size(8.dp))
                CbButton("重新生成整条回复", {
                    actionSegment = null
                    viewModel.regenerateLastResponse()
                }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
            }
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
            target?.takeIf { it.isSelectableForChatScreenshot(assistantSegmentedBubblesEnabled) }?.let {
                Spacer(Modifier.size(8.dp))
                CbButton("多选", {
                    roleplayScreenshotBlockIds(it, assistantSegmentedBubblesEnabled)
                        .firstOrNull()
                        ?.let(::enterScreenshotSelection)
                    actionMessageId = null
                }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
            }
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
private fun ChatJumpControls(
    canJump: Boolean,
    onPreviousMessage: () -> Unit,
    onFirstMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    CbSurface(
        modifier = modifier,
        color = ChatBarTheme.colors.card.copy(alpha = 0.48f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, ChatBarTheme.colors.border.copy(alpha = 0.46f))
    ) {
        Row(Modifier.padding(1.dp), verticalAlignment = Alignment.CenterVertically) {
            ChatJumpIconButton(
                imageVector = AppIcons.ArrowUp,
                contentDescription = "跳到上一条消息",
                enabled = canJump,
                onClick = onPreviousMessage
            )
            ChatJumpIconButton(
                imageVector = AppIcons.ArrowUpToLine,
                contentDescription = "跳到第一条消息",
                enabled = canJump,
                onClick = onFirstMessage
            )
        }
    }
}

@Composable
private fun ChatJumpIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val tint = if (enabled) {
        ChatBarTheme.colors.mutedForeground
    } else {
        ChatBarTheme.colors.mutedForeground.copy(alpha = 0.36f)
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(5.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        CbIcon(imageVector, contentDescription, Modifier.size(15.dp), tint)
    }
}

@Composable
private fun ChatScreenshotSelectionBar(
    selectedHeightPx: Int,
    heightLimitPx: Int,
    generating: Boolean,
    onPreview: () -> Unit,
    onCancel: () -> Unit,
    previewEnabled: Boolean
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(ChatBarTheme.colors.card)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (generating) CbSpinner(Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            CbText(
                "已选高度 ${formatScreenshotHeightPx(selectedHeightPx)} / ${formatScreenshotHeightPx(heightLimitPx)}",
                style = ChatBarTheme.typography.label
            )
            if (selectedHeightPx >= heightLimitPx) {
                CbText("已达高度上限", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
            }
        }
        CbButton(
            "取消",
            onCancel,
            enabled = !generating,
            variant = ButtonVariant.Ghost
        )
        CbButton(
            "预览长截图",
            onPreview,
            enabled = previewEnabled && !generating
        )
    }
}

private fun formatScreenshotHeightPx(heightPx: Int): String = "${heightPx.coerceAtLeast(0)}px"

@Composable
private fun ChatLongScreenshotPreviewDialog(
    path: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = File(path),
                contentDescription = "长截图预览",
                modifier = Modifier.fillMaxSize().padding(bottom = 76.dp),
                contentScale = ContentScale.Fit
            )
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(ChatBarTheme.colors.card)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CbButton("保存", onSave, modifier = Modifier.weight(1f))
                CbButton("分享", onShare, modifier = Modifier.weight(1f), variant = ButtonVariant.Secondary)
                CbButton("关闭", onDismiss, modifier = Modifier.weight(1f), variant = ButtonVariant.Ghost)
            }
        }
    }
}

@Composable
private fun NovelAiGenerationCard(
    state: ImageGenerationState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit
) {
    val label = when (state.phase) {
        ImageGenerationPhase.DESIGNING -> "正在设计 NovelAI Prompt"
        ImageGenerationPhase.GENERATING -> "NovelAI 正在生成"
        ImageGenerationPhase.STREAMING -> "流式预览 ${(state.progress * 100).toInt()}%"
        ImageGenerationPhase.SAVING -> "正在保存图片"
        ImageGenerationPhase.CANCELLED -> "已停止生图"
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
                if (!state.isTerminal) CbSpinner(Modifier.size(18.dp))
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
            if (state.isCancellable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbButton("停止", onCancel, variant = ButtonVariant.Outline)
                }
            }
            if (state.isTerminal) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbButton("重试", onRetry, variant = ButtonVariant.Outline)
                    CbButton("关闭", onDismiss, variant = ButtonVariant.Ghost)
                }
            }
        }
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
    onCancel: () -> Unit,
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
        CbIconButton(AppIcons.AddPhotoAlternate, "添加图片", onImage, enabled = enabled && !responding, tint = ChatBarTheme.colors.primary)
        CbIconButton(AppIcons.OpenInFull, "全屏编辑", onFull, enabled = enabled && !responding, tint = ChatBarTheme.colors.primary)
        CbInput(input, onInput, Modifier.weight(1f).heightIn(min = 44.dp, max = 104.dp), placeholder = if (enabled) "发送消息…" else "本对话已封存", enabled = enabled, minLines = 1)
        Spacer(Modifier.width(8.dp))
        CbIconButton(
            if (responding) AppIcons.Close else AppIcons.Send,
            if (responding) "中断生成" else "发送",
            if (responding) onCancel else onSend,
            modifier = Modifier.background(if (responding) ChatBarTheme.colors.destructive else ChatBarTheme.colors.primary, CircleShape),
            enabled = enabled || responding,
            tint = if (responding) ChatBarTheme.colors.destructiveForeground else ChatBarTheme.colors.primaryForeground
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
                    CbIcon(AppIcons.Close, "删除图片", Modifier.size(14.dp), Color.White)
                }
            }
        }
    }
}

