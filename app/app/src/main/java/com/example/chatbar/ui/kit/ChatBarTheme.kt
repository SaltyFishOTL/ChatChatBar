package com.example.chatbar.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class ChatBarColors(
    val background: Color,
    val foreground: Color,
    val card: Color,
    val cardForeground: Color,
    val popover: Color,
    val popoverForeground: Color,
    val border: Color,
    val input: Color,
    val ring: Color,
    val primary: Color,
    val primaryForeground: Color,
    val secondary: Color,
    val secondaryForeground: Color,
    val muted: Color,
    val mutedForeground: Color,
    val accent: Color,
    val accentForeground: Color,
    val success: Color,
    val destructive: Color,
    val destructiveForeground: Color
)

@Immutable
data class ChatBarTypography(
    val title: TextStyle,
    val heading: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle
)

private val LocalChatBarColors = staticCompositionLocalOf<ChatBarColors> {
    error("ChatBarTheme missing")
}

private val LocalChatBarTypography = staticCompositionLocalOf<ChatBarTypography> {
    error("ChatBarTheme missing")
}

object ChatBarTheme {
    val colors: ChatBarColors
        @Composable get() = LocalChatBarColors.current
    val typography: ChatBarTypography
        @Composable get() = LocalChatBarTypography.current
}

private val LightColors = ChatBarColors(
    background = Color(0xFFFAFAFA),
    foreground = Color(0xFF2C3130),
    card = Color.White,
    cardForeground = Color(0xFF2C3130),
    popover = Color.White,
    popoverForeground = Color(0xFF2C3130),
    border = Color(0xFFE8E8E8),
    input = Color(0xFFFAFAFA),
    ring = Color(0xFF58BCA8).copy(alpha = 0.45f),
    primary = Color(0xFF58BCA8),
    primaryForeground = Color.White,
    secondary = Color(0xFFF6F6F6),
    secondaryForeground = Color(0xFF2C3130),
    muted = Color(0xFFF6F6F6),
    mutedForeground = Color(0xFF6F7773),
    accent = Color(0xFF58BCA8).copy(alpha = 0.10f),
    accentForeground = Color(0xFF2C3130),
    success = Color(0xFF4BAE76),
    destructive = Color(0xFFE46F62),
    destructiveForeground = Color.White
)

private val DarkColors = ChatBarColors(
    background = Color(0xFF111514),
    foreground = Color(0xFFE8EEEB),
    card = Color(0xFF181D1B),
    cardForeground = Color(0xFFE8EEEB),
    popover = Color(0xFF1C2220),
    popoverForeground = Color(0xFFE8EEEB),
    border = Color(0xFF2A322F),
    input = Color(0xFF161B19),
    ring = Color(0xFF69CDB8).copy(alpha = 0.55f),
    primary = Color(0xFF69CDB8),
    primaryForeground = Color(0xFF0C2520),
    secondary = Color(0xFF202724),
    secondaryForeground = Color(0xFFE8EEEB),
    muted = Color(0xFF202724),
    mutedForeground = Color(0xFFA2ACA7),
    accent = Color(0xFF69CDB8).copy(alpha = 0.16f),
    accentForeground = Color(0xFFE8EEEB),
    success = Color(0xFF63C68C),
    destructive = Color(0xFFF08074),
    destructiveForeground = Color(0xFF2B0D09)
)

private val DefaultTypography = ChatBarTypography(
    title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    heading = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    body = TextStyle(fontSize = 14.sp),
    label = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    caption = TextStyle(fontSize = 11.sp)
)

@Composable
fun ChatBarTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalChatBarColors provides if (darkTheme) DarkColors else LightColors,
        LocalChatBarTypography provides DefaultTypography,
        content = content
    )
}
