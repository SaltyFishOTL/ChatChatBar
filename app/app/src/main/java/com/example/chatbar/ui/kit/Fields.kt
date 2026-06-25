package com.example.chatbar.ui.kit

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

@Composable
fun CbField(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    error: String? = null,
    onFullscreenEdit: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = ChatBarTheme.colors
    Column(modifier) {
        if (onFullscreenEdit != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CbText(label, modifier = Modifier.weight(1f), style = ChatBarTheme.typography.label)
                Spacer(Modifier.width(ChatBarSpacing.xs))
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(ChatBarShape.xs))
                        .clickable(role = Role.Button, onClick = onFullscreenEdit),
                    contentAlignment = Alignment.Center
                ) {
                    CbIcon(Icons.Default.OpenInFull, "\u5168\u5c4f\u7f16\u8f91", Modifier.size(14.dp), colors.mutedForeground)
                }
            }
        } else {
            CbText(label, style = ChatBarTheme.typography.label)
        }
        Spacer(Modifier.height(ChatBarSpacing.sm))
        content()
        val supporting = error ?: description
        supporting?.let {
            Spacer(Modifier.height(ChatBarSpacing.xs))
            CbText(
                it,
                color = if (error != null) colors.destructive else colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
        }
    }
}

@Composable
fun CbInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    val colors = ChatBarTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        when {
            isError -> colors.destructive
            focused -> colors.primary
            else -> colors.border
        },
        animationSpec = tween(200),
        label = "inputBorder"
    )
    val borderWidth by animateDpAsState(
        if (focused) 1.5.dp else 1.dp,
        animationSpec = tween(200),
        label = "inputBorderWidth"
    )
    val heightModifier = if (singleLine) {
        Modifier.heightIn(min = 44.dp)
    } else {
        Modifier.height(150.dp)
    }
    val shape = RoundedCornerShape(ChatBarShape.sm)
    Box(modifier = modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .then(heightModifier)
                .background(colors.input, shape)
                .border(borderWidth, borderColor, shape)
                .padding(horizontal = ChatBarSpacing.md, vertical = 11.dp),
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = ChatBarTheme.typography.body.copy(color = colors.foreground),
            cursorBrush = SolidColor(colors.primary),
            interactionSource = interactionSource,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            decorationBox = { innerTextField ->
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    CbText(placeholder, color = colors.mutedForeground)
                }
                innerTextField()
            }
        )
        CbText(
            "${value.length}字",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = ChatBarSpacing.sm, bottom = 6.dp)
                .background(colors.input.copy(alpha = 0.7f), RoundedCornerShape(ChatBarShape.xs))
                .padding(horizontal = ChatBarSpacing.xs, vertical = 1.dp),
            color = colors.mutedForeground,
            style = ChatBarTheme.typography.caption
        )
    }
}

@Composable
fun CbInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1
) {
    val colors = ChatBarTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        if (focused) colors.primary else colors.border,
        animationSpec = tween(200),
        label = "inputBorder"
    )
    val borderWidth by animateDpAsState(
        if (focused) 1.5.dp else 1.dp,
        animationSpec = tween(200),
        label = "inputBorderWidth"
    )
    val shape = RoundedCornerShape(ChatBarShape.sm)
    Box(modifier = modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(colors.input, shape)
                .border(borderWidth, borderColor, shape)
                .padding(horizontal = ChatBarSpacing.md, vertical = 11.dp),
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = ChatBarTheme.typography.body.copy(color = colors.foreground),
            cursorBrush = SolidColor(colors.primary),
            interactionSource = interactionSource,
            decorationBox = { inner ->
                if (value.text.isEmpty() && placeholder.isNotEmpty()) CbText(placeholder, color = colors.mutedForeground)
                inner()
            }
        )
        CbText(
            "${value.text.length}字",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = ChatBarSpacing.sm, bottom = 6.dp)
                .background(colors.input.copy(alpha = 0.7f), RoundedCornerShape(ChatBarShape.xs))
                .padding(horizontal = ChatBarSpacing.xs, vertical = 1.dp),
            color = colors.mutedForeground,
            style = ChatBarTheme.typography.caption
        )
    }
}

@Composable
fun FullscreenTextEditor(
    title: String,
    text: String,
    onTextChange: (String) -> Unit,
    visible: Boolean,
    onDismiss: () -> Unit,
    placeholder: String = "输入内容…",
    onConfirm: (() -> Unit)? = null,
    images: List<String> = emptyList(),
    onAddImage: (() -> Unit)? = null,
    onRemoveImage: ((String) -> Unit)? = null,
    confirmIcon: ImageVector = Icons.Default.Check,
    confirmEnabled: Boolean = true
) {
    if (!visible) return
    val confirm = onConfirm ?: onDismiss
    FullscreenTextEditorLayout(title, onDismiss, confirm, confirmIcon, confirmEnabled, images, onAddImage, onRemoveImage) { ctxColors, interactionSource, focused ->
        val shape = RoundedCornerShape(ChatBarShape.sm)
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxSize()
                .background(ctxColors.input, shape)
                .border(if (focused) 1.5.dp else 1.dp, if (focused) ctxColors.primary else ctxColors.border, shape)
                .padding(horizontal = ChatBarSpacing.md, vertical = 11.dp),
            singleLine = false,
            textStyle = ChatBarTheme.typography.body.copy(color = ctxColors.foreground),
            cursorBrush = SolidColor(ctxColors.primary),
            interactionSource = interactionSource,
            decorationBox = { inner ->
                if (text.isEmpty() && placeholder.isNotEmpty()) CbText(placeholder, color = ctxColors.mutedForeground)
                inner()
            }
        )
    }
}

@Composable
fun FullscreenTextEditor(
    title: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    visible: Boolean,
    onDismiss: () -> Unit,
    placeholder: String = "输入消息…",
    onConfirm: (() -> Unit)? = null,
    images: List<String> = emptyList(),
    onAddImage: (() -> Unit)? = null,
    onRemoveImage: ((String) -> Unit)? = null,
    confirmIcon: ImageVector = Icons.Default.Check,
    confirmEnabled: Boolean = true
) {
    if (!visible) return
    val confirm = onConfirm ?: onDismiss
    FullscreenTextEditorLayout(title, onDismiss, confirm, confirmIcon, confirmEnabled, images, onAddImage, onRemoveImage) { ctxColors, interactionSource, focused ->
        val shape = RoundedCornerShape(ChatBarShape.sm)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxSize()
                .background(ctxColors.input, shape)
                .border(if (focused) 1.5.dp else 1.dp, if (focused) ctxColors.primary else ctxColors.border, shape)
                .padding(horizontal = ChatBarSpacing.md, vertical = 11.dp),
            singleLine = false,
            textStyle = ChatBarTheme.typography.body.copy(color = ctxColors.foreground),
            cursorBrush = SolidColor(ctxColors.primary),
            interactionSource = interactionSource,
            decorationBox = { inner ->
                if (value.text.isEmpty() && placeholder.isNotEmpty()) CbText(placeholder, color = ctxColors.mutedForeground)
                inner()
            }
        )
    }
}

@Composable
private fun FullscreenTextEditorLayout(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmIcon: ImageVector,
    confirmEnabled: Boolean,
    images: List<String>,
    onAddImage: (() -> Unit)?,
    onRemoveImage: ((String) -> Unit)?,
    textField: @Composable (colors: ChatBarColors, interactionSource: MutableInteractionSource, focused: Boolean) -> Unit
) {
    val localView = LocalView.current
    DisposableEffect(Unit) {
        val window = (localView.context as? Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, localView) }
        controller?.let {
            it.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
    val colors = ChatBarTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Box(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
        Column(Modifier.fillMaxSize().padding(ChatBarSpacing.lg)) {
            CbText(title, style = ChatBarTheme.typography.title)
            Spacer(Modifier.size(ChatBarSpacing.md))
            if (images.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = ChatBarSpacing.md), horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                    images.forEach { path ->
                        Box(Modifier.size(96.dp).clip(RoundedCornerShape(ChatBarShape.sm))) {
                            AsyncImage(File(path), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Box(Modifier.align(Alignment.TopEnd).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable { onRemoveImage?.invoke(path) }, contentAlignment = Alignment.Center) {
                                CbIcon(Icons.Default.Close, "删除图片", Modifier.size(16.dp), Color.White)
                            }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                textField(colors, interactionSource, focused)
            }
        }
        CbIconButton(Icons.Default.Close, "退出", onDismiss, Modifier.align(Alignment.BottomStart).padding(ChatBarSpacing.lg).size(56.dp).background(colors.card, CircleShape))
        Row(Modifier.align(Alignment.BottomEnd).padding(ChatBarSpacing.lg), horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.md)) {
            if (onAddImage != null) {
                CbIconButton(Icons.Default.AddPhotoAlternate, "插入图片", onAddImage, Modifier.size(56.dp).background(colors.card, CircleShape), tint = colors.primary)
            }
            CbIconButton(confirmIcon, "确认", onConfirm, Modifier.size(56.dp).background(colors.primary, CircleShape), enabled = confirmEnabled, tint = colors.primaryForeground)
        }
    }
}
