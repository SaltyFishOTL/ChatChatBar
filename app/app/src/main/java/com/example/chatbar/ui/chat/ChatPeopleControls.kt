package com.example.chatbar.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.domain.chat.SessionCharacterParticipationPolicy
import com.example.chatbar.ui.home.CharacterAvatar
import com.example.chatbar.ui.kit.*

@Composable
internal fun ChatPeopleButton(session: ChatSession, card: CharacterCard, onClick: () -> Unit) {
    val active = card.characters.count { SessionCharacterParticipationPolicy.isEnabled(session, card, it.id) }
    Column(
        Modifier.size(48.dp)
            .semantics(mergeDescendants = true) { contentDescription = "会话人物，已启用 $active/${card.characters.size}" }
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CbIcon(AppIcons.Face, null, Modifier.size(18.dp))
        CbText("$active/${card.characters.size}", style = ChatBarTheme.typography.caption)
    }
}

@Composable
internal fun ChatPeopleDialog(
    session: ChatSession,
    card: CharacterCard,
    enabled: Boolean,
    error: String?,
    onPersonEnabled: (String, Boolean) -> Unit,
    onEditCard: () -> Unit,
    onDismiss: () -> Unit
) {
    val active = card.characters.count { SessionCharacterParticipationPolicy.isEnabled(session, card, it.id) }
    CbDialog(
        title = "会话人物 · $active/${card.characters.size}",
        onDismissRequest = onDismiss,
        confirm = { CbButton("完成", onDismiss) }
    ) {
        CbText("开关仅影响本对话后续请求，聊天历史会保留。", style = ChatBarTheme.typography.caption)
        error?.let { CbText(it, color = ChatBarTheme.colors.destructive) }
        if (card.characters.isEmpty()) CbText("角色卡中还没有人物。")
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 420.dp).weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(card.characters, key = { it.id }) { person ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.weight(1f).heightIn(min = 48.dp)
                            .clickable(enabled = enabled && !person.enabled, role = Role.Button, onClick = onEditCard),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CharacterAvatar(person.appearanceImage, Modifier.size(40.dp))
                        Column(Modifier.weight(1f)) {
                            CbText(person.name)
                            if (!person.enabled) CbText("卡内已关闭 · 点此编辑角色卡", style = ChatBarTheme.typography.caption,
                                color = ChatBarTheme.colors.mutedForeground)
                        }
                    }
                    if (person.enabled) {
                        SettingsSwitch(
                            SessionCharacterParticipationPolicy.isEnabled(session, card, person.id),
                            { onPersonEnabled(person.id, it) }, enabled = enabled, label = person.name
                        )
                    } else {
                        Box(
                            Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .semantics { contentDescription = "${person.name}已在角色卡中关闭，点击编辑角色卡" }
                                .clickable(enabled = enabled, role = Role.Button, onClick = onEditCard),
                            contentAlignment = Alignment.Center
                        ) {
                            CbSwitch(false, null, Modifier.clearAndSetSemantics {}, enabled = false)
                        }
                    }
                }
            }
        }
    }
}
