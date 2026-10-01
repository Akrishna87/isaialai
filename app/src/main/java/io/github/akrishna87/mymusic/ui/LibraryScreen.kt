package io.github.akrishna87.mymusic.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.mymusic.AddedFolders
import io.github.akrishna87.mymusic.FolderEntry
import io.github.akrishna87.mymusic.FolderLevel
import io.github.akrishna87.mymusic.LibraryChip
import io.github.akrishna87.mymusic.LibraryGrouping
import io.github.akrishna87.mymusic.MusicViewModel
import io.github.akrishna87.mymusic.NameRequest
import io.github.akrishna87.mymusic.PlaylistStore
import io.github.akrishna87.mymusic.Screen
import io.github.akrishna87.mymusic.Section
import io.github.akrishna87.mymusic.SmartPlaylist
import io.github.akrishna87.mymusic.SongSort
import io.github.akrishna87.mymusic.Storage

@Composable
fun LibraryScreen(vm: MusicViewModel) {
    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(Icons.Rounded.LibraryMusic, null, Modifier.size(34.dp), CircleShape, iconSize = 18.dp)
            Spacer(Modifier.width(12.dp))
            Text("Your Library", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.selectSection(Section.SEARCH) }) { Icon(Icons.Rounded.Search, "Search") }
            IconButton(onClick = { vm.nameRequest = NameRequest("New playlist", "") { vm.createPlaylist(it) } }) {
                Icon(Icons.Rounded.Add, "New playlist")
            }
            Box {
                var menu by remember { mutableStateOf(false) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Find duplicate songs") },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = Palette.SubText) },
                        onClick = { menu = false; vm.open(Screen.Duplicates) },
                    )
                }
            }
        }
        // Six tabs don't fit across a phone; keep the chosen one in view.
        val chipRow = rememberLazyListState()
        LaunchedEffect(vm.libraryChip) { chipRow.animateScrollToItem((vm.libraryChip.ordinal - 1).coerceAtLeast(0)) }
        LazyRow(
            state = chipRow,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(LibraryChip.entries) { chip ->
                val selected = vm.libraryChip == chip
                FilterChip(
                    selected = selected,
                    onClick = { vm.selectChip(chip) },
                    label = { Text(chip.label, fontWeight = FontWeight.SemiBold) },
                    shape = CircleShape,
                    border = null,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Palette.Elevated2,
                        labelColor = Palette.Text,
                        selectedContainerColor = Palette.Coral,
                        selectedLabelColor = Color.Black,
                    ),
                )
            }
        }
        Box(Modifier.weight(1f)) {
            when (vm.libraryChip) {
                LibraryChip.SONGS -> SongsList(vm)
                LibraryChip.ALBUMS -> AlbumsGrid(vm)
                LibraryChip.ARTISTS -> ArtistsList(vm)
                LibraryChip.COMPOSERS -> ComposersList(vm)
                LibraryChip.FOLDERS -> FoldersRoot(vm)
                LibraryChip.PLAYLISTS -> PlaylistsList(vm)
            }
        }
    }
}

@Composable
private fun SongsList(vm: MusicViewModel) {
    val list = remember(vm.songs, vm.sort) { LibraryGrouping.sort(vm.songs, vm.sort) }
    var sortMenu by remember { mutableStateOf(false) }
    if (list.isEmpty()) return EmptyHint("No songs yet. Add a folder from the Folders tab, or tap Rescan on Home.")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    TextButton(onClick = { sortMenu = true }) {
                        Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, tint = Palette.SubText, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(vm.sort.label, color = Palette.Text)
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SongSort.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(s.label) }, onClick = { sortMenu = false; vm.saveSort(s) })
                        }
                    }
                }
                Text(songCount(list.size), color = Palette.SubText, fontSize = 13.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.play(list, 0, shuffled = true, from = "All songs") }) {
                    Icon(Icons.Rounded.Shuffle, "Shuffle all", tint = Palette.SubText, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(6.dp))
                PlayCircleButton(onClick = { vm.play(list, 0, from = "All songs") }, size = 48.dp)
            }
        }
        itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
            SongRow(vm, s, onClick = { vm.play(list, i, from = "All songs") })
        }
    }
}

@Composable
private fun AlbumsGrid(vm: MusicViewModel) {
    val albums = remember(vm.songs) { LibraryGrouping.albums(vm.songs) }
    if (albums.isEmpty()) return EmptyHint("No albums yet.")
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = LocalBottomSpace.current),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.key }) { a ->
            Column(Modifier.clickable { vm.open(Screen.Album(a.key)) }) {
                ArtImage(
                    a.songs.first(),
                    Modifier.fillMaxWidth().aspectRatio(1f).shadow(8.dp, RoundedCornerShape(6.dp)),
                    sizePx = 400,
                    shape = RoundedCornerShape(6.dp),
                    iconSize = 40.dp,
                )
                Spacer(Modifier.height(8.dp))
                Text(a.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                Text("${a.artist} · ${a.songs.size}", color = Palette.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ArtistsList(vm: MusicViewModel) {
    val artists = remember(vm.songs) { LibraryGrouping.artists(vm.songs) }
    if (artists.isEmpty()) return EmptyHint("No artists yet.")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
        items(artists, key = { it.name.lowercase() }) { a ->
            LibraryRow(a.name, "Artist · ${songCount(a.songs.size)}", onClick = { vm.open(Screen.Artist(a.name)) }) {
                ArtImage(a.songs.first(), Modifier.size(60.dp), sizePx = 200, shape = CircleShape)
            }
        }
    }
}

@Composable
private fun ComposersList(vm: MusicViewModel) {
    val composers = remember(vm.songs) { LibraryGrouping.composers(vm.songs) }
    if (composers.isEmpty()) {
        return EmptyHint(
            "No music directors yet. They come from the Composer tag in your song files, " +
                "which most Tamil and Indian film songs have.",
        )
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
        items(composers, key = { it.name.lowercase() }) { c ->
            val movies = if (c.albums.size == 1) "1 movie" else "${c.albums.size} movies"
            LibraryRow(c.name, "Music director · $movies · ${songCount(c.songs.size)}", onClick = { vm.open(Screen.Composer(c.name)) }) {
                ArtImage(c.songs.first(), Modifier.size(60.dp), sizePx = 200, shape = CircleShape)
            }
        }
    }
}

@Composable
private fun PlaylistsList(vm: MusicViewModel) {
    val liked = vm.playlists.liked
    val list = vm.playlists.userPlaylists
    val counts = vm.countsVersion
    val smartSizes = remember(vm.songs, counts) { SmartPlaylist.entries.associateWith { vm.smartSongs(it).size } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
        item {
            LibraryRow(
                "Liked songs",
                "Playlist · ${songCount(liked?.songIds?.size ?: 0)}",
                onClick = {
                    if (liked != null && liked.songIds.isNotEmpty()) vm.open(Screen.PlaylistDetail(PlaylistStore.LIKED_ID))
                    else vm.say("Tap ♥ on any song to add it to Liked songs")
                },
            ) {
                IconTile(Icons.Rounded.Favorite, null, Modifier.size(60.dp))
            }
        }
        items(SmartPlaylist.entries, key = { "smart:" + it.name }) { kind ->
            LibraryRow(kind.label, "Smart playlist · ${songCount(smartSizes[kind] ?: 0)}", onClick = { vm.open(Screen.Smart(kind)) }) {
                IconTile(smartIcon(kind), "smart:" + kind.name, Modifier.size(60.dp))
            }
        }
        item {
            LibraryRow("New playlist", "Make a playlist, then add songs from any song's ⋮ menu", onClick = {
                vm.nameRequest = NameRequest("New playlist", "") { vm.createPlaylist(it) }
            }) {
                Box(Modifier.size(60.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Elevated2), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = Palette.SubText, modifier = Modifier.size(30.dp))
                }
            }
        }
        items(list, key = { it.id }) { p ->
            val first = p.songIds.firstNotNullOfOrNull { vm.songsById[it] }
            LibraryRow(p.name, "Playlist · ${songCount(p.songIds.size)}", onClick = { vm.open(Screen.PlaylistDetail(p.id)) }) {
                ArtImage(first, Modifier.size(60.dp), sizePx = 200)
            }
        }
    }
}

@Composable
fun LibraryRow(title: String, subtitle: String, onClick: () -> Unit, leading: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Palette.SubText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(32.dp), color = Palette.SubText)
}

// ----- Folders -----

@Composable
private fun FoldersRoot(vm: MusicViewModel) {
    val level = remember(vm.songs) { LibraryGrouping.folderLevel(vm.songs, "") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
        folderItems(vm, level)
        item { AddFolderCard(vm) }
    }
}

/** The subfolders of a folder, then the songs directly inside it. */
fun LazyListScope.folderItems(vm: MusicViewModel, level: FolderLevel) {
    items(level.subfolders, key = { "dir:" + it.path }) { f -> FolderRow(vm, f) }
    itemsIndexed(level.songs, key = { _, s -> s.id }) { i, s ->
        SongRow(vm, s, onClick = { vm.play(level.songs, i, from = level.path.substringAfterLast('/').ifEmpty { "Phone storage" }) })
    }
}

@Composable
fun FolderIcon(path: String, size: Dp = 60.dp, shape: RoundedCornerShape = RoundedCornerShape(4.dp)) {
    IconTile(
        if (path == Storage.SD_CARD) Icons.Rounded.SdCard else Icons.Rounded.Folder,
        brushKey = "folder:$path",
        modifier = Modifier.size(size),
        shape = shape,
        iconSize = size * 0.45f,
    )
}

@Composable
fun FolderRow(vm: MusicViewModel, f: FolderEntry, showPath: Boolean = false) {
    LibraryRow(
        f.name,
        if (showPath) "${f.path} · ${songCount(f.songCount)}" else "Folder · ${songCount(f.songCount)}",
        onClick = { vm.open(Screen.Folder(f.path)) },
    ) {
        FolderIcon(f.path)
    }
}

/** Opens the system folder picker; the chosen folder is remembered and read directly. */
@Composable
fun rememberFolderPicker(vm: MusicViewModel): () -> Unit {
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
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Elevated)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Missing songs?", style = MaterialTheme.typography.titleMedium)
        Text(
            "Some folders, like Telegram or app download folders, are hidden from Android's music library. " +
                "Add the folder here and Isaialai will read it directly.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.SubText,
        )
        if (vm.scanningFolders) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Reading added folders…", style = MaterialTheme.typography.bodySmall)
            }
        }
        vm.addedFolders.forEach { (uri, path) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                FolderIcon(path, size = 40.dp)
                Text(path, Modifier.weight(1f).padding(start = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { vm.removeFolder(uri) }) { Icon(Icons.Rounded.Close, "Stop reading $path", tint = Palette.SubText) }
            }
        }
        Button(onClick = pickFolder, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
            Icon(Icons.Rounded.CreateNewFolder, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add a folder", fontWeight = FontWeight.Bold)
        }
    }
}
