package com.waterrower.tracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class RowingColors(
    val pace: Color,
    val distance: Color,
    val active: Color,
    val warning: Color,
    val heartRate: Color,
    val recording: Color,
)

private val DarkRowingColors = RowingColors(
    pace = Color(0xFF38BDF8),
    distance = Color(0xFF34D399),
    active = Color(0xFF22C55E),
    warning = Color(0xFFFBBF24),
    heartRate = Color(0xFFF87171),
    recording = Color(0xFF22C55E),
)

private val LightRowingColors = RowingColors(
    pace = Color(0xFF026894),
    distance = Color(0xFF0B7A54),
    active = Color(0xFF15803D),
    warning = Color(0xFFB45309),
    heartRate = Color(0xFFDC2626),
    recording = Color(0xFF15803D),
)

val LocalRowingColors = staticCompositionLocalOf { DarkRowingColors }

private val DeepOceanDarkScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF00344C),
    primaryContainer = Color(0xFF0B4A6F),
    onPrimaryContainer = Color(0xFFCAE9FF),
    inversePrimary = Color(0xFF026894),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF003825),
    secondaryContainer = Color(0xFF0A5238),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF3F2800),
    tertiaryContainer = Color(0xFF5C3D00),
    onTertiaryContainer = Color(0xFFFEF08A),
    background = Color(0xFF091017),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF091017),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1B2B3C),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceTint = Color(0xFF38BDF8),
    inverseSurface = Color(0xFFE2E8F0),
    inverseOnSurface = Color(0xFF0F172A),
    error = Color(0xFFF87171),
    onError = Color(0xFF5C0A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF243447),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF283C52),
    surfaceDim = Color(0xFF060B10),
    surfaceContainerLowest = Color(0xFF060B10),
    surfaceContainerLow = Color(0xFF0F1923),
    surfaceContainer = Color(0xFF14212E),
    surfaceContainerHigh = Color(0xFF1B2B3C),
    surfaceContainerHighest = Color(0xFF23364B),
)

private val AquaticLightScheme = lightColorScheme(
    primary = Color(0xFF026894),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEEFF),
    onPrimaryContainer = Color(0xFF001F2E),
    inversePrimary = Color(0xFF38BDF8),
    secondary = Color(0xFF0B7A54),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC6F6E2),
    onSecondaryContainer = Color(0xFF002114),
    tertiary = Color(0xFFB45309),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF451A03),
    background = Color(0xFFF4F8FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF4F8FB),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFDBE7F2),
    onSurfaceVariant = Color(0xFF475569),
    surfaceTint = Color(0xFF026894),
    inverseSurface = Color(0xFF1E293B),
    inverseOnSurface = Color(0xFFF1F5F9),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFFCBD5E1),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD5E2EE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5FA),
    surfaceContainer = Color(0xFFE6EFF7),
    surfaceContainerHigh = Color(0xFFDBE7F2),
    surfaceContainerHighest = Color(0xFFD0DFEC),
)

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 52.sp,
        lineHeight = 58.sp,
        letterSpacing = (-1.0).sp,
        fontFeatureSettings = "tnum",
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = "tnum",
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontFeatureSettings = "tnum",
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontFeatureSettings = "tnum",
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun WaterRowerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DeepOceanDarkScheme else AquaticLightScheme
    val rowingColors = if (darkTheme) DarkRowingColors else LightRowingColors

    CompositionLocalProvider(LocalRowingColors provides rowingColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
