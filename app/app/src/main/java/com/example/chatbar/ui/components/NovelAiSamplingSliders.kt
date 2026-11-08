package com.example.chatbar.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.chatbar.ui.kit.CbSlider
import kotlin.math.roundToInt

/**
 * Shared card/studio interaction steps from NovelAI's web UI: CFG 0.1, Rescale 0.02.
 * Normalize only emitted edits, never values loaded from a card, draft, or image.
 */
@Composable
fun NovelAiGuidanceSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    CbSlider(
        value = value,
        onValueChange = { onValueChange((it * 10f).roundToInt() / 10f) },
        valueRange = 1f..10f,
        modifier = modifier,
        steps = 89,
        contentDescription = "CFG Scale"
    )
}

@Composable
fun NovelAiCfgRescaleSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    CbSlider(
        value = value,
        onValueChange = { onValueChange((it * 50f).roundToInt() / 50f) },
        valueRange = 0f..1f,
        modifier = modifier,
        steps = 49,
        contentDescription = "CFG Rescale",
        enabled = enabled
    )
}
