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

// Layered dark palette (v1.0.5 — "too simple plain black" fix).
// The app is no longer one flat black plane: a near-black blue-charcoal
// background, clearly lighter elevated surfaces, and visible borders give
// every screen depth. Emerald stays the accent — it is the app's identity.
val Emerald = Color(0xFF00E676)
val EmeraldDark = Color(0xFF00B248)
/** Faint green tint for the active server card. */
val SurfaceActive = Color(0xFF122019)
val AmoledBlack = Color(0xFF000000)
// Default dark theme: near-black blue-charcoal, not pure black.
val AppBackground = Color(0xFF0A0C10)
val SlateCard = Color(0xFF15171F)
val SlateCardElevated = Color(0xFF1F222C)
val SlateBorder = Color(0xFF2E3342)
val TextPrimary = Color(0xFFF2F3F5)
val TextSecondary = Color(0xFFA6ACB7)
val DangerRed = Color(0xFFFF5252)
val WarningAmber = Color(0xFFFFC107)
val StockholmBlue = Color(0xFF4FC3F7)
val OhioRed = Color(0xFFFF8A65)

private val DarkScheme = darkColorScheme(
    primary = Emerald,
    onPrimary = Color.Black,
    secondary = EmeraldDark,
    background = AppBackground,
    surface = SlateCard,
    surfaceVariant = SlateCardElevated,
    surfaceContainerLowest = Color(0xFF07080B),
    surfaceContainerLow = Color(0xFF0E1015),
    surfaceContainer = Color(0xFF191C24),
    surfaceContainerHigh = Color(0xFF232633),
    surfaceContainerHighest = Color(0xFF2D3140),
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = SlateBorder,
    outlineVariant = Color(0xFF232734),
    error = DangerRed
)

/** AMOLED mode: true-black background, but surfaces and borders stay
 *  visibly lighter — depth survives even at #000000. */
private val AmoledScheme = darkColorScheme(
    primary = Emerald,
    onPrimary = Color.Black,
    secondary = EmeraldDark,
    background = AmoledBlack,
    surface = Color(0xFF101218),
    surfaceVariant = Color(0xFF1A1D26),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0B0E),
    surfaceContainer = Color(0xFF14161C),
    surfaceContainerHigh = Color(0xFF1C1F28),
    surfaceContainerHighest = Color(0xFF262A35),
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFF272C3A),
    outlineVariant = Color(0xFF1C2029),
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
    val scheme = when {
        amoledBlack -> AmoledScheme
        isSystemInDarkTheme() -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        content = content
    )
}
