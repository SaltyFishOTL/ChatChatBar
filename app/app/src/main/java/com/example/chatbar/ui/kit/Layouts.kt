package com.example.chatbar.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp

@Composable
fun CbTopBar(
    title: String,
    modifier: Modifier = Modifier,
    statusBarInset: Boolean = true,
    elevated: Boolean = true,
    navigation: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (elevated) Modifier.shadow(ChatBarElevation.low, ambientColor = ChatBarTheme.colors.cardShadow) else Modifier)
            .background(ChatBarTheme.colors.surfaceElevated)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (statusBarInset) Modifier.statusBarsPadding() else Modifier)
                .height(58.dp)
                .padding(horizontal = ChatBarSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            navigation()
            Box(Modifier.weight(1f).padding(horizontal = ChatBarSpacing.sm)) {
                CbText(title, style = ChatBarTheme.typography.title)
            }
            actions()
        }
        if (elevated) CbDivider(color = ChatBarTheme.colors.border.copy(alpha = 0.72f))
    }
}
