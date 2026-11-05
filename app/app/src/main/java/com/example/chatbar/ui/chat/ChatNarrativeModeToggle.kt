package com.example.chatbar.ui.chat

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.ChatNarrativeMode
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.ui.kit.AppIcons
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarMotion
import com.example.chatbar.ui.kit.ChatBarShape
import com.example.chatbar.ui.kit.ChatBarTheme

/** Compact inline control shared by the composer and fullscreen action row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatNarrativeModeToggle(
    mode: ChatNarrativeMode,
    activeMode: ChatNarrativeMode?,
    enabled: Boolean,
    onSelect: (ChatNarrativeMode) -> Unit
) {
    val context = LocalContext.current
    val colors = ChatBarTheme.colors
    var helpMode by remember { mutableStateOf<ChatNarrativeMode?>(null) }
    val advance by animateFloatAsState(
        targetValue = if (mode == ChatNarrativeMode.ADVANCE) 1f else 0f,
        animationSpec = tween(ChatBarMotion.slow),
        label = "narrativeModeBalance"
    )
    val advanceColor by animateColorAsState(
        if (mode == ChatNarrativeMode.ADVANCE) colors.primary else colors.mutedForeground,
        tween(ChatBarMotion.slow), label = "advanceModeColor"
    )
    val focusColor by animateColorAsState(
        if (mode == ChatNarrativeMode.FOCUS) colors.primary else colors.mutedForeground,
        tween(ChatBarMotion.slow), label = "focusModeColor"
    )
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pendingDescription = if (activeMode != null && activeMode != mode) {
        "本次${activeMode.shortLabel}，下次${mode.shortLabel}"
    } else mode.label

    Box(
        Modifier.size(28.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(ChatBarShape.sm))
            .border(1.dp, if (focused) colors.ring else Color.Transparent, RoundedCornerShape(ChatBarShape.sm))
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClickLabel = "切换叙事模式",
                onLongClickLabel = "查看当前模式完整说明",
                onLongClick = { helpMode = mode },
                onClick = {
                    val next = if (mode == ChatNarrativeMode.ADVANCE) {
                        ChatNarrativeMode.FOCUS
                    } else ChatNarrativeMode.ADVANCE
                    onSelect(next)
                    if (activeMode != null && activeMode != next) {
                        Toast.makeText(
                            context, "本次仍为${activeMode.shortLabel}，下次使用${next.shortLabel}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
            .semantics {
                contentDescription = "叙事模式，${mode.label}"
                stateDescription = pendingDescription
            },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(24.dp)) {
            val advanceSize = 10f + advance * 4f
            val focusSize = 14f - advance * 4f
            // Lucide glyphs include an internal inset; centers keep their strokes tight to the slash.
            CbIcon(
                AppIcons.FastForward, null,
                Modifier.offset(
                    x = (9.5f - advance * 0.5f - advanceSize / 2f).dp,
                    y = (7.5f + advance * 1.5f - advanceSize / 2f).dp
                ).size(advanceSize.dp),
                tint = advanceColor
            )
            Canvas(Modifier.size(24.dp)) {
                val slashY = (11f + advance * 2f).dp.toPx()
                drawLine(
                    color = colors.mutedForeground,
                    start = Offset(7.dp.toPx(), slashY + 5.dp.toPx()),
                    end = Offset(17.dp.toPx(), slashY - 5.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            CbIcon(
                AppIcons.Focus, null,
                Modifier.offset(
                    x = (17f - focusSize / 2f).dp,
                    y = (17f - advance - focusSize / 2f).dp
                ).size(focusSize.dp),
                tint = focusColor
            )
        }
    }

    helpMode?.let { shownMode ->
        CbDialog(
            title = shownMode.label,
            onDismissRequest = { helpMode = null },
            confirm = {
                CbButton("关闭", { helpMode = null }, variant = ButtonVariant.Ghost)
            }
        ) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (activeMode != null && activeMode != mode) {
                    CbText(pendingDescription, color = colors.mutedForeground)
                }
                SelectionContainer {
                    CbText(PromptTemplates.narrativeModeSystemPrompt(shownMode))
                }
            }
        }
    }
}
