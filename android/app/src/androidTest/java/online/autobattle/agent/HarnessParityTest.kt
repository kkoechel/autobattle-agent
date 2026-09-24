package online.autobattle.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import mobile.Mobile
import online.autobattle.agent.engine.Aggregator
import online.autobattle.agent.engine.Deck
import online.autobattle.agent.engine.PayloadBuilder
import online.autobattle.agent.engine.Stats
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Kotlin harness must reproduce the Python agent's numbers exactly.
 *
 * Both drive the same engine on the same seeds, so "close enough" is the wrong
 * bar — any difference means the payload was built differently or the results
 * were folded differently, and either would make every recommendation the app
 * shows subtly untrue. The reference in assets/reference.json was produced by
 * abagent's own harness against the same cached catalogue and meta.
 */
@RunWith(AndroidJUnit4::class)
class HarnessParityTest {

    private fun asset(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(name).bufferedReader().use { it.readText() }

    private fun flatten(cards: JSONArray): List<Int> {
        val out = ArrayList<Int>()
        for (i in 0 until cards.length()) out.add(cards.getInt(i))
        return out
    }

    /**
     * Golden Hive vs the cached field, with the candidate PINNED as its own
     * fixture rather than read out of the meta.
     *
     * The first version took the candidate from the meta snapshot, which made
     * the test depend on whichever deck happened to be in it -- and the live
     * deck had been retuned since (Ancient Power Station and Surge of Will in,
     * Hymn of the Friends and Straw Princelings out, different plan). Python
     * scored the live deck and Kotlin scored the snapshot, and a 0.38-win
     * difference looked exactly like a port bug. Pinning the input is what
     * makes a parity test about the port instead of about the fixtures.
     */
    private fun setup(): Triple<PayloadBuilder, Deck, List<Deck>> {
        val catalogue = JSONArray(asset("cards.json"))
        val meta = JSONObject(asset("meta_pure.json")).getJSONArray("decks")
        val cand = JSONObject(asset("candidate.json"))

        val candidate = Deck(
            slotId = "C0",
            cards = flatten(cand.getJSONArray("cards")),
            battlePlan = cand.optJSONObject("battle_plan"),
            name = cand.optString("name"),
            deckId = cand.getInt("deck_id"),
        )
        val opponents = ArrayList<Deck>()
        for (i in 0 until meta.length()) {
            val d = meta.getJSONObject(i)
            if (d.optInt("deck_id", -1) == candidate.deckId) continue
            opponents.add(
                Deck(
                    slotId = "O${opponents.size}",
                    cards = flatten(d.getJSONArray("cards")),
                    battlePlan = d.optJSONObject("battle_plan"),
                    name = d.optString("deck_name"),
                    deckId = d.optInt("deck_id", -1),
                )
            )
        }
        return Triple(PayloadBuilder(catalogue), candidate, opponents)
    }

    @Test
    fun reproducesThePythonHarnessExactly() {
        val ref = JSONObject(asset("reference.json"))
        val seeds = ref.getJSONArray("seeds").let { a -> (0 until a.length()).map { a.getInt(it) } }
        val (builder, candidate, opponents) = setup()

        assertEquals("opponent count must match the reference",
            ref.getInt("n_opponents"), opponents.size)

        val payload = builder.build(listOf(candidate), opponents, seeds)
        val env = JSONObject(Mobile.run(payload, 4L))
        val scores = Aggregator.aggregate(
            env.getJSONArray("results").toString(), listOf(candidate), seeds, opponents.size)
        val s = scores["C0"]!!

        // Same engine, same seeds, same payload -- these are integer counts
        // averaged, so they are exact, not approximate.
        assertEquals(ref.getDouble("wins"), s.wins, 1e-9)
        assertEquals(ref.getDouble("losses"), s.losses, 1e-9)
        assertEquals(ref.getDouble("draws"), s.draws, 1e-9)
        assertEquals(ref.getDouble("sd"), s.winsSd, 1e-9)
    }

    @Test
    fun perSeedWinsMatchTheReference() {
        // The aggregate could match while individual seeds diverge and cancel.
        // The paired t-test reads per-seed differences, so this is the number
        // that actually has to be right.
        val ref = JSONObject(asset("reference.json"))
        val refBySeed = ref.getJSONObject("wins_by_seed")
        val seeds = ref.getJSONArray("seeds").let { a -> (0 until a.length()).map { a.getInt(it) } }
        val (builder, candidate, opponents) = setup()

        val env = JSONObject(Mobile.run(builder.build(listOf(candidate), opponents, seeds), 4L))
        val s = Aggregator.aggregate(
            env.getJSONArray("results").toString(), listOf(candidate), seeds, opponents.size)["C0"]!!

        for (seed in seeds) {
            assertEquals("seed $seed", refBySeed.getInt(seed.toString()), s.winsBySeed[seed])
        }
    }

    @Test
    fun pairedTResolvesAKnownBadChange() {
        // play_priority=cheapest is known-negative on this deck, but only
        // modestly so, and 61 seeds are needed to resolve it rather than 21.
        //
        // That is the point worth keeping. The same change cost ~17 wins on an
        // untuned pile of commons and costs about one here, because a coherent
        // curve is already close to what `cheapest` would play. A test written
        // against the old figure failed at t=-1.78 -- not because the harness
        // was wrong but because the assumed effect size was. Sizing a test to
        // the effect is the same discipline the search itself runs on.
        val (builder, candidate, opponents) = setup()
        val seeds = (0 until 61).map { 707000 + it }
        val worse = candidate.copy(
            slotId = "C1",
            battlePlan = JSONObject(candidate.battlePlan.toString()).put("play_priority", "cheapest"),
        )
        val env = JSONObject(Mobile.run(builder.build(listOf(candidate, worse), opponents, seeds), 4L))
        val scores = Aggregator.aggregate(
            env.getJSONArray("results").toString(), listOf(candidate, worse), seeds, opponents.size)

        val (gain, t) = Stats.pairedT(scores["C1"]!!, scores["C0"]!!)
        assertTrue("worse deck should score below the incumbent, got $gain", gain < 0)
        assertTrue("t should be clearly negative, got $t", t < -2.0)
        assertTrue("accept() must reject it", !Stats.accept(gain, t))
    }
}
