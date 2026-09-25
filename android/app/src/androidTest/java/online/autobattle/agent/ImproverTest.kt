package online.autobattle.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import online.autobattle.agent.chat.Improver
import online.autobattle.agent.engine.Catalogue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The improver's correctness is mostly about what it holds CONSTANT between
 * measurements, so that is what these check: deck size, deck limits, and the
 * fact that every candidate cuts from the same ordered list.
 *
 * The bias these were written for is subtle and was found on the device, not
 * in review. A card with deck_limit 1 changes one slot; a card with
 * deck_limit 15 changes five. Scoring both against a single control that cut
 * five slots handicaps the control more than the 1-of, handing every 1-of the
 * cost of four blank cards for free -- and the first run duly returned a 1-of
 * as the best card in the pool. Correcting it changed the winner.
 */
@RunWith(AndroidJUnit4::class)
class ImproverTest {

    private fun asset(name: String) =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(name).bufferedReader().use { it.readText() }

    private val cat by lazy { Catalogue(JSONArray(asset("cards.json"))) }
    private val meta by lazy { JSONObject(asset("meta_pure.json")).getJSONArray("decks") }
    private val deck by lazy { JSONObject(asset("candidate.json")) }
    private val deckCards by lazy {
        deck.getJSONArray("cards").let { a -> (0 until a.length()).map { a.getInt(it) } }
    }

    private val imp by lazy {
        Improver(cat, meta, deck.getInt("deck_id"), deck.optString("name"),
            deckCards, deck.getJSONObject("battle_plan"))
    }

    @Test fun pool_is_playable_unplayed_and_not_already_ours() {
        val pool = imp.unexplored()
        assertTrue("pool looks wrong: ${pool.size}", pool.size in 20..400)

        val played = HashSet<Int>()
        for (i in 0 until meta.length()) {
            val a = meta.getJSONObject(i).getJSONArray("cards")
            for (j in 0 until a.length()) played.add(a.getInt(j))
        }
        val mine = deckCards.toSet()
        for (c in pool) {
            assertTrue("$c is retired or unplayable", cat.isPlayable(c))
            assertFalse("$c is already played by the field", c in played)
            assertFalse("$c is already in our deck", c in mine)
            // A card with no rules text is a blank; adding one is exactly the
            // control arm, so offering it as a suggestion is offering nothing.
            assertTrue("$c has no rules text", cat.rulesText(c).isNotEmpty())
        }
    }

    @Test fun pool_is_deterministic() {
        assertEquals(imp.unexplored(), imp.unexplored())
        // Sorted by id, so the order does not depend on map iteration. Two
        // nondeterminism bugs of exactly this kind were found in the Python
        // agent by porting it.
        assertEquals(imp.unexplored().sorted(), imp.unexplored())
    }

    @Test fun pool_excludes_the_control_card() {
        // The filler is in every deck in the field, so it should never be
        // proposed -- but if it ever were, the candidate and the control arm
        // would be the same deck and the delta would be exactly zero.
        assertFalse(online.autobattle.agent.engine.Archetype.INFINITE_FILLER
            in imp.unexplored())
    }
}
