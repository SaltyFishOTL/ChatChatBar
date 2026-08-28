package com.example.chatbar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.update.AppUpdateDownloadState
import com.example.chatbar.domain.update.AppUpdateInfo
import com.example.chatbar.domain.update.DanbooruCatalogUpdateInfo
import com.example.chatbar.domain.update.DanbooruCatalogUpdateState
import com.example.chatbar.domain.update.UpdateCenterCheckResult
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbProgress
import com.example.chatbar.ui.kit.CbSpinner
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarElevation
import com.example.chatbar.ui.kit.ChatBarSpacing
import com.example.chatbar.ui.kit.ChatBarTheme

@Composable
fun UpdateCenterDialog(
    result: UpdateCenterCheckResult,
    appDownloadState: AppUpdateDownloadState,
    catalogUpdateState: DanbooruCatalogUpdateState,
    onDismiss: () -> Unit,
    onAppAction: () -> Unit,
    onCatalogAction: () -> Unit,
    onUpdateAll: () -> Unit,
    onCancelAll: () -> Unit
) {
    val appActive = appDownloadState is AppUpdateDownloadState.Downloading
    val catalogActive = catalogUpdateState is DanbooruCatalogUpdateState.Downloading ||
        catalogUpdateState is DanbooruCatalogUpdateState.Validating ||
        catalogUpdateState is DanbooruCatalogUpdateState.Applying
    val anyActive = appActive || catalogActive
    val bothUpdates = result.appUpdate?.apkAsset != null && result.catalogUpdate != null
    val bothReady = appDownloadState is AppUpdateDownloadState.Ready &&
        catalogUpdateState is DanbooruCatalogUpdateState.Ready
    val canCancelAny = appDownloadState is AppUpdateDownloadState.Downloading ||
        catalogUpdateState is DanbooruCatalogUpdateState.Downloading

    CbDialog(
        onDismissRequest = onDismiss,
        title = "更新中心",
        modifier = Modifier.heightIn(max = 760.dp),
        confirm = {
            if (bothUpdates && !bothReady) {
                CbButton(
                    text = if (anyActive) "取消全部" else "全部更新",
                    onClick = if (anyActive) onCancelAll else onUpdateAll,
                    enabled = !anyActive || canCancelAny
                )
            }
        },
        dismiss = {
            CbButton(
                text = if (anyActive) "更新进行中" else "关闭",
                onClick = onDismiss,
                enabled = !anyActive,
                variant = ButtonVariant.Ghost
            )
        },
        dismissOnClickOutside = !anyActive,
        dismissOnBackPress = !anyActive
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.md)
        ) {
            result.appUpdate?.let { update ->
                AppUpdateCard(update, appDownloadState, onAppAction)
            }
            result.catalogUpdate?.let { update ->
                CatalogUpdateCard(update, catalogUpdateState, onCatalogAction)
            }
            result.appError?.takeIf { result.appUpdate == null }?.let { error ->
                UpdateCheckErrorCard("ChatBar 应用", error)
            }
            result.catalogError?.takeIf { result.catalogUpdate == null }?.let { error ->
                UpdateCheckErrorCard("Danbooru 词条库", error)
            }
        }
    }
}

@Composable
private fun AppUpdateCard(
    updateInfo: AppUpdateInfo,
    state: AppUpdateDownloadState,
    onAction: () -> Unit
) {
    UpdateCard(title = "ChatBar 应用") {
        CbText(
            "${updateInfo.currentVersion} → ${updateInfo.latestVersion}",
            color = ChatBarTheme.colors.mutedForeground,
            style = ChatBarTheme.typography.caption
        )
        AppDownloadStatus(updateInfo, state)
        if (updateInfo.releaseNotes.isNotEmpty()) {
            Spacer(Modifier.height(ChatBarSpacing.xs))
            CbText("更新日志", style = ChatBarTheme.typography.label)
            updateInfo.releaseNotes.forEach { note ->
                CbText(
                    note.body.ifBlank { "${note.name.ifBlank { note.version }}：未填写更新日志" },
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            CbButton(
                text = when {
                    updateInfo.apkAsset == null -> "打开发布页"
                    state is AppUpdateDownloadState.Downloading -> "取消下载"
                    state is AppUpdateDownloadState.Ready -> "安装应用"
                    state is AppUpdateDownloadState.Failed -> "重试下载"
                    else -> "更新应用"
                },
                onClick = onAction,
                variant = ButtonVariant.Outline
            )
        }
    }
}

@Composable
private fun AppDownloadStatus(updateInfo: AppUpdateInfo, state: AppUpdateDownloadState) {
    when {
        updateInfo.apkAsset == null -> StatusText("此版本未附带可安装 APK。", destructive = true)
        state is AppUpdateDownloadState.Downloading -> {
            val progress = state.progress
            StatusText(
                if (progress == null) {
                    "正在下载 ${formatUpdateBytes(state.bytesDownloaded)}"
                } else {
                    "正在下载 ${(progress * 100).toInt()}% · ${formatUpdateBytes(state.bytesDownloaded)} / " +
                        formatUpdateBytes(state.totalBytes ?: 0L)
                }
            )
            CbProgress(progress ?: 0.04f)
        }
        state is AppUpdateDownloadState.Ready -> StatusText("下载完成。安装需 Android 系统确认。")
        state is AppUpdateDownloadState.Failed -> StatusText("下载失败：${state.message}", destructive = true)
        else -> StatusText(
            updateInfo.apkAsset.sizeBytes?.let { "完整安装包 ${formatUpdateBytes(it)}" } ?: "可下载完整安装包"
        )
    }
}

@Composable
private fun CatalogUpdateCard(
    updateInfo: DanbooruCatalogUpdateInfo,
    state: DanbooruCatalogUpdateState,
    onAction: () -> Unit
) {
    UpdateCard(title = "Danbooru 词条库") {
        val latestDate = updateInfo.latestCommitTime.substringBefore('T').takeIf(String::isNotBlank)
        val currentDate = updateInfo.currentMetadata.sourceCommitTime
            .substringBefore('T')
            .takeIf(String::isNotBlank)
        CbText(
            buildString {
                append("发现新版完整词库 · ")
                append(formatUpdateBytes(updateInfo.sizeBytes))
                latestDate?.let { append(" · ").append(it) }
            },
            color = ChatBarTheme.colors.mutedForeground,
            style = ChatBarTheme.typography.caption
        )
        CbText(
            "当前词库 ${currentDate ?: "已安装"} · 最新词库 ${latestDate ?: "可用"}",
            color = ChatBarTheme.colors.mutedForeground,
            style = ChatBarTheme.typography.caption
        )
        when (state) {
            is DanbooruCatalogUpdateState.Downloading -> {
                StatusText(
                    "正在下载 ${(state.progress * 100).toInt()}% · " +
                        "${formatUpdateBytes(state.bytesDownloaded)} / ${formatUpdateBytes(state.totalBytes)}"
                )
                CbProgress(state.progress)
            }
            is DanbooruCatalogUpdateState.Validating -> BusyStatus("正在校验完整词库…")
            is DanbooruCatalogUpdateState.Applying -> BusyStatus("正在安全替换词条库…")
            is DanbooruCatalogUpdateState.Ready -> StatusText(
                "词条库已启用，共 ${state.metadata.rowCount} 条。"
            )
            is DanbooruCatalogUpdateState.Failed -> StatusText(
                "更新失败：${state.message}。旧词库继续可用。",
                destructive = true
            )
            DanbooruCatalogUpdateState.Idle -> StatusText("下载完整数据库后将自动校验并启用。")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            CbButton(
                text = when (state) {
                    is DanbooruCatalogUpdateState.Downloading -> "取消下载"
                    is DanbooruCatalogUpdateState.Validating -> "校验中"
                    is DanbooruCatalogUpdateState.Applying -> "应用中"
                    is DanbooruCatalogUpdateState.Ready -> "已更新"
                    is DanbooruCatalogUpdateState.Failed -> "重试下载"
                    DanbooruCatalogUpdateState.Idle -> "更新词条库"
                },
                onClick = onAction,
                enabled = state is DanbooruCatalogUpdateState.Idle ||
                    state is DanbooruCatalogUpdateState.Downloading ||
                    state is DanbooruCatalogUpdateState.Failed,
                variant = ButtonVariant.Outline
            )
        }
    }
}

@Composable
private fun UpdateCheckErrorCard(title: String, message: String) {
    UpdateCard(title) {
        StatusText(message, destructive = true)
        CbText(
            "另一项检查结果不受影响。关闭后可再次检查。",
            color = ChatBarTheme.colors.mutedForeground,
            style = ChatBarTheme.typography.caption
        )
    }
}

@Composable
private fun UpdateCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    CbSurface(
        modifier = Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.surfaceSubtle,
        elevation = ChatBarElevation.low
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(ChatBarSpacing.md),
            verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
        ) {
            CbText(title, style = ChatBarTheme.typography.heading)
            content()
        }
    }
}

@Composable
private fun BusyStatus(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
    ) {
        CbSpinner(Modifier.size(18.dp))
        StatusText(text)
    }
    CbProgress(1f)
}

@Composable
private fun StatusText(text: String, destructive: Boolean = false) {
    CbText(
        text,
        color = if (destructive) ChatBarTheme.colors.destructive else ChatBarTheme.colors.mutedForeground,
        style = ChatBarTheme.typography.caption
    )
}

private fun formatUpdateBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kib = bytes / 1024.0
    if (kib < 1024.0) return "%.1f KB".format(kib)
    return "%.1f MB".format(kib / 1024.0)
}
