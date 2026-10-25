package io.github.akrishna87.mymusic.tv

import android.app.Application
import android.content.ComponentName
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import kotlin.random.Random

/** A list the TV can open (a playlist, album, artist…). */
data class TvFolder(val id: String, val title: String, val subtitle: String, val cover: String)

data class TvSong(val id: String, val title: String, val artist: String, val album: String, val durationMs: Long)

/** One screen of browsing: a list of lists, or a list of songs. */
class TvPage(val id: String, val title: String) {
    var folders by mutableStateOf<List<TvFolder>>(emptyList())
    var songs by mutableStateOf<List<TvSong>?>(null)
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
}

sealed interface TvScreen {
    data object Connect : TvScreen
    data class Pairing(val phone: String, val code: String) : TvScreen
    data object Main : TvScreen
}

class TvViewModel(app: Application) : AndroidViewModel(app) {

    var screen by mutableStateOf<TvScreen>(TvScreen.Connect); private set
    var link by mutableStateOf(PhoneLink.saved(app)); private set
    val finder = PhoneFinder(app)
    /** Shown on the connect screen: why the TV isn't connected. */
    var problem by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    private var pairJob: Job? = null

    /** The phone's top-level lists (Recently played, Liked songs, Playlists…). */
    var sections by mutableStateOf<List<TvFolder>>(emptyList()); private set
    /** What's open: the chosen section first, then whatever was opened inside it. */
    val pages = mutableStateListOf<TvPage>()
    var showPlayer by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    // ----- The player, mirrored for the screens -----
    var currentId by mutableStateOf<String?>(null); private set
    var title by mutableStateOf(""); private set
    var artist by mutableStateOf(""); private set
    var isPlaying by mutableStateOf(false); private set
    var shuffle by mutableStateOf(false); private set
    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var currentIndex by mutableIntStateOf(0); private set
    /** Songs coming up: (queue index, title, artist). */
    var upNext by mutableStateOf<List<Triple<Int, String, String>>>(emptyList()); private set

    private var controller: MediaController? = null
    private val controllerFuture = MediaController.Builder(app, SessionToken(app, ComponentName(app, TvPlaybackService::class.java))).buildAsync()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()

        override fun onPlayerError(error: PlaybackException) {
            message = "Can't play from ${link?.name ?: "your phone"}. Check sharing is still on (Settings → Play on TV app on the phone)."
        }
    }

    /** This TV's name as the phone shows it. */
    val tvName: String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    init {
        viewModelScope.launch {
            val c = try {
                controllerFuture.await()
            } catch (e: Exception) {
                return@launch
            }
            controller = c
            c.addListener(listener)
            sync()
        }
        viewModelScope.launch {
            while (true) {
                controller?.let {
                    positionMs = it.currentPosition.coerceAtLeast(0)
                    durationMs = it.duration.let { d -> if (d == C.TIME_UNSET || d < 0) 0 else d }
                }
                delay(500)
            }
        }
        reconnect()
    }

    override fun onCleared() {
        finder.stop()
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
    }

    // ----- Connecting -----

    /** Opens the saved phone, finding it again if its address changed. */
    fun reconnect() {
        if (busy) return
        val saved = link ?: return lookForPhones(null)
        busy = true
        viewModelScope.launch {
            try {
                PhoneApi.get(saved.hello(), 4_000)
                problem = null
                enter()
            } catch (e: GoneException) {
                forgetPhone("${saved.name} no longer lets this TV play its music. Pick it to ask again.")
            } catch (e: IOException) {
                lookForPhones(
                    "Can't reach ${saved.name}. On the phone, open Isaialai → Settings → Play on TV app and turn on " +
                        "Share with your TV.",
                )
            } finally {
                busy = false
            }
        }
    }

    private fun lookForPhones(why: String?) {
        problem = why
        screen = TvScreen.Connect
        finder.start()
    }

    /** The saved phone showed up again on the Wi-Fi (maybe at a new address): carry on with it. */
    fun phoneFound(found: FoundPhone) {
        val saved = link ?: return
        if (busy || screen != TvScreen.Connect || found.id.isEmpty() || found.id != saved.id) return
        val moved = saved.copy(host = found.host, port = found.port)
        PhoneLink.save(getApplication(), moved)
        link = moved
        reconnect()
    }

    fun forgetPhone(why: String? = null) {
        PhoneLink.save(getApplication(), null)
        link = null
        sections = emptyList()
        pages.clear()
        showSettings = false
        controller?.run {
            stop()
            clearMediaItems()
        }
        lookForPhones(why)
    }

    /** Pick a phone (found on the Wi-Fi, or typed in as "192.168.1.5:47123") and ask it to pair. */
    fun pair(found: FoundPhone) {
        if (busy) return
        val saved = link
        if (saved != null && found.id.isNotEmpty() && found.id == saved.id) return phoneFound(found)
        busy = true
        problem = null
        pairJob = viewModelScope.launch {
            try {
                val phone = PhoneApi.hello(found.host, found.port)
                val code = Random.nextInt(0, 10_000).toString().padStart(4, '0')
                val origin = "http://${if (':' in found.host) "[${found.host}]" else found.host}:${found.port}"
                val request = PhoneApi.post("$origin/pair", JSONObject().put("name", tvName).put("code", code)).getString("request")
                screen = TvScreen.Pairing(phone.name, code)
                busy = false
                while (true) {
                    delay(1_500)
                    val r = PhoneApi.get("$origin/pair/$request")
                    when (r.optString("status")) {
                        "ok" -> {
                            val l = PhoneLink(phone.id, phone.name, found.host, found.port, r.getString("key"))
                            PhoneLink.save(getApplication(), l)
                            link = l
                            enter()
                            return@launch
                        }
                        "denied" -> {
                            lookForPhones("${phone.name} didn't allow this TV.")
                            return@launch
                        }
                    }
                }
            } catch (e: GoneException) {
                lookForPhones("Nobody answered on the phone in time. Pick it to ask again.")
            } catch (e: IOException) {
                lookForPhones("Couldn't reach that phone. Check sharing is on there, and that the TV and phone are on the same Wi-Fi.")
            } catch (e: org.json.JSONException) {
                lookForPhones("That doesn't look like Isaialai.")
            } finally {
                busy = false
            }
        }
    }

    /** "192.168.1.5:47123" (or just the address) typed with the remote. */
    fun pairTyped(text: String) {
        val t = text.trim()
        val host = t.substringBeforeLast(':').takeIf { ':' in t } ?: t
        val port = t.substringAfterLast(':', "").toIntOrNull()?.takeIf { ':' in t } ?: io.github.akrishna87.mymusic.TvShareServer.PREFERRED_PORT
        if (host.isEmpty() || !Regex("[0-9a-fA-F.:\\[\\]]+").matches(host)) {
            problem = "Type the address the phone shows, like 192.168.1.5:47123."
            return
        }
        pair(FoundPhone("", host.trim('[', ']'), host.trim('[', ']'), port))
    }

    fun cancelPairing() {
        pairJob?.cancel()
        busy = false
        lookForPhones(null)
    }

    private suspend fun enter() {
        finder.stop()
        screen = TvScreen.Main
        if (sections.isEmpty()) {
            val l = link ?: return
            try {
                sections = folders(PhoneApi.get(l.browse("root")))
                sections.getOrNull(sections.indexOfFirst { it.id == "playlists" }.coerceAtLeast(0))?.let { openSection(it) }
            } catch (e: IOException) {
                message = "Couldn't read the lists from ${l.name}."
            }
        }
    }

    // ----- Browsing -----

    fun openSection(folder: TvFolder) {
        pages.clear()
        showSettings = false
        open(folder)
    }

    fun open(folder: TvFolder) {
        val l = link ?: return
        val page = TvPage(folder.id, folder.title)
        pages += page
        viewModelScope.launch {
            try {
                val o = PhoneApi.get(l.browse(folder.id), 15_000)
                page.folders = folders(o)
                page.songs = o.optJSONArray("songs")?.let { arr ->
                    List(arr.length()) { i ->
                        val s = arr.getJSONObject(i)
                        TvSong(s.getString("id"), s.optString("title"), s.optString("artist"), s.optString("album"), s.optLong("durationMs"))
                    }
                }
            } catch (e: GoneException) {
                page.error = "This list isn't on the phone any more."
            } catch (e: IOException) {
                page.error = "Couldn't reach ${l.name}. Check sharing is still on there."
            } finally {
                page.loading = false
            }
        }
    }

    /** Back: up one list. False when already at the top. */
    fun back(): Boolean {
        if (pages.size > 1) {
            pages.removeAt(pages.lastIndex)
            return true
        }
        return false
    }

    private fun folders(o: JSONObject): List<TvFolder> = o.optJSONArray("folders")?.let { arr ->
        List(arr.length()) { i ->
            val f = arr.getJSONObject(i)
            TvFolder(f.getString("id"), f.optString("title"), f.optString("subtitle"), f.optString("cover"))
        }
    }.orEmpty()

    fun artUrl(songId: String?): String? = if (songId.isNullOrEmpty()) null else link?.art(songId)

    // ----- Playing -----

    fun play(songs: List<TvSong>, start: Int, shuffled: Boolean = false) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        c.shuffleModeEnabled = shuffled
        val items = songs.map { s ->
            MediaItem.Builder()
                .setMediaId(s.id)
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album.ifEmpty { null }).build(),
                )
                .build()
        }
        c.setMediaItems(items, if (shuffled) Random.nextInt(items.size) else start.coerceIn(0, items.size - 1), 0L)
        c.prepare()
        c.play()
        message = null
        showPlayer = true
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
            c.play()
        }
    }

    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPrevious()
    fun rewind() = controller?.seekBack()
    fun forward() = controller?.seekForward()
    fun jumpTo(index: Int) = controller?.let { it.seekTo(index, 0L); it.play() }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    private fun sync() {
        val c = controller ?: return
        val item = c.currentMediaItem
        currentId = item?.mediaId?.takeIf { it.isNotEmpty() }
        title = item?.mediaMetadata?.title?.toString().orEmpty()
        artist = item?.mediaMetadata?.artist?.toString().orEmpty()
        isPlaying = c.isPlaying
        shuffle = c.shuffleModeEnabled
        currentIndex = c.currentMediaItemIndex
        val tl = c.currentTimeline
        val next = ArrayList<Triple<Int, String, String>>()
        if (!tl.isEmpty && c.currentMediaItemIndex != C.INDEX_UNSET) {
            var i = c.currentMediaItemIndex
            while (next.size < 100) {
                i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
                if (i == C.INDEX_UNSET) break
                val md = c.getMediaItemAt(i).mediaMetadata
                next += Triple(i, md.title?.toString().orEmpty(), md.artist?.toString().orEmpty())
            }
        }
        upNext = next
        if (isPlaying) message = null
    }
}
