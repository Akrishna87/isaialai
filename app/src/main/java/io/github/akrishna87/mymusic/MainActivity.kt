package io.github.akrishna87.mymusic

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.akrishna87.mymusic.ui.MusicApp
import io.github.akrishna87.mymusic.ui.MyMusicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so keep the status and navigation bar icons light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            MyMusicTheme {
                MusicApp()
            }
        }
    }
}
