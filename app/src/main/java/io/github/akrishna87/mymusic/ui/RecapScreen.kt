package io.github.akrishna87.mymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.mymusic.LibraryGrouping
import io.github.akrishna87.mymusic.MusicViewModel
import io.github.akrishna87.mymusic.RecapPeriod
import io.github.akrishna87.mymusic.RecapStats
import io.github.akrishna87.mymusic.Screen
import java.time.format.TextStyle
import java.util.Locale

/** Your listening for a month, a year or ever: how much, your top songs, artists, music directors and movies. */
@Composable
fun RecapScreen(vm: MusicViewModel) {
    LaunchedEffect(Unit) { vm.refreshListens() }
    var period by rememberSaveable { mutableStateOf(RecapPeriod.THIS_MONTH) }
    val listens = vm.listens
    val byId = vm.songsById
    val stats = remember(listens, byId, period) { RecapStats.of(listens, period.range(), byId) }
    val artists = remember(vm.songs) { LibraryGrouping.artists(vm.songs).associateBy { it.name.lowercase() } }
    val composers = remember(vm.songs) { LibraryGrouping.composers(vm.songs).associateBy { it.name.lowercase() } }

    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Your recap", style = MaterialTheme.typography.titleLarge)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(RecapPeriod.entries) { p ->
                FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) })
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = LocalBottomSpace.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Headline(stats, period) }
            if (stats.isEmpty) {
                item {
                    Text(
                        "Nothing here yet. Your recap counts every song you listen to for at least 30 seconds " +
                            "(or half of a short one), from when you updated Isaialai.",
                        color = Palette.SubText,
                        fontSize = 14.sp,
                    )
                }
                return@LazyColumn
            }
            item {
                val days = stats.busiestDay?.getDisplayName(TextStyle.FULL, Locale.getDefault())
                Text(
                    "You listened on ${stats.daysListened} ${if (stats.daysListened == 1) "day" else "days"}" +
                        (if (days != null) ", most on ${days}s" else "") + (stats.partOfDay?.let { " $it" } ?: "") + ".",
                    color = Palette.SubText,
                    fontSize = 14.sp,
                )
            }
            item {
                TopList("Top songs", stats.topSongs, vm, onClick = { t -> byId[t.songId]?.let { vm.play(listOf(it), 0, from = "Your recap") } })
            }
            item {
                val top = stats.topSongs.mapNotNull { byId[it.songId] }
                if (top.isNotEmpty()) {
                    FilledTonalButton(onClick = { vm.play(top, 0, from = "Your top songs · ${period.label}") }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Play your top songs")
                    }
                }
            }
            item {
                TopList("Top artists", stats.topArtists, vm, round = true, onClick = { t ->
                    artists[t.name.lowercase()]?.let { vm.open(Screen.Artist(it.name)) }
                })
            }
            item {
                TopList("Top music directors", stats.topComposers, vm, round = true, onClick = { t ->
                    composers[t.name.lowercase()]?.let { vm.open(Screen.Composer(it.name)) }
                })
            }
            item {
                TopList("Top movies & albums", stats.topAlbums, vm, onClick = { t ->
                    byId[t.songId]?.let { s -> if (s.album.isNotBlank()) vm.open(Screen.Album(s.albumKey)) }
                })
            }
        }
    }
}

@Composable
private fun Headline(stats: RecapStats, period: RecapPeriod) {
    val title = when (period) {
        RecapPeriod.THIS_MONTH, RecapPeriod.LAST_MONTH -> {
            val today = java.time.LocalDate.now()
            val month = if (period == RecapPeriod.THIS_MONTH) today else today.minusMonths(1)
            month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        }
        RecapPeriod.THIS_YEAR -> java.time.LocalDate.now().year.toString()
        RecapPeriod.ALL_TIME -> "All time"
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(BrandGradient).padding(20.dp),
    ) {
        Text(title, color = Color.White.copy(alpha = 0.9f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(RecapStats.duration(stats.ms), color = Color.White, fontWeight = FontWeight.Black, fontSize = 36.sp)
        Text("of music", color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            "${stats.plays} ${if (stats.plays == 1) "play" else "plays"} · ${stats.songs} ${if (stats.songs == 1) "song" else "different songs"}",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun TopList(title: String, entries: List<RecapStats.Top>, vm: MusicViewModel, round: Boolean = false, onClick: (RecapStats.Top) -> Unit) {
    if (entries.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Elevated).padding(vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        entries.forEachIndexed { i, t ->
            Row(
                Modifier.fillMaxWidth().clickable { onClick(t) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", Modifier.width(22.dp), color = Palette.SubText, fontWeight = FontWeight.Bold)
                ArtImage(vm.songsById[t.songId], Modifier.size(44.dp), sizePx = 150, shape = if (round) CircleShape else RoundedCornerShape(4.dp), iconSize = 20.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(t.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (t.detail.isNotEmpty()) Text(t.detail, color = Palette.SubText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "${t.plays} ${if (t.plays == 1) "play" else "plays"}",
                    color = Palette.SubText,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
