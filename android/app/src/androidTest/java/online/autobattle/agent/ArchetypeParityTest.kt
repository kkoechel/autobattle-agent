package online.autobattle.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import online.autobattle.agent.engine.Archetype
import online.autobattle.agent.engine.Catalogue
import online.autobattle.agent.engine.Mutate
import online.autobattle.agent.engine.Names
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The generator is checked two different ways, because it has two different
 * kinds of code in it.
 *
 * `build()` is deterministic, so it is held to exact equality with the Python
 * agent: same staples, same picks, same quantities, same play order. Anything
 * less would let the two drift while both look reasonable.
 *
 * `mutate()` is not comparable that way — the Python shuffles a candidate pool
 * with its own Mersenne Twister, which Kotlin cannot reproduce. Rather than
 * pretend otherwise, the choice of what to swap is lifted out of the Kotlin
 * version, leaving a deterministic transform that IS directly testable. What
 * remains is verified by invariant: deck size, deck_limit, play-order
 * placement.
 */
@RunWith(AndroidJUnit4::class)
class ArchetypeParityTest {

    private fun asset(name: String) =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(name).bufferedReader().use { it.readText() }

    private val cat by lazy { Catalogue(JSONArray(asset("cards.json"))) }
    private val meta by lazy { JSONObject(asset("meta_pure.json")).getJSONArray("decks") }
    private val ref by lazy { JSONObject(asset("archetype_ref.json")) }

    @Test
    fun staplesMatchPython() {
        val expected = ref.getJSONArray("staples").let { a -> (0 until a.length()).map { a.getInt(it) } }
        assertEquals(expected, Archetype.staples(meta, cat, 10))
    }

    @Test
    fun affinityMatchesPython() {
        val samples = ref.getJSONObject("affinity_samples")
        val theme = Archetype.themeOf(26, cat).toSet()
        for (k in samples.keys()) {
            assertEquals("affinity($k)", samples.getDouble(k),
                Archetype.affinity(k.toInt(), theme, cat), 1e-12)
        }
    }

    @Test
    fun builtArchetypesMatchPythonExactly() {
        val archs = ref.getJSONArray("archetypes")
        for (i in 0 until archs.length()) {
            val e = archs.getJSONObject(i)
            val seed = e.getInt("seed")
            val got = Archetype.build(seed, cat, meta)
            assertNotNull("seed $seed produced no archetype", got)

            assertEquals("theme for seed $seed",
                e.getJSONArray("theme").let { a -> (0 until a.length()).map { a.getString(it) } },
                got!!.theme)

            val expectedCounts = e.getJSONObject("counts")
            val gotCounts = got.cards.groupingBy { it }.eachCount()
            assertEquals("distinct cards for seed $seed", expectedCounts.length(), gotCounts.size)
            for (k in expectedCounts.keys()) {
                assertEquals("seed $seed card $k", expectedCounts.getInt(k), gotCounts[k.toInt()])
            }
            assertEquals("deck size for seed $seed", e.getInt("total"), got.cards.size)

            // Play order is not incidental: card_order is the single most
            // valuable plan field, so the ported generator must produce the
            // same ranking, not merely the same cards.
            assertEquals("card_order for seed $seed",
                e.getJSONArray("card_order").let { a -> (0 until a.length()).map { a.getInt(it) } },
                got.picks.map { it.first })

            assertEquals("name for seed $seed",
                e.getString("name"), Names.forTheme(got.theme, got.seedName))
        }
    }

    @Test
    fun generatedDecksRespectDeckLimits() {
        val archs = ref.getJSONArray("archetypes")
        for (i in 0 until archs.length()) {
            val got = Archetype.build(archs.getJSONObject(i).getInt("seed"), cat, meta)!!
            for ((id, n) in got.cards.groupingBy { it }.eachCount()) {
                assertTrue("card $id over its limit", n <= cat.deckLimit(id))
                assertTrue("card $id not playable", cat.isPlayable(id))
            }
        }
    }

    @Test
    fun retiredCardsCannotSeedAnArchetype() {
        val retired = cat.byId.keys.firstOrNull {
            cat.byId[it]!!.optInt("is_retired", 0) == 1 ||
                cat.byId[it]!!.optBoolean("is_retired", false)
        }
        assertNotNull("fixture should contain a retired card", retired)
        assertNull(Archetype.build(retired!!, cat, meta))
    }

    @Test
    fun mutationHoldsItsInvariants() {
        val base = Archetype.build(789, cat, meta)!!   // the bee archetype
        val cuts = Mutate.cutCandidates(base.cards, base.plan)
        val played = base.cards.toSet()
        val fresh = cat.byId.keys.filter { cat.isPlayable(it) && it !in played }.sorted().take(2)

        val swaps = listOf(
            Mutate.Swap(cuts[0], fresh[0], 3),
            Mutate.Swap(cuts[1], fresh[1], 2),
        )
        val out = Mutate.apply(base.cards, base.plan, swaps, cat)!!

        assertEquals("deck must stay at 100", base.cards.size, out.cards.size)
        for ((id, n) in out.cards.groupingBy { it }.eachCount()) {
            assertTrue("card $id over its limit", n <= cat.deckLimit(id))
        }
        val order = out.plan.getJSONArray("card_order").let { a ->
            (0 until a.length()).map { a.getInt(it) }
        }
        assertTrue("every deck card must be ranked",
            out.cards.toSet().all { it in order })

        // The newcomer must land AHEAD of where the card it replaced sat, or
        // it is being tested under the handicap that got the other card cut.
        val baseOrder = base.plan.getJSONArray("card_order").let { a ->
            (0 until a.length()).map { a.getInt(it) }
        }
        for (s in out.swaps) {
            val oldRank = baseOrder.indexOf(s.cut)
            if (oldRank > 0) {
                assertTrue("added card ${s.add} should rank ahead of cut ${s.cut}",
                    order.indexOf(s.add) <= oldRank)
            }
        }
    }

    @Test
    fun mutationRefusesAnIllegalSwap() {
        val base = Archetype.build(789, cat, meta)!!
        val cuts = Mutate.cutCandidates(base.cards, base.plan)
        // Swapping a card in for itself, and swapping in something already at
        // its limit, must both be no-ops rather than silent corruption.
        assertNull(Mutate.apply(base.cards, base.plan,
            listOf(Mutate.Swap(cuts[0], cuts[0], 3)), cat))
    }
}
