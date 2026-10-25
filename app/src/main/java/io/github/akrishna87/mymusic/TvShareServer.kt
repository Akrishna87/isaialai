package io.github.akrishna87.mymusic

import org.json.JSONObject
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Lets your TV (Isaialai's TV app) play this phone's music over your home Wi-Fi, while you have
 * "Share with your TV" on. A TV first asks to pair: it shows a 4-digit code, you check it matches
 * on the phone and tap Allow, and the TV gets a long random key. Everything else needs that key:
 * browsing your lists, and reading a song or cover to play. See [TvShareService].
 *
 * Addresses (the key is in the path, so the TV's player can use them as they are):
 * - `GET /hello`: which phone this is (no key needed; the same as the phone announces on Wi-Fi)
 * - `POST /pair` {"name", "code"} → {"request"}; then `GET /pair/<request>` → waiting, denied, or ok + key
 * - `GET /t/<key>/api/hello`, `/t/<key>/api/browse/<list id>`, `/t/<key>/s/<song id>`, `/t/<key>/a/<song id>`
 */
class TvShareServer(
    private val about: () -> Pair<String, String>,
    private val library: Library,
    private val pairings: Pairings,
    private val onRequestsChanged: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : TinyHttpServer("tv-share") {

    /** What the TV can browse and play. */
    interface Library {
        /** A list's sub-lists and songs, as JSON, or null if there's no such list. */
        fun browse(id: String): JSONObject?
        fun song(id: String): Source?
        fun art(id: String): Source?
    }

    /** The TVs you've allowed, by key (stored as a hash). */
    interface Pairings {
        fun isPaired(key: String): Boolean
        fun add(key: String, tvName: String)
    }

    class PairRequest(val id: String, val tvName: String, val code: String, val at: Long) {
        /** The key once allowed, "" once refused, null while waiting. */
        @Volatile var result: String? = null
    }

    companion object {
        const val VERSION = 1
        const val PREFERRED_PORT = 47123
        private const val PAIR_TIMEOUT_MS = 3 * 60_000L
        private const val MAX_WAITING = 3
        private const val MAX_ASKS_PER_MINUTE = 6

        fun hash(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

        /** A TV's name as sent, made safe to show: no control characters, at most 40 characters. */
        fun cleanName(name: String?): String =
            name.orEmpty().filter { !it.isISOControl() && it != '‮' && it != '‭' }.trim().take(40).ifEmpty { "A TV" }
    }

    private val random = SecureRandom()
    private val requests = LinkedHashMap<String, PairRequest>()
    private val asks = ArrayDeque<Long>()

    /** TVs waiting for you to allow them, oldest first. */
    fun waiting(): List<PairRequest> = synchronized(requests) {
        expire()
        requests.values.filter { it.result == null }
    }

    fun allow(requestId: String) {
        val r = synchronized(requests) { requests[requestId]?.takeIf { it.result == null } } ?: return
        val key = randomHex(32)
        pairings.add(key, r.tvName)
        r.result = key
        onRequestsChanged()
    }

    fun refuse(requestId: String) {
        synchronized(requests) { requests[requestId]?.takeIf { it.result == null }?.result = "" }
        onRequestsChanged()
    }

    @Synchronized
    override fun stop() {
        super.stop()
        synchronized(requests) { requests.clear() }
        onRequestsChanged()
    }

    override fun handle(request: Request): Reply {
        val s = request.segments
        val m = request.method
        return when {
            m == "GET" && s == listOf("hello") -> hello()
            m == "POST" && s == listOf("pair") -> askToPair(request.body)
            m == "GET" && s.size == 2 && s[0] == "pair" -> pairResult(s[1])
            s.size >= 2 && s[0] == "t" -> {
                if (m == "POST" || !pairings.isPaired(s[1])) return NOT_FOUND
                val rest = s.drop(2)
                when {
                    rest == listOf("api", "hello") -> hello()
                    rest.size == 3 && rest[0] == "api" && rest[1] == "browse" ->
                        decode(rest[2])?.let(library::browse)?.let { Reply.Json(it.toString()) } ?: NOT_FOUND
                    rest.size == 2 && rest[0] == "s" -> decode(rest[1])?.let(library::song)?.let { Reply.Media(it) } ?: NOT_FOUND
                    rest.size == 2 && rest[0] == "a" -> decode(rest[1])?.let(library::art)?.let { Reply.Media(it) } ?: NOT_FOUND
                    else -> NOT_FOUND
                }
            }
            else -> NOT_FOUND
        }
    }

    private fun hello(): Reply {
        val (id, name) = about()
        return Reply.Json(JSONObject().put("app", "isaialai").put("v", VERSION).put("id", id).put("name", name).toString())
    }

    private fun askToPair(body: ByteArray): Reply {
        val o = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            return Reply.Status(400, "Bad Request")
        }
        val code = o.optString("code")
        if (!Regex("\\d{4}").matches(code)) return Reply.Status(400, "Bad Request")
        val r = synchronized(requests) {
            expire()
            val now = clock()
            while (asks.isNotEmpty() && now - asks.first() > 60_000) asks.removeFirst()
            if (asks.size >= MAX_ASKS_PER_MINUTE || requests.values.count { it.result == null } >= MAX_WAITING) {
                return Reply.Status(429, "Too Many Requests")
            }
            asks.addLast(now)
            PairRequest(randomHex(16), cleanName(o.optString("name")), code, now).also { requests[it.id] = it }
        }
        onRequestsChanged()
        return Reply.Json(JSONObject().put("request", r.id).toString())
    }

    private fun pairResult(id: String): Reply {
        val r = synchronized(requests) {
            expire()
            requests[id]
        } ?: return NOT_FOUND
        val result = r.result
        val json = JSONObject()
        when {
            result == null -> json.put("status", "waiting")
            result.isEmpty() -> json.put("status", "denied")
            else -> json.put("status", "ok").put("key", result)
        }
        // The answer is given once.
        if (result != null) synchronized(requests) { requests.remove(id) }
        return Reply.Json(json.toString())
    }

    private fun expire() {
        val now = clock()
        val gone = requests.values.filter { now - it.at > PAIR_TIMEOUT_MS }
        gone.forEach { requests.remove(it.id) }
    }

    private fun decode(s: String): String? = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun randomHex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}
