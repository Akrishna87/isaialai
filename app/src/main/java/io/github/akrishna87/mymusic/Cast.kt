package io.github.akrishna87.mymusic

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Casting to a TV (Chromecast, Google TV, Android TV and TVs with Chromecast built in). The TV
 * opens Google's standard media receiver and plays songs that this phone hands it (see [CastServer]).
 */
object Cast {
    val RECEIVER: String = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID

    /** Null when the phone has no (or too old) Google Play services, so casting isn't offered. */
    fun context(context: Context): CastContext? = try {
        @Suppress("DEPRECATION")
        CastContext.getSharedInstance(context.applicationContext)
    } catch (e: Exception) {
        null
    }
}

/** Read by Google Play services (named in the manifest). */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(Cast.RECEIVER)
        .setStopReceiverApplicationWhenEndingSession(true)
        // Isaialai's own notification and lock-screen controls carry on while casting; no second set.
        .setCastMediaOptions(
            CastMediaOptions.Builder()
                .setNotificationOptions(null)
                .setMediaSessionEnabled(false)
                .build(),
        )
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * The playback service's side of casting: turns queue items into ones the TV can play (web
 * addresses on this phone), serves them while casting, and keeps the phone awake and on Wi-Fi
 * meanwhile so the music doesn't stop when the screen goes off.
 */
class CastBridge(context: Context) {
    private val app = context.applicationContext
    private val server = CastServer(::source)
    private val originals = ConcurrentHashMap<String, MediaItem>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Starts serving on the network shared with the TV at [tv]; false if this phone isn't on one. */
    fun start(tv: InetAddress?): Boolean {
        val address = localAddressFor(tv) ?: return false
        try {
            server.start(address)
        } catch (e: IOException) {
            return false
        }
        keepAwake()
        return true
    }

    fun stop() {
        server.stop()
        originals.clear()
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (e: RuntimeException) {
        }
    }

    /** (Re)takes the locks; called whenever the TV starts playing. They last at most 6 hours. */
    fun keepAwake() {
        try {
            val wl = wakeLock ?: (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Isaialai:cast").also { it.setReferenceCounted(false); wakeLock = it }
            wl.acquire(6 * 60 * 60_000L)
            @Suppress("DEPRECATION")
            val fl = wifiLock ?: (app.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Isaialai:cast").also { it.setReferenceCounted(false); wifiLock = it }
            if (!fl.isHeld) fl.acquire()
        } catch (e: RuntimeException) {
        }
    }

    /** [item] (one of your songs, already checked) as the TV should get it, or null when not casting. */
    fun castItem(item: MediaItem): MediaItem? {
        val id = item.mediaId
        val url = server.urlFor(id) ?: return null
        originals[id] = item
        val md = item.mediaMetadata
        val art = md.artworkUri?.let { server.urlFor(id, CastServer.ART) }
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(url)
            .setMimeType(mimeOf(Song.uriForId(id)))
            .setMediaMetadata(md.buildUpon().setArtworkUri(art?.let(Uri::parse)).build())
            .build()
    }

    /** The phone's own item for a song the TV has, to play it here again after casting. */
    fun original(id: String): MediaItem? = originals[id]

    private fun source(kind: String, id: String): CastServer.Source? {
        val item = originals[id] ?: return null
        val uri = item.localConfiguration?.uri ?: Song.uriForId(id)
        return when (kind) {
            CastServer.SONG -> {
                val length = lengthOf(uri) ?: return null
                CastServer.Source(length, mimeOf(uri)) { offset ->
                    val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Can't read $uri")
                    skipFully(input, offset)
                    input
                }
            }
            CastServer.ART -> {
                val art = item.mediaMetadata.artworkUri ?: return null
                val bitmap = ArtLoader.decode(app, art, 600) ?: return null
                val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
                CastServer.Source(bytes.size.toLong(), "image/jpeg") { offset -> ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt()) }
            }
            else -> null
        }
    }

    private fun lengthOf(uri: Uri): Long? {
        try {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 }?.let { return it }
            }
        } catch (e: Exception) {
        }
        return try {
            app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { n -> n > 0 } }
        } catch (e: Exception) {
            null
        }
    }

    private fun mimeOf(uri: Uri): String {
        val type = try {
            app.contentResolver.getType(uri)
        } catch (e: Exception) {
            null
        }
        if (type != null && type.startsWith("audio/")) return type
        val name = try {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.takeIf { it.startsWith("audio/") } ?: "audio/mpeg"
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) {
                if (input.read() < 0) throw IOException("Past the end")
                left--
            } else {
                left -= n
            }
        }
    }

    companion object {
        /** Interfaces that are mobile data or VPNs: the TV is never there. */
        private val NOT_LOCAL = listOf("rmnet", "ccmni", "v4-", "dummy", "tun", "ppp", "ipsec", "clat")

        /**
         * This phone's address on the network the TV is on (home Wi-Fi, or this phone's hotspot);
         * never the mobile network.
         */
        fun localAddressFor(tv: InetAddress?): InetAddress? {
            val candidates = try {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .filter { it.isUp && !it.isLoopback && NOT_LOCAL.none { p -> it.name.startsWith(p) } }
                    .flatMap { nif -> nif.interfaceAddresses.filter { it.address is Inet4Address } }
            } catch (e: Exception) {
                emptyList()
            }
            if (tv is Inet4Address) {
                candidates.firstOrNull { sameNetwork(it.address, tv, it.networkPrefixLength.toInt()) }?.let { return it.address }
            }
            return candidates.firstOrNull { it.address.isSiteLocalAddress }?.address
        }

        fun sameNetwork(a: InetAddress, b: InetAddress, prefix: Int): Boolean {
            val x = a.address
            val y = b.address
            if (x.size != y.size || prefix !in 1..x.size * 8) return false
            for (bit in 0 until prefix) {
                val mask = 0x80 ushr (bit % 8)
                if ((x[bit / 8].toInt() and mask) != (y[bit / 8].toInt() and mask)) return false
            }
            return true
        }
    }
}

/**
 * The screens' side of casting: finds TVs on your Wi-Fi while the cast picker is open, connects
 * to one, and says which one is playing.
 */
class CastRoutes(context: Context) {
    private val castContext = Cast.context(context)
    private val router: MediaRouter? = if (castContext != null) MediaRouter.getInstance(context.applicationContext) else null
    private val selector = MediaRouteSelector.Builder().addControlCategory(CastMediaControlIntent.categoryForCast(Cast.RECEIVER)).build()

    /** False on phones without Google Play services. */
    val available: Boolean get() = castContext != null

    var tvs by mutableStateOf<List<MediaRouter.RouteInfo>>(emptyList()); private set
    /** The TV playing your music, if any. */
    var connectedTo by mutableStateOf<String?>(null); private set
    var connecting by mutableStateOf<String?>(null); private set
    /** Set when connecting fails, for a message. */
    var onProblem: (String) -> Unit = {}

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            connecting = session.castDevice?.friendlyName ?: connecting
        }
        override fun onSessionStarted(session: CastSession, sessionId: String) = connected(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = connected(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            val name = connecting
            disconnected()
            onProblem("Couldn't connect to ${name ?: "the TV"}. Check it's on and on the same Wi-Fi as this phone.")
        }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) = disconnected()
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) = disconnected()
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    init {
        castContext?.sessionManager?.let { sm ->
            sm.addSessionManagerListener(sessionListener, CastSession::class.java)
            sm.currentCastSession?.takeIf { it.isConnected }?.let(::connected)
        }
    }

    private fun connected(session: CastSession) {
        connecting = null
        connectedTo = session.castDevice?.friendlyName ?: "your TV"
    }

    private fun disconnected() {
        connecting = null
        connectedTo = null
    }

    /** While the picker is open: look for TVs. */
    fun startLooking() {
        router?.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
    }

    fun stopLooking() {
        router?.removeCallback(routerCallback)
    }

    private fun refresh() {
        val r = router ?: return
        tvs = r.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }.sortedBy { it.name.lowercase() }
    }

    fun connect(tv: MediaRouter.RouteInfo) {
        connecting = tv.name
        router?.selectRoute(tv)
    }

    /** Stops the music on the TV; it carries on (paused) on this phone. */
    fun disconnect() {
        castContext?.sessionManager?.endCurrentSession(true)
        disconnected()
    }

    fun release() {
        stopLooking()
        castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
    }
}
