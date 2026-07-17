package com.example.chatbar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.utils.diagnostics.PendingCrashReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CrashReportDialog(
    report: PendingCrashReport,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmingDelete by remember(report.createdAt) { mutableStateOf(false) }
    if (confirmingDelete) {
        CrashReportDeleteConfirmationDialog(
            onConfirm = {
                confirmingDelete = false
                onDelete()
            },
            onDismiss = { confirmingDelete = false }
        )
    } else {
        CbDialog(
            onDismissRequest = onDismiss,
            title = "检测到上次异常退出",
            dismiss = { CbButton("稍后", onDismiss, variant = ButtonVariant.Ghost) },
            confirm = { CbButton("发送报告", onShare) }
        ) {
            CbText(
                "已生成一个脱敏诊断文件，可直接发送给开发者。文件不包含 API Key、Token、聊天正文或 Prompt。",
                color = ChatBarTheme.colors.mutedForeground
            )
            CbText(
                "${report.trigger} · ${formatCrashReportTime(report.createdAt)}",
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                CbButton(
                    "删除报告",
                    { confirmingDelete = true },
                    variant = ButtonVariant.Destructive
                )
            }
        }
    }
}

@Composable
fun CrashReportDeleteConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    CbDialog(
        onDismissRequest = onDismiss,
        title = "删除崩溃报告",
        dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) },
        confirm = { CbButton("删除", onConfirm, variant = ButtonVariant.Destructive) }
    ) {
        CbText(
            "删除后无法恢复；下次发生异常退出时会重新生成。",
            color = ChatBarTheme.colors.mutedForeground
        )
    }
}

private fun formatCrashReportTime(timestamp: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(timestamp))
