package com.example.chatbar.ui.chat

import androidx.compose.runtime.*
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.ui.kit.*

/** The menu exposes one action; deleting the whole message requires a separate confirmation. */
@Composable
internal fun ReplyDeletionConfirmation(
    messageId: String,
    versionId: String,
    hasOtherAlternatives: Boolean,
    onDismiss: () -> Unit,
    onDeleteAlternative: () -> Unit,
    onDeleteWholeMessage: () -> Unit
) {
    var wholeMessage by remember(messageId, versionId) { mutableStateOf(false) }
    if (wholeMessage) {
        WholeMessageDeletionConfirmation(
            onDismiss = { wholeMessage = false },
            onDelete = onDeleteWholeMessage
        )
    } else {
        CbDialog(
            onDismissRequest = onDismiss,
            title = "删除备选",
            titleActions = {
                CbButton("删除全部备选", { wholeMessage = true },
                    modifier = Modifier.width(184.dp).border(1.dp,
                        ChatBarTheme.colors.destructive, RoundedCornerShape(ChatBarShape.sm)),
                    variant = ButtonVariant.Ghost, size = ButtonSize.Sm,
                    contentColor = ChatBarTheme.colors.destructive, autoSizeText = true,
                    trailingIcon = AppIcons.KeyboardArrowRight)
            },
            dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) },
            confirm = {
                CbButton("删除此备选", onDeleteAlternative, variant = ButtonVariant.Destructive)
            }
        ) {
            CbText(if (hasOtherAlternatives)
                "只删除当前备选，其他备选、图片和录音保留。此操作不可撤销。"
            else "删除此备选后，这条回复将不再保留。此操作不可撤销。")
        }
    }
}

@Composable
internal fun WholeMessageDeletionConfirmation(onDismiss: () -> Unit, onDelete: () -> Unit) {
    CbDialog(
        onDismissRequest = onDismiss,
        title = "删除整条回复",
        dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) },
        confirm = { CbButton("全部删除", onDelete, variant = ButtonVariant.Destructive) }
    ) {
        CbText("确定删除整条消息及全部备选回复？其中所有片段、图片和录音都会删除，此操作不可撤销。",
            color = ChatBarTheme.colors.mutedForeground)
    }
}
