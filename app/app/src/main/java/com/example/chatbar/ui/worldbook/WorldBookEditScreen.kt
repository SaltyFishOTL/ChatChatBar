package com.example.chatbar.ui.worldbook

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chatbar.data.local.entity.WorldBookPosition
import com.example.chatbar.data.local.entity.WorldBookSelectiveLogic
import com.example.chatbar.domain.draft.WorldBookEntryModalState
import com.example.chatbar.ui.kit.AppIcons
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbDivider
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbScaffold
import com.example.chatbar.ui.kit.CbSelect
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbSwitch
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WorldBookEditScreen(
    worldBookId: String?,
    draftId: String = "",
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorldBookEditViewModel = viewModel(
        key = worldBookId?.let { "edit:$it" } ?: "new:${draftId.ifBlank { "default" }}",
        factory = WorldBookEditViewModelFactory(worldBookId, draftId)
    )
) {
    var deleteIndex by remember { mutableStateOf<Int?>(null) }
    var showExitDialog by remember { mutableStateOf(false) }

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
                title = if (worldBookId == null) "新建世界书" else "编辑世界书",
                statusBarInset = true,
                navigation = { CbIconButton(AppIcons.ArrowBack, "返回", ::requestExit) },
                actions = {
                    CbIconButton(
                        AppIcons.Save,
                        "保存",
                        { viewModel.save(onBack) },
                        enabled = viewModel.name.isNotBlank(),
                        tint = ChatBarTheme.colors.primary,
                        dirty = viewModel.hasLocalChanges
                    )
                }
            )
        }
    ) { bottomInset ->
        Column(
            Modifier
                .fillMaxSize()
                .background(ChatBarTheme.colors.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CbText("基础信息", color = ChatBarTheme.colors.primary, style = ChatBarTheme.typography.heading)
            viewModel.draftSavedAt?.let { savedAt ->
                CbText(
                    "草稿已保存 ${formatDraftSavedAt(savedAt)}",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
            CbField("名称") { CbInput(viewModel.name, { viewModel.name = it; viewModel.scheduleDraftSave() }, placeholder = "世界书名称") }
            CbField("描述") { CbInput(viewModel.description, { viewModel.description = it; viewModel.scheduleDraftSave() }, singleLine = false, minLines = 2) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CbField("扫描深度", Modifier.weight(1f)) {
                    CbInput(viewModel.scanDepth.toString(), { viewModel.scanDepth = it.toIntOrNull()?.coerceAtLeast(0) ?: viewModel.scanDepth; viewModel.scheduleDraftSave() })
                }
                CbField("Token 预算", Modifier.weight(1f)) {
                    CbInput(viewModel.tokenBudget, { viewModel.tokenBudget = it; viewModel.scheduleDraftSave() }, placeholder = "空 = 不限制")
                }
            }
            ToggleRow("递归扫描", viewModel.recursiveScanning) { viewModel.recursiveScanning = it; viewModel.scheduleDraftSave() }
            ToggleRow("大小写敏感", viewModel.caseSensitive) { viewModel.caseSensitive = it; viewModel.scheduleDraftSave() }
            ToggleRow("整词匹配", viewModel.matchWholeWords) { viewModel.matchWholeWords = it; viewModel.scheduleDraftSave() }

            CbDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                CbText("条目 (${viewModel.entries.size})", color = ChatBarTheme.colors.primary, style = ChatBarTheme.typography.heading)
                CbButton("添加条目", { viewModel.openEntryDialog(null) }, variant = ButtonVariant.Ghost)
            }
            viewModel.entries.forEachIndexed { index, entry ->
                CbSurface(Modifier.fillMaxWidth().clickable { viewModel.openEntryDialog(index) }) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            CbText(entry.name.ifBlank { "未命名条目" }, style = ChatBarTheme.typography.heading)
                            CbText(entry.keys.joinToString(", ").ifBlank { "无触发词" }, color = ChatBarTheme.colors.mutedForeground, maxLines = 1)
                        }
                        CbIconButton(AppIcons.Edit, "编辑", { viewModel.openEntryDialog(index) }, tint = ChatBarTheme.colors.primary)
                        CbIconButton(AppIcons.Delete, "删除", { deleteIndex = index }, tint = ChatBarTheme.colors.destructive)
                    }
                }
            }
            Spacer(Modifier.height(24.dp + bottomInset))
        }
    }

    viewModel.entryModalState?.let { state ->
        WorldBookEntryEditDialog(
            state = state,
            onStateChange = viewModel::updateEntryDialog,
            onDismiss = viewModel::dismissEntryDialog,
            onSave = viewModel::saveEntryDialog
        )
    }
    deleteIndex?.let { index ->
        CbDialog(
            onDismissRequest = { deleteIndex = null },
            title = "删除世界书条目",
            dismiss = { CbButton("取消", { deleteIndex = null }, variant = ButtonVariant.Ghost) },
            confirm = { CbButton("删除", { viewModel.deleteEntry(index); deleteIndex = null }, variant = ButtonVariant.Destructive) }
        ) { CbText("确定删除该条目？", color = ChatBarTheme.colors.mutedForeground) }
    }

    viewModel.restoreDraft?.let { draft ->
        CbDialog(
            onDismissRequest = viewModel::keepOriginal,
            title = if (viewModel.sourceDeleted) "源世界书已删除" else "发现未保存草稿",
            dismissOnClickOutside = false
        ) {
            CbText(
                when {
                    viewModel.sourceDeleted -> "原世界书已不存在，可将草稿转为新世界书继续保存。"
                    viewModel.restoreConflict -> "“${draft.title}”有未保存草稿，且原世界书在草稿创建后已更新。恢复草稿不会覆盖原世界书，正式保存时需要选择覆盖或另存为新世界书。"
                    else -> "“${draft.title}”有未保存草稿。恢复草稿不会覆盖原世界书，只有点保存才会写入正式内容。"
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
            title = "原世界书已更新",
            dismissOnClickOutside = false
        ) {
            CbText("草稿创建后，原世界书已有新改动。请选择覆盖原书或另存为新世界书。", color = ChatBarTheme.colors.mutedForeground)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CbButton("覆盖原书", { viewModel.saveConflict = false; viewModel.save(onBack, forceOverwrite = true) }, modifier = Modifier.fillMaxWidth())
                CbButton("另存为新书", { viewModel.saveConflict = false; viewModel.save(onBack, saveAsNew = true) }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
                CbButton("取消", { viewModel.saveConflict = false }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Ghost)
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onValue: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        CbText(label)
        CbSwitch(value, onValue)
    }
}

@Composable
private fun WorldBookEntryEditDialog(
    state: WorldBookEntryModalState,
    onStateChange: (WorldBookEntryModalState) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    CbDialog(
        onDismissRequest = onDismiss,
        title = if (state.editingIndex == null) "添加世界书条目" else "编辑世界书条目",
        modifier = Modifier.heightIn(max = 760.dp),
        dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) },
        confirm = {
            CbButton("保存", onSave, enabled = state.content.isNotBlank())
        }
    ) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CbField("条目名称") { CbInput(state.name, { onStateChange(state.copy(name = it)) }) }
            CbField("主触发词", description = "多个用英文逗号分隔。") { CbInput(state.keys, { onStateChange(state.copy(keys = it)) }) }
            CbField("二级触发词") { CbInput(state.secondary, { onStateChange(state.copy(secondary = it)) }) }
            CbField("二级逻辑") {
                CbSelect(
                    state.logic,
                    listOf(0, 1, 2, 3),
                    {
                        when (it) {
                            1 -> "NOT ALL"
                            2 -> "NOT ANY"
                            3 -> "AND ALL"
                            else -> "AND ANY"
                        }
                    },
                    { onStateChange(state.copy(logic = it)) }
                )
            }
            CbField("插入位置") {
                CbSelect(
                    state.position,
                    listOf(WorldBookPosition.BEFORE_CHAR, WorldBookPosition.AFTER_CHAR, WorldBookPosition.OUTLET),
                    {
                        when (it) {
                            WorldBookPosition.BEFORE_CHAR -> "角色设定之前"
                            WorldBookPosition.AFTER_CHAR -> "角色设定之后"
                            WorldBookPosition.OUTLET -> "Outlet"
                        }
                    },
                    { onStateChange(state.copy(position = it)) }
                )
            }
            if (state.position == WorldBookPosition.OUTLET) CbField("Outlet 名称") { CbInput(state.outlet, { onStateChange(state.copy(outlet = it)) }) }
            CbField("内容") { CbInput(state.content, { onStateChange(state.copy(content = it)) }, singleLine = false, minLines = 5) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CbField("顺序", Modifier.weight(1f)) { CbInput(state.order, { onStateChange(state.copy(order = it)) }) }
                CbField("触发概率", Modifier.weight(1f)) { CbInput(state.probability, { onStateChange(state.copy(probability = it)) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CbField("扫描深度覆盖", Modifier.weight(1f)) { CbInput(state.scanDepth, { onStateChange(state.copy(scanDepth = it)) }, placeholder = "空 = 使用书设置") }
                CbField("分组权重", Modifier.weight(1f)) { CbInput(state.groupWeight, { onStateChange(state.copy(groupWeight = it)) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CbField("Sticky", Modifier.weight(1f)) { CbInput(state.sticky, { onStateChange(state.copy(sticky = it)) }) }
                CbField("Cooldown", Modifier.weight(1f)) { CbInput(state.cooldown, { onStateChange(state.copy(cooldown = it)) }) }
                CbField("Delay", Modifier.weight(1f)) { CbInput(state.delay, { onStateChange(state.copy(delay = it)) }) }
            }
            CbField("分组") { CbInput(state.group, { onStateChange(state.copy(group = it)) }) }
            ToggleRow("启用", state.enabled) { onStateChange(state.copy(enabled = it)) }
            ToggleRow("常驻", state.constant) { onStateChange(state.copy(constant = it)) }
            ToggleRow("Regex 触发词", state.useRegex) { onStateChange(state.copy(useRegex = it)) }
            ToggleRow("整词匹配", state.wholeWords) { onStateChange(state.copy(wholeWords = it)) }
            ToggleRow("大小写敏感", state.caseSensitive) { onStateChange(state.copy(caseSensitive = it)) }
            ToggleRow("忽略 Token 预算", state.ignoreBudget) { onStateChange(state.copy(ignoreBudget = it)) }
            ToggleRow("递归时排除", state.excludeRecursion) { onStateChange(state.copy(excludeRecursion = it)) }
            ToggleRow("阻止由本条目递归", state.preventRecursion) { onStateChange(state.copy(preventRecursion = it)) }
            ToggleRow("仅递归触发", state.delayUntilRecursion) { onStateChange(state.copy(delayUntilRecursion = it)) }
        }
    }
}

private fun formatDraftSavedAt(timeMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMs))
