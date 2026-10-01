package io.github.akrishna87.mymusic.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Dark, light, or follow the phone's setting. */
enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("Match phone") }

/** One set of the app's colours. */
data class AppColors(
    val isDark: Boolean,
    val background: Color,
    val elevated: Color,
    val elevated2: Color,
    val highlight: Color,
    val text: Color,
    val subText: Color,
    val faint: Color,
    val coral: Color,
    val violet: Color,
    /** Text and icons on top of the accent colour. */
    val onAccent: Color,
    /** The search box. */
    val field: Color,
)

/** Near-black, with a coral → violet accent. */
val DarkAppColors = AppColors(
    isDark = true,
    background = Color(0xFF0A0A0E),
    elevated = Color(0xFF16161D),
    elevated2 = Color(0xFF202029),
    highlight = Color(0xFF2B2B36),
    text = Color(0xFFFFFFFF),
    subText = Color(0xFFA3A3B2),
    faint = Color(0xFF6E6E7C),
    coral = Color(0xFFFF7A59),
    violet = Color(0xFF8E6BFF),
    onAccent = Color.Black,
    field = Color.White,
)

val LightAppColors = AppColors(
    isDark = false,
    background = Color(0xFFFAFAFC),
    elevated = Color(0xFFF0F0F4),
    elevated2 = Color(0xFFE5E5EC),
    highlight = Color(0xFFD9D9E2),
    text = Color(0xFF15151B),
    subText = Color(0xFF5B5B69),
    faint = Color(0xFF9A9AA8),
    coral = Color(0xFFE2522F),
    violet = Color(0xFF6A47E6),
    onAccent = Color.White,
    field = Color(0xFFE8E8EE),
)

/**
 * The app's colours. They change with the theme setting; reading one from a screen redraws it
 * when the theme changes.
 */
object Palette {
    var colors by mutableStateOf(DarkAppColors)

    val isDark get() = colors.isDark
    val Background get() = colors.background
    val Elevated get() = colors.elevated
    val Elevated2 get() = colors.elevated2
    val Highlight get() = colors.highlight
    val Text get() = colors.text
    val SubText get() = colors.subText
    val Faint get() = colors.faint
    val Coral get() = colors.coral
    val Violet get() = colors.violet
    val OnAccent get() = colors.onAccent
    val Field get() = colors.field
}

/** The colours for a theme, with accents taken from the wallpaper when [wallpaper] is on (Android 12+). */
fun appColors(context: Context, dark: Boolean, wallpaper: Boolean): AppColors {
    val base = if (dark) DarkAppColors else LightAppColors
    if (!wallpaper || Build.VERSION.SDK_INT < 31) return base
    val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    return base.copy(coral = scheme.primary, violet = scheme.tertiary, onAccent = scheme.onPrimary)
}

private fun AppColors.scheme(): ColorScheme =
    if (isDark) {
        darkColorScheme(
            primary = coral,
            onPrimary = onAccent,
            primaryContainer = Color(0xFF5A2A1C),
            onPrimaryContainer = Color(0xFFFFDBD0),
            secondary = violet,
            onSecondary = Color.White,
            secondaryContainer = Color(0xFF2E2550),
            onSecondaryContainer = Color(0xFFE9E2FF),
            background = background,
            onBackground = text,
            surface = background,
            onSurface = text,
            surfaceVariant = elevated2,
            onSurfaceVariant = subText,
            surfaceContainerLowest = Color(0xFF050507),
            surfaceContainerLow = Color(0xFF111116),
            surfaceContainer = elevated,
            surfaceContainerHigh = elevated2,
            surfaceContainerHighest = highlight,
            outline = Color(0xFF4A4A58),
            outlineVariant = Color(0xFF2A2A34),
            inverseSurface = Color(0xFFF2F2F5),
            inverseOnSurface = Color(0xFF16161D),
        )
    } else {
        lightColorScheme(
            primary = coral,
            onPrimary = onAccent,
            primaryContainer = Color(0xFFFFDBD0),
            onPrimaryContainer = Color(0xFF3A0B00),
            secondary = violet,
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE9E2FF),
            onSecondaryContainer = Color(0xFF21005D),
            background = background,
            onBackground = text,
            surface = background,
            onSurface = text,
            surfaceVariant = elevated2,
            onSurfaceVariant = subText,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF5F5F8),
            surfaceContainer = elevated,
            surfaceContainerHigh = elevated2,
            surfaceContainerHighest = highlight,
            outline = Color(0xFF8A8A98),
            outlineVariant = Color(0xFFCACAD4),
            inverseSurface = Color(0xFF26262E),
            inverseOnSurface = Color(0xFFF2F2F5),
        )
    }

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
val BrandGradient get() = Brush.linearGradient(listOf(Palette.Violet, Palette.Coral))

@Composable
fun MyMusicTheme(mode: ThemeMode = ThemeMode.DARK, wallpaperColors: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colors = remember(dark, wallpaperColors) { appColors(context, dark, wallpaperColors) }
    SideEffect { if (Palette.colors != colors) Palette.colors = colors }
    MaterialTheme(colorScheme = colors.scheme(), typography = AppTypography) {
        // Text and icons default to black outside a Surface; use the theme's text colour instead.
        CompositionLocalProvider(LocalContentColor provides colors.text, content = content)
    }
}

/** Keeps the full player dark in every theme, like the cover-tinted players in other music apps. */
@Composable
fun AlwaysDark(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
}

/** The theme choice, saved on the phone and read before the first frame (so there's no flash). */
object ThemeSettings {
    var mode by mutableStateOf(ThemeMode.DARK)
        private set
    var wallpaperColors by mutableStateOf(false)
        private set
    /** Show the player over the lock screen when the phone is locked with Isaialai open. */
    var lockScreenPlayer by mutableStateOf(true)
        private set
    private var loaded = false

    private fun prefs(context: Context) = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        mode = ThemeMode.entries.firstOrNull { it.name == p.getString("theme", null) } ?: ThemeMode.DARK
        wallpaperColors = p.getBoolean("wallpaperColors", false)
        lockScreenPlayer = p.getBoolean("lockScreenPlayer", true)
    }

    fun setLockScreenPlayer(context: Context, on: Boolean) {
        lockScreenPlayer = on
        prefs(context).edit().putBoolean("lockScreenPlayer", on).apply()
    }

    fun update(context: Context, mode: ThemeMode = this.mode, wallpaperColors: Boolean = this.wallpaperColors) {
        this.mode = mode
        this.wallpaperColors = wallpaperColors
        prefs(context).edit().putString("theme", mode.name).putBoolean("wallpaperColors", wallpaperColors).apply()
    }
}
