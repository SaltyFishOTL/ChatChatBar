package com.example.chatbar.ui.components

import com.example.chatbar.ui.kit.AppIcons

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.example.chatbar.HomeRoute
import com.example.chatbar.ManageRoute
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarElevation
import com.example.chatbar.ui.kit.ChatBarMotion
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(ChatBarElevation.xhigh, shape, ambientColor = colors.cardShadow, spotColor = colors.cardShadow)
            .background(colors.surfaceElevated, shape)
            .border(1.dp, colors.border.copy(alpha = 0.72f), shape)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = ChatBarSpacing.lg, vertical = ChatBarSpacing.sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                NavItem("聊天", AppIcons.Chat, HomeRoute),
                NavItem("管理", AppIcons.Settings, ManageRoute)
            ).forEach { item ->
                val selected = currentRoute == item.route
                val tint by animateColorAsState(
                    if (selected) colors.primary else colors.mutedForeground,
                    animationSpec = tween(ChatBarMotion.normal),
                    label = "navTint"
                )
                val container by animateColorAsState(
                    if (selected) colors.primaryAlpha else Color.Transparent,
                    animationSpec = tween(ChatBarMotion.normal),
                    label = "navContainer"
                )
                val scale by animateFloatAsState(
                    if (selected) 1f else 0.96f,
                    animationSpec = tween(ChatBarMotion.normal),
                    label = "navScale"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(RoundedCornerShape(ChatBarShape.md))
                        .background(container)
                        .clickable(role = Role.Tab) { onNavigate(item.route) }
                        .semantics { this.selected = selected },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
    }
}

private data class NavItem(val label: String, val icon: ImageVector, val route: NavKey)
