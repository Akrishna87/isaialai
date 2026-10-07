package io.github.akrishna87.mymusic

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Equaliser and sleep-timer settings, shared between the screens (which change them) and
 * [PlaybackService] (which owns the audio effects and applies changes as they're written).
 */
object Effects {
    const val PREFS = "effects"
    const val KEY_INFO = "eq_info"
    private const val KEY_ENABLED = "eq_enabled"
    private const val KEY_PRESET = "eq_preset"
    private const val KEY_LEVELS = "eq_levels"
    private const val KEY_BASS = "bass"
    const val KEY_SLEEP_UNTIL = "sleep_until"
    const val KEY_SLEEP_END_OF_SONG = "sleep_end_of_song"

    /** Crossfade length in seconds, 0 (off) to 12. */
    const val KEY_CROSSFADE = "crossfade_sec"
    /** Even volume (loudness levelling) on or off. */
    const val KEY_EVEN_VOLUME = "even_volume"
    /** Written by the service: "<title>|<dB>" for the song playing now, while even volume is on. */
    const val KEY_EVEN_VOLUME_NOW = "even_volume_now"

    /** Volume boost, like VLC's: 100 (off) to [MAX_BOOST] percent. */
    const val KEY_BOOST = "boost_percent"
    const val MAX_BOOST = 200
    /** Written by the service: false if this phone can't boost (no loudness enhancer effect). */
    const val KEY_BOOST_AVAILABLE = "boost_available"

    /** Continue where you left off: "off", "long" (tracks of 10+ minutes) or "all". */
    const val KEY_RESUME_MODE = "resume_mode"
    /** Written by the service when it continues a song part-way: "<title>|<position ms>|<time>". */
    const val KEY_RESUMED = "resumed"

    /** Your choices, as saved in a backup (not what this phone's equaliser offers, or the timer). */
    val BACKUP_KEYS = listOf(KEY_ENABLED, KEY_PRESET, KEY_LEVELS, KEY_BASS, KEY_CROSSFADE, KEY_EVEN_VOLUME, KEY_BOOST, KEY_RESUME_MODE)

    /** Keys the service writes for the screens to show, rather than settings it should act on. */
    val STATUS_KEYS = setOf(KEY_INFO, KEY_SLEEP_UNTIL, KEY_SLEEP_END_OF_SONG, KEY_EVEN_VOLUME_NOW, KEY_BOOST_AVAILABLE, KEY_RESUMED)

    fun resumeMode(p: SharedPreferences): ResumeMode =
        ResumeMode.entries.firstOrNull { it.key == p.getString(KEY_RESUME_MODE, null) } ?: ResumeMode.LONG

    fun boostPercent(p: SharedPreferences): Int = p.getInt(KEY_BOOST, 100).coerceIn(100, MAX_BOOST)

    /** What this phone's equaliser can do, written by the service once it has created the effect. */
    data class EqInfo(
        val available: Boolean,
        /** Centre frequency of each band, in Hz. */
        val bandsHz: List<Int> = emptyList(),
        /** Lowest and highest band level, in millibels (100 mB = 1 dB). */
        val minLevel: Int = -1500,
        val maxLevel: Int = 1500,
        /** Built-in presets: name and the band levels they set. */
        val presets: List<Pair<String, List<Int>>> = emptyList(),
        val bassBoost: Boolean = false,
    )

    data class Settings(
        val enabled: Boolean,
        /** Index of a built-in preset, or -1 for the person's own band levels. */
        val preset: Int,
        val levels: List<Int>,
        /** Bass boost strength, 0–1000. */
        val bass: Int,
    )

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun readInfo(p: SharedPreferences): EqInfo? = try {
        p.getString(KEY_INFO, null)?.let { s ->
            val o = JSONObject(s)
            val bands = o.getJSONArray("bands")
            val presets = o.getJSONArray("presets")
            EqInfo(
                available = o.getBoolean("available"),
                bandsHz = List(bands.length()) { bands.getInt(it) },
                minLevel = o.optInt("min", -1500),
                maxLevel = o.optInt("max", 1500),
                presets = List(presets.length()) { i ->
                    val pr = presets.getJSONObject(i)
                    val lv = pr.getJSONArray("levels")
                    pr.getString("name") to List(lv.length()) { lv.getInt(it) }
                },
                bassBoost = o.optBoolean("bass", false),
            )
        }
    } catch (e: Exception) {
        null
    }

    fun writeInfo(p: SharedPreferences, info: EqInfo) {
        val o = JSONObject()
            .put("available", info.available)
            .put("bands", JSONArray(info.bandsHz))
            .put("min", info.minLevel)
            .put("max", info.maxLevel)
            .put("bass", info.bassBoost)
            .put("presets", JSONArray(info.presets.map { (name, levels) -> JSONObject().put("name", name).put("levels", JSONArray(levels)) }))
        p.edit().putString(KEY_INFO, o.toString()).apply()
    }

    fun read(p: SharedPreferences): Settings = Settings(
        enabled = p.getBoolean(KEY_ENABLED, false),
        preset = p.getInt(KEY_PRESET, 0),
        levels = p.getString(KEY_LEVELS, "").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() },
        bass = p.getInt(KEY_BASS, 0),
    )

    fun write(p: SharedPreferences, s: Settings) {
        p.edit()
            .putBoolean(KEY_ENABLED, s.enabled)
            .putInt(KEY_PRESET, s.preset)
            .putString(KEY_LEVELS, s.levels.joinToString(","))
            .putInt(KEY_BASS, s.bass)
            .apply()
    }
}

enum class ResumeMode(val key: String, val label: String) {
    OFF("off", "Off"),
    LONG("long", "Long tracks"),
    ALL("all", "All songs"),
    ;

    /** Whether a song this long continues where it was left. */
    fun applies(durationMs: Long): Boolean = when (this) {
        OFF -> false
        LONG -> durationMs >= 10 * 60_000L
        ALL -> durationMs > 0
    }
}

/**
 * Where each song was left, for "continue where you left off". Kept per song, with its length
 * and when it was last played, in the "resume" preferences.
 */
object ResumePoints {
    const val PREFS = "resume"

    data class Point(val songId: String, val positionMs: Long, val durationMs: Long, val at: Long)

    fun prefs(context: android.content.Context): SharedPreferences =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    fun get(p: SharedPreferences, songId: String): Point? = parse(songId, p.getString(songId, null))

    fun all(p: SharedPreferences): List<Point> = p.all.mapNotNull { (k, v) -> parse(k, v as? String) }.sortedByDescending { it.at }

    /** Remembers [positionMs], or forgets the song if it's near the start or the end (finished). */
    fun save(p: SharedPreferences, songId: String, positionMs: Long, durationMs: Long) {
        if (songId.isEmpty() || durationMs <= 0) return
        val e = p.edit()
        if (positionMs < 10_000 || durationMs - positionMs < 15_000) e.remove(songId)
        else e.putString(songId, "$positionMs|$durationMs|${System.currentTimeMillis()}")
        e.apply()
    }

    private fun parse(id: String, v: String?): Point? {
        val parts = v?.split('|') ?: return null
        if (parts.size != 3) return null
        val pos = parts[0].toLongOrNull() ?: return null
        val dur = parts[1].toLongOrNull() ?: return null
        val at = parts[2].toLongOrNull() ?: return null
        return Point(id, pos, dur, at)
    }
}
