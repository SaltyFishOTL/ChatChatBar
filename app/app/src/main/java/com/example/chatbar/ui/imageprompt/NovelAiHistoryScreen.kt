package com.example.chatbar.ui.imageprompt

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry
import com.example.chatbar.domain.image.NovelAiGenerationHistoryImage
import com.example.chatbar.domain.image.NovelAiHistoryApplyMode
import com.example.chatbar.ui.components.ImagePreviewDialog
import com.example.chatbar.ui.kit.AppIcons
import com.example.chatbar.ui.kit.ButtonSize
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarShape
import com.example.chatbar.ui.kit.ChatBarSpacing
import com.example.chatbar.ui.kit.ChatBarTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NovelAiHistoryScreen(
    onBack: () -> Unit,
    viewModel: NovelAiHistoryViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Pair<NovelAiGenerationHistoryEntry, NovelAiGenerationHistoryImage>?>(null) }
    var previewPath by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<NovelAiGenerationHistoryEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(state.applied) { if (state.applied) onBack() }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        CbTopBar(
            title = "生图历史",
            navigation = { CbIconButton(AppIcons.ArrowBack, "返回", onBack) },
            actions = {
                if (state.entries.isNotEmpty()) {
                    CbButton("清空", { confirmClear = true }, enabled = !state.busy, variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
                }
            }
        )
        if (state.entries.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(ChatBarSpacing.xl), verticalArrangement = Arrangement.Center) {
                CbText("暂无历史批次", color = ChatBarTheme.colors.mutedForeground)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(ChatBarSpacing.md),
                verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.md)
            ) {
                items(state.entries, key = NovelAiGenerationHistoryEntry::id) { entry ->
                    HistoryBatchCard(entry, { image -> selected = entry to image }, { pendingDelete = entry })
                }
            }
        }
    }

    if (previewPath == null) selected?.let { (entry, image) ->
        CbDialog(
            onDismissRequest = { selected = null },
            title = "Seed ${image.seed}",
            dismiss = { CbButton("关闭", { selected = null }, variant = ButtonVariant.Ghost) }
        ) {
            AsyncImage(
                model = image.path,
                contentDescription = "历史大图",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .clip(RoundedCornerShape(ChatBarShape.md))
                    .clickable { previewPath = image.path },
                contentScale = ContentScale.Fit
            )
            CbText(
                "点击大图查看；全屏长按可打码、保存或分享",
                modifier = Modifier.padding(top = ChatBarSpacing.xs),
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
            Spacer(Modifier.height(ChatBarSpacing.md))
            CbButton("完整复现", { viewModel.apply(entry, image, NovelAiHistoryApplyMode.FULL) }, Modifier.fillMaxWidth(), enabled = !state.busy)
            Spacer(Modifier.height(ChatBarSpacing.sm))
            CbButton("复用设置，新 Seed", { viewModel.apply(entry, image, NovelAiHistoryApplyMode.NEW_SEED) }, Modifier.fillMaxWidth(), enabled = !state.busy, variant = ButtonVariant.Outline)
            Spacer(Modifier.height(ChatBarSpacing.sm))
            CbButton("仅用 Seed", { viewModel.apply(entry, image, NovelAiHistoryApplyMode.SEED_ONLY) }, Modifier.fillMaxWidth(), enabled = !state.busy, variant = ButtonVariant.Secondary)
        }
    }

    previewPath?.let { path ->
        ImagePreviewDialog(path = path, onDismiss = { previewPath = null })
    }

    pendingDelete?.let { entry ->
        CbDialog(
            onDismissRequest = { pendingDelete = null },
            title = "删除历史批次？",
            confirm = {
                CbButton("删除", { pendingDelete = null; viewModel.delete(entry) }, variant = ButtonVariant.Destructive)
            },
            dismiss = { CbButton("取消", { pendingDelete = null }, variant = ButtonVariant.Ghost) }
        ) { CbText("将删除记录及 ${entry.images.size} 张应用缓存图片。已保存到系统图库的副本不受影响。") }
    }

    if (confirmClear) {
        CbDialog(
            onDismissRequest = { confirmClear = false },
            title = "清空全部历史？",
            confirm = { CbButton("清空", { confirmClear = false; viewModel.clearAll() }, variant = ButtonVariant.Destructive) },
            dismiss = { CbButton("取消", { confirmClear = false }, variant = ButtonVariant.Ghost) }
        ) { CbText("将删除所有已建索引历史及其应用缓存图片。旧版未建索引文件不会删除。") }
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
private fun HistoryBatchCard(
    entry: NovelAiGenerationHistoryEntry,
    onSelect: (NovelAiGenerationHistoryImage) -> Unit,
    onDelete: () -> Unit
) {
    val settings = entry.recipe.settings
    CbSurface(
        Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(Modifier.fillMaxWidth().padding(ChatBarSpacing.md)) {
            Row {
                Column(Modifier.weight(1f)) {
                    CbText(settings.model.displayName, style = ChatBarTheme.typography.label)
                    CbText(
                        "${formatTime(entry.createdAt)} · ${entry.images.size} 张 · ${settings.imageSize().width}×${settings.imageSize().height} · ${settings.steps} Steps · CFG ${"%.1f".format(settings.guidance)} · ${settings.sampler.displayName}",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                }
                CbIconButton(AppIcons.Delete, "删除批次", onDelete, tint = ChatBarTheme.colors.destructive)
            }
            Spacer(Modifier.height(ChatBarSpacing.sm))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                itemsIndexed(entry.images) { index, image ->
                    Column(Modifier.clickable { onSelect(image) }) {
                        AsyncImage(
                            model = image.path,
                            contentDescription = "批次图片 ${index + 1}",
                            modifier = Modifier.size(108.dp).clip(RoundedCornerShape(ChatBarShape.sm)),
                            contentScale = ContentScale.Crop
                        )
                        CbText("Seed ${image.seed}", style = ChatBarTheme.typography.caption, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
