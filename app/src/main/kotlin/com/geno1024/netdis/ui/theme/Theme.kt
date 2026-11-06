package com.geno1024.netdis.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geno1024.netdis.model.ThemeMode

private val DarkScheme = darkColorScheme(
    primary = Mint,
    onPrimary = Night900,
    primaryContainer = ColorTokens.DarkPrimaryContainer,
    onPrimaryContainer = Mint,
    secondary = SignalBlue,
    onSecondary = Night900,
    secondaryContainer = ColorTokens.DarkSecondaryContainer,
    onSecondaryContainer = SignalBlue,
    tertiary = SignalViolet,
    onTertiary = Night900,
    background = Night900,
    onBackground = Paper,
    surface = Night800,
    onSurface = Paper,
    surfaceVariant = Night700,
    onSurfaceVariant = NightMist,
    surfaceContainerLowest = Night900,
    surfaceContainerLow = Night800,
    surfaceContainer = Night700,
    surfaceContainerHigh = Night600,
    outline = NightLine,
    outlineVariant = NightLine,
    error = SignalRose,
    onError = Night900,
)

private val LightScheme = lightColorScheme(
    primary = MintDeep,
    onPrimary = ColorTokens.White,
    primaryContainer = ColorTokens.LightPrimaryContainer,
    onPrimaryContainer = DayInk,
    secondary = ColorTokens.LightSecondary,
    onSecondary = ColorTokens.White,
    secondaryContainer = ColorTokens.LightSecondaryContainer,
    onSecondaryContainer = DayInk,
    tertiary = ColorTokens.LightTertiary,
    onTertiary = ColorTokens.White,
    background = Day100,
    onBackground = DayInk,
    surface = ColorTokens.White,
    onSurface = DayInk,
    surfaceVariant = Day200,
    onSurfaceVariant = DayMist,
    surfaceContainerLowest = ColorTokens.White,
    surfaceContainerLow = Day100,
    surfaceContainer = Day200,
    surfaceContainerHigh = Day200,
    outline = DayLine,
    outlineVariant = DayLine,
    error = ColorTokens.LightError,
    onError = ColorTokens.White,
)

private object ColorTokens {
    val White = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
    val DarkPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF0E3A38)
    val DarkSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF122B45)
    val LightPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFCDEFE8)
    val LightSecondary = androidx.compose.ui.graphics.Color(0xFF2F6FD0)
    val LightSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFDCE8FA)
    val LightTertiary = androidx.compose.ui.graphics.Color(0xFF6750A4)
    val LightError = androidx.compose.ui.graphics.Color(0xFFB3261E)
}

private val NetDisTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
)

private val NetDisShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun NetDisTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = NetDisTypography,
        shapes = NetDisShapes,
        content = content,
    )
}
