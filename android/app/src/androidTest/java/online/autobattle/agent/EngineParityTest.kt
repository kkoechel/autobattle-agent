package online.autobattle.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import mobile.Mobile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the JNI boundary changes nothing, and that the AAR is the engine the
 * league scores with.
 *
 * Everything before this point was verified on the Go side: the extracted
 * engine matches the CLI byte for byte, and the same digest comes out on x86
 * and on ARM. What none of that touches is the bridge itself — gomobile's
 * generated JNI layer, and the string marshalling in and out of it. A bridge
 * that quietly mangled UTF-8 or truncated a 3MB result would pass every Go
 * test and still ship broken numbers to players.
 *
 * ## The digests, and how to re-derive them
 *
 * Each constant below is the digest the Go CLI produces on that asset. The CLI
 * has no `--hash` flag, so the chain is: `validate --local-payload <asset>` and
 * `cmd/parity <asset>` emit byte-identical result arrays, and `cmd/parity
 * <asset> --hash` and `cmd/mobilecheck <asset>` — the latter calling the same
 * `mobile.Run` this test calls — emit the same digest over them. Re-derive from
 * the CLI when the engine moves; never from whatever the app now returns.
 *
 * ## What each fixture can and cannot witness
 *
 * Measured on 2026-11-11 across engines 4.59, 4.60 and 4.61:
 *
 *  - `payload.json` (2026-09-24, 1491 matches) is **identical under all
 *    three**. It proves no collateral drift across a wide spread of real
 *    cards, and nothing at all about any change since September. On its own it
 *    would accept a 4.59 AAR as a 4.61 one — which is how a September engine
 *    once ran against a v4.42 league for three weeks.
 *  - `payload_v460.json` (120 matches) **separates 4.59 from 4.60**: the
 *    "except" restricted-energy spend order changes its results.
 *  - `payload_v461.json` (80 matches) is **identical under all three**, and is
 *    kept anyway. v4.61 changes which pool pays a supplement, and only changes
 *    *whether* it is paid when ordinary energy is short; here ordinary energy
 *    accrues +1 a turn over 138 turns, so the payment happens either way and
 *    only the per-play log differs. `check_v461.js` reads that log and fails 2
 *    of 6 checks on a 4.60 engine; a result digest cannot see it. The fixture
 *    guards against a future regression that *does* reach the results.
 *
 * So no digest here can catch a 4.60 AAR. [engineVersion] is what catches a
 * stale AAR outright, and it must be bumped with `engine.aar.json`.
 */
@RunWith(AndroidJUnit4::class)
class EngineParityTest {

    /** Keep in step with android/app/libs/engine.aar.json and ENGINE.md. */
    private val engineVersion = "4.61"

    private data class Fixture(val asset: String, val matches: Int, val digest: String)

    private val fixtures = listOf(
        Fixture("payload.json", 1491,
            "1b5e48ac07ba3eb90ffaab7504d0bbc58c49cc7a39939c7b052f991a39c9b56b"),
        Fixture("payload_v460.json", 120,
            "b303ea8872bf6807ff218f1a7b20c54b574f0d66505632a3da7b4128ae0a9055"),
        Fixture("payload_v461.json", 80,
            "7a547e445bd7cc55bb7c9cbd65f0873c254131bcf1e7e2bcf8ce605e2affa950"),
    )

    private fun read(asset: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(asset).bufferedReader().use { it.readText() }

    @Test
    fun digestMatchesTheCli() {
        for (f in fixtures) {
            val env = JSONObject(Mobile.run(read(f.asset), 4L))
            assertEquals(f.asset, f.digest, env.getString("hash"))
        }
    }

    @Test
    fun workerCountDoesNotChangeTheAnswer() {
        // Sharding is only safe because the per-game state moved off package
        // globals. If that regresses, results diverge by worker count — and
        // they diverge nondeterministically, which is exactly the kind of bug
        // that survives a single-threaded test suite.
        for (f in fixtures) {
            val one = JSONObject(Mobile.run(read(f.asset), 1L))
            val many = JSONObject(Mobile.run(read(f.asset), 8L))
            assertEquals(f.asset, one.getString("hash"), many.getString("hash"))
            assertEquals(f.asset, f.digest, one.getString("hash"))
            assertEquals(
                f.asset,
                one.getJSONArray("results").length(),
                many.getJSONArray("results").length(),
            )
        }
    }

    @Test
    fun resultsSurviveTheBridgeIntact() {
        for (f in fixtures) {
            val results = JSONObject(Mobile.run(read(f.asset), 4L)).getJSONArray("results")
            assertEquals(f.asset, f.matches, results.length())
            val first = results.getJSONObject(0)
            assertTrue(f.asset, first.has("match_id"))
            assertTrue(f.asset, first.has("reason"))
        }
    }

    @Test
    fun engineVersionIsReported() {
        // The only assertion here that catches a stale AAR on its own. It read
        // "3.94" until 2026-11-11 -- seven weeks and nine engine releases out
        // of date, so this test had been failing for whoever ran it and
        // witnessing nothing for whoever did not.
        assertEquals(engineVersion, Mobile.version())
    }
}
