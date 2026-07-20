package com.example.chatbar.ui.components

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.update.AppReleaseNote
import com.example.chatbar.domain.update.AppUpdateDownloadState
import com.example.chatbar.domain.update.AppUpdateInfo
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbProgress
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarElevation
import com.example.chatbar.ui.kit.ChatBarSpacing
import com.example.chatbar.ui.kit.ChatBarTheme

@Composable
fun AppUpdateDialog(
    updateInfo: AppUpdateInfo,
    downloadState: AppUpdateDownloadState,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit
) {
    val downloading = downloadState is AppUpdateDownloadState.Downloading
    val actionText = when {
        updateInfo.apkAsset == null -> "打开发布页"
        downloadState is AppUpdateDownloadState.Downloading -> "下载中..."
        downloadState is AppUpdateDownloadState.Ready -> "安装更新"
        downloadState is AppUpdateDownloadState.Failed -> "重试下载"
        else -> "下载更新"
    }
    CbDialog(
        onDismissRequest = onDismiss,
        title = "发现新版本",
        confirm = {
            CbButton(
                text = actionText,
                onClick = onUpdate,
                enabled = !downloading
            )
        },
        dismiss = {
            CbButton(
                text = "稍后",
                onClick = onDismiss,
                variant = ButtonVariant.Ghost
            )
        },
        dismissOnClickOutside = !downloading,
        dismissOnBackPress = !downloading
    ) {
        CbText(
            text = "当前版本：${updateInfo.currentVersion}",
            color = ChatBarTheme.colors.mutedForeground
        )
        Spacer(Modifier.height(ChatBarSpacing.sm))
        CbText(
            text = "最新版本：${updateInfo.latestVersion}",
            color = ChatBarTheme.colors.foreground
        )
        Spacer(Modifier.height(ChatBarSpacing.md))
        UpdateDownloadStatus(updateInfo, downloadState)
        Spacer(Modifier.height(ChatBarSpacing.md))
        CbText(
            text = "更新日志",
            style = ChatBarTheme.typography.heading
        )
        Spacer(Modifier.height(ChatBarSpacing.sm))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
        ) {
            if (updateInfo.releaseNotes.isEmpty()) {
                CbText(
                    text = "GitHub 未返回版本日志，可打开 Releases 查看。",
                    color = ChatBarTheme.colors.mutedForeground
                )
            } else {
                updateInfo.releaseNotes.forEach { note ->
                    ReleaseNoteBlock(note)
                }
            }
        }
    }
}

@Composable
private fun UpdateDownloadStatus(
    updateInfo: AppUpdateInfo,
    downloadState: AppUpdateDownloadState
) {
    when {
        updateInfo.apkAsset == null -> CbText(
            text = "此版本未附带可安装 APK，只能打开发布页。",
            color = ChatBarTheme.colors.destructive
        )

        downloadState is AppUpdateDownloadState.Downloading -> {
            val progress = downloadState.progress
            val status = if (progress != null) {
                "正在下载：${(progress * 100).toInt()}%（${formatBytes(downloadState.bytesDownloaded)} / ${formatBytes(downloadState.totalBytes ?: 0L)}）"
            } else {
                "正在下载：${formatBytes(downloadState.bytesDownloaded)}"
            }
            CbText(status, color = ChatBarTheme.colors.mutedForeground)
            Spacer(Modifier.height(ChatBarSpacing.sm))
            CbProgress(progress ?: 0.04f)
        }

        downloadState is AppUpdateDownloadState.Ready -> CbText(
            text = "更新包已下载。点击“安装更新”进入系统安装确认。",
            color = ChatBarTheme.colors.mutedForeground
        )

        downloadState is AppUpdateDownloadState.Failed -> CbText(
            text = "下载失败：${downloadState.message}",
            color = ChatBarTheme.colors.destructive
        )

        else -> CbText(
            text = "可在应用内直接下载更新包。首次安装可能需要允许 ChatBar 安装未知应用。",
            color = ChatBarTheme.colors.mutedForeground
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kib = bytes / 1024.0
    if (kib < 1024.0) return "%.1f KB".format(kib)
    return "%.1f MB".format(kib / 1024.0)
}

@Composable
private fun ReleaseNoteBlock(note: AppReleaseNote) {
    CbSurface(
        modifier = Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.surfaceSubtle,
        elevation = ChatBarElevation.low
    ) {
        Column(
            modifier = Modifier.padding(ChatBarSpacing.md),
            verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)
        ) {
            CbText(
                text = note.name.ifBlank { note.version },
                style = ChatBarTheme.typography.label
            )
            CbText(
                text = note.body.ifBlank { "此版本没有填写 release note。" },
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
        }
    }
}
