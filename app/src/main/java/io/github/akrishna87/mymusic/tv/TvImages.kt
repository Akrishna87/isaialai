package io.github.akrishna87.mymusic.tv

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import io.github.akrishna87.mymusic.ui.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/** Covers from the phone, kept for a while so scrolling back doesn't fetch them again. */
object TvImages {
    private val cache = LruCache<String, ImageBitmap>(160)
    private val missing = LruCache<String, Boolean>(400)

    suspend fun load(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        if (missing.get(url) == true) return null
        return withContext(Dispatchers.IO) {
            try {
                val c = URI(url).toURL().openConnection() as HttpURLConnection
                c.connectTimeout = 5_000
                c.readTimeout = 10_000
                try {
                    if (c.responseCode != 200) {
                        if (c.responseCode == 404) missing.put(url, true)
                        return@withContext null
                    }
                    val bytes = c.inputStream.use { it.readBytes() }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also { cache.put(url, it) }
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** A song's cover from the phone, with a note icon until it arrives (or if there isn't one). */
@Composable
fun RemoteCover(url: String?, modifier: Modifier, shape: Shape, iconSize: Dp) {
    val image by produceState<ImageBitmap?>(null, url) { value = url?.let { TvImages.load(it) } }
    Box(modifier.clip(shape).background(Palette.Elevated2), contentAlignment = Alignment.Center) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Palette.SubText, modifier = Modifier.size(iconSize))
        }
    }
}
