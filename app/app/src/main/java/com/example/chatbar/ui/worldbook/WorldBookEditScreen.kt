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
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.local.entity.WorldBookPosition
import com.example.chatbar.data.local.entity.WorldBookSelectiveLogic
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

@Composable
fun WorldBookEditScreen(
    worldBookId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorldBookEditViewModel = viewModel(
        key = worldBookId,
        factory = WorldBookEditViewModelFactory(worldBookId)
    )
) {
    val context = LocalContext.current
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var showEntryDialog by remember { mutableStateOf(false) }
    var deleteIndex by remember { mutableStateOf<Int?>(null) }
    var lastBackPressAt by remember { mutableStateOf(0L) }

    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackPressAt <= 2000L) onBack() else {
            lastBackPressAt = now
            Toast.makeText(context, "再按一次退出编辑（不会保存修改）", Toast.LENGTH_SHORT).show()
        }
    }

    CbScaffold(
        modifier = modifier,
        topBar = {
            CbTopBar(
                title = if (worldBookId == null) "新建世界书" else "编辑世界书",
                statusBarInset = true,
                navigation = { CbIconButton(AppIcons.ArrowBack, "返回", onBack) },
                actions = {
                    CbIconButton(
                        AppIcons.Save,
                        "保存",
                        { viewModel.save(onBack) },
                        enabled = viewModel.name.isNotBlank(),
                        tint = ChatBarTheme.colors.primary
                    )
                }
            )
        }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(ChatBarTheme.colors.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CbText("基础信息", color = ChatBarTheme.colors.primary, style = ChatBarTheme.typography.heading)
            CbField("名称") { CbInput(viewModel.name, { viewModel.name = it }, placeholder = "世界书名称") }
            CbField("描述") { CbInput(viewModel.description, { viewModel.description = it }, singleLine = false, minLines = 2) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CbField("扫描深度", Modifier.weight(1f)) {
                    CbInput(viewModel.scanDepth.toString(), { viewModel.scanDepth = it.toIntOrNull()?.coerceAtLeast(0) ?: viewModel.scanDepth })
                }
                CbField("Token 预算", Modifier.weight(1f)) {
                    CbInput(viewModel.tokenBudget, { viewModel.tokenBudget = it }, placeholder = "空 = 不限制")
                }
            }
            ToggleRow("递归扫描", viewModel.recursiveScanning) { viewModel.recursiveScanning = it }
            ToggleRow("大小写敏感", viewModel.caseSensitive) { viewModel.caseSensitive = it }
            ToggleRow("整词匹配", viewModel.matchWholeWords) { viewModel.matchWholeWords = it }

            CbDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                CbText("条目 (${viewModel.entries.size})", color = ChatBarTheme.colors.primary, style = ChatBarTheme.typography.heading)
                CbButton("添加条目", { editIndex = null; showEntryDialog = true }, variant = ButtonVariant.Ghost)
            }
            viewModel.entries.forEachIndexed { index, entry ->
                CbSurface(Modifier.fillMaxWidth().clickable { editIndex = index; showEntryDialog = true }) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            CbText(entry.name.ifBlank { "未命名条目" }, style = ChatBarTheme.typography.heading)
                            CbText(entry.keys.joinToString(", ").ifBlank { "无触发词" }, color = ChatBarTheme.colors.mutedForeground, maxLines = 1)
                        }
                        CbIconButton(AppIcons.Edit, "编辑", { editIndex = index; showEntryDialog = true }, tint = ChatBarTheme.colors.primary)
                        CbIconButton(AppIcons.Delete, "删除", { deleteIndex = index }, tint = ChatBarTheme.colors.destructive)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showEntryDialog) {
        WorldBookEntryEditDialog(
            original = editIndex?.let { viewModel.entries.getOrNull(it) },
            onDismiss = { showEntryDialog = false; editIndex = null },
            onSave = { entry ->
                editIndex?.let { viewModel.updateEntry(it, entry) } ?: viewModel.addEntry(entry)
                showEntryDialog = false
                editIndex = null
            }
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
    original: WorldBookEntry?,
    onDismiss: () -> Unit,
    onSave: (WorldBookEntry) -> Unit
) {
    var name by remember { mutableStateOf(original?.name ?: "") }
    var keys by remember { mutableStateOf(original?.keys?.joinToString(", ") ?: "") }
    var secondary by remember { mutableStateOf(original?.secondaryKeys?.joinToString(", ") ?: "") }
    var content by remember { mutableStateOf(original?.content ?: "") }
    var order by remember { mutableStateOf((original?.insertionOrder ?: 100).toString()) }
    var position by remember { mutableStateOf(original?.position ?: WorldBookPosition.BEFORE_CHAR) }
    var enabled by remember { mutableStateOf(original?.enabled ?: true) }
    var constant by remember { mutableStateOf(original?.constant ?: false) }
    var useRegex by remember { mutableStateOf(original?.useRegex ?: false) }
    var wholeWords by remember { mutableStateOf(original?.matchWholeWords ?: false) }
    var caseSensitive by remember { mutableStateOf(original?.caseSensitive ?: false) }
    var ignoreBudget by remember { mutableStateOf(original?.ignoreBudget ?: false) }
    var excludeRecursion by remember { mutableStateOf(original?.excludeRecursion ?: false) }
    var preventRecursion by remember { mutableStateOf(original?.preventRecursion ?: false) }
    var delayUntilRecursion by remember { mutableStateOf(original?.delayUntilRecursion ?: false) }
    var logic by remember { mutableStateOf(original?.selectiveLogic ?: WorldBookSelectiveLogic.AND_ANY.value) }
    var probability by remember { mutableStateOf((original?.probability ?: 100).toString()) }
    var group by remember { mutableStateOf(original?.group ?: "") }
    var groupWeight by remember { mutableStateOf((original?.groupWeight ?: 100).toString()) }
    var scanDepth by remember { mutableStateOf(original?.scanDepth?.toString() ?: "") }
    var sticky by remember { mutableStateOf((original?.sticky ?: 0).toString()) }
    var cooldown by remember { mutableStateOf((original?.cooldown ?: 0).toString()) }
    var delay by remember { mutableStateOf((original?.delay ?: 0).toString()) }
    var outlet by remember { mutableStateOf(original?.outletName ?: "") }

    CbDialog(
        onDismissRequest = onDismiss,
        title = if (original == null) "添加世界书条目" else "编辑世界书条目",
        modifier = Modifier.heightIn(max = 760.dp),
        dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) },
        confirm = {
            CbButton("保存", {
                onSave(
                    (original ?: WorldBookEntry(id = java.util.UUID.randomUUID().toString())).copy(
                        name = name,
                        keys = keys.split(",").map { it.trim() }.filter { it.isNotBlank() },
                        secondaryKeys = secondary.split(",").map { it.trim() }.filter { it.isNotBlank() },
                        selective = secondary.isNotBlank(),
                        selectiveLogic = logic,
                        content = content,
                        insertionOrder = order.toIntOrNull() ?: 100,
                        position = position,
                        enabled = enabled,
                        constant = constant,
                        useRegex = useRegex,
                        matchWholeWords = wholeWords,
                        caseSensitive = caseSensitive,
                        ignoreBudget = ignoreBudget,
                        excludeRecursion = excludeRecursion,
                        preventRecursion = preventRecursion,
                        delayUntilRecursion = delayUntilRecursion,
                        probability = probability.toIntOrNull()?.coerceIn(0, 100) ?: 100,
                        group = group,
                        groupWeight = groupWeight.toIntOrNull()?.coerceAtLeast(0) ?: 100,
                        scanDepth = scanDepth.toIntOrNull()?.coerceAtLeast(0),
                        sticky = sticky.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                        cooldown = cooldown.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                        delay = delay.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                        outletName = outlet
                    )
                )
            }, enabled = content.isNotBlank())
        }
    ) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CbField("条目名称") { CbInput(name, { name = it }) }
            CbField("主触发词", description = "多个用英文逗号分隔。") { CbInput(keys, { keys = it }) }
            CbField("二级触发词") { CbInput(secondary, { secondary = it }) }
            CbField("二级逻辑") {
                CbSelect(
                    logic,
                    listOf(0, 1, 2, 3),
                    {
                        when (it) {
                            1 -> "NOT ALL"
                            2 -> "NOT ANY"
                            3 -> "AND ALL"
                            else -> "AND ANY"
                        }
                    },
                    { logic = it }
                )
            }
            CbField("插入位置") {
                CbSelect(
                    position,
                    listOf(WorldBookPosition.BEFORE_CHAR, WorldBookPosition.AFTER_CHAR, WorldBookPosition.OUTLET),
                    {
                        when (it) {
                            WorldBookPosition.BEFORE_CHAR -> "角色设定之前"
                            WorldBookPosition.AFTER_CHAR -> "角色设定之后"
                            WorldBookPosition.OUTLET -> "Outlet"
                        }
                    },
                    { position = it }
                )
            }
            if (position == WorldBookPosition.OUTLET) CbField("Outlet 名称") { CbInput(outlet, { outlet = it }) }
            CbField("内容") { CbInput(content, { content = it }, singleLine = false, minLines = 5) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CbField("顺序", Modifier.weight(1f)) { CbInput(order, { order = it }) }
                CbField("触发概率", Modifier.weight(1f)) { CbInput(probability, { probability = it }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CbField("扫描深度覆盖", Modifier.weight(1f)) { CbInput(scanDepth, { scanDepth = it }, placeholder = "空 = 使用书设置") }
                CbField("分组权重", Modifier.weight(1f)) { CbInput(groupWeight, { groupWeight = it }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CbField("Sticky", Modifier.weight(1f)) { CbInput(sticky, { sticky = it }) }
                CbField("Cooldown", Modifier.weight(1f)) { CbInput(cooldown, { cooldown = it }) }
                CbField("Delay", Modifier.weight(1f)) { CbInput(delay, { delay = it }) }
            }
            CbField("分组") { CbInput(group, { group = it }) }
            ToggleRow("启用", enabled) { enabled = it }
            ToggleRow("常驻", constant) { constant = it }
            ToggleRow("Regex 触发词", useRegex) { useRegex = it }
            ToggleRow("整词匹配", wholeWords) { wholeWords = it }
            ToggleRow("大小写敏感", caseSensitive) { caseSensitive = it }
            ToggleRow("忽略 Token 预算", ignoreBudget) { ignoreBudget = it }
            ToggleRow("递归时排除", excludeRecursion) { excludeRecursion = it }
            ToggleRow("阻止由本条目递归", preventRecursion) { preventRecursion = it }
            ToggleRow("仅递归触发", delayUntilRecursion) { delayUntilRecursion = it }
        }
    }
}
