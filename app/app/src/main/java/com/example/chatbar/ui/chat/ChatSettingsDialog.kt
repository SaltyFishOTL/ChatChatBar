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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.SaveSlot
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatSettingsDialog(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val session by viewModel.session.collectAsState()
    val models by viewModel.availableModels.collectAsState()
    val formats by viewModel.availableFormats.collectAsState()
    val defaultModelId by viewModel.effectiveDefaultModelId.collectAsState()
    val defaultFormatId by viewModel.effectiveDefaultFormatCardId.collectAsState()
    val slots by viewModel.availableSaveSlots.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var modelId by remember { mutableStateOf(session?.modelId) }
    var formatId by remember { mutableStateOf(session?.formatCardId) }
    var replyLength by remember { mutableStateOf(session?.replyLength ?: "") }
    var replyLanguage by remember { mutableStateOf(session?.replyLanguage ?: "") }
    var supplementary by remember { mutableStateOf(session?.supplementarySetting ?: "") }
    var playerName by remember { mutableStateOf(session?.playerName ?: "") }
    var playerSetting by remember { mutableStateOf(session?.playerSetting ?: "") }
    var background by remember { mutableStateOf(session?.chatBackground ?: "") }
    var longTermMemoryEnabled by remember { mutableStateOf(session?.longTermMemoryEnabled ?: true) }
    var longTermMemory by remember { mutableStateOf(session?.longTermMemory ?: "") }
    var slotName by remember { mutableStateOf("") }
    var slotDescription by remember { mutableStateOf("") }
    var deleteSlot by remember { mutableStateOf<SaveSlot?>(null) }

    var fullscreenField by remember { mutableStateOf<Pair<String, String>?>(null) }
    var fullscreenOnChange by remember { mutableStateOf<((String) -> Unit)?>(null) }

    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.copyUriToLocalFile(it) { path -> background = path } }
    }
    LaunchedEffect(Unit) { viewModel.refreshConfigurations() }
    LaunchedEffect(session) {
        session?.let {
            modelId = it.modelId; formatId = it.formatCardId; replyLength = it.replyLength ?: ""
            replyLanguage = it.replyLanguage ?: ""
            supplementary = it.supplementarySetting ?: ""; playerName = it.playerName ?: ""
            playerSetting = it.playerSetting ?: ""; background = it.chatBackground ?: ""
            longTermMemoryEnabled = it.longTermMemoryEnabled
            longTermMemory = it.longTermMemory
        }
    }

    val settingsDirty = session?.let {
        modelId != it.modelId ||
            formatId != it.formatCardId ||
            replyLength.takeIf(String::isNotBlank) != it.replyLength ||
            replyLanguage.takeIf(String::isNotBlank) != it.replyLanguage ||
            supplementary.takeIf(String::isNotBlank) != it.supplementarySetting ||
            playerName.takeIf(String::isNotBlank) != it.playerName ||
            playerSetting.takeIf(String::isNotBlank) != it.playerSetting ||
            background.takeIf(String::isNotBlank) != it.chatBackground ||
            longTermMemoryEnabled != it.longTermMemoryEnabled ||
            longTermMemory != it.longTermMemory
    } ?: false

    fun openFullscreen(title: String, text: String, onChange: (String) -> Unit) {
        fullscreenField = title to text
        fullscreenOnChange = onChange
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier.fillMaxSize().background(ChatBarTheme.colors.background)) {
            CbTopBar(
                title = "会话设置",
                statusBarInset = true,
                navigation = { CbIconButton(AppIcons.Close, "关闭", onDismiss) },
                actions = {
                    CbDirtySaveButton(settingsDirty, {
                        viewModel.updateSessionConfig(
                            modelId = modelId,
                            formatCardId = formatId,
                            replyLength = replyLength.takeIf(String::isNotBlank),
                            replyLanguage = replyLanguage.takeIf(String::isNotBlank),
                            supplementarySetting = supplementary.takeIf(String::isNotBlank),
                            playerName = playerName.takeIf(String::isNotBlank),
                            playerSetting = playerSetting.takeIf(String::isNotBlank),
                            chatBackground = background.takeIf(String::isNotBlank),
                            longTermMemoryEnabled = longTermMemoryEnabled,
                            longTermMemory = longTermMemory
                        )
                        onDismiss()
                    }, variant = ButtonVariant.Ghost)
                }
            )
            val tabs = if (longTermMemoryEnabled) listOf("参数与设定", "长期记忆", "存档") else listOf("参数与设定", "存档")
            if (tab >= tabs.size) tab = tabs.lastIndex
            CbTabs(tabs, tab, { tab = it })
            if (tab == 0) {
                SettingsContent(
                    modelId, { modelId = it }, defaultModelId, models,
                    formatId, { formatId = it }, defaultFormatId, formats,
                    replyLength, { replyLength = it }, replyLanguage, { replyLanguage = it },
                    supplementary, { supplementary = it },
                    playerName, { playerName = it }, playerSetting, { playerSetting = it },
                    background, { backgroundPicker.launch("image/*") }, { background = "" },
                    longTermMemoryEnabled, { longTermMemoryEnabled = it }, onClearHistory,
                    ::openFullscreen
                )
            } else if (longTermMemoryEnabled && tab == 1) {
                LongTermMemoryContent(longTermMemory, { longTermMemory = it })
            } else {
                SavesContent(
                    slots = slots,
                    name = slotName,
                    onName = { slotName = it },
                    description = slotDescription,
                    onDescription = { slotDescription = it },
                    onCreate = {
                        viewModel.createSaveSlot(slotName, slotDescription)
                        slotName = ""; slotDescription = ""
                    },
                    onLoad = { viewModel.loadSaveSlot(it); onDismiss() },
                    onDelete = { deleteSlot = it }
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
        ) { CbText("确定删除\u201c${slot.name}\u201d？此操作不可撤销。", color = ChatBarTheme.colors.mutedForeground) }
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
}

@Composable
private fun SettingsContent(
    modelId: String?, onModel: (String?) -> Unit, defaultModelId: String?, models: List<ModelConfig>,
    formatId: String?, onFormat: (String?) -> Unit, defaultFormatId: String?, formats: List<FormatCard>,
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
        DefaultAwareSelect("对话模型", modelId, defaultModelId, models.map { IdOption(it.id, it.displayName) }, onModel)
        if (modelId != null && models.none { it.id == modelId }) {
            CbText(
                "原会话模型在当前配置模式不可用，运行时已跟随全局默认。切回原配置模式后可恢复。",
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
        }
        DefaultAwareSelect("格式卡", formatId, defaultFormatId, formats.map { IdOption(it.id, it.name) }, onFormat, noneLabel = "不设置")
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
private fun LongTermMemoryContent(
    memory: String,
    onMemory: (String) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .windowInsetsPadding(WindowInsets.ime)
    ) {
        CbInput(
            value = memory,
            onValueChange = onMemory,
            placeholder = "记录稳定事实、偏好、关系、目标、已确认设定...",
            modifier = Modifier.fillMaxSize(),
            singleLine = false,
            minLines = 24,
            expand = true
        )
    }
}

@Composable
private fun SavesContent(
    slots: List<SaveSlot>,
    name: String,
    onName: (String) -> Unit,
    description: String,
    onDescription: (String) -> Unit,
    onCreate: () -> Unit,
    onLoad: (SaveSlot) -> Unit,
    onDelete: (SaveSlot) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        CbSurface(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, ChatBarTheme.colors.border)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CbText("创建存档", style = ChatBarTheme.typography.heading)
                CbField("名称") { CbInput(name, onName, placeholder = "大战前夕") }
                CbField("备注") { CbInput(description, onDescription, placeholder = "可选") }
                CbButton("创建", onCreate, enabled = name.isNotBlank())
            }
        }
        Spacer(Modifier.height(16.dp))
        if (slots.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CbText("暂无存档", color = ChatBarTheme.colors.mutedForeground)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(slots, key = { it.id }) { slot -> SaveSlotItem(slot, { onLoad(slot) }, { onDelete(slot) }) }
            }
        }
    }
}

@Composable
fun SaveSlotItem(slot: SaveSlot, onLoad: () -> Unit, onDelete: () -> Unit) {
    CbSurface(Modifier.fillMaxWidth(), color = ChatBarTheme.colors.muted, border = BorderStroke(1.dp, ChatBarTheme.colors.border)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CbText(slot.name, style = ChatBarTheme.typography.heading)
                slot.description?.takeIf(String::isNotBlank)?.let { CbText(it, color = ChatBarTheme.colors.mutedForeground) }
                CbText(
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(slot.createdAt))} . ${slot.messages.size} 条消息",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
            CbIconButton(AppIcons.Restore, "读档", onLoad, tint = ChatBarTheme.colors.primary)
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
