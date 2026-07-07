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
import com.example.chatbar.domain.update.AppUpdateInfo
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarElevation
import com.example.chatbar.ui.kit.ChatBarSpacing
import com.example.chatbar.ui.kit.ChatBarTheme

@Composable
fun AppUpdateDialog(
    updateInfo: AppUpdateInfo,
    onDismiss: () -> Unit,
    onOpenRelease: () -> Unit
) {
    CbDialog(
        onDismissRequest = onDismiss,
        title = "发现新版本",
        confirm = {
            CbButton(
                text = "去更新",
                onClick = onOpenRelease
            )
        },
        dismiss = {
            CbButton(
                text = "稍后",
                onClick = onDismiss,
                variant = ButtonVariant.Ghost
            )
        }
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
        CbText(
            text = "可前往 GitHub Releases 下载更新。",
            color = ChatBarTheme.colors.mutedForeground
        )
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
