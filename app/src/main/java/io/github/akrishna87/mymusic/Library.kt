package io.github.akrishna87.mymusic

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.Collator

/**
 * A song, from Android's media library or from a folder the person added.
 * [id] is the MediaStore row id for library songs, or the document URI for added-folder songs.
 */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumKey: String,
    val albumId: Long,
    val durationMs: Long,
    val track: Int,
    val dateAdded: Long,
    val folder: String,
    val fileName: String,
    /** Volume + path + file name, used to spot the same file reached two different ways. */
    val locationKey: String,
    /** Music director(s), as tagged; often several names separated by commas. */
    val composer: String = "",
    val year: Int = 0,
) {
    val uri: Uri get() = uriForId(id)
    val albumArtUri: Uri get() = ContentUris.withAppendedId(ALBUM_ART_URI, albumId)
    val subtitle: String get() = if (album.isNotEmpty()) "$artist · $album" else artist

    fun matches(query: String): Boolean =
        query.isBlank() || listOf(title, artist, album, composer, fileName, folder).any { it.contains(query.trim(), ignoreCase = true) }

    companion object {
        val ALBUM_ART_URI: Uri = Uri.parse("content://media/external/audio/albumart")
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val UNKNOWN_ALBUM = "Unknown album"

        fun uriForId(id: String): Uri =
            id.toLongOrNull()?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
                ?: Uri.parse(id)
    }
}

data class AlbumGroup(val key: String, val name: String, val artist: String, val songs: List<Song>)
data class ArtistGroup(val name: String, val songs: List<Song>)
data class ComposerGroup(val name: String, val songs: List<Song>, val albums: List<AlbumGroup>)

/** Copies of what looks like the same song: same title and artist, about the same length. */
data class DuplicateGroup(val title: String, val artist: String, val copies: List<Song>)

/** One level of the folder tree: the folders inside [path] and the songs directly in it. */
data class FolderLevel(
    val path: String,
    val subfolders: List<FolderEntry>,
    val songs: List<Song>,
    val allSongs: List<Song>,
)

data class FolderEntry(val path: String, val name: String, val songCount: Int)

enum class SongSort(val label: String) { TITLE("A–Z"), ARTIST("Artist"), NEWEST("Newest") }

object Storage {
    const val SD_CARD = "SD card"

    private fun isPrimary(volume: String) =
        volume.isEmpty() || volume.equals("primary", true) || volume.equals("external_primary", true) || volume.equals("external", true)

    /** Folder path as shown in the app: "Music/Hindi" on the phone, "SD card/Music" on a memory card. */
    fun displayFolder(volume: String, relativeDir: String): String =
        listOf(if (isPrimary(volume)) "" else SD_CARD, relativeDir.trim('/')).filter { it.isNotEmpty() }.joinToString("/")

    fun locationKey(volume: String, relativeDir: String, fileName: String): String {
        val v = if (isPrimary(volume)) "primary" else volume.lowercase()
        val dir = relativeDir.trim('/')
        return "$v:${if (dir.isEmpty()) "" else "$dir/"}$fileName".lowercase()
    }

    /** Splits "/storage/emulated/0/Music/a.mp3" or "/storage/1234-ABCD/Music/a.mp3" into volume + folder. */
    fun splitLegacyPath(path: String): Pair<String, String> {
        val parent = File(path).parent.orEmpty()
        return when {
            parent.startsWith("/storage/emulated/") ->
                "primary" to parent.removePrefix("/storage/emulated/").substringAfter('/', "")
            parent.startsWith("/storage/") -> {
                val rest = parent.removePrefix("/storage/")
                rest.substringBefore('/') to rest.substringAfter('/', "")
            }
            else -> "primary" to parent.trim('/')
        }
    }
}

/** Reads every song Android's media scanner knows about, straight from the phone's storage. */
object MusicRepository {

    /** Shorter than this is a sound effect or notification tone, not a song. */
    private const val MIN_DURATION_MS = 10_000L

    @Suppress("DEPRECATION") // MediaStore DATA is the only folder info before Android 10
    fun loadSongs(context: Context): List<Song> {
        val modern = Build.VERSION.SDK_INT >= 29
        val columns = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.IS_RINGTONE,
            MediaStore.Audio.Media.IS_NOTIFICATION,
            MediaStore.Audio.Media.IS_ALARM,
            MediaStore.Audio.Media.COMPOSER,
            MediaStore.Audio.Media.YEAR,
        )
        if (modern) {
            columns += MediaStore.Audio.Media.RELATIVE_PATH
            columns += MediaStore.Audio.Media.VOLUME_NAME
        } else {
            columns += MediaStore.Audio.Media.DATA
        }
        if (Build.VERSION.SDK_INT >= 31) columns += MediaStore.Audio.Media.IS_RECORDING

        val songs = ArrayList<Song>()
        // No IS_MUSIC filter: Android marks plenty of real songs (Podcasts, Audiobooks, some
        // downloads) as "not music". Ringtones, alarms, recordings and short clips are dropped below.
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            columns.toTypedArray(),
            null,
            null,
            null,
        )?.use { c ->
            fun col(name: String) = c.getColumnIndexOrThrow(name)
            val iId = col(MediaStore.Audio.Media._ID)
            val iTitle = col(MediaStore.Audio.Media.TITLE)
            val iArtist = col(MediaStore.Audio.Media.ARTIST)
            val iAlbum = col(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = col(MediaStore.Audio.Media.ALBUM_ID)
            val iDuration = col(MediaStore.Audio.Media.DURATION)
            val iTrack = col(MediaStore.Audio.Media.TRACK)
            val iAdded = col(MediaStore.Audio.Media.DATE_ADDED)
            val iName = col(MediaStore.Audio.Media.DISPLAY_NAME)
            val iRingtone = col(MediaStore.Audio.Media.IS_RINGTONE)
            val iNotification = col(MediaStore.Audio.Media.IS_NOTIFICATION)
            val iAlarm = col(MediaStore.Audio.Media.IS_ALARM)
            val iComposer = col(MediaStore.Audio.Media.COMPOSER)
            val iYear = col(MediaStore.Audio.Media.YEAR)
            val iRecording = if (Build.VERSION.SDK_INT >= 31) col(MediaStore.Audio.Media.IS_RECORDING) else -1
            val iRelative = if (modern) col(MediaStore.Audio.Media.RELATIVE_PATH) else -1
            val iVolume = if (modern) col(MediaStore.Audio.Media.VOLUME_NAME) else -1
            val iData = if (modern) -1 else col(MediaStore.Audio.Media.DATA)

            fun flag(i: Int) = i >= 0 && !c.isNull(i) && c.getInt(i) != 0

            while (c.moveToNext()) {
                if (flag(iRingtone) || flag(iNotification) || flag(iAlarm) || flag(iRecording)) continue
                val duration = if (c.isNull(iDuration)) 0L else c.getLong(iDuration)
                if (duration in 1 until MIN_DURATION_MS) continue

                val fileName = c.getString(iName).orEmpty()
                val (volume, relativeDir) =
                    if (modern) c.getString(iVolume).orEmpty() to c.getString(iRelative).orEmpty()
                    else Storage.splitLegacyPath(c.getString(iData).orEmpty())
                val rawTitle = c.getString(iTitle)
                val rawArtist = c.getString(iArtist)
                val rawAlbum = c.getString(iAlbum).orEmpty()
                val albumId = c.getLong(iAlbumId)
                songs += Song(
                    id = c.getLong(iId).toString(),
                    title = if (rawTitle.isNullOrBlank()) fileName.substringBeforeLast('.') else rawTitle,
                    artist = if (rawArtist.isNullOrBlank() || rawArtist == "<unknown>") Song.UNKNOWN_ARTIST else rawArtist,
                    album = if (rawAlbum == "<unknown>") "" else rawAlbum,
                    albumKey = "ms:$albumId",
                    albumId = albumId,
                    durationMs = duration,
                    track = c.getInt(iTrack) % 1000, // stored as disc * 1000 + track
                    dateAdded = c.getLong(iAdded),
                    folder = Storage.displayFolder(volume, relativeDir),
                    fileName = fileName,
                    locationKey = Storage.locationKey(volume, relativeDir, fileName),
                    composer = c.getString(iComposer)?.takeIf { it != "<unknown>" }?.trim().orEmpty(),
                    year = if (c.isNull(iYear)) 0 else c.getInt(iYear),
                )
            }
        }
        return songs
    }
}

object LibraryGrouping {
    val collator: Collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    private val albumOrder: Comparator<Song> =
        compareBy<Song> { if (it.track > 0) it.track else Int.MAX_VALUE }.thenBy(collator) { it.title }

    fun sort(songs: List<Song>, sort: SongSort): List<Song> = when (sort) {
        SongSort.TITLE -> songs.sortedWith(compareBy(collator) { it.title })
        SongSort.ARTIST -> songs.sortedWith(compareBy<Song, String>(collator) { it.artist }.thenBy(collator) { it.title })
        SongSort.NEWEST -> songs.sortedByDescending { it.dateAdded }
    }

    fun albums(songs: List<Song>): List<AlbumGroup> =
        songs.groupBy { it.albumKey }.map { (key, list) ->
            val artists = list.map { it.artist }.distinct()
            AlbumGroup(
                key = key,
                name = list.first().album.ifEmpty { Song.UNKNOWN_ALBUM },
                artist = if (artists.size == 1) artists[0] else "Various artists",
                songs = list.sortedWith(albumOrder),
            )
        }.sortedWith(compareBy(collator) { it.name })

    fun artists(songs: List<Song>): List<ArtistGroup> =
        songs.groupBy { it.artist.lowercase() }.map { (_, list) ->
            ArtistGroup(
                name = list.first().artist,
                songs = list.sortedWith(compareBy<Song, String>(collator) { it.album }.then(albumOrder)),
            )
        }.sortedWith(compareBy(collator) { it.name })

    /** Splits a composer tag like "A.R. Rahman, Ilaiyaraaja" into its names. */
    fun composerNames(tag: String): List<String> =
        tag.split(',', ';', '/', '|').map { it.trim() }.filter { it.isNotEmpty() && !it.equals("<unknown>", true) }

    /** Music directors, each with their songs and their albums (usually movies), newest first. */
    fun composers(songs: List<Song>): List<ComposerGroup> {
        val byName = LinkedHashMap<String, MutableList<Song>>()
        val shown = HashMap<String, String>()
        for (s in songs) for (name in composerNames(s.composer)) {
            val k = name.lowercase()
            shown.putIfAbsent(k, name)
            byName.getOrPut(k) { ArrayList() } += s
        }
        return byName.map { (k, list) ->
            val albums = albums(list).sortedWith(
                compareByDescending<AlbumGroup> { a -> a.songs.maxOf { it.year } }.thenBy(collator) { it.name },
            )
            ComposerGroup(shown.getValue(k), albums.flatMap { it.songs }, albums)
        }.sortedWith(compareBy(collator) { it.name })
    }

    private fun simplify(text: String): String =
        text.lowercase().replace(Regex("[\\(\\[].*?[\\)\\]]"), "").filter { it.isLetterOrDigit() }

    /**
     * Songs that are on the phone more than once (e.g. saved from both WhatsApp and Telegram):
     * same title and artist, and lengths within 3 seconds of each other.
     */
    fun duplicates(songs: List<Song>): List<DuplicateGroup> {
        val out = ArrayList<DuplicateGroup>()
        songs.groupBy { simplify(it.title) + "|" + simplify(it.artist) }.forEach { (key, list) ->
            if (list.size < 2 || key.startsWith("|")) return@forEach
            var cluster = ArrayList<Song>()
            fun flush() {
                if (cluster.size > 1) out += DuplicateGroup(cluster[0].title, cluster[0].artist, cluster.sortedWith(compareBy(collator) { it.folder }))
            }
            for (s in list.sortedBy { it.durationMs }) {
                if (cluster.isNotEmpty() && s.durationMs - cluster.last().durationMs > 3_000) {
                    flush()
                    cluster = ArrayList()
                }
                cluster += s
            }
            flush()
        }
        return out.sortedWith(compareBy(collator) { it.title })
    }

    /** Folders inside [path] (with song counts that include their subfolders) and the songs directly in it. */
    fun folderLevel(songs: List<Song>, path: String): FolderLevel {
        val prefix = if (path.isEmpty()) "" else "$path/"
        val inside = (if (path.isEmpty()) songs else songs.filter { it.folder == path || it.folder.startsWith(prefix) })
            .sortedWith(compareBy<Song, String>(collator) { it.folder }.thenBy(collator) { it.fileName })
        val direct = inside.filter { it.folder == path }.sortedWith(compareBy(collator) { it.fileName })
        val subfolders = inside.filter { it.folder != path }
            .groupBy { it.folder.removePrefix(prefix).substringBefore('/') }
            .map { (name, list) -> FolderEntry(prefix + name, name, list.size) }
            // The SD card goes after the phone's own folders.
            .sortedWith(compareBy<FolderEntry> { path.isEmpty() && it.name == Storage.SD_CARD }.thenBy(collator) { it.name })
        return FolderLevel(path, subfolders, direct, inside)
    }

    /** Every folder (at any depth) whose path contains [query], for searching the Folders tab. */
    fun searchFolders(songs: List<Song>, query: String): List<FolderEntry> {
        val counts = HashMap<String, Int>()
        for (s in songs) {
            var p = s.folder
            while (p.isNotEmpty()) {
                counts[p] = (counts[p] ?: 0) + 1
                p = p.substringBeforeLast('/', "")
            }
        }
        return counts.filterKeys { it.contains(query.trim(), ignoreCase = true) }
            .map { (p, n) -> FolderEntry(p, p.substringAfterLast('/'), n) }
            .sortedWith(compareBy(collator) { it.path })
    }
}
