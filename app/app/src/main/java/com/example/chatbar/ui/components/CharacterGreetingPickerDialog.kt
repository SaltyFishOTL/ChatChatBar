package com.example.chatbar.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.card.CharacterGreetingChoice
import com.example.chatbar.domain.card.CharacterGreetingPolicy
import com.example.chatbar.ui.kit.*

@Composable
internal fun CharacterGreetingPickerDialog(
    card: CharacterCard,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (CharacterGreetingChoice) -> Unit
) {
    val choices = remember(card.id, card.greeting, card.alternateGreetings) { CharacterGreetingPolicy.choices(card) }
    var selected by remember(choices) { mutableIntStateOf(0) }
    val choice = choices.first { it.position == selected }
    CbDialog(
        title = "选择会话开场",
        onDismissRequest = { if (!busy) onDismiss() },
        dismiss = { CbButton("取消", onDismiss, enabled = !busy, variant = ButtonVariant.Ghost) },
        confirm = { CbButton(if (busy) "正在创建…" else "创建会话", { onConfirm(choice) }, enabled = !busy) }
    ) {
        CbText(card.name, style = ChatBarTheme.typography.heading)
        CbText("仅用于新会话，不改写角色卡或已有对话。重置会话仍使用主开场。",
            color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(choices, key = { it.position }) { item ->
                CbButton(if (item.position == 0) "主开场" else "备用开场 ${item.position}",
                    { selected = item.position }, Modifier.fillMaxWidth(), enabled = !busy,
                    variant = if (selected == item.position) ButtonVariant.Default else ButtonVariant.Outline)
            }
        }
        SelectionContainer {
            Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                CbText(choice.text.ifEmpty { "（空开场）" })
            }
        }
    }
}
