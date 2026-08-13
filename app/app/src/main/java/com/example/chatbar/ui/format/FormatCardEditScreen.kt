package com.example.chatbar.ui.format

import com.example.chatbar.ui.kit.AppIcons

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.data.local.entity.FormatCardUserToolType
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.ButtonSize
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbCard
import com.example.chatbar.ui.kit.CbDivider
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbScaffold
import com.example.chatbar.ui.kit.CbSwitch
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.kit.FullscreenTextEditor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FormatCardEditScreen(
    formatCardId: String?,
    draftId: String = "",
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FormatCardEditViewModel = viewModel(
        key = formatCardId?.let { "edit:$it" } ?: "new:${draftId.ifBlank { "default" }}",
        factory = FormatCardEditViewModelFactory(formatCardId, draftId)
    )
) {
    var fullscreenField by remember { mutableStateOf<Pair<String, String>?>(null) }
    var fullscreenOnChange by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showToolPicker by remember { mutableStateOf(false) }

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
                title = if (formatCardId == null) "新建格式卡" else "编辑格式卡",
                statusBarInset = true,
                navigation = {
                    CbIconButton(AppIcons.ArrowBack, "返回", ::requestExit)
                },
                actions = {
                    CbIconButton(
                        imageVector = AppIcons.Save,
                        contentDescription = "保存",
                        onClick = { viewModel.saveFormatCard(onBack) },
                        enabled = viewModel.canSave,
                        tint = ChatBarTheme.colors.primary,
                        dirty = viewModel.hasLocalChanges
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CbText("格式基本信息", color = ChatBarTheme.colors.primary, style = ChatBarTheme.typography.heading)
            viewModel.draftSavedAt?.let { savedAt ->
                CbText(
                    "草稿已保存 ${formatDraftSavedAt(savedAt)}",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }

            CbField(label = "格式名称", error = viewModel.saveError) {
                CbInput(
                    value = viewModel.name,
                    onValueChange = { viewModel.name = it; viewModel.saveError = null; viewModel.scheduleDraftSave() },
                    placeholder = "例如：剧本对话格式"
                )
            }

            CbField(
                label = "Prompt 格式要求",
                description = "用于约束 AI 回复的结构和表达方式。",
                onFullscreenEdit = {
                    fullscreenField = "Prompt 格式要求" to viewModel.content; fullscreenOnChange = { viewModel.content = it; viewModel.scheduleDraftSave() }
                }
            ) {
                CbInput(
                    value = viewModel.content,
                    onValueChange = { viewModel.content = it; viewModel.scheduleDraftSave() },
                    placeholder = "例如：动作使用 *动作描写*，台词使用中文引号。",
                    singleLine = false,
                    minLines = 6
                )
            }

            CbDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    CbText("用户工具箱", color = ChatBarTheme.colors.primary, style = ChatBarTheme.typography.heading)
                    Spacer(Modifier.height(4.dp))
                    CbText(
                        "按列表顺序追加到本轮请求末尾；不显示或保存到聊天记录。",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                }
                Spacer(Modifier.width(12.dp))
                CbButton(
                    text = "添加工具",
                    onClick = { showToolPicker = true },
                    variant = ButtonVariant.Outline,
                    size = ButtonSize.Sm
                )
            }

            if (viewModel.userTools.isEmpty()) {
                CbCard(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 0.dp,
                    border = BorderStroke(1.dp, ChatBarTheme.colors.border),
                    color = ChatBarTheme.colors.muted.copy(alpha = 0.45f)
                ) {
                    CbText(
                        "尚未添加工具。发送行为保持不变。",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                }
            } else {
                viewModel.userTools.forEachIndexed { index, tool ->
                    UserToolCard(
                        index = index,
                        count = viewModel.userTools.size,
                        tool = tool,
                        validation = viewModel.userToolValidation(index),
                        onUpdate = { transform -> viewModel.updateUserTool(index, transform) },
                        onMoveUp = { viewModel.moveUserTool(index, -1) },
                        onMoveDown = { viewModel.moveUserTool(index, 1) },
                        onDelete = { viewModel.deleteUserTool(index) },
                        onFullscreenTextEdit = {
                            fullscreenField = "强提示词尾缀" to tool.text
                            fullscreenOnChange = { value ->
                                viewModel.updateUserTool(index) { it.copy(text = value) }
                            }
                        }
                    )
                }
            }

            CbDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    CbText("设为全局默认格式")
                    Spacer(Modifier.height(4.dp))
                    CbText(
                        "开启后，新建对话默认采用此格式。",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                }
                CbSwitch(viewModel.isDefault, { viewModel.isDefault = it; viewModel.scheduleDraftSave() })
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

    viewModel.restoreDraft?.let { draft ->
        CbDialog(
            onDismissRequest = viewModel::keepOriginal,
            title = if (viewModel.sourceDeleted) "源格式卡已删除" else "发现未保存草稿",
            dismissOnClickOutside = false
        ) {
            CbText(
                if (viewModel.sourceDeleted) {
                    "原格式卡已不存在，可将草稿转为新格式卡继续保存。"
                } else if (viewModel.restoreConflict) {
                    "“${draft.title}”有未保存草稿，且原格式卡在草稿创建后已更新。恢复草稿不会覆盖原格式卡，正式保存时需要选择覆盖或另存为新格式卡。"
                } else {
                    "“${draft.title}”有未保存草稿。恢复草稿不会覆盖原格式卡，只有点保存才会写入正式内容。"
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
            title = "原格式卡已更新",
            dismissOnClickOutside = false
        ) {
            CbText("草稿创建后，原格式卡已有新改动。请选择覆盖原卡或另存为新格式卡。", color = ChatBarTheme.colors.mutedForeground)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CbButton("覆盖原卡", { viewModel.saveConflict = false; viewModel.saveFormatCard(onBack, forceOverwrite = true) }, modifier = Modifier.fillMaxWidth())
                CbButton("另存为新卡", { viewModel.saveConflict = false; viewModel.saveFormatCard(onBack, saveAsNew = true) }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
                CbButton("取消", { viewModel.saveConflict = false }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Ghost)
            }
        }
    }

    if (showToolPicker) {
        CbDialog(
            onDismissRequest = { showToolPicker = false },
            title = "添加用户工具",
            dismiss = {
                CbButton("取消", { showToolPicker = false }, variant = ButtonVariant.Ghost)
            }
        ) {
            ToolPickerOption(
                title = "随机数生成器",
                description = "每次请求从闭区间生成一个整数；相邻随机数工具会合并成一行。",
                onClick = {
                    viewModel.addUserTool(FormatCardUserToolType.RANDOM_NUMBER)
                    showToolPicker = false
                }
            )
            Spacer(Modifier.height(12.dp))
            ToolPickerOption(
                title = "强提示词尾缀",
                description = "将填写文字原样放进本轮用户消息末尾的工具块。",
                onClick = {
                    viewModel.addUserTool(FormatCardUserToolType.STRONG_PROMPT_SUFFIX)
                    showToolPicker = false
                }
            )
        }
    }
}

@Composable
private fun UserToolCard(
    index: Int,
    count: Int,
    tool: FormatCardUserToolConfig,
    validation: com.example.chatbar.domain.card.FormatCardUserToolValidation,
    onUpdate: ((FormatCardUserToolConfig) -> FormatCardUserToolConfig) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onFullscreenTextEdit: () -> Unit
) {
    val invalid = !validation.isValid
    CbCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = 0.dp,
        border = BorderStroke(
            1.dp,
            if (invalid) ChatBarTheme.colors.destructive else ChatBarTheme.colors.border
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                CbText(
                    when (tool.type) {
                        FormatCardUserToolType.RANDOM_NUMBER -> "随机数生成器"
                        FormatCardUserToolType.STRONG_PROMPT_SUFFIX -> "强提示词尾缀"
                    },
                    style = ChatBarTheme.typography.label
                )
                Spacer(Modifier.height(4.dp))
                CbText(
                    when (tool.type) {
                        FormatCardUserToolType.RANDOM_NUMBER -> "在配置的闭区间内生成随机整数。"
                        FormatCardUserToolType.STRONG_PROMPT_SUFFIX -> "将此文字原样追加给 AI。"
                    },
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
            CbText(
                "${index + 1}/$count",
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CbIconButton(
                AppIcons.ExpandLess,
                "上移第 ${index + 1} 个工具",
                onMoveUp,
                enabled = index > 0
            )
            CbIconButton(
                AppIcons.ExpandMore,
                "下移第 ${index + 1} 个工具",
                onMoveDown,
                enabled = index < count - 1
            )
            CbIconButton(
                AppIcons.Delete,
                "删除第 ${index + 1} 个工具",
                onDelete,
                tint = ChatBarTheme.colors.destructive
            )
        }

        Spacer(Modifier.height(8.dp))
        when (tool.type) {
            FormatCardUserToolType.RANDOM_NUMBER -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CbField(
                    label = "最小值",
                    modifier = Modifier.weight(1f),
                    error = validation.minimumError
                ) {
                    CbInput(
                        value = tool.minimum,
                        onValueChange = { value -> onUpdate { it.copy(minimum = value) } },
                        placeholder = "1"
                    )
                }
                CbField(
                    label = "最大值",
                    modifier = Modifier.weight(1f),
                    error = validation.maximumError
                ) {
                    CbInput(
                        value = tool.maximum,
                        onValueChange = { value -> onUpdate { it.copy(maximum = value) } },
                        placeholder = "100"
                    )
                }
            }

            FormatCardUserToolType.STRONG_PROMPT_SUFFIX -> CbField(
                label = "尾缀文字",
                description = "保留原始换行与空格，不替换角色占位符。",
                error = validation.textError,
                onFullscreenEdit = onFullscreenTextEdit
            ) {
                CbInput(
                    value = tool.text,
                    onValueChange = { value -> onUpdate { it.copy(text = value) } },
                    placeholder = "输入需要强化的要求",
                    singleLine = false,
                    minLines = 3
                )
            }
        }
    }
}

@Composable
private fun ToolPickerOption(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    CbButton(
        text = title,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        variant = ButtonVariant.Outline
    )
    Spacer(Modifier.height(4.dp))
    CbText(
        description,
        color = ChatBarTheme.colors.mutedForeground,
        style = ChatBarTheme.typography.caption
    )
}

private fun formatDraftSavedAt(timeMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMs))
