package online.autobattle.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import online.autobattle.agent.engine.EngineSource
import online.autobattle.agent.engine.Engines
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which engine a measurement is taken with, and whether the app says so.
 *
 * These assert the RULE, not today's world. The interesting case right now is
 * that the AAR is 4.72 while the server serves 4.73, so the router should pick
 * JS -- but the moment the AAR is rebuilt that flips, and a test pinned to
 * "expect JS" would start failing for the best possible reason. So the
 * expectation is computed from the same two shas the router compares, and what
 * is asserted is that the router agrees with them and that the digest is right
 * either way.
 */
@RunWith(AndroidJUnit4::class)
class EnginesRoutingTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 15 matches: enough to be a real cohort, quick enough to run four times. */
    private val canary = "payload_v472.json"
    private val canaryDigest =
        "abd82c683432fd50fce72201bc98373b1c9864b4b98135cfc8b150bd9243d080"

    private fun read(asset: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(asset).bufferedReader().use { it.readText() }

    @After
    fun tidy() = Engines.forget()

    /**
     * Un-initialised, it must fall back to the AAR AND admit the check never
     * happened. Silently using the AAR and calling it verified is the exact
     * failure this whole mechanism exists to stop.
     */
    @Test
    fun withoutInitItUsesTheAarAndSaysTheCheckDidNotHappen() {
        Engines.forget()
        val env = JSONObject(Engines.run(read(canary), 4))
        assertEquals(canaryDigest, env.getString("hash"))
        assertFalse("must not claim the JS path", Engines.lastUse.usingJs)
        assertFalse("must not claim it checked", Engines.lastUse.checked)
        assertTrue("an unchecked measurement must carry a caution",
            Engines.lastUse.caution.isNotEmpty())
    }

    /** The decision rule, against whatever the server is serving today. */
    @Test
    fun routesOnTheLiveEngineSha() {
        Engines.forget()
        Engines.reset(ctx)
        Engines.init(ctx)

        val live = EngineSource.load(ctx, 0L)   // maxAge 0: force a fetch
        Assume.assumeNotNull("offline: there is no routing decision to make", live)
        val expectJs = live!!.sha != BuildConfig.ENGINE_JS_SHA256

        val env = JSONObject(Engines.run(read(canary), 4))
        assertEquals("the digest must be right whichever engine ran",
            canaryDigest, env.getString("hash"))
        assertEquals(
            "live ${live.sha.take(8)} vs AAR's js ${BuildConfig.ENGINE_JS_SHA256.take(8)}",
            expectJs, Engines.lastUse.usingJs,
        )
        assertTrue("it reached the server, so it checked", Engines.lastUse.checked)
        assertEquals("a checked measurement needs no caution", "", Engines.lastUse.caution)
    }

    /**
     * The server must serve the engine it says it serves.
     *
     * This is what GET /api/v1/engine bought us and the old path could not do:
     * before, a download was checked for SHAPE (big enough, has the right two
     * symbols in it) because nothing declared what it should hash to.
     */
    @Test
    fun theServerDeclaresTheEngineItActuallyServes() {
        Engines.forget()
        Engines.reset(ctx)
        val d = EngineSource.describe()
        Assume.assumeNotNull("offline: there is nothing to check", d)

        val got = EngineSource.loadVerified(ctx, d!!)
        assertNotNull("declared ${d.sha.take(8)} but those bytes did not verify", got)
        assertEquals("the sha must be the declared one", d.sha, got!!.sha)
        assertTrue("an engine is a few hundred KB", got.bytes.size > 100_000)
    }

    /**
     * And a declaration that does not match the bytes must be REFUSED rather
     * than run. Without this the sha would be decoration.
     */
    @Test
    fun bytesThatDoNotMatchTheDeclaredShaAreRefused() {
        Engines.forget()
        Engines.reset(ctx)
        val d = EngineSource.describe()
        Assume.assumeNotNull("offline: there is nothing to check", d)

        val lie = EngineSource.Desc(d!!.version, "0".repeat(64), d.url)
        assertNull("bytes that hash to something else were accepted",
            EngineSource.loadVerified(ctx, lie))
    }

    /**
     * A truncated cache must not be run as an engine. It would hash to
     * something that is not the AAR's sha, so the router would send a cohort
     * to a WebView holding half a file.
     */
    @Test
    fun aTruncatedCachedEngineIsRefused() {
        Engines.forget()
        Engines.reset(ctx)
        java.io.File(ctx.filesDir, "battle-engine.js").writeBytes(ByteArray(2048) { 'x'.code.toByte() })

        // maxAge huge: if the shape check did not reject it, this would be
        // served from cache rather than re-downloaded.
        val loaded = EngineSource.load(ctx, Long.MAX_VALUE)
        if (loaded != null) {
            assertTrue("a 2KB file was accepted as the engine", loaded.bytes.size > 100_000)
        }
    }
}
