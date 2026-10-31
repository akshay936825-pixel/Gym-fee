package com.gym.hisab.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class Semantic(val good: Color, val warn: Color, val bad: Color, val muted: Color, val card: Color, val line: Color)

val LocalSemantic = staticCompositionLocalOf {
    Semantic(Color(0xFF0F9D6E), Color(0xFFB7791F), Color(0xFFDC2626), Color(0xFF5B6877), Color.White, Color(0xFFE1E5EB))
}

private val Dark = darkColorScheme(
    primary = Color(0xFF34D399), onPrimary = Color(0xFF04201A),
    primaryContainer = Color(0xFF0F3D31), onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = Color(0xFFFBBF24), onSecondary = Color(0xFF1F1500),
    background = Color(0xFF0E1116), onBackground = Color(0xFFE8EDF3),
    surface = Color(0xFF151A21), onSurface = Color(0xFFE8EDF3),
    surfaceVariant = Color(0xFF1C232C), onSurfaceVariant = Color(0xFF9AA6B6),
    outline = Color(0xFF2A323D), error = Color(0xFFF87171),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0F9D6E), onPrimary = Color.White,
    primaryContainer = Color(0xFFD5F5E8), onPrimaryContainer = Color(0xFF053D2A),
    secondary = Color(0xFFB7791F), onSecondary = Color.White,
    background = Color(0xFFF6F7F9), onBackground = Color(0xFF0F1720),
    surface = Color.White, onSurface = Color(0xFF0F1720),
    surfaceVariant = Color(0xFFEEF1F5), onSurfaceVariant = Color(0xFF5B6877),
    outline = Color(0xFFE1E5EB), error = Color(0xFFDC2626),
)

private val Type = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

private val Round = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

@Composable
fun HisabTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val semantic = if (dark) Semantic(Color(0xFF34D399), Color(0xFFFBBF24), Color(0xFFF87171), Color(0xFF9AA6B6), Color(0xFF151A21), Color(0xFF2A323D))
    else Semantic(Color(0xFF0F9D6E), Color(0xFFB7791F), Color(0xFFDC2626), Color(0xFF5B6877), Color.White, Color(0xFFE1E5EB))
    CompositionLocalProvider(LocalSemantic provides semantic) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, shapes = Round, content = content)
    }
}

val sem: Semantic
    @Composable get() = LocalSemantic.current
