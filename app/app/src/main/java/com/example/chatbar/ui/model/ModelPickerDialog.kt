package com.example.chatbar.ui.model

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.chatbar.ui.kit.*

@Composable
internal fun ModelPickerDialog(
    modelIds: List<String>,
    selectedId: String,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "选择模型",
    modelDetail: (String) -> String? = { null }
) {
    var query by rememberSaveable { mutableStateOf("") }
    var family by rememberSaveable { mutableStateOf<String?>(null) }
    var descending by rememberSaveable { mutableStateOf(false) }
    val families = remember(modelIds) { modelIds.groupingBy(::modelSeries).eachCount().toSortedMap() }
    val activeFamily = family?.takeIf { it in families }
    val filtered = remember(modelIds, query, activeFamily, descending, selectedId) {
        val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        val matches = modelIds.filter { id ->
            (activeFamily == null || modelSeries(id) == activeFamily) &&
                terms.all { id.contains(it, ignoreCase = true) }
        }
        val ordered = matches.sortedWith(String.CASE_INSENSITIVE_ORDER)
            .let { if (descending) it.reversed() else it }
        ordered.sortedBy { it != selectedId }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(query, activeFamily, descending) { listState.scrollToItem(0) }

    CbDialog(
        onDismissRequest = onDismiss,
        title = title,
        dismiss = { CbButton("关闭", onDismiss, variant = ButtonVariant.Ghost) }
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CbInput(query, { query = it }, Modifier.weight(1f), placeholder = "搜索标识，空格分隔关键词")
            if (modelIds.isNotEmpty()) {
                CbIconButton(
                    AppIcons.Refresh, "刷新可用模型", onRefresh,
                    modifier = Modifier.size(48.dp), enabled = !loading
                )
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 460.dp).weight(1f, fill = false),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (loading) {
                item(key = "loading") {
                    CbText(
                        if (modelIds.isEmpty()) "正在获取最新模型…" else "正在刷新，当前显示上次获取的列表…",
                        modifier = Modifier.padding(top = 8.dp).semantics {
                            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                        },
                        color = ChatBarTheme.colors.primary
                    )
                }
            }
            if (error != null) {
                item(key = "error") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CbText(error, color = ChatBarTheme.colors.destructive)
                        if (modelIds.isNotEmpty()) {
                            CbText("刷新失败，以下仍为上次获取的列表。", style = ChatBarTheme.typography.caption)
                        }
                        CbButton("重试", onRefresh, enabled = !loading, variant = ButtonVariant.Outline)
                    }
                }
            }
            if (modelIds.isNotEmpty()) {
                item(key = "filters") {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CbText("模型系列 · 按标识名称归类", style = ChatBarTheme.typography.caption,
                            color = ChatBarTheme.colors.mutedForeground)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { CbChoiceChip("全部 ${modelIds.size}", activeFamily == null, { family = null }) }
                            items(families.keys.toList(), key = { it }) { name ->
                                CbChoiceChip("$name ${families[name]}", name == activeFamily, { family = name })
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            CbText("${filtered.size} / ${modelIds.size} 个模型", Modifier.weight(1f),
                                style = ChatBarTheme.typography.caption, color = ChatBarTheme.colors.mutedForeground)
                            CbButton(if (descending) "名称 Z–A" else "名称 A–Z", { descending = !descending },
                                variant = ButtonVariant.Ghost)
                        }
                        if (selectedId.isNotBlank() && selectedId !in modelIds) {
                            CbText("当前标识不在列表中：$selectedId", style = ChatBarTheme.typography.caption,
                                color = ChatBarTheme.colors.mutedForeground)
                        }
                        CbDivider()
                    }
                }
                if (filtered.isEmpty()) {
                    item(key = "empty") {
                        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CbText("没有匹配的模型", style = ChatBarTheme.typography.heading)
                            CbText("尝试减少关键词，或切换其他系列。", color = ChatBarTheme.colors.mutedForeground)
                            CbButton("清除筛选", { query = ""; family = null }, variant = ButtonVariant.Outline)
                        }
                    }
                }
                items(filtered, key = { "model:$it" }) { id ->
                    val selected = id == selectedId
                    CbSurface(
                        modifier = Modifier.fillMaxWidth(),
                        color = if (selected) ChatBarTheme.colors.primaryAlpha else ChatBarTheme.colors.card,
                        border = BorderStroke(1.dp, if (selected) ChatBarTheme.colors.primary else ChatBarTheme.colors.border)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton) { onSelect(id) }
                                .padding(12.dp).heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                CbText(id, style = ChatBarTheme.typography.label)
                                val detail = modelDetail(id) ?: modelSeries(id)
                                CbText(if (selected) "当前选中 · $detail" else detail,
                                    style = ChatBarTheme.typography.caption, color = ChatBarTheme.colors.mutedForeground)
                            }
                            if (selected) CbIcon(AppIcons.Check, "当前选中", Modifier.size(20.dp), ChatBarTheme.colors.primary)
                        }
                    }
                }
            }
        }
    }
}

/** Name-only grouping; never used to infer model capabilities or request parameters. */
private fun modelSeries(id: String): String {
    val name = id.substringAfterLast('/').lowercase()
    return when {
        "deepseek" in name -> "DeepSeek"
        "qwen" in name -> "Qwen"
        "claude" in name -> "Claude"
        "gemini" in name -> "Gemini"
        "gpt" in name -> "GPT"
        "llama" in name -> "Llama"
        "gemma" in name -> "Gemma"
        "mistral" in name || "mixtral" in name -> "Mistral"
        "glm" in name -> "GLM"
        "kimi" in name || "moonshot" in name -> "Kimi"
        "doubao" in name -> "Doubao"
        else -> "其他"
    }
}
