package com.example.chatbar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme

@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Forum,
    title: String = "暂无内容",
    description: String? = null
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CbIcon(icon, null, Modifier.size(40.dp), ChatBarTheme.colors.mutedForeground)
        Spacer(Modifier.height(16.dp))
        CbText(title, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.heading)
        description?.let {
            Spacer(Modifier.height(6.dp))
            CbText(it, color = ChatBarTheme.colors.mutedForeground)
        }
    }
}
