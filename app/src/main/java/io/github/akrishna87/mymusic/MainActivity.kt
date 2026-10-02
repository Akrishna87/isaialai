package io.github.akrishna87.mymusic

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.akrishna87.mymusic.ui.MusicApp
import io.github.akrishna87.mymusic.ui.MyMusicTheme
import io.github.akrishna87.mymusic.ui.ThemeSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Light status-bar icons to start with; the app switches them when the theme is light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        ThemeSettings.load(this)
        setContent {
            MyMusicTheme(ThemeSettings.mode, ThemeSettings.wallpaperColors) {
                MusicApp()
            }
        }
    }
}
