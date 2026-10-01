package io.github.akrishna87.mymusic.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.akrishna87.mymusic.AddedFolders
import io.github.akrishna87.mymusic.FolderEntry
import io.github.akrishna87.mymusic.FolderLevel
import io.github.akrishna87.mymusic.LibraryGrouping
import io.github.akrishna87.mymusic.MusicViewModel
import io.github.akrishna87.mymusic.NameRequest
import io.github.akrishna87.mymusic.Screen
import io.github.akrishna87.mymusic.Song
import io.github.akrishna87.mymusic.SongSort
import io.github.akrishna87.mymusic.Storage

private val TABS = listOf("Songs", "Albums", "Artists", "Folders", "Playlists")

private val AUDIO_PERMISSION =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasAudioPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, AUDIO_PERMISSION) == PackageManager.PERMISSION_GRANTED

@Composable
fun MusicApp(vm: MusicViewModel = viewModel()) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasAudioPermission(context)) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) denied = true
    }
    // The person may have allowed access from system settings while we were in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasAudioPermission(context) }
    LaunchedEffect(granted) { if (granted) vm.onPermissionGranted() }

    if (granted) {
        Library(vm)
    } else {
        PermissionScreen(
            denied = denied,
            onAsk = { launcher.launch(AUDIO_PERMISSION) },
            onSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                )
            },
        )
    }
}

@Composable
private fun PermissionScreen(denied: Boolean, onAsk: () -> Unit, onSettings: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(96.dp).background(ArtPlaceholder, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.LibraryMusic, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Play the music on your phone", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                "My Music needs permission to see the songs saved on this phone. " +
                    "It plays them straight from storage. Nothing is copied or uploaded.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAsk) { Text("Allow access to music") }
            if (denied) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onSettings) { Text("Open settings") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "If the button above does nothing, open settings → Permissions → Music and audio → Allow.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Library(vm: MusicViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = vm.showPlayer || vm.screens.isNotEmpty()) { vm.back() }

    val top = vm.screens.lastOrNull()
    val detail = top?.let { detailFor(vm, it) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { if (top == null) HomeTopBar(vm) else DetailTopBar(vm, detail) },
            bottomBar = { MiniPlayer(vm) },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when {
                    vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    vm.songs.isEmpty() -> EmptyLibrary(vm)
                    top == null -> HomeContent(vm)
                    else -> DetailContent(vm, detail)
                }
            }
        }
        AnimatedVisibility(
            visible = vm.showPlayer && vm.currentId != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlaying(vm)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 76.dp))
    }

    vm.playlistPickerFor?.let { PlaylistPickerDialog(vm, it) }
    vm.nameRequest?.let { req -> NameDialog(req) { vm.nameRequest = null } }
}

// ----- Home -----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(vm: MusicViewModel) {
    var searching by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }
    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        TopAppBar(
            title = {
                if (searching) {
                    TextField(
                        value = vm.query,
                        onValueChange = { vm.query = it },
                        placeholder = { Text("Search songs, artists, albums") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                } else {
                    Text("My Music", fontWeight = FontWeight.Bold)
                }
            },
            actions = {
                if (searching) {
                    IconButton(onClick = { searching = false; vm.query = "" }) { Icon(Icons.Rounded.Close, "Close search") }
                } else {
                    IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "Search") }
                }
                IconButton(onClick = { vm.refreshLibrary(announce = true) }) { Icon(Icons.Rounded.Refresh, "Rescan phone for music") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        ScrollableTabRow(
            selectedTabIndex = vm.tab,
            edgePadding = 8.dp,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            TABS.forEachIndexed { i, name ->
                Tab(selected = vm.tab == i, onClick = { vm.saveTab(i) }, text = { Text(name) })
            }
        }
    }
}

@Composable
private fun HomeContent(vm: MusicViewModel) {
    when (vm.tab) {
        0 -> SongsTab(vm)
        1 -> AlbumsTab(vm)
        2 -> ArtistsTab(vm)
        3 -> FoldersTab(vm)
        else -> PlaylistsTab(vm)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(32.dp),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SongsTab(vm: MusicViewModel) {
    val list = remember(vm.songs, vm.query, vm.sort) {
        LibraryGrouping.sort(vm.songs.filter { it.matches(vm.query) }, vm.sort)
    }
    var sortMenu by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(songCount(list.size), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    TextButton(onClick = { sortMenu = true }) { Text("Sort: ${vm.sort.label}") }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SongSort.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(s.label) }, onClick = { sortMenu = false; vm.saveSort(s) })
                        }
                    }
                }
                FilledTonalIconButton(onClick = { vm.play(list, 0, shuffled = true) }, enabled = list.isNotEmpty()) {
                    Icon(Icons.Rounded.Shuffle, "Shuffle all")
                }
                FilledIconButton(onClick = { vm.play(list, 0) }, enabled = list.isNotEmpty()) {
                    Icon(Icons.Rounded.PlayArrow, "Play all")
                }
            }
        }
        if (list.isEmpty()) item { Hint("No songs match “${vm.query}”.") }
        itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
            SongRow(vm, s, onClick = { vm.play(list, i) })
        }
    }
}

@Composable
private fun AlbumsTab(vm: MusicViewModel) {
    val albums = remember(vm.songs, vm.query) {
        LibraryGrouping.albums(vm.songs).filter {
            vm.query.isBlank() || it.name.contains(vm.query.trim(), true) || it.artist.contains(vm.query.trim(), true)
        }
    }
    if (albums.isEmpty()) return Hint("No albums match “${vm.query}”.")
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.key }) { a ->
            Column(Modifier.clickable { vm.open(Screen.Album(a.key)) }) {
                ArtImage(
                    a.songs.first(),
                    Modifier.fillMaxWidth().aspectRatio(1f),
                    sizePx = 400,
                    shape = RoundedCornerShape(12.dp),
                    iconSize = 40.dp,
                )
                Spacer(Modifier.height(6.dp))
                Text(a.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${a.artist} · ${a.songs.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun GroupRow(title: String, subtitle: String, onClick: () -> Unit, leading: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ArtistsTab(vm: MusicViewModel) {
    val artists = remember(vm.songs, vm.query) {
        LibraryGrouping.artists(vm.songs).filter { vm.query.isBlank() || it.name.contains(vm.query.trim(), true) }
    }
    if (artists.isEmpty()) return Hint("No artists match “${vm.query}”.")
    LazyColumn(Modifier.fillMaxSize()) {
        items(artists, key = { it.name.lowercase() }) { a ->
            GroupRow(a.name, songCount(a.songs.size), onClick = { vm.open(Screen.Artist(a.name)) }) {
                RoundArt(a.songs.first())
            }
        }
    }
}

@Composable
private fun FoldersTab(vm: MusicViewModel) {
    if (vm.query.isNotBlank()) {
        val found = remember(vm.songs, vm.query) { LibraryGrouping.searchFolders(vm.songs, vm.query) }
        if (found.isEmpty()) return Hint("No folders match “${vm.query}”.")
        LazyColumn(Modifier.fillMaxSize()) {
            items(found, key = { it.path }) { f -> FolderRow(vm, f, showPath = true) }
        }
        return
    }
    val level = remember(vm.songs) { LibraryGrouping.folderLevel(vm.songs, "") }
    LazyColumn(Modifier.fillMaxSize()) {
        folderItems(vm, level)
        item { AddFolderCard(vm) }
    }
}

/** The subfolders of a folder, then the songs directly inside it. */
private fun LazyListScope.folderItems(vm: MusicViewModel, level: FolderLevel) {
    items(level.subfolders, key = { "dir:" + it.path }) { f -> FolderRow(vm, f) }
    itemsIndexed(level.songs, key = { _, s -> s.id }) { i, s ->
        SongRow(vm, s, onClick = { vm.play(level.songs, i) })
    }
}

@Composable
private fun FolderIcon(path: String, size: Dp = 48.dp) {
    val sdCard = path == Storage.SD_CARD
    Box(
        Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (sdCard) Icons.Rounded.SdCard else Icons.Rounded.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(size / 2),
        )
    }
}

@Composable
private fun FolderRow(vm: MusicViewModel, f: FolderEntry, showPath: Boolean = false) {
    GroupRow(
        f.name,
        if (showPath) "${f.path} · ${songCount(f.songCount)}" else songCount(f.songCount),
        onClick = { vm.open(Screen.Folder(f.path)) },
    ) {
        FolderIcon(f.path)
    }
}

/** Opens the system folder picker; the chosen folder is remembered and read directly. */
@Composable
private fun rememberFolderPicker(vm: MusicViewModel): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addFolder(uri)
    }
    return {
        try {
            launcher.launch(AddedFolders.pickerStart)
        } catch (e: ActivityNotFoundException) {
            vm.say("This phone has no folder picker")
        }
    }
}

@Composable
private fun AddFolderCard(vm: MusicViewModel) {
    val pickFolder = rememberFolderPicker(vm)
    Surface(
        Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Missing songs?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Some folders, like Telegram or app download folders, are hidden from Android's music library. " +
                    "Add the folder here and My Music will read it directly.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (vm.scanningFolders) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Reading added folders…", style = MaterialTheme.typography.bodySmall)
                }
            }
            vm.addedFolders.forEach { (uri, path) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FolderIcon(path, size = 36.dp)
                    Text(path, Modifier.weight(1f).padding(start = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { vm.removeFolder(uri) }) { Icon(Icons.Rounded.Close, "Stop reading $path") }
                }
            }
            FilledTonalButton(onClick = pickFolder) {
                Icon(Icons.Rounded.CreateNewFolder, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add a folder")
            }
        }
    }
}

@Composable
private fun PlaylistsTab(vm: MusicViewModel) {
    val all = vm.playlists.items
    val list = all.filter { vm.query.isBlank() || it.name.contains(vm.query.trim(), true) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (list.size == 1) "1 playlist" else "${list.size} playlists",
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = { vm.nameRequest = NameRequest("New playlist", "") { vm.createPlaylist(it) } }) {
                    Text("New playlist")
                }
            }
        }
        if (all.isEmpty()) item { Hint("Make a playlist, then add songs to it from any song's ⋮ menu.") }
        items(list, key = { it.id }) { p ->
            val first = p.songIds.firstNotNullOfOrNull { vm.songsById[it] }
            GroupRow(p.name, songCount(p.songIds.size), onClick = { vm.open(Screen.PlaylistDetail(p.id)) }) {
                ArtImage(first, Modifier.size(48.dp))
            }
        }
    }
}

@Composable
private fun EmptyLibrary(vm: MusicViewModel) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.LibraryMusic, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("No music found on this phone", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Songs saved anywhere on the phone (Music, Download and so on) show up here automatically. " +
                "If you've just copied some over, tap Rescan.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        val pickFolder = rememberFolderPicker(vm)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.refreshLibrary(announce = true) }) { Text("Rescan") }
            Button(onClick = pickFolder) { Text("Add a folder") }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Music in a folder Android hides (Telegram, some downloader apps)? Tap “Add a folder” and pick it.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ----- Album / artist / folder / playlist pages -----

private data class DetailData(
    val title: String,
    val subtitle: String,
    val songs: List<Song>,
    val round: Boolean = false,
    val playlistId: String? = null,
    /** Set for folder pages, which list subfolders as well as songs. */
    val folder: FolderLevel? = null,
)

@Composable
private fun detailFor(vm: MusicViewModel, screen: Screen): DetailData? {
    val songs = vm.songs
    return when (screen) {
        is Screen.Album -> remember(screen, songs) {
            LibraryGrouping.albums(songs.filter { it.albumKey == screen.key }).firstOrNull()?.let {
                DetailData(it.name, "${it.artist} · ${songCount(it.songs.size)}", it.songs)
            }
        }
        is Screen.Artist -> remember(screen, songs) {
            LibraryGrouping.artists(songs.filter { it.artist.equals(screen.name, ignoreCase = true) }).firstOrNull()?.let {
                DetailData(it.name, songCount(it.songs.size), it.songs, round = true)
            }
        }
        is Screen.Folder -> remember(screen, songs) {
            LibraryGrouping.folderLevel(songs, screen.path).takeIf { it.allSongs.isNotEmpty() }?.let { level ->
                val path = screen.path.ifEmpty { "Phone storage" }
                DetailData(path.substringAfterLast('/'), "$path · ${songCount(level.allSongs.size)}", level.allSongs, folder = level)
            }
        }
        is Screen.PlaylistDetail -> {
            val playlist = vm.playlists.get(screen.id)
            val byId = vm.songsById
            remember(playlist, byId) {
                playlist?.let { p ->
                    val list = p.songIds.mapNotNull { byId[it] }
                    DetailData(p.name, songCount(list.size), list, playlistId = p.id)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTopBar(vm: MusicViewModel, detail: DetailData?) {
    TopAppBar(
        title = { Text(detail?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        },
        actions = {
            val id = detail?.playlistId
            if (id != null) {
                IconButton(onClick = {
                    vm.nameRequest = NameRequest("Rename playlist", detail?.title.orEmpty()) { vm.playlists.rename(id, it) }
                }) { Icon(Icons.Rounded.Edit, "Rename playlist") }
                IconButton(onClick = { vm.deletePlaylist(id) }) { Icon(Icons.Rounded.Delete, "Delete playlist") }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun DetailContent(vm: MusicViewModel, detail: DetailData?) {
    if (detail == null) return Hint("This is empty now.")
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (detail.folder != null) {
                    FolderIcon(detail.folder.path, size = 112.dp)
                } else {
                    ArtImage(
                        detail.songs.firstOrNull(),
                        Modifier.size(112.dp),
                        sizePx = 400,
                        shape = if (detail.round) CircleShape else RoundedCornerShape(14.dp),
                        iconSize = 40.dp,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(detail.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(detail.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.play(detail.songs, 0) }, enabled = detail.songs.isNotEmpty()) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Play")
                        }
                        FilledTonalButton(onClick = { vm.play(detail.songs, 0, shuffled = true) }, enabled = detail.songs.isNotEmpty()) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Shuffle")
                        }
                    }
                }
            }
        }
        if (detail.folder != null) {
            folderItems(vm, detail.folder)
            return@LazyColumn
        }
        if (detail.songs.isEmpty()) {
            item { Hint("No songs here yet. Use a song's ⋮ menu → “Add to playlist…”.") }
        }
        itemsIndexed(detail.songs, key = { _, s -> s.id }) { i, s ->
            SongRow(vm, s, onClick = { vm.play(detail.songs, i) }, playlistId = detail.playlistId)
        }
    }
}

// ----- Mini player -----

@Composable
private fun MiniPlayer(vm: MusicViewModel) {
    if (vm.currentId == null) return
    val song = vm.currentSong
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.navigationBarsPadding()) {
            LinearProgressIndicator(
                progress = { if (vm.durationMs > 0) (vm.positionMs.toFloat() / vm.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                trackColor = Color.Transparent,
                drawStopIndicator = {},
            )
            Row(
                Modifier.fillMaxWidth().clickable { vm.showPlayer = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ArtImage(song, Modifier.size(44.dp))
                Column(Modifier.weight(1f)) {
                    Text(song?.title ?: vm.currentTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        song?.artist ?: vm.currentArtist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = vm::togglePlay) {
                    Icon(
                        if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (vm.isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(onClick = { vm.next() }) { Icon(Icons.Rounded.SkipNext, "Next song") }
            }
        }
    }
}
