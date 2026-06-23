package com.example.chatbar.ui.kit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun CbProgress(progress: Float, modifier: Modifier = Modifier, error: Boolean = false) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(ChatBarTheme.colors.muted, RoundedCornerShape(3.dp))
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(6.dp)
                .background(
                    if (error) ChatBarTheme.colors.destructive else ChatBarTheme.colors.primary,
                    RoundedCornerShape(3.dp)
                )
        )
    }
}

@Composable
fun CbSpinner(modifier: Modifier = Modifier) {
    val color = ChatBarTheme.colors.primary
    val transition = rememberInfiniteTransition(label = "spinner")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label = "spinnerRotation"
    )
    Canvas(modifier.size(28.dp).rotate(rotation)) {
        drawArc(
            color = color,
            startAngle = 20f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
            size = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}
