package io.github.akrishna87.mymusic.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.mymusic.ui.BrandGradient
import io.github.akrishna87.mymusic.ui.Palette
import io.github.akrishna87.mymusic.ui.UpdateCard
import kotlinx.coroutines.delay

@Composable
fun TvApp(vm: TvViewModel) {
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        when (val s = vm.screen) {
            TvScreen.Connect -> ConnectScreen(vm)
            is TvScreen.Pairing -> PairingScreen(vm, s)
            TvScreen.Main -> if (vm.showPlayer) PlayerScreen(vm) else BrowseScreen(vm)
        }
    }
}

// ----- Building blocks: everything is reached with the remote's arrows, so focus must show -----

/** Something to select with the remote: grows a little and lights up when it has focus. */
@Composable
private fun TvSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "focus")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (focused) Palette.Highlight else Color.Transparent)
            .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) { content(focused) }
}

@Composable
private fun TvButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvSurface(onClick, modifier, RoundedCornerShape(50)) { focused ->
        Row(
            Modifier
                .background(if (focused) MaterialTheme.colorScheme.primary else Palette.Elevated2)
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = if (focused) Palette.OnAccent else Palette.Text)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = if (focused) Palette.OnAccent else Palette.Text)
        }
    }
}

@Composable
private fun TvIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, big: Boolean = false, on: Boolean = false) {
    TvSurface(onClick, modifier, CircleShape) { focused ->
        Box(
            Modifier
                .size(if (big) 84.dp else 60.dp)
                .background(if (focused) MaterialTheme.colorScheme.primary else if (big) Palette.Elevated2 else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(if (big) 48.dp else 32.dp),
                tint = when {
                    focused -> Palette.OnAccent
                    on -> MaterialTheme.colorScheme.primary
                    else -> Palette.Text
                },
            )
        }
    }
}

/** Puts focus on [requester] once it's on screen (and again when [key] changes). */
@Composable
private fun FocusOnShow(requester: FocusRequester, key: Any?) {
    LaunchedEffect(key) {
        // The item may take a frame or two to be laid out.
        repeat(10) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

// ----- Connecting to the phone -----

@Composable
private fun ConnectScreen(vm: TvViewModel) {
    val phones = vm.finder.phones
    LaunchedEffect(phones) { phones.forEach(vm::phoneFound) }
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var updates by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val typeButton = remember { FocusRequester() }
    FocusOnShow(if (phones.isEmpty()) typeButton else first, phones.isEmpty())

    Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 48.dp), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(BrandGradient).padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("Isaialai", color = Color.White, fontWeight = FontWeight.Black, fontSize = 30.sp)
            }
            Text("Play your phone's music on this TV", fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp)
            Text(
                "1.  On your phone, open Isaialai → Settings → Play on TV app, and turn on Share with your TV.",
                fontSize = 20.sp,
                color = Palette.SubText,
            )
            Text("2.  Pick your phone here.", fontSize = 20.sp, color = Palette.SubText)
            Text("3.  The TV shows a code. On the phone, check it matches and tap Allow (only the first time).", fontSize = 20.sp, color = Palette.SubText)
            vm.problem?.let { Text(it, fontSize = 20.sp, color = Palette.Coral) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Phones on your Wi-Fi", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (phones.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(14.dp))
                    Text("Looking for phones sharing their music…", fontSize = 18.sp, color = Palette.SubText)
                }
            }
            phones.forEachIndexed { i, phone ->
                TvSurface(onClick = { vm.pair(phone) }, modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(phone.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (vm.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(14.dp))
                    Text("Connecting…", fontSize = 18.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            TvButton("Type the phone's address", Icons.Rounded.Keyboard, { typing = !typing }, Modifier.focusRequester(typeButton))
            if (typing) {
                Text("The phone shows it under Settings → Play on TV app, like 192.168.1.5:47123.", fontSize = 16.sp, color = Palette.SubText)
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.take(60) },
                    singleLine = true,
                    label = { Text("Phone's address") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { vm.pairTyped(typed) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                TvButton("Connect", Icons.Rounded.Link, { vm.pairTyped(typed) })
            }
            TvButton("Check for updates", Icons.Rounded.SystemUpdate, { updates = !updates })
            if (updates) UpdateCard()
        }
    }
}

@Composable
private fun PairingScreen(vm: TvViewModel, s: TvScreen.Pairing) {
    BackHandler { vm.cancelPairing() }
    val cancel = remember { FocusRequester() }
    FocusOnShow(cancel, s)
    Column(
        Modifier.fillMaxSize().padding(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("On ${s.phone}, tap Allow", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Check the phone shows this code:", fontSize = 22.sp, color = Palette.SubText)
        Spacer(Modifier.height(16.dp))
        Text(s.code, fontSize = 96.sp, fontWeight = FontWeight.Black, letterSpacing = 24.sp)
        Spacer(Modifier.height(24.dp))
        TvButton("Cancel", null, { vm.cancelPairing() }, Modifier.focusRequester(cancel))
    }
}

// ----- Browsing -----

private fun sectionIcon(id: String): ImageVector = when (id) {
    "recent" -> Icons.Rounded.History
    "liked" -> Icons.Rounded.Favorite
    "playlists" -> Icons.AutoMirrored.Rounded.PlaylistPlay
    "albums" -> Icons.Rounded.Album
    "artists" -> Icons.Rounded.Person
    "composers" -> Icons.Rounded.MusicNote
    else -> Icons.Rounded.LibraryMusic
}

@Composable
private fun BrowseScreen(vm: TvViewModel) {
    BackHandler(enabled = vm.pages.size > 1 || vm.showSettings) {
        if (vm.showSettings) vm.showSettings = false else vm.back()
    }
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(300.dp).fillMaxHeight().background(Palette.Elevated).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Isaialai", fontWeight = FontWeight.Black, fontSize = 28.sp)
            Text("From ${vm.link?.name ?: "your phone"}", color = Palette.SubText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            if (vm.currentId != null) {
                RailItem(if (vm.isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.PlayCircle, "Now playing", vm.title, false) { vm.showPlayer = true }
            }
            val open = vm.pages.firstOrNull()?.id
            vm.sections.forEach { s ->
                RailItem(sectionIcon(s.id), s.title, null, !vm.showSettings && open == s.id) { vm.openSection(s) }
            }
            RailItem(Icons.Rounded.Settings, "Settings", null, vm.showSettings) { vm.showSettings = true }
        }
        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 40.dp, end = 40.dp, top = 32.dp)) {
            if (vm.showSettings) {
                TvSettings(vm)
            } else {
                vm.pages.lastOrNull()?.let { page -> key(page) { PageView(vm, page) } }
            }
            vm.message?.let { m ->
                Text(
                    m,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Elevated2).padding(16.dp),
                    fontSize = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun RailItem(icon: ImageVector, title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    TvSurface(onClick, Modifier.fillMaxWidth()) { focused ->
        Row(
            Modifier
                .background(if (selected && !focused) Palette.Elevated2 else Color.Transparent)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else Palette.Text)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontSize = 19.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) Text(subtitle, fontSize = 14.sp, color = Palette.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PageView(vm: TvViewModel, page: TvPage) {
    val first = remember { FocusRequester() }
    if (!page.loading) FocusOnShow(first, page)
    Column(Modifier.fillMaxSize()) {
        Text(page.title, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        when {
            page.loading -> CircularProgressIndicator()
            page.error != null -> Text(page.error.orEmpty(), fontSize = 20.sp, color = Palette.Coral)
            page.songs != null -> SongList(vm, page.songs.orEmpty(), first)
            page.folders.isEmpty() -> Text("Nothing here yet.", fontSize = 20.sp, color = Palette.SubText)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(190.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(page.folders, key = { _, f -> f.id }) { i, f ->
                    TvSurface({ vm.open(f) }, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                        Column(Modifier.padding(10.dp)) {
                            RemoteCover(vm.artUrl(f.cover), Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(8.dp), 56.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(f.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (f.subtitle.isNotEmpty()) Text(f.subtitle, fontSize = 14.sp, color = Palette.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongList(vm: TvViewModel, songs: List<TvSong>, first: FocusRequester) {
    if (songs.isEmpty()) {
        Text("No songs here yet.", fontSize = 20.sp, color = Palette.SubText)
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                TvButton("Play", Icons.Rounded.PlayArrow, { vm.play(songs, 0) }, Modifier.focusRequester(first))
                TvButton("Shuffle", Icons.Rounded.Shuffle, { vm.play(songs, 0, shuffled = true) })
                Text(if (songs.size == 1) "1 song" else "${songs.size} songs", fontSize = 18.sp, color = Palette.SubText)
            }
        }
        itemsIndexed(songs, key = { i, s -> "$i:${s.id}" }) { i, s ->
            TvSurface({ vm.play(songs, i) }, Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RemoteCover(vm.artUrl(s.id), Modifier.size(56.dp), RoundedCornerShape(6.dp), 26.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.title,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (s.id == vm.currentId) MaterialTheme.colorScheme.primary else Palette.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(listOf(s.artist, s.album).filter { it.isNotEmpty() }.joinToString(" · "), fontSize = 15.sp, color = Palette.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (s.durationMs > 0) Text(formatTime(s.durationMs), fontSize = 16.sp, color = Palette.SubText)
                }
            }
        }
    }
}

@Composable
private fun TvSettings(vm: TvViewModel) {
    val first = remember { FocusRequester() }
    FocusOnShow(first, Unit)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("Playing music from ${vm.link?.name ?: "your phone"}.", fontSize = 20.sp)
        TvButton("Use a different phone", Icons.Rounded.PhoneAndroid, { vm.forgetPhone() }, Modifier.focusRequester(first))
        Text("App updates", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Box(Modifier.widthIn(max = 640.dp)) { UpdateCard() }
    }
}

// ----- Now playing -----

@Composable
private fun PlayerScreen(vm: TvViewModel) {
    BackHandler { vm.showPlayer = false }
    val playButton = remember { FocusRequester() }
    FocusOnShow(playButton, Unit)
    Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            RemoteCover(vm.artUrl(vm.currentId), Modifier.size(320.dp), RoundedCornerShape(16.dp), 120.dp)
            Spacer(Modifier.height(24.dp))
            Text(vm.title.ifEmpty { "Nothing playing" }, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(vm.artist, fontSize = 22.sp, color = Palette.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(20.dp))
            val progress = if (vm.durationMs > 0) (vm.positionMs.toFloat() / vm.durationMs).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(520.dp).height(6.dp).clip(RoundedCornerShape(3.dp)))
            Row(Modifier.width(520.dp).padding(top = 6.dp)) {
                Text(formatTime(vm.positionMs), fontSize = 16.sp, color = Palette.SubText)
                Spacer(Modifier.weight(1f))
                Text(formatTime(vm.durationMs), fontSize = 16.sp, color = Palette.SubText)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                TvIconButton(Icons.Rounded.Shuffle, if (vm.shuffle) "Shuffle on" else "Shuffle off", vm::toggleShuffle, on = vm.shuffle)
                TvIconButton(Icons.Rounded.SkipPrevious, "Previous", { vm.previous() })
                TvIconButton(Icons.Rounded.Replay10, "Back 10 seconds", { vm.rewind() })
                TvIconButton(
                    if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (vm.isPlaying) "Pause" else "Play",
                    vm::togglePlay,
                    Modifier.focusRequester(playButton),
                    big = true,
                )
                TvIconButton(Icons.Rounded.Forward10, "Forward 10 seconds", { vm.forward() })
                TvIconButton(Icons.Rounded.SkipNext, "Next", { vm.next() })
                TvIconButton(Icons.Rounded.LibraryMusic, "Browse your music", { vm.showPlayer = false })
            }
            vm.message?.let { Text(it, fontSize = 18.sp, color = Palette.Coral, modifier = Modifier.padding(top = 16.dp)) }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text("Up next", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            if (vm.upNext.isEmpty()) {
                Text("Nothing after this song.", fontSize = 18.sp, color = Palette.SubText)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(vm.upNext, key = { _, t -> t.first }) { _, (index, title, artist) ->
                    TvSurface({ vm.jumpTo(index) }, Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (artist.isNotEmpty()) Text(artist, fontSize = 15.sp, color = Palette.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
