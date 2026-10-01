package io.github.akrishna87.mymusic.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.akrishna87.mymusic.ArtLoader
import io.github.akrishna87.mymusic.MusicViewModel
import io.github.akrishna87.mymusic.NameRequest
import io.github.akrishna87.mymusic.Screen
import io.github.akrishna87.mymusic.Song

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun songCount(n: Int) = if (n == 1) "1 song" else "$n songs"

/** A song's cover art, or a gradient with a note when it has none. */
@Composable
fun ArtImage(
    song: Song?,
    modifier: Modifier = Modifier,
    sizePx: Int = 160,
    shape: Shape = RoundedCornerShape(8.dp),
    iconSize: Dp = 22.dp,
) {
    val context = LocalContext.current
    var bitmap by remember(song?.id, sizePx) { mutableStateOf<Bitmap?>(song?.let { ArtLoader.cached(it, sizePx) }) }
    LaunchedEffect(song?.id, sizePx) {
        if (song != null && bitmap == null) bitmap = ArtLoader.load(context, song, sizePx)
    }
    Box(modifier.clip(shape).background(ArtPlaceholder), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(iconSize))
        }
    }
}

@Composable
fun SongRow(
    vm: MusicViewModel,
    song: Song,
    onClick: () -> Unit,
    playlistId: String? = null,
) {
    val playing = vm.currentId == song.id
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtImage(song, Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                song.subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (song.durationMs > 0) {
            Text(formatTime(song.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More options for ${song.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Play next") }, onClick = { menu = false; vm.enqueue(song, next = true) })
                DropdownMenuItem(text = { Text("Add to queue") }, onClick = { menu = false; vm.enqueue(song, next = false) })
                DropdownMenuItem(text = { Text("Add to playlist…") }, onClick = { menu = false; vm.playlistPickerFor = song })
                DropdownMenuItem(text = { Text("Go to album") }, onClick = { menu = false; vm.open(Screen.Album(song.albumKey)) })
                DropdownMenuItem(text = { Text("Go to artist") }, onClick = { menu = false; vm.open(Screen.Artist(song.artist)) })
                DropdownMenuItem(text = { Text("Go to folder") }, onClick = { menu = false; vm.open(Screen.Folder(song.folder)) })
                if (playlistId != null) {
                    DropdownMenuItem(
                        text = { Text("Remove from this playlist") },
                        onClick = { menu = false; vm.playlists.remove(playlistId, song.id) },
                    )
                }
            }
        }
    }
}

@Composable
fun PlaylistPickerDialog(vm: MusicViewModel, song: Song) {
    AlertDialog(
        onDismissRequest = { vm.playlistPickerFor = null },
        title = { Text("Add to playlist") },
        text = {
            LazyColumn {
                item {
                    ListItem(
                        headlineContent = { Text("New playlist") },
                        leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
                        modifier = Modifier.clickable {
                            vm.playlistPickerFor = null
                            vm.nameRequest = NameRequest("New playlist", "") { vm.createPlaylist(it, song) }
                        },
                    )
                }
                items(vm.playlists.items, key = { it.id }) { p ->
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text(songCount(p.songIds.size)) },
                        leadingContent = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) },
                        modifier = Modifier.clickable {
                            vm.playlistPickerFor = null
                            vm.addToPlaylist(p.id, song)
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { vm.playlistPickerFor = null }) { Text("Cancel") } },
    )
}

@Composable
fun NameDialog(request: NameRequest, onDismiss: () -> Unit) {
    var name by remember(request) { mutableStateOf(request.initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(request) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                singleLine = true,
                placeholder = { Text("Playlist name") },
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { request.onSave(name.trim()); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RoundArt(song: Song?, size: Dp = 48.dp) =
    ArtImage(song, Modifier.size(size), shape = CircleShape)
