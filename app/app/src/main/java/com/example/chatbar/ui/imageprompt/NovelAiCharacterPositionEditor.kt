package com.example.chatbar.ui.imageprompt

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.DesignedCharacterCenter
import com.example.chatbar.domain.image.NovelAiCharacterPositionPolicy
import com.example.chatbar.domain.image.NovelAiGenerationAction
import com.example.chatbar.domain.image.NovelAiFocusedInpaintPlanner
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiStudioDraft
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbNumberInput
import com.example.chatbar.ui.kit.CbSelect
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarSpacing
import com.example.chatbar.ui.kit.ChatBarTheme
import kotlin.math.roundToInt

@Composable
internal fun NovelAiCharacterPositionDialog(
    draft: NovelAiStudioDraft,
    onDismiss: () -> Unit,
    onConfirm: (Boolean, Map<String, DesignedCharacterCenter>) -> Unit
) {
    val characters = draft.characters
    if (characters.isEmpty()) return
    val model = draft.selectedModel
    var enabled by remember { mutableStateOf(draft.activeSettings.useCharacterPositions) }
    var selectedId by remember { mutableStateOf(characters.first().id) }
    val initial = remember {
        characters.mapIndexed { index, character ->
            character.id to NovelAiCharacterPositionPolicy.center(character, index, characters.size, model)
        }.toMap()
    }
    var values by remember { mutableStateOf(initial.mapValues { it.value.percentText() }) }
    val centers = values.mapNotNull { (id, value) ->
        val x = value.first.toFloatOrNull()
        val y = value.second.toFloatOrNull()
        if (x != null && y != null && x in 0f..100f && y in 0f..100f) {
            id to NovelAiCharacterPositionPolicy.normalize(DesignedCharacterCenter(x / 100, y / 100), model)
        } else null
    }.toMap()
    val valid = centers.size == characters.size
    val selected = characters.first { it.id == selectedId }
    val raw = values.getValue(selectedId)
    val settingsSize = draft.activeSettings.imageSize()
    val inpaint = draft.imageGuidance.action == NovelAiGenerationAction.INPAINT
    val inpaintSize = if (inpaint) runCatching {
        val base = requireNotNull(draft.imageGuidance.baseImage) { "请先设置聚焦重绘基图" }
        NovelAiFocusedInpaintPlanner.plan(
            base.width, base.height,
            requireNotNull(draft.imageGuidance.focusedInpaintRegion) { "请先设置聚焦区域" },
            draft.imageGuidance.focusedInpaintMinimumContext
        ).requestSize
    } else null
    val previewSize = inpaintSize?.getOrNull() ?: settingsSize
    val ratio = previewSize.width.toFloat() / previewSize.height

    CbDialog(
        title = "角色位置",
        onDismissRequest = onDismiss,
        confirm = {
            CbButton("确认", { onConfirm(enabled, if (valid) centers else emptyMap()) },
                enabled = !enabled || valid && inpaintSize?.isFailure != true)
        },
        dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) }
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
        ) {
            CbField("定位方式") {
                CbSelect(enabled, listOf(false, true), { if (it) "自定义" else "AI 自动" }, { enabled = it })
            }
            CbText(
                if (enabled) "先选择角色，再点按或拖动画布设置位置；数字与角色顺序对应。"
                else "由 AI 安排角色位置。切回自定义可继续编辑已保存的位置。",
                style = ChatBarTheme.typography.caption, color = ChatBarTheme.colors.mutedForeground
            )
            if (inpaint) CbText(
                inpaintSize?.exceptionOrNull()?.message ?: "聚焦重绘：位置相对于选中的聚焦区域，而非整张原图。",
                style = ChatBarTheme.typography.caption,
                color = if (inpaintSize?.isFailure == true) ChatBarTheme.colors.destructive else ChatBarTheme.colors.mutedForeground
            )
            CbField("当前角色") {
                CbSelect(
                    value = selected, options = characters,
                    optionLabel = { "角色 ${characters.indexOf(it) + 1} · ${it.prompt.take(24).ifBlank { "未填写" }}" },
                    onValueChange = { selectedId = it.id }, enabled = enabled
                )
            }
            val colors = ChatBarTheme.colors
            val markerStyle = ChatBarTheme.typography.caption
            val measurer = rememberTextMeasurer()
            BoxWithConstraints(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                val frameWidth = minOf(maxWidth.value, maxHeight.value * ratio).dp
                val frameHeight = (frameWidth.value / ratio).dp
                fun place(point: Offset, width: Int, height: Int) {
                    if (!enabled || width <= 0 || height <= 0) return
                    val center = NovelAiCharacterPositionPolicy.normalize(
                        DesignedCharacterCenter(point.x / width, point.y / height), model
                    )
                    values = values + (selectedId to center.percentText())
                }
                Canvas(
                    Modifier.width(frameWidth).height(frameHeight)
                        .semantics { contentDescription = "角色位置画布，横向从左到右，纵向从上到下；也可在下方输入百分比" }
                        .pointerInput(enabled, selectedId, model) {
                            if (enabled) detectTapGestures { place(it, size.width, size.height) }
                        }
                        .pointerInput(enabled, selectedId, model) {
                            if (enabled) detectDragGestures(
                                onDragStart = { place(it, size.width, size.height) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    place(change.position, size.width, size.height)
                                }
                            )
                        }
                ) {
                    drawRect(colors.surfaceSubtle)
                    drawRect(colors.border, style = Stroke(1.dp.toPx()))
                    for (step in 1..4) {
                        val fraction = step / 5f
                        drawLine(colors.border, Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height))
                        drawLine(colors.border, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
                    }
                    characters.sortedBy { it.id == selectedId }.forEach { character ->
                        val center = centers[character.id] ?: initial.getValue(character.id)
                        val point = Offset(size.width * center.x, size.height * center.y)
                        val active = enabled && character.id == selectedId
                        drawCircle(if (active) colors.primary else colors.muted, 13.dp.toPx(), point)
                        drawCircle(colors.border, 13.dp.toPx(), point, style = Stroke(1.dp.toPx()))
                        val label = measurer.measure(
                            "${characters.indexOf(character) + 1}",
                            markerStyle.copy(color = if (active) colors.primaryForeground else colors.foreground)
                        )
                        drawText(label, topLeft = point - Offset(label.size.width / 2f, label.size.height / 2f))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                CbField("横向（左 → 右 %）", modifier = Modifier.weight(1f)) {
                    CbNumberInput(raw.first, { values = values + (selectedId to (it to raw.second)) },
                        enabled = enabled, decimal = true, isError = raw.first.toFloatOrNull()?.let { it in 0f..100f } != true)
                }
                CbField("纵向（上 → 下 %）", modifier = Modifier.weight(1f)) {
                    CbNumberInput(raw.second, { values = values + (selectedId to (raw.first to it)) },
                        enabled = enabled, decimal = true, isError = raw.second.toFloatOrNull()?.let { it in 0f..100f } != true)
                }
            }
            CbText(
                if (model == NovelAiImageModel.V4_5_FULL) "V4.5 使用 5×5 网格，位置会吸附到最近格心（10%、30%、50%、70%、90%）。"
                else "V5 支持自由位置，横纵坐标范围均为 0–100%。网格仅供参考。",
                style = ChatBarTheme.typography.caption, color = colors.mutedForeground
            )
            centers[selectedId]?.let {
                val (x, y) = it.percentText()
                CbText("生效位置：横向 $x% · 纵向 $y%", style = ChatBarTheme.typography.caption)
            }
            if (enabled && !valid) CbText("请为每个角色填写 0–100 的有效位置。", color = colors.destructive)
            CbButton("重置为均匀排列", {
                values = characters.mapIndexed { index, character ->
                    character.id to NovelAiCharacterPositionPolicy.center(
                        character.copy(center = null), index, characters.size, model
                    ).percentText()
                }.toMap()
            }, enabled = enabled, variant = ButtonVariant.Outline)
        }
    }
}

private fun DesignedCharacterCenter.percentText(): Pair<String, String> {
    fun text(value: Float) = ((value * 1000).roundToInt() / 10f).toString().removeSuffix(".0")
    return text(x) to text(y)
}
