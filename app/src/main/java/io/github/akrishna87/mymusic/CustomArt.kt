package io.github.akrishna87.mymusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.security.MessageDigest

/**
 * Cover pictures you've chosen for songs, used instead of (or where there's no) art in the file.
 * A copy of each picture is kept in the app's own storage; song files are never changed.
 */
object CustomArt {
    private const val SCHEME = "isaialai-art"
    private const val HOST = "cover"
    private const val MAX_SIZE = 1200
    private val NAME = Regex("[0-9a-f]{40}")

    /** Goes up whenever a cover is added or removed, so pictures on screen reload. */
    var version by mutableIntStateOf(0)
        private set

    @Volatile
    private var names: Set<String>? = null

    private fun dir(context: Context) = File(context.applicationContext.filesDir, "covers")

    private fun nameFor(songId: String): String =
        MessageDigest.getInstance("SHA-1").digest(songId.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Reads which songs have a cover. Call once at start-up (it's quick: one folder listing). */
    @Synchronized
    fun load(context: Context) {
        if (names == null) names = dir(context).list()?.mapNotNull { it.removeSuffix(".jpg").takeIf(NAME::matches) }?.toSet() ?: emptySet()
    }

    fun has(songId: String): Boolean = names?.contains(nameFor(songId)) == true

    /** The address the player and notification use for a song's own cover, or null if it has none. */
    fun uriFor(songId: String): Uri? =
        if (has(songId)) Uri.Builder().scheme(SCHEME).authority(HOST).appendPath(nameFor(songId)).build() else null

    fun isCoverUri(uri: Uri): Boolean =
        uri.scheme == SCHEME && uri.authority == HOST && uri.pathSegments.size == 1 && NAME.matches(uri.pathSegments[0]) &&
            uri.query == null && uri.fragment == null

    /** The picture behind a cover address; only ever a file in the app's own covers folder. */
    fun fileFor(context: Context, uri: Uri): File? =
        if (isCoverUri(uri)) File(dir(context), uri.pathSegments[0] + ".jpg").takeIf { it.isFile } else null

    /**
     * Uses the picture at [image] as the cover of [songIds]: cropped to a square, at most
     * 1200 px, saved as JPEG. Returns false if it isn't a picture that can be read. Slow: call
     * off the main thread.
     */
    fun save(context: Context, songIds: List<String>, image: Uri): Boolean {
        val square = readSquare(context, image) ?: return false
        val folder = dir(context).apply { mkdirs() }
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            square.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
        val added = songIds.map { id ->
            val name = nameFor(id)
            val tmp = File(folder, "$name.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(File(folder, "$name.jpg"))
            name
        }
        synchronized(this) { names = (names ?: emptySet()) + added }
        version++
        return true
    }

    fun remove(context: Context, songIds: List<String>) {
        val gone = songIds.map(::nameFor)
        gone.forEach { File(dir(context), "$it.jpg").delete() }
        synchronized(this) { names = (names ?: emptySet()) - gone.toSet() }
        version++
    }

    private fun readSquare(context: Context, image: Uri): Bitmap? = try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(image)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIZE) sample *= 2
            val bmp = resolver.openInputStream(image)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            bmp?.let {
                // The middle square, like an album cover.
                val side = minOf(it.width, it.height)
                val square = Bitmap.createBitmap(it, (it.width - side) / 2, (it.height - side) / 2, side, side)
                if (side > MAX_SIZE) Bitmap.createScaledBitmap(square, MAX_SIZE, MAX_SIZE, true) else square
            }
        }
    } catch (e: Exception) {
        null
    }
}
