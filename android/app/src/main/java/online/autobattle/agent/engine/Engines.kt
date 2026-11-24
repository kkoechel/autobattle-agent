package online.autobattle.agent.engine

import android.content.Context
import online.autobattle.agent.BuildConfig

/**
 * Picks the engine a measurement is taken with, and remembers which it was.
 *
 * THE RULE. Hash the live js/battle-engine.js and compare it with the sha of
 * that file at the commit this AAR was built from (BuildConfig.ENGINE_JS_SHA256,
 * read from libs/engine.aar.json):
 *
 *   same bytes      -> the AAR is current. Use Go; it is ~2.5x faster.
 *   different bytes -> the engine has moved past this build. Use the server's
 *                      JS, which is by definition what the league now scores
 *                      with.
 *   cannot tell     -> no network and no cached engine. Use Go and SAY SO.
 *
 * A false positive -- a comment-only change to the JS -- costs speed, never
 * correctness, so the comparison deliberately fails towards the slower engine.
 *
 * WHY NOT A VERSION STRING: battle-engine.js exposes none; its UMD exports are
 * only {runCohort, simulateMatch, recordBattle, hashResults}. WHY NOT A CANARY
 * PAYLOAD: a fixed canary only detects the change it was built for, and all
 * eleven parity fixtures give identical digests under 4.72 and 4.73 -- it would
 * have been blind to the very drift that prompted this.
 *
 * Offline behaviour is Ryan's call, 2026-11-23: measure anyway and label the
 * number, rather than refuse. An agent that stops working on a train is worse
 * than one that tells you which engine produced a figure.
 */
object Engines {

    /**
     * What ran, and whether we were able to confirm it is what the league uses.
     *
     * Note which case is the worrying one. [usingJs] means the AAR was behind
     * and we switched to the server's engine -- that is the mechanism working,
     * not a warning. The only measurement worth cautioning about is one taken
     * on the AAR when we could not reach the server to find out whether the
     * AAR is still current.
     */
    data class Use(
        val label: String,
        val checked: Boolean,
        val usingJs: Boolean,
    ) {
        /** One line for a result screen. Empty when there is nothing to say. */
        val caution: String
            get() = if (checked) "" else
                "measured with engine ${BuildConfig.ENGINE_VERSION}; could not " +
                    "reach the server to confirm that is what the league scores with"
    }

    @Volatile private var appCtx: Context? = null
    @Volatile private var js: JsRunner? = null
    @Volatile private var jsSha: String? = null

    @Volatile var lastUse: Use = Use("go ${BuildConfig.ENGINE_VERSION}", checked = false, usingJs = false)
        private set

    /** Call once, with the application context. */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /**
     * Plays the cohort on whichever engine is right, and records which.
     * Blocking and long; never call it on the main thread.
     */
    fun run(payload: String, workers: Int): String {
        val runner = pick()
        if (runner === GoRunner) return runner.run(payload, workers)
        return try {
            runner.run(payload, workers)
        } catch (t: CohortTimeout) {
            // Deliberately not falling back: see CohortTimeout. Go would be
            // handed the same payload with no way to interrupt it, so the
            // caller gets a failure it can report instead of a thread that
            // never returns. Every other JS failure still falls back below.
            synchronized(this) { js?.close(); js = null; jsSha = null }
            throw t
        } catch (t: Throwable) {
            // The JS path broke: a truncated engine, a WebView that would not
            // come up, a payload too big for the bridge. A number from the
            // older engine beats no number at all -- but it is no longer a
            // CHECKED number, so it is labelled like any other unverified one.
            synchronized(this) { js?.close(); js = null; jsSha = null }
            lastUse = Use("go ${BuildConfig.ENGINE_VERSION}", checked = false, usingJs = false)
            GoRunner.run(payload, workers)
        }
    }

    /** Test-only: forget the context as well as the cache. */
    internal fun forget() {
        synchronized(this) { js?.close(); js = null; jsSha = null }
        appCtx = null
        lastUse = Use("go ${BuildConfig.ENGINE_VERSION}", checked = false, usingJs = false)
    }

    private fun pick(): CohortRunner {
        val ctx = appCtx ?: run {
            // Engines.init was never called. Go is the safe answer, and the
            // label says the check did not happen rather than claiming it passed.
            lastUse = Use("go ${BuildConfig.ENGINE_VERSION}", checked = false, usingJs = false)
            return GoRunner
        }

        // THE CHEAP PATH. GET /api/v1/engine declares {version, sha256, url},
        // so the question "has the engine moved past this build?" costs a few
        // hundred bytes instead of a 437KB download, and the bytes that do get
        // downloaded are verified against a sha the server stated rather than
        // merely sniffed for shape.
        EngineSource.describe()?.let { d ->
            if (d.sha == BuildConfig.ENGINE_JS_SHA256) {
                lastUse = Use("go ${BuildConfig.ENGINE_VERSION}", checked = true, usingJs = false)
                return GoRunner
            }
            EngineSource.loadVerified(ctx, d)?.let { got ->
                lastUse = Use("js ${d.version} (server)", checked = true, usingJs = true)
                return runnerFor(ctx, got.bytes, got.sha)
            }
            // Declared, but the bytes could not be had intact. Fall through:
            // the old path may still have a usable cached engine.
        }

        val live = EngineSource.load(ctx)
        if (live == null) {
            lastUse = Use("go ${BuildConfig.ENGINE_VERSION}", checked = false, usingJs = false)
            return GoRunner
        }

        if (live.sha == BuildConfig.ENGINE_JS_SHA256) {
            lastUse = Use("go ${BuildConfig.ENGINE_VERSION}", checked = true, usingJs = false)
            return GoRunner
        }

        // The engine has moved. Build (or rebuild) the JS runner for these bytes.
        lastUse = Use("js ${live.sha.take(8)} (server)", checked = true, usingJs = true)
        return runnerFor(ctx, live.bytes, live.sha)
    }

    /** The JS runner for exactly these bytes, rebuilt when they change. */
    private fun runnerFor(ctx: Context, bytes: ByteArray, sha: String): CohortRunner =
        synchronized(this) {
            if (js == null || jsSha != sha) {
                js?.close()
                js = JsRunner(ctx, bytes)
                jsSha = sha
            }
            js!!
        }

    /** Drops any cached engine and runner. Tests, and a forced re-check. */
    fun reset(ctx: Context) {
        synchronized(this) {
            js?.close(); js = null; jsSha = null
        }
        EngineSource.clearCache(ctx)
    }
}
