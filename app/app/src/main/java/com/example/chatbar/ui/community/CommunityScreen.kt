package com.example.chatbar.ui.community

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
                            enabled = state.configured && !state.busy
                        )
                    } else {
                        CbIconButton(
                            imageVector = AppIcons.Logout,
                            contentDescription = "退出登录",
                            onClick = viewModel::signOut,
                            enabled = !state.busy
                        )
                        CbIconButton(
                            imageVector = AppIcons.UploadFile,
                            contentDescription = "上传",
                            onClick = viewModel::openUpload,
                            enabled = state.configured && !state.busy
                        )
                    }
                    CbIconButton(
                        imageVector = AppIcons.Refresh,
                        contentDescription = "刷新",
                        onClick = viewModel::refresh,
                        enabled = state.configured && !state.loading && !state.busy
                    )
                }
            )
        }
    ) { bottomInset ->
        Column(Modifier.fillMaxSize().padding(ChatBarSpacing.lg)) {
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
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CbSpinner(Modifier.size(32.dp))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
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
                                onDownload = { viewModel.prepareImport(item) },
                                onManage = if (mine && state.selectedSection == CommunitySection.MINE) {
                                    { actionItem = item }
                                } else {
                                    null
                                }
                            )
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
    onDownload: () -> Unit,
    onManage: (() -> Unit)?
) {
    CbCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onManage != null) {
                    Modifier.combinedClickable(onClick = {}, onLongClick = onManage)
                } else {
                    Modifier
                }
            ),
        border = BorderStroke(1.dp, ChatBarTheme.colors.border.copy(alpha = 0.72f)),
        elevation = ChatBarElevation.low
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (item.type == CommunityItemType.CHARACTER && previewUrl != null) {
                CommunityPreviewImage(
                    url = previewUrl,
                    contentDescription = "${item.title} 预览图"
                )
                Spacer(Modifier.width(ChatBarSpacing.md))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypePill(item.type)
                    Spacer(Modifier.width(ChatBarSpacing.sm))
                    CbText(
                        item.title,
                        style = ChatBarTheme.typography.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (item.description.isNotBlank()) {
                    Spacer(Modifier.height(ChatBarSpacing.sm))
                    CbText(
                        item.description,
                        color = ChatBarTheme.colors.mutedForeground,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (item.tags.isNotEmpty()) {
                    Spacer(Modifier.height(ChatBarSpacing.sm))
                    CbText(
                        item.tags.joinToString("  #", prefix = "#"),
                        color = ChatBarTheme.colors.primary,
                        style = ChatBarTheme.typography.caption,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(ChatBarSpacing.sm))
                CbText(
                    "${item.authorName} · ${formatBytes(item.sizeBytes)} · ${item.downloadCount} 次下载 · ${item.createdAt.take(10)}",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(ChatBarSpacing.md))
            Column(horizontalAlignment = Alignment.End) {
                CbButton(
                    text = "下载",
                    onClick = onDownload,
                    enabled = !busy,
                    size = ButtonSize.Sm,
                    variant = ButtonVariant.Secondary
                )
                if (mine && onManage != null) {
                    Spacer(Modifier.height(ChatBarSpacing.sm))
                    CbIconButton(
                        imageVector = AppIcons.Edit,
                        contentDescription = "管理社区条目",
                        onClick = onManage,
                        enabled = !busy
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
    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 96.dp)
            .clip(RoundedCornerShape(ChatBarShape.sm))
            .background(ChatBarTheme.colors.surfaceSubtle)
            .border(1.dp, ChatBarTheme.colors.border, RoundedCornerShape(ChatBarShape.sm))
    ) {
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
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
