package io.github.akrishna87.mymusic

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.Collator

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val track: Int,
    val dateAdded: Long,
    val folder: String,
    val fileName: String,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val albumArtUri: Uri get() = ContentUris.withAppendedId(ALBUM_ART_URI, albumId)
    val subtitle: String get() = if (album.isNotEmpty()) "$artist · $album" else artist

    fun matches(query: String): Boolean =
        query.isBlank() || listOf(title, artist, album, fileName).any { it.contains(query.trim(), ignoreCase = true) }

    companion object {
        val ALBUM_ART_URI: Uri = Uri.parse("content://media/external/audio/albumart")
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val UNKNOWN_ALBUM = "Unknown album"
    }
}

data class AlbumGroup(val id: Long, val name: String, val artist: String, val songs: List<Song>)
data class ArtistGroup(val name: String, val songs: List<Song>)
data class FolderGroup(val path: String, val songs: List<Song>) {
    val name: String get() = path.substringAfterLast('/').ifEmpty { "Phone storage" }
}

enum class SongSort(val label: String) { TITLE("A–Z"), ARTIST("Artist"), NEWEST("Newest") }

/** Reads every song Android's media scanner knows about, straight from the phone's storage. */
object MusicRepository {

    fun loadSongs(context: Context): List<Song> {
        val folderColumn =
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH
            else @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DISPLAY_NAME,
            folderColumn,
        )
        val songs = ArrayList<Song>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null,
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iDuration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val iFolder = c.getColumnIndexOrThrow(folderColumn)
            while (c.moveToNext()) {
                val fileName = c.getString(iName).orEmpty()
                val rawTitle = c.getString(iTitle)
                val rawArtist = c.getString(iArtist)
                val rawAlbum = c.getString(iAlbum).orEmpty()
                val rawFolder = c.getString(iFolder).orEmpty()
                songs += Song(
                    id = c.getLong(iId),
                    title = if (rawTitle.isNullOrBlank()) fileName.substringBeforeLast('.') else rawTitle,
                    artist = if (rawArtist.isNullOrBlank() || rawArtist == "<unknown>") Song.UNKNOWN_ARTIST else rawArtist,
                    album = if (rawAlbum == "<unknown>") "" else rawAlbum,
                    albumId = c.getLong(iAlbumId),
                    durationMs = c.getLong(iDuration),
                    track = c.getInt(iTrack) % 1000, // stored as disc * 1000 + track
                    dateAdded = c.getLong(iAdded),
                    folder = if (Build.VERSION.SDK_INT >= 29) rawFolder.trim('/')
                    else File(rawFolder).parent.orEmpty().removePrefix("/storage/emulated/0").trim('/'),
                    fileName = fileName,
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
        songs.groupBy { it.albumId }.map { (id, list) ->
            val artists = list.map { it.artist }.distinct()
            AlbumGroup(
                id = id,
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

    fun folders(songs: List<Song>): List<FolderGroup> =
        songs.groupBy { it.folder }.map { (path, list) ->
            FolderGroup(path, list.sortedWith(compareBy(collator) { it.fileName }))
        }.sortedWith(compareBy(collator) { it.path })
}
