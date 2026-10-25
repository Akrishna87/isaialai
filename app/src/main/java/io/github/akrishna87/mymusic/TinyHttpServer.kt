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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A very small web server for the phone's own network (see [CastServer] and [TvShareServer]):
 * GET and HEAD for songs and pictures (with byte ranges, so players can seek), small JSON
 * replies, and small POST bodies. It reads nothing it's sent beyond the request line, headers
 * and a body of at most 4 KB, handles a few requests at once, and drops slow ones.
 */
abstract class TinyHttpServer(private val threadName: String) {

    /** A song or picture to send: its size, type, and a way to read it from a byte offset. */
    class Source(val length: Long, val mime: String, val open: (offset: Long) -> InputStream)

    class Request(
        val method: String,
        /** The path split at "/", still URL-encoded, without the query. */
        val segments: List<String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    sealed class Reply {
        class Status(val code: Int, val text: String) : Reply()
        class Json(val json: String, val code: Int = 200) : Reply()
        class Media(val source: Source, val crossOrigin: Boolean = false) : Reply()
    }

    @Volatile private var socket: ServerSocket? = null
    private var pool = Executors.newFixedThreadPool(THREADS)

    /** When the last request came in (ms since 1970), for stopping when idle. */
    @Volatile var lastRequestAt = 0L
        private set

    val running: Boolean get() = socket?.isClosed == false

    /** "http://192.168.1.5:41231", or null when not running. */
    val origin: String?
        get() = socket?.takeIf { !it.isClosed }?.let { "http://${hostOf(it.inetAddress)}:${it.localPort}" }

    val port: Int get() = socket?.takeIf { !it.isClosed }?.localPort ?: -1

    protected abstract fun handle(request: Request): Reply

    /** Starts on [address], on [preferredPort] if it's free (else any free port). */
    @Synchronized
    open fun start(address: InetAddress, preferredPort: Int = 0) {
        stop()
        val s = ServerSocket()
        s.reuseAddress = true
        try {
            s.bind(InetSocketAddress(address, preferredPort), 16)
        } catch (e: IOException) {
            if (preferredPort == 0) throw e
            s.bind(InetSocketAddress(address, 0), 16)
        }
        socket = s
        lastRequestAt = System.currentTimeMillis()
        if (pool.isShutdown) pool = Executors.newFixedThreadPool(THREADS)
        Thread({ acceptLoop(s) }, threadName).apply { isDaemon = true }.start()
    }

    @Synchronized
    open fun stop() {
        socket?.let {
            try {
                it.close()
            } catch (e: IOException) {
            }
        }
        socket = null
        pool.shutdownNow()
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
                val out = c.getOutputStream()
                val parts = requestLine.split(' ')
                if (parts.size < 2) return status(out, 400, "Bad Request")
                val method = parts[0]
                if (method != "GET" && method != "HEAD" && method != "POST") return status(out, 405, "Method Not Allowed")
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length !in 0..MAX_BODY) return status(out, 413, "Payload Too Large")
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) return
                    read += n
                }
                lastRequestAt = System.currentTimeMillis()
                val segments = parts[1].substringBefore('?').trimStart('/').split('/')
                val reply = try {
                    handle(Request(method, segments, headers, body))
                } catch (e: Exception) {
                    Reply.Status(500, "Internal Server Error")
                }
                send(out, method, headers, reply)
            } catch (e: SocketException) {
                // the other side hung up (players do when seeking)
            } catch (e: IOException) {
            }
        }
    }

    private fun send(out: OutputStream, method: String, headers: Map<String, String>, reply: Reply) {
        when (reply) {
            is Reply.Status -> status(out, reply.code, reply.text)
            is Reply.Json -> {
                val bytes = reply.json.toByteArray(Charsets.UTF_8)
                head(
                    out,
                    reply.code,
                    if (reply.code == 200) "OK" else "Error",
                    listOf("Content-Type" to "application/json; charset=utf-8", "Content-Length" to bytes.size.toString(), "Cache-Control" to "no-store"),
                )
                if (method != "HEAD") out.write(bytes)
                out.flush()
            }
            is Reply.Media -> sendMedia(out, method, headers, reply)
        }
    }

    private fun sendMedia(out: OutputStream, method: String, headers: Map<String, String>, reply: Reply.Media) {
        val source = reply.source
        val len = source.length
        var start = 0L
        var end = len - 1
        var partial = false
        headers["range"]?.let { r ->
            val m = Regex("""bytes=(\d*)-(\d*)""").matchEntire(r.trim()) ?: return@let
            val (a, b) = m.destructured
            when {
                a.isEmpty() && b.isNotEmpty() -> start = (len - b.toLong()).coerceAtLeast(0) // the last b bytes
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
        )
        // A Cast receiver is a web page, so it needs permission to read across origins.
        if (reply.crossOrigin) extra += "Access-Control-Allow-Origin" to "*"
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

    private fun hostOf(a: InetAddress): String = a.hostAddress?.let { if (':' in it) "[$it]" else it } ?: "127.0.0.1"

    companion object {
        private const val THREADS = 6
        private const val MAX_BODY = 4096

        fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }

        val NOT_FOUND = Reply.Status(404, "Not Found")
    }
}
