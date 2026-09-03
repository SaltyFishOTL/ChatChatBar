package com.example.chatbar.ui.kit

import com.example.chatbar.ui.kit.AppIcons

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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged

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
                    CbIcon(AppIcons.OpenInFull, "\u5168\u5c4f\u7f16\u8f91", Modifier.size(14.dp), colors.mutedForeground)
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
    expand: Boolean = false,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    inputTransformation: InputTransformation? = null,
    outputTransformation: OutputTransformation? = null,
    secure: Boolean = false
) {
    val state = rememberControlledTextFieldState(value, onValueChange)
    StateBasedCbInput(
        state = state,
        modifier = modifier,
        placeholder = placeholder,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        expand = expand,
        isError = isError,
        keyboardOptions = keyboardOptions,
        inputTransformation = inputTransformation,
        outputTransformation = outputTransformation,
        secure = secure,
        fixedMultilineHeight = 150.dp,
        onFocusChanged = null
    )
}

@Composable
private fun rememberControlledTextFieldState(
    value: String,
    onValueChange: (String) -> Unit
): TextFieldState {
    val state = rememberTextFieldState(
        initialText = value,
        initialSelection = TextRange(value.length)
    )
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val pendingEchoes = remember { mutableListOf<String>() }

    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }
            .distinctUntilChanged()
            .collect { next ->
                if (next != latestValue) {
                    pendingEchoes += next
                    if (pendingEchoes.size > 32) pendingEchoes.removeAt(0)
                    latestOnValueChange(next)
                }
            }
    }
    LaunchedEffect(value) {
        val echoIndex = pendingEchoes.indexOf(value)
        if (echoIndex >= 0) {
            repeat(echoIndex + 1) { pendingEchoes.removeAt(0) }
            return@LaunchedEffect
        }
        if (state.text.toString() != value) {
            state.edit {
                replace(0, length, value)
                selection = TextRange(value.length)
            }
        }
    }
    return state
}

@Composable
private fun rememberControlledTextFieldState(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit
): TextFieldState {
    val state = rememberTextFieldState(
        initialText = value.text,
        initialSelection = value.selection
    )
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val pendingEchoes = remember { mutableListOf<TextFieldValue>() }

    LaunchedEffect(state) {
        snapshotFlow {
            TextFieldValue(
                text = state.text.toString(),
                selection = state.selection,
                composition = state.composition
            )
        }
            .distinctUntilChanged()
            .collect { next ->
                if (next != latestValue) {
                    pendingEchoes += next
                    if (pendingEchoes.size > 32) pendingEchoes.removeAt(0)
                    latestOnValueChange(next)
                }
            }
    }
    LaunchedEffect(value.text, value.selection, value.composition) {
        val echoIndex = pendingEchoes.indexOf(value)
        if (echoIndex >= 0) {
            repeat(echoIndex + 1) { pendingEchoes.removeAt(0) }
            return@LaunchedEffect
        }
        if (state.text.toString() != value.text || state.selection != value.selection) {
            state.edit {
                replace(0, length, value.text)
                selection = TextRange(
                    value.selection.start.coerceIn(0, value.text.length),
                    value.selection.end.coerceIn(0, value.text.length)
                )
            }
        }
    }
    return state
}

@Composable
fun rememberCbTextFieldState(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit
): TextFieldState = rememberControlledTextFieldState(value, onValueChange)

@Composable
fun rememberCbTextFieldState(
    value: String,
    onValueChange: (TextFieldValue) -> Unit
): TextFieldState {
    val state = rememberTextFieldState(
        initialText = value,
        initialSelection = TextRange(value.length)
    )
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)

    LaunchedEffect(state) {
        snapshotFlow {
            TextFieldValue(
                text = state.text.toString(),
                selection = state.selection,
                composition = state.composition
            )
        }.distinctUntilChanged().collect(latestOnValueChange)
    }
    LaunchedEffect(value) {
        if (state.text.toString() != latestValue) {
            state.edit {
                replace(0, length, latestValue)
                selection = TextRange(latestValue.length)
            }
        }
    }
    return state
}

@Composable
private fun StateBasedCbInput(
    state: TextFieldState,
    modifier: Modifier,
    placeholder: String,
    enabled: Boolean,
    singleLine: Boolean,
    minLines: Int,
    expand: Boolean,
    isError: Boolean,
    keyboardOptions: KeyboardOptions,
    inputTransformation: InputTransformation?,
    outputTransformation: OutputTransformation?,
    secure: Boolean,
    fixedMultilineHeight: androidx.compose.ui.unit.Dp?,
    onFocusChanged: ((Boolean) -> Unit)?,
    textStyle: TextStyle? = null,
    textOverlay: (@Composable BoxScope.(TextLayoutResult?, Int) -> Unit)? = null
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
    val sizeModifier = when {
        singleLine -> Modifier.heightIn(min = 44.dp)
        expand -> Modifier.fillMaxSize()
        fixedMultilineHeight != null -> Modifier.height(fixedMultilineHeight)
        else -> Modifier.heightIn(min = 44.dp)
    }
    val shape = RoundedCornerShape(ChatBarShape.sm)
    val textScrollState = rememberScrollState()
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    LaunchedEffect(focused) { onFocusChanged?.invoke(focused) }
    Box(modifier = modifier.fillMaxWidth()) {
        val textModifier = Modifier.fillMaxWidth().then(sizeModifier)
        val decorator = TextFieldDecorator { innerTextField ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(colors.input, shape)
                    .border(borderWidth, borderColor, shape)
                    .padding(horizontal = ChatBarSpacing.md, vertical = 11.dp)
            ) {
                if (state.text.isEmpty() && placeholder.isNotEmpty()) {
                    CbText(placeholder, color = colors.mutedForeground)
                }
                innerTextField()
                textOverlay?.invoke(this, textLayoutResult, textScrollState.value)
            }
        }
        if (secure) {
            BasicSecureTextField(
                state = state,
                modifier = textModifier,
                enabled = enabled,
                inputTransformation = inputTransformation,
                textStyle = ChatBarTheme.typography.body.copy(color = colors.foreground),
                interactionSource = interactionSource,
                cursorBrush = SolidColor(colors.primary),
                decorator = decorator
            )
        } else {
            BasicTextField(
                state = state,
                modifier = textModifier,
                enabled = enabled,
                inputTransformation = inputTransformation,
                outputTransformation = outputTransformation,
                textStyle = (textStyle ?: ChatBarTheme.typography.body).copy(color = colors.foreground),
                cursorBrush = SolidColor(colors.primary),
                interactionSource = interactionSource,
                keyboardOptions = keyboardOptions,
                scrollState = textScrollState,
                onTextLayout = { getResult -> textLayoutResult = getResult() },
                lineLimits = if (singleLine) {
                    TextFieldLineLimits.SingleLine
                } else {
                    TextFieldLineLimits.MultiLine(minHeightInLines = minLines)
                },
                decorator = decorator
            )
        }
        CharacterCount(
            length = state.text.length,
            modifier = Modifier.align(Alignment.BottomEnd)
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
    minLines: Int = 1,
    expand: Boolean = false,
    inputTransformation: InputTransformation? = null,
    outputTransformation: OutputTransformation? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    textStyle: TextStyle? = null,
    textOverlay: (@Composable BoxScope.(TextLayoutResult?, Int) -> Unit)? = null
) {
    val state = rememberControlledTextFieldState(value, onValueChange)
    StateBasedCbInput(
        state = state,
        modifier = modifier,
        placeholder = placeholder,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        expand = expand,
        isError = false,
        keyboardOptions = KeyboardOptions.Default,
        inputTransformation = inputTransformation,
        outputTransformation = outputTransformation,
        secure = false,
        fixedMultilineHeight = null,
        onFocusChanged = onFocusChanged,
        textStyle = textStyle,
        textOverlay = textOverlay
    )
}

@Composable
fun CbInput(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    expand: Boolean = false,
    inputTransformation: InputTransformation? = null,
    outputTransformation: OutputTransformation? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    textStyle: TextStyle? = null,
    textOverlay: (@Composable BoxScope.(TextLayoutResult?, Int) -> Unit)? = null
) {
    StateBasedCbInput(
        state = state,
        modifier = modifier,
        placeholder = placeholder,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        expand = expand,
        isError = false,
        keyboardOptions = KeyboardOptions.Default,
        inputTransformation = inputTransformation,
        outputTransformation = outputTransformation,
        secure = false,
        fixedMultilineHeight = null,
        onFocusChanged = onFocusChanged,
        textStyle = textStyle,
        textOverlay = textOverlay
    )
}

@Composable
fun FullscreenTextEditor(
    title: String,
    text: String,
    onTextChange: (String) -> Unit,
    visible: Boolean,
    onDismiss: () -> Unit,
    placeholder: String = "输入内容…",
    onConfirm: ((String) -> Unit)? = null,
    images: List<String> = emptyList(),
    onAddImage: (() -> Unit)? = null,
    onRemoveImage: ((String) -> Unit)? = null,
    confirmIcon: ImageVector = AppIcons.Check,
    confirmEnabled: Boolean = true,
    canConfirm: (String) -> Boolean = { true },
    outputTransformation: OutputTransformation? = null,
    onDraftTextChange: ((String) -> Unit)? = null
) {
    if (!visible) return
    val editorState = rememberTextFieldState(
        initialText = text,
        initialSelection = TextRange(text.length)
    )
    val latestOnDraftTextChange by rememberUpdatedState(onDraftTextChange)
    val confirm = {
        val finalText = editorState.text.toString()
        onTextChange(finalText)
        if (onConfirm == null) {
            onDismiss()
        } else {
            onConfirm(finalText)
        }
    }
    LaunchedEffect(editorState) {
        if (latestOnDraftTextChange != null) {
            snapshotFlow { editorState.text.toString() }
                .distinctUntilChanged()
                .collect { latestOnDraftTextChange?.invoke(it) }
        }
    }
    FullscreenTextEditorLayout(title, onDismiss, confirm, confirmIcon, confirmEnabled && canConfirm(editorState.text.toString()), images, onAddImage, onRemoveImage) { ctxColors, interactionSource, focused ->
        CursorAwareFullscreenTextField(
            state = editorState,
            placeholder = placeholder,
            colors = ctxColors,
            interactionSource = interactionSource,
            focused = focused,
            outputTransformation = outputTransformation
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
    onConfirm: ((TextFieldValue) -> Unit)? = null,
    images: List<String> = emptyList(),
    onAddImage: (() -> Unit)? = null,
    onRemoveImage: ((String) -> Unit)? = null,
    confirmIcon: ImageVector = AppIcons.Check,
    confirmEnabled: Boolean = true,
    canConfirm: (TextFieldValue) -> Boolean = { true }
) {
    if (!visible) return
    val editorState = rememberTextFieldState(
        initialText = value.text,
        initialSelection = value.selection
    )
    val finalValue = {
        TextFieldValue(
            text = editorState.text.toString(),
            selection = editorState.selection,
            composition = editorState.composition
        )
    }
    val confirm = {
        val editorValue = finalValue()
        onValueChange(editorValue)
        if (onConfirm == null) {
            onDismiss()
        } else {
            onConfirm(editorValue)
        }
    }
    FullscreenTextEditorLayout(title, onDismiss, confirm, confirmIcon, confirmEnabled && canConfirm(finalValue()), images, onAddImage, onRemoveImage) { ctxColors, interactionSource, focused ->
        CursorAwareFullscreenTextField(
            state = editorState,
            placeholder = placeholder,
            colors = ctxColors,
            interactionSource = interactionSource,
            focused = focused
        )
    }
}

@Stable
class FullscreenTextEditorState internal constructor(
    internal val textFieldState: TextFieldState
) {
    val value: TextFieldValue
        get() = TextFieldValue(
            text = textFieldState.text.toString(),
            selection = textFieldState.selection,
            composition = textFieldState.composition
        )

    fun replace(value: TextFieldValue) {
        textFieldState.edit {
            replace(0, length, value.text)
            selection = TextRange(
                value.selection.start.coerceIn(0, value.text.length),
                value.selection.end.coerceIn(0, value.text.length)
            )
        }
    }
}

@Composable
fun rememberFullscreenTextEditorState(
    initialValue: TextFieldValue
): FullscreenTextEditorState {
    val textFieldState = rememberTextFieldState(
        initialText = initialValue.text,
        initialSelection = initialValue.selection
    )
    return remember(textFieldState) { FullscreenTextEditorState(textFieldState) }
}

/**
 * Opt-in stateful fullscreen editor. Existing String/TextFieldValue overloads keep their original
 * isolated-draft behavior; feature-specific accessories use this overload only when they need to
 * observe or replace the transient draft without committing it.
 */
@Composable
fun FullscreenTextEditor(
    state: FullscreenTextEditorState,
    title: String,
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (TextFieldValue) -> Unit,
    placeholder: String = "输入消息…",
    images: List<String> = emptyList(),
    onAddImage: (() -> Unit)? = null,
    onRemoveImage: ((String) -> Unit)? = null,
    confirmIcon: ImageVector = AppIcons.Check,
    confirmEnabled: Boolean = true,
    canConfirm: (TextFieldValue) -> Boolean = { true },
    outputTransformation: OutputTransformation? = null,
    textStyle: TextStyle? = null,
    textOverlay: (@Composable BoxScope.(TextLayoutResult?, Int, String) -> Unit)? = null,
    onDraftValueChange: ((TextFieldValue) -> Unit)? = null,
    topContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    if (!visible) return
    val latestOnDraftValueChange by rememberUpdatedState(onDraftValueChange)
    val currentValue = state.value
    LaunchedEffect(state) {
        if (latestOnDraftValueChange != null) {
            snapshotFlow { state.value }
                .distinctUntilChanged()
                .collect { latestOnDraftValueChange?.invoke(it) }
        }
    }
    FullscreenTextEditorLayout(
        title = title,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(state.value) },
        confirmIcon = confirmIcon,
        confirmEnabled = confirmEnabled && canConfirm(currentValue),
        images = images,
        onAddImage = onAddImage,
        onRemoveImage = onRemoveImage,
        topContent = topContent
    ) { ctxColors, interactionSource, focused ->
        CursorAwareFullscreenTextField(
            state = state.textFieldState,
            placeholder = placeholder,
            colors = ctxColors,
            interactionSource = interactionSource,
            focused = focused,
            outputTransformation = outputTransformation,
            textStyle = textStyle,
            textOverlay = textOverlay
        )
    }
}

@Composable
private fun CursorAwareFullscreenTextField(
    state: TextFieldState,
    placeholder: String,
    colors: ChatBarColors,
    interactionSource: MutableInteractionSource,
    focused: Boolean,
    modifier: Modifier = Modifier,
    outputTransformation: OutputTransformation? = null,
    textStyle: TextStyle? = null,
    textOverlay: (@Composable BoxScope.(TextLayoutResult?, Int, String) -> Unit)? = null
) {
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(ChatBarShape.sm)
    val scrollState = rememberScrollState()
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var fieldHeightPx by remember { mutableStateOf(0) }
    val imeBottom = WindowInsets.ime.getBottom(density)

    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    LaunchedEffect(
        state.selection,
        state.text,
        imeBottom,
        fieldHeightPx,
        textLayoutResult,
        scrollState.maxValue,
        focused
    ) {
        val layout = textLayoutResult ?: return@LaunchedEffect
        if (!focused || fieldHeightPx <= 0) return@LaunchedEffect
        val selectionEnd = state.selection.end.coerceIn(0, state.text.length)
        val cursorRect = layout.getCursorRect(selectionEnd)
        val targetScroll = fullscreenCursorScrollTarget(
            cursorTopPx = cursorRect.top,
            maxScrollPx = scrollState.maxValue,
            imeVisible = imeBottom > 0,
            selection = state.selection
        ) ?: return@LaunchedEffect
        if (targetScroll != scrollState.value) scrollState.scrollTo(targetScroll)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { fieldHeightPx = it.height }
            .background(colors.input, shape)
            .border(if (focused) 1.5.dp else 1.dp, if (focused) colors.primary else colors.border, shape)
            .padding(horizontal = ChatBarSpacing.md, vertical = 11.dp)
    ) {
        BasicTextField(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .heightIn(min = maxHeight.coerceAtLeast(1.dp)),
            lineLimits = TextFieldLineLimits.MultiLine(),
            textStyle = (textStyle ?: ChatBarTheme.typography.body).copy(color = colors.foreground),
            cursorBrush = SolidColor(colors.primary),
            outputTransformation = outputTransformation,
            interactionSource = interactionSource,
            scrollState = scrollState,
            onTextLayout = { getResult -> textLayoutResult = getResult() },
            decorator = { inner ->
                if (state.text.isEmpty() && placeholder.isNotEmpty()) CbText(placeholder, color = colors.mutedForeground)
                inner()
            }
        )
        textOverlay?.invoke(
            this,
            textLayoutResult,
            scrollState.value,
            state.text.toString()
        )
        CharacterCount(
            length = state.text.length,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

internal fun fullscreenCursorScrollTarget(
    cursorTopPx: Float,
    maxScrollPx: Int,
    imeVisible: Boolean,
    selection: TextRange
): Int? {
    if (!imeVisible || !selection.collapsed) return null
    return cursorTopPx.roundToInt().coerceIn(0, maxScrollPx.coerceAtLeast(0))
}

@Composable
fun CbFullscreenTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "输入内容…"
) {
    val state = rememberControlledTextFieldState(value, onValueChange)
    val colors = ChatBarTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    CursorAwareFullscreenTextField(
        state = state,
        placeholder = placeholder,
        colors = colors,
        interactionSource = interactionSource,
        focused = focused,
        modifier = modifier
    )
}

@Composable
private fun CharacterCount(
    length: Int,
    modifier: Modifier = Modifier
) {
    val colors = ChatBarTheme.colors
    CbText(
        "${length}字",
        modifier = modifier
            .padding(end = ChatBarSpacing.sm, bottom = 6.dp)
            .background(colors.input.copy(alpha = 0.7f), RoundedCornerShape(ChatBarShape.xs))
            .padding(horizontal = ChatBarSpacing.xs, vertical = 1.dp),
        color = colors.mutedForeground,
        style = ChatBarTheme.typography.caption
    )
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
    topContent: (@Composable ColumnScope.() -> Unit)? = null,
    textField: @Composable (colors: ChatBarColors, interactionSource: MutableInteractionSource, focused: Boolean) -> Unit
) {
    val colors = ChatBarTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .windowInsetsPadding(WindowInsets.ime)
    ) {
        Column(Modifier.fillMaxSize().padding(ChatBarSpacing.lg)) {
            CbText(title, style = ChatBarTheme.typography.title)
            Spacer(Modifier.size(ChatBarSpacing.md))
            if (topContent != null) {
                topContent()
                Spacer(Modifier.size(ChatBarSpacing.md))
            }
            if (images.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = ChatBarSpacing.md), horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                    images.forEach { path ->
                        Box(Modifier.size(96.dp).clip(RoundedCornerShape(ChatBarShape.sm))) {
                            AsyncImage(File(path), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Box(Modifier.align(Alignment.TopEnd).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable { onRemoveImage?.invoke(path) }, contentAlignment = Alignment.Center) {
                                CbIcon(AppIcons.Close, "删除图片", Modifier.size(16.dp), Color.White)
                            }
                        }
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 56.dp + ChatBarSpacing.md)
            ) {
                textField(colors, interactionSource, focused)
            }
        }
        CbIconButton(AppIcons.Close, "退出", onDismiss, Modifier.align(Alignment.BottomStart).padding(ChatBarSpacing.lg).size(56.dp).background(colors.card, CircleShape))
        Row(Modifier.align(Alignment.BottomEnd).padding(ChatBarSpacing.lg), horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.md)) {
            if (onAddImage != null) {
                CbIconButton(AppIcons.AddPhotoAlternate, "插入图片", onAddImage, Modifier.size(56.dp).background(colors.card, CircleShape), tint = colors.primary)
            }
            CbIconButton(confirmIcon, "确认", onConfirm, Modifier.size(56.dp).background(colors.primary, CircleShape), enabled = confirmEnabled, tint = colors.primaryForeground)
        }
    }
}
