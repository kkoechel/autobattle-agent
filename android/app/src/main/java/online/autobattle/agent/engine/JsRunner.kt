package online.autobattle.agent.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The server's own js/battle-engine.js, run in a headless WebView.
 *
 * Everything is served to the page from a SYNTHETIC ORIGIN rather than handed
 * to it as a string, and that one decision is load-bearing three times over:
 *
 *  - Workers, and therefore any parallelism at all, need a real origin. A page
 *    built with loadDataWithBaseURL does not reliably have one.
 *  - A training sweep's payload reaches ~3.4MB. Interpolating that into page
 *    source, or pushing it through evaluateJavascript, is not viable; the page
 *    just fetches '/payload.json' and this class streams it.
 *  - The engine is served the same way, so the worker can importScripts it.
 *
 * Nothing is fetched from the real network here: blockNetworkLoads is on.
 * [engineJs] is bytes the caller has already verified against a sha256; this
 * class only runs them.
 *
 * THE DIGEST IS COMPUTED IN KOTLIN, deliberately. The page hands back the same
 * canonical text that battle-engine.js's own hashResults() would hash -- the
 * rows sorted by match_id, JSON.stringify'd -- and MessageDigest hashes those
 * UTF-8 bytes. Letting the page call crypto.subtle instead would make the
 * digest depend on the WebView counting the synthetic origin as a secure
 * context, which is exactly the kind of thing that works until it does not.
 *
 * ONE PAGE AND ONE WORKER POOL, KEPT ALIVE. The page is loaded once and its
 * workers are kept, so cohort-worker.js's `loaded !== engineUrl` guard finally
 * earns its keep and the engine is parsed once per pool rather than once per
 * call. Compete.train makes up to fourteen calls in a run.
 *
 * WHAT IT ACTUALLY BOUGHT, measured on the S22 by running JsEngineParityTest's
 * eleven fixtures before and after: 10.86s -> 8.85s, so about 0.18s a call.
 * That is a QUARTER of the ~0.67s fixed cost per call, which means re-spawning
 * workers and re-parsing the engine was never the bulk of it. Twice now an
 * estimate of this overhead has come in high -- reusing the WebView alone was
 * predicted at ~7s and returned ~0.5s -- so the next person should measure
 * before believing a number here, this paragraph included.
 *
 * WHAT IS LEFT is the payload round trip, not the engine: every call still
 * fetches '/payload.json' and JSON.parses it, and that payload reaches ~3.4MB
 * on a training sweep. Anyone chasing the remaining per-call cost should start
 * there rather than with the pool.
 *
 * The price is that state now outlives a run, so anything that could leave the
 * page wrong -- a failure, a timeout, a change of engine bytes, a change of
 * worker count -- tears the pool down instead of reusing it. A stale engine
 * living on in a worker would measure with the wrong one and say nothing.
 */
class JsRunner(
    appCtx: Context,
    private val engineJs: ByteArray,
    private val timeoutMs: Long = 30 * 60 * 1000L,
) : CohortRunner, Closeable {

    private val appCtx = appCtx.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val workerJs: ByteArray by lazy {
        this.appCtx.assets.open(WORKER_ASSET).use { it.readBytes() }
    }

    /** State of the run in flight. Read by the bridge and the interceptor. */
    private class Pending {
        val canon = StringBuilder()
        val latch = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val rows = AtomicInteger(-1)
    }

    @Volatile private var pending: Pending? = null
    @Volatile private var payloadBytes: ByteArray = ByteArray(0)
    @Volatile private var web: WebView? = null

    /** The page is loaded and window.__run exists; the pool is alive. */
    @Volatile private var booted = false

    /** Released once the page says it is ready, or once it fails trying. */
    @Volatile private var bootSignal: CountDownLatch? = null

    override val label = "js (server)"

    @Synchronized
    override fun run(payload: String, workers: Int): String {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "JsRunner.run blocks until the cohort finishes; call it off the main thread"
        }

        val p = Pending()
        payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val n = workers.coerceIn(1, MAX_SHARDS)
        pending = p

        val wv = webView()
        boot(wv, p)
        if (p.failure.get() == null) {
            main.post { wv.evaluateJavascript("window.__run($n)", null) }
        }

        val finished = p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        pending = null

        if (!finished) {
            // The workers are still chewing on a payload that may never
            // finish, and terminating the page is the only way to stop them.
            // That costs the pool, which is the right trade: a cohort that has
            // already burned the timeout is past caring about a warm engine.
            close()
            throw CohortTimeout(
                "the cohort did not finish within ${timeoutMs / 60_000} minutes; the " +
                    "payload may contain a match that cannot be simulated",
            )
        }
        p.failure.get()?.let {
            // What the pool is holding after a failure is unknown, so make the
            // next run build a fresh one rather than reuse something broken.
            close()
            throw IllegalStateException("JS engine: $it")
        }

        val text = synchronized(p.canon) { p.canon.toString() }
        check(text.isNotEmpty()) { "JS engine reported ${p.rows.get()} rows but sent no results" }

        val hash = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        // Concatenated rather than built through JSONObject: `text` is already
        // valid JSON and can be megabytes, so re-parsing it to re-serialise it
        // would cost more than the simulation on a small run.
        return """{"results":$text,"hash":"$hash"}"""
    }

    /** Creates the WebView on the main thread, once, and waits for it. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun webView(): WebView {
        web?.let { return it }
        val made = AtomicReference<WebView?>(null)
        val ready = CountDownLatch(1)
        main.post {
            val wv = WebView(appCtx)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = false
            wv.settings.blockNetworkLoads = true
            wv.addJavascriptInterface(bridge, BRIDGE)
            wv.webViewClient = client
            made.set(wv)
            ready.countDown()
        }
        check(ready.await(30, TimeUnit.SECONDS)) { "WebView was not created within 30s" }
        return made.get()!!.also { web = it }
    }

    /**
     * Loads the page once and waits for it to report that window.__run exists.
     *
     * This is what makes the pool possible: afterwards the page stays loaded,
     * its workers stay alive, and a run is an evaluateJavascript into a context
     * that has already parsed the engine.
     */
    private fun boot(wv: WebView, p: Pending) {
        if (booted) return
        val ready = CountDownLatch(1)
        bootSignal = ready
        main.post { wv.loadUrl("https://$HOST/index.html") }
        if (!ready.await(BOOT_TIMEOUT_S, TimeUnit.SECONDS)) {
            p.failure.compareAndSet(
                null, "the page did not become ready within ${BOOT_TIMEOUT_S}s")
            return
        }
        bootSignal = null
        if (p.failure.get() == null) booted = true
    }

    override fun close() {
        val wv = web ?: return
        web = null
        booted = false
        bootSignal = null
        main.post { wv.loadUrl("about:blank"); wv.destroy() }
    }

    private val bridge = object {
        /** A slice of the canonical results text. */
        @JavascriptInterface
        fun chunk(s: String) {
            val p = pending ?: return
            synchronized(p.canon) { p.canon.append(s) }
        }

        @JavascriptInterface
        fun done(n: Int) {
            val p = pending ?: return
            p.rows.set(n); p.latch.countDown()
        }

        /** The page has defined window.__run and can be given work. */
        @JavascriptInterface
        fun ready() {
            bootSignal?.countDown()
        }

        @JavascriptInterface
        fun fail(message: String) {
            pending?.let { it.failure.compareAndSet(null, message); it.latch.countDown() }
            // A failure while booting has to release the boot wait too, or
            // run() sits out the full timeout before reporting what the page
            // has already told it.
            bootSignal?.countDown()
        }
    }

    private val client = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView, request: WebResourceRequest,
        ): WebResourceResponse? {
            if (!request.url.host.equals(HOST, ignoreCase = true)) return null
            return when (request.url.path) {
                "/", "/index.html" -> serve("text/html", page().toByteArray(Charsets.UTF_8))
                "/engine.js" -> serve("application/javascript", engineJs)
                "/$WORKER_ASSET" -> serve("application/javascript", workerJs)
                "/payload.json" -> serve("application/json", payloadBytes)
                else -> null
            }
        }

        override fun onReceivedError(
            view: WebView, request: WebResourceRequest,
            error: android.webkit.WebResourceError,
        ) {
            if (!request.isForMainFrame) return
            pending?.let {
                it.failure.compareAndSet(null, "page failed to load: ${error.description}")
                it.latch.countDown()
            }
            bootSignal?.countDown()
        }
    }

    private fun serve(mime: String, bytes: ByteArray) = WebResourceResponse(
        mime, "utf-8", 200, "OK",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(bytes),
    )

    /**
     * Shards stride-wise across workers and merges back by match_id.
     *
     * Sharding cannot change the answer: hashResults() sorts by match_id before
     * hashing, and the merge is keyed by match_id too. Verified against the Go
     * CLI's digest for payload.json at 1, 2, 4 and 6 shards.
     */
    private fun page(): String = """
<!doctype html><meta charset="utf-8"><title>cohort</title><body><script>
(function () {
  var POOL = null, SIZE = 0;
  function fail(e) { $BRIDGE.fail(String((e && e.message) || e)); }

  // One worker, kept across runs. The engine inside it is parsed on its first
  // shard only: cohort-worker.js refuses to re-import an engine it holds.
  function makeSlot() {
    var s = { w: new Worker('/$WORKER_ASSET'), res: null, rej: null };
    s.w.onerror = function (ev) {
      var rej = s.rej; s.res = s.rej = null;
      if (rej) rej(new Error(ev.message || 'worker failed'));
    };
    s.w.onmessage = function (ev) {
      var res = s.res, rej = s.rej; s.res = s.rej = null;
      if (ev.data && ev.data.ok) { if (res) res(ev.data.results); }
      else if (rej) rej(new Error((ev.data && ev.data.message) || 'worker failed'));
    };
    return s;
  }

  // Rebuilt only when the worker count changes; otherwise reused as it is.
  function pool(n) {
    if (POOL && SIZE === n) return POOL;
    if (POOL) POOL.forEach(function (s) { s.w.terminate(); });
    SIZE = n; POOL = [];
    for (var i = 0; i < n; i++) POOL.push(makeSlot());
    return POOL;
  }

  function send(s, msg) {
    return new Promise(function (res, rej) {
      s.res = res; s.rej = rej; s.w.postMessage(msg);
    });
  }

  // Called by Kotlin once per cohort. The payload is re-fetched every time;
  // JsRunner has swapped the bytes behind '/payload.json' before calling.
  window.__run = function (workers) {
    try {
      var W = Math.max(1, Math.min($MAX_SHARDS, workers));
      var slots = pool(W);
      fetch('/payload.json').then(function (r) { return r.json(); }).then(function (payload) {
        var base = { cards: payload.cards, slots: payload.slots,
                     chaos_effect: payload.chaos_effect || null };
        if (payload.starting_life != null) base.starting_life = payload.starting_life;
        if (payload.honor_to_win != null) base.honor_to_win = payload.honor_to_win;

        var parts = [];
        for (var i = 0; i < W; i++) {
          var part = payload.matches.filter(function (_, j) { return j % W === i; });
          if (part.length) parts.push(part);
        }
        return Promise.all(parts.map(function (part, i) {
          var shard = { matches: part };
          for (var k in base) shard[k] = base[k];
          return send(slots[i], { engineUrl: '/engine.js', payload: shard });
        })).then(function (out) {
          var by = {};
          out.forEach(function (rowsIn) { rowsIn.forEach(function (r) { by[r.match_id] = r; }); });
          var merged = [], missing = 0;
          payload.matches.forEach(function (m) {
            var r = by[m.match_id];
            if (r) merged.push(r); else missing++;
          });
          if (missing) throw new Error(missing + ' of ' + payload.matches.length + ' matches came back empty');

          // The same canonical form hashResults() uses, hashed on the Kotlin side.
          var canon = JSON.stringify(merged.slice().sort(function (a, b) {
            return a.match_id < b.match_id ? -1 : 1;
          }));
          var STEP = 262144;
          for (var q = 0; q < canon.length; q += STEP) $BRIDGE.chunk(canon.substr(q, STEP));
          $BRIDGE.done(merged.length);
        });
      }).catch(fail);
    } catch (e) { fail(e); }
  };

  $BRIDGE.ready();
})();
</script></body>
"""

    private companion object {
        /** Never resolved by DNS: every request to it is answered from memory. */
        const val HOST = "engine.local"
        const val BRIDGE = "Agent"
        const val WORKER_ASSET = "cohort-worker.js"
        const val MAX_SHARDS = 6
        const val BOOT_TIMEOUT_S = 60L
    }
}
