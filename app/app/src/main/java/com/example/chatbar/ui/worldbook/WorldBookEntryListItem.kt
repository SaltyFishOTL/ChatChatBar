package com.example.chatbar.ui.worldbook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.ui.kit.AppIcons
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbSwitch
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme

@Composable
internal fun WorldBookEntryListItem(
    entry: WorldBookEntry,
    onEdit: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean = true
) {
    val title = entry.name.ifBlank { "未命名条目" }
    CbSurface(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onEdit)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CbText(title, style = ChatBarTheme.typography.heading, maxLines = 2, overflow = TextOverflow.Ellipsis)
                CbText(entry.keys.joinToString(", ").ifBlank { "无触发词" },
                    color = ChatBarTheme.colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                CbText(if (entry.enabled) "已启用" else "已停用",
                    color = if (entry.enabled) ChatBarTheme.colors.primary else ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption)
            }
            Box(
                Modifier.size(48.dp)
                    .semantics { contentDescription = "启用世界书条目：$title" }
                    .toggleable(value = entry.enabled, enabled = enabled, role = Role.Switch,
                        onValueChange = onEnabledChange),
                contentAlignment = Alignment.Center
            ) {
                CbSwitch(entry.enabled, onCheckedChange = null, enabled = enabled)
            }
            CbIconButton(AppIcons.Edit, "编辑世界书条目：$title", onEdit,
                enabled = enabled, tint = ChatBarTheme.colors.primary)
            CbIconButton(AppIcons.Delete, "删除世界书条目：$title", onDelete,
                enabled = enabled, tint = ChatBarTheme.colors.destructive)
        }
    }
}
