package io.github.akrishna87.mymusic

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * All your songs in one zip file, to move them to a new phone: each song keeps its folder
 * ("Music/Hindi/song.mp3"), so playlists find them again, and Isaialai's backup goes in too.
 * On the new phone, unpacking puts every song back in its folder and offers the backup.
 */
object MusicZip {
    /** Where the backup goes inside the zip: the same place as on the phone. */
    const val BACKUP_DIR = "Download/Isaialai"

    /** What unpacking takes from a zip: audio files (and the backup); anything else is left out. */
    private val AUDIO = Regex("\\.(mp3|m4a|m4b|aac|flac|ogg|oga|opus|wav|wma|amr|awb|mka|mid|midi|aif|aiff|3gp|mp4|webm)$", RegexOption.IGNORE_CASE)

    /** Folders Android lets apps put audio in; songs from anywhere else go under Music. */
    private val AUDIO_DIRS = setOf("Music", "Alarms", "Notifications", "Podcasts", "Ringtones", "Audiobooks", "Recordings")

    /** Unpacking stops while this little is free, rather than fill the phone. */
    private const val KEEP_FREE = 200L * 1024 * 1024

    class Progress(
        val title: String,
        val files: Int,
        /** -1 while unknown (unpacking reads the zip from start to end). */
        val totalFiles: Int,
        val bytes: Long,
        val totalBytes: Long,
        val current: String,
    )

    /** What packing would do: each song with its place in the zip, how big, and the phone's free space. */
    class Plan(val songs: List<Pair<Song, String>>, val totalBytes: Long, val freeBytes: Long)

    class NotEnoughSpace : IOException("Not enough space")

    class Unpacked(val added: Int, val skipped: Int, val failed: Int, val backupText: String?)

    @Suppress("DEPRECATION")
    private fun storageRoot(): File = Environment.getExternalStorageDirectory()

    fun freeBytes(): Long = try {
        StatFs(storageRoot().path).availableBytes
    } catch (e: Exception) {
        -1
    }

    /** "Music/Hindi/song.mp3": the song's folder and file name, as it's put in the zip. */
    fun entryPath(s: Song): String {
        val dir = s.folder.trim('/')
        return clean(if (dir.isEmpty()) s.fileName else "$dir/${s.fileName}") ?: s.fileName.ifBlank { "song" }
    }

    /**
     * A safe relative path from a zip entry's name, or null: no "..", no leading "/", no control
     * characters, nothing that could point outside the folder it's unpacked into.
     */
    fun clean(name: String): String? {
        val parts = name.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.size > 24 || parts.any { it == ".." || it.length > 200 || it.any { c -> c < ' ' } }) return null
        return parts.joinToString("/")
    }

    fun plan(context: Context, songs: List<Song>): Plan {
        val used = HashSet<String>()
        var total = 0L
        val list = songs.map { s ->
            var path = entryPath(s)
            var n = 2
            while (!used.add(path.lowercase())) path = path.substringBeforeLast('.') + " ($n)." + path.substringAfterLast('.').also { n++ }
            total += sizeOf(context, s.uri)
            s to path
        }
        return Plan(list, total, freeBytes())
    }

    private fun sizeOf(context: Context, uri: Uri): Long = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.coerceAtLeast(0) } ?: 0
    } catch (e: Exception) {
        0
    }

    /**
     * Writes every song in [plan], then [backup] (path in the backup folder → text), into the zip
     * at [target]. Songs aren't compressed again (they already are, so it would only be slower).
     * Returns how many songs were packed; a song that can't be read is left out. Slow: call on IO.
     */
    suspend fun pack(context: Context, plan: Plan, backup: Map<String, String>, target: Uri, onProgress: (Progress) -> Unit): Int {
        val resolver = context.contentResolver
        val raw: OutputStream = try {
            resolver.openOutputStream(target, "wt")
        } catch (e: IllegalArgumentException) {
            resolver.openOutputStream(target, "w")
        } ?: throw IOException("Couldn't write the zip")
        var packed = 0
        var bytes = 0L
        var lastReport = 0L
        val title = "Packing your songs"
        ZipOutputStream(BufferedOutputStream(raw, 256 * 1024)).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            val buf = ByteArray(256 * 1024)
            for ((i, pair) in plan.songs.withIndex()) {
                currentCoroutineContext().ensureActive()
                val (song, path) = pair
                val input = try {
                    resolver.openInputStream(song.uri)
                } catch (e: Exception) {
                    null
                } ?: continue // deleted, or the memory card is out
                input.use {
                    zip.putNextEntry(ZipEntry(path).apply { if (song.dateAdded > 0) time = song.dateAdded * 1000 })
                    while (true) {
                        val n = it.read(buf)
                        if (n < 0) break
                        zip.write(buf, 0, n)
                        bytes += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) {
                            currentCoroutineContext().ensureActive()
                            lastReport = now
                            onProgress(Progress(title, i, plan.songs.size, bytes, plan.totalBytes, song.title))
                        }
                    }
                    zip.closeEntry()
                }
                packed++
            }
            for ((path, text) in backup) {
                zip.putNextEntry(ZipEntry("$BACKUP_DIR/$path"))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        onProgress(Progress(title, plan.songs.size, plan.songs.size, bytes, plan.totalBytes, ""))
        return packed
    }

    /**
     * Puts the songs in the zip at [source] back in their folders on this phone. Songs already
     * there ([known] location keys, or a file at the same place) are skipped, so unpacking again
     * after stopping just adds the rest. Returns what happened, with the backup's text if the
     * zip has one. Slow: call on IO. On Android 9 and older this needs the storage permission.
     */
    suspend fun unpack(context: Context, source: Uri, known: Set<String>, onProgress: (Progress) -> Unit): Unpacked {
        val resolver = context.contentResolver
        val total = try {
            resolver.query(source, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L } ?: 0L
        } catch (e: Exception) {
            0L
        }
        val counted = CountingStream(resolver.openInputStream(source) ?: throw IOException("Couldn't open the zip"))
        var added = 0
        var skipped = 0
        var failed = 0
        var backupText: String? = null
        var sawZip = false
        var lastReport = 0L
        val title = "Unpacking songs"
        ZipInputStream(BufferedInputStream(counted, 256 * 1024)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                sawZip = true
                currentCoroutineContext().ensureActive()
                val path = clean(entry.name)
                when {
                    entry.isDirectory || path == null -> Unit
                    path.substringAfterLast('/').equals(BackupFile.NAME, ignoreCase = true) -> backupText = readText(zip)
                    AUDIO.containsMatchIn(path) -> {
                        val target = placeFor(path)
                        val name = target.substringAfterLast('/')
                        val dir = target.substringBeforeLast('/', "")
                        val key = Storage.locationKey("primary", dir, name)
                        if (key in known || File(storageRoot(), target).exists()) {
                            skipped++
                        } else {
                            if (freeBytes() in 0 until KEEP_FREE) throw NotEnoughSpace()
                            if (writeSong(context, dir, name, zip)) added++ else failed++
                        }
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastReport > 250) {
                    lastReport = now
                    onProgress(Progress(title, added + skipped, -1, counted.count, total, path?.substringAfterLast('/').orEmpty()))
                }
            }
        }
        if (!sawZip) throw IOException("Not a zip file")
        return Unpacked(added, skipped, failed, backupText)
    }

    /** Where a song from the zip goes: its own folder if Android allows audio there, else under Music. */
    private fun placeFor(path: String): String {
        val p = path.removePrefix(Storage.SD_CARD + "/")
        val top = p.substringBefore('/')
        val allowed = top in AUDIO_DIRS && (top != "Recordings" || Build.VERSION.SDK_INT >= 31)
        return when {
            !p.contains('/') -> "Music/$p"
            allowed || top == Environment.DIRECTORY_DOWNLOADS -> p
            else -> "Music/$p"
        }
    }

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4b" -> "audio/mp4"
            "3gp" -> "audio/3gpp"
            "webm" -> "audio/webm"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.takeIf { it.startsWith("audio/") } ?: "audio/mpeg"
        }
    }

    private suspend fun writeSong(context: Context, dir: String, name: String, input: InputStream): Boolean =
        if (Build.VERSION.SDK_INT >= 29) writeSongQ(context, dir, name, input) else writeSongLegacy(context, dir, name, input)

    @RequiresApi(29)
    private suspend fun writeSongQ(context: Context, dir: String, name: String, input: InputStream): Boolean {
        val resolver = context.contentResolver
        val downloads = dir.substringBefore('/') == Environment.DIRECTORY_DOWNLOADS
        val collection = if (downloads) MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            null
        } ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out -> copyGuarded(input, out) } ?: throw IOException()
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (ignored: Exception) {
            }
            if (e is kotlinx.coroutines.CancellationException || e is NotEnoughSpace) throw e
            if (e is IOException && freeBytes() in 0 until KEEP_FREE) throw NotEnoughSpace()
            false
        }
    }

    private suspend fun writeSongLegacy(context: Context, dir: String, name: String, input: InputStream): Boolean {
        val root = storageRoot().canonicalFile
        val file = File(File(root, dir), name)
        // Never outside shared storage, whatever the zip says.
        if (!file.canonicalPath.startsWith(root.path + File.separator)) return false
        return try {
            file.parentFile?.mkdirs()
            file.outputStream().use { out -> copyGuarded(input, out) }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            true
        } catch (e: Exception) {
            file.delete()
            if (e is kotlinx.coroutines.CancellationException || e is NotEnoughSpace) throw e
            false
        }
    }

    /**
     * Copies one song out of the zip, checking now and then that the phone isn't filling up (a
     * zip can claim a small file and unpack into a huge one) and that you haven't pressed Stop.
     */
    private suspend fun copyGuarded(input: InputStream, out: OutputStream) {
        val buf = ByteArray(256 * 1024)
        var sinceCheck = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            sinceCheck += n
            if (sinceCheck >= 32L * 1024 * 1024) {
                sinceCheck = 0
                currentCoroutineContext().ensureActive()
                if (freeBytes() in 0 until KEEP_FREE) throw NotEnoughSpace()
            }
        }
    }

    /** The backup's text, if it isn't absurdly big. */
    private fun readText(input: InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > 20 * 1024 * 1024) return null
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** Counts the bytes read, for the progress bar. */
    private class CountingStream(input: InputStream) : FilterInputStream(input) {
        @Volatile var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }
}
