package com.example.chatbar.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.chatbar.domain.update.AppUpdateInfo
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbText
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
    }
}
