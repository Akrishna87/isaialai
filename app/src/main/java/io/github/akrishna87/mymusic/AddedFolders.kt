package io.github.akrishna87.mymusic

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.File

/**
 * Folders the person added with the system folder picker. They're read directly rather than
 * through Android's media library, so they work even for folders the library skips, such as
 * folders containing a ".nomedia" file (Telegram, many downloader apps).
 */
object AddedFolders {
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /** Where the folder picker opens: the phone's Music folder. */
    val pickerStart: Uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:Music")

    fun list(context: Context): List<Uri> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
            .map { it.uri }

    fun add(context: Context, tree: Uri) {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun remove(context: Context, tree: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // already gone
        }
    }

    /** The folder's path as shown in the app, e.g. "Music/Telegram" or "SD card/Songs". */
    fun displayPath(context: Context, tree: Uri): String {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        if (tree.authority == EXTERNAL_STORAGE) {
            return Storage.displayFolder(docId.substringBefore(':'), docId.substringAfter(':', ""))
                .ifEmpty { "Phone storage" }
        }
        return try {
            context.contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        } ?: "Added folder"
    }

    class ScanResult(val songs: List<Song>, val audioFilesPerFolder: Map<Uri, Int>)

    /**
     * Walks every added folder and returns the songs in them, skipping files whose
     * [Song.locationKey] is in [alreadyKnown] (they're already in the media library).
     */
    fun scan(context: Context, trees: List<Uri>, alreadyKnown: Set<String>): ScanResult {
        val tags = TagCache(context)
        val out = ArrayList<Song>()
        val seen = HashSet<String>()
        val perFolder = HashMap<Uri, Int>()
        for (tree in trees) {
            perFolder[tree] = try {
                walk(context, tree, tags, alreadyKnown, seen, out)
            } catch (e: Exception) {
                0 // folder deleted, card removed, or access revoked
            }
        }
        tags.save()
        return ScanResult(out, perFolder)
    }

    private fun walk(
        context: Context,
        tree: Uri,
        tags: TagCache,
        alreadyKnown: Set<String>,
        seen: MutableSet<String>,
        out: MutableList<Song>,
    ): Int {
        val external = tree.authority == EXTERNAL_STORAGE
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootPath = displayPath(context, tree).let { if (it == "Phone storage") "" else it }
        val pending = mutableListOf(rootId to rootPath)
        var audioFiles = 0
        var dirs = 0
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        while (pending.isNotEmpty() && dirs < 5_000) {
            val (dirId, dirPath) = pending.removeAt(pending.lastIndex)
            dirs++
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
            context.contentResolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1).orEmpty()
                    val mime = c.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) pending += docId to (if (dirPath.isEmpty()) name else "$dirPath/$name")
                        continue
                    }
                    if (!mime.startsWith("audio/") && !AUDIO_EXTENSIONS.containsMatchIn(name)) continue
                    audioFiles++

                    val locationKey =
                        if (external) {
                            val path = docId.substringAfter(':', "")
                            Storage.locationKey(docId.substringBefore(':'), path.substringBeforeLast('/', ""), name)
                        } else {
                            "doc:$docId".lowercase()
                        }
                    if (locationKey in alreadyKnown || !seen.add(locationKey)) continue

                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                    val modified = if (c.isNull(4)) 0L else c.getLong(4)
                    val t = tags.get(context, uri, "${if (c.isNull(3)) 0 else c.getLong(3)}/$modified")
                    if (t.durationMs in 1 until 10_000) continue // a sound effect, not a song
                    out += Song(
                        id = uri.toString(),
                        title = t.title.ifBlank { name.substringBeforeLast('.') },
                        artist = t.artist.ifBlank { Song.UNKNOWN_ARTIST },
                        album = t.album,
                        albumKey = if (t.album.isNotBlank()) "tag:${t.albumArtist.lowercase()}|${t.album.lowercase()}" else "dir:$dirPath",
                        albumId = 0,
                        durationMs = t.durationMs,
                        track = t.track,
                        dateAdded = modified / 1000,
                        folder = dirPath,
                        fileName = name,
                        locationKey = locationKey,
                    )
                }
            }
        }
        return audioFiles
    }

    private val AUDIO_EXTENSIONS = Regex("\\.(mp3|m4a|m4b|aac|flac|ogg|oga|opus|wav|wma|amr|3gp|mka|webm|weba)$", RegexOption.IGNORE_CASE)
}

/** Song tags read with MediaMetadataRetriever, cached on disk so rescans are fast. */
private class TagCache(context: Context) {
    data class Tags(val title: String, val artist: String, val album: String, val albumArtist: String, val durationMs: Long, val track: Int)

    private val file = File(context.filesDir, "folder-tags.json")
    private val entries: JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }
    private val used = HashSet<String>()
    private var dirty = false

    fun get(context: Context, uri: Uri, stamp: String): Tags {
        val key = uri.toString()
        used += key
        entries.optJSONObject(key)?.let { o ->
            if (o.optString("stamp") == stamp) {
                return Tags(o.optString("t"), o.optString("a"), o.optString("al"), o.optString("aa"), o.optLong("d"), o.optInt("n"))
            }
        }
        val tags = read(context, uri)
        entries.put(
            key,
            JSONObject().put("stamp", stamp).put("t", tags.title).put("a", tags.artist).put("al", tags.album)
                .put("aa", tags.albumArtist).put("d", tags.durationMs).put("n", tags.track),
        )
        dirty = true
        return tags
    }

    private fun read(context: Context, uri: Uri): Tags {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            fun tag(key: Int) = r.extractMetadata(key)?.trim().orEmpty()
            Tags(
                title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                durationMs = tag(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0L,
                track = tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).substringBefore('/').trim().toIntOrNull() ?: 0,
            )
        } catch (e: Exception) {
            Tags("", "", "", "", 0L, 0)
        } finally {
            try { r.release() } catch (e: Exception) { /* ignore */ }
        }
    }

    fun save() {
        // Forget files that weren't seen this time (deleted or folder removed).
        val stale = entries.keys().asSequence().filter { it !in used }.toList()
        stale.forEach { entries.remove(it) }
        if (!dirty && stale.isEmpty()) return
        try {
            file.writeText(entries.toString())
        } catch (e: Exception) {
            // cache is only an optimisation
        }
    }
}
