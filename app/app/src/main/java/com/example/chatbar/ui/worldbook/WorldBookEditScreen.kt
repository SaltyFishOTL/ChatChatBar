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
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.byValue
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
import com.example.chatbar.ui.kit.FullscreenTextEditor
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
    var showTutorial by remember { mutableStateOf(false) }
    var descriptionFullscreen by remember { mutableStateOf(false) }

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
                        AppIcons.HelpOutline,
                        "世界书教程",
                        { showTutorial = true }
                    )
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
            CbField("描述", onFullscreenEdit = { descriptionFullscreen = true }) {
                CbInput(
                    viewModel.description,
                    { viewModel.description = it; viewModel.scheduleDraftSave() },
                    singleLine = false,
                    minLines = 2
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CbField("扫描深度", Modifier.weight(1f)) {
                    CbInput(
                        viewModel.scanDepth.toString(),
                        {
                            viewModel.scanDepth = it.toInt().coerceAtLeast(0)
                            viewModel.scheduleDraftSave()
                        },
                        inputTransformation = InputTransformation.byValue { current, proposed ->
                            proposed.takeIf { value ->
                                value.isNotEmpty() && value.all(Char::isDigit) && value.toString().toIntOrNull() != null
                            } ?: current
                        }
                    )
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

    FullscreenTextEditor(
        title = "编辑世界书描述",
        text = viewModel.description,
        onTextChange = {
            viewModel.description = it
            viewModel.scheduleDraftSave()
        },
        visible = descriptionFullscreen,
        onDismiss = { descriptionFullscreen = false }
    )

    if (showTutorial) {
        WorldBookTutorialDialog(onDismiss = { showTutorial = false })
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
    var contentFullscreen by remember(state.editingIndex) { mutableStateOf(false) }
    if (contentFullscreen) {
        FullscreenTextEditor(
            title = "编辑条目内容",
            text = state.content,
            onTextChange = { onStateChange(state.copy(content = it)) },
            visible = true,
            onDismiss = { contentFullscreen = false }
        )
        return
    }

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
            CbField("内容", onFullscreenEdit = { contentFullscreen = true }) {
                CbInput(state.content, { onStateChange(state.copy(content = it)) }, singleLine = false, minLines = 5)
            }
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

private data class WorldBookHelpSection(
    val title: String,
    val items: List<Pair<String, String>>
)

private val worldBookHelpSections = listOf(
    WorldBookHelpSection(
        title = "整本世界书",
        items = listOf(
            "扫描深度" to "每次回复前，往回查看最近多少条聊天消息来找触发词。数字越大，越容易找到较早提过的内容，也会多做一些检查。一般保持默认；短对话可小些，长线剧情可适当加大。",
            "Token 预算" to "这本世界书一次最多带多少内容给模型，可理解为“内容长度上限”。留空表示不限制。设得太小，部分已触发条目可能放不进去。",
            "递归扫描" to "一个条目触发后，再检查它的内容能否带出其他条目。适合有关联的设定；关系复杂时可能一次带出更多内容。",
            "大小写敏感、整词匹配" to "这两个书级开关会随世界书保存和导出。当前实际匹配以每个条目里的同名开关为准；需要控制效果时，请在条目中设置。"
        )
    ),
    WorldBookHelpSection(
        title = "触发与放置",
        items = listOf(
            "主触发词" to "最近聊天里出现任意一个主触发词，条目才有机会生效。多个词用英文逗号分开。",
            "二级触发词与二级逻辑" to "用于给主触发词再加一道条件。AND ANY 表示二级词出现任意一个；AND ALL 表示全部出现；NOT ANY 表示一个都不能出现；NOT ALL 表示不能全部同时出现。",
            "常驻" to "不检查触发词，每次都带上这条内容。适合始终有效的核心规则，但常驻太多会挤占对话空间。",
            "插入位置" to "“角色设定之前/之后”决定内容放在角色设定哪边。Outlet 是指定位置，只有格式卡预留了同名位置时才使用；普通用户选前两项即可。",
            "顺序" to "同一位置有多条内容时，数字较小的排在前面。预算不够时，数字较大的条目会优先保留。"
        )
    ),
    WorldBookHelpSection(
        title = "范围与次数",
        items = listOf(
            "触发概率" to "条件满足后真正生效的机会。100 表示每次生效，50 大约一半机会，0 表示不会生效。",
            "扫描深度覆盖" to "只给当前条目单独设置回看条数。留空就使用整本世界书的扫描深度；填 0 表示这条不会靠触发词生效。",
            "分组与分组权重" to "同一分组里一次只选一个条目。权重数字最大的胜出；适合互相排斥的天气、地点或状态。",
            "Sticky" to "条目生效后，接下来多少条消息继续保持，不必再次命中触发词。0 表示不保持。",
            "Cooldown" to "条目保持结束后，多少条消息内暂时不再触发。0 表示不冷却。",
            "Delay" to "聊天总消息数达到这个数字后，条目才允许生效。0 表示从一开始就可以。"
        )
    ),
    WorldBookHelpSection(
        title = "匹配与递归",
        items = listOf(
            "Regex 触发词" to "给熟悉表达式规则的用户使用。普通关键词保持关闭，输入什么就按什么找，更不容易出错。",
            "整词匹配" to "只匹配完整词，避免短词藏在长词中也误触发。中文没有天然空格分词，通常保持关闭更稳妥。",
            "大小写敏感" to "开启后，英文大写和小写必须一致；关闭时 Dragon 和 dragon 会被当作同一个词。",
            "忽略 Token 预算" to "即使世界书已达到内容上限，也要保留这条。只给绝不能遗漏的规则开启。",
            "递归时排除" to "聊天可以直接触发本条目，但其他条目的内容不能顺带触发它。",
            "阻止由本条目递归" to "本条目可以生效，但不再用它的内容继续寻找其他条目。",
            "仅递归触发" to "聊天不能直接触发本条目；只有其他条目生效后，才可能顺带带出它。"
        )
    )
)

@Composable
private fun WorldBookTutorialDialog(onDismiss: () -> Unit) {
    CbDialog(
        onDismissRequest = onDismiss,
        title = "世界书教程",
        confirm = { CbButton("知道了", onDismiss) }
    ) {
        Column(
            Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CbText(
                "世界书会在聊天中找到触发词，再把对应设定交给模型。拿不准时先用默认值，只填写触发词和内容也能正常使用。",
                color = ChatBarTheme.colors.mutedForeground
            )
            worldBookHelpSections.forEach { section ->
                CbSurface(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CbText(
                            section.title,
                            color = ChatBarTheme.colors.primary,
                            style = ChatBarTheme.typography.heading
                        )
                        section.items.forEach { (label, explanation) ->
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                CbText(label, style = ChatBarTheme.typography.label)
                                CbText(
                                    explanation,
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
}

private fun formatDraftSavedAt(timeMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMs))
