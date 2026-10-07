package io.github.akrishna87.mymusic.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.mymusic.AddedFolders
import io.github.akrishna87.mymusic.BackupStorage
import io.github.akrishna87.mymusic.MusicViewModel
import io.github.akrishna87.mymusic.RestorePlan
import java.text.DateFormat
import java.util.Date

/** The file picker, opening in Download (where shared and downloaded files usually are). */
private class OpenDocumentInDownload : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(AddedFolders.EXTERNAL_STORAGE, "primary:Download"))
}

@Composable
fun BackupCard(vm: MusicViewModel) {
    var explain by remember { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.readRestoreFolder(uri)
    }
    val filePicker = rememberLauncherForActivityResult(OpenDocumentInDownload()) { uri ->
        if (uri != null) vm.readRestoreFile(uri)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.backUpNow() else vm.say("Without storage access, Isaialai can't save a backup in Download")
    }
    @Suppress("UNUSED_VARIABLE")
    val refresh = vm.backupVersion // re-read the status below after each backup
    val needsPermission = vm.backupNeedsPermission()
    val backUp = { if (needsPermission) permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else vm.backUpNow() }

    SettingsCard {
        SwitchRow(
            "Automatic backup",
            "Keeps a copy of your playlists, liked songs, play counts, song edits and settings in " +
                "${vm.backupLocation}, updated as you go. Each playlist is also saved as a .m3u file " +
                "that other music apps can open.",
            checked = vm.autoBackup,
            onChange = vm::setAutoBackupOn,
        )
        val error = vm.backupError
        val last = vm.lastBackupAt
        Text(
            when {
                error != null -> error
                last > 0 -> "Last backup: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last))
                else -> "No backup yet"
            },
            color = if (error != null || needsPermission) MaterialTheme.colorScheme.primary else Palette.SubText,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(4.dp))
        FilledTonalButton(onClick = backUp, modifier = Modifier.fillMaxWidth()) {
            Text(if (needsPermission) "Allow storage access and back up" else "Back up now")
        }
        FilledTonalButton(onClick = { explain = true }, modifier = Modifier.fillMaxWidth()) { Text("Restore from backup") }
        OutlinedButton(
            onClick = {
                try {
                    filePicker.launch(arrayOf("*/*"))
                } catch (e: ActivityNotFoundException) {
                    vm.say("This phone has no file picker")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Import a playlist file (.m3u)") }
        Text(
            "New phone? Copy the ${BackupStorage.FOLDER} folder from Download to the new phone with your file manager " +
                "(or a USB cable), along with your music. Then install Isaialai and tap Restore from backup.",
            color = Palette.SubText,
            fontSize = 13.sp,
        )
    }

    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text("Restore from a backup") },
            text = {
                Text(
                    "Choose the ${BackupStorage.FOLDER} folder (it's in Download, or wherever you copied it), " +
                        "tap “Use this folder”, then “Allow”. A folder of .m3u playlists from another music app works too.\n\n" +
                        "Restoring adds to what's here; nothing is deleted.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explain = false
                    try {
                        folderPicker.launch(BackupStorage.pickerStart)
                    } catch (e: ActivityNotFoundException) {
                        vm.say("This phone has no folder picker")
                    }
                }) { Text("Choose folder") }
            },
            dismissButton = { TextButton(onClick = { explain = false }) { Text("Cancel") } },
        )
    }

    vm.restorePlan?.let { RestoreDialog(vm, it) }
}

@Composable
private fun RestoreDialog(vm: MusicViewModel, plan: RestorePlan) {
    var withSettings by remember(plan) { mutableStateOf(true) }
    val isBackup = plan.backedUpAt > 0 || plan.settings != null || plan.liked.isNotEmpty() || plan.plays.isNotEmpty()
    AlertDialog(
        onDismissRequest = { vm.restorePlan = null },
        title = { Text(if (isBackup) "Restore this backup?" else "Import playlists?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val made = if (plan.backedUpAt > 0) " · made " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(plan.backedUpAt)) else ""
                Text("From ${plan.source}$made", color = Palette.SubText, fontSize = 13.sp)
                val lines = buildList {
                    when (plan.playlists.size) {
                        0 -> Unit
                        1 -> add("“${plan.playlists[0].first}” (${songCount(plan.playlists[0].second.size)})")
                        else -> add("${plan.playlists.size} playlists (${songCount(plan.songsInPlaylists)})")
                    }
                    if (plan.liked.isNotEmpty()) add("${plan.liked.size} liked ${if (plan.liked.size == 1) "song" else "songs"}")
                    if (plan.plays.isNotEmpty()) add("Play counts for ${songCount(plan.plays.size)}")
                    if (plan.edits.isNotEmpty()) add("${plan.edits.size} song ${if (plan.edits.size == 1) "edit" else "edits"}")
                }
                lines.forEach { Text("•  $it", fontWeight = FontWeight.SemiBold) }
                if (plan.missing > 0) {
                    Text(
                        "${songCount(plan.missing)} ${if (plan.missing == 1) "isn't" else "aren't"} on this phone and will be left out. " +
                            "Copy your music over first, or restore again afterwards.",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                    )
                }
                if (plan.settings != null) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = withSettings, role = Role.Checkbox, onValueChange = { withSettings = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = withSettings, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Also restore settings (theme, equaliser, crossfade…)")
                    }
                }
                Text("Nothing here is deleted or replaced.", color = Palette.SubText, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = { vm.restore(plan, withSettings) }) { Text(if (isBackup) "Restore" else "Import") } },
        dismissButton = { TextButton(onClick = { vm.restorePlan = null }) { Text("Cancel") } },
    )
}

/** For the playlist page: saves the playlist as a .m3u file wherever you choose. */
@Composable
fun rememberPlaylistExporter(vm: MusicViewModel, playlistId: String): (String) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri: Uri? ->
        if (uri != null) vm.exportPlaylist(playlistId, uri)
    }
    return { fileName ->
        try {
            launcher.launch(fileName)
        } catch (e: ActivityNotFoundException) {
            vm.say("This phone has no file picker")
        }
    }
}
