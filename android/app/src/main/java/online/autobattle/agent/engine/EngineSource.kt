package online.autobattle.agent.engine

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The live js/battle-engine.js: fetched, fingerprinted, kept on disk.
 *
 * WHAT THE SHA IS AND IS NOT. It is a FINGERPRINT used to tell whether the
 * engine has moved past the AAR -- not an integrity check. There is nothing to
 * verify it against: the server declares no sha for this file (the draft app
 * gets one in its /draft/compute payload; asking for the same on the agent API
 * is a main-app change we deliberately do not depend on). What protects the
 * bytes is HTTPS to autobattle.online, which is exactly the trust ui/Replay.kt
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
    private const val CACHE_FILE = "battle-engine.js"

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

    private fun download(): ByteArray {
        val bucket = System.currentTimeMillis() / DEFAULT_MAX_AGE_MS
        val conn = (URL("$ENGINE_URL?v=$bucket").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "AutoBattleAgent-Android")
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        try {
            if (conn.responseCode != 200) error("engine download: HTTP ${conn.responseCode}")
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
