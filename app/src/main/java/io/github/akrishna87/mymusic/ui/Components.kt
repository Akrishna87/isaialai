package io.github.akrishna87.mymusic.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import io.github.akrishna87.mymusic.AlbumGroup
import io.github.akrishna87.mymusic.AlbumNames
import io.github.akrishna87.mymusic.ArtLoader
import io.github.akrishna87.mymusic.CoverRequest
import io.github.akrishna87.mymusic.CustomArt
import io.github.akrishna87.mymusic.ArtistGroup
import io.github.akrishna87.mymusic.LibraryGrouping
import io.github.akrishna87.mymusic.SmartPlaylist
import io.github.akrishna87.mymusic.SongEdits
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

/** Space at the bottom of scrolling screens so the last row clears the mini player and tab bar. */
val LocalBottomSpace = compositionLocalOf { 0.dp }

/** A song's cover art, or a colourful gradient with a note when it has none. */
@Composable
fun ArtImage(
    song: Song?,
    modifier: Modifier = Modifier,
    sizePx: Int = 160,
    shape: Shape = RoundedCornerShape(4.dp),
    iconSize: Dp = 22.dp,
) {
    val context = LocalContext.current
    val coverVersion = CustomArt.version
    var bitmap by remember(song?.id, sizePx, coverVersion) { mutableStateOf<Bitmap?>(song?.let { ArtLoader.cached(it, sizePx) }) }
    LaunchedEffect(song?.id, sizePx, coverVersion) {
        if (song != null && bitmap == null) bitmap = ArtLoader.load(context, song, sizePx)
    }
    Box(
        modifier.clip(shape).background(placeholderBrush(song?.colorKey() ?: "")),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(iconSize))
        }
    }
}

/** A gradient tile with an icon, for things that have no cover: Liked songs, Shuffle all, folders. */
@Composable
fun IconTile(icon: ImageVector, brushKey: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(4.dp), iconSize: Dp = 24.dp) {
    Box(
        modifier.clip(shape).background(if (brushKey == null) BrandGradient else placeholderBrush(brushKey)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun SongRow(
    vm: MusicViewModel,
    song: Song,
    onClick: () -> Unit,
    playlistId: String? = null,
    number: Int? = null,
    /** A ≡ handle for dragging the song to a new place (in your playlists). */
    dragHandle: (@Composable () -> Unit)? = null,
    /** Extra song-menu items for this list, such as "Move to top". */
    extraMenu: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)? = null,
) {
    val playing = vm.currentId == song.id
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (number != null) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                if (playing) {
                    Icon(Icons.Rounded.GraphicEq, contentDescription = "Playing", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                } else {
                    Text("$number", color = Palette.SubText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            ArtImage(song, Modifier.size(50.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                color = if (playing) MaterialTheme.colorScheme.primary else Palette.Text,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (vm.isLiked(song)) {
                    Icon(Icons.Rounded.Favorite, contentDescription = "Liked", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    if (number != null) song.artist else song.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.SubText,
                )
            }
        }
        dragHandle?.invoke()
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More options for ${song.title}", tint = Palette.SubText)
            }
            SongMenu(vm, song, expanded = menu, onDismiss = { menu = false }, playlistId = playlistId, extra = extraMenu)
        }
    }
}

@Composable
fun SongMenu(
    vm: MusicViewModel,
    song: Song,
    expanded: Boolean,
    onDismiss: () -> Unit,
    playlistId: String? = null,
    extra: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        extra?.invoke(this, onDismiss)
        val liked = vm.isLiked(song)
        MenuItem(if (liked) "Remove from Liked songs" else "Like", if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder) { onDismiss(); vm.toggleLike(song) }
        MenuItem("Play next", Icons.Rounded.SkipNext) { onDismiss(); vm.enqueue(song, next = true) }
        MenuItem("Add to queue", Icons.Rounded.AddToQueue) { onDismiss(); vm.enqueue(song, next = false) }
        MenuItem("Add to playlist…", Icons.AutoMirrored.Rounded.PlaylistAdd) { onDismiss(); vm.playlistPickerFor = song }
        if (AlbumNames.hasAlbum(song)) {
            MenuItem("Go to album", Icons.Rounded.Album) { onDismiss(); vm.open(Screen.Album(song.albumKey)) }
        }
        MenuItem("Go to artist", Icons.Rounded.Person) { onDismiss(); vm.open(Screen.Artist(song.artist)) }
        LibraryGrouping.composerNames(song.composer).firstOrNull()?.let { composer ->
            MenuItem("Go to music director", Icons.Rounded.LibraryMusic) { onDismiss(); vm.open(Screen.Composer(composer)) }
        }
        MenuItem("Go to folder", Icons.Rounded.Folder) { onDismiss(); vm.open(Screen.Folder(song.folder)) }
        MenuItem("Edit song details…", Icons.Rounded.Edit) { onDismiss(); vm.editing = song }
        MenuItem("Change cover art…", Icons.Rounded.Image) { onDismiss(); vm.requestCover(song) }
        MenuItem("Cut song…", Icons.Rounded.ContentCut) { onDismiss(); vm.open(Screen.Cut(song.id, ringtone = false)) }
        MenuItem("Set as ringtone…", Icons.Rounded.NotificationsActive) { onDismiss(); vm.open(Screen.Cut(song.id, ringtone = true)) }
        if (playlistId != null) {
            MenuItem("Remove from this playlist", Icons.Rounded.RemoveCircleOutline) { onDismiss(); vm.playlists.remove(playlistId, song.id) }
        }
    }
}

@Composable
fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = Palette.SubText) },
        onClick = onClick,
    )
}

/** Section title used on Home and Search. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp),
        style = MaterialTheme.typography.titleLarge,
    )
}

@Composable
fun AlbumCard(album: AlbumGroup, onClick: () -> Unit, size: Dp = 150.dp, subtitle: String = album.artist) {
    Column(Modifier.width(size).clickable(onClick = onClick)) {
        ArtImage(
            album.songs.first(),
            Modifier.size(size).shadow(8.dp, RoundedCornerShape(6.dp)),
            sizePx = 400,
            shape = RoundedCornerShape(6.dp),
            iconSize = 40.dp,
        )
        Spacer(Modifier.height(8.dp))
        Text(album.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
        Text(subtitle, color = Palette.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
    }
}

@Composable
fun ArtistBubble(artist: ArtistGroup, onClick: () -> Unit, size: Dp = 120.dp, label: String = "Artist") {
    Column(Modifier.width(size).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        ArtImage(artist.songs.first(), Modifier.size(size), sizePx = 300, shape = CircleShape, iconSize = 36.dp)
        Spacer(Modifier.height(8.dp))
        Text(artist.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
        Text(label, color = Palette.SubText, fontSize = 13.sp)
    }
}

/** A horizontal row of cards under a section title. */
@Composable
fun <T> CardRow(title: String, entries: List<T>, key: (T) -> Any, card: @Composable (T) -> Unit) {
    if (entries.isEmpty()) return
    SectionTitle(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(entries, key = key) { card(it) }
    }
}

/** The big round play button on album, artist and playlist pages. */
@Composable
fun PlayCircleButton(onClick: () -> Unit, playing: Boolean = false, size: Dp = 56.dp, enabled: Boolean = true) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = Palette.OnAccent,
        shadowElevation = 6.dp,
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

/**
 * A slim seek bar: thin track, a dot that grows while dragging.
 * [onDrag] reports the position being dragged to (or null when let go) so the time can follow it.
 */
@Composable
fun SeekBar(
    fraction: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onDrag: (Float?) -> Unit = {},
    enabled: Boolean = true,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val drag by rememberUpdatedState(onDrag)
    val shown = (dragging ?: fraction).coerceIn(0f, 1f)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics { contentDescription = "Seek bar" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { o -> seek((o.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = (o.x / size.width).coerceIn(0f, 1f); drag(dragging) },
                    onDragEnd = { dragging?.let { seek(it) }; dragging = null; drag(null) },
                    onDragCancel = { dragging = null; drag(null) },
                ) { change, _ ->
                    dragging = (change.position.x / size.width).coerceIn(0f, 1f)
                    drag(dragging)
                }
            },
    ) {
        val trackH = (if (dragging != null) 6.dp else 4.dp).toPx()
        val y = size.height / 2
        val r = CornerRadius(trackH / 2, trackH / 2)
        drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, y - trackH / 2), Size(size.width, trackH), r)
        drawRoundRect(Color.White, Offset(0f, y - trackH / 2), Size(size.width * shown, trackH), r)
        drawCircle(Color.White, radius = (if (dragging != null) 8.dp else 6.dp).toPx(), center = Offset(size.width * shown, y))
    }
}

@Composable
fun PlaylistPickerDialog(vm: MusicViewModel, song: Song) {
    AlertDialog(
        onDismissRequest = { vm.playlistPickerFor = null },
        containerColor = Palette.Elevated,
        title = { Text("Add to playlist") },
        text = {
            LazyColumn {
                item {
                    ListItem(
                        headlineContent = { Text("New playlist") },
                        leadingContent = { IconTile(Icons.Rounded.Add, null, Modifier.size(44.dp)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            vm.playlistPickerFor = null
                            vm.nameRequest = NameRequest("New playlist", "") { vm.createPlaylist(it, song) }
                        },
                    )
                }
                items(vm.playlists.userPlaylists, key = { it.id }) { p ->
                    val first = p.songIds.firstNotNullOfOrNull { vm.songsById[it] }
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text(songCount(p.songIds.size)) },
                        leadingContent = { ArtImage(first, Modifier.size(44.dp)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
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
        containerColor = Palette.Elevated,
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

fun smartIcon(kind: SmartPlaylist): ImageVector = when (kind) {
    SmartPlaylist.MOST_PLAYED -> Icons.Rounded.Whatshot
    SmartPlaylist.RECENTLY_ADDED -> Icons.Rounded.NewReleases
    SmartPlaylist.NOT_PLAYED_LATELY -> Icons.Rounded.History
    SmartPlaylist.NEVER_PLAYED -> Icons.Rounded.AutoAwesome
}

/** Fix a song's title, artist, album, music director or year. Kept in the app; the file isn't changed. */
@Composable
fun EditSongDialog(vm: MusicViewModel, song: Song, onDismiss: () -> Unit) {
    var title by remember(song.id) { mutableStateOf(song.title) }
    var artist by remember(song.id) { mutableStateOf(song.artist.takeIf { it != Song.UNKNOWN_ARTIST }.orEmpty()) }
    var album by remember(song.id) { mutableStateOf(song.album) }
    var composer by remember(song.id) { mutableStateOf(song.composer) }
    var year by remember(song.id) { mutableStateOf(song.year.takeIf { it > 0 }?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Elevated,
        title = { Text("Edit song details") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { EditField("Title", title) { title = it } }
                item { EditField("Artist", artist) { artist = it } }
                item { EditField("Album or movie", album) { album = it } }
                item { EditField("Music director", composer) { composer = it } }
                item { EditField("Year", year, numbers = true) { year = it.filter(Char::isDigit).take(4) } }
                item {
                    Text(
                        "Changes are kept in Isaialai. The song file itself isn't changed, so other apps still see the original details.",
                        color = Palette.SubText,
                        fontSize = 12.sp,
                    )
                }
                if (vm.isEdited(song)) {
                    item {
                        TextButton(onClick = { onDismiss(); vm.saveEdit(song, null) }) { Text("Undo my changes") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    onDismiss()
                    vm.saveEdit(song, SongEdits.Edit(title, artist, album, composer, year.toIntOrNull() ?: 0))
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditField(label: String, value: String, numbers: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(120)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = if (numbers) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear $label") }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Pick a picture from the phone as a song's cover (whether or not it already has one), for just
 * that song or its whole album or movie. Or go back to the cover in the file.
 */
@Composable
fun CoverDialog(vm: MusicViewModel, request: CoverRequest, onDismiss: () -> Unit) {
    val albumToo = request.albumSongs.size > 1
    var wholeAlbum by remember(request) { mutableStateOf(albumToo) }
    val targets = if (wholeAlbum) request.albumSongs else listOf(request.song)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setCover(targets, uri)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Elevated,
        title = { Text("Change cover art") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ArtImage(request.song, Modifier.size(72.dp), sizePx = 300, shape = RoundedCornerShape(6.dp))
                    Text(request.song.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (albumToo) {
                    CoverScope("Just this song", selected = !wholeAlbum) { wholeAlbum = false }
                    CoverScope("All ${songCount(request.albumSongs.size)} in “${request.albumName}”", selected = wholeAlbum) { wholeAlbum = true }
                }
                Text(
                    "Choose any picture from your phone. Isaialai keeps its own copy; the song files aren't changed.",
                    color = Palette.SubText,
                    fontSize = 13.sp,
                )
                if (vm.hasCover(targets)) {
                    TextButton(onClick = { onDismiss(); vm.removeCover(targets) }) { Text("Use the cover from the file") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Choose picture") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CoverScope(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
