package io.github.akrishna87.mymusic

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * "Even volume": how loud each song is, so loud ones can be turned down and quiet ones up.
 * A song is measured once (a few seconds from eight places across it) and remembered.
 */
object Loudness {
    /** The level songs are brought to, as average (RMS) level in dB below full scale. */
    private const val TARGET_DB = -14f
    const val MAX_CUT_DB = -12f
    const val MAX_BOOST_DB = 6f

    private const val WINDOWS = 8
    private const val WINDOW_US = 3_000_000L

    private val cache = HashMap<String, Float>()
    private var loaded = false

    /** How many dB to turn a song with this average level up (+) or down (−). */
    fun gainFor(levelDb: Float): Float = (TARGET_DB - levelDb).coerceIn(MAX_CUT_DB, MAX_BOOST_DB)

    private fun file(context: Context) = File(context.filesDir, "loudness.json")

    @Synchronized
    fun cached(context: Context, songId: String): Float? {
        if (!loaded) {
            loaded = true
            try {
                val f = file(context)
                if (f.exists()) {
                    val o = JSONObject(f.readText())
                    o.keys().forEach { k -> cache[k] = o.getDouble(k).toFloat() }
                }
            } catch (e: Exception) {
                // start over
            }
        }
        return cache[songId]
    }

    @Synchronized
    private fun remember(context: Context, songId: String, level: Float) {
        cache[songId] = level
        try {
            file(context).writeText(JSONObject(cache.mapValues { it.value.toDouble() }).toString())
        } catch (e: Exception) {
            // only an optimisation
        }
    }

    /** The song's average level in dB (cached after the first time), or null if it can't be read. Slow: call off the main thread. */
    fun level(context: Context, songId: String): Float? {
        cached(context, songId)?.let { return it }
        val measured = measure(context, Song.uriForId(songId)) ?: return null
        remember(context, songId, measured)
        return measured
    }

    private fun measure(context: Context, uri: Uri): Float? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var sumSquares = 0.0
            var samples = 0L
            var floatPcm = false
            val starts = if (durationUs > WINDOW_US * WINDOWS) {
                List(WINDOWS) { i -> durationUs * (2 * i + 1) / (2 * WINDOWS) - WINDOW_US / 2 }
            } else {
                listOf(0L)
            }
            val info = MediaCodec.BufferInfo()
            for (start in starts) {
                val end = if (starts.size == 1) Long.MAX_VALUE else start + WINDOW_US
                extractor.seekTo(start, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                decoder.flush()
                var inputDone = false
                var steps = 0
                while (steps++ < 20_000) {
                    if (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buffer = decoder.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0 || extractor.sampleTime > end) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val out = decoder.outputFormat
                        floatPcm = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else if (outIndex >= 0) {
                        if (info.size > 0 && info.presentationTimeUs >= start) {
                            val data = decoder.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            if (floatPcm) {
                                val f = data.asFloatBuffer()
                                while (f.hasRemaining()) { val v = f.get().toDouble(); sumSquares += v * v; samples++ }
                            } else {
                                val s = data.asShortBuffer()
                                while (s.hasRemaining()) { val v = s.get() / 32768.0; sumSquares += v * v; samples++ }
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || info.presentationTimeUs > end) break
                    }
                }
            }
            if (samples == 0L) return null
            val rms = sqrt(sumSquares / samples)
            return (20 * log10(rms.coerceAtLeast(1e-6))).toFloat()
        } catch (e: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (e: Exception) { /* ignore */ }
            try { codec?.release() } catch (e: Exception) { /* ignore */ }
            extractor.release()
        }
    }
}
