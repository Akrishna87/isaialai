package io.github.akrishna87.mymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import io.github.akrishna87.mymusic.MusicViewModel
import io.github.akrishna87.mymusic.Screen
import io.github.akrishna87.mymusic.Song

@Composable
fun NowPlaying(vm: MusicViewModel) {
    val song = vm.currentSong
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.showPlayer = false }) { Icon(Icons.Rounded.KeyboardArrowDown, "Close player") }
                Text(
                    if (showQueue) "UP NEXT" else "NOW PLAYING",
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    color = muted,
                )
                IconButton(onClick = { showQueue = !showQueue }) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, if (showQueue) "Show cover" else "Show up next", tint = if (showQueue) accent else muted)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                if (showQueue) {
                    UpNextList(vm)
                } else {
                    ArtImage(song, Modifier.aspectRatio(1f), sizePx = 800, shape = RoundedCornerShape(20.dp), iconSize = 96.dp)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        song?.title ?: vm.currentTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song?.subtitle ?: vm.currentArtist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (song != null) NowPlayingMenu(vm, song)
            }

            Spacer(Modifier.height(12.dp))
            val duration = vm.durationMs
            val fraction = dragging ?: if (duration > 0) (vm.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Slider(
                value = fraction,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { vm.seekTo((it * duration).toLong()) }
                    dragging = null
                },
                enabled = duration > 0,
            )
            Row {
                Text(formatTime(dragging?.let { (it * duration).toLong() } ?: vm.positionMs), style = MaterialTheme.typography.bodySmall, color = muted)
                Spacer(Modifier.weight(1f))
                Text(formatTime(duration), style = MaterialTheme.typography.bodySmall, color = muted)
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = vm::toggleShuffle) {
                    Icon(Icons.Rounded.Shuffle, if (vm.shuffle) "Shuffle on" else "Shuffle off", tint = if (vm.shuffle) accent else muted)
                }
                IconButton(onClick = { vm.previous() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous song", Modifier.size(36.dp))
                }
                FilledIconButton(onClick = vm::togglePlay, modifier = Modifier.size(76.dp)) {
                    Icon(
                        if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (vm.isPlaying) "Pause" else "Play",
                        Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = { vm.next() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Next song", Modifier.size(36.dp))
                }
                IconButton(onClick = vm::cycleRepeat) {
                    val (icon, label) = when (vm.repeatMode) {
                        Player.REPEAT_MODE_ONE -> Icons.Rounded.RepeatOne to "Repeating this song"
                        Player.REPEAT_MODE_ALL -> Icons.Rounded.Repeat to "Repeating all"
                        else -> Icons.Rounded.Repeat to "Repeat off"
                    }
                    Icon(icon, label, tint = if (vm.repeatMode == Player.REPEAT_MODE_OFF) muted else accent)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NowPlayingMenu(vm: MusicViewModel, song: Song) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Add to playlist…") }, onClick = { open = false; vm.playlistPickerFor = song })
            DropdownMenuItem(text = { Text("Go to album") }, onClick = { open = false; vm.open(Screen.Album(song.albumKey)) })
            DropdownMenuItem(text = { Text("Go to artist") }, onClick = { open = false; vm.open(Screen.Artist(song.artist)) })
            DropdownMenuItem(text = { Text("Go to folder") }, onClick = { open = false; vm.open(Screen.Folder(song.folder)) })
        }
    }
}

@Composable
private fun UpNextList(vm: MusicViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (vm.upNext.isEmpty()) {
            item {
                Text(
                    if (vm.repeatMode == Player.REPEAT_MODE_ALL) "Nothing after this song — the queue will start over."
                    else "Nothing after this song.",
                    Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(vm.upNext, key = { it.first }) { (index, id) ->
            val s = vm.songsById[id]
            Row(
                Modifier.fillMaxWidth().clickable { vm.jumpTo(index) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ArtImage(s, Modifier.size(44.dp))
                Column(Modifier.weight(1f)) {
                    Text(s?.title ?: "Song unavailable", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        s?.artist.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
