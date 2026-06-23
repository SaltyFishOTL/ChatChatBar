package com.example.chatbar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import com.example.chatbar.HomeRoute
import com.example.chatbar.ManageRoute
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.ChatBarTheme

@Composable
fun BottomNavBar(
    currentRoute: NavKey,
    onNavigate: (NavKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChatBarTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.card)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(64.dp)
            .padding(horizontal = 48.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(
            NavigationItem("聊天", Icons.Default.Chat, HomeRoute),
            NavigationItem("管理", Icons.Default.Settings, ManageRoute)
        ).forEach { item ->
            val selected = currentRoute == item.route
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable { onNavigate(item.route) },
                contentAlignment = Alignment.Center
            ) {
                CbIcon(
                    imageVector = item.icon,
                    contentDescription = item.title,
                    modifier = Modifier.size(20.dp),
                    tint = if (selected) colors.primary else colors.mutedForeground
                )
            }
        }
    }
}

private data class NavigationItem(val title: String, val icon: ImageVector, val route: NavKey)
