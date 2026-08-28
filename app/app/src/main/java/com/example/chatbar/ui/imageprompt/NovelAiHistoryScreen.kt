package com.example.chatbar.ui.imageprompt

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry
import com.example.chatbar.domain.image.NovelAiGenerationHistoryImage
import com.example.chatbar.domain.image.NovelAiGalleryConflictDecision
import com.example.chatbar.domain.image.NovelAiHistoryApplyMode
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiImageUseTarget
import com.example.chatbar.domain.image.hasMissingHistorySource
import com.example.chatbar.domain.image.requiresImageGuidanceReuseWarning
import com.example.chatbar.ui.components.ImagePreviewDialog
import com.example.chatbar.ui.components.ImagePreviewItem
import com.example.chatbar.ui.kit.AppIcons
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbChoiceChip
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbDivider
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbProgress
import com.example.chatbar.ui.kit.CbSelect
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbSwitch
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarShape
import com.example.chatbar.ui.kit.ChatBarSpacing
import com.example.chatbar.ui.kit.ChatBarTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private data class PendingHistoryApply(
    val entry: NovelAiGenerationHistoryEntry,
    val image: NovelAiGenerationHistoryImage,
    val mode: NovelAiHistoryApplyMode
)

@Composable
fun NovelAiHistoryScreen(
    onBack: () -> Unit,
    viewModel: NovelAiHistoryViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var searchExpanded by remember { mutableStateOf(false) }
    var showDateFilter by remember { mutableStateOf(false) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var fullPreviewIndex by remember { mutableStateOf<Int?>(null) }
    var pendingDelete by remember { mutableStateOf<NovelAiGenerationHistoryEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var useAsItem by remember { mutableStateOf<NovelAiHistoryImageItem?>(null) }
    var pendingApply by remember { mutableStateOf<PendingHistoryApply?>(null) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.prepareBatchExport()
        else viewModel.reportError("未授予存储权限，无法批量保存图片")
    }
    val overwriteAuthorizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.resumeBatchExportAfterAuthorization(result.resultCode == Activity.RESULT_OK)
    }

    fun requestBatchExport() {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.prepareBatchExport()
        }
    }

    LaunchedEffect(state.applied) {
        if (state.applied) {
            viewModel.consumeApplied()
            onBack()
        }
    }
    LaunchedEffect(state.filteredImages, selectedKey) {
        if (selectedKey != null && state.filteredImages.none { it.key == selectedKey }) {
            selectedKey = null
            fullPreviewIndex = null
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { notice ->
            Toast.makeText(context, notice, Toast.LENGTH_LONG).show()
            viewModel.consumeNotice()
        }
    }
    LaunchedEffect(state.exportAuthorization) {
        state.exportAuthorization?.let { intentSender ->
            viewModel.markExportAuthorizationLaunched()
            runCatching {
                overwriteAuthorizationLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }.onFailure {
                viewModel.resumeBatchExportAfterAuthorization(false)
            }
        }
    }
    LaunchedEffect(state.selectionMode) {
        if (!state.selectionMode) confirmBatchDelete = false
    }
    BackHandler(enabled = state.selectionMode) {
        when {
            state.busy -> Unit
            state.exportPlan != null -> viewModel.resolveExportConflict(null, false)
            else -> viewModel.clearSelection()
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        CbTopBar(
            title = if (state.selectionMode) "已选 ${state.selectedImageKeys.size} 张" else "生图历史",
            navigation = {
                CbIconButton(
                    if (state.selectionMode) AppIcons.Close else AppIcons.ArrowBack,
                    if (state.selectionMode) "退出多选" else "返回",
                    if (state.selectionMode) viewModel::clearSelection else onBack,
                    enabled = !state.busy && state.exportPlan == null
                )
            },
            actions = {
                if (!state.selectionMode) {
                CbIconButton(
                    AppIcons.Calendar,
                    "按日期筛选",
                    { showDateFilter = true },
                    enabled = state.entries.isNotEmpty(),
                    tint = if (state.dateFilter != null) ChatBarTheme.colors.primary else ChatBarTheme.colors.foreground
                )
                CbIconButton(
                    AppIcons.Search,
                    "搜索 Prompt",
                    {
                        if (searchExpanded) viewModel.updateSearchQuery("")
                        searchExpanded = !searchExpanded
                    },
                    enabled = state.entries.isNotEmpty(),
                    tint = if (state.searchQuery.isNotEmpty()) ChatBarTheme.colors.primary else ChatBarTheme.colors.foreground
                )
                if (state.entries.isNotEmpty()) {
                    CbIconButton(
                        AppIcons.DeleteSweep,
                        "清空全部历史",
                        { confirmClear = true },
                        enabled = !state.busy,
                        tint = ChatBarTheme.colors.destructive
                    )
                }
                }
            }
        )

        if (searchExpanded && !state.selectionMode) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = ChatBarSpacing.md, vertical = ChatBarSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)
            ) {
                CbInput(
                    value = state.searchQuery,
                    onValueChange = viewModel::updateSearchQuery,
                    modifier = Modifier.weight(1f),
                    placeholder = "搜索画风、基础或角色 Prompt"
                )
                CbIconButton(
                    AppIcons.Close,
                    "关闭并清除搜索",
                    {
                        viewModel.updateSearchQuery("")
                        searchExpanded = false
                    }
                )
            }
        }

        state.dateFilter?.takeUnless { state.selectionMode }?.let { filter ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ChatBarSpacing.md, vertical = ChatBarSpacing.xs)
                    .background(ChatBarTheme.colors.primaryAlpha, RoundedCornerShape(ChatBarShape.sm))
                    .padding(start = ChatBarSpacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CbText(
                    "日期 · ${filter.displayLabel()} · ${state.filteredImages.size} 张",
                    modifier = Modifier.weight(1f),
                    color = ChatBarTheme.colors.primary,
                    style = ChatBarTheme.typography.caption
                )
                CbIconButton(AppIcons.Close, "清除日期筛选", { viewModel.applyDateFilter(null) })
            }
        }

        when {
            state.entries.isEmpty() -> HistoryEmptyState("暂无历史图片")
            state.filteredImages.isEmpty() -> HistoryEmptyState("没有符合筛选条件的图片")
            else -> NovelAiHistoryGallery(
                items = state.filteredImages,
                onSelect = { item ->
                    if (state.selectionMode) viewModel.toggleSelection(item) else selectedKey = item.key
                },
                onLongSelect = { item ->
                    searchExpanded = false
                    viewModel.startSelection(item)
                },
                selectionMode = state.selectionMode,
                selectedKeys = state.selectedImageKeys,
                bottomContentPadding = if (state.selectionMode) 104.dp else ChatBarSpacing.sm
            )
        }
    }
        if (state.selectionMode) {
            HistoryBatchActionCard(
                title = state.batchTitle,
                busy = state.busy || state.exportPlan != null,
                onTitleChange = viewModel::updateBatchTitle,
                onDelete = { confirmBatchDelete = true },
                onSave = ::requestBatchExport,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = ChatBarSpacing.md, vertical = ChatBarSpacing.sm)
            )
        }
    }

    if (showDateFilter) {
        HistoryDateFilterDialog(
            entries = state.entries,
            current = state.dateFilter,
            onDismiss = { showDateFilter = false },
            onClear = {
                viewModel.applyDateFilter(null)
                showDateFilter = false
            },
            onApply = { filter ->
                viewModel.applyDateFilter(filter)
                showDateFilter = false
            }
        )
    }

    state.currentExportConflict?.let { conflict ->
        var applyToRemaining by remember(conflict.key) { mutableStateOf(false) }
        CbDialog(
            onDismissRequest = { viewModel.resolveExportConflict(null, false) },
            title = "发现重名图片",
            confirm = {
                CbButton(
                    "覆盖",
                    {
                        viewModel.resolveExportConflict(
                            NovelAiGalleryConflictDecision.OVERWRITE,
                            applyToRemaining
                        )
                    },
                    size = com.example.chatbar.ui.kit.ButtonSize.Sm
                )
            },
            dismiss = {
                Row(horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)) {
                    CbButton(
                        "停止",
                        { viewModel.resolveExportConflict(null, false) },
                        variant = ButtonVariant.Ghost,
                        size = com.example.chatbar.ui.kit.ButtonSize.Sm
                    )
                    CbButton(
                        "跳过",
                        {
                            viewModel.resolveExportConflict(
                                NovelAiGalleryConflictDecision.SKIP,
                                applyToRemaining
                            )
                        },
                        variant = ButtonVariant.Outline,
                        size = com.example.chatbar.ui.kit.ButtonSize.Sm
                    )
                }
            }
        ) {
            CbText(conflict.displayName)
            if (conflict.duplicateCount > 1) {
                Spacer(Modifier.height(ChatBarSpacing.xs))
                CbText(
                    "图库中有 ${conflict.duplicateCount} 个同名项；覆盖时仅替换最新项。",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
            Spacer(Modifier.height(ChatBarSpacing.md))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CbText("本次导出后续都这样处理", Modifier.weight(1f))
                CbSwitch(applyToRemaining, { applyToRemaining = it })
            }
        }
    }

    if (confirmBatchDelete && state.selectionMode) {
        CbDialog(
            onDismissRequest = { if (!state.busy) confirmBatchDelete = false },
            title = "删除 ${state.selectedImageKeys.size} 张历史图片？",
            confirm = {
                HoldToConfirmDeleteButton(
                    enabled = !state.busy,
                    onConfirmed = {
                        confirmBatchDelete = false
                        viewModel.deleteSelectedImages()
                    }
                )
            },
            dismiss = {
                CbButton(
                    "取消",
                    { confirmBatchDelete = false },
                    enabled = !state.busy,
                    variant = ButtonVariant.Ghost
                )
            },
            dismissOnBackPress = !state.busy,
            dismissOnClickOutside = !state.busy
        ) {
            CbText("只删除应用内历史与缓存文件；已保存到系统图库的副本不受影响。")
        }
    }

    val selectedIndex = selectedKey?.let { key -> state.filteredImages.indexOfFirst { it.key == key } }
        ?.takeIf { it >= 0 }
    if (
        selectedIndex != null && fullPreviewIndex == null && pendingDelete == null &&
        pendingApply == null && !showDateFilter && !confirmClear
    ) {
        HistoryDetailDialog(
            items = state.filteredImages,
            initialIndex = selectedIndex,
            busy = state.busy,
            onDismiss = { selectedKey = null },
            onCurrentChanged = { item -> selectedKey = item.key },
            onOpenImage = { index -> fullPreviewIndex = index },
            onApply = { entry, image, mode ->
                if (entry.recipe.requiresImageGuidanceReuseWarning(mode)) {
                    pendingApply = PendingHistoryApply(entry, image, mode)
                } else {
                    viewModel.apply(entry, image, mode)
                }
            },
            onUseAs = { item -> useAsItem = item },
            onDeleteBatch = { entry -> pendingDelete = entry }
        )
    }

    useAsItem?.let { item ->
        HistoryUseAsDialog(
            model = state.studioModel,
            onDismiss = { useAsItem = null },
            onSelect = { target ->
                useAsItem = null
                selectedKey = null
                viewModel.useImage(item, target)
            }
        )
    }

    fullPreviewIndex?.let { index ->
        val previewItems = state.filteredImages.map { item ->
            ImagePreviewItem(messageId = item.entry.id, path = item.image.path)
        }
        if (previewItems.isNotEmpty()) {
            ImagePreviewDialog(
                items = previewItems,
                initialIndex = index.coerceIn(previewItems.indices),
                onDismiss = { fullPreviewIndex = null },
                onPageChanged = { page ->
                    fullPreviewIndex = page
                    state.filteredImages.getOrNull(page)?.let { selectedKey = it.key }
                }
            )
        }
    }

    pendingDelete?.let { entry ->
        CbDialog(
            onDismissRequest = { pendingDelete = null },
            title = "删除历史批次？",
            confirm = {
                CbButton(
                    "删除",
                    {
                        pendingDelete = null
                        selectedKey = null
                        viewModel.delete(entry)
                    },
                    variant = ButtonVariant.Destructive
                )
            },
            dismiss = { CbButton("取消", { pendingDelete = null }, variant = ButtonVariant.Ghost) }
        ) {
            CbText("将删除记录及 ${entry.images.size} 张应用缓存图片。已保存到系统图库的副本不受影响。")
        }
    }

    pendingApply?.let { request ->
        CbDialog(
            onDismissRequest = { pendingApply = null },
            title = "无法准确复现",
            confirm = {
                CbButton("仍然应用", {
                    pendingApply = null
                    viewModel.apply(request.entry, request.image, request.mode)
                })
            },
            dismiss = { CbButton("取消", { pendingApply = null }, variant = ButtonVariant.Ghost) }
        ) {
            CbText(
                "原图生成时使用了图像参考，但历史未保存其来源图片。复用设置或 Seed 只能恢复现有参数，结果无法准确复现。"
            )
        }
    }

    if (confirmClear) {
        CbDialog(
            onDismissRequest = { confirmClear = false },
            title = "清空全部历史？",
            confirm = {
                CbButton(
                    "清空",
                    {
                        confirmClear = false
                        selectedKey = null
                        viewModel.clearAll()
                    },
                    variant = ButtonVariant.Destructive
                )
            },
            dismiss = { CbButton("取消", { confirmClear = false }, variant = ButtonVariant.Ghost) }
        ) {
            CbText("将删除全部已建索引历史及其应用缓存图片，不仅限于当前筛选结果。旧版未建索引文件不会删除。")
        }
    }

    state.error?.let { error ->
        CbDialog(
            onDismissRequest = viewModel::dismissError,
            title = "操作失败",
            confirm = { CbButton("知道了", viewModel::dismissError) }
        ) { CbText(error) }
    }
}

@Composable
private fun HistoryEmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(ChatBarSpacing.xl), contentAlignment = Alignment.Center) {
        CbText(text, color = ChatBarTheme.colors.mutedForeground)
    }
}

@Composable
internal fun NovelAiHistoryGallery(
    items: List<NovelAiHistoryImageItem>,
    onSelect: (NovelAiHistoryImageItem) -> Unit,
    onLongSelect: (NovelAiHistoryImageItem) -> Unit = {},
    selectionMode: Boolean = false,
    selectedKeys: List<String> = emptyList(),
    bottomContentPadding: androidx.compose.ui.unit.Dp = ChatBarSpacing.sm,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = ChatBarSpacing.sm,
            top = ChatBarSpacing.sm,
            end = ChatBarSpacing.sm,
            bottom = bottomContentPadding
        ),
        horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)
    ) {
        itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(ChatBarShape.sm))
                    .border(1.dp, ChatBarTheme.colors.border, RoundedCornerShape(ChatBarShape.sm))
                    .combinedClickable(
                        onClick = { onSelect(item) },
                        onLongClickLabel = "选择历史图片",
                        onLongClick = { onLongSelect(item) }
                    )
            ) {
                AsyncImage(
                    model = item.image.path,
                    contentDescription = "历史图片 ${index + 1}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (selectionMode) {
                    val selectionIndex = selectedKeys.indexOf(item.key).takeIf { it >= 0 }?.plus(1)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(ChatBarSpacing.xs)
                            .size(26.dp)
                            .background(
                                if (selectionIndex == null) ChatBarTheme.colors.card.copy(alpha = 0.82f)
                                else ChatBarTheme.colors.primary,
                                CircleShape
                            )
                            .border(
                                1.dp,
                                if (selectionIndex == null) ChatBarTheme.colors.border
                                else ChatBarTheme.colors.primary,
                                CircleShape
                            )
                            .semantics {
                                contentDescription = selectionIndex?.let { "选中序号 $it" } ?: "未选中"
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        selectionIndex?.let {
                            CbText(
                                it.toString(),
                                color = ChatBarTheme.colors.primaryForeground,
                                style = ChatBarTheme.typography.caption
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryBatchActionCard(
    title: String,
    busy: Boolean,
    onTitleChange: (String) -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    CbSurface(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, ChatBarTheme.colors.border),
        color = ChatBarTheme.colors.card
    ) {
        Row(
            Modifier.fillMaxWidth().padding(ChatBarSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)
        ) {
            CbInput(
                value = title,
                onValueChange = onTitleChange,
                modifier = Modifier.weight(1f),
                placeholder = "标题",
                enabled = !busy
            )
            CbIconButton(
                AppIcons.Delete,
                "批量删除选中图片",
                onDelete,
                modifier = Modifier.size(48.dp),
                enabled = !busy,
                tint = ChatBarTheme.colors.destructive
            )
            CbIconButton(
                AppIcons.Save,
                "批量保存选中图片",
                onSave,
                modifier = Modifier.size(48.dp),
                enabled = !busy
            )
        }
    }
}

@Composable
private fun HoldToConfirmDeleteButton(
    enabled: Boolean,
    onConfirmed: () -> Unit
) {
    var progress by remember { mutableStateOf(0f) }
    var holding by remember { mutableStateOf(false) }
    val colors = ChatBarTheme.colors
    val durationMillis = 3_000L
    LaunchedEffect(holding) {
        if (!holding) {
            progress = 0f
            return@LaunchedEffect
        }
        val startedAt = SystemClock.uptimeMillis()
        while (holding) {
            progress = ((SystemClock.uptimeMillis() - startedAt).toFloat() /
                durationMillis).coerceIn(0f, 1f)
            if (progress >= 1f) {
                holding = false
                onConfirmed()
                break
            }
            delay(16)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(ChatBarShape.sm))
                .background(if (enabled) colors.destructive else colors.muted)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        holding = true
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (holding && event.changes.any { it.pressed })
                        holding = false
                    }
                }
                .semantics {
                    role = Role.Button
                    contentDescription = "按住三秒确认删除"
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                }
                .padding(horizontal = ChatBarSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)
        ) {
            CbIcon(AppIcons.Delete, null, Modifier.size(18.dp), colors.destructiveForeground)
            CbText(
                if (progress > 0f) "继续按住" else "按住删除",
                color = colors.destructiveForeground,
                style = ChatBarTheme.typography.label
            )
        }
        if (progress > 0f) {
            Spacer(Modifier.height(ChatBarSpacing.xs))
            CbProgress(progress, Modifier.width(132.dp))
        }
    }
}

@Composable
private fun HistoryDateFilterDialog(
    entries: List<NovelAiGenerationHistoryEntry>,
    current: NovelAiHistoryDateFilter?,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onApply: (NovelAiHistoryDateFilter) -> Unit
) {
    val latestTimestamp = entries.firstOrNull()?.createdAt ?: System.currentTimeMillis()
    val (defaultYear, defaultMonth, defaultDay) = NovelAiHistoryFilterPolicy.dateParts(latestTimestamp)
    var granularity by remember(current, latestTimestamp) {
        mutableStateOf(current?.granularity ?: NovelAiHistoryDateGranularity.DAY)
    }
    var year by remember(current, latestTimestamp) { mutableIntStateOf(current?.year ?: defaultYear) }
    var month by remember(current, latestTimestamp) { mutableIntStateOf(current?.month ?: defaultMonth) }
    var day by remember(current, latestTimestamp) { mutableIntStateOf(current?.day ?: defaultDay) }
    val years = remember(entries, year) {
        (entries.map { NovelAiHistoryFilterPolicy.dateParts(it.createdAt).first } + year)
            .distinct()
            .sortedDescending()
    }
    val maximumDay = remember(year, month) {
        Calendar.getInstance().run {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            getActualMaximum(Calendar.DAY_OF_MONTH)
        }
    }
    LaunchedEffect(maximumDay) {
        if (day > maximumDay) day = maximumDay
    }

    CbDialog(
        onDismissRequest = onDismiss,
        title = "日期筛选",
        confirm = {
            CbButton(
                "应用",
                {
                    onApply(
                        NovelAiHistoryDateFilter(
                            granularity = granularity,
                            year = year,
                            month = month.takeUnless { granularity == NovelAiHistoryDateGranularity.YEAR },
                            day = day.takeIf { granularity == NovelAiHistoryDateGranularity.DAY }
                        )
                    )
                }
            )
        },
        dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) }
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)) {
            NovelAiHistoryDateGranularity.entries.forEach { option ->
                CbChoiceChip(
                    text = when (option) {
                        NovelAiHistoryDateGranularity.DAY -> "按日"
                        NovelAiHistoryDateGranularity.MONTH -> "按月"
                        NovelAiHistoryDateGranularity.YEAR -> "按年"
                    },
                    selected = granularity == option,
                    onClick = { granularity = option },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(ChatBarSpacing.md))
        CbSelect(year, years, { "${it}年" }, { year = it }, placeholder = "年份")
        if (granularity != NovelAiHistoryDateGranularity.YEAR) {
            Spacer(Modifier.height(ChatBarSpacing.sm))
            CbSelect(month, (1..12).toList(), { "${it}月" }, { month = it }, placeholder = "月份")
        }
        if (granularity == NovelAiHistoryDateGranularity.DAY) {
            Spacer(Modifier.height(ChatBarSpacing.sm))
            CbSelect(day, (1..maximumDay).toList(), { "${it}日" }, { day = it }, placeholder = "日期")
        }
        Spacer(Modifier.height(ChatBarSpacing.sm))
        CbButton("全部日期", onClear, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
    }
}

@Composable
private fun HistoryUseAsDialog(
    model: NovelAiImageModel,
    onDismiss: () -> Unit,
    onSelect: (NovelAiImageUseTarget) -> Unit
) {
    val targets = NovelAiImageUseTarget.entries.filter {
        model == NovelAiImageModel.V4_5_FULL || it in setOf(
            NovelAiImageUseTarget.IMAGE_TO_IMAGE,
            NovelAiImageUseTarget.INPAINT
        )
    }
    CbDialog(
        onDismissRequest = onDismiss,
        title = "用作图像引导",
        dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) }
    ) {
        targets.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                row.forEach { target ->
                    CbButton(target.displayName, { onSelect(target) }, Modifier.weight(1f), variant = ButtonVariant.Outline)
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(ChatBarSpacing.xs))
        }
    }
}

@Composable
internal fun HistoryDetailDialog(
    items: List<NovelAiHistoryImageItem>,
    initialIndex: Int,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCurrentChanged: (NovelAiHistoryImageItem) -> Unit,
    onOpenImage: (Int) -> Unit,
    onApply: (NovelAiGenerationHistoryEntry, NovelAiGenerationHistoryImage, NovelAiHistoryApplyMode) -> Unit,
    onUseAs: (NovelAiHistoryImageItem) -> Unit,
    onDeleteBatch: (NovelAiGenerationHistoryEntry) -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(items.indices)) { items.size }
    val currentIndex = pagerState.currentPage.coerceIn(items.indices)
    val current = items[currentIndex]

    LaunchedEffect(pagerState.currentPage, items) {
        items.getOrNull(pagerState.currentPage)?.let(onCurrentChanged)
    }

    CbDialog(
        onDismissRequest = onDismiss,
        title = "图片详情",
        dismiss = { CbButton("关闭", onDismiss, variant = ButtonVariant.Ghost) }
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val previewHeight = (maxHeight * 0.44f).coerceIn(168.dp, 300.dp)
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth().height(previewHeight),
                    key = { page -> items[page].key }
                ) { page ->
                    AsyncImage(
                        model = items[page].image.path,
                        contentDescription = "历史详情图片 ${page + 1}",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(ChatBarShape.md))
                            .background(ChatBarTheme.colors.surfaceSubtle)
                            .clickable { onOpenImage(page) },
                        contentScale = ContentScale.Fit
                    )
                }
                CbText(
                    "第 ${currentIndex + 1} 张 / 共 ${items.size} 张",
                    modifier = Modifier.fillMaxWidth().padding(vertical = ChatBarSpacing.xs),
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
                val fullReproductionAvailable = !current.entry.recipe.imageGuidance.hasMissingHistorySource()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    NovelAiImageAction(
                        AppIcons.Restore,
                        if (fullReproductionAvailable) "完整复现" else "缺少来源",
                        "完整复现",
                        !busy && fullReproductionAvailable,
                        Modifier.weight(1f)
                    ) {
                        onApply(current.entry, current.image, NovelAiHistoryApplyMode.FULL)
                    }
                    NovelAiImageAction(AppIcons.Tune, "复用", "复用设置并使用新 Seed", !busy, Modifier.weight(1f)) {
                        onApply(current.entry, current.image, NovelAiHistoryApplyMode.NEW_SEED)
                    }
                    NovelAiImageAction(AppIcons.Seed, "Seed", "仅复用 Seed", !busy, Modifier.weight(1f)) {
                        onApply(current.entry, current.image, NovelAiHistoryApplyMode.SEED_ONLY)
                    }
                    NovelAiImageAction(AppIcons.AddPhotoAlternate, "用作", "用作图像引导", !busy, Modifier.weight(1f)) {
                        onUseAs(current)
                    }
                }
                CbDivider(Modifier.padding(vertical = ChatBarSpacing.sm))
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 320.dp)) {
                    item { HistoryDetailContent(current, onDeleteBatch) }
                }
            }
        }
    }
}

@Composable
private fun HistoryDetailContent(
    item: NovelAiHistoryImageItem,
    onDeleteBatch: (NovelAiGenerationHistoryEntry) -> Unit
) {
    val recipe = item.entry.recipe
    val settings = recipe.settings
    val size = settings.imageSize()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            CbText(formatFullTime(item.entry.createdAt), style = ChatBarTheme.typography.label)
            CbText("Seed ${item.image.seed}", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
        }
        CbIconButton(
            AppIcons.Delete,
            "删除当前图片所属批次",
            { onDeleteBatch(item.entry) },
            tint = ChatBarTheme.colors.destructive
        )
    }
    Spacer(Modifier.height(ChatBarSpacing.sm))
    HistoryDetailValue("画风 Prompt", recipe.stylePrompt)
    HistoryDetailValue("基础 Prompt", recipe.basePrompt)
    recipe.characters.forEachIndexed { index, character ->
        HistoryDetailValue("角色 ${index + 1} Prompt", character.prompt)
    }
    HistoryDetailValue("基础负面 Prompt", recipe.negativePrompt)
    recipe.characters.forEachIndexed { index, character ->
        HistoryDetailValue("角色 ${index + 1} 负面 Prompt", character.negativePrompt)
    }
    recipe.imageGuidance.summary(settings.model).takeIf(String::isNotBlank)?.let { summary ->
        val guidance = recipe.imageGuidance
        HistoryDetailValue(
            "图像引导",
            buildString {
                append(summary)
                when (guidance.action) {
                    com.example.chatbar.domain.image.NovelAiGenerationAction.IMAGE_TO_IMAGE ->
                        append("\nStrength ${"%.2f".format(guidance.imageToImageStrength)} · Noise ${"%.2f".format(guidance.imageToImageNoise)}")
                    com.example.chatbar.domain.image.NovelAiGenerationAction.INPAINT ->
                        append("\nStrength ${"%.2f".format(guidance.inpaintStrength)} · 原始基图/蒙版未随历史保存")
                    else -> Unit
                }
                if (guidance.hasMissingHistorySource()) append("\n缺少生成来源；完整复现不可用")
            }
        )
    }
    HistoryDetailValue(
        "详细设置",
        "${settings.model.displayName} · ${size.width}×${size.height} · ${settings.count} 张\n" +
            "${settings.steps} Steps · CFG Scale ${"%.1f".format(settings.guidance)} · " +
            "CFG Rescale ${"%.2f".format(settings.cfgRescale)} · ${settings.sampler.displayName}"
    )
}

@Composable
private fun HistoryDetailValue(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    CbText(label, style = ChatBarTheme.typography.label)
    Spacer(Modifier.height(ChatBarSpacing.xs))
    CbSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "复制$label") {
                clipboard.setText(AnnotatedString(value))
                Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
            },
        color = ChatBarTheme.colors.surfaceSubtle,
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        CbText(
            value.ifBlank { "（空）" },
            modifier = Modifier.fillMaxWidth().padding(ChatBarSpacing.sm),
            color = if (value.isBlank()) ChatBarTheme.colors.mutedForeground else ChatBarTheme.colors.foreground
        )
    }
    Spacer(Modifier.height(ChatBarSpacing.sm))
}

private fun formatFullTime(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
