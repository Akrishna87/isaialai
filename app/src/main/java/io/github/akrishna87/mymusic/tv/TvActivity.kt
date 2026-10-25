package io.github.akrishna87.mymusic.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import io.github.akrishna87.mymusic.ui.MyMusicTheme
import io.github.akrishna87.mymusic.ui.ThemeMode
import io.github.akrishna87.mymusic.ui.ThemeSettings

/**
 * Isaialai on a Google TV / Android TV: plays the music on your phone (shared from the phone's
 * Settings → Play on TV app), browsed and controlled with the TV remote.
 */
class TvActivity : ComponentActivity() {
    private val vm: TvViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSettings.load(this)
        setContent {
            MyMusicTheme(ThemeMode.DARK, wallpaperColors = false, accent = ThemeSettings.accent) {
                TvApp(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the TV's home screen: the phone may have started sharing meanwhile.
        if (vm.screen == TvScreen.Connect && vm.link != null) vm.reconnect()
    }

    /** The remote's media buttons work on every screen. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (vm.screen != TvScreen.Main || vm.currentId == null) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> vm.togglePlay()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (!vm.isPlaying) vm.togglePlay()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (vm.isPlaying) vm.togglePlay()
            KeyEvent.KEYCODE_MEDIA_NEXT -> vm.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> vm.previous()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> vm.forward()
            KeyEvent.KEYCODE_MEDIA_REWIND -> vm.rewind()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
