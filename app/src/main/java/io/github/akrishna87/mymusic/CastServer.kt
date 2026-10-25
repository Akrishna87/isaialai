package io.github.akrishna87.mymusic

import java.net.InetAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands songs to the TV while casting. A TV can't read files on the phone, so it's given web
 * addresses on this phone instead, and this tiny server answers them:
 * - only on the home Wi-Fi address it's started on (not the mobile network);
 * - only with a long random secret in the address, new each time casting starts;
 * - only for songs that were put in the cast queue ([urlFor]), and only to read them;
 * - with byte ranges, so the TV can seek.
 * It stops when casting stops.
 */
class CastServer(private val sources: (kind: String, id: String) -> Source?) : TinyHttpServer("cast-server") {

    companion object {
        const val SONG = "s"
        const val ART = "a"
    }

    private val random = SecureRandom()
    private val allowed = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var token = ""

    /** "http://192.168.1.5:41231/<secret>", or null when not running. */
    val base: String? get() = origin?.let { "$it/$token" }

    @Synchronized
    override fun start(address: InetAddress, preferredPort: Int) {
        token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        super.start(address, preferredPort)
    }

    @Synchronized
    override fun stop() {
        super.stop()
        allowed.clear()
    }

    /**
     * The address the TV reads song [id] from (kind "s"), or its cover picture (kind "a"); from now
     * on it may. Null when not running.
     */
    fun urlFor(id: String, kind: String = SONG): String? {
        val b = base ?: return null
        allowed += "$kind/$id"
        return "$b/$kind/" + URLEncoder.encode(id, "UTF-8")
    }

    override fun handle(request: Request): Reply {
        if (request.method == "POST") return Reply.Status(405, "Method Not Allowed")
        // /<token>/<kind>/<id>
        val path = request.segments
        if (path.size != 3 || token.isEmpty() || !constantTimeEquals(path[0], token)) return NOT_FOUND
        val id = try {
            URLDecoder.decode(path[2], "UTF-8")
        } catch (e: IllegalArgumentException) {
            return NOT_FOUND
        }
        if ("${path[1]}/$id" !in allowed) return NOT_FOUND
        val source = sources(path[1], id) ?: return NOT_FOUND
        return Reply.Media(source, crossOrigin = true)
    }
}
