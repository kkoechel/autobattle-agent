package online.autobattle.agent.engine

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The live js/battle-engine.js: fetched, fingerprinted, kept on disk.
 *
 * THE SERVER NOW DECLARES ITS ENGINE. GET /api/v1/engine answers
 * {engine_version, engine_sha256, engine_url} (main added it 2026-11-24 at
 * Ryan's request), so the normal path is: ask, compare the declared sha with
 * this build's, and download only when they differ -- then VERIFY the bytes
 * against the sha the server declared. That is a real integrity check, which
 * the old path could not do, and it replaces a 437KB download every six hours
 * with a request of a couple of hundred bytes.
 *
 * engine_url is sha-versioned, so unlike the bare path it can be cached hard.
 * Its host is checked before it is fetched: a URL out of a response body is
 * not a licence to go anywhere.
 *
 * THE OLD PATH IS KEPT as a fallback, for when the endpoint cannot be reached.
 * There the sha is only a FINGERPRINT for "has the engine moved past the AAR",
 * not an integrity check -- nothing declares the expected value, so HTTPS to
 * autobattle.online is what protects the bytes, exactly the trust ui/Replay.kt
 * already places in the same file when it loads it into a WebView.
 *
 * CACHE-BUSTING IS NOT OPTIONAL. The plain URL is served
 * `Cache-Control: public, max-age=31536000`, so an unversioned fetch can be
 * answered from a year-old copy. The draft app hit this and solved it with a
 * versioned URL; we cannot version by sha (we are fetching it to learn the
 * sha), so the query carries an hourly bucket and the request says no-cache.
 */
object EngineSource {

    private const val ENGINE_URL = "https://autobattle.online/js/battle-engine.js"
    private const val DESCRIBE_URL = "https://autobattle.online/api/v1/engine"
    private const val CACHE_FILE = "battle-engine.js"

    /** Only this host is ever fetched, whatever a response body asks for. */
    private const val ALLOWED_HOST = "autobattle.online"

    /** What the server says its engine is. */
    class Desc(val version: String, val sha: String, val url: String)

    /**
     * Asks the server which engine it is serving. Null when the endpoint
     * cannot be reached or answers something unusable -- the caller then
     * falls back to [load].
     */
    fun describe(): Desc? = runCatching {
        val conn = (java.net.URL(DESCRIBE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "AutoBattleAgent-Android")
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        val body = try {
            if (conn.responseCode != 200) return@runCatching null
            conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
        val o = org.json.JSONObject(body)
        val sha = o.optString("engine_sha256").lowercase()
        val url = o.optString("engine_url")
        val version = o.optString("engine_version")
        // Everything here came off the wire, so none of it is trusted on sight:
        // a sha that is not a sha cannot be compared, and a url that is not on
        // our own host is not fetched.
        if (!sha.matches(Regex("[0-9a-f]{64}"))) return@runCatching null
        val host = runCatching { java.net.URL(url).host }.getOrNull()
        if (!url.startsWith("https://") || !host.equals(ALLOWED_HOST, ignoreCase = true)) {
            return@runCatching null
        }
        Desc(version, sha, url)
    }.getOrNull()

    /**
     * The engine the server declared, from cache or freshly downloaded, with
     * the bytes CHECKED against [want].sha. Null when it cannot be had intact.
     */
    fun loadVerified(ctx: Context, want: Desc): Loaded? {
        val f = File(ctx.applicationContext.filesDir, CACHE_FILE)
        val cached = if (f.exists()) runCatching { f.readBytes() }.getOrNull() else null
        if (cached != null && sha256(cached) == want.sha) {
            return Loaded(cached, want.sha, fresh = false)
        }
        val got = runCatching { download(want.url) }.getOrNull() ?: return null
        // The whole point of the endpoint: bytes that do not hash to what the
        // server declared are discarded, not run.
        if (sha256(got) != want.sha || !looksLikeEngine(got)) return null
        runCatching {
            File(f.parentFile, "$CACHE_FILE.tmp").let { tmp ->
                tmp.writeBytes(got)
                if (!tmp.renameTo(f)) { f.writeBytes(got); tmp.delete() }
            }
        }
        return Loaded(got, want.sha, fresh = true)
    }

    /** Six hours: an engine ships a few times a week at most. */
    const val DEFAULT_MAX_AGE_MS = 6L * 60 * 60 * 1000

    class Loaded(val bytes: ByteArray, val sha: String, val fresh: Boolean)

    /**
     * Returns the engine, preferring a cache entry younger than [maxAgeMs].
     * Null only when there is no cache AND the network did not answer -- the
     * caller then has nothing to run but the AAR.
     */
    fun load(ctx: Context, maxAgeMs: Long = DEFAULT_MAX_AGE_MS): Loaded? {
        val f = File(ctx.applicationContext.filesDir, CACHE_FILE)

        // Re-hashed on every read rather than trusting a stored sha: a
        // truncated write or a half-finished download must not be able to
        // present itself as a known-good engine.
        val cached = if (f.exists()) runCatching { f.readBytes() }.getOrNull()?.takeIf(::looksLikeEngine)
            else null
        if (cached != null && System.currentTimeMillis() - f.lastModified() < maxAgeMs) {
            return Loaded(cached, sha256(cached), fresh = false)
        }

        val downloaded = runCatching { download() }.getOrNull()?.takeIf(::looksLikeEngine)
        if (downloaded == null) {
            // Stale beats absent: an old engine still tells us whether the AAR
            // was current as of the last time we could ask.
            return cached?.let { Loaded(it, sha256(it), fresh = false) }
        }

        runCatching { File(f.parentFile, "$CACHE_FILE.tmp").let { tmp ->
            tmp.writeBytes(downloaded)
            if (!tmp.renameTo(f)) { f.writeBytes(downloaded); tmp.delete() }
        } }
        return Loaded(downloaded, sha256(downloaded), fresh = true)
    }

    /** Forgets the cache, so the next [load] must go to the network. */
    fun clearCache(ctx: Context) {
        File(ctx.applicationContext.filesDir, CACHE_FILE).delete()
    }

    private fun download(url: String? = null): ByteArray {
        val bucket = System.currentTimeMillis() / DEFAULT_MAX_AGE_MS
        val target = url ?: "$ENGINE_URL?v=$bucket"
        val conn = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "AutoBattleAgent-Android")
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        try {
            if (conn.responseCode != 200) error("engine download: HTTP ${conn.responseCode} from $target")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * A cheap shape check, not an integrity check.
     *
     * There is no sha to verify against, so this cannot detect a substituted
     * engine -- HTTPS is what does that. What it DOES catch is the common
     * failure: a truncated download or a half-written cache file, which would
     * otherwise be hashed, compared, and then handed to a WebView as the
     * engine. A captive-portal HTML page fails it too.
     */
    private fun looksLikeEngine(bytes: ByteArray): Boolean {
        if (bytes.size < 100_000) return false
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8)
        val tail = String(bytes, maxOf(0, bytes.size - 4096), minOf(bytes.size, 4096), Charsets.UTF_8)
        return "BattleEngine" in head + tail && "runCohort" in head + tail
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
