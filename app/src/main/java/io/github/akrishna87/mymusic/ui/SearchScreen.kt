package io.github.akrishna87.mymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.mymusic.ArtistGroup
import io.github.akrishna87.mymusic.LibraryGrouping
import io.github.akrishna87.mymusic.MusicViewModel
import io.github.akrishna87.mymusic.Screen
import io.github.akrishna87.mymusic.Song

private class BrowseTile(val title: String, val key: String, val song: Song?, val onClick: () -> Unit)

@Composable
fun SearchScreen(vm: MusicViewModel) {
    val query = vm.query.trim()
    val focusManager = LocalFocusManager.current
    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        Text(
            "Search",
            Modifier.statusBarsPadding().padding(start = 16.dp, top = 16.dp, bottom = 12.dp),
            style = MaterialTheme.typography.headlineMedium,
        )
        TextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            placeholder = { Text("Songs, artists, albums or folders", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (vm.query.isNotEmpty()) {
                    IconButton(onClick = { vm.query = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
                }
            },
            singleLine = true,
            // The keyboard's search key just closes the keyboard; results already show as you type.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            shape = RoundedCornerShape(8.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Palette.Field,
                unfocusedContainerColor = Palette.Field,
                focusedTextColor = Color.Black,
                unfocusedTextColor = Color.Black,
                cursorColor = Color.Black,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedLeadingIconColor = Color.Black,
                unfocusedLeadingIconColor = Color.Black,
                focusedTrailingIconColor = Color.Black,
                unfocusedTrailingIconColor = Color.Black,
                focusedPlaceholderColor = Color(0xFF5A5A66),
                unfocusedPlaceholderColor = Color(0xFF5A5A66),
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (query.isEmpty()) BrowseGrid(vm) else SearchResults(vm, query)
    }
}

@Composable
private fun BrowseGrid(vm: MusicViewModel) {
    val songs = vm.songs
    val tiles = remember(songs) {
        val artists = LibraryGrouping.artists(songs).sortedByDescending { it.songs.size }.take(6)
        val albums = LibraryGrouping.albums(songs).sortedByDescending { it.songs.size }.take(6)
        val folders = LibraryGrouping.searchFolders(songs, "").filter { it.path.contains('/') || songs.any { s -> s.folder == it.path } }
            .sortedByDescending { it.songCount }.take(6)
        buildList {
            artists.forEach { a -> add(BrowseTile(a.name, "artist:" + a.name, a.songs.first()) { vm.open(Screen.Artist(a.name)) }) }
            folders.forEach { f ->
                add(BrowseTile(f.name, "folder:" + f.path, songs.firstOrNull { it.folder == f.path || it.folder.startsWith(f.path + "/") }) { vm.open(Screen.Folder(f.path)) })
            }
            albums.forEach { a -> add(BrowseTile(a.name, "album:" + a.key, a.songs.first()) { vm.open(Screen.Album(a.key)) }) }
        }.distinctBy { it.key }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalBottomSpace.current),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Browse your music", Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
        }
        items(tiles, key = { it.key }) { t ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(placeholderColor(t.key).deep(0.15f))
                    .clickable(onClick = t.onClick),
            ) {
                Text(
                    t.title,
                    Modifier.padding(12.dp).fillMaxWidth(0.7f),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // A tilted cover peeking out of the corner.
                ArtImage(
                    t.song,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 14.dp, y = 6.dp)
                        .rotate(25f)
                        .size(70.dp)
                        .shadow(6.dp, RoundedCornerShape(4.dp)),
                    sizePx = 200,
                )
            }
        }
    }
}

@Composable
private fun SearchResults(vm: MusicViewModel, query: String) {
    val songs = vm.songs
    val matchedSongs = remember(songs, query) { songs.filter { it.matches(query) }.take(60) }
    val albums = remember(songs, query) {
        LibraryGrouping.albums(songs).filter { it.name.contains(query, true) || it.artist.contains(query, true) }.take(15)
    }
    val artists = remember(songs, query) { LibraryGrouping.artists(songs).filter { it.name.contains(query, true) }.take(15) }
    val composers = remember(songs, query) { LibraryGrouping.composers(songs).filter { it.name.contains(query, true) }.take(15) }
    val folders = remember(songs, query) { LibraryGrouping.searchFolders(songs, query).take(10) }

    if (matchedSongs.isEmpty() && albums.isEmpty() && artists.isEmpty() && composers.isEmpty() && folders.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Nothing found for “$query”", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Check the spelling, or try fewer words.", color = Palette.SubText)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = LocalBottomSpace.current)) {
        if (matchedSongs.isNotEmpty()) {
            item { SectionTitle("Songs") }
            itemsIndexed(matchedSongs, key = { _, s -> "s:" + s.id }) { i, s ->
                SongRow(vm, s, onClick = { vm.play(matchedSongs, i, from = "Search: $query") })
            }
        }
        item {
            CardRow("Artists", artists, { it.name.lowercase() }) { a -> ArtistBubble(a, onClick = { vm.open(Screen.Artist(a.name)) }) }
        }
        item {
            CardRow("Music directors", composers, { "c:" + it.name.lowercase() }) { c ->
                ArtistBubble(ArtistGroup(c.name, c.songs), onClick = { vm.open(Screen.Composer(c.name)) }, label = "Music director")
            }
        }
        item {
            CardRow("Albums", albums, { it.key }) { a -> AlbumCard(a, onClick = { vm.open(Screen.Album(a.key)) }) }
        }
        if (folders.isNotEmpty()) {
            item { SectionTitle("Folders") }
            items(folders, key = { "f:" + it.path }) { f -> FolderRow(vm, f, showPath = true) }
        }
    }
}
