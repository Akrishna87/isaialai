package io.github.akrishna87.mymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.mymusic.ArtistGroup
import io.github.akrishna87.mymusic.LibraryGrouping
import io.github.akrishna87.mymusic.MusicViewModel
import io.github.akrishna87.mymusic.PlaylistStore
import io.github.akrishna87.mymusic.Screen
import io.github.akrishna87.mymusic.Song
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
fun HomeScreen(vm: MusicViewModel) {
    val songs = vm.songs
    val byId = vm.songsById
    val albums = remember(songs) { LibraryGrouping.albums(songs) }
    val albumByKey = remember(albums) { albums.associateBy { it.key } }
    val artists = remember(songs) { LibraryGrouping.artists(songs) }
    val history = vm.history
    val counts = vm.countsVersion

    val recentAlbums = remember(history, albumByKey) {
        history.mapNotNull { byId[it]?.albumKey }.distinct().mapNotNull { albumByKey[it] }.take(12)
    }
    val newest = remember(albums) { albums.sortedByDescending { a -> a.songs.maxOf { it.dateAdded } }.take(12) }
    val topArtists = remember(artists, counts) {
        artists.sortedWith(
            compareByDescending<ArtistGroup> { a -> a.songs.sumOf { vm.playCount(it.id) } }.thenByDescending { it.songs.size },
        ).take(12)
    }
    val onRepeat = remember(songs, counts) {
        songs.filter { vm.playCount(it.id) > 1 }.sortedByDescending { vm.playCount(it.id) }.take(12)
    }
    // A different handful of albums each day.
    val rediscover = remember(albums) { albums.shuffled(Random(LocalDate.now().toEpochDay())).take(10) }

    val tint = rememberArtColor(history.firstNotNullOfOrNull { byId[it] } ?: songs.firstOrNull())

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tint.deep(0.55f), Palette.Background), endY = 1100f)),
        contentPadding = PaddingValues(bottom = LocalBottomSpace.current),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(greeting(), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.showEqualizer = true }) {
                    Icon(Icons.Rounded.Tune, contentDescription = "Equalizer")
                }
                IconButton(onClick = { vm.refreshLibrary(announce = true) }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Rescan phone for music")
                }
            }
        }
        item {
            QuickTiles(vm, songs, if (recentAlbums.size >= 2) recentAlbums else newest)
        }
        item {
            CardRow("Jump back in", recentAlbums, { it.key }) { a ->
                AlbumCard(a, onClick = { vm.open(Screen.Album(a.key)) })
            }
        }
        item {
            CardRow("Recently added", newest, { it.key }) { a ->
                AlbumCard(a, onClick = { vm.open(Screen.Album(a.key)) })
            }
        }
        item {
            CardRow("Your artists", topArtists, { it.name.lowercase() }) { a ->
                ArtistBubble(a, onClick = { vm.open(Screen.Artist(a.name)) })
            }
        }
        item {
            CardRow("On repeat", onRepeat, { it.id }) { s ->
                SongCard(s, onClick = { vm.play(onRepeat, onRepeat.indexOf(s), from = "On repeat") })
            }
        }
        item {
            CardRow("Rediscover", rediscover, { it.key }) { a ->
                AlbumCard(a, onClick = { vm.open(Screen.Album(a.key)) })
            }
        }
    }
}

/** Two columns of quick shortcuts: Liked songs, Shuffle all, and recent albums. */
@Composable
private fun QuickTiles(vm: MusicViewModel, songs: List<Song>, albums: List<io.github.akrishna87.mymusic.AlbumGroup>) {
    val liked = vm.playlists.liked
    val tiles = buildList<@Composable RowScope.() -> Unit> {
        add {
            QuickTile("Liked songs", icon = Icons.Rounded.Favorite) {
                if (liked != null && liked.songIds.isNotEmpty()) vm.open(Screen.PlaylistDetail(PlaylistStore.LIKED_ID))
                else vm.say("Tap ♥ on any song to add it to Liked songs")
            }
        }
        add {
            QuickTile("Shuffle all", icon = Icons.Rounded.Shuffle) {
                vm.play(songs, 0, shuffled = true, from = "All songs")
            }
        }
        albums.take(4).forEach { a ->
            add { QuickTile(a.name, song = a.songs.first()) { vm.open(Screen.Album(a.key)) } }
        }
    }
    Column(
        Modifier.padding(horizontal = 16.dp).padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { tile -> tile(this) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RowScope.QuickTile(title: String, song: Song? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .weight(1f)
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.Highlight.copy(alpha = 0.85f))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) IconTile(icon, null, Modifier.size(56.dp), RoundedCornerShape(0.dp))
        else ArtImage(song, Modifier.size(56.dp), shape = RoundedCornerShape(0.dp))
        Text(
            title,
            Modifier.padding(horizontal = 10.dp),
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SongCard(song: Song, onClick: () -> Unit) {
    Column(Modifier.width(150.dp).clickable(onClick = onClick)) {
        ArtImage(song, Modifier.size(150.dp).shadow(8.dp, RoundedCornerShape(6.dp)), sizePx = 400, shape = RoundedCornerShape(6.dp), iconSize = 40.dp)
        Spacer(Modifier.height(8.dp))
        Text(song.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
        Text(song.artist, color = Palette.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
    }
}
