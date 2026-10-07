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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.github.akrishna87.mymusic.ui.ThemeSettings
import io.github.akrishna87.mymusic.ui.formatTime
import io.github.akrishna87.mymusic.ui.songCount
import kotlin.random.Random

sealed interface Screen {
    data class Album(val key: String) : Screen
    data class Artist(val name: String) : Screen
    data class Folder(val path: String) : Screen
    data class PlaylistDetail(val id: String) : Screen
    data class Composer(val name: String) : Screen
    data class Smart(val kind: SmartPlaylist) : Screen
    data object Duplicates : Screen
    data object Settings : Screen
    /** Cut part of a song, to save as a new song or as a ringtone ([ringtone] picks that to start with). */
    data class Cut(val songId: String, val ringtone: Boolean) : Screen
}

/** Playlists that fill themselves from what you play. */
enum class SmartPlaylist(val label: String, val blurb: String) {
    MOST_PLAYED("Most played", "Your 50 most played songs"),
    RECENTLY_ADDED("Recently added", "Songs added in the last 30 days"),
    NOT_PLAYED_LATELY("Not played in a while", "Songs you played before, but not in the last 2 months"),
    NEVER_PLAYED("Never played", "Songs you haven't played in Isaialai yet"),
}

enum class Section { HOME, SEARCH, LIBRARY }

enum class LibraryChip(val label: String) {
    SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), COMPOSERS("Composers"), FOLDERS("Folders"), PLAYLISTS("Playlists")
}

/** Choosing a cover for [song], or for every song in its album ([albumSongs], if it has one). */
data class CoverRequest(val song: Song, val albumName: String, val albumSongs: List<Song>)

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
    /** .lrc files in added folders, keyed by [LyricsLoader.lrcKey]. */
    var lrcFiles by mutableStateOf<Map<String, Uri>>(emptyMap()); private set

    // ----- Navigation & UI -----
    val screens = mutableStateListOf<Screen>()
    var showPlayer by mutableStateOf(false)
    var section by mutableStateOf(Section.HOME); private set
    var libraryChip by mutableStateOf(
        LibraryChip.entries.firstOrNull { it.name == prefs.getString("chipName", null) } ?: LibraryChip.SONGS,
    ); private set
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

    /** Song details corrected in the app; see [SongEdits]. */
    private val edits = SongEdits(app)
    /** The song whose details are being edited, if the edit dialog is open. */
    var editing by mutableStateOf<Song?>(null)
    /** The library as read from the phone, before edits are applied. */
    private var rawSongs: List<Song> = emptyList()

    // ----- Listening history (for Home) -----
    /** Song ids, most recently played first. */
    var history by mutableStateOf(loadHistory()); private set
    private val playCounts: MutableMap<String, Int> = loadCounts()
    /** When each song was last played (ms since epoch). */
    private val lastPlayed: MutableMap<String, Long> = loadLastPlayed()
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
    var crossfadeSec by mutableIntStateOf(effectsPrefs.getInt(Effects.KEY_CROSSFADE, 0)); private set
    /** Volume boost in percent (100 = off). */
    var boostPercent by mutableIntStateOf(Effects.boostPercent(effectsPrefs)); private set
    var boostAvailable by mutableStateOf(effectsPrefs.getBoolean(Effects.KEY_BOOST_AVAILABLE, true)); private set
    var evenVolume by mutableStateOf(effectsPrefs.getBoolean(Effects.KEY_EVEN_VOLUME, false)); private set
    /** The song playing now and how many dB even volume moved it, while even volume is on. */
    var evenVolumeNow by mutableStateOf(readEvenVolumeNow()); private set
    var resumeMode by mutableStateOf(Effects.resumeMode(effectsPrefs)); private set
    private val effectsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key in Effects.BACKUP_KEYS) requestBackup()
        resumeMode = Effects.resumeMode(p)
        if (key == Effects.KEY_RESUMED) {
            p.getString(Effects.KEY_RESUMED, null)?.split('|')?.takeIf { it.size == 3 }?.let { (title, pos, _) ->
                val at = formatTime(pos.toLongOrNull() ?: 0)
                messageChannel.trySend("Continuing “$title” from $at · tap ⏮ to start over")
            }
        }
        eqInfo = Effects.readInfo(p)
        eqSettings = Effects.read(p)
        sleepUntil = p.getLong(Effects.KEY_SLEEP_UNTIL, 0L)
        sleepEndOfSong = p.getBoolean(Effects.KEY_SLEEP_END_OF_SONG, false)
        crossfadeSec = p.getInt(Effects.KEY_CROSSFADE, 0)
        boostPercent = Effects.boostPercent(p)
        boostAvailable = p.getBoolean(Effects.KEY_BOOST_AVAILABLE, true)
        evenVolume = p.getBoolean(Effects.KEY_EVEN_VOLUME, false)
        evenVolumeNow = readEvenVolumeNow()
    }

    private fun readEvenVolumeNow(): Pair<String, Float>? =
        effectsPrefs.getString(Effects.KEY_EVEN_VOLUME_NOW, null)?.let { v ->
            v.substringAfterLast('|').toFloatOrNull()?.let { v.substringBeforeLast('|') to it }
        }

    fun setCrossfade(seconds: Int) {
        crossfadeSec = seconds.coerceIn(0, 12)
        effectsPrefs.edit().putInt(Effects.KEY_CROSSFADE, crossfadeSec).apply()
    }

    fun setBoost(percent: Int) {
        boostPercent = percent.coerceIn(100, Effects.MAX_BOOST)
        effectsPrefs.edit().putInt(Effects.KEY_BOOST, boostPercent).apply()
    }

    fun chooseResumeMode(mode: ResumeMode) {
        resumeMode = mode
        effectsPrefs.edit().putString(Effects.KEY_RESUME_MODE, mode.key).apply()
    }

    // ----- Continue listening -----

    private val resumePrefs = ResumePoints.prefs(app)
    /** Changes whenever a song's saved place changes, so Home's "Continue listening" updates. */
    var resumeVersion by mutableIntStateOf(0); private set
    private val resumeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> resumeVersion++ }

    /** Songs left part-way, most recent first, with where they were left. */
    fun continueListening(): List<Pair<Song, ResumePoints.Point>> {
        if (resumeMode == ResumeMode.OFF) return emptyList()
        return ResumePoints.all(resumePrefs)
            .filter { resumeMode.applies(it.durationMs) }
            .mapNotNull { p -> songsById[p.songId]?.let { it to p } }
            .take(12)
    }

    fun setEvenVolumeOn(on: Boolean) {
        evenVolume = on
        effectsPrefs.edit().putBoolean(Effects.KEY_EVEN_VOLUME, on).apply()
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
    var speed by mutableStateOf(1f); private set
    /** Pitch as a factor (1 = normal); the screen shows it in semitones. */
    var pitch by mutableStateOf(1f); private set

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
        CustomArt.load(app)
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
        resumePrefs.registerOnSharedPreferenceChangeListener(resumeListener)
    }

    override fun onCleared() {
        effectsPrefs.unregisterOnSharedPreferenceChangeListener(effectsListener)
        resumePrefs.unregisterOnSharedPreferenceChangeListener(resumeListener)
        prefs.unregisterOnSharedPreferenceChangeListener(uiListener)
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
        rawSongs = list
        val edited = list.map(edits::apply)
        songs = edited
        songsById = edited.associateBy { it.id }
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
            LyricsLoader.forget()
            if (trees.isEmpty()) {
                folderSongs = emptyList()
                lrcFiles = emptyMap()
                publish(library)
            } else {
                scanningFolders = true
                try {
                    val result = withContext(Dispatchers.IO) { AddedFolders.scan(app, trees, known) }
                    folderSongs = result.songs
                    lrcFiles = result.lyricsFiles
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
            requestBackup()
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
        prefs.edit().putString("chipName", c.name).apply()
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
        speed = c.playbackParameters.speed
        pitch = c.playbackParameters.pitch

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

    private fun loadLastPlayed(): MutableMap<String, Long> = try {
        val o = org.json.JSONObject(prefs.getString("lastPlayed", "{}") ?: "{}")
        o.keys().asSequence().associateWithTo(HashMap<String, Long>()) { o.getLong(it) }
    } catch (e: Exception) {
        HashMap()
    }

    private fun recordPlay(id: String) {
        history = (listOf(id) + history.filter { it != id }).take(100)
        playCounts[id] = (playCounts[id] ?: 0) + 1
        lastPlayed[id] = System.currentTimeMillis()
        countsVersion++
        saveHistory()
        requestBackup(delayMs = 15_000)
    }

    private fun saveHistory() {
        prefs.edit()
            .putString("history", org.json.JSONArray(history).toString())
            .putString("counts", org.json.JSONObject(playCounts as Map<*, *>).toString())
            .putString("lastPlayed", org.json.JSONObject(lastPlayed as Map<*, *>).toString())
            .apply()
    }

    /** The songs in a smart playlist right now. */
    fun smartSongs(kind: SmartPlaylist, all: List<Song> = songs): List<Song> {
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        return when (kind) {
            SmartPlaylist.MOST_PLAYED ->
                all.filter { playCount(it.id) > 0 }.sortedByDescending { playCount(it.id) }.take(50)
            SmartPlaylist.RECENTLY_ADDED ->
                all.filter { it.dateAdded * 1000 >= now - 30 * day }.sortedByDescending { it.dateAdded }
            SmartPlaylist.NOT_PLAYED_LATELY ->
                all.filter { s -> lastPlayed[s.id]?.let { it < now - 60 * day } == true }
                    .sortedByDescending { playCount(it.id) }
            SmartPlaylist.NEVER_PLAYED ->
                all.filter { playCount(it.id) == 0 }.sortedByDescending { it.dateAdded }
        }
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

    fun pause() = controller?.pause()
    /** Jumps back or forward 10 seconds in the song. */
    fun rewind10() = controller?.seekBack()
    fun forward10() = controller?.seekForward()
    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPrevious()
    /** Always the song before (used by swipe gestures), never "restart this song". */
    fun previousTrack() = controller?.seekToPreviousMediaItem()
    fun seekTo(ms: Long) = controller?.seekTo(ms)
    fun jumpTo(index: Int) = controller?.let { it.seekTo(index, 0L); it.play() }

    /** Moves a song within Up next ([from] and [to] are positions in [upNext]). */
    fun moveUpNext(from: Int, to: Int) {
        val c = controller ?: return
        if (from == to || from !in upNext.indices || to !in upNext.indices) return
        // Show the new order straight away; the player confirms it a moment later.
        upNext = upNext.toMutableList().apply { add(to, removeAt(from)) }
        c.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_MOVE_UPCOMING, Bundle.EMPTY),
            Bundle().apply { putInt("from", from); putInt("to", to) },
        )
    }

    /**
     * Takes a song out of the queue ([queueIndex] and [songId] as in [upNext]). Does nothing if that
     * place in the queue now holds a different song, so a repeated request can't remove the next one.
     */
    fun removeFromQueue(queueIndex: Int, songId: String) {
        val c = controller ?: return
        if (queueIndex !in 0 until c.mediaItemCount || queueIndex == c.currentMediaItemIndex) return
        if (c.getMediaItemAt(queueIndex).mediaId != songId) return
        val title = c.getMediaItemAt(queueIndex).mediaMetadata.title
        upNext = upNext.filter { it.first != queueIndex }
        c.removeMediaItem(queueIndex)
        messageChannel.trySend("Removed “$title” from the queue")
    }

    fun setSpeedAndPitch(newSpeed: Float, newPitch: Float) {
        val c = controller ?: return
        c.playbackParameters = androidx.media3.common.PlaybackParameters(newSpeed.coerceIn(0.5f, 2f), newPitch.coerceIn(0.5f, 2f))
    }

    fun isEdited(song: Song) = edits.isEdited(song.id)

    /** Saves corrected details for [song] (null [edit] undoes them) and updates the queue to match. */
    fun saveEdit(song: Song, edit: SongEdits.Edit?) {
        if (edit == null) edits.clear(song.id) else edits.set(song.id, edit)
        publish(rawSongs)
        refreshQueued(setOf(song.id))
        messageChannel.trySend(if (edit == null) "Back to the details in the file" else "Saved")
    }

    /** Updates queued copies of these songs (new details or cover) without interrupting playback. */
    private fun refreshQueued(ids: Set<String>) {
        val c = controller ?: return
        for (i in 0 until c.mediaItemCount) {
            val id = c.getMediaItemAt(i).mediaId
            if (id in ids) songsById[id]?.let { c.replaceMediaItem(i, it.toMediaItem()) }
        }
    }

    // ----- Cover art -----

    /** The cover dialog, if it's open. */
    var coverRequest by mutableStateOf<CoverRequest?>(null)

    fun requestCover(song: Song) {
        val album = if (AlbumNames.hasAlbum(song)) songs.filter { it.albumKey == song.albumKey } else listOf(song)
        coverRequest = CoverRequest(song, song.album, album)
    }

    fun hasCover(songs: List<Song>) = songs.any { CustomArt.has(it.id) }

    /** Uses the picture at [image] as the cover of [songs]. */
    fun setCover(songs: List<Song>, image: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { CustomArt.save(getApplication(), songs.map { it.id }, image) }
            if (ok) {
                refreshQueued(songs.mapTo(HashSet()) { it.id })
                messageChannel.trySend(if (songs.size == 1) "Cover changed" else "Cover changed for ${songCount(songs.size)}")
            } else {
                messageChannel.trySend("Couldn't use that picture")
            }
        }
    }

    fun removeCover(songs: List<Song>) {
        CustomArt.remove(getApplication(), songs.map { it.id })
        refreshQueued(songs.mapTo(HashSet()) { it.id })
        messageChannel.trySend("Back to the cover in the file")
    }

    suspend fun loadLyrics(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        LyricsLoader.load(getApplication(), song, lrcFiles)
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        messageChannel.trySend(if (c.shuffleModeEnabled) "Shuffle on" else "Shuffle off")
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        messageChannel.trySend(
            when (c.repeatMode) {
                Player.REPEAT_MODE_ALL -> "Repeating all songs"
                Player.REPEAT_MODE_ONE -> "Repeating this song"
                else -> "Repeat off"
            }
        )
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

    /** Moves a song within a playlist ([from] and [to] are positions in [shown], the list on screen). */
    fun movePlaylistSong(playlistId: String, shown: List<Song>, from: Int, to: Int) {
        if (from == to || from !in shown.indices || to !in shown.indices) return
        val order = shown.map { it.id }.toMutableList().apply { add(to, removeAt(from)) }
        playlists.reorder(playlistId, order)
    }

    fun deletePlaylist(id: String) {
        playlists.delete(id)
        if (screens.lastOrNull() == Screen.PlaylistDetail(id)) screens.removeAt(screens.lastIndex)
        messageChannel.trySend("Playlist deleted")
    }

    // ----- Backup & restore -----

    private val backupStorage = BackupStorage(app)
    var autoBackup by mutableStateOf(backupStorage.autoBackup); private set
    /** Goes up after each backup, so what Settings shows about it refreshes. */
    var backupVersion by mutableIntStateOf(0); private set
    /** A backup or playlist file that's been read, waiting for you to confirm restoring it. */
    var restorePlan by mutableStateOf<RestorePlan?>(null)
    private var backupJob: Job? = null
    /** One backup at a time (a cancelled one may still be finishing its writes). */
    private val backupLock = kotlinx.coroutines.sync.Mutex()
    private val uiListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in BackupSettings.UI_KEYS) requestBackup()
    }

    init {
        playlists.onChange = { requestBackup() }
        edits.onChange = { requestBackup() }
        prefs.registerOnSharedPreferenceChangeListener(uiListener)
    }

    val backupLocation: String get() = backupStorage.location
    val lastBackupAt: Long get() = backupStorage.lastBackupAt
    val backupError: String? get() = backupStorage.lastError
    fun backupNeedsPermission(): Boolean = backupStorage.needsPermission()

    fun setAutoBackupOn(on: Boolean) {
        backupStorage.autoBackup = on
        autoBackup = on
        if (on) requestBackup(delayMs = 0)
    }

    /** Backs up a little after the last change, so a burst of changes is written once. */
    fun requestBackup(delayMs: Long = 4_000) {
        if (!backupStorage.autoBackup) return
        backupJob?.cancel()
        backupJob = viewModelScope.launch {
            delay(delayMs)
            runBackup(force = false)
        }
    }

    fun backUpNow() {
        if (loading || rawSongs.isEmpty()) {
            messageChannel.trySend("Wait for your songs to load, then try again")
            return
        }
        backupJob?.cancel()
        viewModelScope.launch {
            val ok = runBackup(force = true)
            messageChannel.trySend(
                when {
                    ok == true -> "Backed up to $backupLocation"
                    ok == null -> "Nothing to back up yet"
                    else -> backupStorage.lastError ?: "Couldn't save the backup"
                },
            )
        }
    }

    /**
     * Writes the backup file and a .m3u file per playlist. Returns null if there's nothing worth
     * backing up (a fresh install), so an old backup isn't buried under an empty one.
     */
    private suspend fun runBackup(force: Boolean): Boolean? {
        // Before the library is read (or without permission to read it) songs can't be described.
        if (loading || rawSongs.isEmpty()) return if (force) false else null
        val app = getApplication<Application>()
        val lists = playlists.items.toList()
        val recent = history
        val counts = HashMap(playCounts)
        val last = HashMap(lastPlayed)
        val edited = edits.all()
        val byId = rawSongs.associateBy { it.id }
        val result = backupLock.withLock { withContext(Dispatchers.IO) {
            val cache = RefCache.load(app)
            // Songs not on the phone right now (memory card out?) keep how they were last described.
            fun ref(id: String): SongRef? = byId[id]?.let { SongRef.of(it) }?.also { cache[id] = it } ?: cache[id]
            val liked = lists.firstOrNull { it.id == PlaylistStore.LIKED_ID }
            val own = lists.filter { it.id != PlaylistStore.LIKED_ID }
            val contents = BackupFile.Contents(
                createdMs = System.currentTimeMillis(),
                playlists = own.map { p -> p.name to p.songIds.mapNotNull(::ref) },
                liked = liked?.songIds?.mapNotNull(::ref).orEmpty(),
                history = recent.mapNotNull(::ref),
                plays = counts.mapNotNull { (id, n) -> ref(id)?.let { BackupFile.Play(it, n, last[id] ?: 0L) } },
                edits = edited.mapNotNull { (id, e) -> ref(id)?.let { it to e } },
                settings = BackupSettings.snapshot(app),
            )
            if (contents.isEmpty && !force) return@withContext null
            val files = LinkedHashMap<String, String>()
            val sameAs = HashMap<String, String>()
            files[BackupFile.NAME] = BackupFile.write(contents)
            // The time it was made changes every time; only rewrite the file when the rest does.
            sameAs[BackupFile.NAME] = BackupFile.write(BackupFile.Contents(0, contents.playlists, contents.liked, contents.history, contents.plays, contents.edits, contents.settings))
            val m3u = listOf("Liked songs" to contents.liked).filter { it.second.isNotEmpty() } + contents.playlists
            for ((name, songs) in m3u) {
                var file = "$PLAYLISTS_DIR/" + M3u.fileName(name)
                var n = 2
                while (file in files) file = "$PLAYLISTS_DIR/" + M3u.fileName("$name ($n)").also { n++ }
                files[file] = M3u.write(name, songs)
            }
            val keep = (lists.flatMap { it.songIds } + recent + counts.keys + edited.keys).toSet()
            RefCache.save(app, cache.filterKeys { it in keep })
            backupStorage.writeAll(files, sameAs, force)
        } }
        backupVersion++
        return result
    }

    /** Reads the backup (or the playlist files) in a folder you picked, to show what it holds. */
    fun readRestoreFolder(tree: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val songsNow = rawSongs
            val plan = withContext(Dispatchers.IO) {
                try {
                    RestorePlan.fromFolder(app, tree, songsNow)
                } catch (e: Exception) {
                    null
                }
            }
            if (plan == null) messageChannel.trySend("No Isaialai backup or playlist files in that folder")
            else restorePlan = plan
        }
    }

    /** Reads a backup or playlist file you picked. */
    fun readRestoreFile(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val songsNow = rawSongs
            val plan = withContext(Dispatchers.IO) { RestorePlan.fromFile(app, uri, songsNow) }
            if (plan == null) messageChannel.trySend("That isn't an Isaialai backup or a playlist file")
            else restorePlan = plan
        }
    }

    /** Adds what's in [plan] to what's here; nothing already here is removed or replaced. */
    fun restore(plan: RestorePlan, withSettings: Boolean) {
        restorePlan = null
        val app = getApplication<Application>()
        var added = 0
        plan.playlists.forEach { (name, ids) -> added += playlists.importPlaylist(name, ids) }
        val liked = playlists.likeAll(plan.liked)
        for ((id, count, at) in plan.plays) {
            playCounts[id] = maxOf(playCounts[id] ?: 0, count)
            lastPlayed[id] = maxOf(lastPlayed[id] ?: 0L, at)
        }
        if (plan.history.isNotEmpty()) history = (history + plan.history.filter { it !in history }).take(100)
        plan.edits.forEach { (id, e) -> if (!edits.isEdited(id)) edits.set(id, e) }
        if (withSettings && plan.settings != null) {
            BackupSettings.apply(app, plan.settings)
            ThemeSettings.reload(app)
            sort = SongSort.entries.getOrElse(prefs.getInt("sort", 0)) { SongSort.TITLE }
        }
        countsVersion++
        saveHistory()
        if (plan.edits.isNotEmpty()) publish(rawSongs)
        viewModelScope.launch {
            // Keep backing up into the folder restored from, so there's one backup, not two.
            plan.adoptFolder?.let { folder -> backupLock.withLock { withContext(Dispatchers.IO) { backupStorage.adopt(folder) } } }
            backupVersion++
            requestBackup(delayMs = 1_000)
        }

        val what = buildList {
            if (plan.playlists.isNotEmpty()) add(if (plan.playlists.size == 1) "“${plan.playlists[0].first}”" else "${plan.playlists.size} playlists")
            if (plan.liked.isNotEmpty()) add("${plan.liked.size} liked ${if (plan.liked.size == 1) "song" else "songs"}")
            if (plan.plays.isNotEmpty()) add("play counts")
            if (withSettings && plan.settings != null) add("settings")
        }
        val text = if (what.isEmpty()) "Nothing new to restore" else "Restored " + what.joinToString(", ").replace(Regex(", ([^,]*)$"), " and $1")
        val missing = if (plan.missing == 0) "" else
            "\n${songCount(plan.missing)} ${if (plan.missing == 1) "isn't" else "aren't"} on this phone. Copy your music over, then restore again to add ${if (plan.missing == 1) "it" else "them"}."
        if (added + liked == 0 && plan.missing == 0 && what.isEmpty()) messageChannel.trySend("Already up to date")
        else messageChannel.trySend(text + missing)
    }

    /** Saves one playlist as a .m3u file wherever you chose. */
    fun exportPlaylist(playlistId: String, target: Uri) {
        val p = playlists.get(playlistId) ?: return
        val byId = rawSongs.associateBy { it.id }
        val text = M3u.write(p.name, p.songIds.mapNotNull { byId[it]?.let { s -> SongRef.of(s) } })
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val resolver = getApplication<Application>().contentResolver
                    val out = try {
                        resolver.openOutputStream(target, "wt")
                    } catch (e: IllegalArgumentException) {
                        resolver.openOutputStream(target, "w")
                    }
                    out?.use { it.write(text.toByteArray()) } != null
                } catch (e: Exception) {
                    false
                }
            }
            messageChannel.trySend(if (ok) "Saved “${p.name}” as a playlist file" else "Couldn't save the playlist file")
        }
    }
}

private const val PLAYLISTS_DIR = "Playlists"
