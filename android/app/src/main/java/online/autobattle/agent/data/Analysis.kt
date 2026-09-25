package online.autobattle.agent.data

import android.content.Context
import mobile.Mobile
import online.autobattle.agent.api.ApiClient
import online.autobattle.agent.engine.Aggregator
import online.autobattle.agent.engine.Catalogue
import online.autobattle.agent.engine.Deck
import online.autobattle.agent.engine.PayloadBuilder
import online.autobattle.agent.engine.Score
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One opponent's record against the player's deck. */
data class Matchup(
    val name: String,
    val wins: Int,
    val losses: Int,
    val draws: Int,
) {
    val played get() = wins + losses + draws
    val winRate get() = if (played == 0) 0.0 else wins.toDouble() / played
    /**
     * Points dropped. A loss costs a full win, a draw half — so a deck we
     * merely stall against is counted for what it is: a matchup with a win
     * still sitting in it. Draws are not a curiosity here; a match is
     * best-of-2 with the play/draw swapped, so a 1-1 split IS the draw, and
     * they run ~27% of a Standard cohort.
     */
    val cost get() = if (played == 0) 0.0 else (losses + 0.5 * draws) / played
}

data class Analysis(
    val deckName: String,
    val score: Score,
    val matchups: List<Matchup>,
    val seeds: Int,
    val engineVersion: String,
    val elapsedMs: Long,
) {
    /** The matchups actually costing something, worst first. */
    val problems get() = matchups.filter { it.cost > 0.02 }.sortedByDescending { it.cost }
    val clean get() = matchups.count { it.winRate >= 0.98 }
}

/**
 * Fetches what the engine needs and scores the player's deck against the live
 * field, on the phone.
 *
 * The catalogue is ~870KB and changes a few times a day; the arena meta
 * changes every ten minutes. Both are cached to disk so opening the app does
 * not re-download the catalogue every time.
 */
class Analyst(private val ctx: Context, private val api: ApiClient) {

    private fun cached(name: String, maxAgeMs: Long, fetch: () -> String): String {
        val f = File(ctx.filesDir, name)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs) {
            return f.readText()
        }
        val fresh = fetch()
        f.writeText(fresh)
        return fresh
    }

    fun catalogue(): JSONArray =
        JSONArray(cached("cards.json", 6 * 60 * 60 * 1000L) { api.cards().toString() })

    fun meta(arena: String): JSONObject =
        JSONObject(cached("meta_$arena.json", 5 * 60 * 1000L) { api.meta(arena).toString() })

    /** The catalogue, indexed. Disk-cached, so cheap after the first call. */
    fun catalogueObj(): Catalogue = Catalogue(catalogue())

    /** The arena field as the generator wants it. */
    fun metaDecks(arena: String = "pure"): JSONArray = meta(arena).getJSONArray("decks")

    /** One of the player's decks, or null if it cannot be read. */
    fun deckFor(deckId: Int): JSONObject? =
        runCatching { api.deck(deckId) }.getOrNull()

    private fun expand(deck: JSONObject): List<Int> = expandCards(deck)

    companion object {
        /** GET /decks returns (card_id, quantity) pairs; the engine wants a flat list. */
        fun expandCards(deck: JSONObject): List<Int> {
            val out = ArrayList<Int>()
            val cards = deck.optJSONArray("cards") ?: return out
            for (i in 0 until cards.length()) {
                val c = cards.getJSONObject(i)
                repeat(c.getInt("quantity")) { out.add(c.getInt("card_id")) }
            }
            return out
        }
    }

    /**
     * @param seeds each seed is one whole simulated cohort. 21 is enough to
     *   rank matchups for display; it is NOT enough to accept a change — that
     *   needs the paired test and far more seeds, because a single cohort has
     *   a ~2-6 win standard deviation depending on the deck.
     */
    fun analyse(deckId: Int, arena: String = "pure", seeds: Int = 21, workers: Int = 4): Analysis {
        val started = System.currentTimeMillis()
        val cat = catalogue()
        val metaDecks = meta(arena).getJSONArray("decks")
        val deck = api.deck(deckId)

        val candidate = Deck(
            slotId = "C0",
            cards = expand(deck),
            battlePlan = deck.optJSONObject("battle_plan"),
            name = deck.optString("name"),
            deckId = deckId,
        )
        val opponents = ArrayList<Deck>()
        val names = HashMap<String, String>()
        for (i in 0 until metaDecks.length()) {
            val d = metaDecks.getJSONObject(i)
            if (d.optInt("deck_id", -1) == deckId) continue
            val cards = d.getJSONArray("cards")
            val slot = "O${opponents.size}"
            names[slot] = d.optString("deck_name")
            opponents.add(
                Deck(
                    slotId = slot,
                    cards = (0 until cards.length()).map { cards.getInt(it) },
                    battlePlan = d.optJSONObject("battle_plan"),
                    name = names[slot]!!,
                    deckId = d.optInt("deck_id", -1),
                )
            )
        }

        val seedList = (0 until seeds).map { 1_000_000 + it }
        val payload = PayloadBuilder(cat).build(listOf(candidate), opponents, seedList)
        val env = JSONObject(Mobile.run(payload, workers.toLong()))
        val score = Aggregator.aggregate(
            env.getJSONArray("results").toString(), listOf(candidate), seedList, opponents.size
        )["C0"]!!

        val matchups = score.perOpponent.map { (slot, wld) ->
            Matchup(names[slot] ?: slot, wld.first, wld.second, wld.third)
        }
        return Analysis(
            deckName = candidate.name,
            score = score,
            matchups = matchups,
            seeds = seeds,
            engineVersion = Mobile.version(),
            elapsedMs = System.currentTimeMillis() - started,
        )
    }
}
