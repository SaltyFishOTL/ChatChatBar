package com.example.chatbar.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun CbSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = ChatBarTheme.colors
    val thumbOffset by animateDpAsState(if (checked) 18.dp else 2.dp, animationSpec = tween(200), label = "switchThumb")
    val bgColor by animateColorAsState(if (checked) colors.primary else colors.muted, animationSpec = tween(200), label = "switchBg")
    Box(
        modifier = modifier
            .size(width = 40.dp, height = 24.dp)
            .background(bgColor, RoundedCornerShape(12.dp))
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .offset(x = thumbOffset)
                .size(20.dp)
                .background(if (checked) colors.primaryForeground else colors.card, CircleShape)
        )
    }
}

@Composable
fun <T> CbSelect(
    value: T?,
    options: List<T>,
    optionLabel: (T) -> String,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "请选择"
) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ChatBarShape.sm)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(ChatBarTheme.colors.surfaceSubtle, shape)
            .border(1.dp, ChatBarTheme.colors.border, shape)
            .clickable(role = Role.Button) { open = true }
            .padding(horizontal = ChatBarSpacing.md, vertical = ChatBarSpacing.md)
    ) {
        CbText(
            value?.let(optionLabel) ?: placeholder,
            color = if (value == null) ChatBarTheme.colors.mutedForeground else ChatBarTheme.colors.foreground
        )
    }
    if (open) {
        CbDialog(
            onDismissRequest = { open = false },
            title = placeholder,
            dismiss = { CbButton("取消", { open = false }, variant = ButtonVariant.Ghost) }
        ) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(options) { option ->
                    CbText(
                        optionLabel(option),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onValueChange(option)
                                open = false
                            }
                            .padding(vertical = ChatBarSpacing.md)
                    )
                }
            }
        }
    }
}

@Composable
fun CbChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChatBarTheme.colors
    val bg by animateColorAsState(if (selected) colors.primaryAlpha else colors.surfaceElevated, animationSpec = tween(ChatBarMotion.normal), label = "chipBg")
    val borderColor by animateColorAsState(if (selected) colors.primary else colors.border, animationSpec = tween(ChatBarMotion.normal), label = "chipBorder")
    val textColor by animateColorAsState(if (selected) colors.primary else colors.foreground, animationSpec = tween(ChatBarMotion.normal), label = "chipText")
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .background(bg, RoundedCornerShape(ChatBarShape.sm))
            .border(1.dp, borderColor, RoundedCornerShape(ChatBarShape.sm))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = ChatBarSpacing.md, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        CbText(text, color = textColor, style = ChatBarTheme.typography.label)
    }
}

@Composable
fun CbTabs(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeSelectedIndex = if (items.isEmpty()) 0 else selectedIndex.coerceIn(0, items.lastIndex)
    Row(
        modifier
            .fillMaxWidth()
            .swipeToAdjacentTab(
                selectedIndex = safeSelectedIndex,
                itemCount = items.size,
                onSelected = onSelected
            )
            .padding(horizontal = ChatBarSpacing.sm)
    ) {
        items.forEachIndexed { index, label ->
            val selected = index == safeSelectedIndex
            val textColor by animateColorAsState(
                if (selected) ChatBarTheme.colors.foreground else ChatBarTheme.colors.mutedForeground,
                animationSpec = tween(ChatBarMotion.normal),
                label = "tabText"
            )
            val indicatorColor by animateColorAsState(
                if (selected) ChatBarTheme.colors.primary else Color.Transparent,
                animationSpec = tween(ChatBarMotion.normal),
                label = "tabIndicator"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .selectable(selected = selected, role = Role.Tab) { onSelected(index) },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CbText(label, color = textColor, style = ChatBarTheme.typography.label)
                    Spacer(Modifier.size(6.dp))
                    Box(
                        Modifier
                            .size(width = 24.dp, height = 2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(indicatorColor)
                    )
                }
            }
        }
    }
}

fun Modifier.swipeToAdjacentTab(
    selectedIndex: Int,
    itemCount: Int,
    onSelected: (Int) -> Unit,
    enabled: Boolean = true,
    threshold: Dp = 72.dp
): Modifier = composed {
    val thresholdPx = with(LocalDensity.current) { threshold.toPx() }
    if (!enabled || itemCount < 2) {
        Modifier
    } else {
        Modifier.pointerInput(selectedIndex, itemCount, enabled, thresholdPx) {
            var totalDrag = 0f
            detectHorizontalDragGestures(
                onDragStart = { totalDrag = 0f },
                onHorizontalDrag = { change, dragAmount ->
                    totalDrag += dragAmount
                    change.consume()
                },
                onDragCancel = { totalDrag = 0f },
                onDragEnd = {
                    val targetIndex = when {
                        totalDrag <= -thresholdPx -> selectedIndex + 1
                        totalDrag >= thresholdPx -> selectedIndex - 1
                        else -> selectedIndex
                    }
                    if (targetIndex != selectedIndex && targetIndex in 0 until itemCount) {
                        onSelected(targetIndex)
                    }
                    totalDrag = 0f
                }
            )
        }
    }
}

@Composable
fun CbSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    contentDescription: String? = null
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    fun valueFromX(x: Float): Float {
        val fraction = (x / widthPx).coerceIn(0f, 1f)
        val raw = valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)
        if (steps <= 0) return raw
        val intervals = steps + 1
        val stepSize = (valueRange.endInclusive - valueRange.start) / intervals
        return valueRange.start + kotlin.math.round((raw - valueRange.start) / stepSize) * stepSize
    }
    fun snappedValue(rawValue: Float): Float {
        val coerced = rawValue.coerceIn(valueRange.start, valueRange.endInclusive)
        if (steps <= 0) return coerced
        val intervals = steps + 1
        val stepSize = (valueRange.endInclusive - valueRange.start) / intervals
        return valueRange.start + kotlin.math.round((coerced - valueRange.start) / stepSize) * stepSize
    }
    val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.coerceIn(valueRange.start, valueRange.endInclusive),
                    range = valueRange,
                    steps = steps
                )
                if (contentDescription != null) this.contentDescription = contentDescription
                setProgress { target ->
                    onValueChange(snappedValue(target))
                    true
                }
            }
            .focusable()
            .pointerInput(valueRange, steps) { detectTapGestures { onValueChange(valueFromX(it.x)) } }
            .pointerInput(valueRange, steps) {
                detectHorizontalDragGestures { change, _ -> onValueChange(valueFromX(change.position.x)) }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().size(height = 4.dp, width = 1.dp).background(ChatBarTheme.colors.muted, RoundedCornerShape(2.dp)))
        Box(Modifier.fillMaxWidth(fraction).size(height = 4.dp, width = 1.dp).background(ChatBarTheme.colors.primary, RoundedCornerShape(2.dp)))
        Box(
            Modifier
                .offset(x = ((widthPx * fraction) / androidx.compose.ui.platform.LocalDensity.current.density).dp - 8.dp)
                .size(16.dp)
                .background(ChatBarTheme.colors.card, CircleShape)
                .border(2.dp, ChatBarTheme.colors.primary, CircleShape)
        )
    }
}
