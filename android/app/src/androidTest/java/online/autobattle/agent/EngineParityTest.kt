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
 * ## What each fixture actually witnesses
 *
 * Measured 2026-11-16 by running every fixture through engine 4.69 and engine
 * 4.61 — the version this AAR replaced — and comparing digests:
 *
 *  - **Catch a 4.61 AAR:** `payload_v462` (Flying/Shieldbreaker combat),
 *    `payload_v464` (Patient), `payload_v465` (per-card self-sacrifice cap),
 *    `payload_v466` (Reach), `payload_v467` (tagged anthems narrow by
 *    supertype), `payload_v468` (granted keywords match keyword tags).
 *  - **Blind to it:** `payload.json` (2026-09-24, unchanged since 4.59),
 *    `payload_v460`, `payload_v461`, and `payload_v469`.
 *
 * The blind ones are kept deliberately, and the reason differs. `payload.json`
 * is the only broad one: 1491 matches over real cards, so it is the collateral
 * check. `v460`, `v461` and `v469` each target a change that moves the per-play
 * LOG without moving the match RESULT — v4.61 changes which pool pays a
 * supplement, v4.69's two plan flags change play order — and a result digest
 * cannot see that. `check_v461.js` and `check_v469.js` can, and do.
 *
 * That asymmetry is the thing to remember: a fixture built for a change is not
 * automatically a fixture that can witness it. Six of those ten can; four
 * cannot, and the version assertion below is what covers the gap.
 *
 * ## 2026-11-20, v4.72
 *
 * `payload_v472` (per-ability `only_if`, and the `card_count` gate kind) CAN
 * witness its own change: the same asset digests
 * `abd82c68...` under 4.72 and `e38abba2...` under 4.71, measured by running
 * `cmd/parity --hash` over both extracted engine trees. So it catches a 4.71
 * AAR, and `cmd/mobilecheck` -- the same `mobile.Run` this test calls --
 * agreed with `cmd/parity` on the 4.72 digest before the AAR was installed.
 *
 * Eleven fixtures now, seven of which can witness the change they were built
 * for. The v4.70 and v4.71 asks were superseded unbuilt, so no fixture exists
 * for either; `payload_v472` is the only witness that the AAR is not 4.69.
 */
@RunWith(AndroidJUnit4::class)
class EngineParityTest {

    /** Keep in step with android/app/libs/engine.aar.json and ENGINE.md. */
    private val engineVersion = "4.72"

    private data class Fixture(val asset: String, val matches: Int, val digest: String)

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
        // It read "3.94" until 2026-11-11 -- seven weeks and nine engine
        // releases out of date, so this test had been failing for whoever ran
        // it and witnessing nothing for whoever did not. It is also the only
        // assertion here that catches a stale AAR on its own, which matters:
        // four of the ten fixtures above cannot tell 4.61 from 4.69.
        assertEquals(engineVersion, Mobile.version())
    }
}
