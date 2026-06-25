package com.example.chatbar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.ChatBarShape
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File

@Composable
fun CbAvatar(
    imagePath: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    shape: Shape = CircleShape,
    fallbackIcon: ImageVector? = null,
    fallbackTint: Color = ChatBarTheme.colors.mutedForeground
) {
    Box(
        modifier = modifier.size(size).clip(shape).background(ChatBarTheme.colors.muted),
        contentAlignment = Alignment.Center
    ) {
        if (!imagePath.isNullOrBlank()) {
            AsyncImage(
                model = File(imagePath),
                contentDescription = contentDescription,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop
            )
        } else if (fallbackIcon != null) {
            CbIcon(fallbackIcon, contentDescription, Modifier.size(size * 0.5f), fallbackTint)
        }
    }
}

@Composable
fun CbAvatar(
    imagePath: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    rounded: Boolean = false,
    fallbackIcon: ImageVector? = null,
    fallbackTint: Color = ChatBarTheme.colors.mutedForeground
) {
    val shape = if (rounded) RoundedCornerShape(ChatBarShape.sm) else CircleShape
    CbAvatar(
        imagePath = imagePath,
        contentDescription = contentDescription,
        modifier = modifier,
        size = size,
        shape = shape,
        fallbackIcon = fallbackIcon,
        fallbackTint = fallbackTint
    )
}
