package com.example.chatbar.ui.kit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun CbSkeletonLine(
    modifier: Modifier = Modifier,
    widthFraction: Float = 1f
) {
    val shimmer = rememberShimmerBrush()
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(14.dp)
            .clip(RoundedCornerShape(ChatBarShape.xs))
            .background(shimmer)
    )
}

@Composable
fun CbSkeletonCard(
    modifier: Modifier = Modifier,
    lineCount: Int = 3
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ChatBarShape.md))
            .background(ChatBarTheme.colors.card)
            .padding(ChatBarSpacing.lg)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            CbSkeletonAvatar(Modifier.size(40.dp))
            Spacer(Modifier.width(ChatBarSpacing.md))
            Column {
                CbSkeletonLine(Modifier, 0.6f)
                Spacer(Modifier.height(ChatBarSpacing.sm))
                CbSkeletonLine(Modifier, 0.35f)
            }
        }
        Spacer(Modifier.height(ChatBarSpacing.lg))
        repeat(lineCount) {
            CbSkeletonLine(Modifier, if (it == lineCount - 1) 0.55f else 1f)
            if (it < lineCount - 1) Spacer(Modifier.height(ChatBarSpacing.sm))
        }
    }
}

@Composable
fun CbSkeletonAvatar(modifier: Modifier = Modifier) {
    val shimmer = rememberShimmerBrush()
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(shimmer)
    )
}

@Composable
private fun rememberShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )
    val baseColor = ChatBarTheme.colors.muted
    val shimmerColor = ChatBarTheme.colors.border
    return Brush.linearGradient(
        colors = listOf(baseColor, shimmerColor, baseColor),
        start = Offset(translateAnim - 300, 0f),
        end = Offset(translateAnim, 0f)
    )
}
