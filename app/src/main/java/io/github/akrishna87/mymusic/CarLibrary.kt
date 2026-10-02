package io.github.akrishna87.mymusic

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray

/**
 * Your library as Android Auto shows it: folders to browse (Recently played, Liked songs,
 * Playlists, Albums & movies, Artists, Music directors, All songs) and songs to play.
 * Read from the phone when the car asks, and kept for a minute.
 */
class CarLibrary(private val context: Context) {

    companion object {
        const val ROOT = "root"
        private const val RECENT = "recent"
        private const val LIKED = "liked"
        private const val PLAYLISTS = "playlists"
        private const val ALBUMS = "albums"
        private const val ARTISTS = "artists"
        private const val COMPOSERS = "composers"
        private const val ALL = "all"
        private const val CACHE_MS = 60_000L
    }

    private class Snapshot(val songs: List<Song>, val byId: Map<String, Song>, val at: Long)

    private var snapshot: Snapshot? = null

    /** The most recently browsed song lists, so playing one song from a list queues the whole list. */
    private val recentLists = ArrayDeque<List<Song>>()

    @Synchronized
    private fun library(): Snapshot {
        snapshot?.let { if (System.currentTimeMillis() - it.at < CACHE_MS) return it }
        val phone = try { MusicRepository.loadSongs(context) } catch (e: Exception) { emptyList() }
        val known = phone.mapTo(HashSet()) { it.locationKey }
        val trees = AddedFolders.list(context)
        val folders = if (trees.isEmpty()) emptyList() else AddedFolders.scan(context, trees, known).songs
        val edits = SongEdits(context)
        val songs = (phone + folders).map(edits::apply)
        return Snapshot(songs, songs.associateBy { it.id }, System.currentTimeMillis()).also { snapshot = it }
    }

    val root: MediaItem get() = folder(ROOT, "Isaialai", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    /** The items inside [parentId], or null if there's no such folder. */
    fun children(parentId: String): List<MediaItem>? {
        val lib = library()
        if (parentId == ROOT) {
            return listOf(
                folder(RECENT, "Recently played", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                folder(LIKED, "Liked songs", MediaMetadata.MEDIA_TYPE_PLAYLIST),
                folder(PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                folder(ALBUMS, "Albums & movies", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                folder(ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                folder(COMPOSERS, "Music directors", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                folder(ALL, "All songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            )
        }
        return when (parentId) {
            PLAYLISTS -> playlists().filter { it.first != PlaylistStore.LIKED_ID }.map { (id, name, ids) ->
                folder("playlist:$id", name, MediaMetadata.MEDIA_TYPE_PLAYLIST, subtitle = songsLabel(ids.size))
            }
            ALBUMS -> LibraryGrouping.albums(lib.songs).map {
                folder("album:${it.key}", it.name, MediaMetadata.MEDIA_TYPE_ALBUM, subtitle = it.artist)
            }
            ARTISTS -> LibraryGrouping.artists(lib.songs).map {
                folder("artist:${it.name.lowercase()}", it.name, MediaMetadata.MEDIA_TYPE_ARTIST, subtitle = songsLabel(it.songs.size))
            }
            COMPOSERS -> LibraryGrouping.composers(lib.songs).map {
                folder("composer:${it.name.lowercase()}", it.name, MediaMetadata.MEDIA_TYPE_ARTIST, subtitle = songsLabel(it.songs.size))
            }
            else -> songsIn(parentId)?.let { list ->
                remember(list)
                list.map { it.toMediaItem() }
            }
        }
    }

    /** A folder or song by its id, for the car's "what is this?" requests. */
    fun item(id: String): MediaItem? {
        if (id == ROOT) return root
        library().byId[id]?.let { return it.toMediaItem() }
        return children(ROOT)?.firstOrNull { it.mediaId == id }
    }

    /** The songs in a song list (Liked songs, a playlist, an album…), in play order. */
    private fun songsIn(parentId: String): List<Song>? {
        val lib = library()
        return when {
            parentId == RECENT -> history().mapNotNull { lib.byId[it] }
            parentId == LIKED -> playlists().firstOrNull { it.first == PlaylistStore.LIKED_ID }?.third.orEmpty().mapNotNull { lib.byId[it] }
            parentId == ALL -> LibraryGrouping.sort(lib.songs, SongSort.TITLE)
            parentId.startsWith("playlist:") ->
                playlists().firstOrNull { it.first == parentId.removePrefix("playlist:") }?.third?.mapNotNull { lib.byId[it] }
            parentId.startsWith("album:") ->
                LibraryGrouping.albums(lib.songs).firstOrNull { it.key == parentId.removePrefix("album:") }?.songs
            parentId.startsWith("artist:") ->
                LibraryGrouping.artists(lib.songs).firstOrNull { it.name.lowercase() == parentId.removePrefix("artist:") }?.songs
            parentId.startsWith("composer:") ->
                LibraryGrouping.composers(lib.songs).firstOrNull { it.name.lowercase() == parentId.removePrefix("composer:") }?.songs
            else -> null
        }
    }

    /**
     * When the car plays one song from a list it just showed, the rest of that list plays after it
     * (as on the phone). Returns the list and where the song is in it, or null.
     */
    @Synchronized
    fun listContaining(songId: String): Pair<List<Song>, Int>? {
        for (list in recentLists) {
            val i = list.indexOfFirst { it.id == songId }
            if (i >= 0) return list to i
        }
        return library().byId[songId]?.let { listOf(it) to 0 }
    }

    /** Songs matching a spoken request ("play Ilaiyaraaja on Isaialai"); everything, shuffled, if it's empty. */
    fun search(query: String): List<Song> {
        val songs = library().songs
        if (query.isBlank()) return songs.shuffled()
        val words = query.trim()
        return songs.filter { it.matches(words) }.take(200)
    }

    @Synchronized
    private fun remember(list: List<Song>) {
        recentLists.addFirst(list)
        while (recentLists.size > 6) recentLists.removeLast()
    }

    private fun history(): List<String> = try {
        val arr = JSONArray(context.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("history", "[]"))
        List(arr.length()) { arr.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    /** (id, name, song ids) for each playlist, read the same way the app stores them. */
    private fun playlists(): List<Triple<String, String, List<String>>> = try {
        val arr = JSONArray(context.getSharedPreferences("playlists", Context.MODE_PRIVATE).getString("data", "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val ids = o.getJSONArray("songs")
            Triple(o.getString("id"), o.getString("name"), List(ids.length()) { ids.getString(it) })
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun songsLabel(n: Int) = if (n == 1) "1 song" else "$n songs"

    private fun folder(id: String, title: String, type: Int, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(type)
                    .build(),
            )
            .build()
}
