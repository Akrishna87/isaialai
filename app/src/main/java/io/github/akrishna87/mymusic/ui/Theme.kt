package io.github.akrishna87.mymusic.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Same palette as the web version of My Music.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF7A59),
    onPrimary = Color(0xFF2B0D04),
    primaryContainer = Color(0xFF5A2A1C),
    onPrimaryContainer = Color(0xFFFFDBD0),
    secondary = Color(0xFFB9A6FF),
    onSecondary = Color(0xFF1D1240),
    secondaryContainer = Color(0xFF3A2F57),
    onSecondaryContainer = Color(0xFFF3EFFC),
    background = Color(0xFF14111C),
    onBackground = Color(0xFFF3EFFC),
    surface = Color(0xFF14111C),
    onSurface = Color(0xFFF3EFFC),
    surfaceVariant = Color(0xFF262134),
    onSurfaceVariant = Color(0xFFA69FBE),
    surfaceContainerLowest = Color(0xFF0F0D15),
    surfaceContainerLow = Color(0xFF1A1724),
    surfaceContainer = Color(0xFF1E1A29),
    surfaceContainerHigh = Color(0xFF262134),
    surfaceContainerHighest = Color(0xFF2E2840),
    outline = Color(0xFF4A4163),
    outlineVariant = Color(0xFF322B45),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFE0532E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBD0),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF6C4BD8),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6DEFF),
    onSecondaryContainer = Color(0xFF1D1630),
    background = Color(0xFFF8F6FD),
    onBackground = Color(0xFF1D1630),
    surface = Color(0xFFF8F6FD),
    onSurface = Color(0xFF1D1630),
    surfaceVariant = Color(0xFFF0ECF9),
    onSurfaceVariant = Color(0xFF6B6387),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF9FF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0ECF9),
    surfaceContainerHighest = Color(0xFFE8E2F5),
    outline = Color(0xFFB9B0D0),
    outlineVariant = Color(0xFFE2DCF0),
)

val ArtPlaceholder = Brush.linearGradient(listOf(Color(0xFF6C4BD8), Color(0xFFFF7A59)))

@Composable
fun MyMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
