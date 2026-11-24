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
 * Nothing is fetched from the real network here. [engineJs] is bytes the caller
 * has already verified against a sha256; this class only runs them.
 *
 * THE DIGEST IS COMPUTED IN KOTLIN, deliberately. The page hands back the same
 * canonical text that battle-engine.js's own hashResults() would hash -- the
 * rows sorted by match_id, JSON.stringify'd -- and MessageDigest hashes those
 * UTF-8 bytes. Letting the page call crypto.subtle instead would make the
 * digest depend on the WebView counting the synthetic origin as a secure
 * context, which is exactly the kind of thing that works until it does not.
 */
class JsRunner(
    appCtx: Context,
    private val engineJs: ByteArray,
    private val timeoutMs: Long = 30 * 60 * 1000L,
) : CohortRunner {

    private val appCtx = appCtx.applicationContext
    private val workerJs: ByteArray by lazy {
        this.appCtx.assets.open(WORKER_ASSET).use { it.readBytes() }
    }

    override val label = "js (server)"

    @SuppressLint("SetJavaScriptEnabled")
    override fun run(payload: String, workers: Int): String {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "JsRunner.run blocks until the cohort finishes; call it off the main thread"
        }

        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val canon = StringBuilder()
        val failure = AtomicReference<String?>(null)
        val rows = AtomicInteger(-1)
        val done = CountDownLatch(1)
        val held = AtomicReference<WebView?>(null)

        val bridge = object {
            /** A slice of the canonical results text. */
            @JavascriptInterface
            fun chunk(s: String) {
                synchronized(canon) { canon.append(s) }
            }

            @JavascriptInterface
            fun done(n: Int) {
                rows.set(n); done.countDown()
            }

            @JavascriptInterface
            fun fail(message: String) {
                failure.set(message); done.countDown()
            }
        }

        val main = Handler(Looper.getMainLooper())
        main.post {
            val wv = WebView(appCtx)
            held.set(wv)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = false
            wv.settings.blockNetworkLoads = true   // nothing here may touch the real network
            wv.addJavascriptInterface(bridge, BRIDGE)
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView, request: WebResourceRequest,
                ): WebResourceResponse? {
                    if (!request.url.host.equals(HOST, ignoreCase = true)) return null
                    return when (request.url.path) {
                        "/", "/index.html" -> serve("text/html", page(workers).toByteArray(Charsets.UTF_8))
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
                    if (request.isForMainFrame) {
                        failure.compareAndSet(null, "page failed to load: ${error.description}")
                        done.countDown()
                    }
                }
            }
            wv.loadUrl("https://$HOST/index.html")
        }

        val finished = try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            main.post { held.getAndSet(null)?.let { it.loadUrl("about:blank"); it.destroy() } }
        }

        if (!finished) throw IllegalStateException("JS engine did not finish within ${timeoutMs}ms")
        failure.get()?.let { throw IllegalStateException("JS engine: $it") }

        val text = synchronized(canon) { canon.toString() }
        check(text.isNotEmpty()) { "JS engine returned ${rows.get()} rows but no results text" }

        val hash = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        // Concatenated rather than built through JSONObject: `text` is already
        // valid JSON and can be megabytes, so re-parsing it to re-serialise it
        // would cost more than the simulation on a small run.
        return """{"results":$text,"hash":"$hash"}"""
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
     * CLI's digest for payload.json at 2, 4 and 6 shards.
     */
    private fun page(workers: Int): String = """
<!doctype html><meta charset="utf-8"><title>cohort</title><body><script>
(function () {
  var W = Math.max(1, Math.min(6, $workers));
  function fail(e) { $BRIDGE.fail(String((e && e.message) || e)); }
  try {
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
      return Promise.all(parts.map(function (part) {
        return new Promise(function (res, rej) {
          var w = new Worker('/$WORKER_ASSET');
          w.onerror = function (ev) { w.terminate(); rej(new Error(ev.message || 'worker failed')); };
          w.onmessage = function (ev) {
            w.terminate();
            if (ev.data && ev.data.ok) res(ev.data.results);
            else rej(new Error((ev.data && ev.data.message) || 'worker failed'));
          };
          var shard = { matches: part };
          for (var k in base) shard[k] = base[k];
          w.postMessage({ engineUrl: '/engine.js', payload: shard });
        });
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
        for (var p = 0; p < canon.length; p += STEP) $BRIDGE.chunk(canon.substr(p, STEP));
        $BRIDGE.done(merged.length);
      });
    }).catch(fail);
  } catch (e) { fail(e); }
})();
</script></body>
"""

    private companion object {
        /** Never resolved by DNS: every request to it is answered from memory. */
        const val HOST = "engine.local"
        const val BRIDGE = "Agent"
        const val WORKER_ASSET = "cohort-worker.js"
    }
}
