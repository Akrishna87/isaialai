package io.github.akrishna87.mymusic

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.abs

/**
 * A song as written in a backup or playlist file: where the file is, and its tags, so it can be
 * found again later, on this phone or on another one (where the library's own ids are different).
 */
data class SongRef(val path: String, val title: String, val artist: String, val album: String = "", val durationMs: Long = 0) {
    fun toJson(): JSONObject =
        JSONObject().put("path", path).put("title", title).put("artist", artist).put("album", album).put("durationMs", durationMs)

    companion object {
        fun of(song: Song) = SongRef(SongPaths.of(song), song.title, song.artist, song.album, song.durationMs)

        fun fromJson(o: JSONObject) =
            SongRef(o.optString("path"), o.optString("title"), o.optString("artist"), o.optString("album"), o.optLong("durationMs"))
    }
}

object SongPaths {
    private const val PHONE = "/storage/emulated/0"
    private val STORAGE_PREFIX = Regex(
        "^(/storage/emulated/\\d+/|/storage/self/primary/|/mnt/sdcard/|/sdcard/|/storage/sdcard\\d*/|/storage/[^/]+/|/mnt/media_rw/[^/]+/|[a-z]:/)",
    )

    /**
     * The file's full path as other music apps write it in playlists, e.g.
     * "/storage/emulated/0/Music/Hindi/song.mp3" (or "/storage/1234-ABCD/…" on a memory card).
     */
    fun of(song: Song): String {
        val volume = song.locationKey.substringBefore(':', "primary")
        val card = volume != "primary" && volume != "doc"
        val dir = (if (card) song.folder.removePrefix(Storage.SD_CARD) else song.folder).trim('/')
        val root = when {
            volume == "doc" -> ""
            card -> "/storage/" + volume.uppercase()
            else -> PHONE
        }
        return "/" + listOf(root.trim('/'), dir, song.fileName).filter { it.isNotEmpty() }.joinToString("/")
    }

    /**
     * The part of any style of path after the storage it's on, lower-case: "music/hindi/song.mp3"
     * from "/storage/emulated/0/Music/Hindi/song.mp3", "/sdcard/…", "file:///…" or "C:\Music\…".
     */
    fun relative(path: String): String {
        var p = path.trim().replace('\\', '/')
        if (p.startsWith("file://", ignoreCase = true)) p = Uri.decode(p.substring(7))
        p = p.lowercase()
        p = STORAGE_PREFIX.replaceFirst(p, "")
        while (p.startsWith("../") || p.startsWith("./")) p = p.substringAfter('/')
        return p.trim('/')
    }
}

/** Finds songs in this phone's library from a [SongRef] or a playlist file's line. */
class SongMatcher(songs: List<Song>) {
    private val byRelative = HashMap<String, MutableList<Song>>()
    private val byFileName = HashMap<String, MutableList<Song>>()
    private val byTitle = HashMap<String, MutableList<Song>>()

    init {
        for (s in songs) {
            byRelative.getOrPut(s.locationKey.substringAfter(':')) { ArrayList() } += s
            byFileName.getOrPut(s.fileName.lowercase()) { ArrayList() } += s
            byTitle.getOrPut(norm(s.title)) { ArrayList() } += s
        }
    }

    fun find(ref: SongRef): Song? = find(ref.path, ref.title, ref.artist, ref.durationMs)

    /** [durationMs] 0 means unknown. Returns null if the song isn't on this phone. */
    fun find(path: String?, title: String?, artist: String?, durationMs: Long): Song? {
        if (!path.isNullOrBlank()) {
            val rel = SongPaths.relative(path)
            byRelative[rel]?.let { return closest(it, durationMs) }
            // Same file in another folder (or a path written by another app): the copy whose
            // folders match the most, counting from the file name back.
            byFileName[rel.substringAfterLast('/')]?.let { list ->
                val best = list.maxWith(compareBy<Song> { sameEnding(it.locationKey.substringAfter(':'), rel) }.thenBy { -gap(it, durationMs) })
                val folderMatches = sameEnding(best.locationKey.substringAfter(':'), rel) >= 2
                if (folderMatches || durationMs <= 0 || gap(best, durationMs) <= 5_000) return best
            }
        }
        if (title.isNullOrBlank()) return null
        val candidates = byTitle[norm(title)]
            ?.filter { durationMs <= 0 || it.durationMs <= 0 || gap(it, durationMs) <= 5_000 }
            .orEmpty()
        if (candidates.isEmpty()) return null
        val wantArtist = artist?.takeIf { it.isNotBlank() && it != Song.UNKNOWN_ARTIST }?.let(::norm)
        val sameArtist = if (wantArtist == null) candidates else candidates.filter { norm(it.artist) == wantArtist }
        return when {
            sameArtist.isNotEmpty() -> closest(sameArtist, durationMs)
            candidates.size == 1 -> candidates[0] // the artist tag differs, but it's the only song with that name
            else -> null
        }
    }

    private fun closest(list: List<Song>, durationMs: Long): Song = list.minBy { gap(it, durationMs) }

    private fun gap(s: Song, durationMs: Long): Long = if (durationMs <= 0 || s.durationMs <= 0) 0 else abs(s.durationMs - durationMs)

    /** How many path parts two paths share at the end ("hindi/song.mp3" = 2). */
    private fun sameEnding(a: String, b: String): Int {
        val x = a.split('/').reversed()
        val y = b.split('/').reversed()
        var n = 0
        while (n < x.size && n < y.size && x[n] == y[n]) n++
        return n
    }

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }
}

/**
 * Playlist files (.m3u / .m3u8): a plain list of song files that almost every music app can read
 * and write, with "#EXTINF" lines giving each song's length and "Artist - Title".
 */
object M3u {
    class Entry(val path: String, val title: String?, val artist: String?, val durationMs: Long)
    class Parsed(val name: String?, val entries: List<Entry>)

    fun write(name: String, songs: List<SongRef>): String = buildString {
        append("#EXTM3U\n")
        append("#PLAYLIST:").append(oneLine(name)).append('\n')
        for (s in songs) {
            val secs = if (s.durationMs > 0) (s.durationMs + 500) / 1000 else -1
            val label = if (s.artist.isNotBlank() && s.artist != Song.UNKNOWN_ARTIST) "${s.artist} - ${s.title}" else s.title
            append("#EXTINF:").append(secs).append(',').append(oneLine(label)).append('\n')
            append(s.path).append('\n')
        }
    }

    /** Returns null if [text] doesn't look like a playlist file. */
    fun parse(text: String): Parsed? {
        var name: String? = null
        var pending: Entry? = null
        val entries = ArrayList<Entry>()
        var sawHeader = false
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", ignoreCase = true) -> sawHeader = true
                line.startsWith("#PLAYLIST:", ignoreCase = true) -> name = line.substringAfter(':').trim().ifEmpty { null }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val info = line.substringAfter(':')
                    // "#EXTINF:215 tvg-id=\"x\",Artist - Title": length, optional attributes, then the label.
                    val secs = info.substringBefore(',').trim().substringBefore(' ').toLongOrNull() ?: -1
                    val label = if (',' in info) info.substringAfter(',').trim() else ""
                    val artist = if (" - " in label) label.substringBefore(" - ").trim() else null
                    val title = if (" - " in label) label.substringAfter(" - ").trim() else label.ifEmpty { null }
                    pending = Entry("", title, artist, if (secs > 0) secs * 1000 else 0)
                }
                line.startsWith("#") -> Unit
                // Internet streams and app-only links can't be songs on the phone.
                LINK.containsMatchIn(line) && !line.startsWith("file://", ignoreCase = true) -> pending = null
                else -> {
                    val p = pending
                    entries += Entry(line, p?.title, p?.artist, p?.durationMs ?: 0)
                    pending = null
                }
            }
        }
        if (!sawHeader && entries.none { AUDIO.containsMatchIn(it.path) }) return null
        return Parsed(name, entries)
    }

    private val LINK = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)
    private val AUDIO = Regex("\\.(mp3|m4a|aac|flac|ogg|opus|wav|wma|amr|3gp|mka|mp4)$", RegexOption.IGNORE_CASE)

    private fun oneLine(s: String) = s.replace('\n', ' ').replace('\r', ' ').trim()

    /** A file name for a playlist called [name]: no characters that phones or computers refuse. */
    fun fileName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").replace(Regex("\\s+"), " ").trim().trimStart('.')
            .take(80).ifEmpty { "Playlist" } + ".m3u"
}

/**
 * Everything Isaialai keeps that's worth moving to a new phone, as one readable JSON file:
 * playlists, liked songs, play counts and history, song edits and settings. Songs are listed once
 * (with their path and tags, see [SongRef]) and referred to by position in that list.
 */
object BackupFile {
    const val NAME = "Isaialai backup.json"
    private const val FORMAT = 1

    class Play(val song: SongRef, val count: Int, val lastPlayed: Long)

    class Contents(
        val createdMs: Long,
        val playlists: List<Pair<String, List<SongRef>>>,
        val liked: List<SongRef>,
        val history: List<SongRef>,
        val plays: List<Play>,
        val edits: List<Pair<SongRef, SongEdits.Edit>>,
        val settings: JSONObject?,
    ) {
        val isEmpty: Boolean get() = playlists.isEmpty() && liked.isEmpty() && plays.isEmpty() && edits.isEmpty()
    }

    fun write(c: Contents): String {
        val songs = JSONArray()
        val index = HashMap<SongRef, Int>()
        fun ref(s: SongRef): Int = index.getOrPut(s) { songs.put(s.toJson()); songs.length() - 1 }
        fun refs(list: List<SongRef>) = JSONArray(list.map(::ref))

        val o = JSONObject()
            .put("app", "Isaialai")
            .put("format", FORMAT)
            .put("created", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US).format(java.util.Date(c.createdMs)))
            .put("createdMs", c.createdMs)
        o.put("playlists", JSONArray(c.playlists.map { (name, list) -> JSONObject().put("name", name).put("songs", refs(list)) }))
        o.put("liked", refs(c.liked))
        o.put("history", refs(c.history))
        o.put("plays", JSONArray(c.plays.map { JSONObject().put("song", ref(it.song)).put("count", it.count).put("last", it.lastPlayed) }))
        o.put(
            "edits",
            JSONArray(
                c.edits.map { (s, e) ->
                    JSONObject().put("song", ref(s)).put("title", e.title).put("artist", e.artist).put("album", e.album)
                        .put("composer", e.composer).put("year", e.year)
                },
            ),
        )
        c.settings?.let { o.put("settings", it) }
        o.put("songs", songs) // last, so the readable parts come first
        return o.toString(1)
    }

    /** Returns null if [text] isn't an Isaialai backup. */
    fun read(text: String): Contents? = try {
        val o = JSONObject(text.removePrefix("\uFEFF"))
        if (o.optString("app") != "Isaialai") {
            null
        } else {
            val songsArr = o.getJSONArray("songs")
            val songs = List(songsArr.length()) { SongRef.fromJson(songsArr.getJSONObject(it)) }
            fun at(i: Int) = songs.getOrNull(i)
            fun list(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.optInt(it, -1) }.mapNotNull(::at)
            fun objects(name: String): List<JSONObject> = o.optJSONArray(name)?.let { a -> List(a.length()) { a.optJSONObject(it) }.filterNotNull() }.orEmpty()

            Contents(
                createdMs = o.optLong("createdMs"),
                playlists = objects("playlists").map { it.optString("name").ifBlank { "Playlist" } to list(it.optJSONArray("songs")) },
                liked = list(o.optJSONArray("liked")),
                history = list(o.optJSONArray("history")),
                plays = objects("plays").mapNotNull { p -> at(p.optInt("song", -1))?.let { Play(it, p.optInt("count"), p.optLong("last")) } },
                edits = objects("edits").mapNotNull { e ->
                    at(e.optInt("song", -1))?.let {
                        it to SongEdits.Edit(e.optString("title"), e.optString("artist"), e.optString("album"), e.optString("composer"), e.optInt("year"))
                    }
                },
                settings = o.optJSONObject("settings"),
            )
        }
    } catch (e: Exception) {
        null
    }
}

/** Your choices that go in a backup: theme and sound settings (nothing about this phone itself). */
object BackupSettings {
    /** Theme, lock-screen player and song sort, kept in the "ui" preferences. */
    val UI_KEYS = listOf("theme", "wallpaperColors", "lockScreenPlayer", "sort")

    fun snapshot(context: android.content.Context): JSONObject {
        fun pick(prefs: android.content.SharedPreferences, keys: List<String>) = JSONObject().apply {
            val all = prefs.all
            keys.forEach { k -> all[k]?.let { put(k, it) } }
        }
        return JSONObject()
            .put("ui", pick(context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE), UI_KEYS))
            .put("effects", pick(Effects.prefs(context), Effects.BACKUP_KEYS))
    }

    /** Applies settings from a backup; values of the wrong kind are skipped. */
    fun apply(context: android.content.Context, o: JSONObject) {
        fun put(prefs: android.content.SharedPreferences, from: JSONObject?, keys: List<String>) {
            if (from == null) return
            val e = prefs.edit()
            for (k in keys) {
                if (!from.has(k)) continue
                when (val v = from.get(k)) {
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, limit(k, v))
                    is Long -> e.putInt(k, limit(k, v.toInt()))
                    is String -> e.putString(k, v.take(500))
                }
            }
            e.apply()
        }
        put(context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE), o.optJSONObject("ui"), UI_KEYS)
        put(Effects.prefs(context), o.optJSONObject("effects"), Effects.BACKUP_KEYS)
    }

    private fun limit(key: String, v: Int): Int = when (key) {
        Effects.KEY_CROSSFADE -> v.coerceIn(0, 12)
        Effects.KEY_BOOST -> v.coerceIn(100, Effects.MAX_BOOST)
        "bass" -> v.coerceIn(0, 1000)
        "sort" -> v.coerceIn(0, SongSort.entries.size - 1)
        else -> v
    }
}
