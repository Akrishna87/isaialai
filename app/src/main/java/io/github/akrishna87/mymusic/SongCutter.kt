package io.github.akrishna87.mymusic

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** What a cut part of a song is saved as. */
enum class CutKind(val label: String, val folder: String) {
    SONG("Song", "Music/Isaialai cuts"),
    RINGTONE("Phone ringtone", Environment.DIRECTORY_RINGTONES),
    NOTIFICATION("Notification sound", Environment.DIRECTORY_NOTIFICATIONS),
    ALARM("Alarm sound", Environment.DIRECTORY_ALARMS),
    ;

    val ringtoneType: Int?
        get() = when (this) {
            SONG -> null
            RINGTONE -> RingtoneManager.TYPE_RINGTONE
            NOTIFICATION -> RingtoneManager.TYPE_NOTIFICATION
            ALARM -> RingtoneManager.TYPE_ALARM
        }
}

/**
 * Cuts part of a song into a new audio file (AAC in .m4a) and saves it to the phone's Music,
 * Ringtones, Notifications or Alarms folder. The original song is never changed.
 */
object SongCutter {

    class CutFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Writes [startMs]..[endMs] of [song] to a file in the app's cache and returns it.
     * [onProgress] gets 0..100 now and then.
     */
    @OptIn(UnstableApi::class)
    suspend fun cut(context: Context, song: Song, startMs: Long, endMs: Long, onProgress: (Int) -> Unit): File = coroutineScope {
        val out = File(context.cacheDir, "cuts").apply { mkdirs() }.resolve("cut-${System.currentTimeMillis()}.m4a")
        val item = MediaItem.Builder()
            .setUri(song.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build(),
            )
            .build()
        val edited = EditedMediaItem.Builder(item).setRemoveVideo(true).build()
        withContext(Dispatchers.Main) {
            val done = CompletableDeferred<Unit>()
            val transformer = Transformer.Builder(context)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        done.complete(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        done.completeExceptionally(CutFailed("Couldn't cut this song", exportException))
                    }
                })
                .build()
            transformer.start(edited, out.absolutePath)
            val progress = launch {
                val holder = ProgressHolder()
                while (isActive) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    delay(200)
                }
            }
            try {
                done.await()
            } catch (e: CancellationException) {
                transformer.cancel()
                out.delete()
                throw e
            } finally {
                progress.cancel()
            }
        }
        if (!out.isFile || out.length() == 0L) throw CutFailed("Couldn't cut this song")
        out
    }

    /**
     * Copies [file] into the phone's shared storage as [kind] and returns its address in the media
     * library. On Android 9 and older this needs the storage write permission.
     */
    suspend fun saveToPhone(context: Context, file: File, title: String, artist: String, kind: CutKind): Uri =
        withContext(Dispatchers.IO) {
            val name = safeName(title) + ".m4a"
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.IS_MUSIC, kind == CutKind.SONG)
                put(MediaStore.Audio.Media.IS_RINGTONE, kind == CutKind.RINGTONE)
                put(MediaStore.Audio.Media.IS_NOTIFICATION, kind == CutKind.NOTIFICATION)
                put(MediaStore.Audio.Media.IS_ALARM, kind == CutKind.ALARM)
            }
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Audio.Media.RELATIVE_PATH, kind.folder + "/")
                values.put(MediaStore.Audio.Media.IS_PENDING, 1)
                val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    ?: throw CutFailed("Couldn't save to your phone")
                try {
                    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        ?: throw CutFailed("Couldn't save to your phone")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw if (e is CutFailed) e else CutFailed("Couldn't save to your phone", e)
                }
                file.delete()
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStorageDirectory(), kind.folder).apply { mkdirs() }
                var target = File(dir, name)
                var n = 2
                while (target.exists()) target = File(dir, safeName(title) + " ($n).m4a").also { n++ }
                file.copyTo(target)
                file.delete()
                scan(context, target) ?: throw CutFailed("Saved, but the phone didn't add it to its library")
            }
        }

    private suspend fun scan(context: Context, file: File): Uri? = suspendCancellableCoroutine { cont ->
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("audio/mp4")) { _, uri ->
            if (cont.isActive) cont.resume(uri)
        }
    }

    /** Whether Android lets Isaialai change the phone's ringtone (the "Modify system settings" permission). */
    fun canSetRingtones(context: Context): Boolean = Settings.System.canWrite(context)

    /** Makes [uri] the phone's ringtone, notification or alarm sound. */
    fun setAsDefault(context: Context, uri: Uri, kind: CutKind) {
        val type = kind.ringtoneType ?: return
        RingtoneManager.setActualDefaultRingtoneUri(context, type, uri)
    }

    private fun safeName(title: String): String =
        title.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "Isaialai cut" }
}
