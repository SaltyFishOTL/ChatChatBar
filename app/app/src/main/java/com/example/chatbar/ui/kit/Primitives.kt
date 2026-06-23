package com.example.chatbar.ui.kit

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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
    shape: Shape = RoundedCornerShape(10.dp),
    border: BorderStroke? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(color)
            .then(if (border != null) Modifier.border(border, shape) else Modifier),
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
    val colors = ChatBarTheme.colors
    val background = when (variant) {
        ButtonVariant.Default -> colors.primary
        ButtonVariant.Destructive -> colors.destructive
        ButtonVariant.Secondary -> colors.secondary
        ButtonVariant.Outline, ButtonVariant.Ghost, ButtonVariant.Link -> Color.Transparent
    }
    val foreground = when (variant) {
        ButtonVariant.Default -> colors.primaryForeground
        ButtonVariant.Destructive -> colors.destructiveForeground
        ButtonVariant.Secondary -> colors.secondaryForeground
        ButtonVariant.Outline, ButtonVariant.Ghost -> colors.foreground
        ButtonVariant.Link -> colors.primary
    }
    val height = when (size) {
        ButtonSize.Xs, ButtonSize.IconXs -> 28.dp
        ButtonSize.Sm, ButtonSize.IconSm -> 34.dp
        ButtonSize.Default, ButtonSize.Icon -> 40.dp
        ButtonSize.Lg, ButtonSize.IconLg -> 44.dp
    }
    val horizontalPadding = when (size) {
        ButtonSize.Xs -> 10.dp
        ButtonSize.Sm -> 12.dp
        ButtonSize.Default -> 16.dp
        ButtonSize.Lg -> 20.dp
        else -> 0.dp
    }
    Box(
        modifier = modifier
            .height(height.coerceAtLeast(48.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .then(
                if (variant == ButtonVariant.Outline) {
                    Modifier.border(1.dp, colors.border, RoundedCornerShape(8.dp))
                } else Modifier
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center
    ) {
        CbText(text, color = foreground, style = ChatBarTheme.typography.label)
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
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
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
    Box(
        modifier = modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(ChatBarTheme.colors.primary)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        CbIcon(imageVector, contentDescription, Modifier.size(24.dp), ChatBarTheme.colors.primaryForeground)
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
                .padding(18.dp)
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
        CbSurface(
            modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
            border = BorderStroke(1.dp, ChatBarTheme.colors.border)
        ) {
            Column(Modifier.padding(18.dp)) {
                CbText(title, style = ChatBarTheme.typography.title)
                Spacer(Modifier.height(14.dp))
                content()
                if (confirm != null || dismiss != null) {
                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        dismiss?.invoke()
                        if (dismiss != null && confirm != null) Spacer(Modifier.size(8.dp))
                        confirm?.invoke()
                    }
                }
            }
        }
    }
}
