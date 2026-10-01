package io.github.akrishna87.mymusic.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import io.github.akrishna87.mymusic.Lyrics
import io.github.akrishna87.mymusic.MusicViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun NowPlaying(vm: MusicViewModel) {
    val song = vm.currentSong
    val color = rememberArtColor(song)
    // What fills the middle of the player: the cover, Up next or the lyrics.
    var panel by rememberSaveable { mutableStateOf(Panel.COVER) }
    var speedDialog by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = vm.durationMs
    // The cover eases back a little when paused.
    val artScale by animateFloatAsState(if (vm.isPlaying) 1f else 0.86f, spring(dampingRatio = 0.65f, stiffness = 220f), label = "artScale")
    val dim = Color.White.copy(alpha = 0.7f)
    var sleepDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Swipe down anywhere to close the player.
    val dragDown = remember { Animatable(0f) }
    // Swipe the cover sideways to change song.
    var swipeX by remember { mutableFloatStateOf(0f) }

    AlwaysDark {
        Box(
            Modifier
                .fillMaxSize()
                // The swipe is detected outside the offset below, so the finger is measured against
                // the screen rather than against the player that's moving along with it.
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragDown.value > size.height * 0.2f) vm.showPlayer = false
                            else scope.launch { dragDown.animateTo(0f) }
                        },
                        onDragCancel = { scope.launch { dragDown.animateTo(0f) } },
                    ) { change, dy ->
                        change.consume()
                        scope.launch { dragDown.snapTo((dragDown.value + dy).coerceAtLeast(0f)) }
                    }
                }
                .offset { IntOffset(0, dragDown.value.roundToInt()) }
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
                    targetState = panel,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                    label = "artOrQueue",
                ) { shown ->
                    if (shown == Panel.QUEUE) {
                        UpNextList(vm)
                    } else if (shown == Panel.LYRICS) {
                        LyricsPanel(vm)
                    } else {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectHorizontalDragGestures(
                                        onDragEnd = {
                                            val threshold = 80.dp.toPx()
                                            if (swipeX < -threshold) vm.next() else if (swipeX > threshold) vm.previousTrack()
                                            swipeX = 0f
                                        },
                                        onDragCancel = { swipeX = 0f },
                                    ) { change, dx ->
                                        change.consume()
                                        swipeX += dx
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            ArtImage(
                                song,
                                Modifier
                                    .aspectRatio(1f)
                                    .graphicsLayer {
                                        scaleX = artScale
                                        scaleY = artScale
                                        translationX = swipeX * 0.6f
                                        alpha = 1f - (abs(swipeX) / 1200f).coerceAtMost(0.4f)
                                    }
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
                    ToggleButton(Icons.Rounded.Shuffle, if (vm.shuffle) "Shuffle on" else "Shuffle off", on = vm.shuffle, onClick = vm::toggleShuffle)
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
                    val (repeatIcon, repeatLabel) = when (vm.repeatMode) {
                        Player.REPEAT_MODE_ONE -> Icons.Rounded.RepeatOne to "Repeating this song"
                        Player.REPEAT_MODE_ALL -> Icons.Rounded.Repeat to "Repeating all"
                        else -> Icons.Rounded.Repeat to "Repeat off"
                    }
                    ToggleButton(repeatIcon, repeatLabel, on = vm.repeatMode != Player.REPEAT_MODE_OFF, onClick = vm::cycleRepeat)
                }

                Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.showEqualizer = true }) {
                        Icon(
                            Icons.Rounded.Tune,
                            "Equalizer",
                            tint = if (vm.eqSettings.enabled) MaterialTheme.colorScheme.primary else dim,
                        )
                    }
                    SleepButton(vm, dim) { sleepDialog = true }
                    SpeedButton(vm, dim) { speedDialog = true }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { panel = if (panel == Panel.LYRICS) Panel.COVER else Panel.LYRICS }) {
                        Icon(
                            Icons.Rounded.Lyrics,
                            if (panel == Panel.LYRICS) "Hide lyrics" else "Show lyrics",
                            tint = if (panel == Panel.LYRICS) MaterialTheme.colorScheme.primary else dim,
                        )
                    }
                    IconButton(onClick = { panel = if (panel == Panel.QUEUE) Panel.COVER else Panel.QUEUE }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.QueueMusic,
                            if (panel == Panel.QUEUE) "Show cover" else "Show up next",
                            tint = if (panel == Panel.QUEUE) MaterialTheme.colorScheme.primary else dim,
                        )
                    }
                }
            }
        }
    }
    if (sleepDialog) SleepTimerDialog(vm) { sleepDialog = false }
    if (speedDialog) SpeedDialog(vm) { speedDialog = false }
}

private enum class Panel { COVER, QUEUE, LYRICS }

/**
 * Shuffle / repeat: clearly bright with a dot underneath when on, dimmed when off.
 * (A coloured icon alone disappears against covers of the same colour.)
 */
@Composable
private fun ToggleButton(icon: ImageVector, description: String, on: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, description, tint = if (on) Color.White else Color.White.copy(alpha = 0.5f), modifier = Modifier.size(26.dp))
            if (on) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = 12.dp)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                )
            }
        }
    }
}

@Composable
private fun SleepButton(vm: MusicViewModel, dim: Color, onClick: () -> Unit) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val left = (vm.sleepUntil - now).coerceAtLeast(0)
    val active = vm.sleepEndOfSong || left > 0
    val label = when {
        vm.sleepEndOfSong -> "End of song"
        left > 0 -> formatTime(left)
        else -> ""
    }
    Row(
        Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = if (active) "Sleep timer: $label left" else "Sleep timer" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = if (active) MaterialTheme.colorScheme.primary else dim)
        if (active) {
            Spacer(Modifier.width(6.dp))
            Text(label, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SleepTimerDialog(vm: MusicViewModel, onDismiss: () -> Unit) {
    val active = vm.sleepEndOfSong || vm.sleepUntil > System.currentTimeMillis()
    val choices = listOf("15 minutes" to 15, "30 minutes" to 30, "45 minutes" to 45, "1 hour" to 60, "End of this song" to -1)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Elevated,
        icon = { Icon(Icons.Rounded.Bedtime, contentDescription = null) },
        title = { Text("Sleep timer") },
        text = {
            Column {
                Text("Music fades out and stops after:", color = Palette.SubText, modifier = Modifier.padding(bottom = 8.dp))
                choices.forEach { (label, minutes) ->
                    Text(
                        label,
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDismiss(); vm.setSleepTimer(minutes) }
                            .padding(vertical = 13.dp, horizontal = 8.dp),
                        fontSize = 16.sp,
                    )
                }
                if (active) {
                    Text(
                        "Turn off timer",
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDismiss(); vm.setSleepTimer(0) }
                            .padding(vertical = 13.dp, horizontal = 8.dp),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The songs coming up. Drag a song by its handle to move it, or swipe it left to take it out
 * of the queue. Tap one to play it now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpNextList(vm: MusicViewModel) {
    val rowHeight = 64.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    val items = vm.upNext
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    fun dropTarget(from: Int) = (from + (dragOffset / rowPx).roundToInt()).coerceIn(0, (vm.upNext.size - 1).coerceAtLeast(0))
    val target = dragFrom?.let { dropTarget(it) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("Up next", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 2.dp))
            if (items.size > 1) {
                Text("Drag ≡ to move a song, swipe left to remove it.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        if (items.isEmpty()) {
            item {
                Text(
                    if (vm.repeatMode == Player.REPEAT_MODE_ALL) "Nothing after this song — the queue will start over."
                    else "Nothing after this song.",
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
        itemsIndexed(items, key = { _, it -> "${it.first}:${it.second}" }) { pos, (index, id) ->
            val s = vm.songsById[id]
            val title = s?.title ?: "Song unavailable"
            // While dragging, the dragged row follows the finger and the rows it passes make room.
            val from = dragFrom
            val shift = when {
                from == null || target == null -> 0f
                pos == from -> dragOffset
                from < target && pos in (from + 1)..target -> -rowPx
                from > target && pos in target until from -> rowPx
                else -> 0f
            }
            val dismiss = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value == SwipeToDismissBoxValue.EndToStart) {
                        vm.removeFromQueue(index)
                        true
                    } else {
                        false
                    }
                },
            )
            SwipeToDismissBox(
                state = dismiss,
                enableDismissFromStartToEnd = false,
                modifier = Modifier.zIndex(if (pos == from) 1f else 0f).graphicsLayer { translationY = shift },
                backgroundContent = {
                    if (dismiss.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                        Box(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color(0xFFB3261E)).padding(end = 20.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = null, tint = Color.White)
                        }
                    }
                },
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (pos == from) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { vm.jumpTo(index) }
                        .semantics { contentDescription = "Up next: $title" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ArtImage(s, Modifier.size(46.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s?.artist.orEmpty(), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(
                        Icons.Rounded.DragHandle,
                        "Reorder $title",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier
                            .size(48.dp)
                            .pointerInput(pos, items.size) {
                                // Up/down only, so a sideways swipe starting here still removes the song.
                                detectVerticalDragGestures(
                                    onDragStart = { dragFrom = pos; dragOffset = 0f },
                                    onDragEnd = {
                                        val start = dragFrom
                                        val end = start?.let { dropTarget(it) }
                                        dragFrom = null
                                        dragOffset = 0f
                                        if (start != null && end != null) vm.moveUpNext(start, end)
                                    },
                                    onDragCancel = { dragFrom = null; dragOffset = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    dragOffset += amount
                                }
                            }
                            .padding(12.dp),
                    )
                }
            }
        }
    }
}

/** The song's lyrics: timed ones scroll along with the music (tap a line to jump there). */
@Composable
private fun LyricsPanel(vm: MusicViewModel) {
    val song = vm.currentSong
    var lyrics by remember(song?.id, vm.lrcFiles) { mutableStateOf<Lyrics?>(null) }
    var loaded by remember(song?.id, vm.lrcFiles) { mutableStateOf(false) }
    LaunchedEffect(song?.id, vm.lrcFiles) {
        if (song != null) lyrics = vm.loadLyrics(song)
        loaded = true
    }
    val found = lyrics
    when {
        !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        found == null -> Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.Lyrics, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("No lyrics for this song", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Isaialai shows lyrics saved inside the song, or from an .lrc file with the same name in the same folder. " +
                    "For .lrc files, add that folder in Library → Folders → Add a folder.",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
        found.synced -> SyncedLyrics(vm, found)
        else -> LazyColumn(Modifier.fillMaxSize()) {
            item { Text(found.source.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f)) }
            items(found.lines) { line ->
                Text(line.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp, modifier = Modifier.padding(vertical = 3.dp))
            }
        }
    }
}

@Composable
private fun SyncedLyrics(vm: MusicViewModel, lyrics: Lyrics) {
    val listState = rememberLazyListState()
    // A little ahead, so a line lights up as it starts rather than just after.
    val current = lyrics.lineAt(vm.positionMs + 300)
    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem((current - 1).coerceAtLeast(0))
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        itemsIndexed(lyrics.lines) { i, line ->
            Text(
                line.text.ifBlank { "♪" },
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { vm.seekTo(line.timeMs) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                fontSize = 24.sp,
                lineHeight = 31.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    i == current -> Color.White
                    i < current -> Color.White.copy(alpha = 0.55f)
                    else -> Color.White.copy(alpha = 0.35f)
                },
            )
        }
        item { Spacer(Modifier.height(200.dp)) }
    }
}

@Composable
private fun SpeedButton(vm: MusicViewModel, dim: Color, onClick: () -> Unit) {
    val changed = vm.speed != 1f || vm.pitch != 1f
    Text(
        speedLabel(vm.speed),
        Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .semantics { contentDescription = "Playback speed" },
        color = if (changed) MaterialTheme.colorScheme.primary else dim,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
    )
}

/** 1× · 1.25× · 1.5× */
private fun speedLabel(speed: Float): String =
    String.format(java.util.Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "×"

private fun semitones(pitch: Float): Int = (12 * ln(pitch.toDouble()) / ln(2.0)).roundToInt()

@Composable
private fun SpeedDialog(vm: MusicViewModel, onDismiss: () -> Unit) {
    var speed by remember { mutableFloatStateOf(vm.speed) }
    var semis by remember { mutableIntStateOf(semitones(vm.pitch)) }
    fun apply() = vm.setSpeedAndPitch(speed, 2.0.pow(semis / 12.0).toFloat())
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Elevated,
        icon = { Icon(Icons.Rounded.Speed, contentDescription = null) },
        title = { Text("Speed & pitch") },
        text = {
            Column {
                Text("Speed: ${speedLabel(speed)}", fontWeight = FontWeight.SemiBold)
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { v ->
                        FilterChip(
                            selected = speed == v,
                            onClick = { speed = v; apply() },
                            label = { Text(speedLabel(v)) },
                        )
                    }
                }
                Slider(
                    value = speed,
                    onValueChange = { speed = (it * 20).roundToInt() / 20f },
                    onValueChangeFinished = { apply() },
                    valueRange = 0.5f..2f,
                    steps = 29,
                    modifier = Modifier.semantics { contentDescription = "Speed" },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Pitch: " + when {
                        semis == 0 -> "normal"
                        semis > 0 -> "+$semis semitone${if (semis == 1) "" else "s"}"
                        else -> "$semis semitone${if (semis == -1) "" else "s"}"
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = semis.toFloat(),
                    onValueChange = { semis = it.roundToInt() },
                    onValueChangeFinished = { apply() },
                    valueRange = -6f..6f,
                    steps = 11,
                    modifier = Modifier.semantics { contentDescription = "Pitch" },
                )
                Text(
                    "Speed changes how fast songs play without changing the key. Pitch changes the key (for singing along) without changing the speed. Both stay until you reset them.",
                    color = Palette.SubText,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = { speed = 1f; semis = 0; apply() }) { Text("Reset") }
        },
    )
}
