package io.github.akrishna87.mymusic.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.akrishna87.mymusic.TvShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** The phone this TV plays music from, and the key it was given when you allowed it. */
data class PhoneLink(val id: String, val name: String, val host: String, val port: Int, val key: String) {
    val origin: String get() = "http://${if (':' in host) "[$host]" else host}:$port"
    private val base: String get() = "$origin/t/$key"

    fun hello() = "$base/api/hello"
    fun browse(listId: String) = "$base/api/browse/${enc(listId)}"
    fun song(songId: String) = "$base/s/${enc(songId)}"
    fun art(songId: String) = "$base/a/${enc(songId)}"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("tv_link", Context.MODE_PRIVATE)

        @Volatile private var cached: PhoneLink? = null
        @Volatile private var loaded = false

        /** The phone this TV is paired with, if any. */
        fun saved(context: Context): PhoneLink? {
            if (loaded) return cached
            val p = prefs(context)
            cached = p.getString("key", null)?.let { key ->
                PhoneLink(p.getString("id", "").orEmpty(), p.getString("name", "your phone").orEmpty(), p.getString("host", "").orEmpty(), p.getInt("port", 0), key)
            }?.takeIf { it.host.isNotEmpty() && it.port > 0 }
            loaded = true
            return cached
        }

        fun save(context: Context, link: PhoneLink?) {
            val e = prefs(context).edit().clear()
            if (link != null) {
                e.putString("id", link.id).putString("name", link.name).putString("host", link.host)
                    .putInt("port", link.port).putString("key", link.key)
            }
            e.apply()
            cached = link
            loaded = true
        }
    }
}

/** A phone sharing its music on this Wi-Fi (Settings → Play on TV app on the phone). */
data class FoundPhone(val id: String, val name: String, val host: String, val port: Int)

/** The phone answered "no such thing": the key was forgotten on the phone, or a pairing request ran out. */
class GoneException : IOException("Not found")

/** Small JSON calls to the phone. */
object PhoneApi {
    suspend fun get(url: String, timeoutMs: Int = 6_000): JSONObject = call(url, null, timeoutMs)

    suspend fun post(url: String, body: JSONObject): JSONObject = call(url, body.toString(), 6_000)

    private suspend fun call(url: String, body: String?, timeoutMs: Int): JSONObject = withContext(Dispatchers.IO) {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            when (c.responseCode) {
                200 -> JSONObject(c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
                404 -> throw GoneException()
                else -> throw IOException("The phone said ${c.responseCode}")
            }
        } catch (e: FileNotFoundException) {
            throw GoneException()
        } catch (e: org.json.JSONException) {
            throw IOException("Not an answer from Isaialai")
        } finally {
            c.disconnect()
        }
    }

    /** Who's at [host]:[port], if it's Isaialai sharing. */
    suspend fun hello(host: String, port: Int): FoundPhone {
        val origin = "http://${if (':' in host) "[$host]" else host}:$port"
        val o = get("$origin/hello", 4_000)
        if (o.optString("app") != "isaialai") throw IOException("Not Isaialai")
        return FoundPhone(o.optString("id"), o.optString("name", "Phone"), host, port)
    }
}

/**
 * Looks for phones sharing their music on this Wi-Fi (they announce themselves, see
 * [io.github.akrishna87.mymusic.TvShareService]). Found phones appear in [phones].
 */
class PhoneFinder(context: Context) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    var phones by mutableStateOf<List<FoundPhone>>(emptyList()); private set
    /** Names of announced phones, to drop them again when they stop sharing. */
    private val byService = HashMap<String, FoundPhone>()

    fun start() {
        if (listener != null || nsd == null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    toResolve.addLast(info)
                    resolveNext()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    byService.remove(info.serviceName)
                    phones = byService.values.toList()
                }
            }
        }
        listener = l
        try {
            nsd.discoverServices(TvShare.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            listener = null
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        try {
            nsd?.stopServiceDiscovery(l)
        } catch (e: Exception) {
        }
    }

    /** Android resolves one service at a time. */
    private fun resolveNext() {
        if (resolving || nsd == null) return
        val info = toResolve.removeFirstOrNull() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        resolveNext()
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val attrs = info.attributes.orEmpty()
                    fun attr(k: String) = attrs[k]?.toString(Charsets.UTF_8)
                    // The phone says its own Wi-Fi address (the server listens only there).
                    val host = attr("ip") ?: info.host?.hostAddress
                    val phone = host?.let { FoundPhone(attr("id").orEmpty(), info.serviceName, it, info.port) }
                    main.post {
                        if (phone != null && listener != null) {
                            byService[info.serviceName] = phone
                            phones = byService.values.toList()
                        }
                        resolving = false
                        resolveNext()
                    }
                }
            })
        } catch (e: Exception) {
            resolving = false
        }
    }
}
