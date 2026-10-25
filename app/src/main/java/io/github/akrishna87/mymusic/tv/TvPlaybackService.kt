package io.github.akrishna87.mymusic.tv

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The TV app's player: plays songs straight from the paired phone (see [PhoneLink]), keeps
 * playing when you leave the app, and answers the TV remote's play/pause and skip buttons.
 */
@OptIn(UnstableApi::class)
class TvPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var errorStreak = 0

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            // Fetch well ahead (up to 5 minutes), so a Wi-Fi hiccup doesn't stop the music.
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(60_000, 300_000, 1_500, 3_000).build())
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) errorStreak = 0
            }

            override fun onPlayerError(error: PlaybackException) {
                // A song the phone can't send (deleted meanwhile): skip it. If several fail in a row,
                // the phone has probably stopped sharing; stop and let the screen say so.
                errorStreak++
                if (errorStreak < 3 && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setId("tv")
            .setSessionActivity(openApp)
            .setCallback(Callback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player ?: return
        if (!p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            // The TV's own remote and system controls may play, pause and skip; only Isaialai's
            // screens choose what's queued.
            if (controller.uid == Process.myUid()) return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(
                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .removeAll(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)
                        .build(),
                )
                .build()
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val link = PhoneLink.saved(this@TvPlaybackService)
                ?: return Futures.immediateFailedFuture(IllegalStateException("No phone"))
            return Futures.immediateFuture(mediaItems.mapTo(ArrayList()) { withAddress(it, link) })
        }
    }

    /** Items arrive with just the song id; the address to play it from is added here. */
    private fun withAddress(item: MediaItem, link: PhoneLink): MediaItem =
        item.buildUpon()
            .setUri(link.song(item.mediaId))
            .setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(Uri.parse(link.art(item.mediaId))).build())
            .build()
}
