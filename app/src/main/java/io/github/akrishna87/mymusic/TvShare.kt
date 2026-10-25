package io.github.akrishna87.mymusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.security.SecureRandom

/**
 * "Play on TV app": the phone's side. While sharing is on, Isaialai's TV app (on a Google TV or
 * Android TV on the same Wi-Fi) can browse your lists and play your songs. The screens read and
 * change it through this object; [TvShareService] does the work.
 */
object TvShare {
    /** How phones announce themselves on Wi-Fi, for the TV app to find them. */
    const val SERVICE_TYPE = "_isaialai._tcp"

    var sharing by mutableStateOf(false); private set
    /** "192.168.1.5:47123" while sharing. */
    var address by mutableStateOf<String?>(null); private set
    /** TVs waiting for you to allow them. */
    var waiting by mutableStateOf<List<TvShareServer.PairRequest>>(emptyList()); private set
    var paired by mutableStateOf<List<PairedTv>>(emptyList()); private set
    /** Why sharing stopped by itself, to show once. */
    var stopReason by mutableStateOf<String?>(null)

    private var server: TvShareServer? = null
    private var store: TvPairingStore? = null
    private val main = Handler(Looper.getMainLooper())

    class PairedTv(val hash: String, val name: String, val at: Long)

    fun store(context: Context): TvPairingStore =
        store ?: synchronized(this) { store ?: TvPairingStore(context.applicationContext).also { store = it; paired = it.list() } }

    fun loadPaired(context: Context) {
        paired = store(context).list()
    }

    fun start(context: Context) {
        stopReason = null
        ContextCompat.startForegroundService(context, Intent(context, TvShareService::class.java))
    }

    fun stop(context: Context) {
        context.startService(Intent(context, TvShareService::class.java).setAction(TvShareService.ACTION_STOP))
    }

    fun allow(request: TvShareServer.PairRequest) = server?.allow(request.id)

    fun refuse(request: TvShareServer.PairRequest) = server?.refuse(request.id)

    fun forget(context: Context, tv: PairedTv) {
        store(context).remove(tv.hash)
        paired = store(context).list()
    }

    /** This phone's name as TVs show it, e.g. "Pixel 8". */
    fun phoneName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** A random id for this phone (so a TV finds the same phone again when its address changes). */
    fun phoneId(context: Context): String {
        val p = context.getSharedPreferences("tv_share", Context.MODE_PRIVATE)
        p.getString("phone_id", null)?.let { return it }
        val id = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        p.edit().putString("phone_id", id).apply()
        return id
    }

    internal fun started(s: TvShareServer, at: String, context: Context) {
        server = s
        sharing = true
        address = at
        paired = store(context).list()
    }

    internal fun stopped() {
        server = null
        sharing = false
        address = null
        waiting = emptyList()
    }

    /** The server changed its waiting list (from one of its threads). */
    internal fun requestsChanged(context: Context) {
        main.post {
            waiting = server?.waiting().orEmpty()
            paired = store(context).list()
        }
    }
}

/** The TVs you've allowed. Only a hash of each TV's key is kept, so it can't be read back. */
class TvPairingStore(context: Context) : TvShareServer.Pairings {
    private val prefs = context.getSharedPreferences("tv_share", Context.MODE_PRIVATE)
    private val tvs = LinkedHashMap<String, TvShare.PairedTv>()

    init {
        try {
            val arr = JSONArray(prefs.getString("paired", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                tvs[o.getString("hash")] = TvShare.PairedTv(o.getString("hash"), o.optString("name"), o.optLong("at"))
            }
        } catch (e: Exception) {
        }
    }

    @Synchronized
    override fun isPaired(key: String): Boolean = key.length == 64 && TvShareServer.hash(key) in tvs

    @Synchronized
    override fun add(key: String, tvName: String) {
        val hash = TvShareServer.hash(key)
        tvs[hash] = TvShare.PairedTv(hash, tvName, System.currentTimeMillis())
        save()
    }

    @Synchronized
    fun remove(hash: String) {
        tvs.remove(hash)
        save()
    }

    @Synchronized
    fun list(): List<TvShare.PairedTv> = tvs.values.toList()

    private fun save() {
        val arr = JSONArray()
        tvs.values.forEach { arr.put(JSONObject().put("hash", it.hash).put("name", it.name).put("at", it.at)) }
        prefs.edit().putString("paired", arr.toString()).apply()
    }
}

/** Your library as the TV app browses it: the same lists Android Auto shows (see [CarLibrary]). */
class PhoneLibrary(context: Context) : TvShareServer.Library {
    private val app = context.applicationContext
    private val car = CarLibrary(app)
    private val trusted = TrustedUris(app)
    // Covers are asked for many at a time as the TV scrolls; keep the last few MB of them.
    private val covers = object : LruCache<String, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size.coerceAtLeast(1)
    }

    override fun browse(id: String): JSONObject? {
        val folders = JSONArray()
        fun folder(fid: String, title: String, subtitle: String?, cover: String?) {
            folders.put(JSONObject().put("id", fid).put("title", title).put("subtitle", subtitle.orEmpty()).put("cover", cover.orEmpty()))
        }
        fun count(n: Int) = if (n == 1) "1 song" else "$n songs"
        when (id) {
            CarLibrary.ROOT -> car.children(CarLibrary.ROOT).orEmpty().forEach {
                folder(it.mediaId, it.mediaMetadata.title?.toString().orEmpty(), null, null)
            }
            CarLibrary.PLAYLISTS -> car.children(CarLibrary.PLAYLISTS).orEmpty().forEach {
                folder(it.mediaId, it.mediaMetadata.title?.toString().orEmpty(), it.mediaMetadata.subtitle?.toString(), car.songsIn(it.mediaId)?.firstOrNull()?.id)
            }
            CarLibrary.ALBUMS -> LibraryGrouping.albums(car.songs()).forEach {
                folder("album:${it.key}", it.name, it.artist, it.songs.firstOrNull()?.id)
            }
            CarLibrary.ARTISTS -> LibraryGrouping.artists(car.songs()).forEach {
                folder("artist:${it.name.lowercase()}", it.name, count(it.songs.size), it.songs.firstOrNull()?.id)
            }
            CarLibrary.COMPOSERS -> LibraryGrouping.composers(car.songs()).forEach {
                folder("composer:${it.name.lowercase()}", it.name, count(it.songs.size), it.songs.firstOrNull()?.id)
            }
            else -> {
                val songs = car.songsIn(id) ?: return null
                val arr = JSONArray()
                songs.forEach { s ->
                    arr.put(
                        JSONObject().put("id", s.id).put("title", s.title).put("artist", s.artist)
                            .put("album", s.album).put("durationMs", s.durationMs),
                    )
                }
                return JSONObject().put("songs", arr)
            }
        }
        return JSONObject().put("folders", folders)
    }

    override fun song(id: String): TinyHttpServer.Source? {
        val s = car.song(id) ?: return null
        if (!trusted.isSong(s.uri)) return null
        return SongFiles.song(app, s.uri)
    }

    override fun art(id: String): TinyHttpServer.Source? {
        val bytes = covers.get(id) ?: run {
            val s = car.song(id) ?: return null
            val src = SongFiles.art(app, ArtLoader.artworkUriFor(s), 500) ?: return null
            src.open(0).use { it.readBytes() }.also { covers.put(id, it) }
        }
        return TinyHttpServer.Source(bytes.size.toLong(), "image/jpeg") { offset -> ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt()) }
    }
}

/**
 * Runs the TV share while it's on: the server ([TvShareServer]) on this phone's Wi-Fi address,
 * announced on the Wi-Fi so the TV app finds the phone, with a notification to stop it. Stops by
 * itself after 30 minutes without the TV, or when the phone leaves Wi-Fi.
 */
class TvShareService : Service() {

    companion object {
        const val ACTION_STOP = "io.github.akrishna87.mymusic.TV_SHARE_STOP"
        private const val CHANNEL = "tv_share"
        private const val ASK_CHANNEL = "tv_share_ask"
        private const val NOTIFICATION = 4701
        private const val ASK_NOTIFICATION = 4702
        private const val IDLE_MS = 30 * 60_000L
    }

    private var server: TvShareServer? = null
    private var address: InetAddress? = null
    private var announced: NsdManager.RegistrationListener? = null
    private val locks by lazy { AwakeLocks(this, "Isaialai:tv") }
    private val scope = MainScope()
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android needs the notification first, even when this is the stop button.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION,
            notification(null),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (server?.running != true) begin()
        return START_NOT_STICKY
    }

    private fun begin() {
        val at = CastBridge.localAddressFor(null)
        if (at == null) {
            end("Connect this phone to Wi-Fi (the same one as your TV) to share with the TV.")
            return
        }
        val s = server ?: TvShareServer(
            about = { TvShare.phoneId(this) to TvShare.phoneName(this) },
            library = PhoneLibrary(this),
            pairings = TvShare.store(this),
            onRequestsChanged = {
                TvShare.requestsChanged(this)
                Handler(Looper.getMainLooper()).post { askNotification() }
            },
        ).also { server = it }
        try {
            s.start(at, TvShareServer.PREFERRED_PORT)
        } catch (e: IOException) {
            end("Couldn't start sharing on this Wi-Fi.")
            return
        }
        address = at
        val shown = "${at.hostAddress}:${s.port}"
        TvShare.started(s, shown, this)
        announce(at, s.port)
        locks.hold()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(shown))
        if (!watching) {
            watching = true
            scope.launch { watch() }
        }
    }

    /** Stops when the TV hasn't asked for anything for a while; follows the phone to a new Wi-Fi address. */
    private suspend fun watch() {
        while (scope.isActive) {
            delay(30_000)
            val s = server ?: return
            if (System.currentTimeMillis() - s.lastRequestAt > IDLE_MS) {
                end("Sharing with your TV stopped after 30 minutes without the TV.")
                return
            }
            val now = CastBridge.localAddressFor(null)
            if (now == null) {
                end("Sharing with your TV stopped: this phone left Wi-Fi.")
                return
            }
            if (now != address) {
                unannounce()
                begin()
            }
            locks.hold()
        }
    }

    private fun end(reason: String?) {
        TvShare.stopReason = reason
        stopSelf()
    }

    override fun onDestroy() {
        unannounce()
        server?.stop()
        server = null
        locks.release()
        scope.cancel()
        getSystemService(NotificationManager::class.java).cancel(ASK_NOTIFICATION)
        TvShare.stopped()
        super.onDestroy()
    }

    private fun announce(at: InetAddress, port: Int) {
        val nsd = getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = TvShare.phoneName(this@TvShareService).take(60)
            serviceType = TvShare.SERVICE_TYPE
            setPort(port)
            setAttribute("id", TvShare.phoneId(this@TvShareService))
            setAttribute("ip", at.hostAddress)
            setAttribute("v", TvShareServer.VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            announced = listener
        } catch (e: Exception) {
            // The TV app can still connect by typing the address shown on the phone.
        }
    }

    private fun unannounce() {
        val l = announced ?: return
        announced = null
        try {
            getSystemService(NsdManager::class.java)?.unregisterService(l)
        } catch (e: Exception) {
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sharing with your TV", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(ASK_CHANNEL, "TV asking to play your music", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun notification(at: String?): Notification {
        channels()
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TvShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tv_share)
            .setContentTitle("Sharing your music with your TV")
            .setContentText(if (at == null) "Starting…" else "On Wi-Fi at $at. Stops after 30 minutes without the TV.")
            .setOngoing(true)
            .setContentIntent(openApp())
            .addAction(0, "Stop", stop)
            .build()
    }

    /** A TV is asking to pair: say so, and the app shows Allow / Don't allow. */
    private fun askNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val ask = server?.waiting()?.lastOrNull()
        if (ask == null) {
            nm.cancel(ASK_NOTIFICATION)
            return
        }
        try {
            nm.notify(
                ASK_NOTIFICATION,
                NotificationCompat.Builder(this, ASK_CHANNEL)
                    .setSmallIcon(R.drawable.ic_tv_share)
                    .setContentTitle("${ask.tvName} wants to play your music")
                    .setContentText("Tap to allow it. The TV shows the code ${ask.code}.")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(openApp())
                    .build(),
            )
        } catch (e: SecurityException) {
            // Notifications are off for Isaialai; the request still shows in the app.
        }
    }
}
