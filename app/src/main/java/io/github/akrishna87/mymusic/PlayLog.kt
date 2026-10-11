package io.github.akrishna87.mymusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every time a song was really listened to, for the recap: kept in the app as one line per
 * listen (when, which song, for how long, and its name so it still reads right if the song is
 * deleted later). Written by the player, so it counts music played with the app closed too.
 */
object PlayLog {
    private fun file(context: Context) = File(context.applicationContext.filesDir, "listens.tsv")

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').take(200)

    @Synchronized
    fun add(context: Context, l: Listen) {
        try {
            file(context).appendText(listOf(l.at, clean(l.songId), l.ms, clean(l.title), clean(l.artist), clean(l.album)).joinToString("\t") + "\n")
        } catch (e: Exception) {
            // only for the recap
        }
    }

    @Synchronized
    fun read(context: Context): List<Listen> = try {
        val f = file(context)
        if (!f.exists()) emptyList() else f.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 6) null else {
                val at = p[0].toLongOrNull()
                val ms = p[2].toLongOrNull()
                if (at == null || ms == null || p[1].isEmpty()) null else Listen(at, p[1], ms, p[3], p[4], p[5])
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Adds listens from a backup, skipping ones already here. Returns how many were added. */
    @Synchronized
    fun addAll(context: Context, listens: List<Listen>): Int {
        val have = read(context).mapTo(HashSet()) { it.at to it.songId }
        val fresh = listens.filter { (it.at to it.songId) !in have }.sortedBy { it.at }
        fresh.forEach { add(context, it) }
        return fresh.size
    }
}

/** What the screens save about your listening, read where the screens aren't running (the player, for Autoplay). */
fun readListeningSignals(context: Context): ListeningSignals {
    val ui = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    val counts = try {
        val o = JSONObject(ui.getString("counts", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.optInt(it) }
    } catch (e: Exception) {
        emptyMap()
    }
    val recent = try {
        val a = JSONArray(ui.getString("history", "[]"))
        List(a.length()) { a.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }
    val lists = try {
        val a = JSONArray(context.getSharedPreferences("playlists", Context.MODE_PRIVATE).getString("data", "[]"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val ids = o.getJSONArray("songs")
            o.getString("id") to List(ids.length()) { ids.getString(it) }
        }
    } catch (e: Exception) {
        emptyList()
    }
    return ListeningSignals(
        playCounts = counts,
        playlists = lists.map { it.second },
        liked = lists.firstOrNull { it.first == PlaylistStore.LIKED_ID }?.second?.toSet().orEmpty(),
        recent = recent,
    )
}
