package io.github.akrishna87.mymusic.ui

import android.Manifest
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.akrishna87.mymusic.CutKind
import io.github.akrishna87.mymusic.MusicViewModel
import io.github.akrishna87.mymusic.Song
import io.github.akrishna87.mymusic.SongCutter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "1:05" or "1:05.5" → milliseconds; null if it isn't a time. */
private fun parseTime(text: String): Long? {
    val t = text.trim()
    val parts = t.split(':')
    return try {
        when (parts.size) {
            1 -> (parts[0].toDouble() * 1000).toLong()
            2 -> parts[0].toLong() * 60_000 + (parts[1].toDouble() * 1000).toLong()
            3 -> parts[0].toLong() * 3_600_000 + parts[1].toLong() * 60_000 + (parts[2].toDouble() * 1000).toLong()
            else -> null
        }
    } catch (e: NumberFormatException) {
        null
    }
}

/**
 * Cut part of a song into a new file: choose where it starts and ends, listen to it, then save
 * it as a song or as the phone's ringtone, notification or alarm sound.
 */
@Composable
fun CutScreen(vm: MusicViewModel, songId: String, ringtone: Boolean) {
    val song = vm.songsById[songId]
    if (song == null) {
        Box(Modifier.fillMaxSize().background(Palette.Background), contentAlignment = Alignment.Center) {
            Text("This song isn't on the phone any more.", color = Palette.SubText)
        }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    // The song's length: from the library, or read from the file if the library doesn't know it.
    var duration by remember(songId) { mutableLongStateOf(song.durationMs) }
    LaunchedEffect(songId) {
        if (duration <= 0) {
            duration = withContext(Dispatchers.IO) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, song.uri)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } catch (e: Exception) {
                    0L
                } finally {
                    try { r.release() } catch (e: Exception) { /* ignore */ }
                }
            }
        }
    }

    var startMs by remember(songId) { mutableLongStateOf(0L) }
    var endMs by remember(songId) { mutableLongStateOf(0L) }
    var kind by remember(songId) { mutableStateOf(if (ringtone) CutKind.RINGTONE else CutKind.SONG) }
    var name by remember(songId) { mutableStateOf(song.title + if (ringtone) " ringtone" else " (cut)") }
    // A ringtone starts as the first 30 seconds; a cut starts as the whole song.
    LaunchedEffect(duration) {
        if (duration > 0 && endMs == 0L) endMs = if (ringtone) minOf(duration, 30_000L) else duration
    }

    var startText by remember(songId) { mutableStateOf("0:00") }
    var endText by remember(songId) { mutableStateOf("") }
    LaunchedEffect(startMs) { startText = formatTime(startMs) }
    LaunchedEffect(endMs) { endText = formatTime(endMs) }

    fun setStart(ms: Long) { startMs = ms.coerceIn(0, (endMs - 1_000).coerceAtLeast(0)) }
    fun setEnd(ms: Long) { endMs = ms.coerceIn((startMs + 1_000).coerceAtMost(duration), duration) }

    // ----- Preview: plays just the chosen part, on its own player -----
    val preview = remember { ExoPlayer.Builder(context).build() }
    var previewing by remember { mutableStateOf(false) }
    DisposableEffect(preview) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) previewing = false
            }
        }
        preview.addListener(listener)
        onDispose {
            preview.removeListener(listener)
            preview.release()
        }
    }
    fun stopPreview() {
        preview.stop()
        previewing = false
    }
    fun playPreview() {
        vm.pause()
        preview.setMediaItem(
            MediaItem.Builder().setUri(song.uri)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build())
                .build(),
        )
        preview.prepare()
        preview.play()
        previewing = true
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { stopPreview() }

    // ----- Saving -----
    var progress by remember { mutableStateOf<Int?>(null) }
    var askPermissionFor by remember { mutableStateOf<Pair<Uri, CutKind>?>(null) }
    var waitingForPermission by remember { mutableStateOf<Pair<Uri, CutKind>?>(null) }

    fun finishRingtone(uri: Uri, k: CutKind) {
        if (SongCutter.canSetRingtones(context)) {
            try {
                SongCutter.setAsDefault(context, uri, k)
                vm.say("Set as your ${k.label.lowercase()}")
            } catch (e: Exception) {
                vm.say("Saved, but Android didn't let Isaialai set it. Pick it in Settings → Sound.")
            }
            vm.back()
        } else {
            askPermissionFor = uri to k
        }
    }

    // Back from Android's "Modify system settings" screen: finish setting the ringtone if allowed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val waiting = waitingForPermission ?: return@LifecycleEventEffect
        waitingForPermission = null
        if (SongCutter.canSetRingtones(context)) {
            finishRingtone(waiting.first, waiting.second)
        } else {
            vm.say("Saved in ${waiting.second.folder}. Pick it any time in Settings → Sound.")
            vm.back()
        }
    }

    fun save() {
        stopPreview()
        val k = kind
        val title = name.trim().ifEmpty { song.title }
        scope.launch {
            progress = 0
            try {
                val file = SongCutter.cut(context, song, startMs, endMs) { progress = it }
                val uri = SongCutter.saveToPhone(context, file, title, song.artist, k)
                progress = null
                if (k == CutKind.SONG) {
                    vm.say("Saved “$title” to ${CutKind.SONG.folder}")
                    vm.refreshLibrary()
                    vm.back()
                } else {
                    finishRingtone(uri, k)
                }
            } catch (e: SongCutter.CutFailed) {
                progress = null
                vm.say(e.message ?: "Couldn't cut this song")
            } catch (e: SecurityException) {
                progress = null
                vm.say("Android didn't allow saving there")
            }
        }
    }

    // Android 9 and older ask before an app saves a new file to shared storage.
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) save() else vm.say("Isaialai needs storage access to save the cut")
    }
    fun onSave() {
        if (Build.VERSION.SDK_INT < 29) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else save()
    }

    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(if (ringtone) "Make a ringtone" else "Cut song", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.fillMaxSize()
                // Tapping anywhere outside a time field closes the keyboard and applies the time.
                .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } }
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalBottomSpace.current),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ArtImage(song, Modifier.size(64.dp), sizePx = 240, shape = RoundedCornerShape(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${song.artist} · ${formatTime(duration)}", color = Palette.SubText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (duration <= 0) {
                Text("Reading the song…", color = Palette.SubText)
                return@Column
            }

            SettingsCard {
                Text("Part to keep: ${formatTime(endMs - startMs)}", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                RangeSlider(
                    value = startMs.toFloat()..endMs.toFloat(),
                    onValueChange = { r ->
                        if (previewing) stopPreview()
                        setStart(r.start.toLong())
                        setEnd(r.endInclusive.toLong())
                    },
                    valueRange = 0f..duration.toFloat(),
                    modifier = Modifier.semantics { contentDescription = "Part to keep" },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeField("Start", startText, Modifier.weight(1f), onChange = { startText = it }, onDone = { focus.clearFocus() }) {
                        parseTime(startText)?.let(::setStart)
                        startText = formatTime(startMs)
                    }
                    TimeField("End", endText, Modifier.weight(1f), onChange = { endText = it }, onDone = { focus.clearFocus() }) {
                        parseTime(endText)?.let(::setEnd)
                        endText = formatTime(endMs)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-5_000L to "Start −5s", 5_000L to "Start +5s").forEach { (d, label) ->
                        AssistChip(onClick = { setStart(startMs + d) }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-5_000L to "End −5s", 5_000L to "End +5s").forEach { (d, label) ->
                        AssistChip(onClick = { setEnd(endMs + d) }, label = { Text(label) })
                    }
                }
                FilledTonalButton(onClick = { if (previewing) stopPreview() else playPreview() }) {
                    Icon(if (previewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (previewing) "Stop" else "Play this part")
                }
            }

            SettingsCard {
                Text("Save as", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                CutKind.entries.forEach { k ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { kind = k },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = kind == k, onClick = { kind = k })
                        Text(
                            when (k) {
                                CutKind.SONG -> "A new song (in ${CutKind.SONG.folder})"
                                else -> "My ${k.label.lowercase()}"
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "The original song isn't changed. The part you keep is saved as a new file on your phone.",
                    color = Palette.SubText,
                    fontSize = 13.sp,
                )
            }

            val p = progress
            if (p != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Cutting… $p%")
                }
            } else {
                Button(
                    onClick = ::onSave,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Rounded.ContentCut, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (kind == CutKind.SONG) "Save" else "Save and set", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    askPermissionFor?.let { pending ->
        AlertDialog(
            onDismissRequest = { askPermissionFor = null },
            containerColor = Palette.Elevated,
            title = { Text("Allow changing your ringtone?") },
            text = {
                Text(
                    "It's saved in ${pending.second.folder}. To set it for you, Android needs you to turn on " +
                        "“Allow modifying system settings” for Isaialai. Then come back here.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askPermissionFor = null
                    waitingForPermission = pending
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = {
                    askPermissionFor = null
                    vm.say("Saved in ${pending.second.folder}. Pick it any time in Settings → Sound.")
                    vm.back()
                }) { Text("Not now") }
            },
        )
    }
}

/** A time like 1:05. What's typed is applied ([onCommit]) when the field loses focus. */
@Composable
private fun TimeField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit, onDone: () -> Unit, onCommit: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == ':' || c == '.' }.take(9)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier
            .onFocusChanged { state ->
                if (focused && !state.isFocused) onCommit()
                focused = state.isFocused
            }
            .semantics { contentDescription = "$label time" },
    )
}
