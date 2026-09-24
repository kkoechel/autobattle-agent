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
 * Proves the JNI boundary changes nothing.
 *
 * Everything before this point was verified on the Go side: the extracted
 * engine matches the CLI byte for byte, and the same digest comes out on x86
 * and on ARM. What none of that touches is the bridge itself — gomobile's
 * generated JNI layer, and the string marshalling in and out of it. A bridge
 * that quietly mangled UTF-8 or truncated a 3MB result would pass every Go
 * test and still ship broken numbers to players.
 *
 * So this asserts the digest computed inside the app equals the one computed
 * by `validate --local-payload` on the same payload. That constant is the
 * whole point of the test; if the engine changes, it must be re-derived from
 * the CLI rather than from whatever the app now returns.
 */
@RunWith(AndroidJUnit4::class)
class EngineParityTest {

    private val expectedDigest =
        "e3a0ac84ce1ea44ca7a3a3f5db3202a155ee35bb8bdd79950750748609f181a4"

    private fun payload(): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("payload.json").bufferedReader().use { it.readText() }

    @Test
    fun digestMatchesTheCli() {
        val env = JSONObject(Mobile.run(payload(), 4L))
        assertEquals(expectedDigest, env.getString("hash"))
    }

    @Test
    fun workerCountDoesNotChangeTheAnswer() {
        // Sharding is only safe because the per-game state moved off package
        // globals. If that regresses, results diverge by worker count — and
        // they diverge nondeterministically, which is exactly the kind of bug
        // that survives a single-threaded test suite.
        val one = JSONObject(Mobile.run(payload(), 1L))
        val many = JSONObject(Mobile.run(payload(), 8L))
        assertEquals(one.getString("hash"), many.getString("hash"))
        assertEquals(one.getJSONArray("results").length(), many.getJSONArray("results").length())
    }

    @Test
    fun resultsSurviveTheBridgeIntact() {
        val env = JSONObject(Mobile.run(payload(), 4L))
        val results = env.getJSONArray("results")
        assertEquals(1491, results.length())
        val first = results.getJSONObject(0)
        assertTrue(first.has("match_id"))
        assertTrue(first.has("reason"))
    }

    @Test
    fun engineVersionIsReported() {
        assertEquals("3.94", Mobile.version())
    }
}
