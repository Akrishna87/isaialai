package io.github.akrishna87.mymusic

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.File

/**
 * What restoring a backup (or importing playlist files) would add, with each song already found
 * in this phone's library. Songs that aren't on the phone are counted in [missing].
 */
class RestorePlan(
    /** Where it came from, as shown to you: a folder path or a file name. */
    val source: String,
    /** When the backup was made (0 for playlist files). */
    val backedUpAt: Long,
    val playlists: List<Pair<String, List<String>>>,
    val liked: List<String>,
    val history: List<String>,
    /** Song id, play count, last played. */
    val plays: List<Triple<String, Int, Long>>,
    val edits: List<Pair<String, SongEdits.Edit>>,
    val settings: JSONObject?,
    /** Songs in the playlists and liked songs that were found / aren't on this phone. */
    val found: Int,
    val missing: Int,
    /** The backup folder, to keep backing up into after restoring (so there's one backup, not two). */
    val adoptFolder: Uri?,
) {
    val songsInPlaylists: Int get() = playlists.sumOf { it.second.size }

    companion object {
        private val PLAYLIST_FILE = Regex("\\.m3u8?$", RegexOption.IGNORE_CASE)

        /**
         * Reads the folder you picked: an Isaialai backup folder (the newest backup file in it),
         * or any folder of .m3u playlist files, e.g. from another music app. Picking the folder
         * above the Isaialai folder (like Download) works too.
         */
        fun fromFolder(context: Context, tree: Uri, library: List<Song>): RestorePlan? {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            var docs = BackupStorage.list(context, tree, rootId)
            var where = AddedFolders.displayPath(context, tree)
            if (docs.none { it.name.endsWith(".json", true) || PLAYLIST_FILE.containsMatchIn(it.name) }) {
                docs.firstOrNull { it.isDir && it.name.equals(BackupStorage.FOLDER, ignoreCase = true) }?.let { sub ->
                    docs = BackupStorage.list(context, tree, DocumentsContract.getDocumentId(sub.uri))
                    where = "$where/${sub.name}"
                }
            }
            val backups = docs.filter { !it.isDir && it.name.endsWith(".json", ignoreCase = true) }
                .sortedWith(compareByDescending<BackupStorage.Doc> { it.name == BackupFile.NAME }.thenByDescending { it.modified })
            for (doc in backups) {
                val contents = BackupStorage.readText(context, doc.uri)?.let(BackupFile::read) ?: continue
                // Only the folder you picked can be written to; a backup found one level down is read-only.
                val adopt = if (where == AddedFolders.displayPath(context, tree)) tree else null
                return fromBackup(contents, library, where, adopt)
            }
            val playlistDocs = docs.filter { !it.isDir && PLAYLIST_FILE.containsMatchIn(it.name) } +
                docs.filter { it.isDir && it.name.equals("Playlists", ignoreCase = true) }.flatMap { dir ->
                    BackupStorage.list(context, tree, DocumentsContract.getDocumentId(dir.uri)).filter { !it.isDir && PLAYLIST_FILE.containsMatchIn(it.name) }
                }
            val parsed = playlistDocs.mapNotNull { doc ->
                BackupStorage.readText(context, doc.uri)?.let(M3u::parse)?.let { (it.name ?: doc.name.replace(PLAYLIST_FILE, "")) to it }
            }
            return if (parsed.isEmpty()) null else fromPlaylistFiles(parsed, library, where)
        }

        /** Reads one picked file: an Isaialai backup, or a .m3u playlist. */
        fun fromFile(context: Context, uri: Uri, library: List<Song>): RestorePlan? {
            val text = BackupStorage.readText(context, uri) ?: return null
            val name = BackupStorage.displayName(context, uri) ?: "the file"
            BackupFile.read(text)?.let { return fromBackup(it, library, name, adopt = null) }
            val parsed = M3u.parse(text) ?: return null
            return fromPlaylistFiles(listOf((parsed.name ?: name.replace(PLAYLIST_FILE, "")) to parsed), library, name)
        }

        private fun fromBackup(c: BackupFile.Contents, library: List<Song>, source: String, adopt: Uri?): RestorePlan {
            val matcher = SongMatcher(library)
            val cache = HashMap<SongRef, String?>()
            fun id(r: SongRef): String? = cache.getOrPut(r) { matcher.find(r)?.id }
            val core = (c.playlists.flatMap { it.second } + c.liked).distinct()
            val found = core.count { id(it) != null }
            return RestorePlan(
                source = source,
                backedUpAt = c.createdMs,
                playlists = c.playlists.map { (name, refs) -> name to refs.mapNotNull(::id).distinct() },
                liked = c.liked.mapNotNull(::id).distinct(),
                history = c.history.mapNotNull(::id).distinct(),
                plays = c.plays.mapNotNull { p -> id(p.song)?.let { Triple(it, p.count.coerceAtLeast(0), p.lastPlayed) } },
                edits = c.edits.mapNotNull { (r, e) -> id(r)?.let { it to e } },
                settings = c.settings,
                found = found,
                missing = core.size - found,
                adoptFolder = adopt,
            )
        }

        private fun fromPlaylistFiles(files: List<Pair<String, M3u.Parsed>>, library: List<Song>, source: String): RestorePlan {
            val matcher = SongMatcher(library)
            var found = 0
            var missing = 0
            val lists = files.map { (name, parsed) ->
                name to parsed.entries.mapNotNull { e ->
                    // A label that isn't "Artist - Title" may be a title with " - " in it.
                    (
                        matcher.find(e.path, e.title, e.artist, e.durationMs)
                            ?: e.artist?.let { a -> matcher.find(null, "$a - ${e.title}", null, e.durationMs) }
                        )?.id.also { if (it == null) missing++ else found++ }
                }.distinct()
            }
            return RestorePlan(source, 0, lists, emptyList(), emptyList(), emptyList(), emptyList(), null, found, missing, adoptFolder = null)
        }
    }
}

/**
 * How each song in your playlists was last described ([SongRef]), kept in the app, so a song
 * that's missing for a while (memory card taken out) stays in the backup instead of dropping out.
 */
object RefCache {
    private fun file(context: Context) = File(context.filesDir, "backup-songs.json")

    fun load(context: Context): MutableMap<String, SongRef> = try {
        val o = JSONObject(file(context).readText())
        o.keys().asSequence().associateWithTo(HashMap()) { SongRef.fromJson(o.getJSONObject(it)) }
    } catch (e: Exception) {
        HashMap()
    }

    fun save(context: Context, refs: Map<String, SongRef>) {
        val o = JSONObject()
        refs.forEach { (id, r) -> o.put(id, r.toJson()) }
        val f = file(context)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(f)) {
            f.writeText(o.toString())
            tmp.delete()
        }
    }
}
