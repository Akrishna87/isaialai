package io.github.akrishna87.mymusic

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Hands songs to the TV while casting. A TV can't read files on the phone, so it's given web
 * addresses on this phone instead, and this tiny server answers them:
 * - only on the home Wi-Fi address it's started on (not the mobile network);
 * - only with a long random secret in the address, new each time casting starts;
 * - only for songs that were put in the cast queue ([urlFor]), and only to read them;
 * - with byte ranges, so the TV can seek.
 * It stops when casting stops.
 */
class CastServer(private val sources: (kind: String, id: String) -> Source?) {

    /** A song (or cover picture) to send: its size, type, and a way to read it from a byte offset. */
    class Source(val length: Long, val mime: String, val open: (offset: Long) -> InputStream)

    companion object {
        const val SONG = "s"
        const val ART = "a"
    }

    private val random = SecureRandom()
    private val allowed = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var token = ""
    private var pool = Executors.newFixedThreadPool(6)

    val running: Boolean get() = socket?.isClosed == false

    /** "http://192.168.1.5:41231/<secret>", or null when not running. */
    val base: String?
        get() = socket?.takeIf { !it.isClosed }?.let { "http://${hostOf(it.inetAddress)}:${it.localPort}/$token" }

    @Synchronized
    fun start(address: InetAddress) {
        stop()
        token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(address, 0), 16)
        socket = s
        if (pool.isShutdown) pool = Executors.newFixedThreadPool(6)
        Thread({ acceptLoop(s) }, "cast-server").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        socket?.let {
            try {
                it.close()
            } catch (e: IOException) {
            }
        }
        socket = null
        allowed.clear()
        pool.shutdownNow()
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

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val client = try {
                s.accept()
            } catch (e: IOException) {
                break
            }
            try {
                pool.execute { serve(client) }
            } catch (e: Exception) {
                client.close()
            }
        }
    }

    private fun serve(client: Socket) {
        client.use { c ->
            try {
                c.soTimeout = TimeUnit.SECONDS.toMillis(20).toInt()
                val input = BufferedInputStream(c.getInputStream())
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                    if (headers.size > 64) return
                }
                respond(c.getOutputStream(), requestLine, headers)
            } catch (e: SocketException) {
                // the TV closed the connection (it does when seeking)
            } catch (e: IOException) {
            }
        }
    }

    private fun respond(out: OutputStream, requestLine: String, headers: Map<String, String>) {
        val parts = requestLine.split(' ')
        if (parts.size < 2) return status(out, 400, "Bad Request")
        val method = parts[0]
        if (method != "GET" && method != "HEAD") return status(out, 405, "Method Not Allowed")
        // /<token>/<kind>/<id>
        val path = parts[1].substringBefore('?').trimStart('/').split('/', limit = 3)
        if (path.size != 3 || token.isEmpty() || !constantTimeEquals(path[0], token)) return status(out, 404, "Not Found")
        val id = try {
            URLDecoder.decode(path[2], "UTF-8")
        } catch (e: IllegalArgumentException) {
            return status(out, 404, "Not Found")
        }
        if ("${path[1]}/$id" !in allowed) return status(out, 404, "Not Found")
        val source = sources(path[1], id) ?: return status(out, 404, "Not Found")

        val len = source.length
        var start = 0L
        var end = len - 1
        var partial = false
        headers["range"]?.let { r ->
            val m = Regex("""bytes=(\d*)-(\d*)""").matchEntire(r.trim()) ?: return@let
            val (a, b) = m.destructured
            when {
                a.isEmpty() && b.isNotEmpty() -> { start = (len - b.toLong()).coerceAtLeast(0) } // the last b bytes
                a.isNotEmpty() -> {
                    start = a.toLong()
                    if (b.isNotEmpty()) end = minOf(b.toLong(), len - 1)
                }
                else -> return@let
            }
            partial = true
        }
        if (len <= 0 || start > end || start >= len) {
            return head(out, 416, "Range Not Satisfiable", listOf("Content-Range" to "bytes */$len", "Content-Length" to "0"))
        }
        val count = end - start + 1
        val extra = mutableListOf(
            "Content-Type" to source.mime,
            "Content-Length" to count.toString(),
            "Accept-Ranges" to "bytes",
            // The Cast receiver is a web page, so it needs permission to read across origins.
            "Access-Control-Allow-Origin" to "*",
        )
        if (partial) extra += "Content-Range" to "bytes $start-$end/$len"
        head(out, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", extra)
        if (method == "HEAD") return
        source.open(start).use { input ->
            val buf = ByteArray(64 * 1024)
            var left = count
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        out.flush()
    }

    private fun status(out: OutputStream, code: Int, text: String) = head(out, code, text, listOf("Content-Length" to "0"))

    private fun head(out: OutputStream, code: Int, text: String, headers: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 $code $text\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    /** One header line, at most 8 KB (anything longer isn't a real request). */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun hostOf(a: InetAddress): String = a.hostAddress?.let { if (':' in it) "[$it]" else it } ?: "127.0.0.1"
}
