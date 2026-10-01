package io.github.akrishna87.mymusic.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.mymusic.AlbumGroup
import io.github.akrishna87.mymusic.FolderLevel
import io.github.akrishna87.mymusic.SmartPlaylist
import io.github.akrishna87.mymusic.LibraryGrouping
import io.github.akrishna87.mymusic.MusicViewModel
import io.github.akrishna87.mymusic.NameRequest
import io.github.akrishna87.mymusic.PlaylistStore
import io.github.akrishna87.mymusic.Screen
import io.github.akrishna87.mymusic.Song

private enum class DetailKind(val label: String) {
    ALBUM("Album"), ARTIST("Artist"), PLAYLIST("Playlist"), LIKED("Playlist"), FOLDER("Folder"),
    COMPOSER("Music director"), SMART("Smart playlist"),
}

private data class DetailData(
    val kind: DetailKind,
    val title: String,
    val songs: List<Song>,
    val byline: String = "",
    val playlistId: String? = null,
    /** Set for folder pages, which list subfolders as well as songs. */
    val folder: FolderLevel? = null,
    /** A music director's movies and albums, shown above their songs. */
    val albums: List<AlbumGroup> = emptyList(),
    val smart: SmartPlaylist? = null,
) {
    val subtitle: String
        get() = listOf(kind.label, byline, songCount(songs.size), totalTime(songs)).filter { it.isNotEmpty() }.joinToString(" · ")
}

private fun totalTime(songs: List<Song>): String {
    val minutes = songs.sumOf { it.durationMs.coerceAtLeast(0) } / 60_000
    return when {
        minutes <= 0 -> ""
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} hr ${minutes % 60} min"
    }
}

@Composable
private fun detailFor(vm: MusicViewModel, screen: Screen): DetailData? {
    val songs = vm.songs
    return when (screen) {
        is Screen.Album -> remember(screen, songs) {
            LibraryGrouping.albums(songs.filter { it.albumKey == screen.key }).firstOrNull()?.let {
                DetailData(DetailKind.ALBUM, it.name, it.songs, byline = it.artist)
            }
        }
        is Screen.Artist -> remember(screen, songs) {
            LibraryGrouping.artists(songs.filter { it.artist.equals(screen.name, ignoreCase = true) }).firstOrNull()?.let {
                DetailData(DetailKind.ARTIST, it.name, it.songs)
            }
        }
        is Screen.Folder -> remember(screen, songs) {
            LibraryGrouping.folderLevel(songs, screen.path).takeIf { it.allSongs.isNotEmpty() }?.let { level ->
                val path = screen.path.ifEmpty { "Phone storage" }
                DetailData(DetailKind.FOLDER, path.substringAfterLast('/'), level.allSongs, byline = path, folder = level)
            }
        }
        is Screen.Composer -> remember(screen, songs) {
            LibraryGrouping.composers(songs).firstOrNull { it.name.equals(screen.name, ignoreCase = true) }?.let {
                val movies = if (it.albums.size == 1) "1 movie or album" else "${it.albums.size} movies and albums"
                DetailData(DetailKind.COMPOSER, it.name, it.songs, byline = movies, albums = it.albums)
            }
        }
        is Screen.Smart -> {
            val counts = vm.countsVersion
            remember(screen, songs, counts) {
                DetailData(DetailKind.SMART, screen.kind.label, vm.smartSongs(screen.kind, songs), smart = screen.kind)
            }
        }
        Screen.Duplicates -> null
        is Screen.PlaylistDetail -> {
            val playlist = vm.playlists.get(screen.id)
            val byId = vm.songsById
            remember(playlist, byId) {
                playlist?.let { p ->
                    val kind = if (p.id == PlaylistStore.LIKED_ID) DetailKind.LIKED else DetailKind.PLAYLIST
                    DetailData(kind, p.name, p.songIds.mapNotNull { byId[it] }, playlistId = p.id)
                }
            }
        }
    }
}

@Composable
fun DetailScreen(vm: MusicViewModel, screen: Screen) {
    val detail = detailFor(vm, screen)
    val lead = detail?.songs?.firstOrNull()
    val artColor = rememberArtColor(lead)
    val color = if (detail?.kind == DetailKind.LIKED) Palette.Violet else artColor
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 500 } }
    val barColor by animateColorAsState(if (scrolled) color.wash(0.55f) else Color.Transparent, label = "bar")

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        if (detail == null) {
            Text("This is empty now.", Modifier.align(Alignment.Center), color = Palette.SubText)
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
                item { Hero(vm, detail, color) }
                if (detail.albums.size > 1) {
                    item {
                        CardRow("Movies & albums", detail.albums, { it.key }) { a ->
                            AlbumCard(a, onClick = { vm.open(Screen.Album(a.key)) }, subtitle = a.songs.maxOf { it.year }.takeIf { it > 0 }?.toString() ?: a.artist)
                        }
                    }
                    item { SectionTitle("Songs") }
                }
                when {
                    detail.folder != null -> folderItems(vm, detail.folder)
                    detail.smart != null && detail.songs.isEmpty() -> item {
                        Text("Nothing here yet. ${detail.smart.blurb}.", Modifier.padding(24.dp), color = Palette.SubText)
                    }
                    detail.songs.isEmpty() -> item {
                        Text("No songs here yet. Use a song's ⋮ menu → “Add to playlist…”.", Modifier.padding(24.dp), color = Palette.SubText)
                    }
                    else -> itemsIndexed(detail.songs, key = { _, s -> s.id }) { i, s ->
                        SongRow(
                            vm,
                            s,
                            onClick = { vm.play(detail.songs, i, from = detail.title) },
                            playlistId = detail.playlistId?.takeIf { detail.kind == DetailKind.PLAYLIST || detail.kind == DetailKind.LIKED },
                            number = if (detail.kind == DetailKind.ALBUM) (s.track.takeIf { it > 0 } ?: (i + 1)) else null,
                        )
                    }
                }
            }
        }

        // Back button on top; the bar fills in with the cover colour once the header scrolls away.
        Row(
            Modifier.fillMaxWidth().background(barColor).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            AnimatedVisibility(scrolled && detail != null, Modifier.weight(1f), enter = fadeIn(), exit = fadeOut()) {
                Text(detail?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!scrolled || detail == null) Spacer(Modifier.weight(1f))
            val id = detail?.playlistId
            if (id != null && detail?.kind == DetailKind.PLAYLIST) {
                IconButton(onClick = {
                    vm.nameRequest = NameRequest("Rename playlist", detail?.title.orEmpty()) { vm.playlists.rename(id, it) }
                }) { Icon(Icons.Rounded.Edit, "Rename playlist") }
                IconButton(onClick = { vm.deletePlaylist(id) }) { Icon(Icons.Rounded.Delete, "Delete playlist") }
            }
        }
    }
}

@Composable
private fun Hero(vm: MusicViewModel, detail: DetailData, color: Color) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(color.wash(0.2f), color.wash(0.75f), Palette.Background)))
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 64.dp, bottom = 8.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val cover = Modifier.size(230.dp).shadow(28.dp, if (detail.kind == DetailKind.ARTIST) CircleShape else RoundedCornerShape(8.dp))
            when (detail.kind) {
                DetailKind.FOLDER -> FolderIcon(detail.folder?.path.orEmpty(), size = 230.dp, shape = RoundedCornerShape(8.dp))
                DetailKind.LIKED -> IconTile(Icons.Rounded.Favorite, null, cover, RoundedCornerShape(8.dp), iconSize = 96.dp)
                DetailKind.SMART -> detail.smart?.let {
                    IconTile(smartIcon(it), "smart:" + it.name, cover, RoundedCornerShape(8.dp), iconSize = 96.dp)
                }
                DetailKind.ARTIST, DetailKind.COMPOSER -> ArtImage(detail.songs.firstOrNull(), cover, sizePx = 600, shape = CircleShape, iconSize = 72.dp)
                else -> ArtImage(detail.songs.firstOrNull(), cover, sizePx = 600, shape = RoundedCornerShape(8.dp), iconSize = 72.dp)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(detail.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Text(detail.subtitle, color = Palette.SubText, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        detail.smart?.let { Text(it.blurb, color = Palette.SubText, fontSize = 13.sp) }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { vm.play(detail.songs, 0, shuffled = true, from = detail.title) },
                enabled = detail.songs.isNotEmpty(),
            ) {
                Icon(Icons.Rounded.Shuffle, "Shuffle", tint = Palette.SubText, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.weight(1f))
            // Pause/resume if this list is what's playing; otherwise start it.
            val playingThis = vm.playingFrom == detail.title && detail.songs.any { it.id == vm.currentId }
            PlayCircleButton(
                onClick = { if (playingThis) vm.togglePlay() else vm.play(detail.songs, 0, from = detail.title) },
                playing = playingThis && vm.isPlaying,
                enabled = detail.songs.isNotEmpty(),
            )
        }
    }
}
