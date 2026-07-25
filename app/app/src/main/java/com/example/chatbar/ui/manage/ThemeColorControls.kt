package com.example.chatbar.ui.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.appearance.DefaultThemeColorHsv
import com.example.chatbar.domain.appearance.MAX_THEME_COLOR_HISTORY_SIZE
import com.example.chatbar.domain.appearance.ThemeColorHsv
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbHsvColorPicker
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarColors
import com.example.chatbar.ui.kit.ChatBarShape
import com.example.chatbar.ui.kit.ChatBarSpacing
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.kit.chatBarColors
import com.example.chatbar.ui.kit.toComposeColor
import kotlin.math.roundToInt

@Composable
internal fun ThemeColorSettingControls(
    current: ThemeColorHsv,
    history: List<ThemeColorHsv>,
    onSelectColor: (ThemeColorHsv) -> Unit
) {
    val normalizedCurrent = current.normalized()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) { onSelectColor(normalizedCurrent) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.md)
    ) {
        ThemeColorSwatch(normalizedCurrent, size = 32.dp)
        Column(Modifier.weight(1f)) {
            CbText("主题色", style = ChatBarTheme.typography.label)
            CbText(
                normalizedCurrent.summary(),
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
        }
        CbText(
            "设置",
            color = ChatBarTheme.colors.primary,
            style = ChatBarTheme.typography.label
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        history.take(MAX_THEME_COLOR_HISTORY_SIZE).forEachIndexed { index, color ->
            ThemeColorSwatchButton(
                color = color,
                description = "历史主题色 ${index + 1}，${color.normalized().summary()}",
                onClick = { onSelectColor(color.normalized()) }
            )
        }
        Spacer(Modifier.weight(1f))
        DefaultThemeColorButton(onClick = { onSelectColor(DefaultThemeColorHsv) })
    }
}

@Composable
internal fun ThemeColorPickerDialog(
    initialColor: ThemeColorHsv,
    onDismissRequest: () -> Unit,
    onApply: (ThemeColorHsv) -> Unit
) {
    var draft by remember(initialColor) { mutableStateOf(initialColor.normalized()) }
    val lightColors = remember(draft) { chatBarColors(darkTheme = false, themeColor = draft) }
    val darkColors = remember(draft) { chatBarColors(darkTheme = true, themeColor = draft) }

    CbDialog(
        onDismissRequest = onDismissRequest,
        title = "选择主题色",
        modifier = Modifier.heightIn(max = 720.dp),
        dismiss = {
            Row(horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)) {
                CbButton(
                    text = "恢复默认",
                    onClick = { draft = DefaultThemeColorHsv },
                    variant = ButtonVariant.Ghost
                )
                CbButton(
                    text = "取消",
                    onClick = onDismissRequest,
                    variant = ButtonVariant.Ghost
                )
            }
        },
        confirm = {
            CbButton(
                text = "应用",
                onClick = { onApply(draft.normalized()) }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 540.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.lg)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.md)
            ) {
                ThemeColorSwatch(draft, size = 44.dp)
                Column {
                    CbText(draft.summary(), style = ChatBarTheme.typography.heading)
                    CbText(
                        "确认前仅在此处预览",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                }
            }
            CbHsvColorPicker(
                value = draft,
                onValueChange = { draft = it }
            )
            ThemePalettePreview(label = "浅色预览", colors = lightColors)
            ThemePalettePreview(label = "深色预览", colors = darkColors)
        }
    }
}

@Composable
private fun ThemeColorSwatchButton(
    color: ThemeColorHsv,
    description: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        ThemeColorSwatch(color, size = 30.dp)
    }
}

@Composable
private fun DefaultThemeColorButton(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(48.dp)
            .heightIn(min = 48.dp)
            .semantics { contentDescription = "恢复 APP 默认主题色" }
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        ThemeColorSwatch(DefaultThemeColorHsv, size = 26.dp)
        CbText(
            "默认",
            color = ChatBarTheme.colors.mutedForeground,
            style = ChatBarTheme.typography.caption
        )
    }
}

@Composable
private fun ThemeColorSwatch(color: ThemeColorHsv, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .background(color.normalized().toComposeColor(), CircleShape)
            .border(1.dp, ChatBarTheme.colors.border, CircleShape)
    )
}

@Composable
private fun ThemePalettePreview(label: String, colors: ChatBarColors) {
    Column(verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)) {
        CbText(
            label,
            color = ChatBarTheme.colors.mutedForeground,
            style = ChatBarTheme.typography.label
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.background, RoundedCornerShape(ChatBarShape.sm))
                .border(1.dp, colors.border, RoundedCornerShape(ChatBarShape.sm))
                .padding(ChatBarSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
        ) {
            CbText("正文与浅染背景", color = colors.foreground)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
            ) {
                PaletteSample(
                    label = "主色",
                    background = colors.primary,
                    foreground = colors.primaryForeground,
                    modifier = Modifier.weight(1f)
                )
                PaletteSample(
                    label = "强调",
                    background = colors.accent,
                    foreground = colors.accentForeground,
                    modifier = Modifier.weight(1f)
                )
                PaletteSample(
                    label = "浅染",
                    background = colors.surfaceSubtle,
                    foreground = colors.foreground,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun PaletteSample(
    label: String,
    background: androidx.compose.ui.graphics.Color,
    foreground: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .background(background, RoundedCornerShape(ChatBarShape.xs)),
        contentAlignment = Alignment.Center
    ) {
        CbText(label, color = foreground, style = ChatBarTheme.typography.caption)
    }
}

private fun ThemeColorHsv.summary(): String {
    val normalized = normalized()
    return "H ${normalized.hueDegrees.roundToInt()}°  S ${(normalized.saturation * 100f).roundToInt()}%  V ${(normalized.value * 100f).roundToInt()}%"
}
