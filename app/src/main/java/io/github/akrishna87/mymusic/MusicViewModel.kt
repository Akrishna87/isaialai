package io.github.akrishna87.mymusic

import android.app.Application
import android.content.ComponentName
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.akrishna87.mymusic.ui.songCount
import kotlin.random.Random

sealed interface Screen {
    data class Album(val key: String) : Screen
    data class Artist(val name: String) : Screen
    data class Folder(val path: String) : Screen
    data class PlaylistDetail(val id: String) : Screen
}

enum class Section { HOME, SEARCH, LIBRARY }

enum class LibraryChip(val label: String) { SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), FOLDERS("Folders"), PLAYLISTS("Playlists") }

/** A pending "name this playlist" prompt. */
data class NameRequest(val title: String, val initial: String, val onSave: (String) -> Unit)

class MusicViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("ui", 0)

    // ----- Library -----
    var songs by mutableStateOf<List<Song>>(emptyList()); private set
    var songsById by mutableStateOf<Map<String, Song>>(emptyMap()); private set
    var loading by mutableStateOf(true); private set

    /** Folders added with the folder picker, as (folder, path shown in the app). */
    var addedFolders by mutableStateOf<List<Pair<Uri, String>>>(emptyList()); private set
    var scanningFolders by mutableStateOf(false); private set
    private var folderSongs: List<Song> = emptyList()

    // ----- Navigation & UI -----
    val screens = mutableStateListOf<Screen>()
    var showPlayer by mutableStateOf(false)
    var section by mutableStateOf(Section.HOME); private set
    var libraryChip by mutableStateOf(LibraryChip.entries.getOrElse(prefs.getInt("chip", 0)) { LibraryChip.SONGS }); private set
    /** What's typed on the Search screen. */
    var query by mutableStateOf("")
    /** Where the current queue came from, shown at the top of the full player. */
    var playingFrom by mutableStateOf("Your library"); private set
    var sort by mutableStateOf(SongSort.entries.getOrElse(prefs.getInt("sort", 0)) { SongSort.TITLE })
    var playlistPickerFor by mutableStateOf<Song?>(null)
    var nameRequest by mutableStateOf<NameRequest?>(null)
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    val playlists = PlaylistStore(app)

    // ----- Listening history (for Home) -----
    /** Song ids, most recently played first. */
    var history by mutableStateOf(loadHistory()); private set
    private val playCounts: MutableMap<String, Int> = loadCounts()
    var countsVersion by mutableIntStateOf(0); private set
    private var lastRecorded: String? = null

    // ----- Equaliser & sleep timer (applied by PlaybackService) -----
    private val effectsPrefs = Effects.prefs(app)
    /** What the phone's equaliser offers; null until the player service has started once. */
    var eqInfo by mutableStateOf(Effects.readInfo(effectsPrefs)); private set
    var eqSettings by mutableStateOf(Effects.read(effectsPrefs)); private set
    var showEqualizer by mutableStateOf(false)
    /** When the sleep timer stops playback (ms since epoch), or 0. */
    var sleepUntil by mutableLongStateOf(effectsPrefs.getLong(Effects.KEY_SLEEP_UNTIL, 0L)); private set
    var sleepEndOfSong by mutableStateOf(effectsPrefs.getBoolean(Effects.KEY_SLEEP_END_OF_SONG, false)); private set
    private val effectsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
        eqInfo = Effects.readInfo(p)
        eqSettings = Effects.read(p)
        sleepUntil = p.getLong(Effects.KEY_SLEEP_UNTIL, 0L)
        sleepEndOfSong = p.getBoolean(Effects.KEY_SLEEP_END_OF_SONG, false)
    }

    // ----- Player mirror -----
    var currentId by mutableStateOf<String?>(null); private set
    var currentTitle by mutableStateOf(""); private set
    var currentArtist by mutableStateOf(""); private set
    var isPlaying by mutableStateOf(false); private set
    var shuffle by mutableStateOf(false); private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF); private set
    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    /** Upcoming songs as (queue index, song id), in play order. */
    var upNext by mutableStateOf<List<Pair<Int, String>>>(emptyList()); private set

    val currentSong: Song? get() = currentId?.let { songsById[it] }

    private var controller: MediaController? = null
    private val controllerFuture = MediaController.Builder(
        app,
        SessionToken(app, ComponentName(app, PlaybackService::class.java)),
    ).buildAsync()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncFromPlayer()

        override fun onPlayerError(error: PlaybackException) {
            val name = controller?.currentMediaItem?.mediaMetadata?.title ?: "that song"
            messageChannel.trySend("Couldn't play “$name” — skipping it.")
        }
    }

    private var observing = false
    private var refreshJob: Job? = null
    private var libraryJob: Job? = null
    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            // New downloads or deleted files: rescan shortly after things settle down.
            refreshJob?.cancel()
            refreshJob = viewModelScope.launch {
                delay(2_000)
                refreshLibrary()
            }
        }
    }

    init {
        viewModelScope.launch {
            val c = try { controllerFuture.await() } catch (e: Exception) { return@launch }
            controller = c
            c.addListener(playerListener)
            syncFromPlayer()
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
    }

    init {
        effectsPrefs.registerOnSharedPreferenceChangeListener(effectsListener)
    }

    override fun onCleared() {
        effectsPrefs.unregisterOnSharedPreferenceChangeListener(effectsListener)
        if (observing) getApplication<Application>().contentResolver.unregisterContentObserver(mediaObserver)
        controller?.removeListener(playerListener)
        MediaController.releaseFuture(controllerFuture)
    }

    fun onPermissionGranted() {
        if (!observing) {
            getApplication<Application>().contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaObserver,
            )
            observing = true
        }
        refreshLibrary()
    }

    private fun publish(list: List<Song>) {
        songs = list
        songsById = list.associateBy { it.id }
        loading = false
        syncFromPlayer()
    }

    /**
     * Reloads Android's media library (fast), then reads any added folders directly (slower the
     * first time, cached after). [newFolder] is a folder that was just added, to report on.
     */
    fun refreshLibrary(announce: Boolean = false, newFolder: Uri? = null) {
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val library = withContext(Dispatchers.IO) {
                try {
                    MusicRepository.loadSongs(app)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            val known = library.mapTo(HashSet()) { it.locationKey }
            // Keep showing the previous folder songs while the folders are re-read.
            publish(library + folderSongs.filter { it.locationKey !in known })

            val trees = AddedFolders.list(app)
            addedFolders = withContext(Dispatchers.IO) { trees.map { it to AddedFolders.displayPath(app, it) } }
            if (trees.isEmpty()) {
                folderSongs = emptyList()
                publish(library)
            } else {
                scanningFolders = true
                try {
                    val result = withContext(Dispatchers.IO) { AddedFolders.scan(app, trees, known) }
                    folderSongs = result.songs
                    publish(library + result.songs)
                    if (newFolder != null) {
                        val name = addedFolders.firstOrNull { it.first == newFolder }?.second ?: "the folder"
                        val n = result.audioFilesPerFolder[newFolder] ?: 0
                        messageChannel.trySend(if (n == 0) "No songs found in $name" else "Added $name: ${songCount(n)}")
                    }
                } finally {
                    scanningFolders = false
                }
            }
            if (announce) messageChannel.trySend("Found ${songCount(songs.size)}")
        }
    }

    fun addFolder(tree: Uri) {
        try {
            AddedFolders.add(getApplication(), tree)
        } catch (e: SecurityException) {
            messageChannel.trySend("Couldn't get access to that folder")
            return
        }
        messageChannel.trySend("Reading the folder…")
        refreshLibrary(newFolder = tree)
    }

    fun removeFolder(tree: Uri) {
        AddedFolders.remove(getApplication(), tree)
        refreshLibrary()
        messageChannel.trySend("Folder removed. The songs in it are no longer listed.")
    }

    fun say(text: String) {
        messageChannel.trySend(text)
    }

    fun selectSection(s: Section) {
        showPlayer = false
        // Tapping the section you're already in goes back to its start, like other music apps.
        if (s != section || screens.isNotEmpty()) screens.clear()
        section = s
    }

    fun selectChip(c: LibraryChip) {
        libraryChip = c
        prefs.edit().putInt("chip", c.ordinal).apply()
    }

    fun saveSort(s: SongSort) {
        sort = s
        prefs.edit().putInt("sort", s.ordinal).apply()
    }

    fun open(screen: Screen) {
        showPlayer = false
        screens += screen
    }

    fun back(): Boolean = when {
        showEqualizer -> { showEqualizer = false; true }
        showPlayer -> { showPlayer = false; true }
        screens.isNotEmpty() -> { screens.removeAt(screens.lastIndex); true }
        else -> false
    }

    // ----- Playback commands -----

    private fun syncFromPlayer() {
        val c = controller ?: return
        val item = c.currentMediaItem
        currentId = item?.mediaId?.takeIf { it.isNotEmpty() }
        currentTitle = item?.mediaMetadata?.title?.toString().orEmpty()
        currentArtist = item?.mediaMetadata?.artist?.toString().orEmpty()
        isPlaying = c.isPlaying
        shuffle = c.shuffleModeEnabled
        repeatMode = c.repeatMode
        positionMs = c.currentPosition.coerceAtLeast(0)

        val tl = c.currentTimeline
        val next = ArrayList<Pair<Int, String>>()
        if (!tl.isEmpty && c.currentMediaItemIndex != C.INDEX_UNSET) {
            var i = c.currentMediaItemIndex
            while (next.size < 300) {
                i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
                if (i == C.INDEX_UNSET) break
                next += i to c.getMediaItemAt(i).mediaId
            }
        }
        upNext = next

        // Count a song as played once it starts playing.
        val id = currentId
        if (id != null && id != lastRecorded && c.playWhenReady) {
            lastRecorded = id
            recordPlay(id)
        }
    }

    // ----- History -----

    private fun loadHistory(): List<String> = try {
        val arr = org.json.JSONArray(prefs.getString("history", "[]"))
        List(arr.length()) { arr.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun loadCounts(): MutableMap<String, Int> = try {
        val o = org.json.JSONObject(prefs.getString("counts", "{}") ?: "{}")
        o.keys().asSequence().associateWithTo(HashMap<String, Int>()) { o.getInt(it) }
    } catch (e: Exception) {
        HashMap()
    }

    private fun recordPlay(id: String) {
        history = (listOf(id) + history.filter { it != id }).take(100)
        playCounts[id] = (playCounts[id] ?: 0) + 1
        countsVersion++
        prefs.edit()
            .putString("history", org.json.JSONArray(history).toString())
            .putString("counts", org.json.JSONObject(playCounts as Map<*, *>).toString())
            .apply()
    }

    fun playCount(id: String): Int = playCounts[id] ?: 0

    // ----- Likes -----

    fun isLiked(song: Song?): Boolean = song != null && playlists.isLiked(song.id)

    fun toggleLike(song: Song) {
        val liked = playlists.toggleLike(song.id)
        messageChannel.trySend(if (liked) "Added to Liked songs" else "Removed from Liked songs")
    }

    fun play(list: List<Song>, start: Int, shuffled: Boolean = false, from: String = "Your library") {
        val c = controller ?: return
        if (list.isEmpty()) return
        playingFrom = from
        if (shuffled) c.shuffleModeEnabled = true
        val startIndex = if (shuffled) Random.nextInt(list.size) else start.coerceIn(0, list.size - 1)
        c.setMediaItems(list.map { it.toMediaItem() }, startIndex, 0L)
        c.prepare()
        c.play()
    }

    fun enqueue(song: Song, next: Boolean) {
        val c = controller ?: return
        val args = song.toBundle().apply { putBoolean("next", next) }
        c.sendCustomCommand(SessionCommand(PlaybackService.CMD_ENQUEUE, Bundle.EMPTY), args)
        messageChannel.trySend(if (next) "Plays next" else "Added to the queue")
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
    /** Always the song before (used by swipe gestures), never "restart this song". */
    fun previousTrack() = controller?.seekToPreviousMediaItem()
    fun seekTo(ms: Long) = controller?.seekTo(ms)
    fun jumpTo(index: Int) = controller?.let { it.seekTo(index, 0L); it.play() }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // ----- Equaliser -----

    private fun updateEq(s: Effects.Settings) {
        eqSettings = s
        Effects.write(effectsPrefs, s)
    }

    /** The band levels currently in effect: the chosen preset's, or the person's own. */
    fun eqLevels(): List<Int> {
        val info = eqInfo ?: return emptyList()
        val s = eqSettings
        return if (s.preset in info.presets.indices) info.presets[s.preset].second
        else List(info.bandsHz.size) { s.levels.getOrElse(it) { 0 } }
    }

    fun setEqEnabled(on: Boolean) = updateEq(eqSettings.copy(enabled = on))

    fun selectEqPreset(index: Int) {
        val levels = eqInfo?.presets?.getOrNull(index)?.second ?: eqLevels()
        updateEq(eqSettings.copy(preset = index, levels = levels, enabled = true))
    }

    fun setEqBand(band: Int, level: Int) {
        val levels = eqLevels().toMutableList()
        if (band !in levels.indices) return
        levels[band] = level
        updateEq(eqSettings.copy(preset = -1, levels = levels, enabled = true))
    }

    fun setBassBoost(strength: Int) = updateEq(eqSettings.copy(bass = strength, enabled = true))

    fun resetEq() {
        val info = eqInfo ?: return
        val flat = info.presets.indexOfFirst { it.second.all { level -> level == 0 } }
        updateEq(Effects.Settings(eqSettings.enabled, preset = flat, levels = List(info.bandsHz.size) { 0 }, bass = 0))
    }

    // ----- Sleep timer -----

    /** [minutes] > 0 stops after that long, -1 at the end of the current song, 0 cancels. */
    fun setSleepTimer(minutes: Int) {
        val c = controller ?: return
        c.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SLEEP, Bundle.EMPTY),
            Bundle().apply { putInt("minutes", minutes) },
        )
        messageChannel.trySend(
            when {
                minutes > 0 -> "Music will stop in ${if (minutes >= 60) "${minutes / 60} hour" else "$minutes minutes"}"
                minutes == -1 -> "Music will stop at the end of this song"
                else -> "Sleep timer off"
            }
        )
    }

    // ----- Playlists -----

    fun addToPlaylist(playlistId: String, song: Song) {
        val p = playlists.get(playlistId) ?: return
        val added = playlists.add(playlistId, listOf(song.id))
        messageChannel.trySend(if (added > 0) "Added to “${p.name}”" else "Already in “${p.name}”")
    }

    fun createPlaylist(name: String, with: Song? = null) {
        val p = playlists.create(name, listOfNotNull(with?.id))
        messageChannel.trySend(if (with != null) "Added to “${p.name}”" else "Created “${p.name}”")
    }

    fun deletePlaylist(id: String) {
        playlists.delete(id)
        if (screens.lastOrNull() == Screen.PlaylistDetail(id)) screens.removeAt(screens.lastIndex)
        messageChannel.trySend("Playlist deleted")
    }
}
