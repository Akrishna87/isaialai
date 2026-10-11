package io.github.akrishna87.mymusic

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.random.Random

/** What Isaialai knows about how you listen, used to find songs and artists that go together. */
class ListeningSignals(
    val playCounts: Map<String, Int> = emptyMap(),
    /** Your playlists (and Liked songs), as song ids. */
    val playlists: List<List<String>> = emptyList(),
    val liked: Set<String> = emptySet(),
    /** Song ids, most recently played first. */
    val recent: List<String> = emptyList(),
)

/**
 * Finds what goes with what, using only what's on the phone: shared singers and music directors,
 * the same movie or album, folder and era, songs you keep in the same playlists, and what you
 * like and play most. Used for Instant mix, Autoplay, Similar artists and Home's mixes.
 */
class Similar(private val library: List<Song>, private val signals: ListeningSignals) {

    private class Features(
        val artists: Set<String>,
        val composers: Set<String>,
        val album: String?,
        val folder: String,
        val year: Int,
        val playlists: Set<Int>,
    )

    private val playlistsOf: Map<String, Set<Int>> = HashMap<String, MutableSet<Int>>().also { map ->
        signals.playlists.forEachIndexed { i, ids -> ids.forEach { map.getOrPut(it) { HashSet() } += i } }
    }

    private val features = HashMap<String, Features>(library.size * 2)

    private fun f(s: Song): Features = features.getOrPut(s.id) {
        Features(
            artists = names(s.artist),
            composers = names(s.composer),
            album = s.albumKey.takeIf { s.album.isNotBlank() && it.isNotEmpty() },
            folder = s.folder.lowercase(),
            year = s.year,
            playlists = playlistsOf[s.id].orEmpty(),
        )
    }

    /** How alike two songs are; 0 means nothing in common. */
    fun score(a: Song, b: Song): Double {
        val x = f(a)
        val y = f(b)
        var s = 0.0
        s += 4.0 * minOf(2, (x.artists intersect y.artists).size)
        s += 4.0 * minOf(2, (x.composers intersect y.composers).size)
        if (x.album != null && x.album == y.album) s += 3.0
        if (x.folder.isNotEmpty() && x.folder == y.folder) s += 1.0
        if (x.year > 0 && y.year > 0) {
            val dy = abs(x.year - y.year)
            s += when {
                dy <= 2 -> 1.5
                dy <= 5 -> 0.75
                else -> 0.0
            }
        }
        s += minOf(6.0, 2.0 * (x.playlists intersect y.playlists).size)
        if (a.id in signals.liked && b.id in signals.liked) s += 1.0
        return s
    }

    /** A little extra for songs you like and play, and a lot less for ones you just heard. */
    private fun personal(s: Song, recent: Map<String, Int>): Double {
        var p = 0.0
        if (s.id in signals.liked) p += 0.75
        val plays = signals.playCounts[s.id] ?: 0
        if (plays > 0) p += minOf(1.5, ln(1.0 + plays) / 2)
        // The last few songs you heard go to the back; ones from a little earlier just move down.
        recent[s.id]?.let { at -> p -= if (at < 5) 10.0 else 3.0 }
        return p
    }

    /**
     * Up to [size] songs that go with [seeds], best first but varied (not one album after
     * another). [includeSeeds]: whether the seeds themselves may be picked (for an artist's or
     * album's mix) or not (for a song's radio, which starts with the song). Songs in [exclude]
     * are never picked. If too few songs are alike, the rest is filled with your favourites and
     * then anything, so a mix is never cut short in a small library.
     */
    fun mix(
        seeds: List<Song>,
        size: Int = 50,
        includeSeeds: Boolean = false,
        exclude: Set<String> = emptySet(),
        random: Random = Random.Default,
    ): List<Song> {
        if (library.isEmpty() || size <= 0) return emptyList()
        val seedList = seeds.distinctBy { it.id }.take(25)
        val seedIds = seedList.mapTo(HashSet()) { it.id }
        val recent = signals.recent.take(15).withIndex().filter { it.value !in seedIds }.associate { it.value to it.index }
        val candidates = library.filter { it.id !in exclude && (includeSeeds || it.id !in seedIds) }
        if (candidates.isEmpty()) return emptyList()

        val scored = candidates.map { c ->
            val alike = if (seedList.isEmpty()) 0.0 else {
                val each = seedList.map { s -> if (s.id == c.id) 6.0 else score(s, c) }
                each.max() + 0.3 * each.average()
            }
            Triple(c, alike, alike + personal(c, recent) + random.nextDouble() * 1.5)
        }.sortedByDescending { it.third }

        val picked = ArrayList<Song>(size)
        val pickedIds = HashSet<String>()
        val perAlbum = HashMap<String, Int>()
        val perArtist = HashMap<String, Int>()
        val maxPerAlbum = 4
        val maxPerArtist = maxOf(3, (size * 0.4).toInt())
        fun take(s: Song) {
            picked += s
            pickedIds += s.id
            f(s).album?.let { perAlbum[it] = (perAlbum[it] ?: 0) + 1 }
            f(s).artists.forEach { perArtist[it] = (perArtist[it] ?: 0) + 1 }
        }
        fun fits(s: Song): Boolean {
            val ff = f(s)
            if (ff.album != null && (perAlbum[ff.album] ?: 0) >= maxPerAlbum) return false
            if (ff.artists.any { (perArtist[it] ?: 0) >= maxPerArtist }) return false
            // Not two songs in a row from the same movie or album.
            val last = picked.lastOrNull()?.let(::f)
            return !(last != null && ff.album != null && ff.album == last.album)
        }

        // Alike songs first, keeping variety; songs held back for variety come next.
        val alike = scored.filter { it.second >= 2.0 }
        val held = ArrayList<Song>()
        for ((s, _, _) in alike) {
            if (picked.size >= size) break
            if (fits(s)) take(s) else held += s
        }
        for (s in held) {
            if (picked.size >= size) break
            if (s.id !in pickedIds && fits(s)) take(s)
        }
        // Then the rest (your favourites rank first), still without repeats.
        for ((s, _, _) in scored) {
            if (picked.size >= size) break
            if (s.id !in pickedIds) take(s)
        }
        return picked
    }

    /** Artists like [artist]: who share movies, music directors, names (duets), folders and playlists with them. */
    fun similarArtists(artist: ArtistGroup, all: List<ArtistGroup>, max: Int = 10): List<ArtistGroup> {
        class Profile(
            val names: Set<String>,
            val composers: Set<String>,
            val albums: Set<String>,
            val folders: Set<String>,
            val playlists: Set<Int>,
            val year: Double,
        )
        fun profile(g: ArtistGroup): Profile {
            val fs = g.songs.map(::f)
            val years = g.songs.map { it.year }.filter { it > 0 }
            return Profile(
                names = names(g.name),
                composers = fs.flatMapTo(HashSet()) { it.composers },
                albums = fs.mapNotNullTo(HashSet()) { it.album },
                folders = fs.mapTo(HashSet()) { it.folder }.filterTo(HashSet()) { it.isNotEmpty() },
                playlists = fs.flatMapTo(HashSet()) { it.playlists },
                year = if (years.isEmpty()) 0.0 else years.average(),
            )
        }
        fun jaccard(a: Set<*>, b: Set<*>): Double = if (a.isEmpty() || b.isEmpty()) 0.0 else (a intersect b).size.toDouble() / (a union b).size

        if (artist.name.isUnknownArtist()) return emptyList()
        val me = profile(artist)
        return all.asSequence()
            .filter { it.name != artist.name && !it.name.isUnknownArtist() }
            .map { other ->
                val o = profile(other)
                var s = 5.0 * minOf(2, (me.names intersect o.names).size)
                s += 8.0 * jaccard(me.composers, o.composers)
                s += minOf(9.0, 3.0 * (me.albums intersect o.albums).size)
                s += 2.0 * jaccard(me.folders, o.folders)
                s += minOf(4.5, 1.5 * (me.playlists intersect o.playlists).size)
                if (me.year > 0 && o.year > 0 && abs(me.year - o.year) <= 5) s += 1.0
                other to s
            }
            .filter { it.second >= 2.5 }
            .sortedByDescending { it.second }
            .take(max)
            .map { it.first }
            .toList()
    }

    companion object {
        private val SPLIT = Regex("""\s*(?:,|;|/|\||&|\+|\bfeat\.?\s|\bft\.?\s|\bfeaturing\b|\bwith\b|\sx\s|\sand\s)\s*""", RegexOption.IGNORE_CASE)
        private val NOT_A_NAME = setOf("", "unknownartist", "unknown", "variousartists", "various", "va")

        /** The names in an artist or music director tag, normalised: "A.R. Rahman & Hariharan" → {"arrahman", "hariharan"}. */
        fun names(tag: String): Set<String> =
            tag.split(SPLIT).map { norm(it) }.filterTo(HashSet()) { it !in NOT_A_NAME }

        /** The names in a tag as written: "A.R. Rahman & Hariharan" → ["A.R. Rahman", "Hariharan"]. */
        fun displayNames(tag: String): List<String> =
            tag.split(SPLIT).map { it.trim() }.filter { norm(it) !in NOT_A_NAME }.distinctBy { norm(it) }

        private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase().filter(::isNamePart)

        /** Letters, digits and the vowel signs of scripts like Tamil (which aren't "letters" on their own). */
        fun isNamePart(c: Char): Boolean = c.isLetterOrDigit() || when (Character.getType(c).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
            else -> false
        }

        private fun String.isUnknownArtist() = norm(this) in NOT_A_NAME
    }
}
