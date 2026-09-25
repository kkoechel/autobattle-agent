package online.autobattle.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import online.autobattle.agent.chat.Counter
import online.autobattle.agent.engine.Catalogue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Name resolution is the fragile half of "beat X": the search itself is
 * measured, but picking the WRONG deck produces a confident, well-measured
 * answer to a question nobody asked, and there is nothing in the numbers to
 * reveal it. So these lean on resolution, and on the one invariant that stops
 * the verdict being self-serving: the target must remain inside the field the
 * candidate is judged against.
 */
@RunWith(AndroidJUnit4::class)
class CounterTest {

    private fun asset(name: String) =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(name).bufferedReader().use { it.readText() }

    private val cat by lazy { Catalogue(JSONArray(asset("cards.json"))) }
    private val meta by lazy { JSONObject(asset("meta_pure.json")).getJSONArray("decks") }
    private val deck by lazy { JSONObject(asset("candidate.json")) }
    private val cards by lazy {
        deck.getJSONArray("cards").let { a -> (0 until a.length()).map { a.getInt(it) } }
    }
    private val c by lazy {
        Counter(cat, meta, deck.getInt("deck_id"), deck.optString("name"),
            cards, deck.getJSONObject("battle_plan"))
    }

    private fun firstOpponentName(): String {
        for (i in 0 until meta.length()) {
            val d = meta.getJSONObject(i)
            if (d.optInt("deck_id", -1) == deck.getInt("deck_id")) continue
            val n = d.optString("deck_name")
            if (n.isNotBlank()) return n
        }
        error("no opponents in fixture")
    }

    @Test fun exact_name_resolves() {
        val n = firstOpponentName()
        val t = c.find(n)
        assertNotNull("could not resolve '$n'", t)
        assertEquals(n, t!!.name)
    }

    @Test fun case_and_whitespace_do_not_matter() {
        val n = firstOpponentName()
        assertEquals(n, c.find("  " + n.uppercase() + "  ")?.name)
    }

    @Test fun a_prefix_resolves() {
        val n = firstOpponentName()
        if (n.length < 4) return
        assertEquals(n, c.find(n.substring(0, 4))?.name)
    }

    @Test fun our_own_deck_is_never_a_target() {
        // "beat Golden Hive" while playing Golden Hive would otherwise search
        // for a card that beats the deck it is being added to.
        val t = c.find(deck.optString("name"))
        assertTrue("resolved to ourselves",
            t == null || t.deckId != deck.getInt("deck_id"))
    }

    @Test fun nonsense_resolves_to_nothing() {
        assertNull(c.find("zzzz not a deck zzzz"))
        assertNull(c.find(""))
    }

    @Test fun suggestions_are_real_opponents() {
        val names = c.names()
        assertTrue("no suggestions", names.isNotEmpty())
        for (n in names) assertNotNull("suggested '$n' does not resolve", c.find(n))
        assertFalse("suggested our own deck", deck.optString("name") in names)
    }
}
