package com.example.chatbar.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Settings
import com.example.chatbar.HomeRoute
import com.example.chatbar.ManageRoute
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarElevation
import com.example.chatbar.ui.kit.ChatBarShape
import com.example.chatbar.ui.kit.ChatBarSpacing
import com.example.chatbar.ui.kit.ChatBarTheme

@Composable
fun BottomNavBar(
    currentRoute: NavKey,
    onNavigate: (NavKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChatBarTheme.colors
    val shape = RoundedCornerShape(topStart = ChatBarShape.lg, topEnd = ChatBarShape.lg)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(ChatBarElevation.xhigh, shape, ambientColor = colors.cardShadow)
            .background(colors.card, shape)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(64.dp)
            .padding(horizontal = ChatBarSpacing.xxl),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(
            NavItem("聊天", Icons.AutoMirrored.Filled.Chat, HomeRoute),
            NavItem("管理", Icons.Default.Settings, ManageRoute)
        ).forEach { item ->
            val selected = currentRoute == item.route
            val tint by animateColorAsState(
                if (selected) colors.primary else colors.mutedForeground,
                animationSpec = tween(200),
                label = "navTint"
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onNavigate(item.route) }
                    .padding(top = 4.dp)
            ) {
                CbIcon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    modifier = Modifier.size(22.dp),
                    tint = tint
                )
                CbText(
                    item.label,
                    color = tint,
                    style = ChatBarTheme.typography.caption,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

private data class NavItem(val label: String, val icon: ImageVector, val route: NavKey)
