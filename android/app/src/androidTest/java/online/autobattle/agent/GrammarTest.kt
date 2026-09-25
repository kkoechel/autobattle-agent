package online.autobattle.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import online.autobattle.agent.chat.DeckRef
import online.autobattle.agent.chat.Grammar
import online.autobattle.agent.chat.Intent
import online.autobattle.agent.engine.Catalogue
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The grammar is what stands in for a language model, so its failure mode
 * matters more than its hit rate: a wrong parse spends thirty seconds of the
 * phone's CPU before the player learns they were misunderstood. These tests
 * are therefore as interested in what it REFUSES as in what it accepts.
 *
 * The vocabulary comes from the real catalogue rather than a fixture list,
 * because the tags an alias points at have to exist. Three of the first
 * aliases written here pointed at tags carried by 3 and 5 cards, which cannot
 * headline a 100-card deck, and one -- "sacrifice" -> "utilize" -- shadowed a
 * real tag on 25 cards and silently built a different archetype.
 */
@RunWith(AndroidJUnit4::class)
class GrammarTest {

    private fun asset(name: String) =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(name).bufferedReader().use { it.readText() }

    private val cat by lazy { Catalogue(JSONArray(asset("cards.json"))) }
    private val vocab by lazy { Grammar.vocabularyOf(cat) }
    private val names by lazy {
        val m = HashMap<String, Int>()
        for (id in cat.byId.keys) if (cat.isPlayable(id)) m[cat.name(id)] = id
        m
    }
    private val g by lazy { Grammar(vocab, names) }

    @Test fun vocabulary_is_drawn_from_playable_cards_only() {
        assertTrue("expected a real vocabulary, got ${vocab.size}", vocab.size in 20..120)
        assertTrue("poison should be offerable", "poison" in vocab)
        // Tags too thin to headline a deck must not be offered: a list built
        // from three cards is 97 staples reported back as the player's theme.
        assertFalse("bee is on 5 cards and cannot headline", "bee" in vocab)
        assertFalse("anthem is on 3 cards", "anthem" in vocab)
    }

    @Test fun build_requests_parse_to_a_tag() {
        for (phrase in listOf(
            "build me a poison deck",
            "make a poison list",
            "can you build a poison deck for me",
            "poison",
        )) {
            val i = g.parse(phrase)
            assertTrue("$phrase -> $i", i is Intent.Build)
            assertEquals(phrase, "poison", (i as Intent.Build).tag)
        }
    }

    @Test fun aliases_resolve_to_the_engines_own_word() {
        assertEquals("life", (g.parse("build a lifegain deck") as Intent.Build).tag)
        assertEquals("energy", (g.parse("make me a ramp deck") as Intent.Build).tag)
        assertEquals("mill", (g.parse("build a decking deck") as Intent.Build).tag)
        assertEquals("destroy", (g.parse("build a removal deck") as Intent.Build).tag)
    }

    @Test fun an_alias_never_shadows_a_real_tag() {
        // sacrifice is a tag on 25 cards. An early draft aliased it to
        // "utilize" and, because aliases were applied after the vocabulary,
        // overwrote the direct match.
        assertEquals("sacrifice", (g.parse("build a sacrifice deck") as Intent.Build).tag)
    }

    @Test fun longest_phrase_wins_over_its_prefix() {
        // "token" is noise; "token-copy" is the mechanic. Scanning shortest
        // first would resolve the phrase to the wrong one.
        val i = g.parse("build a token copy deck")
        assertEquals("token-copy", (i as Intent.Build).tag)
    }

    @Test fun plurals_resolve() {
        assertEquals("beast", (g.parse("build a beasts deck") as Intent.Build).tag)
    }

    @Test fun analyse_and_deck_references() {
        assertTrue(g.parse("analyse my deck") is Intent.Analyse)
        assertTrue(g.parse("how is my deck doing") is Intent.Analyse ||
            g.parse("how is my deck doing") is Intent.Help)
        val byId = g.parse("analyse deck 40806")
        assertEquals(DeckRef.Id(40806), (byId as Intent.Analyse).deck)
        assertEquals(DeckRef.Active, (g.parse("analyse my deck") as Intent.Analyse).deck)
    }

    @Test fun beat_takes_a_name_not_a_tag() {
        // "beat the poison deck" is a request about an OPPONENT, and reading
        // it as a request to build one would run the wrong 30-second job.
        val i = g.parse("beat the poison deck")
        assertTrue("got $i", i is Intent.Beat)
        assertEquals("poison", (i as Intent.Beat).who)
        assertEquals("hymn", (g.parse("how do I beat Hymn") as Intent.Beat).who)
    }

    @Test fun improve_is_recognised_even_though_it_is_not_built() {
        assertTrue(g.parse("improve my deck") is Intent.Improve)
        assertTrue(g.parse("tune my list") is Intent.Improve)
    }

    @Test fun nonsense_is_refused_rather_than_guessed() {
        val i = g.parse("what is the airspeed velocity of an unladen swallow")
        assertTrue("got $i", i is Intent.Unsure || i is Intent.Help)
    }

    @Test fun a_passing_mention_is_not_a_build_request() {
        // A bare theme is a build request; a sentence that merely contains the
        // word is not, or any question mentioning poison silently starts a
        // half-minute simulation.
        val i = g.parse("does my deck have a problem with poison decks in the field")
        assertFalse("got $i", i is Intent.Build)
    }

    @Test fun near_misses_are_offered_back() {
        val i = g.parse("build me a poisen deck")
        if (i is Intent.Unsure) {
            assertTrue("expected a suggestion, got ${i.nearestTags}",
                "poison" in i.nearestTags)
        } else {
            assertEquals("poison", (i as Intent.Build).tag)
        }
    }

    @Test fun a_named_card_resolves_to_that_card() {
        val id = names["Bomber Bee"]
        if (id != null) {
            val i = g.parse("what does Bomber Bee do in my deck")
            assertTrue("got $i", i is Intent.Card)
            assertEquals(id, (i as Intent.Card).cardId)
        }
    }

    @Test fun a_card_name_beats_a_tag_inside_it() {
        // "Venom Dart" contains no tag word, but a card whose name embeds one
        // must still resolve to the CARD: asking about one card and being
        // handed a 30-second theme build is the worst kind of near-miss,
        // because the answer looks plausible.
        val poisonCard = names.keys.firstOrNull { it.contains("Venom", true) }
        if (poisonCard != null) {
            val i = g.parse("should I run more $poisonCard")
            assertTrue("$poisonCard -> $i", i is Intent.Card)
        }
    }

    @Test fun an_explicit_build_still_wins_over_a_card_name() {
        // "build me a poison deck" must stay a build even if some card is
        // called something poison-ish; the verb is unambiguous.
        val i = g.parse("build me a poison deck")
        assertTrue("got $i", i is Intent.Build)
    }

    @Test fun help_is_reachable() {
        assertTrue(g.parse("help") is Intent.Help)
        assertTrue(g.parse("") is Intent.Help)
        assertTrue(g.parse("what can you do") is Intent.Help)
    }
}
