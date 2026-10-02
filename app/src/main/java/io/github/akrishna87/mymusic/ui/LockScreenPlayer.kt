package io.github.akrishna87.mymusic.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.mymusic.MusicViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Whether the phone is locked right now: checked the moment the app comes on screen (so waking
 * the phone never shows the library first), then twice a second while it stays there.
 */
@Composable
fun rememberPhoneLocked(): Boolean {
    val context = LocalContext.current
    val keyguard = remember { context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val locked by produceState(keyguard.isKeyguardLocked, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = keyguard.isKeyguardLocked
                delay(500)
            }
        }
    }
    return locked
}

/** Lets the app's window show over the lock screen (or stops it). */
fun Activity.showOverLockScreen(on: Boolean) {
    if (Build.VERSION.SDK_INT >= 27) {
        setShowWhenLocked(on)
    } else {
        @Suppress("DEPRECATION")
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
    }
}

/**
 * What shows over the lock screen: the cover, the song and the controls, and nothing else
 * (no library, playlists or settings until the phone is unlocked).
 */
@Composable
fun LockScreenPlayer(vm: MusicViewModel) {
    val context = LocalContext.current
    val song = vm.currentSong
    val color = rememberArtColor(song)
    val duration = vm.durationMs
    BackHandler {} // Back does nothing here; unlock to leave.
    AlwaysDark {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(color.deep(0.15f), color.deep(0.7f), Color(0xFF070709))))
                .semantics { contentDescription = "Lock screen player" },
        ) {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("ISAIALAI", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.weight(0.6f))
                ArtImage(
                    song,
                    Modifier.fillMaxWidth().aspectRatio(1f).shadow(30.dp, RoundedCornerShape(12.dp)),
                    sizePx = 900,
                    shape = RoundedCornerShape(12.dp),
                    iconSize = 110.dp,
                )
                Spacer(Modifier.height(28.dp))
                Text(
                    song?.title ?: vm.currentTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    song?.artist ?: vm.currentArtist,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(18.dp))
                SeekBar(
                    fraction = if (duration > 0) vm.positionMs.toFloat() / duration else 0f,
                    onSeek = { vm.seekTo((it * duration).toLong()) },
                    enabled = duration > 0,
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatTime(vm.positionMs), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(duration), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    IconButton(onClick = { vm.previous() }, modifier = Modifier.size(64.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "Previous song", modifier = Modifier.size(46.dp))
                    }
                    Surface(onClick = vm::togglePlay, shape = CircleShape, color = Color.White, contentColor = Color.Black, modifier = Modifier.size(78.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (vm.isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(44.dp),
                            )
                        }
                    }
                    IconButton(onClick = { vm.next() }, modifier = Modifier.size(64.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Next song", modifier = Modifier.size(46.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    val activity = context as? Activity ?: return@TextButton
                    val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    keyguard.requestDismissKeyguard(activity, null)
                }) {
                    Icon(Icons.Rounded.LockOpen, contentDescription = null, tint = Color.White.copy(alpha = 0.8f))
                    Spacer(Modifier.width(8.dp))
                    Text("Unlock to open Isaialai", color = Color.White.copy(alpha = 0.8f))
                }
            }
        }
    }
}
