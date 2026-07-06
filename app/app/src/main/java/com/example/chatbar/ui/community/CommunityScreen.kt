package com.example.chatbar.ui.community

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.clearMessage()
    }

    val filteredItems = state.items.filtered(state.query, state.selectedType)

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
                selectedType = state.selectedType,
                onQueryChange = viewModel::setQuery,
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
                        item { EmptyCommunityState() }
                    } else {
                        items(filteredItems, key = { it.id }) { item ->
                            CommunityItemCard(
                                item = item,
                                busy = state.busy,
                                onDownload = { viewModel.prepareImport(item) }
                            )
                        }
                    }
                }
            }
        }
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
    selectedType: CommunityItemType?,
    onQueryChange: (String) -> Unit,
    onTypeSelected: (CommunityItemType?) -> Unit
) {
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

@Composable
private fun CommunityItemCard(
    item: CommunityItem,
    busy: Boolean,
    onDownload: () -> Unit
) {
    CbCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, ChatBarTheme.colors.border.copy(alpha = 0.72f)),
        elevation = ChatBarElevation.low
    ) {
        Row(verticalAlignment = Alignment.Top) {
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
            CbButton(
                text = "下载",
                onClick = onDownload,
                enabled = !busy,
                size = ButtonSize.Sm,
                variant = ButtonVariant.Secondary
            )
        }
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
private fun EmptyCommunityState() {
    CbCard(Modifier.fillMaxWidth()) {
        CbText("暂无条目", style = ChatBarTheme.typography.title)
        Spacer(Modifier.height(ChatBarSpacing.sm))
        CbText("换个筛选或刷新试试", color = ChatBarTheme.colors.mutedForeground)
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
