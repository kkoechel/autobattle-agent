package online.autobattle.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import mobile.Mobile
import online.autobattle.agent.engine.JsRunner
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * The server's JavaScript engine must be an exact substitute for the AAR.
 *
 * WHY. The Go engine can only change in a Play release, so a shipped app
 * measures with whatever engine it was built with until the next upload. The
 * JS engine is fetched from the server and is always current, and Play policy
 * permits that for interpreted code where it forbids it for a native .so. The
 * fallback is only sound if the two engines agree exactly, so this asserts the
 * SAME eleven CLI-derived digests EngineParityTest asserts of the AAR.
 *
 * Proven on the laptop before any of this was written: the served engine
 * reproduces all eleven, and sharding it across 2, 4 and 6 workers reproduces
 * payload.json's 1b5e48ac... every time -- hashResults() sorts by match_id, so
 * which worker played which match cannot matter.
 *
 * THE FIXTURES CANNOT TELL 4.72 FROM 4.73. All eleven predate Brindeloid
 * Warrior joining the filler set, and all eleven give identical digests under
 * both. That is why staleness is decided by the engine file's sha256 and not
 * by running a canary through it: a fixed canary only ever detects the change
 * it was built for.
 *
 * androidTest/assets/battle-engine.js is pinned so this test is hermetic:
 * sha256 0d507afbf3d3e01e83bc100d341da1962aceb7965cc893a1583f5e7caa059067,
 * 423,760 bytes, autobattle.online e369faf (v4.73), the exact bytes
 * https://autobattle.online/js/battle-engine.js served on 2026-11-23.
 * Re-pin it from the live URL when the engine moves, and re-derive digests
 * from the Go CLI (`cmd/parity <asset> --hash`), never from what this returns.
 */
@RunWith(AndroidJUnit4::class)
class JsEngineParityTest {

    private data class Fixture(val asset: String, val matches: Int, val digest: String)

    /** The same table as EngineParityTest. If one changes, both must. */
    private val fixtures = listOf(
        Fixture("payload.json", 1491,
            "1b5e48ac07ba3eb90ffaab7504d0bbc58c49cc7a39939c7b052f991a39c9b56b"),
        Fixture("payload_v460.json", 120,
            "b303ea8872bf6807ff218f1a7b20c54b574f0d66505632a3da7b4128ae0a9055"),
        Fixture("payload_v461.json", 80,
            "7a547e445bd7cc55bb7c9cbd65f0873c254131bcf1e7e2bcf8ce605e2affa950"),
        Fixture("payload_v462.json", 240,
            "2b0a61d7d626ef5ce39776fb93e6117fa55a875eeeaa3154f5a6facfdbaddb39"),
        Fixture("payload_v464.json", 60,
            "302f4d7a119f4c4832d4dffafcf15f884264f351b8421ca84e52869ba3809258"),
        Fixture("payload_v465.json", 40,
            "6f507a4ae65326ce2cc3e79f20ff0dff7ee59f40620eb9194543657efe2c79b8"),
        Fixture("payload_v466.json", 120,
            "2df8068f9d66315f7a01da9da8c3f886f3e8aee435358fd80051148ec1b3e7f1"),
        Fixture("payload_v467.json", 30,
            "f33f45bee51eae97dc6fb10245a13be29679d643c2fdd143def190918d74e307"),
        Fixture("payload_v468.json", 30,
            "509e52f703d5a0b3965bc6df899bae3b99afa5cda6fb6c938a54dee7fc5a18a7"),
        Fixture("payload_v469.json", 30,
            "4cd174174e7406da82f8a90ae1aa768abcf2f25ea2659530edf05d757e75fa6a"),
        Fixture("payload_v472.json", 15,
            "abd82c683432fd50fce72201bc98373b1c9864b4b98135cfc8b150bd9243d080"),
    )

    private val pinnedEngineSha =
        "0d507afbf3d3e01e83bc100d341da1962aceb7965cc893a1583f5e7caa059067"

    private fun assetBytes(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(name).use { it.readBytes() }

    private fun read(asset: String): String = String(assetBytes(asset), Charsets.UTF_8)

    /**
     * One runner for the whole class, closed at the end.
     *
     * The app context, not the instrumentation one, so the runner reads
     * cohort-worker.js from the APP's assets -- the same copy it will read in
     * production. Reused rather than rebuilt per test because the WebView
     * costs ~0.67s to bring up, and reuse is the behaviour worth testing:
     * these tests run many cohorts through one runner, as Compete.train does.
     */
    private val js by lazy {
        JsRunner(
            InstrumentationRegistry.getInstrumentation().targetContext,
            assetBytes("battle-engine.js"),
            20 * 60 * 1000L,
        )
    }

    @After
    fun shutDown() = js.close()

    /**
     * The pinned engine is the bytes it claims to be. Without this, a corrupted
     * or silently re-saved asset would look like an engine disagreement.
     */
    @Test
    fun pinnedEngineIsTheFileItClaimsToBe() {
        val sha = MessageDigest.getInstance("SHA-256").digest(assetBytes("battle-engine.js"))
            .joinToString("") { "%02x".format(it) }
        assertEquals(pinnedEngineSha, sha)
    }

    /** The whole point: the JS engine gives the Go CLI's digest, every fixture. */
    @Test
    fun digestsMatchTheCli() {
        for (f in fixtures) {
            val env = JSONObject(js.run(read(f.asset), 4))
            assertEquals(f.asset, f.digest, env.getString("hash"))
            assertEquals(f.asset, f.matches, env.getJSONArray("results").length())
        }
    }

    /**
     * Sharding must not change the answer. One worker and six must agree with
     * each other and with the CLI, or a cohort's result would depend on how
     * many cores the phone happened to give us.
     */
    @Test
    fun workerCountDoesNotChangeTheAnswer() {
        val f = fixtures.first { it.asset == "payload_v462.json" }
        val one = JSONObject(js.run(read(f.asset), 1))
        val many = JSONObject(js.run(read(f.asset), 6))
        assertEquals(one.getString("hash"), many.getString("hash"))
        assertEquals(f.digest, one.getString("hash"))
        assertEquals(one.getJSONArray("results").length(), many.getJSONArray("results").length())
    }

    /** Rows must survive the chunked bridge intact, not merely hash the same. */
    @Test
    fun resultsSurviveTheBridgeIntact() {
        val f = fixtures.first { it.asset == "payload.json" }   // 1,491 rows, the big one
        val env = JSONObject(js.run(read(f.asset), 4))
        val results = env.getJSONArray("results")
        assertEquals(f.matches, results.length())
        val row = results.getJSONObject(0)
        assertTrue("row has no match_id", row.has("match_id"))
        assertTrue("row has no reason", row.has("reason"))
    }

    /**
     * Go and JS, same payload, same device, compared to each other rather than
     * to a recorded constant -- so this still means something on a day when
     * both have moved on and the table above has been re-pinned.
     */
    @Test
    fun theTwoEnginesAgreeWithEachOtherOnThisDevice() {
        for (f in fixtures) {
            val go = JSONObject(Mobile.run(read(f.asset), 4L)).getString("hash")
            val jsHash = JSONObject(js.run(read(f.asset), 4)).getString("hash")
            assertEquals(f.asset, go, jsHash)
        }
    }
}
