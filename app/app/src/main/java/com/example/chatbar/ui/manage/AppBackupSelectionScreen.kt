package com.example.chatbar.ui.manage

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.chatbar.domain.backup.*
import com.example.chatbar.ui.kit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class BackupSelectionPage { OVERVIEW, SETTINGS, CHARACTERS }

/** One hierarchy per page. Fixed estimate/action footer stays reachable on compact phones. */
@Composable
internal fun AppBackupSelectionScreen(
    catalogState: BackupCatalogState,
    initial: AppBackupSelection?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onContinue: (AppBackupSelection) -> Unit
) {
    var selection by remember { mutableStateOf(initial) }
    var page by remember { mutableStateOf(BackupSelectionPage.OVERVIEW) }
    var query by remember { mutableStateOf("") }
    var expandedGroups by remember { mutableStateOf(emptySet<String>()) }
    var expandedCards by remember { mutableStateOf(emptySet<String>()) }
    var expandedSession by remember { mutableStateOf<String?>(null) }
    var estimate by remember { mutableStateOf<BackupSizeEstimate?>(null) }
    var estimating by remember { mutableStateOf(false) }
    var estimateError by remember { mutableStateOf<String?>(null) }
    val catalog = catalogState.catalog
    LaunchedEffect(catalog) {
        if (catalog != null) selection = selection?.normalized(catalog) ?: catalog.allSelection()
    }
    LaunchedEffect(selection, catalog) {
        estimateError = null
        estimate = null
        val chosen = selection
        if (catalog == null || chosen == null || chosen.isEmpty) { estimating = false; return@LaunchedEffect }
        estimating = true
        delay(120)
        try { estimate = withContext(Dispatchers.IO) { catalog.estimate(chosen) } }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { estimateError = error.message ?: "无法估算大小" }
        finally { estimating = false }
    }
    fun back() {
        if (page == BackupSelectionPage.OVERVIEW) onDismiss()
        else { page = BackupSelectionPage.OVERVIEW; query = "" }
    }
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { back() }
        Column(Modifier.fillMaxSize().background(ChatBarTheme.colors.background).navigationBarsPadding().imePadding()) {
            CbTopBar(
                title = when (page) {
                    BackupSelectionPage.OVERVIEW -> "选择导出内容"
                    BackupSelectionPage.SETTINGS -> "全局设置项"
                    BackupSelectionPage.CHARACTERS -> "角色卡与会话"
                },
                navigation = { CbIconButton(AppIcons.ArrowBack, "返回", ::back) },
                actions = { CbIconButton(AppIcons.Refresh, "刷新数据清单", onRefresh, enabled = !catalogState.loading) }
            )
            if (catalog != null && selection != null) {
                val chosen = requireNotNull(selection)
                Row(Modifier.fillMaxWidth().padding(horizontal = ChatBarSpacing.md, vertical = ChatBarSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                    CbButton("全选", { selection = when (page) {
                        BackupSelectionPage.OVERVIEW -> catalog.allSelection()
                        BackupSelectionPage.SETTINGS -> chosen.copy(globalOptions = BackupSettingOptions.ids)
                        BackupSelectionPage.CHARACTERS -> chosen.withAllSessions(catalog)
                    } }, Modifier.weight(1f), variant = ButtonVariant.Outline)
                    if (page == BackupSelectionPage.OVERVIEW) CbButton("轻量", { selection = catalog.lightSelection() }, Modifier.weight(1f), variant = ButtonVariant.Outline)
                    CbButton("清空", { selection = when (page) {
                        BackupSelectionPage.OVERVIEW -> AppBackupSelection()
                        BackupSelectionPage.SETTINGS -> chosen.copy(globalOptions = emptySet())
                        BackupSelectionPage.CHARACTERS -> chosen.copy(characterIds = emptySet(), sessions = emptyMap())
                    } }, Modifier.weight(1f), variant = ButtonVariant.Ghost)
                }
                if (page != BackupSelectionPage.OVERVIEW) CbInput(query, { query = it },
                    Modifier.fillMaxWidth().padding(horizontal = ChatBarSpacing.md), placeholder = "搜索名称")
                LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(ChatBarSpacing.md), verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                    if (page == BackupSelectionPage.OVERVIEW) {
                        item {
                            BackupGroupRow("全局设置", "已选 ${chosen.globalOptions.size} / ${BackupSettingOptions.all.size} 项",
                                chosen.globalOptions.isNotEmpty(), { enabled ->
                                    selection = chosen.copy(globalOptions = if (enabled) BackupSettingOptions.ids else emptySet())
                                }, onDetails = { page = BackupSelectionPage.SETTINGS })
                        }
                        item {
                            CbCard {
                                BackupChoiceRow("角色卡与会话", "${chosen.characterIds.size} 张角色卡 · ${chosen.sessions.size} 个会话",
                                    chosen.characterIds.isNotEmpty(), { enabled ->
                                        selection = if (enabled) chosen.withAllSessions(catalog)
                                        else chosen.copy(characterIds = emptySet(), sessions = emptyMap())
                                    }, onDetails = { page = BackupSelectionPage.CHARACTERS })
                                CbDivider()
                                BackupAllSessionContentRows(chosen) { selection = it }
                            }
                        }
                        item {
                            BackupGroupRow("生图工作室设置", "参数、参考图、草稿、撤销和 AI 设计会话",
                                chosen.studioSettings, { selection = chosen.copy(studioSettings = it) })
                        }
                        item {
                            CbCard {
                                BackupChoiceRow("生图工作室历史", "${catalog.historyCount} 个生成批次", chosen.studioHistory,
                                    { selection = chosen.copy(studioHistory = it) })
                                AnimatedVisibility(chosen.studioHistory) {
                                    Column {
                                        CbDivider()
                                        BackupChoiceRow("包含历史图片", if (chosen.studioHistoryImages) "保存原始图片字节" else "仅保留 Seed、提示词和生图参数",
                                            chosen.studioHistoryImages, { selection = chosen.copy(studioHistoryImages = it) })
                                    }
                                }
                            }
                        }
                        item {
                            CbCard {
                                BackupChoiceRow("朋友圈", "${catalog.momentCount} 条动态 · 文字、点赞与任务", chosen.moments,
                                    { selection = chosen.copy(moments = it) })
                                AnimatedVisibility(chosen.moments) {
                                    Column {
                                        CbDivider()
                                        BackupChoiceRow("包含朋友圈图片", if (chosen.momentsImages) "保存正文配图；头像随发布人信息保留"
                                            else "省略正文配图；保留头像、文字与生图提示词", chosen.momentsImages,
                                            { selection = chosen.copy(momentsImages = it) })
                                    }
                                }
                            }
                        }
                        item {
                            CbText("所需关联模型、格式卡和世界书自动带入。轻量方案省略聊天图片、语音、会话存档、工作室历史原图和朋友圈配图。",
                                color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                        }
                        item { CbText("导入默认覆盖勾选范围，未勾选内容保留；也可选择增量更新或增量导入。",
                            color = ChatBarTheme.colors.warning, style = ChatBarTheme.typography.caption) }
                    } else if (page == BackupSelectionPage.SETTINGS) {
                        BackupSettingOptions.all.filter { query.isBlank() || it.label.contains(query, true) || it.group.contains(query, true) }
                            .groupBy { it.group }.forEach { (group, options) ->
                                item(key = "group:$group") {
                                    val selectedCount = options.count { it.id in chosen.globalOptions }
                                    BackupGroupRow(group, "$selectedCount / ${options.size} 项", selectedCount > 0,
                                        { enabled -> selection = chosen.copy(globalOptions = if (enabled)
                                            chosen.globalOptions + options.map { it.id } else chosen.globalOptions - options.map { it.id }.toSet()) },
                                        onDetails = { expandedGroups = if (group in expandedGroups) expandedGroups - group else expandedGroups + group },
                                        expanded = group in expandedGroups || query.isNotBlank())
                                }
                                if (group in expandedGroups || query.isNotBlank()) items(options, key = { it.id }) { option ->
                                    BackupChoiceRow(option.label, null, option.id in chosen.globalOptions,
                                        { enabled -> selection = chosen.copy(globalOptions = if (enabled) chosen.globalOptions + option.id else chosen.globalOptions - option.id) })
                                }
                            }
                    } else {
                        item(key = "all-session-content") {
                            CbCard { BackupAllSessionContentRows(chosen) { selection = it } }
                        }
                        item { CbText("先选择角色卡，再选择对应会话。长期记忆、阅读位置和会话草稿随会话导出。",
                            color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption) }
                        catalog.characters.filter { card -> query.isBlank() || card.name.contains(query, true) ||
                            catalog.sessions.any { it.characterId == card.id && it.title.contains(query, true) } }.forEach { card ->
                            val sessions = catalog.sessions.filter { it.characterId == card.id }
                            val cardSelected = card.id in chosen.characterIds
                            item(key = "card:${card.id}") {
                                CbSurface(Modifier.fillMaxWidth(), color = ChatBarTheme.colors.secondary,
                                    border = BorderStroke(1.dp, ChatBarTheme.colors.border)) {
                                    BackupChoiceRow(card.name, "已选 ${sessions.count { it.id in chosen.sessions }} / ${sessions.size} 个会话",
                                        cardSelected, { selection = chosen.withCharacter(card.id, it, catalog) },
                                        onDetails = { expandedCards = if (card.id in expandedCards) expandedCards - card.id else expandedCards + card.id },
                                        expanded = card.id in expandedCards || query.isNotBlank(), kind = "角色卡", leading = AppIcons.Face)
                                }
                            }
                            if (card.id in expandedCards || query.isNotBlank()) {
                                item(key = "bulk:${card.id}") {
                                    BackupSessionBranch {
                                        Column {
                                            CbText("${card.name} 的会话", Modifier.padding(horizontal = ChatBarSpacing.sm, vertical = ChatBarSpacing.xs),
                                                style = ChatBarTheme.typography.caption, color = ChatBarTheme.colors.mutedForeground)
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                                                CbButton("全选会话", { selection = chosen.withSessions(sessions.map { it.id }, true) },
                                                    Modifier.weight(1f), enabled = cardSelected, variant = ButtonVariant.Ghost)
                                                CbButton("清空会话", { selection = chosen.withSessions(sessions.map { it.id }, false) },
                                                    Modifier.weight(1f), enabled = cardSelected, variant = ButtonVariant.Ghost)
                                            }
                                        }
                                    }
                                }
                                items(sessions.filter { query.isBlank() || card.name.contains(query, true) || it.title.contains(query, true) }, key = { "session:${it.id}" }) { session ->
                                    val choice = chosen.sessions[session.id]
                                    BackupSessionBranch {
                                        CbSurface(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, ChatBarTheme.colors.border)) {
                                            Column {
                                                BackupChoiceRow(session.title,
                                                    if (!cardSelected) "请先勾选角色卡" else if (choice == null) "未选择" else buildList {
                                                        add(if (choice.images) "图片" else "无图片")
                                                        if (choice.voice) add("语音")
                                                        if (choice.archives) add("存档")
                                                    }.joinToString(" · "),
                                                    choice != null, { enabled -> selection = chosen.copy(sessions = if (enabled)
                                                        chosen.sessions + (session.id to chosen.newSessionChoice()) else chosen.sessions - session.id) },
                                                    enabled = cardSelected,
                                                    onDetails = if (choice != null) { { expandedSession = if (expandedSession == session.id) null else session.id } } else null,
                                                    expanded = expandedSession == session.id, kind = "会话", leading = AppIcons.Chat)
                                                AnimatedVisibility(choice != null && expandedSession == session.id) {
                                                    if (choice != null) Column {
                                                        CbDivider()
                                                        BackupChoiceRow("聊天图片", "用户发送与 AI 生成的图片", choice.images,
                                                            { selection = chosen.copy(sessions = chosen.sessions + (session.id to choice.copy(images = it))) })
                                                        BackupChoiceRow("会话语音", "已生成音频与语音进度", choice.voice,
                                                            { selection = chosen.copy(sessions = chosen.sessions + (session.id to choice.copy(voice = it))) })
                                                        BackupChoiceRow("会话存档", "独立存档原包，包含其原有图片和语音", choice.archives,
                                                            { selection = chosen.copy(sessions = chosen.sessions + (session.id to choice.copy(archives = it))) })
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val orphanCount = catalog.sessions.count { session -> catalog.characters.none { it.id == session.characterId } }
                        if (orphanCount > 0) item { CbText("$orphanCount 个会话的角色卡已缺失，无法选择导出。",
                            color = ChatBarTheme.colors.warning, style = ChatBarTheme.typography.caption) }
                    }
                    if (catalogState.loading) item { CbText("正在刷新清单…", color = ChatBarTheme.colors.mutedForeground) }
                }
            } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (catalogState.loading) CbSpinner() else CbText(catalogState.error ?: "没有可读取的本地数据")
            }
            CbDivider()
            Column(Modifier.fillMaxWidth().padding(ChatBarSpacing.md), verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)) {
                AnimatedContent(when {
                    selection?.isEmpty != false -> "尚未选择数据"
                    estimating -> "正在估算…"
                    estimate != null -> "预计大小约 ${backupSize(requireNotNull(estimate).bytes)}"
                    else -> "大小暂不可用"
                }, label = "backupEstimate") { CbText(it, style = ChatBarTheme.typography.label) }
                CbText(estimateError ?: if ((estimate?.unknownResources ?: 0) > 0) "部分外部文件大小未知；最终大小随压缩和最新数据变化。"
                    else "按所选数据与去重资源估算；压缩后通常更小。",
                    color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                CbButton(if (page == BackupSelectionPage.OVERVIEW) "下一步" else "返回总览", {
                    if (page == BackupSelectionPage.OVERVIEW) onContinue(requireNotNull(selection)) else { page = BackupSelectionPage.OVERVIEW; query = "" }
                }, Modifier.fillMaxWidth(), enabled = selection?.isEmpty == false && !catalogState.loading && catalogState.error == null && estimateError == null)
            }
        }
    }
}

@Composable
private fun BackupGroupRow(
    title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    onDetails: (() -> Unit)? = null, expanded: Boolean = false
) {
    CbCard { BackupChoiceRow(title, description, checked, onCheckedChange, onDetails = onDetails, expanded = expanded) }
}

@Composable
private fun BackupAllSessionContentRows(selection: AppBackupSelection, onChange: (AppBackupSelection) -> Unit) {
    Column {
        BackupSessionContent.entries.forEachIndexed { index, content ->
            if (index > 0) CbDivider()
            val label = when (content) {
                BackupSessionContent.IMAGES -> "图片"
                BackupSessionContent.VOICE -> "语音"
                BackupSessionContent.ARCHIVES -> "存档"
            }
            val default = when (content) {
                BackupSessionContent.IMAGES -> selection.sessionImagesDefault
                BackupSessionContent.VOICE -> selection.sessionVoiceDefault
                BackupSessionContent.ARCHIVES -> selection.sessionArchivesDefault
            }
            val included = selection.sessions.values.count { choice -> when (content) {
                BackupSessionContent.IMAGES -> choice.images
                BackupSessionContent.VOICE -> choice.voice
                BackupSessionContent.ARCHIVES -> choice.archives
            } }
            val checked = if (selection.sessions.isEmpty()) default else included > 0
            val title = "全部会话$label" + if (included > 0 && included < selection.sessions.size) "（部分已开启）" else ""
            BackupChoiceRow(title, buildString {
                if (selection.sessions.isNotEmpty()) append("$included / ${selection.sessions.size} 个会话包含；")
                append("新选默认${if (default) "包含" else "不含"}")
            }, checked, { onChange(selection.withAllSessionContent(content, it)) })
        }
    }
}

/** Separate lazy items retain the same inset/rail, making ownership visible at every row. */
@Composable
private fun BackupSessionBranch(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = ChatBarSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(ChatBarTheme.colors.border))
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun BackupChoiceRow(
    title: String, description: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true, onDetails: (() -> Unit)? = null, expanded: Boolean = false,
    kind: String? = null, leading: ImageVector? = null
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = ChatBarSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        CbCheckbox(checked, onCheckedChange, enabled = enabled, contentDescription = if (kind == null) title else "$kind：$title")
        Column(Modifier.weight(1f).clickable(enabled = enabled, onClickLabel = if (onDetails == null) "选择$title" else "查看$title") {
            if (onDetails != null) onDetails() else onCheckedChange(!checked)
        }.padding(vertical = ChatBarSpacing.sm)) {
            if (kind != null) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)) {
                val kindColor = if (kind == "角色卡") ChatBarTheme.colors.primary else ChatBarTheme.colors.mutedForeground
                if (leading != null) CbIcon(leading, null, Modifier.size(14.dp), kindColor)
                CbText(kind, color = kindColor, style = ChatBarTheme.typography.caption)
            }
            CbText(title, color = if (!enabled) ChatBarTheme.colors.mutedForeground
                else if (kind == "角色卡") ChatBarTheme.colors.secondaryForeground else ChatBarTheme.colors.foreground,
                style = if (kind == "角色卡") ChatBarTheme.typography.title else ChatBarTheme.typography.label,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (description != null) CbText(description, color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onDetails != null) CbIconButton(if (expanded) AppIcons.ExpandLess else AppIcons.KeyboardArrowRight, "查看$title", onDetails)
    }
}

internal fun backupSize(bytes: Long): String = android.text.format.Formatter.formatShortFileSize(com.example.chatbar.ChatBarApp.instance, bytes)
