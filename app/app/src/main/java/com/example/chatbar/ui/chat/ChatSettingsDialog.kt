package com.example.chatbar.ui.chat

import com.example.chatbar.ui.kit.AppIcons

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.rememberCoroutineScope
import coil.compose.AsyncImage
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.MemoryHead
import com.example.chatbar.data.local.entity.MemoryBackfillStatus
import com.example.chatbar.data.local.entity.MemoryAuthor
import com.example.chatbar.data.local.entity.MemoryDecisionTier
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryRevisionOperation
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryUpdateStatus
import com.example.chatbar.data.local.entity.PlayerSetting
import com.example.chatbar.data.local.entity.SaveSlotSummary
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.VectorChunk
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.domain.chat.PlaceholderRenderer
import com.example.chatbar.domain.memory.MemoryTimelinePolicy
import com.example.chatbar.domain.memory.MemoryBackfillPhase
import com.example.chatbar.domain.rag.ChatMemoryIndexPolicy
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbChoiceChip
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbDivider
import com.example.chatbar.ui.kit.CbDirtySaveButton
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbSelect
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbSwitch
import com.example.chatbar.ui.kit.CbTabs
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.kit.FullscreenTextEditor
import com.example.chatbar.ui.kit.swipeToAdjacentTab
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun ChatSettingsDialog(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onClearHistory: () -> Unit,
    onJumpToSource: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val session by viewModel.session.collectAsState()
    val models by viewModel.availableModels.collectAsState()
    val formats by viewModel.availableFormats.collectAsState()
    val worldBooks by viewModel.availableWorldBooks.collectAsState()
    val characterCard by viewModel.characterCard.collectAsState()
    val globalPlayerSetting by ChatBarApp.instance.settingsRepository.playerSetting.collectAsState(initial = PlayerSetting())
    val defaultModelId by viewModel.effectiveDefaultModelId.collectAsState()
    val defaultImageModelId by viewModel.effectiveDefaultImageModelId.collectAsState()
    val defaultFormatId by viewModel.effectiveDefaultFormatCardId.collectAsState()
    val slots by viewModel.availableSaveSlots.collectAsState()
    val ragChunks by viewModel.ragMemoryChunks.collectAsState()
    val ragStatus by viewModel.ragMemoryStatus.collectAsState()
    val ragBusy by viewModel.ragMemoryBusy.collectAsState()
    val memoryUi by viewModel.longTermMemoryUiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var modelId by remember { mutableStateOf(session?.modelId) }
    var imageModelId by remember { mutableStateOf(session?.imageModelId) }
    var formatId by remember { mutableStateOf(session?.formatCardId) }
    var replyLength by remember { mutableStateOf(session?.replyLength ?: "") }
    var replyLanguage by remember { mutableStateOf(session?.replyLanguage ?: "") }
    var supplementary by remember { mutableStateOf(session?.supplementarySetting ?: "") }
    var playerName by remember { mutableStateOf(session?.playerName ?: "") }
    var playerSetting by remember { mutableStateOf(session?.playerSetting ?: "") }
    var background by remember { mutableStateOf(session?.chatBackground ?: "") }
    var longTermMemoryEnabled by remember { mutableStateOf(session?.longTermMemoryEnabled ?: true) }
    var longTermMemory by remember { mutableStateOf(session?.longTermMemory ?: "") }
    var extraWorldBookIds by remember { mutableStateOf(session?.extraWorldBookIds ?: emptyList()) }
    var slotName by remember { mutableStateOf("") }
    var slotDescription by remember { mutableStateOf("") }
    var deleteSlot by remember { mutableStateOf<SaveSlotSummary?>(null) }
    var archiveStatus by remember { mutableStateOf<String?>(null) }
    var exportSlotId by remember { mutableStateOf<String?>(null) }
    var ragEditor by remember { mutableStateOf<RagChunkEditor?>(null) }
    var deleteRagChunk by remember { mutableStateOf<VectorChunk?>(null) }

    var fullscreenField by remember { mutableStateOf<Pair<String, String>?>(null) }
    var fullscreenOnChange by remember { mutableStateOf<((String) -> Unit)?>(null) }

    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.copyUriToLocalFile(it) { path -> background = path } }
    }
    val exportSaveSlot = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val slotId = exportSlotId.also { exportSlotId = null }
        if (uri != null && slotId != null) {
            scope.launch {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        viewModel.exportSaveSlotJson(slotId, output)
                    } ?: error("无法写入文件")
                }.fold(
                    onSuccess = { archiveStatus = "存档已导出。" },
                    onFailure = { archiveStatus = "导出失败：${it.message}" }
                )
            }
        }
    }
    val importSaveSlot = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        viewModel.importSaveSlotJson(input)
                    } ?: error("文件为空")
                }.fold(
                    onSuccess = { archiveStatus = "已导入存档：${it.name}" },
                    onFailure = { archiveStatus = "导入失败：${it.message}" }
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.refreshConfigurations()
        viewModel.refreshRagMemoryChunks()
        viewModel.refreshLongTermMemory()
    }
    LaunchedEffect(session) {
        session?.let {
            modelId = it.modelId; imageModelId = it.imageModelId; formatId = it.formatCardId; replyLength = it.replyLength ?: ""
            replyLanguage = it.replyLanguage ?: ""
            supplementary = it.supplementarySetting ?: ""; playerName = it.playerName ?: ""
            playerSetting = it.playerSetting ?: ""; background = it.chatBackground ?: ""
            longTermMemoryEnabled = it.longTermMemoryEnabled
            longTermMemory = it.longTermMemory
            extraWorldBookIds = it.extraWorldBookIds
        }
    }

    val settingsDirty = session?.let {
        modelId != it.modelId ||
            imageModelId != it.imageModelId ||
            formatId != it.formatCardId ||
            replyLength.takeIf(String::isNotBlank) != it.replyLength ||
            replyLanguage.takeIf(String::isNotBlank) != it.replyLanguage ||
            supplementary.takeIf(String::isNotBlank) != it.supplementarySetting ||
            playerName.takeIf(String::isNotBlank) != it.playerName ||
            playerSetting.takeIf(String::isNotBlank) != it.playerSetting ||
            background.takeIf(String::isNotBlank) != it.chatBackground ||
            longTermMemoryEnabled != it.longTermMemoryEnabled ||
            extraWorldBookIds != it.extraWorldBookIds
    } ?: false
    val renderPlayerName = session?.playerName?.takeIf { it.isNotBlank() }
        ?: globalPlayerSetting.playerName.takeIf { it.isNotBlank() }
    val renderBotName = characterCard?.name ?: session?.title ?: ""

    fun renderSessionText(text: String): String =
        PlaceholderRenderer.render(text, renderPlayerName, renderBotName)

    fun openFullscreen(title: String, text: String, onChange: (String) -> Unit) {
        fullscreenField = title to text
        fullscreenOnChange = onChange
    }

    fun closeFullscreen() {
        fullscreenField = null
        fullscreenOnChange = null
    }

    Dialog(
        onDismissRequest = {
            if (fullscreenField != null) closeFullscreen() else onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier.fillMaxSize().background(ChatBarTheme.colors.background)) {
            Column(Modifier.fillMaxSize()) {
                CbTopBar(
                    title = "会话设置",
                    statusBarInset = true,
                    navigation = { CbIconButton(AppIcons.Close, "关闭", onDismiss) },
                    actions = {
                        CbDirtySaveButton(settingsDirty, {
                            viewModel.updateSessionConfig(
                                modelId = modelId,
                                imageModelId = imageModelId,
                                formatCardId = formatId,
                                replyLength = replyLength.takeIf(String::isNotBlank),
                                replyLanguage = replyLanguage.takeIf(String::isNotBlank),
                                supplementarySetting = supplementary.takeIf(String::isNotBlank),
                                playerName = playerName.takeIf(String::isNotBlank),
                                playerSetting = playerSetting.takeIf(String::isNotBlank),
                                chatBackground = background.takeIf(String::isNotBlank),
                                longTermMemoryEnabled = longTermMemoryEnabled,
                                longTermMemory = session?.longTermMemory.orEmpty(),
                                extraWorldBookIds = extraWorldBookIds
                            )
                            onDismiss()
                        }, variant = ButtonVariant.Ghost)
                    }
                )
                val tabs = buildList {
                    add("参数与设定")
                    add("长期记忆")
                    add("RAG 检索库")
                    add("存档")
                }
                if (tab >= tabs.size) tab = tabs.lastIndex
                CbTabs(tabs, tab, { tab = it })
                Box(
                    Modifier
                        .weight(1f)
                        .swipeToAdjacentTab(
                            selectedIndex = tab,
                            itemCount = tabs.size,
                            onSelected = { tab = it }
                        )
                ) {
                    when (tabs[tab]) {
                        "参数与设定" -> SettingsContent(
                            modelId, { modelId = it }, defaultModelId, models,
                            imageModelId, { imageModelId = it }, defaultImageModelId,
                            formatId, { formatId = it }, defaultFormatId, formats,
                            worldBooks, characterCard?.worldBookIds.orEmpty(), extraWorldBookIds, { extraWorldBookIds = it },
                            replyLength, { replyLength = it }, replyLanguage, { replyLanguage = it },
                            supplementary, { supplementary = it },
                            playerName, { playerName = it }, playerSetting, { playerSetting = it },
                            background, { backgroundPicker.launch("image/*") }, { background = "" },
                            longTermMemoryEnabled, { longTermMemoryEnabled = it }, onClearHistory,
                            ::openFullscreen
                        )
                        "长期记忆" -> LongTermMemoryContent(
                            session = session,
                            state = memoryUi,
                            onRefresh = viewModel::refreshLongTermMemory,
                            onEditNode = viewModel::editMemoryNode,
                            onRegenerateNode = viewModel::regenerateMemoryNodeCandidate,
                            onOpenNodeEditor = ::openFullscreen,
                            onEditHead = viewModel::editMemoryHead,
                            onMarkSourcesCorrected = viewModel::markMemorySourcesCorrected,
                            onJumpToSource = onJumpToSource,
                            onRebuildFromOriginal = viewModel::rebuildMemoryFromOriginal,
                            onPauseBackfill = viewModel::pauseMemoryUpdates,
                            onIncreaseLimit = viewModel::increaseMemoryLimit,
                            onRetryMaintenance = viewModel::retryMemoryMaintenance,
                            onRetryHead = viewModel::retryMemoryHead,
                            onRestoreVersion = viewModel::restoreMemoryVersion,
                            onLoadMoreHistory = viewModel::loadMoreMemoryHistory,
                            onResolveDecision = viewModel::resolveMemoryCompressionDecision
                        )
                        "RAG 检索库" -> RagMemoryContent(
                            chunks = ragChunks,
                            status = ragStatus,
                            busy = ragBusy,
                            onRefresh = viewModel::refreshRagMemoryChunks,
                            onRebuildLegacy = {
                                scope.launch { viewModel.rebuildLegacyRagMemoryChunks() }
                            },
                            onCreate = {
                                viewModel.clearRagMemoryStatus()
                                ragEditor = RagChunkEditor(chunkId = null, content = "")
                            },
                            onEdit = { chunk ->
                                viewModel.clearRagMemoryStatus()
                                ragEditor = RagChunkEditor(chunkId = chunk.id, content = chunk.content)
                            },
                            onDelete = { deleteRagChunk = it }
                        )
                        else -> SavesContent(
                            slots = slots,
                            name = slotName,
                            onName = { slotName = it },
                            description = slotDescription,
                            onDescription = { slotDescription = it },
                            status = archiveStatus,
                            onCreate = {
                                viewModel.createSaveSlot(slotName, slotDescription)
                                slotName = ""; slotDescription = ""
                                archiveStatus = "存档已创建。"
                            },
                            onImport = { importSaveSlot.launch(arrayOf("application/json", "text/*", "*/*")) },
                            onLoad = { viewModel.loadSaveSlot(it); onDismiss() },
                            onDelete = { deleteSlot = it },
                            onExport = { slot ->
                                exportSlotId = slot.id
                                exportSaveSlot.launch("${safeArchiveFileName(renderSessionText(slot.name))}.json")
                            },
                            renderText = ::renderSessionText
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
                    onDismiss = ::closeFullscreen
                )
            }
            ragEditor?.let { editor ->
                FullscreenTextEditor(
                    title = if (editor.chunkId == null) "新建 RAG 块" else "编辑 RAG 块",
                    text = editor.content,
                    onTextChange = { ragEditor = editor.copy(content = it) },
                    visible = true,
                    onDismiss = { if (!ragBusy) ragEditor = null },
                    onConfirm = {
                        scope.launch {
                            if (viewModel.saveRagMemoryChunk(editor.chunkId, editor.content)) {
                                ragEditor = null
                            }
                        }
                    },
                    placeholder = "输入需要参与检索的记忆文本…",
                    confirmIcon = AppIcons.Save,
                    confirmEnabled = editor.content.isNotBlank() && !ragBusy
                )
            }
        }
    }

    deleteSlot?.let { slot ->
        CbDialog(
            onDismissRequest = { deleteSlot = null },
            title = "删除存档",
            dismiss = { CbButton("取消", { deleteSlot = null }, variant = ButtonVariant.Ghost) },
            confirm = {
                CbButton("删除", { viewModel.deleteSaveSlot(slot.id); deleteSlot = null }, variant = ButtonVariant.Destructive)
            }
        ) { CbText("确定删除\u201c${renderSessionText(slot.name)}\u201d？此操作不可撤销。", color = ChatBarTheme.colors.mutedForeground) }
    }

    deleteRagChunk?.let { chunk ->
        CbDialog(
            onDismissRequest = { if (!ragBusy) deleteRagChunk = null },
            title = "删除 RAG 块",
            dismiss = {
                CbButton("取消", { deleteRagChunk = null }, variant = ButtonVariant.Ghost, enabled = !ragBusy)
            },
            confirm = {
                CbButton(
                    "删除",
                    {
                        scope.launch {
                            if (viewModel.deleteRagMemoryChunk(chunk.id)) {
                                deleteRagChunk = null
                            }
                        }
                    },
                    variant = ButtonVariant.Destructive,
                    enabled = !ragBusy
                )
            }
        ) {
            CbText("确定删除此 RAG 块？删除后不会自动恢复。", color = ChatBarTheme.colors.mutedForeground)
        }
    }

}

@Composable
private fun SettingsContent(
    modelId: String?, onModel: (String?) -> Unit, defaultModelId: String?, models: List<ModelConfig>,
    imageModelId: String?, onImageModel: (String?) -> Unit, defaultImageModelId: String?,
    formatId: String?, onFormat: (String?) -> Unit, defaultFormatId: String?, formats: List<FormatCard>,
    worldBooks: List<WorldBook>, inheritedWorldBookIds: List<String>, extraWorldBookIds: List<String>, onExtraWorldBookIds: (List<String>) -> Unit,
    length: String, onLength: (String) -> Unit, language: String, onLanguage: (String) -> Unit,
    supplementary: String, onSupplementary: (String) -> Unit,
    playerName: String, onPlayerName: (String) -> Unit, playerSetting: String, onPlayerSetting: (String) -> Unit,
    background: String, onPickBackground: () -> Unit, onClearBackground: () -> Unit,
    longTermMemoryEnabled: Boolean, onLongTermMemoryEnabled: (Boolean) -> Unit, onClearHistory: () -> Unit,
    openFullscreen: (String, String, (String) -> Unit) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CbText("长期记忆", style = ChatBarTheme.typography.label)
                CbText("发送时注入 Prompt，AI 回复后自动总结更新。", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
            }
            CbSwitch(longTermMemoryEnabled, onLongTermMemoryEnabled)
        }
        CbDivider()
        val modelOptions = models.map { IdOption(it.id, it.displayName) }
        DefaultAwareSelect("默认对话模型", modelId, defaultModelId, modelOptions, onModel)
        if (modelId != null && models.none { it.id == modelId }) {
            CbText(
                "原会话模型在当前配置模式不可用，运行时已跟随全局默认。切回原配置模式后可恢复。",
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
        }
        DefaultAwareSelect("默认生图模型", imageModelId, defaultImageModelId, modelOptions, onImageModel)
        if (imageModelId != null && models.none { it.id == imageModelId }) {
            CbText(
                "原会话生图模型在当前配置模式不可用，运行时已跟随全局默认生图模型。",
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
        }
        DefaultAwareSelect("格式卡", formatId, defaultFormatId, formats.map { IdOption(it.id, it.name) }, onFormat, noneLabel = "不设置")
        WorldBookSettings(worldBooks, inheritedWorldBookIds, extraWorldBookIds, onExtraWorldBookIds)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CbField("回复长度", Modifier.weight(1f)) { CbInput(length, onLength, placeholder = "50 字内 / 长篇") }
            CbField("回复语言", Modifier.weight(1f)) { CbInput(language, onLanguage, placeholder = "中文") }
        }
        CbField("临时补充设定", onFullscreenEdit = {
            openFullscreen("临时补充设定", supplementary, onSupplementary)
        }) { CbInput(supplementary, onSupplementary, singleLine = false, minLines = 3) }
        CbDivider()
        CbField("玩家名称覆盖", description = "会话 Prompt 中的 ${'$'}username 将替换为此名称。") {
            CbInput(playerName, onPlayerName, placeholder = "会话专属名称")
        }
        CbField("玩家设定覆盖", onFullscreenEdit = {
            openFullscreen("玩家设定覆盖", playerSetting, onPlayerSetting)
        }) { CbInput(playerSetting, onPlayerSetting, singleLine = false, minLines = 3) }
        CbDivider()
        CbField("聊天背景覆盖") {
            Box(
                Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(10.dp)).background(ChatBarTheme.colors.muted).clickable(onClick = onPickBackground),
                contentAlignment = Alignment.Center
            ) {
                if (background.isNotBlank()) {
                    AsyncImage(File(background), "聊天背景", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                }
                CbText("点击选择图片", color = if (background.isBlank()) ChatBarTheme.colors.mutedForeground else Color.White)
            }
            if (background.isNotBlank()) CbButton("恢复角色默认背景", onClearBackground, variant = ButtonVariant.Ghost)
        }
        CbDivider()
        CbText("危险操作", color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.label)
        CbButton("清空历史和长期记忆", onClearHistory, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Destructive)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WorldBookSettings(
    worldBooks: List<WorldBook>,
    inheritedIds: List<String>,
    selectedIds: List<String>,
    onSelectedIds: (List<String>) -> Unit
) {
    val inherited = inheritedIds.mapNotNull { id -> worldBooks.firstOrNull { it.id == id } }
    CbField(
        "世界书",
        description = "角色绑定世界书会自动继承；这里可为当前会话额外启用世界书。"
    ) {
        if (inherited.isNotEmpty()) {
            CbText(
                "角色继承：${inherited.joinToString("、") { it.name }}",
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
            Spacer(Modifier.height(8.dp))
        }
        if (worldBooks.isEmpty()) {
            CbText("暂无世界书", color = ChatBarTheme.colors.mutedForeground)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                worldBooks.forEach { book ->
                    val selected = book.id in selectedIds
                    CbChoiceChip(
                        text = book.name,
                        selected = selected,
                        onClick = {
                            onSelectedIds(
                                if (selected) selectedIds - book.id
                                else (selectedIds + book.id).distinct()
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun LongTermMemoryContent(
    session: ChatSession?,
    state: LongTermMemoryUiState,
    onRefresh: () -> Unit,
    onEditNode: (String, String) -> Unit,
    onRegenerateNode: suspend (String, (String) -> Unit) -> Result<String>,
    onOpenNodeEditor: (String, String, (String) -> Unit) -> Unit,
    onEditHead: (MemoryHead) -> Unit,
    onMarkSourcesCorrected: (List<String>) -> Unit,
    onJumpToSource: (String) -> Unit,
    onRebuildFromOriginal: () -> Unit,
    onPauseBackfill: () -> Unit,
    onIncreaseLimit: () -> Unit,
    onRetryMaintenance: () -> Unit,
    onRetryHead: () -> Unit,
    onRestoreVersion: (String) -> Unit,
    onLoadMoreHistory: (MemoryTier) -> Unit,
    onResolveDecision: (Boolean) -> Unit
) {
    var page by remember { mutableIntStateOf(0) }
    var tierSection by remember { mutableIntStateOf(0) }
    var showBackfillConfirm by remember { mutableStateOf(false) }
    if (showBackfillConfirm) {
        val estimate = state.backfillEstimate
        CbDialog(
            onDismissRequest = { showBackfillConfirm = false },
            title = "补录长期记忆",
            dismiss = {
                CbButton("取消", { showBackfillConfirm = false }, variant = ButtonVariant.Outline)
            },
            confirm = {
                CbButton("开始补录", {
                    showBackfillConfirm = false
                    onRebuildFromOriginal()
                })
            }
        ) {
            CbText(
                buildString {
                    if ((estimate?.missingSourceTurns ?: 0) > 0) {
                        append("将从聊天记录中补齐 ${estimate?.missingSourceTurns ?: 0} 轮尚未生成长期记忆的对话。")
                        append("预计调用模型 ${estimate?.episodeCallsMin ?: 0}–${estimate?.episodeCallsMax ?: 0} 次；")
                        append("如果空间不足，还可能触发 ${estimate?.compressionCallsMin ?: 0}–${estimate?.compressionCallsMax ?: 0} 次压缩。")
                        append("待处理原文约 ${estimate?.sourceCharacters ?: 0} 字。")
                    }
                    if (state.headBackfillRequired) append("还会根据长期记忆与最新基线剧情更新当前状态。")
                    append("补录期间暂时无法继续聊天，可以随时暂停。不同模型耗时和额度消耗不同。")
                },
                color = ChatBarTheme.colors.mutedForeground
            )
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .windowInsetsPadding(WindowInsets.ime)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CbText(
                    "长期记忆 ${state.usedArchiveChars}/${session?.memoryLimitChars ?: 2000} 字（最高 20000）",
                    style = ChatBarTheme.typography.label
                )
                CbText(
                    "历史记忆：${(session?.memoryArchiveStatus ?: MemoryUpdateStatus.IDLE).memoryDisplayName()} · " +
                        "当前状态：${(session?.memoryHeadStatus ?: MemoryUpdateStatus.IDLE).memoryDisplayName()}",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
            CbIconButton(
                AppIcons.Refresh,
                "刷新长期记忆页面",
                onRefresh,
                tint = ChatBarTheme.colors.mutedForeground
            )
        }
        (session?.memoryArchiveError ?: session?.memoryHeadError ?: state.error)?.let {
            CbText(it, color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.caption)
        }
        if (state.usedArchiveChars > (session?.memoryLimitChars ?: 2000) &&
            (session?.memoryLimitChars ?: 2000) < 20000
        ) {
            CbButton(
                "增加 2000 字",
                onIncreaseLimit,
                variant = ButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (session?.memoryArchiveStatus == MemoryUpdateStatus.ERROR) {
            CbButton(
                "重试 Archive 维护",
                onRetryMaintenance,
                variant = ButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (session?.memoryHeadStatus == MemoryUpdateStatus.ERROR) {
            CbButton(
                "重试 HEAD 更新",
                onRetryHead,
                variant = ButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth()
            )
        }
        state.warnings.forEach {
            CbText("⚠ $it", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
        }
        MemoryBackfillAction(
            state = state,
            onStart = { showBackfillConfirm = true },
            onPause = onPauseBackfill
        )
        state.memoryState?.pendingDecision?.let { pending ->
            CbSurface(Modifier.fillMaxWidth(), color = ChatBarTheme.colors.muted) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbText(
                        "长期记忆上限选择待处理：${pending.tier.memoryDisplayName()}",
                        style = ChatBarTheme.typography.label
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CbButton("增加 2000 字", { onResolveDecision(true) })
                        CbButton(
                            "保持上限并压缩",
                            { onResolveDecision(false) },
                            variant = ButtonVariant.Outline
                        )
                    }
                }
            }
        }
        CbTabs(listOf("当前状态", "近期流程", "事件总结", "故事进程", "注入预览"), page, { page = it })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (page) {
                0 -> MemoryHeadPage(
                    state,
                    onEditHead,
                    onMarkSourcesCorrected = { onMarkSourcesCorrected(emptyList()) }
                )
                1, 2, 3 -> {
                    val tier = when (page) {
                        1 -> MemoryTier.EPISODE
                        2 -> MemoryTier.ARC
                        else -> MemoryTier.ERA
                    }
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CbTabs(listOf("当前", "编辑", "历史"), tierSection, { tierSection = it })
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (tierSection) {
                                0 -> MemoryTierCurrent(
                                    state,
                                    tier,
                                    onMarkSourcesCorrected = { onMarkSourcesCorrected(listOf(it)) },
                                    onJumpToSource = onJumpToSource
                                )
                                1 -> MemoryTierEditor(
                                    state = state,
                                    tier = tier,
                                    onEditNode = onEditNode,
                                    onRegenerateNode = onRegenerateNode,
                                    onOpenNodeEditor = onOpenNodeEditor
                                )
                                else -> MemoryVersionHistory(
                                    state,
                                    tier,
                                    onRestoreVersion,
                                    onLoadMore = { onLoadMoreHistory(tier) }
                                )
                            }
                        }
                    }
                }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    MemoryReadOnlyCard("Archive + HEAD", state.preview.ifBlank { "（空）" })
                }
            }
        }
    }
}

@Composable
internal fun MemoryBackfillAction(
    state: LongTermMemoryUiState,
    onStart: () -> Unit,
    onPause: () -> Unit
) {
    val backfill = state.memoryState?.backfill
    val missingCount = state.backfillEstimate?.missingSourceTurns ?: 0
    val needsBackfill = missingCount > 0 || state.headBackfillRequired
    if (backfill?.status == MemoryBackfillStatus.RUNNING) {
        val runtime = state.backfillProgress
        val completedTurns = runtime?.completedSourceTurns ?: backfill.completedSourceTurnIds.size
        val totalTurns = runtime?.totalSourceTurns
            ?: (backfill.completedSourceTurnIds.size + backfill.pendingSourceTurnIds.size)
        val fraction = if (totalTurns == 0) {
            if (runtime?.phase == MemoryBackfillPhase.UPDATING_HEAD) 1f else 0f
        } else {
            runtime?.fraction ?: (completedTurns.toFloat() / totalTurns).coerceIn(0f, 1f)
        }
        val phaseText = when (runtime?.phase) {
            MemoryBackfillPhase.PREPARING -> "正在准备下一条近期流程"
            MemoryBackfillPhase.GENERATING_EPISODE -> "正在生成${runtime.currentRangeLabel.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()}"
            MemoryBackfillPhase.CHECKING_SPACE -> "正在检查长期记忆空间"
            MemoryBackfillPhase.SAVING_EPISODE -> "正在保存近期流程"
            MemoryBackfillPhase.UPDATING_HEAD -> "正在生成当前状态"
            null -> "正在准备补录"
        }
        CbSurface(Modifier.fillMaxWidth(), color = ChatBarTheme.colors.muted) {
            Column(
                Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CbText(phaseText, style = ChatBarTheme.typography.label)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(ChatBarTheme.colors.border)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .background(ChatBarTheme.colors.primary)
                    )
                }
                CbText(
                    if (totalTurns == 0) {
                        "近期流程无需补录"
                    } else {
                        "已处理 $completedTurns/$totalTurns 轮 · 已生成 ${runtime?.completedEpisodes ?: backfill.completedEpisodeCount} 条近期流程"
                    },
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
                if (runtime?.phase == MemoryBackfillPhase.GENERATING_EPISODE) {
                    CbSurface(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, ChatBarTheme.colors.border)) {
                        CbText(
                            runtime.streamingSummary.ifBlank { "模型正在生成摘要…" },
                            modifier = Modifier.padding(8.dp),
                            color = ChatBarTheme.colors.mutedForeground,
                            style = ChatBarTheme.typography.caption
                        )
                    }
                }
                CbText("补录期间聊天暂时不可用。", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                CbButton(
                    "完成当前步骤后暂停",
                    onPause,
                    variant = ButtonVariant.Outline,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    } else if (backfill?.status == MemoryBackfillStatus.ERROR) {
        CbSurface(Modifier.fillMaxWidth(), color = ChatBarTheme.colors.muted) {
            Column(
                Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CbText(
                    "补录失败：${backfill.error ?: "未知错误"}",
                    color = ChatBarTheme.colors.destructive
                )
                if (needsBackfill) {
                    CbButton(
                        "重试补录",
                        onStart,
                        modifier = Modifier.fillMaxWidth(),
                        variant = ButtonVariant.Outline
                    )
                }
            }
        }
    } else if (needsBackfill) {
        CbButton(
            "一键补录长期记忆",
            onStart,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun MemoryTierCurrent(
    state: LongTermMemoryUiState,
    tier: MemoryTier,
    onMarkSourcesCorrected: (String) -> Unit = {},
    onJumpToSource: (String) -> Unit = {}
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (tier == MemoryTier.EPISODE) {
            state.nodes.filter { it.tier == MemoryTier.LEGACY_REFERENCE }.forEach { legacy ->
                MemoryReadOnlyCard(
                    "旧版记忆参考｜时间未知",
                    "不代表当前进展。补录完成前继续注入。\n\n${legacy.body}"
                )
            }
        }
        state.nodes.filter { it.tier == tier }.forEach { node ->
            MemoryReadOnlyCard(
                node.memoryRangeLabel(state),
                node.body
            )
            state.memoryState?.staleSourcesByNodeId?.get(node.id)?.let { sourceIds ->
                val labels = sourceIds.mapNotNull { sourceId ->
                    state.memoryState.timeline.firstOrNull { it.sourceTurnId == sourceId }
                        ?.let { "T${it.displayT}" }
                }
                CbSurface(Modifier.fillMaxWidth(), color = ChatBarTheme.colors.muted) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CbText(
                            "来源已变化${labels.takeIf { it.isNotEmpty() }?.joinToString(prefix = "：") ?: ""}。旧正文继续注入。",
                            color = ChatBarTheme.colors.mutedForeground,
                            style = ChatBarTheme.typography.caption
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            sourceIds.firstOrNull()?.let { sourceId ->
                                CbButton(
                                    "跳转来源",
                                    { onJumpToSource(sourceId) },
                                    variant = ButtonVariant.Ghost,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            CbButton(
                                "已按新来源校正",
                                { onMarkSourcesCorrected(node.id) },
                                variant = ButtonVariant.Outline,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MemoryHeadPage(
    state: LongTermMemoryUiState,
    onEditHead: (MemoryHead) -> Unit,
    onMarkSourcesCorrected: () -> Unit = {}
) {
    val head = state.head ?: return
    if (!state.headPresent) {
        CbSurface(Modifier.fillMaxWidth(), color = ChatBarTheme.colors.muted) {
            CbText(
                if (state.headInitializationPending) {
                    "当前状态将在第三轮对话开始时生成。"
                } else {
                    "当前状态尚未生成，请使用页面顶部的一键补录。"
                },
                modifier = Modifier.padding(12.dp),
                color = ChatBarTheme.colors.mutedForeground
            )
        }
        return
    }
    var headDraft by remember(head) { mutableStateOf(head) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CbSurface(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, ChatBarTheme.colors.border)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CbText("HEAD｜截至 ${head.memoryThroughLabel(state)}", style = ChatBarTheme.typography.heading)
                CbField("位置") { CbInput(headDraft.location, { headDraft = headDraft.copy(location = it) }) }
                CbField("人物状态") { CbInput(headDraft.participants, { headDraft = headDraft.copy(participants = it) }, singleLine = false, minLines = 2) }
                CbField("关系") { CbInput(headDraft.relationships, { headDraft = headDraft.copy(relationships = it) }, singleLine = false, minLines = 2) }
                CbField("目标") { CbInput(headDraft.goals, { headDraft = headDraft.copy(goals = it) }, singleLine = false, minLines = 2) }
                CbField("未解决") { CbInput(headDraft.unresolved, { headDraft = headDraft.copy(unresolved = it) }, singleLine = false, minLines = 2) }
                CbField("世界状态") { CbInput(headDraft.worldState, { headDraft = headDraft.copy(worldState = it) }, singleLine = false, minLines = 2) }
            }
        }
        if (head.stale) {
            CbSurface(Modifier.fillMaxWidth(), color = ChatBarTheme.colors.muted) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CbText(
                        "HEAD来源已变化。旧状态继续注入，直到保存修正或确认已校正。",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                    CbButton(
                        "已按新来源校正",
                        onMarkSourcesCorrected,
                        variant = ButtonVariant.Outline,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        CbButton("保存当前状态", { onEditHead(headDraft) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun MemoryTierEditor(
    state: LongTermMemoryUiState,
    tier: MemoryTier,
    onEditNode: (String, String) -> Unit,
    onRegenerateNode: suspend (String, (String) -> Unit) -> Result<String>,
    onOpenNodeEditor: (String, String, (String) -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        state.nodes.filter { it.tier == tier }.forEach { node ->
            var draft by remember(node.id, node.body) { mutableStateOf(node.body) }
            var regenerating by remember(node.id) { mutableStateOf(false) }
            var regenerationStatus by remember(node.id) { mutableStateOf<Pair<Boolean, String>?>(null) }
            val hasUnsavedChanges = draft != node.body
            CbSurface(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, ChatBarTheme.colors.border)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbText(node.memoryRangeLabel(state), style = ChatBarTheme.typography.heading)
                    CbText(
                        "AI会从原始聊天轮或直接子节点重新生成；当前错误正文不会作为依据。覆盖范围和来源关系不会改变。",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                    CbField(
                        label = "正文",
                        onFullscreenEdit = if (regenerating) null else {
                            {
                                onOpenNodeEditor(
                                    "${when (tier) {
                                        MemoryTier.EPISODE -> "近期流程"
                                        MemoryTier.ARC -> "事件总结"
                                        MemoryTier.ERA -> "故事进程"
                                        MemoryTier.LEGACY_REFERENCE -> "旧版记忆参考"
                                    }}正文",
                                    draft
                                ) { draft = it }
                            }
                        }
                    ) {
                        CbInput(
                            draft,
                            { draft = it },
                            enabled = !regenerating,
                            singleLine = false,
                            minLines = 4
                        )
                    }
                    CbText(
                        if (hasUnsavedChanges) {
                            "有未保存修改，离开此页面会丢失。"
                        } else {
                            "当前内容已保存到Checkpoint。"
                        },
                        color = if (hasUnsavedChanges) {
                            ChatBarTheme.colors.destructive
                        } else {
                            ChatBarTheme.colors.mutedForeground
                        },
                        style = ChatBarTheme.typography.label
                    )
                    regenerationStatus?.let { (failed, message) ->
                        CbText(
                            message,
                            color = if (failed) {
                                ChatBarTheme.colors.destructive
                            } else {
                                ChatBarTheme.colors.mutedForeground
                            },
                            style = ChatBarTheme.typography.caption
                        )
                    }
                    CbButton(
                        if (regenerating) "正在从原始依据重新生成…" else "AI重新生成此节点",
                        {
                            scope.launch {
                                val originalDraft = draft
                                regenerating = true
                                regenerationStatus = false to "AI正在流式生成候选；校验失败会自动重试。"
                                try {
                                    onRegenerateNode(node.id) { streamedSummary ->
                                        draft = streamedSummary
                                    }.fold(
                                        onSuccess = { candidate ->
                                            draft = candidate
                                            regenerationStatus = false to "AI候选已写入正文；确认后再保存Checkpoint。"
                                        },
                                        onFailure = { error ->
                                            draft = originalDraft
                                            regenerationStatus = true to
                                                (error.message ?: "AI重新生成失败")
                                        }
                                    )
                                } finally {
                                    regenerating = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !regenerating,
                        variant = ButtonVariant.Outline
                    )
                    CbButton(
                        if (hasUnsavedChanges) {
                            "保存修改到Checkpoint（未保存）"
                        } else {
                            "当前内容已保存到Checkpoint"
                        },
                        { onEditNode(node.id, draft) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !regenerating && hasUnsavedChanges && draft.isNotBlank(),
                        variant = if (hasUnsavedChanges) ButtonVariant.Default else ButtonVariant.Outline
                    )
                }
            }
        }
    }
}

@Composable
internal fun MemoryVersionHistory(
    state: LongTermMemoryUiState,
    tier: MemoryTier,
    onRestoreVersion: (String) -> Unit,
    onLoadMore: () -> Unit = {}
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(state.versionsByTier[tier].orEmpty(), key = { it.revision.id }) { version ->
            CbSurface(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, ChatBarTheme.colors.border)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbText(
                        "${version.revision.operation.memoryDisplayName()} · ${version.revision.author.memoryDisplayName()}",
                        style = ChatBarTheme.typography.heading
                    )
                    CbText(
                        "${formatRagTime(version.revision.createdAt)} · 模型 ${version.revision.modelId ?: "—"} · ${version.affectedRangeLabel}",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                    version.diffs.forEach { diff ->
                        CbSurface(Modifier.fillMaxWidth(), color = ChatBarTheme.colors.muted) {
                            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                CbText(diff.label, style = ChatBarTheme.typography.label)
                                diff.before?.let { CbText("− $it", color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.caption) }
                                diff.after?.let { CbText("+ $it", color = ChatBarTheme.colors.foreground, style = ChatBarTheme.typography.caption) }
                            }
                        }
                    }
                    CbButton(
                        if (version.isCurrent) "当前Checkpoint" else "恢复此分页",
                        { onRestoreVersion(version.revision.id) },
                        variant = ButtonVariant.Outline,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !version.isCurrent
                    )
                    version.restoreBeforeRevisionId?.let { beforeRevisionId ->
                        CbButton(
                            "恢复到本次变化前",
                            { onRestoreVersion(beforeRevisionId) },
                            variant = ButtonVariant.Ghost,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
        if (state.historyHasMoreByTier[tier] == true) {
            item(key = "load-more-${tier.name}") {
                CbButton(
                    "加载更早Checkpoint",
                    onLoadMore,
                    variant = ButtonVariant.Outline,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun MemoryRevisionOperation.memoryDisplayName(): String = when (this) {
    MemoryRevisionOperation.MIGRATE -> "迁移"
    MemoryRevisionOperation.PURE_APPEND_SYNC -> "纯新增同步"
    MemoryRevisionOperation.COMPRESSION_SOURCE -> "压缩来源"
    MemoryRevisionOperation.COMPRESSION_TARGET -> "压缩结果"
    MemoryRevisionOperation.USER_EDIT -> "用户修改"
    MemoryRevisionOperation.PRE_RESTORE_CHECKPOINT -> "恢复前备份"
    MemoryRevisionOperation.RESTORE -> "恢复历史"
    MemoryRevisionOperation.LOAD_SAVE -> "载入存档"
    MemoryRevisionOperation.DEBUG_REBUILD -> "Debug重新补录"
}

private fun MemoryAuthor.memoryDisplayName(): String = when (this) {
    MemoryAuthor.AI -> "AI"
    MemoryAuthor.USER -> "用户"
    MemoryAuthor.RESTORE -> "恢复操作"
    MemoryAuthor.MIGRATION -> "迁移"
}

private fun MemoryUpdateStatus.memoryDisplayName(): String = when (this) {
    MemoryUpdateStatus.IDLE -> "空闲"
    MemoryUpdateStatus.UPDATING -> "更新中"
    MemoryUpdateStatus.ERROR -> "失败"
    MemoryUpdateStatus.LIMIT_DECISION_REQUIRED -> "等待选择"
    MemoryUpdateStatus.PAUSED -> "已暂停"
}

private fun MemoryDecisionTier.memoryDisplayName(): String = when (this) {
    MemoryDecisionTier.EPISODE -> "近期流程 → 事件总结"
    MemoryDecisionTier.ARC -> "事件总结 → 故事进程"
    MemoryDecisionTier.ERA -> "故事进程再次压缩"
}

@Composable
private fun MemoryReadOnlyCard(title: String, content: String) {
    CbSurface(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, ChatBarTheme.colors.border)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CbText(title, style = ChatBarTheme.typography.heading)
            CbText(content.ifBlank { "（空）" }, color = ChatBarTheme.colors.mutedForeground)
        }
    }
}

private fun MemoryNode.memoryRangeLabel(state: LongTermMemoryUiState): String {
    val range = state.memoryState?.timeline?.let { MemoryTimelinePolicy.range(this, it) }
    return if (range == null) "${tier.name}｜时间未知" else "${tier.name} T${range.startT}-T${range.endT}"
}

private fun MemoryHead.memoryThroughLabel(state: LongTermMemoryUiState): String {
    val id = throughSourceTurnId ?: return "T?"
    return state.memoryState?.timeline?.firstOrNull { it.sourceTurnId == id }
        ?.let { "T${it.displayT}" }
        ?: "T?"
}

@Composable
private fun SavesContent(
    slots: List<SaveSlotSummary>,
    name: String,
    onName: (String) -> Unit,
    description: String,
    onDescription: (String) -> Unit,
    status: String?,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onLoad: (SaveSlotSummary) -> Unit,
    onDelete: (SaveSlotSummary) -> Unit,
    onExport: (SaveSlotSummary) -> Unit,
    renderText: (String) -> String
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        CbSurface(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, ChatBarTheme.colors.border)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CbText("创建存档", style = ChatBarTheme.typography.heading)
                CbField("名称") { CbInput(name, onName, placeholder = "大战前夕") }
                CbField("备注") { CbInput(description, onDescription, placeholder = "可选") }
                status?.let {
                    CbText(it, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbButton("创建", onCreate, enabled = name.isNotBlank())
                    CbButton("导入 JSON", onImport, variant = ButtonVariant.Outline)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (slots.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CbText("暂无存档", color = ChatBarTheme.colors.mutedForeground)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(slots, key = { it.id }) { slot ->
                    SaveSlotItem(
                        slot = slot,
                        onLoad = { onLoad(slot) },
                        onExport = { onExport(slot) },
                        onDelete = { onDelete(slot) },
                        renderText = renderText
                    )
                }
            }
        }
    }
}

@Composable
private fun RagMemoryContent(
    chunks: List<VectorChunk>,
    status: String?,
    busy: Boolean,
    onRefresh: () -> Unit,
    onRebuildLegacy: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (VectorChunk) -> Unit,
    onDelete: (VectorChunk) -> Unit
) {
    val legacyCount = chunks.count(ChatMemoryIndexPolicy::needsAutomaticRebuild)
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CbText("当前会话 · ${chunks.size} 块", style = ChatBarTheme.typography.heading)
                CbText(
                    "展示当前会话 CHAT_MEMORY；待更新自动记忆重建前不会参与检索。",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
            CbIconButton(AppIcons.Refresh, "刷新", onRefresh, enabled = !busy)
            CbIconButton(AppIcons.Add, "新建 RAG 块", onCreate, tint = ChatBarTheme.colors.primary, enabled = !busy)
        }
        status?.let {
            CbText(it, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
        }
        if (legacyCount > 0) {
            CbSurface(
                Modifier.fillMaxWidth(),
                color = ChatBarTheme.colors.muted,
                border = BorderStroke(1.dp, ChatBarTheme.colors.border)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CbText(
                        "发现 $legacyCount 个旧版自动块。重建会按完整T轮次重新索引已滑出上下文的原始对话，并补上旧版遗漏的T；手动块不受影响。",
                        modifier = Modifier.weight(1f),
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                    CbButton("重建RAG索引", onRebuildLegacy, variant = ButtonVariant.Outline, enabled = !busy)
                }
            }
        }
        if (chunks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CbText("暂无 RAG 检索块", color = ChatBarTheme.colors.mutedForeground)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(chunks, key = { it.id }) { chunk ->
                    RagMemoryChunkCard(
                        chunk = chunk,
                        enabled = !busy,
                        onEdit = { onEdit(chunk) },
                        onDelete = { onDelete(chunk) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RagMemoryChunkCard(
    chunk: VectorChunk,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val mode = when (chunk.metadata["indexMode"]) {
        "manual" -> "手动"
        "message_pair" -> "旧版用户—助手消息组"
        "memory_node" -> "旧版长期记忆耦合块"
        "timeline_turn" -> "原始 T 轮次"
        "single_message" -> "旧版单消息记忆"
        "single_message_contextual" -> "旧版上下文记忆"
        else -> chunk.metadata["indexMode"] ?: "旧版"
    }
    val metadataText = chunk.metadata.toSortedMap().entries
        .joinToString("\n") { (key, value) -> "$key = $value" }
    CbSurface(
        Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.card,
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    CbText(mode, style = ChatBarTheme.typography.label)
                    CbText(
                        "${chunk.content.length} 字符 · ${chunk.embedding.size} 维 · ${formatRagTime(chunk.createdAt)}",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                }
                CbIconButton(AppIcons.Edit, "编辑 RAG 块", onEdit, enabled = enabled)
                CbIconButton(AppIcons.Delete, "删除 RAG 块", onDelete, tint = ChatBarTheme.colors.destructive, enabled = enabled)
            }
            CbDivider()
            CbText(chunk.content)
            CbDivider()
            CbText("ID: ${chunk.id}", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
            chunk.messageId?.let {
                CbText("消息 ID: $it", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
            }
            if (metadataText.isNotBlank()) {
                CbText(metadataText, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
            }
        }
    }
}

@Composable
fun SaveSlotItem(
    slot: SaveSlotSummary,
    onLoad: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    renderText: (String) -> String = { it }
) {
    CbSurface(Modifier.fillMaxWidth(), color = ChatBarTheme.colors.muted, border = BorderStroke(1.dp, ChatBarTheme.colors.border)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CbText(renderText(slot.name), style = ChatBarTheme.typography.heading)
                slot.description?.takeIf(String::isNotBlank)?.let {
                    CbText(renderText(it), color = ChatBarTheme.colors.mutedForeground)
                }
                CbText(
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(slot.createdAt))} . ${slot.messageCount} 条消息",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
            CbIconButton(AppIcons.Restore, "读档", onLoad, tint = ChatBarTheme.colors.primary)
            CbIconButton(AppIcons.Export, "导出 JSON", onExport, tint = ChatBarTheme.colors.primary)
            CbIconButton(AppIcons.Delete, "删除", onDelete, tint = ChatBarTheme.colors.destructive)
        }
    }
}

@Composable
private fun DefaultAwareSelect(
    label: String,
    selectedId: String?,
    defaultId: String?,
    options: List<IdOption>,
    onSelected: (String?) -> Unit,
    noneLabel: String? = null
) {
    val allOptions = if (defaultId == null && noneLabel != null) listOf(IdOption(null, noneLabel)) + options else options
    val effectiveId = selectedId ?: defaultId
    val selected = allOptions.firstOrNull { it.id == effectiveId } ?: allOptions.firstOrNull()
    CbField(label) {
        CbSelect(selected, allOptions, { it.label }, { option ->
            onSelected(if (option.id == defaultId) null else option.id)
        })
    }
}

private data class IdOption(val id: String?, val label: String)
private data class RagChunkEditor(val chunkId: String?, val content: String)

private fun formatRagTime(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

private fun safeArchiveFileName(value: String): String =
    value.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "chat-save-slot" }
