package io.github.akrishna87.mymusic.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import io.github.akrishna87.mymusic.MusicViewModel

@Composable
fun NowPlaying(vm: MusicViewModel) {
    val song = vm.currentSong
    val color = rememberArtColor(song)
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = vm.durationMs
    // The cover eases back a little when paused.
    val artScale by animateFloatAsState(if (vm.isPlaying) 1f else 0.86f, spring(dampingRatio = 0.65f, stiffness = 220f), label = "artScale")
    val dim = Color.White.copy(alpha = 0.7f)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(color.deep(0.1f), color.deep(0.65f), Color(0xFF070709))))
            // Swallow taps so they don't reach the screen underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.showPlayer = false }) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Close player", modifier = Modifier.size(32.dp))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PLAYING FROM", style = MaterialTheme.typography.labelSmall, color = dim)
                    Text(vm.playingFrom, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More options") }
                    if (song != null) SongMenu(vm, song, expanded = menu, onDismiss = { menu = false })
                }
            }

            AnimatedContent(
                targetState = showQueue,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 20.dp),
                contentAlignment = Alignment.Center,
                label = "artOrQueue",
            ) { queue ->
                if (queue) {
                    UpNextList(vm)
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ArtImage(
                            song,
                            Modifier
                                .aspectRatio(1f)
                                .graphicsLayer { scaleX = artScale; scaleY = artScale }
                                .shadow(30.dp, RoundedCornerShape(10.dp)),
                            sizePx = 900,
                            shape = RoundedCornerShape(10.dp),
                            iconSize = 110.dp,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        song?.title ?: vm.currentTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(song?.artist ?: vm.currentArtist, color = dim, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (song != null) {
                    val liked = vm.isLiked(song)
                    IconButton(onClick = { vm.toggleLike(song) }) {
                        Icon(
                            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            if (liked) "Remove from Liked songs" else "Like",
                            tint = if (liked) MaterialTheme.colorScheme.primary else Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            SeekBar(
                fraction = if (duration > 0) vm.positionMs.toFloat() / duration else 0f,
                onSeek = { vm.seekTo((it * duration).toLong()) },
                onDrag = { dragFraction = it },
                enabled = duration > 0,
            )
            Row {
                Text(formatTime(dragFraction?.let { (it * duration).toLong() } ?: vm.positionMs), color = dim, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(formatTime(duration), color = dim, fontSize = 12.sp)
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = vm::toggleShuffle) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        if (vm.shuffle) "Shuffle on" else "Shuffle off",
                        tint = if (vm.shuffle) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
                IconButton(onClick = { vm.previous() }, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous song", modifier = Modifier.size(46.dp))
                }
                Surface(onClick = vm::togglePlay, shape = CircleShape, color = Color.White, contentColor = Color.Black, modifier = Modifier.size(74.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (vm.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(42.dp),
                        )
                    }
                }
                IconButton(onClick = { vm.next() }, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Next song", modifier = Modifier.size(46.dp))
                }
                IconButton(onClick = vm::cycleRepeat) {
                    val (icon, label) = when (vm.repeatMode) {
                        Player.REPEAT_MODE_ONE -> Icons.Rounded.RepeatOne to "Repeating this song"
                        Player.REPEAT_MODE_ALL -> Icons.Rounded.Repeat to "Repeating all"
                        else -> Icons.Rounded.Repeat to "Repeat off"
                    }
                    Icon(
                        icon,
                        label,
                        tint = if (vm.repeatMode == Player.REPEAT_MODE_OFF) Color.White else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { showQueue = !showQueue }) {
                    Icon(
                        Icons.AutoMirrored.Rounded.QueueMusic,
                        if (showQueue) "Show cover" else "Show up next",
                        tint = if (showQueue) MaterialTheme.colorScheme.primary else dim,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpNextList(vm: MusicViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("Up next", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (vm.upNext.isEmpty()) {
            item {
                Text(
                    if (vm.repeatMode == Player.REPEAT_MODE_ALL) "Nothing after this song — the queue will start over."
                    else "Nothing after this song.",
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
        items(vm.upNext, key = { it.first }) { (index, id) ->
            val s = vm.songsById[id]
            Row(
                Modifier.fillMaxWidth().clickable { vm.jumpTo(index) }.padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ArtImage(s, Modifier.size(46.dp))
                Column(Modifier.weight(1f)) {
                    Text(s?.title ?: "Song unavailable", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s?.artist.orEmpty(), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
