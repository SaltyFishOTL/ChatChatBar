package com.example.chatbar.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.example.chatbar.ui.kit.ChatBarTheme

/**
 * 正在输入指示器 - 仿微信/Telegram 的三点动画
 */
@Composable
fun TypingIndicator(
    modifier: Modifier = Modifier,
    dotColor: Color? = null
) {
    val resolvedDotColor = dotColor ?: ChatBarTheme.colors.mutedForeground
    val transition = rememberInfiniteTransition(label = "typing")
    
    @Composable
    fun animateDot(delayMillis: Int): Float {
        val scale by transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 900
                    0.3f at delayMillis
                    1.0f at delayMillis + 200
                    0.3f at delayMillis + 400
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "dotScale"
        )
        return scale
    }

    val scale1 = animateDot(0)
    val scale2 = animateDot(150)
    val scale3 = animateDot(300)

    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Dot(scale1, resolvedDotColor)
        Dot(scale2, resolvedDotColor)
        Dot(scale3, resolvedDotColor)
    }
}

@Composable
private fun Dot(scale: Float, color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = scale.coerceAtLeast(0.4f)
            }
            .background(color, CircleShape)
    )
}
