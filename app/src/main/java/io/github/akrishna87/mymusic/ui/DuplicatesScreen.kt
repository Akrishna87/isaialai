package io.github.akrishna87.mymusic.ui

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.mymusic.LibraryGrouping
import io.github.akrishna87.mymusic.MusicViewModel
import io.github.akrishna87.mymusic.Song

/** Songs that are on the phone more than once, with a way to delete the extra copies. */
@Composable
fun DuplicatesScreen(vm: MusicViewModel) {
    val groups = remember(vm.songs) { LibraryGrouping.duplicates(vm.songs) }
    val context = LocalContext.current
    val deleter = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vm.say("Deleted")
            vm.refreshLibrary()
        }
    }
    // Android asks you to confirm every delete; library songs only, Android 11 and later.
    fun canDelete(song: Song) = Build.VERSION.SDK_INT >= 30 && song.id.toLongOrNull() != null
    fun delete(song: Song) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val request = MediaStore.createDeleteRequest(context.contentResolver, listOf(song.uri))
            deleter.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } catch (e: Exception) {
            vm.say("Couldn't delete that file")
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Duplicate songs", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
            item {
                Text(
                    if (groups.isEmpty()) "No duplicate songs found. Every song is on your phone once."
                    else "These songs are on your phone more than once (same title and artist, about the same length). " +
                        "Play a copy to check it, then delete the ones you don't need.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Palette.SubText,
                    fontSize = 14.sp,
                )
            }
            groups.forEach { g ->
                item(key = "g:" + g.copies.first().id) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
                        Text(g.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${g.artist} · ${g.copies.size} copies", color = Palette.SubText, fontSize = 13.sp)
                    }
                }
                items(g.copies, key = { "c:" + it.id }) { s ->
                    val where = s.folder.ifEmpty { "Phone storage" }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ArtImage(s, Modifier.size(44.dp))
                        Column(Modifier.weight(1f)) {
                            Text(where, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${s.fileName} · ${formatTime(s.durationMs)}", color = Palette.SubText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { vm.play(listOf(s), 0, from = "Duplicate songs") }) {
                            Icon(Icons.Rounded.PlayArrow, "Play the copy in $where")
                        }
                        if (canDelete(s)) {
                            IconButton(onClick = { delete(s) }) {
                                Icon(Icons.Rounded.DeleteOutline, "Delete the copy in $where", tint = Palette.SubText)
                            }
                        }
                    }
                }
            }
            if (groups.any { g -> g.copies.any { !canDelete(it) } }) {
                item {
                    Text(
                        "Copies without a delete button (in folders you added, or on older Android) can be deleted from your Files app.",
                        Modifier.padding(16.dp),
                        color = Palette.SubText,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}
