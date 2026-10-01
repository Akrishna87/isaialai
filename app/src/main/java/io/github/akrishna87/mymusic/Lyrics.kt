package io.github.akrishna87.mymusic

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/** A song's words: timed lines that follow the music (from an .lrc file), or plain text. */
data class Lyrics(val lines: List<Line>, val synced: Boolean, val source: String) {
    data class Line(val timeMs: Long, val text: String)

    /** The line being sung at [positionMs], or -1 before the first one. */
    fun lineAt(positionMs: Long): Int {
        if (!synced) return -1
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    companion object {
        private val STAMP = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
        private val TAG = Regex("^\\[(ar|ti|al|au|by|re|ve|length|#):.*]$", RegexOption.IGNORE_CASE)
        private val OFFSET = Regex("^\\[offset:\\s*([+-]?\\d+)\\s*]$", RegexOption.IGNORE_CASE)

        /** Reads LRC ("[01:23.45]words") or, if there are no timestamps, plain text. */
        fun parse(text: String, source: String): Lyrics? {
            val raw = text.replace("\r\n", "\n").replace('\r', '\n').trim('﻿', ' ', '\n')
            if (raw.isBlank()) return null
            var offset = 0L
            val timed = ArrayList<Line>()
            for (line in raw.lines()) {
                val l = line.trim()
                val off = OFFSET.find(l)
                if (off != null) {
                    offset = off.groupValues[1].toLong()
                    continue
                }
                if (TAG.matches(l)) continue
                val stamps = STAMP.findAll(l).toList()
                if (stamps.isEmpty() || stamps.first().range.first != 0) continue
                val words = l.substring(stamps.last().range.last + 1).trim()
                for (m in stamps) {
                    val (min, sec, frac) = m.destructured
                    val fracMs = when (frac.length) {
                        0 -> 0L
                        1 -> frac.toLong() * 100
                        2 -> frac.toLong() * 10
                        else -> frac.take(3).toLong()
                    }
                    // A positive offset means the words come earlier.
                    val t = TimeUnit.MINUTES.toMillis(min.toLong()) + TimeUnit.SECONDS.toMillis(sec.toLong()) + fracMs - offset
                    timed += Line(t.coerceAtLeast(0), words)
                }
            }
            if (timed.isNotEmpty()) {
                return Lyrics(timed.sortedBy { it.timeMs }, synced = true, source = source)
            }
            val plain = raw.lines().map { it.trim() }.filterNot { TAG.matches(it) }
            return Lyrics(plain.map { Line(0, it) }, synced = false, source = source).takeIf { plain.any(String::isNotBlank) }
        }
    }
}

object LyricsLoader {
    private val cache = HashMap<String, Lyrics?>()

    fun forget() = synchronized(cache) { cache.clear() }

    /**
     * An .lrc file with the song's name in the same folder (readable when that folder was added
     * with "Add a folder"), else lyrics saved inside the song file. Call off the main thread.
     */
    fun load(context: Context, song: Song, lrcFiles: Map<String, Uri>): Lyrics? {
        synchronized(cache) { if (cache.containsKey(song.id)) return cache[song.id] }
        val found = fromLrcFile(context, song, lrcFiles) ?: embedded(context, song)
        synchronized(cache) { cache[song.id] = found }
        return found
    }

    /** Where to look up a song's .lrc in [lrcFiles]: its location without the extension. */
    fun lrcKey(locationKey: String): String = locationKey.substringBeforeLast('.')

    private fun fromLrcFile(context: Context, song: Song, lrcFiles: Map<String, Uri>): Lyrics? {
        val uri = lrcFiles[lrcKey(song.locationKey)] ?: return null
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes().take(512 * 1024).toByteArray()
                Lyrics.parse(decode(bytes), "Lyrics file")
            }
        } catch (e: Exception) {
            null
        }
    }

    @OptIn(UnstableApi::class)
    private fun embedded(context: Context, song: Song): Lyrics? = try {
        val groups = MetadataRetriever.retrieveMetadata(context, MediaItem.fromUri(song.uri)).get(10, TimeUnit.SECONDS)
        var text: String? = null
        for (g in 0 until groups.length) {
            val group = groups[g]
            for (f in 0 until group.length) {
                val metadata = group.getFormat(f).metadata ?: continue
                text = text ?: lyricsIn(metadata)
            }
        }
        text?.let { Lyrics.parse(it, "Saved in the song") }
    } catch (e: Exception) {
        null
    }

    @OptIn(UnstableApi::class)
    private fun lyricsIn(metadata: Metadata): String? {
        for (i in 0 until metadata.length()) {
            when (val e = metadata[i]) {
                // MP4 (©lyr) arrives as a text frame; some taggers use TXXX:LYRICS.
                is TextInformationFrame ->
                    if (e.id == "USLT" || (e.id == "TXXX" && e.description.orEmpty().contains("lyric", ignoreCase = true))) {
                        e.values.joinToString("\n").takeIf { it.isNotBlank() }?.let { return it }
                    }
                // MP3 USLT frames aren't decoded by the player, so read them here.
                is BinaryFrame -> if (e.id == "USLT") usltText(e.data)?.let { return it }
                // FLAC / Ogg / Opus.
                is VorbisComment ->
                    if (e.key.equals("LYRICS", true) || e.key.equals("UNSYNCEDLYRICS", true)) {
                        e.value.takeIf { it.isNotBlank() }?.let { return it }
                    }
            }
        }
        return null
    }

    /** ID3 USLT: encoding byte, 3-letter language, a description ending in a terminator, then the words. */
    private fun usltText(data: ByteArray): String? {
        if (data.size < 5) return null
        val charset: Charset = when (data[0].toInt()) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        val wide = data[0].toInt() == 1 || data[0].toInt() == 2
        var i = 4
        // Skip the description.
        if (wide) {
            while (i + 1 < data.size && !(data[i].toInt() == 0 && data[i + 1].toInt() == 0)) i += 2
            i += 2
        } else {
            while (i < data.size && data[i].toInt() != 0) i++
            i += 1
        }
        if (i >= data.size) return null
        return String(data, i, data.size - i, charset).trimEnd('\u0000').takeIf { it.isNotBlank() }
    }

    /** .lrc files are usually UTF-8; fall back to Latin-1 for old ones that aren't. */
    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).drop(1)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).drop(1)
        val utf8 = Charsets.UTF_8.newDecoder()
        return try {
            utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }
}
