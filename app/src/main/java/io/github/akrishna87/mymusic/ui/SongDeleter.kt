package io.github.akrishna87.mymusic.ui

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import io.github.akrishna87.mymusic.MusicViewModel

/**
 * Deletes the song a ⋮ menu asked to (vm.deleteRequest). Android shows its own
 * "Allow Isaialai to delete this audio file?" first; nothing is deleted unless you allow it.
 */
@Composable
fun SongDeleter(vm: MusicViewModel) {
    val context = LocalContext.current
    val deleter = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val song = vm.deleteRequest
        vm.deleteRequest = null
        if (result.resultCode == Activity.RESULT_OK) {
            vm.say("Deleted “${song?.title ?: "the song"}”")
            vm.refreshLibrary()
        }
    }
    val song = vm.deleteRequest
    LaunchedEffect(song) {
        if (song == null || Build.VERSION.SDK_INT < 30) return@LaunchedEffect
        try {
            val request = MediaStore.createDeleteRequest(context.contentResolver, listOf(song.uri))
            deleter.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } catch (e: Exception) {
            vm.deleteRequest = null
            vm.say("Couldn't delete that file")
        }
    }
}
