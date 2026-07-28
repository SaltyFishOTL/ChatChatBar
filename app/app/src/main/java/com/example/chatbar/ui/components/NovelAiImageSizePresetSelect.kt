package com.example.chatbar.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.chatbar.domain.image.NovelAiImageSizePreset
import com.example.chatbar.ui.kit.CbSelect

private data class NovelAiImageSizeChoice(
    val preset: NovelAiImageSizePreset?,
    val label: String
)

private val automaticSizeChoice = NovelAiImageSizeChoice(
    preset = null,
    label = "自动（全局设置 / AI 建议）"
)

private val presetSizeChoices = NovelAiImageSizePreset.entries.map { preset ->
    NovelAiImageSizeChoice(
        preset = preset,
        label = when (preset) {
            NovelAiImageSizePreset.PORTRAIT -> "竖图（13:19，832×1216）"
            NovelAiImageSizePreset.SQUARE -> "方图（1:1，1024×1024）"
            NovelAiImageSizePreset.HORIZONTAL -> "横图（19:13，1216×832）"
        }
    )
}

@Composable
fun NovelAiImageSizePresetSelect(
    value: NovelAiImageSizePreset?,
    onValueChange: (NovelAiImageSizePreset?) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    includeAutomatic: Boolean = false,
    placeholder: String = "选择图片比例"
) {
    val options = if (includeAutomatic) {
        listOf(automaticSizeChoice) + presetSizeChoices
    } else {
        presetSizeChoices
    }
    val selected = options.firstOrNull { choice -> choice.preset == value }
        ?.takeIf { value != null || includeAutomatic }
    CbSelect(
        value = selected,
        options = options,
        optionLabel = NovelAiImageSizeChoice::label,
        onValueChange = { choice -> onValueChange(choice.preset) },
        modifier = modifier,
        placeholder = placeholder,
        enabled = enabled
    )
}
