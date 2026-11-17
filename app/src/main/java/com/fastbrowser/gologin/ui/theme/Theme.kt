package com.fastbrowser.gologin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// AMOLED-first palette
val Emerald = Color(0xFF00E676)
val EmeraldDark = Color(0xFF00B248)
val AmoledBlack = Color(0xFF000000)
val SlateCard = Color(0xFF121318)
val SlateCardElevated = Color(0xFF1B1D24)
val SlateBorder = Color(0xFF262932)
val TextPrimary = Color(0xFFF2F3F5)
val TextSecondary = Color(0xFF9BA1AB)
val DangerRed = Color(0xFFFF5252)
val WarningAmber = Color(0xFFFFC107)
val StockholmBlue = Color(0xFF4FC3F7)
val OhioRed = Color(0xFFFF8A65)

private val DarkScheme = darkColorScheme(
    primary = Emerald,
    onPrimary = Color.Black,
    secondary = EmeraldDark,
    background = AmoledBlack,
    surface = SlateCard,
    surfaceVariant = SlateCardElevated,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = SlateBorder,
    error = DangerRed
)

private val LightScheme = lightColorScheme(
    primary = EmeraldDark,
    onPrimary = Color.White,
    secondary = EmeraldDark,
    background = Color(0xFFF6F7F9),
    surface = Color.White,
    surfaceVariant = Color(0xFFECEEF1),
    onBackground = Color(0xFF14161A),
    onSurface = Color(0xFF14161A),
    onSurfaceVariant = Color(0xFF5A6068),
    outline = Color(0xFFD8DBE0),
    error = DangerRed
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp)
)

@Composable
fun FastBrowserTheme(
    amoledBlack: Boolean = true,
    content: @Composable () -> Unit
) {
    val useDark = if (amoledBlack) true else isSystemInDarkTheme()
    val scheme = if (useDark) DarkScheme else LightScheme
    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        content = content
    )
}
