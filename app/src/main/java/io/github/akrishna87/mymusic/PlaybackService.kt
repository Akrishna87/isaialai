package io.github.akrishna87.mymusic

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Owns the player. Media3 turns the session into the media notification, lock-screen
 * controls and headphone/car/Bluetooth button handling, and keeps playback going while
 * the app is in the background.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        /** Custom command: add a song to the queue. Args: a song bundle plus "next" (Boolean). */
        const val CMD_ENQUEUE = "io.github.akrishna87.mymusic.ENQUEUE"

        /** The running player, for the home-screen widget's buttons; null when the service isn't running. */
        @Volatile
        var activePlayer: Player? = null
            private set

        /** Custom command: sleep timer. Arg "minutes": > 0 to stop after that long, -1 at the end of the song, 0 to cancel. */
        const val CMD_SLEEP = "io.github.akrishna87.mymusic.SLEEP"
    }

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val scope = MainScope()
    private var errorStreak = 0

    // Equaliser and bass boost, attached to this player's own audio stream.
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private val effectsPrefs by lazy { Effects.prefs(this) }
    private val effectsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != Effects.KEY_INFO && key != Effects.KEY_SLEEP_UNTIL && key != Effects.KEY_SLEEP_END_OF_SONG) applyEffects()
    }
    private var sleepJob: Job? = null

    override fun onCreate() {
        super.onCreate()
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
        val audioSession = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
        player.setAudioSessionId(audioSession)
        setUpEffects(audioSession)
        activePlayer = player
        effectsPrefs.registerOnSharedPreferenceChangeListener(effectsListener)
        clearSleep() // a timer can't outlive the service that was running it

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setBitmapLoader(CacheBitmapLoader(ArtBitmapLoader(this)))
            .setCallback(SessionCallback())
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) errorStreak = 0
                savePosition()
                PlayerWidget.update(this@PlaybackService, player)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // "End of this song" sleep timer: the player has just paused at the end of the song.
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) clearSleep()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // A brand-new queue started while shuffle is on: shuffle it starting from the chosen song.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && player.shuffleModeEnabled) {
                    shuffleFromCurrent()
                }
                savePosition()
                PlayerWidget.update(this@PlaybackService, player)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) saveQueue()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled) shuffleFromCurrent()
                savePosition()
            }

            override fun onRepeatModeChanged(repeatMode: Int) = savePosition()

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

        scope.launch {
            while (isActive) {
                delay(10_000)
                if (player.isPlaying) savePosition()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away keeps music going if it's playing; otherwise shut down.
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        activePlayer = null
        savePosition()
        clearSleep()
        effectsPrefs.unregisterOnSharedPreferenceChangeListener(effectsListener)
        equalizer?.release()
        bassBoost?.release()
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
                        player.volume = step / 20f
                        delay(500)
                    }
                    player.pause()
                    player.volume = 1f
                    clearSleep()
                }
            }
            minutes == -1 -> {
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
            player.volume = 1f
        }
        effectsPrefs.edit().remove(Effects.KEY_SLEEP_UNTIL).remove(Effects.KEY_SLEEP_END_OF_SONG).apply()
    }

    // ----- Queue helpers -----

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
        if (items.isEmpty()) null
        else SavedQueue(items, pos.getInt("index", 0).coerceIn(0, items.size - 1), pos.getLong("position", 0).coerceAtLeast(0))
    } catch (e: Exception) {
        null
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

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_ENQUEUE, Bundle.EMPTY))
                .add(SessionCommand(CMD_SLEEP, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_ENQUEUE) {
                enqueue(songFromBundle(args).toMediaItem(), args.getBoolean("next"))
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
            Futures.immediateFuture(mediaItems.map { it.withPlayableUri() }.toMutableList())

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
        ArtLoader.decode(appContext, uri, 512) ?: throw IOException("No artwork for $uri")
    })
}
