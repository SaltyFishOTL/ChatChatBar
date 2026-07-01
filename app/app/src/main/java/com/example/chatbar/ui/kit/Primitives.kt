package com.example.chatbar.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Icon

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
    val colors = ChatBarTheme.colors
    val surfaceColor = if (elevation > 0.dp && color == colors.card) colors.surfaceElevated else color
    Box(
        modifier = modifier
            .let {
                if (elevation > 0.dp) {
                    it.shadow(
                        elevation = elevation,
                        shape = shape,
                        clip = false,
                        ambientColor = colors.cardShadow,
                        spotColor = colors.cardShadow
                    )
                } else {
                    it
                }
            }
            .clip(shape)
            .background(surfaceColor)
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
        animationSpec = tween(ChatBarMotion.fast),
        label = "btnScale"
    )
    val alpha by animateFloatAsState(
        if (enabled) 1f else 0.48f,
        animationSpec = tween(ChatBarMotion.normal),
        label = "btnAlpha"
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
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
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
fun CbDirtySaveButton(
    dirty: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Ghost
) {
    Box(modifier) {
        CbButton("保存", onClick, variant = variant)
        if (dirty) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(8.dp)
                    .background(ChatBarTheme.colors.destructive, CircleShape)
            )
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
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint
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
        animationSpec = tween(ChatBarMotion.fast),
        label = "iconBtnScale"
    )
    val alpha by animateFloatAsState(
        if (enabled) 1f else 0.45f,
        animationSpec = tween(ChatBarMotion.normal),
        label = "iconBtnAlpha"
    )
    androidx.compose.runtime.LaunchedEffect(pressVersion) {
        if (pressVersion > 0) {
            kotlinx.coroutines.delay(150)
            pressVersion = 0
        }
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
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
        animationSpec = tween(ChatBarMotion.fast),
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
            .shadow(
                elevation = ChatBarElevation.xhigh,
                shape = shape,
                ambientColor = ChatBarTheme.colors.cardShadow,
                spotColor = ChatBarTheme.colors.cardShadow
            )
            .size(width = 52.dp, height = 52.dp)
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
    dismissOnClickOutside: Boolean = true,
    dismissOnBackPress: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside
        )
    ) {
        val surfaceInteraction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .fillMaxSize()
                .background(ChatBarTheme.colors.dim)
                .clickable(enabled = dismissOnClickOutside, onClick = onDismissRequest),
            contentAlignment = Alignment.Center
        ) {
            CbSurface(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = ChatBarSpacing.xxl)
                    .clickable(
                        interactionSource = surfaceInteraction,
                        indication = null,
                        onClick = {}
                    ),
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
