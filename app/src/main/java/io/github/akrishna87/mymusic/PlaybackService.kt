package io.github.akrishna87.mymusic

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Owns the player. Media3 turns the session into the media notification, lock-screen
 * controls and headphone/car/Bluetooth button handling, and keeps playback going while
 * the app is in the background.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    companion object {
        /** Custom command: add a song to the queue. Args: a song bundle plus "next" (Boolean). */
        const val CMD_ENQUEUE = "io.github.akrishna87.mymusic.ENQUEUE"

        /** The running player, for the home-screen widget's buttons; null when the service isn't running. */
        @Volatile
        var activePlayer: Player? = null
            private set

        /** Custom command: sleep timer. Arg "minutes": > 0 to stop after that long, -1 at the end of the song, 0 to cancel. */
        const val CMD_SLEEP = "io.github.akrishna87.mymusic.SLEEP"

        /** Custom command: move a song within Up next. Args "from" and "to": positions in Up next (0 = next song). */
        const val CMD_MOVE_UPCOMING = "io.github.akrishna87.mymusic.MOVE_UPCOMING"
    }

    private var session: MediaLibrarySession? = null
    // Android Auto's view of the library, read off the main thread.
    private val carLibrary by lazy { CarLibrary(this) }
    private val libraryExecutor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    private lateinit var player: ExoPlayer
    private val scope = MainScope()
    private var errorStreak = 0

    // Equaliser and bass boost, attached to this player's own audio stream.
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private val effectsPrefs by lazy { Effects.prefs(this) }
    private val effectsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Effects.KEY_EVEN_VOLUME -> updateEvenVolume()
            Effects.KEY_BOOST -> applyLoudness()
            Effects.KEY_CROSSFADE -> if (crossfadeMs() == 0L) cancelFade()
            in Effects.STATUS_KEYS -> Unit
            else -> applyEffects()
        }
    }
    private var sleepJob: Job? = null
    private var audioSession = C.AUDIO_SESSION_ID_UNSET

    // The player's volume is the product of these: even volume (turning loud songs down),
    // the sleep timer's fade-out, and the outgoing song's side of a crossfade.
    private var evenVolume = 1f
    private var sleepFade = 1f
    private var crossfadeFade = 1f
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var evenVolumeJob: Job? = null

    private fun applyVolume() {
        player.volume = (evenVolume * sleepFade * crossfadeFade).coerceIn(0f, 1f)
    }

    // Crossfade: a second player starts the next song early and fades it in while this one fades out.
    private var fadePlayer: ExoPlayer? = null
    private var fade: Fade? = null
    private var handoverJob: Job? = null
    private class Fade(val toId: String, val lengthMs: Long) {
        var handingOver = false
    }

    override fun onCreate() {
        super.onCreate()
        CustomArt.load(this)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        // Give the player a fixed audio session so the equaliser can attach to it.
        audioSession = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
        player.setAudioSessionId(audioSession)
        setUpEffects(audioSession)
        loudnessEnhancer = try {
            LoudnessEnhancer(audioSession)
        } catch (e: Exception) {
            null
        }
        effectsPrefs.edit().putBoolean(Effects.KEY_BOOST_AVAILABLE, loudnessEnhancer != null).apply()
        applyLoudness()
        activePlayer = player
        effectsPrefs.registerOnSharedPreferenceChangeListener(effectsListener)
        clearSleep() // a timer can't outlive the service that was running it
        restorePlaybackSpeed()

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, SessionCallback())
            .setSessionActivity(openApp)
            .setBitmapLoader(CacheBitmapLoader(ArtBitmapLoader(this)))
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) errorStreak = 0
                // Mid-crossfade, the incoming song stops and starts with this one. This also covers
                // phone calls and other apps' sounds, which silence the player without pausing it.
                if (fade?.handingOver == false) fadePlayer?.playWhenReady = isPlaying
                if (!isPlaying) rememberWhereLeft()
                savePosition()
                PlayerWidget.update(this@PlaybackService, player)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // "End of this song" sleep timer: the player has just paused at the end of the song.
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) clearSleep()
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                // Seeking during a crossfade calls it off (the handover's own seek doesn't).
                if (reason == Player.DISCONTINUITY_REASON_SEEK && fade?.handingOver == false) cancelFade()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                fade?.let { f ->
                    if (f.handingOver) return@let
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && mediaItem?.mediaId == f.toId) startHandover(f)
                    else cancelFade() // skipped, or the queue changed
                }
                // Remember where the song before was left, then continue this one where it was left.
                lastSong?.let { (id, pos, dur) ->
                    if (id != mediaItem?.mediaId && resumeMode().applies(dur)) ResumePoints.save(resumePrefs, id, pos, dur)
                }
                lastSong = null
                if (fade?.handingOver != true && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) continueWhereLeft(mediaItem)
                updateEvenVolume()
                // A brand-new queue started while shuffle is on: shuffle it starting from the chosen song.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && player.shuffleModeEnabled) {
                    shuffleFromCurrent()
                }
                savePosition()
                PlayerWidget.update(this@PlaybackService, player)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                    // Songs added, moved or removed mid-crossfade: the next song may have changed.
                    if (fade?.handingOver == false && player.nextMediaItemIndex.let { it == C.INDEX_UNSET || player.getMediaItemAt(it).mediaId != fade?.toId }) {
                        cancelFade()
                    }
                    saveQueue()
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (fade?.handingOver == false) cancelFade() // the next song may have changed
                if (shuffleModeEnabled) shuffleFromCurrent()
                savePosition()
            }

            override fun onRepeatModeChanged(repeatMode: Int) = savePosition()

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                getSharedPreferences("position", Context.MODE_PRIVATE).edit()
                    .putFloat("speed", playbackParameters.speed)
                    .putFloat("pitch", playbackParameters.pitch)
                    .apply()
            }

            override fun onPlayerError(error: PlaybackException) {
                // Skip songs that can't be played (deleted, unsupported format) instead of stopping.
                errorStreak++
                if (errorStreak < player.mediaItemCount && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })

        restoreQueue()
        updateEvenVolume()

        scope.launch {
            while (isActive) {
                delay(200)
                crossfadeTick()
                trackSong()
            }
        }
        scope.launch {
            while (isActive) {
                delay(10_000)
                if (player.isPlaying) {
                    savePosition()
                    rememberWhereLeft()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away keeps music going if it's playing; otherwise shut down.
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        activePlayer = null
        rememberWhereLeft()
        savePosition()
        clearSleep()
        effectsPrefs.unregisterOnSharedPreferenceChangeListener(effectsListener)
        equalizer?.release()
        bassBoost?.release()
        loudnessEnhancer?.release()
        fadePlayer?.release()
        libraryExecutor.shutdown()
        scope.cancel()
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    // ----- Equaliser -----

    private fun setUpEffects(audioSession: Int) {
        try {
            val eq = Equalizer(0, audioSession)
            equalizer = eq
            val bands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val presets = (0 until eq.numberOfPresets.toInt()).map { p ->
                eq.usePreset(p.toShort())
                eq.getPresetName(p.toShort()) to (0 until bands).map { eq.getBandLevel(it.toShort()).toInt() }
            }
            bassBoost = try {
                BassBoost(0, audioSession).takeIf { it.strengthSupported }
            } catch (e: Exception) {
                null
            }
            Effects.writeInfo(
                effectsPrefs,
                Effects.EqInfo(
                    available = true,
                    bandsHz = (0 until bands).map { eq.getCenterFreq(it.toShort()) / 1000 }, // milliHertz → Hz
                    minLevel = range[0].toInt(),
                    maxLevel = range[1].toInt(),
                    presets = presets,
                    bassBoost = bassBoost != null,
                ),
            )
        } catch (e: Exception) {
            // Some phones (and emulators) have no equaliser effect.
            equalizer = null
            Effects.writeInfo(effectsPrefs, Effects.EqInfo(available = false))
        }
        applyEffects()
    }

    private fun applyEffects() {
        val s = Effects.read(effectsPrefs)
        equalizer?.let { eq ->
            try {
                val range = eq.bandLevelRange
                if (s.preset in 0 until eq.numberOfPresets) {
                    eq.usePreset(s.preset.toShort())
                } else {
                    s.levels.forEachIndexed { band, level ->
                        if (band < eq.numberOfBands) eq.setBandLevel(band.toShort(), level.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                    }
                }
                eq.setEnabled(s.enabled)
            } catch (e: Exception) {
                // the effect was taken over by another app; nothing to do
            }
        }
        bassBoost?.let { b ->
            try {
                b.setStrength(s.bass.coerceIn(0, 1000).toShort())
                b.setEnabled(s.enabled && s.bass > 0)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    // ----- Sleep timer -----

    private fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        player.pauseAtEndOfMediaItems = false
        when {
            minutes > 0 -> {
                val until = System.currentTimeMillis() + minutes * 60_000L
                effectsPrefs.edit().putLong(Effects.KEY_SLEEP_UNTIL, until).putBoolean(Effects.KEY_SLEEP_END_OF_SONG, false).apply()
                sleepJob = scope.launch {
                    delay(minutes * 60_000L - 10_000L)
                    // Fade out over ten seconds rather than stopping abruptly.
                    for (step in 20 downTo 0) {
                        sleepFade = step / 20f
                        applyVolume()
                        delay(500)
                    }
                    player.pause()
                    clearSleep()
                }
            }
            minutes == -1 -> {
                cancelFade()
                player.pauseAtEndOfMediaItems = true
                effectsPrefs.edit().putLong(Effects.KEY_SLEEP_UNTIL, 0).putBoolean(Effects.KEY_SLEEP_END_OF_SONG, true).apply()
            }
            else -> clearSleep()
        }
    }

    private fun clearSleep() {
        sleepJob?.cancel()
        sleepJob = null
        if (::player.isInitialized) {
            player.pauseAtEndOfMediaItems = false
            sleepFade = 1f
            applyVolume()
        }
        effectsPrefs.edit().remove(Effects.KEY_SLEEP_UNTIL).remove(Effects.KEY_SLEEP_END_OF_SONG).apply()
    }

    // ----- Even volume -----

    private fun evenVolumeOn() = effectsPrefs.getBoolean(Effects.KEY_EVEN_VOLUME, false)

    /** Sets the level for the song now playing: measured once per song, then remembered. */
    private fun updateEvenVolume() {
        evenVolumeJob?.cancel()
        val item = player.currentMediaItem
        if (!evenVolumeOn() || item == null) {
            applyGain(null, 0f)
            return
        }
        val id = item.mediaId
        val title = item.mediaMetadata.title?.toString().orEmpty()
        val known = Loudness.cached(this, id)
        if (known != null) applyGain(title, Loudness.gainFor(known))
        evenVolumeJob = scope.launch {
            if (known == null) {
                val level = withContext(Dispatchers.IO) { Loudness.level(this@PlaybackService, id) }
                if (player.currentMediaItem?.mediaId == id) applyGain(title, level?.let(Loudness::gainFor) ?: 0f)
            }
            // Measure the next song now, so it starts at the right level.
            val next = player.nextMediaItemIndex
            if (next != C.INDEX_UNSET) {
                val nextId = player.getMediaItemAt(next).mediaId
                withContext(Dispatchers.IO) { Loudness.level(this@PlaybackService, nextId) }
            }
        }
    }

    /** How much even volume is raising the current song, in dB (0 when it's lowering it or off). */
    private var evenBoostDb = 0f

    /** Turns down with the player's volume; turns up with the loudness enhancer (which won't clip). */
    private fun applyGain(title: String?, db: Float) {
        evenVolume = if (db < 0) 10f.pow(db / 20f) else 1f
        applyVolume()
        evenBoostDb = db.coerceAtLeast(0f)
        applyLoudness()
        val e = effectsPrefs.edit()
        if (title == null) e.remove(Effects.KEY_EVEN_VOLUME_NOW)
        else e.putString(Effects.KEY_EVEN_VOLUME_NOW, "$title|" + String.format(Locale.US, "%.1f", db))
        e.apply()
    }

    /**
     * Everything that makes the sound louder than the file: even volume raising a quiet song, plus
     * the volume boost (200% = +6 dB). The loudness enhancer limits peaks, so loud parts don't clip.
     */
    private fun applyLoudness() {
        val boostDb = (20 * log10(Effects.boostPercent(effectsPrefs) / 100.0)).toFloat()
        val total = evenBoostDb + boostDb
        loudnessEnhancer?.let {
            try {
                it.setTargetGain((total * 100).roundToInt())
                it.enabled = total > 0.01f
            } catch (e: Exception) {
                // effect taken over by another app
            }
        }
    }

    /** The volume a song should play at for even volume (cuts only; used for the incoming song in a crossfade). */
    private fun evenVolumeFor(songId: String): Float {
        if (!evenVolumeOn()) return 1f
        val db = Loudness.cached(this, songId)?.let(Loudness::gainFor) ?: return 1f
        return if (db < 0) 10f.pow(db / 20f) else 1f
    }

    // ----- Crossfade -----

    private fun crossfadeMs(): Long = effectsPrefs.getInt(Effects.KEY_CROSSFADE, 0).coerceIn(0, 12) * 1000L

    private fun crossfadeTick() {
        val f = fade
        if (f == null) {
            val length = crossfadeMs()
            if (length == 0L || !player.isPlaying || player.pauseAtEndOfMediaItems) return
            if (player.repeatMode == Player.REPEAT_MODE_ONE) return
            val duration = player.duration
            // Not worth it for very short songs.
            if (duration == C.TIME_UNSET || duration < length * 2 + 2_000) return
            val remaining = duration - player.currentPosition
            if (remaining > length || remaining < 600) return
            val next = player.nextMediaItemIndex
            if (next == C.INDEX_UNSET) return
            startFade(next, remaining)
            return
        }
        if (f.handingOver) return
        val remaining = (player.duration - player.currentPosition).coerceAtLeast(0)
        val t = (1f - remaining.toFloat() / f.lengthMs).coerceIn(0f, 1f)
        // Equal-power curves, so the blend doesn't dip in the middle.
        crossfadeFade = cos(t * PI / 2).toFloat()
        applyVolume()
        fadePlayer?.volume = sin(t * PI / 2).toFloat() * evenVolumeFor(f.toId)
    }

    private fun startFade(next: Int, remaining: Long) {
        val item = player.getMediaItemAt(next)
        val fp = fadePlayer ?: ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ false, // the main player holds focus; this one mustn't take it away
            )
            .build()
            .also {
                // Same audio session, so the equaliser applies to the incoming song too.
                it.setAudioSessionId(audioSession)
                it.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) = cancelFade()
                })
                fadePlayer = it
            }
        fp.setMediaItem(item)
        // A song with a saved place fades in from there.
        ResumePoints.get(resumePrefs, item.mediaId)?.takeIf { resumeMode().applies(it.durationMs) }?.let { fp.seekTo(it.positionMs) }
        fp.playbackParameters = player.playbackParameters // same speed and pitch
        fp.volume = 0f
        fp.prepare()
        fp.play()
        fade = Fade(item.mediaId, remaining.coerceAtLeast(600))
    }

    /**
     * The outgoing song has ended and the player moved on to the incoming one (from its start,
     * silently). Jump it to where the crossfade player has got to, then swap over.
     */
    private fun startHandover(f: Fade) {
        val fp = fadePlayer ?: return cancelFade()
        f.handingOver = true
        crossfadeFade = 0f
        applyVolume()
        player.seekTo(fp.currentPosition + 100)
        handoverJob = scope.launch {
            delay(60)
            var waited = 0
            while (waited < 3_000 && !(player.isPlaying && player.playbackState == Player.STATE_READY)) {
                delay(20)
                waited += 20
            }
            val from = fp.volume
            for (step in 1..10) {
                crossfadeFade = step / 10f
                applyVolume()
                fp.volume = from * (1f - step / 10f)
                delay(25)
            }
            endFade()
        }
    }

    private fun cancelFade() {
        handoverJob?.cancel()
        endFade()
    }

    private fun endFade() {
        fade = null
        handoverJob = null
        fadePlayer?.let {
            it.stop()
            it.clearMediaItems()
        }
        crossfadeFade = 1f
        if (::player.isInitialized) applyVolume()
    }

    // ----- Continue where you left off -----

    private val resumePrefs by lazy { ResumePoints.prefs(this) }

    private fun resumeMode() = Effects.resumeMode(effectsPrefs)

    /** The song playing, where it's got to, and its length; kept up to date for when it changes. */
    private var lastSong: Triple<String, Long, Long>? = null

    private fun trackSong() {
        val id = player.currentMediaItem?.mediaId ?: return
        val dur = player.duration
        if (id.isNotEmpty() && dur != C.TIME_UNSET && dur > 0) lastSong = Triple(id, player.currentPosition, dur)
    }

    /** Saves the current song's place (on pause, every 10 s, and when the player closes). */
    private fun rememberWhereLeft() {
        val id = player.currentMediaItem?.mediaId ?: return
        val dur = player.duration
        val pos = player.currentPosition
        if (dur == C.TIME_UNSET || dur <= 0 || !resumeMode().applies(dur)) return
        // Just after a song starts its place isn't known yet; don't let that wipe a saved one.
        if (pos < 10_000 && dur - pos >= 15_000) return
        ResumePoints.save(resumePrefs, id, pos, dur)
    }

    /** A song started from its beginning: jump to where it was left, if it was. */
    private fun continueWhereLeft(item: MediaItem?) {
        val id = item?.mediaId ?: return
        if (player.currentPosition > 3_000) return // already starting part-way (e.g. reopening the app)
        val point = ResumePoints.get(resumePrefs, id) ?: return
        if (!resumeMode().applies(point.durationMs)) return
        player.seekTo(point.positionMs)
        val title = item.mediaMetadata.title?.toString().orEmpty()
        effectsPrefs.edit().putString(Effects.KEY_RESUMED, "$title|${point.positionMs}|${System.currentTimeMillis()}").apply()
    }

    // ----- Speed & pitch -----

    private fun restorePlaybackSpeed() {
        val p = getSharedPreferences("position", Context.MODE_PRIVATE)
        val speed = p.getFloat("speed", 1f).coerceIn(0.25f, 3f)
        val pitch = p.getFloat("pitch", 1f).coerceIn(0.25f, 3f)
        if (speed != 1f || pitch != 1f) player.playbackParameters = PlaybackParameters(speed, pitch)
    }

    // ----- Queue helpers -----

    /** Queue indexes of the songs after the current one, in the order they'll play. */
    private fun upcoming(): MutableList<Int> {
        val out = mutableListOf<Int>()
        val tl = player.currentTimeline
        if (tl.isEmpty) return out
        var i = player.currentMediaItemIndex
        while (true) {
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (i == C.INDEX_UNSET || out.size >= tl.windowCount) break
            out += i
        }
        return out
    }

    /** Moves the song at Up next position [from] to position [to], with or without shuffle. */
    private fun moveUpcoming(from: Int, to: Int) {
        val up = upcoming()
        if (from !in up.indices || to !in up.indices || from == to) return
        val cur = player.currentMediaItemIndex
        if (player.shuffleModeEnabled) {
            // Keep what has played, then the new Up next order.
            val order = shuffledIndexes()
            val played = order.subList(0, order.indexOf(cur) + 1).toList()
            up.add(to, up.removeAt(from))
            applyShuffleOrder(played + up)
        } else {
            // Without shuffle, Up next is simply the queue after the current song.
            player.moveMediaItem(cur + 1 + from, cur + 1 + to)
        }
    }

    /** The queue indexes in the order they will play when shuffle is on. */
    private fun shuffledIndexes(): MutableList<Int> {
        val out = mutableListOf<Int>()
        val tl = player.currentTimeline
        if (tl.isEmpty) return out
        var i = tl.getFirstWindowIndex(true)
        while (i != C.INDEX_UNSET && out.size < tl.windowCount) {
            out += i
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        return out
    }

    private fun applyShuffleOrder(order: List<Int>) {
        if (order.size == player.mediaItemCount) {
            player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), Random.nextLong()))
        }
    }

    private fun shuffleFromCurrent() {
        val n = player.mediaItemCount
        val cur = player.currentMediaItemIndex
        if (n == 0 || cur !in 0 until n) return
        val rest = (0 until n).filter { it != cur }.shuffled()
        applyShuffleOrder(listOf(cur) + rest)
    }

    private fun enqueue(item: MediaItem, next: Boolean) {
        if (player.mediaItemCount == 0) {
            player.setMediaItem(item)
            player.prepare()
            player.play()
            return
        }
        val cur = player.currentMediaItemIndex
        val at = if (next) cur + 1 else player.mediaItemCount
        player.addMediaItem(at, item)
        if (player.shuffleModeEnabled) {
            // ExoPlayer drops new items at a random spot in the shuffle; put it where it was asked for.
            val order = shuffledIndexes()
            order.remove(at)
            if (next) order.add(order.indexOf(cur) + 1, at) else order.add(at)
            applyShuffleOrder(order)
        }
    }

    // ----- Remembering where you left off -----

    private fun saveQueue() {
        val arr = JSONArray()
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            val md = item.mediaMetadata
            arr.put(
                JSONObject()
                    .put("id", item.mediaId)
                    .put("title", md.title?.toString().orEmpty())
                    .put("artist", md.artist?.toString().orEmpty())
                    .put("album", md.albumTitle?.toString().orEmpty())
                    .put("art", md.artworkUri?.toString().orEmpty())
            )
        }
        getSharedPreferences("queue", Context.MODE_PRIVATE).edit().putString("items", arr.toString()).apply()
    }

    private fun savePosition() {
        getSharedPreferences("position", Context.MODE_PRIVATE).edit()
            .putInt("index", player.currentMediaItemIndex)
            .putLong("position", player.currentPosition)
            .putBoolean("shuffle", player.shuffleModeEnabled)
            .putInt("repeat", player.repeatMode)
            .apply()
    }

    private data class SavedQueue(val items: List<MediaItem>, val index: Int, val position: Long)

    private fun loadSavedQueue(): SavedQueue? = try {
        val arr = JSONArray(getSharedPreferences("queue", Context.MODE_PRIVATE).getString("items", "[]"))
        val pos = getSharedPreferences("position", Context.MODE_PRIVATE)
        val items = List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val song = songStub(o.getString("id"), o.optString("title"), o.optString("artist"), o.optString("album"))
            val art = o.optString("art")
            song.toMediaItem().let { item ->
                if (art.isEmpty()) item
                else item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(Uri.parse(art)).build()).build()
            }
        }
        val kept = keepTrusted(items, pos.getInt("index", 0))
        if (kept.items.isEmpty()) null
        else SavedQueue(kept.items, kept.startIndex.coerceIn(0, kept.items.size - 1), if (kept.startMoved) 0 else pos.getLong("position", 0).coerceAtLeast(0))
    } catch (e: Exception) {
        null
    }

    private class Kept(val items: MutableList<MediaItem>, val startIndex: Int, val startMoved: Boolean)

    /**
     * Only songs from your library or added folders get into the queue. [startIndex] is moved to
     * match; if the song it pointed at was dropped, the next kept one starts instead.
     */
    private fun keepTrusted(items: List<MediaItem>, startIndex: Int): Kept {
        val trusted = TrustedUris(this)
        val kept = ArrayList<MediaItem>(items.size)
        var start = startIndex
        var moved = false
        items.forEachIndexed { i, item ->
            val safe = item.trustedOrNull(trusted)
            if (safe != null) kept += safe
            else if (i < startIndex) start--
            else if (i == startIndex) moved = true
        }
        if (startIndex == C.INDEX_UNSET) return Kept(kept, C.INDEX_UNSET, false)
        return Kept(kept, start.coerceIn(0, (kept.size - 1).coerceAtLeast(0)), moved)
    }

    private fun restoreQueue() {
        val saved = loadSavedQueue() ?: return
        val pos = getSharedPreferences("position", Context.MODE_PRIVATE)
        player.repeatMode = pos.getInt("repeat", Player.REPEAT_MODE_OFF)
        player.shuffleModeEnabled = pos.getBoolean("shuffle", false)
        player.setMediaItems(saved.items, saved.index, saved.position)
        player.prepare()
    }

    // ----- Session callbacks -----

    private inner class SessionCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (isOwnApp(controller)) {
                val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                    .add(SessionCommand(CMD_ENQUEUE, Bundle.EMPTY))
                    .add(SessionCommand(CMD_SLEEP, Bundle.EMPTY))
                    .add(SessionCommand(CMD_MOVE_UPCOMING, Bundle.EMPTY))
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(commands)
                    .build()
            }
            // Android Auto (on the phone, or a car with Android built in) can browse the library and
            // pick songs.
            if (isCar(session, controller)) {
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS)
                    .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                    .build()
            }
            // Headphones, watch and lock-screen controls: play, pause, skip and seek.
            // The phone's own media controls (trusted system apps) may also resume the last queue;
            // any other app can't change what's queued or use the sleep timer / add-to-queue.
            val player = if (controller.isTrusted) {
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            } else {
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .removeAll(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)
                    .build()
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS)
                .setAvailablePlayerCommands(player)
                .build()
        }

        /** The app's own screens and its notification; the uid comes from Android, so it can't be faked. */
        private fun isOwnApp(controller: MediaSession.ControllerInfo) = controller.uid == Process.myUid()

        private fun isCar(session: MediaSession, controller: MediaSession.ControllerInfo) =
            session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)

        /** Only Isaialai itself and the car may list your music. */
        private fun mayBrowse(session: MediaSession, controller: MediaSession.ControllerInfo) =
            isOwnApp(controller) || isCar(session, controller)

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            if (!mayBrowse(session, browser)) Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            else Futures.immediateFuture(LibraryResult.ofItem(carLibrary.root, params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (!mayBrowse(session, browser)) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            return libraryExecutor.submit(Callable<LibraryResult<ImmutableList<MediaItem>>> {
                val all = carLibrary.children(parentId)
                    ?: return@Callable LibraryResult.ofError<ImmutableList<MediaItem>>(LibraryResult.RESULT_ERROR_BAD_VALUE)
                val from = (page.coerceAtLeast(0).toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()
                val to = (from + pageSize.coerceAtLeast(1)).coerceAtMost(all.size)
                LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
            })
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (!mayBrowse(session, browser)) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            return libraryExecutor.submit(Callable<LibraryResult<MediaItem>> {
                carLibrary.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                    ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            })
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (!isOwnApp(controller)) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            if (customCommand.customAction == CMD_ENQUEUE) {
                val item = songFromBundle(args).toMediaItem().trustedOrNull(TrustedUris(this@PlaybackService))
                    ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                enqueue(item, args.getBoolean("next"))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == CMD_MOVE_UPCOMING) {
                moveUpcoming(args.getInt("from", -1), args.getInt("to", -1))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == CMD_SLEEP) {
                setSleep(args.getInt("minutes"))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(keepTrusted(mediaItems, C.INDEX_UNSET).items)

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (isCar(mediaSession, controller)) {
                // A song picked in the car plays with the rest of its list; a spoken request plays what matches.
                val query = mediaItems.singleOrNull()?.requestMetadata?.searchQuery
                val single = mediaItems.singleOrNull()?.mediaId?.takeIf { it.isNotEmpty() }
                if (query != null || single != null) {
                    return libraryExecutor.submit(Callable<MediaSession.MediaItemsWithStartPosition> {
                        val (list, at) = if (query != null) carLibrary.search(query) to 0 else carLibrary.listContaining(single!!) ?: (emptyList<Song>() to 0)
                        val kept = keepTrusted(list.map { it.toMediaItem() }, at)
                        if (kept.items.isEmpty()) throw SecurityException("Nothing to play")
                        MediaSession.MediaItemsWithStartPosition(kept.items, kept.startIndex, 0)
                    })
                }
            }
            val kept = keepTrusted(mediaItems, startIndex)
            if (kept.items.isEmpty() && mediaItems.isNotEmpty()) {
                return Futures.immediateFailedFuture(SecurityException("Not songs on this phone"))
            }
            val position = if (kept.startMoved) 0 else startPositionMs
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(kept.items, kept.startIndex, position))
        }

        /** A headphone/car "play" press after the app was closed picks up the last queue. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = loadSavedQueue()
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(saved.items, saved.index, saved.position))
        }
    }
}

/** Gives the notification and lock screen each song's own cover art. */
@OptIn(UnstableApi::class)
private class ArtBitmapLoader(context: Context) : BitmapLoader {
    private val appContext = context.applicationContext
    private val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    private val fallback = DataSourceBitmapLoader(appContext)

    override fun supportsMimeType(mimeType: String): Boolean = fallback.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = fallback.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = executor.submit(Callable<Bitmap> {
        if (!TrustedUris(appContext).isArt(uri)) throw IOException("Not artwork from your music: $uri")
        ArtLoader.decode(appContext, uri, 512) ?: throw IOException("No artwork for $uri")
    })
}
