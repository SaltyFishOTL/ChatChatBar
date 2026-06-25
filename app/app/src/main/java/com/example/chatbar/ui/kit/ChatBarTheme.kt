package com.example.chatbar.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class ChatBarColors(
    val background: Color,
    val foreground: Color,
    val card: Color,
    val cardForeground: Color,
    val cardShadow: Color,
    val popover: Color,
    val popoverForeground: Color,
    val border: Color,
    val input: Color,
    val ring: Color,
    val primary: Color,
    val primaryForeground: Color,
    val primaryAlpha: Color,
    val secondary: Color,
    val secondaryForeground: Color,
    val muted: Color,
    val mutedForeground: Color,
    val accent: Color,
    val accentForeground: Color,
    val success: Color,
    val warning: Color,
    val destructive: Color,
    val destructiveForeground: Color,
    val dim: Color
)

@Immutable
data class ChatBarTypography(
    val displayLarge: TextStyle,
    val displaySmall: TextStyle,
    val heading: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle
)

object ChatBarShape {
    val xs = 6.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val full = 9999.dp
}

object ChatBarSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 48.dp
}

object ChatBarElevation {
    val none = 0.dp
    val low = 1.dp
    val medium = 3.dp
    val high = 6.dp
    val xhigh = 8.dp
}

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
    foreground = Color(0xFF1A1C1B),
    card = Color.White,
    cardForeground = Color(0xFF1A1C1B),
    cardShadow = Color(0x1A000000),
    popover = Color.White,
    popoverForeground = Color(0xFF1A1C1B),
    border = Color(0xFFE4E7E5),
    input = Color(0xFFF5F5F3),
    ring = Color(0xFF58BCA8).copy(alpha = 0.45f),
    primary = Color(0xFF3EA08E),
    primaryForeground = Color.White,
    primaryAlpha = Color(0xFF58BCA8).copy(alpha = 0.12f),
    secondary = Color(0xFFF3F4F3),
    secondaryForeground = Color(0xFF1A1C1B),
    muted = Color(0xFFF3F4F3),
    mutedForeground = Color(0xFF757975),
    accent = Color(0xFF58BCA8).copy(alpha = 0.10f),
    accentForeground = Color(0xFF1A1C1B),
    success = Color(0xFF3DA86C),
    warning = Color(0xFFE8A838),
    destructive = Color(0xFFDC5C50),
    destructiveForeground = Color.White,
    dim = Color(0x4D000000)
)

private val DarkColors = ChatBarColors(
    background = Color(0xFF0F1412),
    foreground = Color(0xFFE4EAE7),
    card = Color(0xFF171C1A),
    cardForeground = Color(0xFFE4EAE7),
    cardShadow = Color(0x33000000),
    popover = Color(0xFF1B2120),
    popoverForeground = Color(0xFFE4EAE7),
    border = Color(0xFF2A312E),
    input = Color(0xFF151917),
    ring = Color(0xFF69CDB8).copy(alpha = 0.55f),
    primary = Color(0xFF5DC4B2),
    primaryForeground = Color(0xFF0A1E1B),
    primaryAlpha = Color(0xFF69CDB8).copy(alpha = 0.16f),
    secondary = Color(0xFF1F2624),
    secondaryForeground = Color(0xFFE4EAE7),
    muted = Color(0xFF1F2624),
    mutedForeground = Color(0xFF9EA7A3),
    accent = Color(0xFF69CDB8).copy(alpha = 0.14f),
    accentForeground = Color(0xFFE4EAE7),
    success = Color(0xFF5CBC82),
    warning = Color(0xFFDCA83C),
    destructive = Color(0xFFEC7A6E),
    destructiveForeground = Color(0xFF1F0705),
    dim = Color(0x66000000)
)

private val DefaultTypography = ChatBarTypography(
    displayLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp),
    displaySmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    heading = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    title = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    label = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    caption = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)
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
