package io.github.akrishna87.mymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.mymusic.AlbumGroup
import io.github.akrishna87.mymusic.ArtistGroup
import io.github.akrishna87.mymusic.LibraryGrouping
import io.github.akrishna87.mymusic.MusicViewModel
import io.github.akrishna87.mymusic.PlaylistStore
import io.github.akrishna87.mymusic.RecapPeriod
import io.github.akrishna87.mymusic.RecapStats
import io.github.akrishna87.mymusic.Screen
import io.github.akrishna87.mymusic.Song
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

/** Home's rows; you can hide them and change their order in Settings. */
enum class HomeSection(val label: String) {
    SHORTCUTS("Shortcuts"),
    CONTINUE("Continue listening"),
    JUMP_BACK_IN("Jump back in"),
    MIXES("Mixes for you"),
    RECAP("Your recap"),
    RECENTLY_ADDED("Recently added"),
    YOUR_ARTISTS("Your artists"),
    ON_REPEAT("On repeat"),
    REDISCOVER("Rediscover"),
}

/** A ready-made Instant mix on Home: around an artist, a music director, or what you've been playing. */
private class MixCardData(val title: String, val subtitle: String, val seeds: List<Song>, val key: String)

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

    // Recently played: each song's movie or album, or the song itself if it doesn't have one.
    val jumpBackIn = remember(history, albumByKey) {
        history.mapNotNull { byId[it] }
            .distinctBy { it.albumKey }
            .map { s -> albumByKey[s.albumKey] ?: s }
            .take(12)
    }
    val recentAlbums = remember(jumpBackIn) { jumpBackIn.filterIsInstance<AlbumGroup>() }
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

    val resumeVersion = vm.resumeVersion
    val continueListening = remember(byId, resumeVersion, vm.resumeMode) { vm.continueListening() }

    val tint = rememberArtColor(history.firstNotNullOfOrNull { byId[it] } ?: songs.firstOrNull())

    // Mixes: what you've played lately, then your top artists and music directors.
    val mixes = remember(songs, counts, history) {
        buildList {
            val recentSongs = history.mapNotNull { byId[it] }.take(15)
            if (recentSongs.size >= 3) add(MixCardData("Daily mix", "Songs like what you've been playing", recentSongs, "daily"))
            topArtists.filter { !it.name.equals(Song.UNKNOWN_ARTIST, true) }.take(4).forEach {
                add(MixCardData("${it.name} mix", "Artist", it.songs, "a:" + it.name.lowercase()))
            }
            LibraryGrouping.composers(songs)
                .sortedByDescending { c -> c.songs.sumOf { vm.playCount(it.id) } * 1000 + c.songs.size }
                .take(3)
                .forEach { add(MixCardData("${it.name} mix", "Music director", it.songs, "c:" + it.name.lowercase())) }
        }
    }
    LaunchedEffect(Unit) { vm.refreshListens() }
    val listens = vm.listens
    val month = remember(listens, byId) { RecapStats.of(listens, RecapPeriod.THIS_MONTH.range(), byId) }
    val order = ThemeSettings.homeOrder.filter { it !in ThemeSettings.homeHidden }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tint.wash(0.55f), Palette.Background), endY = 1100f)),
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
                IconButton(onClick = { vm.open(Screen.Settings) }) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                }
            }
        }
        for (section in order) when (section) {
            HomeSection.SHORTCUTS -> item(key = section.name) {
                QuickTiles(vm, songs, if (recentAlbums.size >= 2) recentAlbums else newest)
            }
            HomeSection.CONTINUE -> item(key = section.name) {
                CardRow("Continue listening", continueListening, { "c:" + it.first.id }) { (song, point) ->
                    ContinueCard(song, point, onClick = { vm.play(listOf(song), 0, from = "Continue listening") })
                }
            }
            HomeSection.JUMP_BACK_IN -> item(key = section.name) {
                CardRow<Any>("Jump back in", jumpBackIn, { if (it is Song) "s:" + it.id else "a:" + (it as AlbumGroup).key }) { item ->
                    when (item) {
                        is AlbumGroup -> AlbumCard(item, onClick = { vm.open(Screen.Album(item.key)) })
                        is Song -> SongCard(item, onClick = { vm.play(listOf(item), 0, from = "Recently played") })
                        else -> Unit
                    }
                }
            }
            HomeSection.MIXES -> item(key = section.name) {
                CardRow("Mixes for you", mixes, { it.key }) { m ->
                    MixCard(m, onClick = { vm.instantMix(m.title.removeSuffix(" mix"), m.seeds) })
                }
            }
            HomeSection.RECAP -> item(key = section.name) {
                RecapCard(month, onClick = { vm.open(Screen.Recap) })
            }
            HomeSection.RECENTLY_ADDED -> item(key = section.name) {
                CardRow("Recently added", newest, { it.key }) { a ->
                    AlbumCard(a, onClick = { vm.open(Screen.Album(a.key)) })
                }
            }
            HomeSection.YOUR_ARTISTS -> item(key = section.name) {
                CardRow("Your artists", topArtists, { it.name.lowercase() }) { a ->
                    ArtistBubble(a, onClick = { vm.open(Screen.Artist(a.name)) })
                }
            }
            HomeSection.ON_REPEAT -> item(key = section.name) {
                CardRow("On repeat", onRepeat, { it.id }) { s ->
                    SongCard(s, onClick = { vm.play(onRepeat, onRepeat.indexOf(s), from = "On repeat") })
                }
            }
            HomeSection.REDISCOVER -> item(key = section.name) {
                CardRow("Rediscover", rediscover, { it.key }) { a ->
                    AlbumCard(a, onClick = { vm.open(Screen.Album(a.key)) })
                }
            }
        }
    }
}

/** Two columns of quick shortcuts: Liked songs, Shuffle all, and recent albums. */
@Composable
private fun QuickTiles(vm: MusicViewModel, songs: List<Song>, albums: List<AlbumGroup>) {
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

/** A song left part-way: its cover with how far you got, and the time left. */
@Composable
private fun ContinueCard(song: Song, point: io.github.akrishna87.mymusic.ResumePoints.Point, onClick: () -> Unit) {
    val left = ((point.durationMs - point.positionMs) / 60_000).coerceAtLeast(1)
    Column(Modifier.width(150.dp).clickable(onClick = onClick)) {
        Box {
            ArtImage(song, Modifier.size(150.dp).shadow(8.dp, RoundedCornerShape(6.dp)), sizePx = 400, shape = RoundedCornerShape(6.dp), iconSize = 40.dp)
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f)),
            ) {
                Box(
                    Modifier.fillMaxWidth((point.positionMs.toFloat() / point.durationMs).coerceIn(0f, 1f)).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(song.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
        Text("$left min left", color = Palette.SubText, maxLines = 1, fontSize = 13.sp)
    }
}

/** An Instant mix on Home: the seed's cover, with the mix's name. */
@Composable
private fun MixCard(mix: MixCardData, onClick: () -> Unit) {
    Column(Modifier.width(150.dp).clickable(onClick = onClick).semantics(mergeDescendants = true) {}) {
        Box {
            ArtImage(mix.seeds.first(), Modifier.size(150.dp).shadow(8.dp, RoundedCornerShape(6.dp)), sizePx = 400, shape = RoundedCornerShape(6.dp), iconSize = 40.dp)
            Box(
                Modifier.matchParentSize().clip(RoundedCornerShape(6.dp))
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))),
            )
            Row(Modifier.align(Alignment.BottomStart).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Mix", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(mix.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
        Text(mix.subtitle, color = Palette.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
    }
}

/** This month's listening in a line, opening the full recap. */
@Composable
private fun RecapCard(month: RecapStats, onClick: () -> Unit) {
    val name = LocalDate.now().month.getDisplayName(TextStyle.FULL, Locale.getDefault())
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 24.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BrandGradient)
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Text("Your $name so far", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            if (month.isEmpty) "Play some music and see what you listened to most."
            else "${RecapStats.duration(month.ms)} of music" + (month.topArtists.firstOrNull()?.let { " · most of all ${it.name}" } ?: ""),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text("See your recap ›", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
