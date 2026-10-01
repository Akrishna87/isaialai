package io.github.akrishna87.mymusic

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.media3.common.Player
import androidx.media3.session.MediaButtonReceiver
import java.util.concurrent.Executors

/** Home-screen widget: cover, title, artist and ⏮ ⏯ ⏭. */
class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Before anything has played since the phone started, show the last song we knew about.
        render(context, loadState(context), null)
    }

    private data class State(val title: String, val artist: String, val playing: Boolean, val art: String?)

    companion object {
        const val ACTION_PLAY_PAUSE = "io.github.akrishna87.mymusic.widget.PLAY_PAUSE"
        const val ACTION_NEXT = "io.github.akrishna87.mymusic.widget.NEXT"
        const val ACTION_PREVIOUS = "io.github.akrishna87.mymusic.widget.PREVIOUS"

        private val worker = Executors.newSingleThreadExecutor()

        /** Called by the player service whenever the song or play/pause state changes. */
        fun update(context: Context, player: Player) {
            val app = context.applicationContext
            if (!hasWidgets(app)) return
            val md = player.currentMediaItem?.mediaMetadata
            val state = State(
                title = md?.title?.toString().orEmpty(),
                artist = md?.artist?.toString().orEmpty(),
                playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
                art = md?.artworkUri?.toString(),
            )
            saveState(app, state)
            worker.execute {
                val art = state.art?.let { ArtLoader.decode(app, Uri.parse(it), 300) }?.let { rounded(it, 24f) }
                render(app, state, art)
            }
        }

        private fun hasWidgets(context: Context) =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, PlayerWidget::class.java)).isNotEmpty()

        private fun saveState(context: Context, s: State) {
            context.getSharedPreferences("widget", Context.MODE_PRIVATE).edit()
                .putString("title", s.title).putString("artist", s.artist).putString("art", s.art).apply()
        }

        private fun loadState(context: Context): State {
            val p = context.getSharedPreferences("widget", Context.MODE_PRIVATE)
            return State(p.getString("title", "").orEmpty(), p.getString("artist", "").orEmpty(), false, p.getString("art", null))
        }

        private fun render(context: Context, s: State, art: Bitmap?) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_player)
            views.setTextViewText(R.id.widget_title, s.title.ifEmpty { context.getString(R.string.app_name) })
            views.setTextViewText(R.id.widget_artist, s.artist.ifEmpty { "Tap to open" })
            if (art != null) views.setImageViewBitmap(R.id.widget_art, art)
            else views.setImageViewResource(R.id.widget_art, R.drawable.widget_art_placeholder)
            views.setImageViewResource(R.id.widget_play, if (s.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
            views.setContentDescription(R.id.widget_play, if (s.playing) "Pause" else "Play")

            val openApp = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, openApp)
            views.setOnClickPendingIntent(R.id.widget_prev, action(context, ACTION_PREVIOUS, 1))
            views.setOnClickPendingIntent(R.id.widget_play, action(context, ACTION_PLAY_PAUSE, 2))
            views.setOnClickPendingIntent(R.id.widget_next, action(context, ACTION_NEXT, 3))
            manager.updateAppWidget(ids, views)
        }

        private fun action(context: Context, action: String, request: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, request,
                Intent(context, WidgetActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun rounded(src: Bitmap, radius: Float): Bitmap {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(
                    if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src,
                    Shader.TileMode.CLAMP, Shader.TileMode.CLAMP,
                )
            }
            Canvas(out).drawRoundRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), radius, radius, paint)
            return out
        }
    }
}

/** Handles the widget's buttons. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val player = PlaybackService.activePlayer
        if (player == null) {
            // Nothing is running: "play" resumes the last queue through Media3's media-button handling.
            if (intent.action == PlayerWidget.ACTION_PLAY_PAUSE) {
                val press = Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setComponent(ComponentName(context, MediaButtonReceiver::class.java))
                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                MediaButtonReceiver().onReceive(context, press)
            }
            return
        }
        when (intent.action) {
            PlayerWidget.ACTION_PLAY_PAUSE ->
                if (player.isPlaying) player.pause()
                else {
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.play()
                }
            PlayerWidget.ACTION_NEXT -> player.seekToNextMediaItem()
            PlayerWidget.ACTION_PREVIOUS -> player.seekToPrevious()
        }
    }
}
