package com.example.chatbar.ui.kit

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

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
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button, onClick = onFullscreenEdit),
                    contentAlignment = Alignment.Center
                ) {
                    CbIcon(Icons.Default.OpenInFull, "全屏编辑", Modifier.size(14.dp), colors.mutedForeground)
                }
            }
        } else {
            CbText(label, style = ChatBarTheme.typography.label)
        }
        Spacer(Modifier.height(7.dp))
        content()
        val supporting = error ?: description
        supporting?.let {
            Spacer(Modifier.height(6.dp))
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
    val borderColor = when {
        isError -> colors.destructive
        focused -> colors.primary
        else -> colors.border
    }
    val heightModifier = if (singleLine) {
        Modifier.heightIn(min = 44.dp)
    } else {
        Modifier.height(150.dp)
    }
    Box(modifier = modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .then(heightModifier)
                .background(colors.input, RoundedCornerShape(8.dp))
                .border(if (focused) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp),
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
                .padding(end = 8.dp, bottom = 6.dp)
                .background(colors.input.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
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
    Box(modifier = modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(colors.input, RoundedCornerShape(8.dp))
                .border(if (focused) 1.5.dp else 1.dp, if (focused) colors.primary else colors.border, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp),
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
                .padding(end = 8.dp, bottom = 6.dp)
                .background(colors.input.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
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
    placeholder: String = "输入内容…"
) {
    if (!visible) return
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
    val scrollState = rememberScrollState()
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Box(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            CbText(title, style = ChatBarTheme.typography.title)
            Spacer(Modifier.size(12.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .background(colors.input, RoundedCornerShape(8.dp))
                        .border(if (focused) 1.5.dp else 1.dp, if (focused) colors.primary else colors.border, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    singleLine = false,
                    textStyle = ChatBarTheme.typography.body.copy(color = colors.foreground),
                    cursorBrush = SolidColor(colors.primary),
                    interactionSource = interactionSource,
                    decorationBox = { inner ->
                        if (text.isEmpty() && placeholder.isNotEmpty()) CbText(placeholder, color = colors.mutedForeground)
                        inner()
                    }
                )
            }
        }
        CbIconButton(
            Icons.Default.Close, "退出",
            onDismiss,
            Modifier.align(Alignment.BottomStart).padding(16.dp).size(56.dp).background(colors.card, CircleShape)
        )
        CbIconButton(
            Icons.Default.Check, "确认",
            onDismiss,
            Modifier.align(Alignment.BottomEnd).padding(16.dp).size(56.dp).background(colors.primary, CircleShape),
            tint = colors.primaryForeground
        )
    }
}
