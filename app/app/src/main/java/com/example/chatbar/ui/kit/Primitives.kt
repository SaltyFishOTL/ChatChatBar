package com.example.chatbar.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun CbText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ChatBarTheme.colors.foreground,
    style: TextStyle = ChatBarTheme.typography.body,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = overflow
    )
}

@Composable
fun CbSurface(
    modifier: Modifier = Modifier,
    color: Color = ChatBarTheme.colors.card,
    shape: Shape = RoundedCornerShape(ChatBarShape.md),
    border: BorderStroke? = null,
    elevation: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .let { if (elevation > 0.dp) it.shadow(elevation, shape, ambientColor = ChatBarTheme.colors.cardShadow) else it }
            .clip(shape)
            .background(color)
            .let { if (border != null) it.border(border, shape) else it },
        content = content
    )
}

enum class ButtonVariant { Default, Destructive, Outline, Secondary, Ghost, Link }
enum class ButtonSize { Xs, Sm, Default, Lg, IconXs, IconSm, Icon, IconLg }

@Composable
fun CbButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: ButtonVariant = ButtonVariant.Default,
    size: ButtonSize = ButtonSize.Default
) {
    var pressVersion by remember { mutableStateOf(0) }
    val scale by animateFloatAsState(
        if (pressVersion % 2 == 1) 0.96f else 1f,
        animationSpec = tween(80),
        label = "btnScale"
    )
    val colors = ChatBarTheme.colors
    val bg = when (variant) {
        ButtonVariant.Default -> colors.primary
        ButtonVariant.Destructive -> colors.destructive
        ButtonVariant.Secondary -> colors.secondary
        ButtonVariant.Outline, ButtonVariant.Ghost, ButtonVariant.Link -> Color.Transparent
    }
    val fg = when (variant) {
        ButtonVariant.Default -> colors.primaryForeground
        ButtonVariant.Destructive -> colors.destructiveForeground
        ButtonVariant.Secondary -> colors.secondaryForeground
        ButtonVariant.Outline, ButtonVariant.Ghost -> colors.foreground
        ButtonVariant.Link -> colors.primary
    }
    val h = when (size) {
        ButtonSize.Xs, ButtonSize.IconXs -> 28.dp
        ButtonSize.Sm, ButtonSize.IconSm -> 34.dp
        ButtonSize.Default, ButtonSize.Icon -> 40.dp
        ButtonSize.Lg, ButtonSize.IconLg -> 44.dp
    }
    val hp = when (size) {
        ButtonSize.Xs -> 10.dp
        ButtonSize.Sm -> 12.dp
        ButtonSize.Default -> 16.dp
        ButtonSize.Lg -> 20.dp
        else -> 0.dp
    }
    val shape = RoundedCornerShape(ChatBarShape.sm)
    androidx.compose.runtime.LaunchedEffect(pressVersion) {
        if (pressVersion > 0) {
            kotlinx.coroutines.delay(150)
            pressVersion = 0
        }
    }
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(h.coerceAtLeast(44.dp))
            .clip(shape)
            .background(bg)
            .let { if (variant == ButtonVariant.Outline) it.border(1.dp, colors.border, shape) else it }
            .clickable(enabled = enabled, role = Role.Button) {
                pressVersion = 1
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.padding(horizontal = hp)) {
            CbText(text, color = fg, style = ChatBarTheme.typography.label)
        }
    }
}

@Composable
fun CbIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = ChatBarTheme.colors.foreground
) {
    androidx.compose.foundation.Image(
        painter = rememberVectorPainter(imageVector),
        contentDescription = contentDescription,
        modifier = modifier,
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint)
    )
}

@Composable
fun CbIconButton(
    imageVector: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = ChatBarTheme.colors.foreground
) {
    var pressVersion by remember { mutableStateOf(0) }
    val scale by animateFloatAsState(
        if (pressVersion % 2 == 1) 0.92f else 1f,
        animationSpec = tween(80),
        label = "iconBtnScale"
    )
    androidx.compose.runtime.LaunchedEffect(pressVersion) {
        if (pressVersion > 0) {
            kotlinx.coroutines.delay(150)
            pressVersion = 0
        }
    }
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(40.dp)
            .clip(RoundedCornerShape(ChatBarShape.sm))
            .clickable(enabled = enabled, role = Role.Button) {
                pressVersion = 1
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        CbIcon(imageVector, contentDescription, Modifier.size(20.dp), tint)
    }
}

@Composable
fun CbFab(
    imageVector: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pressVersion by remember { mutableStateOf(0) }
    val scale by animateFloatAsState(
        if (pressVersion % 2 == 1) 0.92f else 1f,
        animationSpec = tween(80),
        label = "fabScale"
    )
    androidx.compose.runtime.LaunchedEffect(pressVersion) {
        if (pressVersion > 0) {
            kotlinx.coroutines.delay(150)
            pressVersion = 0
        }
    }
    val shape = RoundedCornerShape(ChatBarShape.lg)
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(ChatBarElevation.medium, shape, ambientColor = ChatBarTheme.colors.cardShadow)
            .size(width = 48.dp, height = 48.dp)
            .clip(shape)
            .background(ChatBarTheme.colors.primary)
            .clickable(role = Role.Button) {
                pressVersion = 1
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        CbIcon(imageVector, contentDescription, Modifier.size(22.dp), ChatBarTheme.colors.primaryForeground)
    }
}

@Composable
fun CbDivider(modifier: Modifier = Modifier, color: Color = ChatBarTheme.colors.border) {
    Spacer(modifier = modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun CbScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Box(modifier.fillMaxSize().background(ChatBarTheme.colors.background)) {
        Column(Modifier.fillMaxSize()) {
            topBar()
            Box(Modifier.weight(1f)) { content(PaddingValues()) }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(ChatBarSpacing.lg)
        ) { floatingActionButton() }
    }
}

@Composable
fun CbDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    confirm: (@Composable () -> Unit)? = null,
    dismiss: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(ChatBarTheme.colors.dim)
                .clickable(onClick = onDismissRequest),
            contentAlignment = Alignment.Center
        ) {
            CbSurface(
                modifier = modifier.fillMaxWidth().padding(horizontal = ChatBarSpacing.xxl),
                border = BorderStroke(1.dp, ChatBarTheme.colors.border),
                elevation = ChatBarElevation.high
            ) {
                Column(Modifier.padding(ChatBarSpacing.lg)) {
                    CbText(title, style = ChatBarTheme.typography.heading)
                    Spacer(Modifier.height(ChatBarSpacing.md))
                    content()
                    if (confirm != null || dismiss != null) {
                        Spacer(Modifier.height(ChatBarSpacing.lg))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            dismiss?.invoke()
                            if (dismiss != null && confirm != null) Spacer(Modifier.size(ChatBarSpacing.sm))
                            confirm?.invoke()
                        }
                    }
                }
            }
        }
    }
}
