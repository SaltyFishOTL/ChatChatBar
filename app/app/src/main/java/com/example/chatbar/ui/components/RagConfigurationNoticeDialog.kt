package com.example.chatbar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme

@Composable
fun RagConfigurationNoticeDialog(
    onDismissRequest: () -> Unit,
    onContinue: () -> Unit
) {
    CbDialog(
        onDismissRequest = onDismissRequest,
        title = "检索辅助模型未完整配置",
        dismiss = {
            CbButton("取消", onDismissRequest, variant = ButtonVariant.Ghost)
        },
        confirm = {
            CbButton("继续新聊天", onContinue)
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CbText("未配置也能正常聊天。", style = ChatBarTheme.typography.heading)
            CbText(
                "向量模型负责向量匹配，从文档设定集和长期记忆中找回相关细节；检索规划模型负责整理检索目标，让匹配更准确。",
                color = ChatBarTheme.colors.mutedForeground
            )
            CbText(
                "如果不配置，建议在全局设置中调高“上下文消息组”，让模型直接读取更多历史消息；这会增加 Token 用量和费用。",
                color = ChatBarTheme.colors.mutedForeground
            )
        }
    }
}
