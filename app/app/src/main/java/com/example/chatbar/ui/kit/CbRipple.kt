package com.example.chatbar.ui.kit

import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

@Composable
fun ProvideRipple(
    rippleColor: Color = ChatBarTheme.colors.primary,
    content: @Composable () -> Unit
) {
    val ripple = remember(rippleColor) {
        ripple(bounded = true, color = rippleColor)
    }
    CompositionLocalProvider(
        LocalIndication provides ripple,
        content = content
    )
}
