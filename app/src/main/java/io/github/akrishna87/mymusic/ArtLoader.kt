package io.github.akrishna87.mymusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections

/** Loads cover art embedded in the songs, with a small in-memory cache. */
object ArtLoader {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val limiter = Semaphore(4)

    private fun key(song: Song, size: Int) = "${song.id}@$size"

    fun cached(song: Song, size: Int): Bitmap? = cache.get(key(song, size))

    suspend fun load(context: Context, song: Song, size: Int): Bitmap? {
        val k = key(song, size)
        cache.get(k)?.let { return it }
        if (k in missing) return null
        return limiter.withPermit {
            withContext(Dispatchers.IO) {
                val bmp = decode(context.applicationContext, artworkUriFor(song), size)
                if (bmp != null) cache.put(k, bmp) else missing += k
                bmp
            }
        }
    }

    /**
     * Android 10+ can pull art out of each library song; older versions only have per-album art.
     * Songs from added folders are read directly.
     */
    fun artworkUriFor(song: Song): Uri =
        if (song.id.toLongOrNull() != null && Build.VERSION.SDK_INT < 29) song.albumArtUri else song.uri

    fun decode(context: Context, uri: Uri, size: Int): Bitmap? = try {
        when {
            uri.authority == MediaStore.AUTHORITY && uri.path.orEmpty().contains("/albumart") ->
                context.contentResolver.openInputStream(uri)?.use { decodeBytes(it.readBytes(), size) }
            uri.authority == MediaStore.AUTHORITY && Build.VERSION.SDK_INT >= 29 ->
                context.contentResolver.loadThumbnail(uri, Size(size, size), null)
            else -> embeddedPicture(context, uri)?.let { decodeBytes(it, size) }
        }
    } catch (e: Exception) {
        null // no embedded art, or the file has gone
    }

    private fun embeddedPicture(context: Context, uri: Uri): ByteArray? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.embeddedPicture
        } finally {
            try { r.release() } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun decodeBytes(bytes: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= size) return bmp
        val scale = size.toFloat() / longest
        return Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
    }
}
