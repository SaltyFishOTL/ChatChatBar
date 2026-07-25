package com.example.chatbar.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import com.example.chatbar.domain.appearance.ThemeColorHsv
import kotlin.math.roundToInt

@Composable
fun CbHsvColorPicker(
    value: ThemeColorHsv,
    onValueChange: (ThemeColorHsv) -> Unit,
    modifier: Modifier = Modifier
) {
    val normalized = value.normalized()
    val hueBrush = remember {
        Brush.horizontalGradient(
            (0..6).map { index ->
                ThemeColorHsv(
                    hueDegrees = index * 60f,
                    saturation = 1f,
                    value = 1f
                ).toComposeColor()
            }
        )
    }
    val saturationBrush = remember(normalized.hueDegrees, normalized.value) {
        Brush.horizontalGradient(
            listOf(
                normalized.copy(saturation = 0f).toComposeColor(),
                normalized.copy(saturation = 1f).toComposeColor()
            )
        )
    }
    val valueBrush = remember(normalized.hueDegrees, normalized.saturation) {
        Brush.horizontalGradient(
            listOf(
                normalized.copy(value = 0f).toComposeColor(),
                normalized.copy(value = 1f).toComposeColor()
            )
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
    ) {
        HsvSliderLabel("色相", "${normalized.hueDegrees.roundToInt()}°")
        CbSlider(
            value = normalized.hueDegrees,
            onValueChange = { onValueChange(normalized.copy(hueDegrees = it).normalized()) },
            valueRange = 0f..360f,
            steps = 359,
            contentDescription = "色相 ${normalized.hueDegrees.roundToInt()} 度",
            trackBrush = hueBrush
        )
        HsvSliderLabel("饱和度", "${(normalized.saturation * 100f).roundToInt()}%")
        CbSlider(
            value = normalized.saturation,
            onValueChange = { onValueChange(normalized.copy(saturation = it).normalized()) },
            valueRange = 0f..1f,
            steps = 99,
            contentDescription = "饱和度 ${(normalized.saturation * 100f).roundToInt()}%",
            trackBrush = saturationBrush
        )
        HsvSliderLabel("亮度", "${(normalized.value * 100f).roundToInt()}%")
        CbSlider(
            value = normalized.value,
            onValueChange = { onValueChange(normalized.copy(value = it).normalized()) },
            valueRange = 0f..1f,
            steps = 99,
            contentDescription = "亮度 ${(normalized.value * 100f).roundToInt()}%",
            trackBrush = valueBrush
        )
    }
}

@Composable
private fun HsvSliderLabel(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CbText(label, style = ChatBarTheme.typography.label)
        CbText(
            value,
            color = ChatBarTheme.colors.mutedForeground,
            style = ChatBarTheme.typography.label
        )
    }
}
