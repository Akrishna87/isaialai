package io.github.akrishna87.mymusic

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Playlist(val id: String, val name: String, val songIds: List<String>)

/** Playlists are small, so they live as JSON in SharedPreferences. */
class PlaylistStore(context: Context) {
    private val prefs = context.getSharedPreferences("playlists", Context.MODE_PRIVATE)
    val items = mutableStateListOf<Playlist>()
    /** Called after every change (used to keep the backup up to date). */
    var onChange: (() -> Unit)? = null

    init {
        try {
            val arr = JSONArray(prefs.getString("data", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ids = o.getJSONArray("songs")
                // Older versions stored library ids as numbers; getString reads both.
                items += Playlist(o.getString("id"), o.getString("name"), List(ids.length()) { ids.getString(it) })
            }
        } catch (e: Exception) {
            // corrupt data: start empty rather than crash
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (p in items) {
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("songs", JSONArray(p.songIds)))
        }
        prefs.edit().putString("data", arr.toString()).apply()
        onChange?.invoke()
    }

    fun get(id: String): Playlist? = items.firstOrNull { it.id == id }

    /** Everything except Liked songs, which is shown separately. */
    val userPlaylists: List<Playlist> get() = items.filter { it.id != LIKED_ID }

    val liked: Playlist? get() = get(LIKED_ID)

    fun isLiked(songId: String): Boolean = liked?.songIds?.contains(songId) == true

    /** Returns true if the song is now liked. */
    fun toggleLike(songId: String): Boolean {
        val p = liked
        if (p == null) {
            items.add(0, Playlist(LIKED_ID, "Liked songs", listOf(songId)))
            save()
            return true
        }
        val nowLiked = songId !in p.songIds
        // Newest likes first, like other music apps.
        update(LIKED_ID) { it.copy(songIds = if (nowLiked) listOf(songId) + it.songIds else it.songIds - songId) }
        return nowLiked
    }

    companion object {
        const val LIKED_ID = "liked"
    }

    fun create(name: String, songIds: List<String> = emptyList()): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name, songIds.distinct())
        items += p
        save()
        return p
    }

    fun rename(id: String, name: String) = update(id) { it.copy(name = name) }

    fun delete(id: String) {
        items.removeAll { it.id == id }
        save()
    }

    /** Returns how many of the songs were new to the playlist. */
    fun add(id: String, songIds: List<String>): Int {
        val p = get(id) ?: return 0
        val fresh = songIds.filter { it !in p.songIds }.distinct()
        if (fresh.isNotEmpty()) update(id) { it.copy(songIds = it.songIds + fresh) }
        return fresh.size
    }

    /**
     * Adds [songIds] to your playlist called [name], making it if there isn't one (restoring a
     * backup twice, or importing a file again, doesn't make copies). Returns how many were new.
     */
    fun importPlaylist(name: String, songIds: List<String>): Int {
        val existing = userPlaylists.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        if (existing == null) {
            create(name.trim().ifEmpty { "Playlist" }, songIds)
            return songIds.distinct().size
        }
        return add(existing.id, songIds)
    }

    /** Likes all of [songIds] (in that order, after any already liked). Returns how many were new. */
    fun likeAll(songIds: List<String>): Int {
        val fresh = songIds.distinct().filter { !isLiked(it) }
        if (fresh.isEmpty()) return 0
        if (liked == null) {
            items.add(0, Playlist(LIKED_ID, "Liked songs", fresh))
            save()
        } else {
            update(LIKED_ID) { it.copy(songIds = it.songIds + fresh) }
        }
        return fresh.size
    }

    fun remove(id: String, songId: String) = update(id) { it.copy(songIds = it.songIds - songId) }

    /**
     * Puts the songs of a playlist in the order [shownOrder] (the songs as listed on screen).
     * Songs in the playlist that aren't on the phone right now keep their place at the end.
     */
    fun reorder(id: String, shownOrder: List<String>) = update(id) { p ->
        val shown = shownOrder.filter { it in p.songIds }.distinct()
        p.copy(songIds = shown + p.songIds.filter { it !in shown })
    }

    private fun update(id: String, change: (Playlist) -> Playlist) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        items[i] = change(items[i])
        save()
    }
}
