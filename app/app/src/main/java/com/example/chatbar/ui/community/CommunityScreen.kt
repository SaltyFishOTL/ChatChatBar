package com.example.chatbar.ui.community

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.chatbar.domain.community.CommunityItem
import com.example.chatbar.domain.community.CommunityItemType
import com.example.chatbar.domain.community.CommunityPendingImport
import com.example.chatbar.domain.community.CommunityPreviewCache
import com.example.chatbar.domain.community.CommunityUploadCandidate
import com.example.chatbar.ui.kit.AppIcons
import com.example.chatbar.ui.kit.ButtonSize
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbCard
import com.example.chatbar.ui.kit.CbChoiceChip
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbScaffold
import com.example.chatbar.ui.kit.CbSpinner
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbTabs
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarElevation
import com.example.chatbar.ui.kit.ChatBarShape
import com.example.chatbar.ui.kit.ChatBarSpacing
import com.example.chatbar.ui.kit.ChatBarTheme

@Composable
fun CommunityScreen(
    viewModel: CommunityViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val session by viewModel.session.collectAsState()
    val candidates by viewModel.candidates.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var actionItem by remember { mutableStateOf<CommunityItem?>(null) }
    var updateItem by remember { mutableStateOf<CommunityItem?>(null) }
    var deleteItem by remember { mutableStateOf<CommunityItem?>(null) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.clearMessage()
    }

    val visibleItems = if (state.selectedSection == CommunitySection.MINE) state.myItems else state.items
    val filteredItems = visibleItems.filtered(state.query, state.selectedType)
    val listState = rememberLazyListState()
    val activeHasMore = if (state.selectedSection == CommunitySection.MINE) state.hasMoreMyItems else state.hasMoreItems

    LaunchedEffect(state.selectedSection, filteredItems.size, activeHasMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisibleIndex ->
                val shouldPrefetch = filteredItems.isNotEmpty() &&
                    activeHasMore &&
                    lastVisibleIndex >= filteredItems.lastIndex - PREFETCH_VISIBLE_THRESHOLD
                if (shouldPrefetch) viewModel.loadNextPage()
            }
    }

    CbScaffold(
        topBar = {
            CbTopBar(
                title = "社区",
                actions = {
                    if (session == null) {
                        CbIconButton(
                            imageVector = AppIcons.Login,
                            contentDescription = "Discord 登录",
                            onClick = {
                                runCatching { uriHandler.openUri(viewModel.loginUrl()) }
                                    .onFailure {
                                        Toast.makeText(context, "无法打开 Discord 登录", Toast.LENGTH_SHORT).show()
                                    }
                            },
                            enabled = state.enabled && state.configured && !state.busy
                        )
                    } else {
                        CbIconButton(
                            imageVector = AppIcons.Logout,
                            contentDescription = "退出登录",
                            onClick = viewModel::signOut,
                            enabled = state.enabled && !state.busy
                        )
                        CbIconButton(
                            imageVector = AppIcons.UploadFile,
                            contentDescription = "上传",
                            onClick = viewModel::openUpload,
                            enabled = state.enabled && state.configured && !state.busy
                        )
                    }
                    CbIconButton(
                        imageVector = AppIcons.Refresh,
                        contentDescription = "刷新",
                        onClick = viewModel::refresh,
                        enabled = state.enabled && state.configured && !state.loading && !state.busy
                    )
                }
            )
        }
    ) { bottomInset ->
        Column(Modifier.fillMaxSize().padding(ChatBarSpacing.lg)) {
            if (!state.enabled) {
                CommunityDisabledState()
                return@CbScaffold
            }
            if (!state.configured) {
                ConfigMissingState()
                return@CbScaffold
            }
            SearchAndFilters(
                query = state.query,
                selectedSection = state.selectedSection,
                selectedType = state.selectedType,
                onQueryChange = viewModel::setQuery,
                onSectionSelected = viewModel::setSection,
                onTypeSelected = viewModel::setTypeFilter
            )
            Spacer(Modifier.height(ChatBarSpacing.md))
            if (state.loading && filteredItems.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CbSpinner(Modifier.size(32.dp))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.md),
                    contentPadding = PaddingValues(bottom = bottomInset + ChatBarSpacing.xl)
                ) {
                    if (filteredItems.isEmpty()) {
                        item {
                            EmptyCommunityState(
                                section = state.selectedSection,
                                loggedIn = session != null
                            )
                        }
                    } else {
                        items(filteredItems, key = { it.id }) { item ->
                            val mine = session?.userId == item.authorUserId
                            CommunityItemCard(
                                item = item,
                                previewUrl = viewModel.previewUrl(item),
                                busy = state.busy,
                                mine = mine && state.selectedSection == CommunitySection.MINE,
                                onOpen = { viewModel.openDetail(item) },
                                onDownload = { viewModel.prepareImport(item) },
                                onManage = if (mine && state.selectedSection == CommunitySection.MINE) {
                                    { actionItem = item }
                                } else {
                                    null
                                }
                            )
                        }
                        if (state.loadingMore || (state.loading && filteredItems.isNotEmpty())) {
                            item(key = "community-page-loading") {
                                PageLoadingRow()
                            }
                        }
                    }
                }
            }
        }
    }

    actionItem?.let { item ->
        CommunityItemActionDialog(
            item = item,
            onDismiss = { actionItem = null },
            onUpdate = {
                updateItem = item
                actionItem = null
            },
            onDelete = {
                deleteItem = item
                actionItem = null
            }
        )
    }

    updateItem?.let { item ->
        ConfirmUpdateDialog(
            item = item,
            busy = state.busy,
            onDismiss = { updateItem = null },
            onConfirm = {
                viewModel.updateCommunityItem(item)
                updateItem = null
            }
        )
    }

    deleteItem?.let { item ->
        ConfirmDeleteDialog(
            item = item,
            busy = state.busy,
            onDismiss = { deleteItem = null },
            onConfirm = {
                viewModel.deleteCommunityItem(item)
                deleteItem = null
            }
        )
    }

    if (state.uploadOpen) {
        UploadDialog(
            state = state,
            candidates = candidates,
            busy = state.busy,
            onDismiss = viewModel::closeUpload,
            onType = viewModel::setUploadType,
            onCandidate = viewModel::selectCandidate,
            onTitle = viewModel::setUploadTitle,
            onDescription = viewModel::setUploadDescription,
            onTags = viewModel::setUploadTags,
            onSubmit = viewModel::submitUpload
        )
    }

    state.pendingImport?.let { pending ->
        ImportDialog(
            pending = pending,
            busy = state.busy,
            onDismiss = viewModel::dismissImport,
            onNew = { viewModel.importPending(asOverwrite = false) },
            onOverwrite = { viewModel.importPending(asOverwrite = true) }
        )
    }

    state.detailItem?.let { item ->
        CommunityDetailDialog(
            item = item,
            detail = state.detail,
            loading = state.detailLoading,
            busy = state.busy,
            onDismiss = viewModel::dismissDetail,
            onDownload = {
                viewModel.dismissDetail()
                viewModel.prepareImport(item)
            }
        )
    }
}

private const val PREFETCH_VISIBLE_THRESHOLD = 6

@Composable
private fun PageLoadingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ChatBarSpacing.md),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CbSpinner(Modifier.size(20.dp))
        Spacer(Modifier.width(ChatBarSpacing.sm))
        CbText("加载更多", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
    }
}

@Composable
private fun SearchAndFilters(
    query: String,
    selectedSection: CommunitySection,
    selectedType: CommunityItemType?,
    onQueryChange: (String) -> Unit,
    onSectionSelected: (CommunitySection) -> Unit,
    onTypeSelected: (CommunityItemType?) -> Unit
) {
    CbTabs(
        items = CommunitySection.entries.map { it.label },
        selectedIndex = CommunitySection.entries.indexOf(selectedSection).coerceAtLeast(0),
        onSelected = { index -> onSectionSelected(CommunitySection.entries[index]) }
    )
    Spacer(Modifier.height(ChatBarSpacing.sm))
    CbInput(
        value = query,
        onValueChange = onQueryChange,
        placeholder = "搜索标题、描述、标签",
        singleLine = true
    )
    Spacer(Modifier.height(ChatBarSpacing.sm))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
    ) {
        CbChoiceChip(
            text = "全部",
            selected = selectedType == null,
            onClick = { onTypeSelected(null) }
        )
        CommunityItemType.entries.forEach { type ->
            CbChoiceChip(
                text = type.label,
                selected = selectedType == type,
                onClick = { onTypeSelected(type) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommunityItemCard(
    item: CommunityItem,
    previewUrl: String?,
    busy: Boolean,
    mine: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onManage: (() -> Unit)?
) {
    val characterPreviewUrl = previewUrl.takeIf { item.type == CommunityItemType.CHARACTER }
    CbCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onManage),
        border = BorderStroke(1.dp, ChatBarTheme.colors.border.copy(alpha = 0.72f)),
        elevation = ChatBarElevation.low
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.md)) {
            if (characterPreviewUrl != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
                ) {
                    CommunityPreviewImage(
                        url = characterPreviewUrl,
                        contentDescription = "${item.title} 预览图"
                    )
                    CbButton(
                        text = "下载",
                        onClick = onDownload,
                        enabled = !busy,
                        size = ButtonSize.Sm,
                        variant = ButtonVariant.Secondary
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                    TypePill(item.type)
                    CbText(
                        item.authorName.ifBlank { "匿名" },
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                CbText(
                    item.title,
                    style = ChatBarTheme.typography.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.description.isNotBlank()) {
                    CbText(
                        item.description,
                        color = ChatBarTheme.colors.mutedForeground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (item.tags.isNotEmpty()) {
                    CbText(
                        item.tags.joinToString("  #", prefix = "#"),
                        color = ChatBarTheme.colors.primary,
                        style = ChatBarTheme.typography.caption,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                CbText(
                    "${formatBytes(item.sizeBytes)} · ${item.downloadCount} 次下载 · ${item.createdAt.take(10)}",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (characterPreviewUrl == null || (mine && onManage != null)) {
            Spacer(Modifier.height(ChatBarSpacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (characterPreviewUrl == null) {
                    CbButton(
                        text = "下载",
                        onClick = onDownload,
                        enabled = !busy,
                        size = ButtonSize.Sm,
                        variant = ButtonVariant.Secondary
                    )
                }
                if (mine && onManage != null) {
                    Spacer(Modifier.weight(1f))
                    CbButton(
                        text = "管理",
                        onClick = onManage,
                        enabled = !busy,
                        size = ButtonSize.Sm,
                        variant = ButtonVariant.Ghost
                    )
                }
            }
        }
    }
}

@Composable
private fun CommunityPreviewImage(
    url: String,
    contentDescription: String
) {
    val context = LocalContext.current
    val request = remember(url, context) {
        CommunityPreviewCache.request(context, url)
    }
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(ChatBarShape.sm))
            .background(ChatBarTheme.colors.surfaceSubtle)
            .border(1.dp, ChatBarTheme.colors.border, RoundedCornerShape(ChatBarShape.sm))
    ) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun CommunityDetailDialog(
    item: CommunityItem,
    detail: CommunityItemDetail?,
    loading: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    CbDialog(
        onDismissRequest = onDismiss,
        title = item.title,
        modifier = Modifier.heightIn(max = 760.dp),
        dismiss = {
            CbButton("关闭", onDismiss, variant = ButtonVariant.Ghost, enabled = !loading)
        },
        confirm = {
            CbButton("下载", onDownload, enabled = !busy && !loading, variant = ButtonVariant.Secondary)
        }
    ) {
        Column(
            Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.md)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                TypePill(item.type)
                CbText(
                    "${item.authorName} · ${formatBytes(item.sizeBytes)} · ${item.downloadCount} 次下载",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
            if (item.description.isNotBlank()) DetailField("简介", item.description)
            if (item.tags.isNotEmpty()) DetailField("标签", item.tags.joinToString("  #", prefix = "#"))
            if (loading || detail == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CbSpinner(Modifier.size(20.dp))
                    Spacer(Modifier.width(ChatBarSpacing.sm))
                    CbText("正在加载卡内容", color = ChatBarTheme.colors.mutedForeground)
                }
            } else {
                when (detail) {
                    is CommunityItemDetail.Character -> CharacterDetailContent(detail)
                    is CommunityItemDetail.Format -> FormatDetailContent(detail)
                    is CommunityItemDetail.WorldBook -> WorldBookDetailContent(detail)
                }
            }
        }
    }
}

@Composable
private fun CharacterDetailContent(detail: CommunityItemDetail.Character) {
    val card = detail.data.card
    DetailSection("角色卡") {
        DetailField("名称", card.name)
        DetailField("编辑模式", if (card.editMode.name == "FREEFORM") "自由模式" else "分段模式")
        DetailField("作者", card.creator)
        DetailField("版本", card.characterVersion)
        DetailField("开场白", card.greeting)
        if (card.alternateGreetings.isNotEmpty()) {
            DetailField("备用开场白", card.alternateGreetings.joinToString("\n\n"))
        }
        DetailField("基本设定", card.basicSetting)
        DetailField("自由人物设定", card.freeformCharacterText)
        DetailField("系统提示词", card.systemPrompt)
        DetailField("历史后置指令", card.postHistoryInstructions)
        DetailField("对话示例", card.mesExample)
        DetailField("创作者备注", card.creatorNotes)
        DetailField("NovelAI 默认风格提示词", card.defaultImagePrompt)
    }
    DetailSection("人物 ${card.characters.size}") {
        card.characters.forEachIndexed { index, character ->
            DetailField("${index + 1}. ${character.name}", buildString {
                appendLabeled("简介", character.profile)
                appendLabeled("外貌", character.appearance)
                appendLabeled("服装", character.clothing)
                appendLabeled("能力", character.abilities)
                appendLabeled("习惯", character.habits)
                appendLabeled("背景", character.background)
                appendLabeled("关系", character.relationships)
                appendLabeled("语气", character.speakingStyle)
                appendLabeled("生图提示词", character.imagePrompt)
            })
        }
    }
    if (detail.data.worldBooks.isNotEmpty()) {
        DetailSection("绑定世界书 ${detail.data.worldBooks.size}") {
            detail.data.worldBooks.forEach { book ->
                DetailField(book.name, "${book.entries.size} 条目")
            }
        }
    }
    if (detail.data.documents.isNotEmpty()) {
        DetailSection("参考文档 ${detail.data.documents.size}") {
            detail.data.documents.forEach { doc ->
                DetailField("${doc.fileName} (${doc.fileType})", doc.content.trimForDetail())
            }
        }
    }
}

@Composable
private fun FormatDetailContent(detail: CommunityItemDetail.Format) {
    DetailSection("格式卡") {
        DetailField("名称", detail.data.name)
        DetailField("内容", detail.data.content)
    }
}

@Composable
private fun WorldBookDetailContent(detail: CommunityItemDetail.WorldBook) {
    val book = detail.data.book
    DetailSection("世界书") {
        DetailField("名称", book.name)
        DetailField("简介", book.description)
        DetailField("参数", "扫描 ${book.scanDepth} 条 · ${book.entries.size} 条目")
    }
    DetailSection("条目 ${book.entries.size}") {
        book.entries.forEachIndexed { index, entry ->
            DetailField("${index + 1}. ${entry.name.ifBlank { "未命名条目" }}", buildString {
                appendLabeled("触发词", entry.keys.joinToString(", "))
                appendLabeled("内容", entry.content)
                appendLabeled("位置", entry.position.name)
                appendLabeled("顺序", entry.insertionOrder.toString())
                appendLabeled("概率", "${entry.probability}%")
                appendLabeled("备注", entry.comment)
            })
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    CbSurface(
        modifier = Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.surfaceSubtle,
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(Modifier.padding(ChatBarSpacing.md), verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
            CbText(title, style = ChatBarTheme.typography.label)
            content()
        }
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    val text = value.trimForDetail()
    if (text.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CbText(label, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
        CbText(text)
    }
}

private fun String.trimForDetail(limit: Int = 1600): String {
    val text = trim()
    if (text.length <= limit) return text
    return text.take(limit).trimEnd() + "\n…内容过长，已截断"
}

private fun StringBuilder.appendLabeled(label: String, value: String) {
    val text = value.trim()
    if (text.isBlank()) return
    if (isNotEmpty()) append("\n\n")
    append(label).append("：").append(text)
}

@Composable
private fun CommunityItemActionDialog(
    item: CommunityItem,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit
) {
    CbDialog(
        onDismissRequest = onDismiss,
        title = item.title,
        dismiss = {
            CbButton("取消", onDismiss, variant = ButtonVariant.Ghost)
        }
    ) {
        ActionRow(
            icon = AppIcons.Refresh,
            title = "用同名本地卡覆盖",
            onClick = onUpdate
        )
        ActionRow(
            icon = AppIcons.Delete,
            title = "删除",
            destructive = true,
            onClick = onDelete
        )
    }
}

@Composable
private fun ConfirmUpdateDialog(
    item: CommunityItem,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    CbDialog(
        onDismissRequest = onDismiss,
        title = "覆盖社区条目",
        dismiss = {
            CbButton("取消", onDismiss, variant = ButtonVariant.Ghost, enabled = !busy)
        },
        confirm = {
            CbButton("覆盖", onConfirm, enabled = !busy, variant = ButtonVariant.Destructive)
        }
    ) {
        CbText(
            "将用本地同名${item.type.label}覆盖“${item.title}”。社区下载包会更新，下载次数保留。",
            color = ChatBarTheme.colors.mutedForeground
        )
    }
}

@Composable
private fun ConfirmDeleteDialog(
    item: CommunityItem,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    CbDialog(
        onDismissRequest = onDismiss,
        title = "删除社区条目",
        dismiss = {
            CbButton("取消", onDismiss, variant = ButtonVariant.Ghost, enabled = !busy)
        },
        confirm = {
            CbButton("删除", onConfirm, enabled = !busy, variant = ButtonVariant.Destructive)
        }
    ) {
        CbText("确定删除“${item.title}”？", color = ChatBarTheme.colors.mutedForeground)
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val color = if (destructive) ChatBarTheme.colors.destructive else ChatBarTheme.colors.foreground
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = ChatBarSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CbIcon(icon, null, Modifier.size(18.dp), color)
        Spacer(Modifier.width(ChatBarSpacing.md))
        CbText(title, color = color)
    }
}

@Composable
private fun TypePill(type: CommunityItemType) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(ChatBarShape.xs))
            .background(ChatBarTheme.colors.primaryAlpha)
            .padding(horizontal = ChatBarSpacing.sm, vertical = 4.dp)
    ) {
        CbText(type.label, color = ChatBarTheme.colors.primary, style = ChatBarTheme.typography.caption)
    }
}

@Composable
private fun UploadDialog(
    state: CommunityUiState,
    candidates: List<CommunityUploadCandidate>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onType: (CommunityItemType) -> Unit,
    onCandidate: (CommunityUploadCandidate) -> Unit,
    onTitle: (String) -> Unit,
    onDescription: (String) -> Unit,
    onTags: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val selectedIndex = CommunityItemType.entries.indexOf(state.uploadType).coerceAtLeast(0)
    CbDialog(
        onDismissRequest = onDismiss,
        title = "上传到社区",
        dismiss = {
            CbButton("取消", onDismiss, variant = ButtonVariant.Ghost, enabled = !busy)
        },
        confirm = {
            CbButton("上传", onSubmit, enabled = !busy && state.selectedLocalId != null)
        }
    ) {
        CbTabs(
            items = CommunityItemType.entries.map { it.label },
            selectedIndex = selectedIndex,
            onSelected = { index -> onType(CommunityItemType.entries[index]) }
        )
        Spacer(Modifier.height(ChatBarSpacing.md))
        CandidateList(
            candidates = candidates,
            selectedId = state.selectedLocalId,
            onCandidate = onCandidate
        )
        Spacer(Modifier.height(ChatBarSpacing.md))
        CbInput(
            value = state.uploadTitle,
            onValueChange = onTitle,
            placeholder = "标题",
            singleLine = true
        )
        Spacer(Modifier.height(ChatBarSpacing.sm))
        CbInput(
            value = state.uploadDescription,
            onValueChange = onDescription,
            placeholder = "简介",
            singleLine = false,
            minLines = 3
        )
        Spacer(Modifier.height(ChatBarSpacing.sm))
        CbInput(
            value = state.uploadTags,
            onValueChange = onTags,
            placeholder = "标签，用逗号分隔",
            singleLine = true
        )
        if (busy) {
            Spacer(Modifier.height(ChatBarSpacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CbSpinner(Modifier.size(20.dp))
                Spacer(Modifier.width(ChatBarSpacing.sm))
                CbText("处理中", color = ChatBarTheme.colors.mutedForeground)
            }
        }
    }
}

@Composable
private fun CandidateList(
    candidates: List<CommunityUploadCandidate>,
    selectedId: String?,
    onCandidate: (CommunityUploadCandidate) -> Unit
) {
    if (candidates.isEmpty()) {
        CbSurface(
            modifier = Modifier.fillMaxWidth(),
            color = ChatBarTheme.colors.surfaceSubtle,
            border = BorderStroke(1.dp, ChatBarTheme.colors.border)
        ) {
            CbText(
                "暂无可上传条目",
                modifier = Modifier.padding(ChatBarSpacing.lg),
                color = ChatBarTheme.colors.mutedForeground
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
        verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
    ) {
        items(candidates, key = { it.id }) { candidate ->
            val selected = selectedId == candidate.id
            CbSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ChatBarShape.sm))
                    .clickable { onCandidate(candidate) },
                color = if (selected) ChatBarTheme.colors.primaryAlpha else ChatBarTheme.colors.surfaceSubtle,
                border = BorderStroke(
                    1.dp,
                    if (selected) ChatBarTheme.colors.primary else ChatBarTheme.colors.border
                )
            ) {
                Column(Modifier.padding(ChatBarSpacing.md)) {
                    CbText(candidate.title, style = ChatBarTheme.typography.label)
                    if (candidate.subtitle.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        CbText(
                            candidate.subtitle,
                            color = ChatBarTheme.colors.mutedForeground,
                            style = ChatBarTheme.typography.caption
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportDialog(
    pending: CommunityPendingImport,
    busy: Boolean,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onOverwrite: () -> Unit
) {
    CbDialog(
        onDismissRequest = onDismiss,
        title = "导入社区包",
        dismiss = {
            CbButton("取消", onDismiss, variant = ButtonVariant.Ghost, enabled = !busy)
        },
        confirm = {
            Row(horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                CbButton(
                    text = if (pending.conflictName == null) "导入" else "新建副本",
                    onClick = onNew,
                    enabled = !busy,
                    variant = ButtonVariant.Secondary
                )
                if (pending.conflictName != null) {
                    CbButton(
                        text = "覆盖",
                        onClick = onOverwrite,
                        enabled = !busy,
                        variant = ButtonVariant.Destructive
                    )
                }
            }
        }
    ) {
        TypePill(pending.item.type)
        Spacer(Modifier.height(ChatBarSpacing.md))
        CbText(pending.displayName, style = ChatBarTheme.typography.title)
        pending.conflictName?.let { name ->
            Spacer(Modifier.height(ChatBarSpacing.sm))
            CbText(
                "本地已有同名条目：$name",
                color = ChatBarTheme.colors.destructive,
                style = ChatBarTheme.typography.label
            )
        }
        if (busy) {
            Spacer(Modifier.height(ChatBarSpacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CbSpinner(Modifier.size(20.dp))
                Spacer(Modifier.width(ChatBarSpacing.sm))
                CbText("处理中", color = ChatBarTheme.colors.mutedForeground)
            }
        }
    }
}

@Composable
private fun CommunityDisabledState() {
    CbCard(Modifier.fillMaxWidth()) {
        CbText("社区已关闭", style = ChatBarTheme.typography.title)
        Spacer(Modifier.height(ChatBarSpacing.sm))
        CbText(
            "后端全局开关已关闭，社区入口和网络请求已暂停",
            color = ChatBarTheme.colors.mutedForeground
        )
    }
}

@Composable
private fun ConfigMissingState() {
    CbCard(Modifier.fillMaxWidth()) {
        CbText("Supabase 未配置", style = ChatBarTheme.typography.title)
        Spacer(Modifier.height(ChatBarSpacing.sm))
        CbText(
            "需要 CHATBAR_SUPABASE_URL 和 CHATBAR_SUPABASE_ANON_KEY",
            color = ChatBarTheme.colors.mutedForeground
        )
    }
}

@Composable
private fun EmptyCommunityState(section: CommunitySection, loggedIn: Boolean) {
    val title = when {
        section == CommunitySection.MINE && loggedIn -> "暂无我的上传"
        section == CommunitySection.MINE -> "登录后查看我的上传"
        else -> "暂无条目"
    }
    val description = when {
        section == CommunitySection.MINE && loggedIn -> "上传角色卡、格式卡或世界书后会出现在这里"
        section == CommunitySection.MINE -> "使用 Discord 登录后可管理自己上传的内容"
        else -> "换个筛选或刷新试试"
    }
    CbCard(Modifier.fillMaxWidth()) {
        CbText(title, style = ChatBarTheme.typography.title)
        Spacer(Modifier.height(ChatBarSpacing.sm))
        CbText(description, color = ChatBarTheme.colors.mutedForeground)
    }
}

private fun List<CommunityItem>.filtered(query: String, type: CommunityItemType?): List<CommunityItem> {
    val q = query.trim().lowercase()
    return filter { item ->
        val typeMatch = type == null || item.type == type
        val queryMatch = q.isBlank() ||
            item.title.lowercase().contains(q) ||
            item.description.lowercase().contains(q) ||
            item.tags.any { it.lowercase().contains(q) } ||
            item.authorName.lowercase().contains(q)
        typeMatch && queryMatch
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}
