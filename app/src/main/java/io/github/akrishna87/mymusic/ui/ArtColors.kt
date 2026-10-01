package io.github.akrishna87.mymusic.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette as ColorPalette
import io.github.akrishna87.mymusic.ArtLoader
import io.github.akrishna87.mymusic.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pairs of colours for songs and folders without cover art, chosen by name so they stay stable. */
private val placeholderPairs = listOf(
    0xFF6C4BD8 to 0xFFFF7A59,
    0xFF1E88E5 to 0xFF00BFA5,
    0xFFD81B60 to 0xFF8E24AA,
    0xFFF4511E to 0xFFFFB300,
    0xFF00897B to 0xFF7CB342,
    0xFF3949AB to 0xFF00ACC1,
    0xFFE53935 to 0xFFAB47BC,
    0xFF5E35B1 to 0xFFEC407A,
)

private fun pairFor(key: String) = placeholderPairs[(key.hashCode() and 0x7fffffff) % placeholderPairs.size]

fun placeholderBrush(key: String): Brush = pairFor(key).let { (a, b) -> Brush.linearGradient(listOf(Color(a), Color(b))) }

fun placeholderColor(key: String): Color = Color(pairFor(key).first)

fun Song.colorKey(): String = albumKey.ifEmpty { title }

/** A darker shade for backgrounds, keeping white text readable. */
fun Color.deep(amount: Float = 0.45f): Color = lerp(this, Color.Black, amount)

/** A tint for page backgrounds: darker in the dark theme, a pale wash in the light one, so text stays readable. */
fun Color.wash(amount: Float = 0.45f): Color =
    if (Palette.isDark) lerp(this, Palette.Background, amount) else lerp(this, Palette.Background, 0.55f + amount * 0.45f)

/** Picks a lively colour out of each song's cover art (cached). */
object ArtColors {
    private val cache = LruCache<String, Int>(400)

    fun cached(song: Song): Color? = cache.get(song.id)?.let { Color(it) }

    suspend fun load(context: Context, song: Song): Color? {
        cache.get(song.id)?.let { return Color(it) }
        val bmp = ArtLoader.load(context, song, 160) ?: return null
        val rgb = withContext(Dispatchers.Default) {
            try {
                // Palette needs to read pixels, which hardware bitmaps don't allow.
                val readable = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
                val p = ColorPalette.from(readable).maximumColorCount(16).generate()
                (p.vibrantSwatch ?: p.darkVibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch)?.rgb
            } catch (e: Exception) {
                null
            }
        } ?: return null
        cache.put(song.id, rgb)
        return Color(rgb)
    }
}

/** The colour of [song]'s artwork, animated when the song changes. */
@Composable
fun rememberArtColor(song: Song?, fallback: Color = Palette.Highlight): Color {
    val context = LocalContext.current
    var target by remember(song?.id) {
        mutableStateOf(song?.let { ArtColors.cached(it) ?: placeholderColor(it.colorKey()) } ?: fallback)
    }
    LaunchedEffect(song?.id) {
        if (song != null) ArtColors.load(context, song)?.let { target = it }
    }
    val animated by animateColorAsState(target, tween(700), label = "artColor")
    return animated
}
