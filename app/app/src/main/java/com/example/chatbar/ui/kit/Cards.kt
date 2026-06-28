package com.example.chatbar.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun CbCard(
    modifier: Modifier = Modifier,
    elevation: Dp = ChatBarElevation.low,
    shape: Shape = RoundedCornerShape(ChatBarShape.md),
    border: BorderStroke? = null,
    color: Color = ChatBarTheme.colors.card,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = ChatBarTheme.colors
    val surfaceColor = if (elevation > 0.dp && color == colors.card) colors.surfaceElevated else color
    Column(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = colors.cardShadow,
                spotColor = colors.cardShadow,
                clip = false
            )
            .clip(shape)
            .background(surfaceColor)
            .let { m -> if (border != null) m.border(border, shape) else m }
            .padding(ChatBarSpacing.lg),
        content = content
    )
}

@Composable
fun CbCardSectioned(
    modifier: Modifier = Modifier,
    elevation: Dp = ChatBarElevation.low,
    shape: Shape = RoundedCornerShape(ChatBarShape.md),
    border: BorderStroke? = null,
    color: Color = ChatBarTheme.colors.card,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = ChatBarTheme.colors
    val surfaceColor = if (elevation > 0.dp && color == colors.card) colors.surfaceElevated else color
    Column(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = colors.cardShadow,
                spotColor = colors.cardShadow,
                clip = false
            )
            .clip(shape)
            .background(surfaceColor)
            .let { m -> if (border != null) m.border(border, shape) else m }
    ) {
        header?.let {
            Column(Modifier.padding(horizontal = ChatBarSpacing.lg, vertical = ChatBarSpacing.md)) { it() }
            CbDivider()
        }
        Column(Modifier.padding(ChatBarSpacing.lg)) { content() }
        footer?.let {
            CbDivider()
            Column(Modifier.padding(horizontal = ChatBarSpacing.lg, vertical = ChatBarSpacing.md)) { it() }
        }
    }
}
