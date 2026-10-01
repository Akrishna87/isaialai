package io.github.akrishna87.mymusic.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The app's own palette: near-black, with a coral → violet accent. */
object Palette {
    val Background = Color(0xFF0A0A0E)
    val Elevated = Color(0xFF16161D)
    val Elevated2 = Color(0xFF202029)
    val Highlight = Color(0xFF2B2B36)
    val Text = Color(0xFFFFFFFF)
    val SubText = Color(0xFFA3A3B2)
    val Faint = Color(0xFF6E6E7C)
    val Coral = Color(0xFFFF7A59)
    val Violet = Color(0xFF8E6BFF)
}

private val DarkColors = darkColorScheme(
    primary = Palette.Coral,
    onPrimary = Color(0xFF1A0600),
    primaryContainer = Color(0xFF5A2A1C),
    onPrimaryContainer = Color(0xFFFFDBD0),
    secondary = Palette.Violet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2E2550),
    onSecondaryContainer = Color(0xFFE9E2FF),
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Background,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Elevated2,
    onSurfaceVariant = Palette.SubText,
    surfaceContainerLowest = Color(0xFF050507),
    surfaceContainerLow = Color(0xFF111116),
    surfaceContainer = Palette.Elevated,
    surfaceContainerHigh = Palette.Elevated2,
    surfaceContainerHighest = Palette.Highlight,
    outline = Color(0xFF4A4A58),
    outlineVariant = Color(0xFF2A2A34),
    inverseSurface = Color(0xFFF2F2F5),
    inverseOnSurface = Color(0xFF16161D),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.8).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Bold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
    )
}

/** Brand gradient, used for "Shuffle all" and the app's own tiles. */
val BrandGradient = Brush.linearGradient(listOf(Palette.Violet, Palette.Coral))

@Composable
fun MyMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = AppTypography, content = content)
}
