package com.example.chatbar.ui.imageprompt

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme

@Composable
internal fun NovelAiImageAction(
    icon: ImageVector,
    label: String,
    description: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CbIconButton(icon, description, onClick, enabled = enabled)
        CbText(
            label,
            color = ChatBarTheme.colors.mutedForeground.copy(alpha = if (enabled) 1f else 0.45f),
            style = ChatBarTheme.typography.caption,
            maxLines = 1
        )
    }
}
