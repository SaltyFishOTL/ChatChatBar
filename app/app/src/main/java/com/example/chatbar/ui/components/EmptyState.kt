package com.example.chatbar.ui.components

import com.example.chatbar.ui.kit.AppIcons

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarElevation
import com.example.chatbar.ui.kit.ChatBarMotion
import com.example.chatbar.ui.kit.ChatBarShape
import com.example.chatbar.ui.kit.ChatBarSpacing
import com.example.chatbar.ui.kit.ChatBarTheme

@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
    icon: ImageVector = AppIcons.Forum,
    title: String = "暂无内容",
    description: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val alpha by animateFloatAsState(
        if (visible) 1f else 0f,
        animationSpec = tween(ChatBarMotion.slow),
        label = "emptyAlpha"
    )
    val scale by animateFloatAsState(
        if (visible) 1f else 0.96f,
        animationSpec = tween(ChatBarMotion.slow),
        label = "emptyScale"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(ChatBarSpacing.xxl)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CbSurface(
            color = ChatBarTheme.colors.surfaceElevated,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(ChatBarShape.xxl),
            border = BorderStroke(1.dp, ChatBarTheme.colors.border),
            elevation = ChatBarElevation.medium
        ) {
            CbIcon(
                icon,
                null,
                Modifier.padding(ChatBarSpacing.xl).size(42.dp),
                ChatBarTheme.colors.primary
            )
        }
        Spacer(Modifier.height(ChatBarSpacing.lg))
        CbText(title, color = ChatBarTheme.colors.foreground, style = ChatBarTheme.typography.heading)
        description?.let {
            Spacer(Modifier.height(ChatBarSpacing.sm))
            CbText(it, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(ChatBarSpacing.xl))
            CbButton(text = actionLabel, onClick = onAction)
        }
    }
}
