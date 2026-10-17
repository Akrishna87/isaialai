package io.github.akrishna87.mymusic

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One time a song was listened to (at least 30 seconds, or half of a short song). */
data class Listen(
    /** When it started, ms since 1970. */
    val at: Long,
    val songId: String,
    /** How long it was actually playing. */
    val ms: Long,
    val title: String,
    val artist: String,
    val album: String,
)

/** The stretch of time a recap covers. */
enum class RecapPeriod(val label: String) {
    THIS_MONTH("This month"), LAST_MONTH("Last month"), THIS_YEAR("This year"), ALL_TIME("All time");

    /** [start, end) in ms, in the phone's time zone. */
    fun range(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): LongRange {
        fun ms(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val month = today.withDayOfMonth(1)
        return when (this) {
            THIS_MONTH -> ms(month) until ms(month.plusMonths(1))
            LAST_MONTH -> ms(month.minusMonths(1)) until ms(month)
            THIS_YEAR -> ms(today.withDayOfYear(1)) until ms(today.withDayOfYear(1).plusYears(1))
            ALL_TIME -> 0L until Long.MAX_VALUE
        }
    }
}

/** Your listening over a period: how much, and what most. */
class RecapStats(
    val ms: Long,
    val plays: Int,
    val songs: Int,
    val daysListened: Int,
    val topSongs: List<Top>,
    val topArtists: List<Top>,
    val topComposers: List<Top>,
    val topAlbums: List<Top>,
    /** The day of the week with the most listening, and the part of the day. */
    val busiestDay: DayOfWeek?,
    val partOfDay: String?,
) {
    /** A song, artist, music director or album, with how often and how long it was played. */
    data class Top(val name: String, val detail: String, val plays: Int, val ms: Long, val songId: String)

    val isEmpty: Boolean get() = plays == 0

    companion object {
        /**
         * Adds up [listens] in [range]. Music directors and up-to-date names come from [library]
         * where the song is still there; otherwise what was saved when it played is used.
         */
        fun of(listens: List<Listen>, range: LongRange, library: Map<String, Song>, zone: ZoneId = ZoneId.systemDefault(), top: Int = 5): RecapStats {
            val inRange = listens.filter { it.at in range }
            fun <K> rank(key: (Listen) -> List<K>, describe: (K, List<Listen>) -> Pair<String, String>): List<Top> {
                val groups = LinkedHashMap<K, MutableList<Listen>>()
                for (l in inRange) for (k in key(l).distinct()) groups.getOrPut(k) { ArrayList() } += l
                return groups.entries
                    .sortedWith(compareByDescending<Map.Entry<K, MutableList<Listen>>> { it.value.size }.thenByDescending { e -> e.value.sumOf { it.ms } })
                    .take(top)
                    .map { (k, ls) ->
                        val (name, detail) = describe(k, ls)
                        Top(name, detail, ls.size, ls.sumOf { it.ms }, ls.groupingBy { it.songId }.eachCount().maxBy { it.value }.key)
                    }
            }
            fun song(l: Listen) = library[l.songId]

            val topSongs = rank({ listOf(it.songId) }) { _, ls ->
                val s = song(ls.first())
                (s?.title ?: ls.first().title) to (s?.artist ?: ls.first().artist)
            }
            val topArtists = rank({ l -> Similar.displayNames(song(l)?.artist ?: l.artist).map { it.lowercase() } }) { k, ls ->
                val name = ls.firstNotNullOf { l -> Similar.displayNames(song(l)?.artist ?: l.artist).firstOrNull { it.lowercase() == k } }
                name to ""
            }
            val topComposers = rank({ l -> LibraryGrouping.composerNames(song(l)?.composer.orEmpty()).map { it.lowercase() } }) { k, ls ->
                val name = ls.firstNotNullOf { l -> LibraryGrouping.composerNames(song(l)?.composer.orEmpty()).firstOrNull { it.lowercase() == k } }
                name to ""
            }
            val topAlbums = rank({ l -> listOfNotNull((song(l)?.album ?: l.album).takeIf { it.isNotBlank() }?.lowercase()) }) { k, ls ->
                val s = song(ls.first())
                ((s?.album ?: ls.first().album).ifBlank { k }) to (s?.artist ?: ls.first().artist)
            }

            val times = inRange.map { Instant.ofEpochMilli(it.at).atZone(zone) }
            val byDay = inRange.zip(times).groupBy({ it.second.dayOfWeek }) { it.first.ms }.mapValues { it.value.sum() }
            val byPart = inRange.zip(times).groupBy({ partOfDay(it.second.hour) }) { it.first.ms }.mapValues { it.value.sum() }
            return RecapStats(
                ms = inRange.sumOf { it.ms },
                plays = inRange.size,
                songs = inRange.map { it.songId }.distinct().size,
                daysListened = times.map { it.toLocalDate() }.distinct().size,
                topSongs = topSongs,
                topArtists = topArtists,
                topComposers = topComposers,
                topAlbums = topAlbums,
                busiestDay = byDay.maxByOrNull { it.value }?.key,
                partOfDay = byPart.maxByOrNull { it.value }?.key,
            )
        }

        fun partOfDay(hour: Int): String = when (hour) {
            in 5..11 -> "in the morning"
            in 12..16 -> "in the afternoon"
            in 17..20 -> "in the evening"
            else -> "at night"
        }

        /** "12 h 5 min", "45 min" or "0 min". */
        fun duration(ms: Long): String {
            val min = ms / 60_000
            return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
        }
    }
}
