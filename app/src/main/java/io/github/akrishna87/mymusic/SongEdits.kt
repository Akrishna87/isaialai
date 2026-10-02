package io.github.akrishna87.mymusic

import android.content.Context
import org.json.JSONObject

/**
 * Song details you've corrected in the app (title, artist, album, music director, year).
 * They're kept by Isaialai and laid over what's in the file; the song files themselves are
 * never changed, so an edit can always be undone.
 */
class SongEdits(context: Context) {
    data class Edit(val title: String, val artist: String, val album: String, val composer: String, val year: Int)

    private val prefs = context.getSharedPreferences("edits", Context.MODE_PRIVATE)
    private val edits = HashMap<String, Edit>()

    init {
        try {
            val o = JSONObject(prefs.getString("data", "{}") ?: "{}")
            o.keys().forEach { id ->
                val e = o.getJSONObject(id)
                edits[id] = Edit(e.optString("t"), e.optString("a"), e.optString("al"), e.optString("c"), e.optInt("y"))
            }
        } catch (e: Exception) {
            // start without edits rather than crash
        }
    }

    private fun save() {
        val o = JSONObject()
        edits.forEach { (id, e) ->
            o.put(id, JSONObject().put("t", e.title).put("a", e.artist).put("al", e.album).put("c", e.composer).put("y", e.year))
        }
        prefs.edit().putString("data", o.toString()).apply()
    }

    fun isEdited(songId: String) = songId in edits

    fun set(songId: String, edit: Edit) {
        edits[songId] = edit
        save()
    }

    fun clear(songId: String) {
        if (edits.remove(songId) != null) save()
    }

    /** The song as you've corrected it, or unchanged. */
    fun apply(song: Song): Song {
        val e = edits[song.id] ?: return song
        val album = e.album.trim()
        return song.copy(
            title = e.title.trim().ifEmpty { song.title },
            artist = e.artist.trim().ifEmpty { Song.UNKNOWN_ARTIST },
            album = album,
            // A changed album name moves the song to that album (or movie).
            albumKey = AlbumNames.keyFor(album, song.id),
            composer = e.composer.trim(),
            year = e.year,
        )
    }
}
