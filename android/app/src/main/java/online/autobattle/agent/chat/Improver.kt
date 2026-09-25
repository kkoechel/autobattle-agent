package online.autobattle.agent.chat

import mobile.Mobile
import online.autobattle.agent.engine.Aggregator
import online.autobattle.agent.engine.Archetype
import online.autobattle.agent.engine.Catalogue
import online.autobattle.agent.engine.Deck
import online.autobattle.agent.engine.Mutate
import online.autobattle.agent.engine.PayloadBuilder
import online.autobattle.agent.engine.Score
import online.autobattle.agent.engine.Stats
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Improve my deck": a proven shell plus the cards nobody plays.
 *
 * Deliberately NOT a rebuild. The player's list already works, and every
 * measurement we have says the shell is what makes it work -- decks built from
 * scratch screen at 12-16 of 24 against a field whose best manages 20. So this
 * changes a handful of cards and leaves the engine alone.
 *
 * The pool is cards the field does not run. Mining the field can only ever
 * propose a card someone already plays, so it cannot be unorthodox by
 * construction; the unplayed cards are the only place a genuinely new idea can
 * come from, and there are few enough of them to measure exhaustively.
 *
 * Two stages, because one is not enough and the reason is specific. A screen
 * cheap enough to cover the whole pool cannot resolve a one-win effect: at 41
 * seeds a real +1.48W effect came back NEGATIVE (-0.88, t=-0.54). Taking the
 * best of a screen and reporting its screen number is also the winner's curse
 * -- the maximum of many noisy draws is biased upward by construction, and a
 * "+3.8W, t=2.6" swap measured this way was really +1.48W. So the screen only
 * ORDERS candidates, and the number reported comes from a second, independent
 * measurement of the survivor.
 */
class Improver(
    private val cat: Catalogue,
    private val metaDecks: JSONArray,
    private val deckId: Int,
    private val deckName: String,
    private val deckCards: List<Int>,
    private val deckPlan: JSONObject,
) {

    data class Result(
        /**
         * The deck this was measured against -- NOT whichever deck happens to
         * be active. "improve deck 46660" targets one deck by id while the
         * account's active deck is another; writing the result to the active
         * one would save a measured improvement to a deck it was never
         * measured on.
         */
        val deckId: Int,
        val deckName: String,
        val add: Int,
        val addName: String,
        val addQty: Int,
        val removed: List<Pair<Int, Int>>,      // (cardId, copies cut)
        val cards: List<Int>,
        val plan: JSONObject,
        val screened: Int,
        val screenDelta: Double,                // versus the filler control arm
        val gain: Double,                       // validated, versus the real deck
        val t: Double,
        val candidate: Score,
        val incumbent: Score,
        val validateSeeds: Int,
        val opponents: Int,
        val elapsedMs: Long,
    ) {
        val accepted: Boolean get() = Stats.accept(gain, t)
    }

    /** Playable cards with rules text that no deck in the field runs. */
    fun unexplored(): List<Int> {
        val played = HashSet<Int>()
        for (i in 0 until metaDecks.length()) {
            val a = metaDecks.getJSONObject(i).getJSONArray("cards")
            for (j in 0 until a.length()) played.add(a.getInt(j))
        }
        val mine = deckCards.toSet()
        return cat.byId.keys
            .filter {
                cat.isPlayable(it) && it !in played && it !in mine &&
                    cat.rulesText(it).isNotEmpty() && cat.deckLimit(it) > 0
            }
            .sorted()
    }

    fun run(
        qty: Int = 5,
        screenOpponents: Int = 16,
        screenSeeds: Int = 5,
        validateSeeds: Int = 61,
        workers: Int = 4,
        onProgress: (String) -> Unit = {},
    ): Result? {
        val started = System.currentTimeMillis()
        val pool = unexplored()
        if (pool.isEmpty()) return null

        // ---- stage 1: order the whole pool ------------------------------
        onProgress("Measuring ${pool.size} cards the field never plays…")
        val sOpp = fieldOpponents(metaDecks, deckId, screenOpponents)
        val sSeeds = (0 until screenSeeds).map { 3_000_000 + it }

        val arms = ArrayList<Deck>()
        arms.add(Deck("MINE", deckCards, deckPlan, deckName, deckId))

        val byArm = HashMap<String, Triple<Int, Mutate.Result, Int>>()
        for (c in pool) {
            val q = minOf(qty, cat.deckLimit(c))
            val v = variantOf(deckCards, deckPlan, cat, c, q) ?: continue
            val slot = "A${byArm.size}"
            byArm[slot] = Triple(c, v, v.swaps.sumOf { it.qty })
            arms.add(Deck(slot, v.cards, v.plan, cat.name(c), null))
        }
        if (byArm.isEmpty()) return null

        // One control arm PER CUT DEPTH: the same cut, refilled with the
        // engine's designated blank.
        //
        // Measuring against the untouched deck folds the cost of the cut into
        // every card's score -- the first census run showed a cluster of cards
        // tied at "+0.5" that included a 99-energy card never cast, because
        // +0.5 was simply the going rate for a blank.
        //
        // But one control is not enough either, and that error is subtler. A
        // card with deck_limit 1 changes one slot; a card with deck_limit 15
        // changes five. Scoring both against a control that cut five slots
        // handicaps the control more than it handicaps the 1-of, so every
        // 1-of gains the cost of four blanks for free. The first run of this
        // duly returned a 1-of as its best card.
        val depths = byArm.values.map { it.third }.toSet()
        val controlSlot = HashMap<Int, String>()
        for (d in depths) {
            val cv = variantOf(deckCards, deckPlan, cat, Archetype.INFINITE_FILLER, d) ?: continue
            val slot = "C$d"
            controlSlot[d] = slot
            arms.add(Deck(slot, cv.cards, cv.plan, "control x$d", null))
        }

        val screen = simulate(cat, arms, sOpp, sSeeds, workers)
        val mine = screen["MINE"]!!
        fun baseFor(depth: Int): Double =
            controlSlot[depth]?.let { screen[it]?.wins } ?: mine.wins
        val ordered = byArm.entries
            .map { (slot, cv) -> cv.first to (screen[slot]!!.wins - baseFor(cv.third)) }
            .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })

        val best = ordered.first()
        if (best.second <= 0.0) {
            onProgress("Nothing in the unplayed pool beat a blank card.")
        }

        // ---- stage 2: measure the survivor independently -----------------
        // A fresh seed block, against the FULL field, versus the real deck.
        // Reusing the screen's seeds would report the number that won the
        // screen, which is the winner's curse restated.
        onProgress("Validating ${cat.name(best.first)} over $validateSeeds cohorts…")
        val vOpp = fieldOpponents(metaDecks, deckId, Int.MAX_VALUE)
        val vSeeds = (0 until validateSeeds).map { 4_000_000 + it }
        val v = variantOf(deckCards, deckPlan, cat, best.first, minOf(qty, cat.deckLimit(best.first))) ?: return null
        val vArms = listOf(
            Deck("MINE", deckCards, deckPlan, deckName, deckId),
            Deck("CAND", v.cards, v.plan, cat.name(best.first), null),
        )
        val vr = simulate(cat, vArms, vOpp, vSeeds, workers)
        val (gain, t) = Stats.pairedT(vr["CAND"]!!, vr["MINE"]!!)

        return Result(
            deckId = deckId,
            deckName = deckName,
            add = best.first, addName = cat.name(best.first),
            addQty = v.swaps.sumOf { it.qty },
            removed = v.swaps.map { it.cut to it.qty },
            cards = v.cards, plan = v.plan,
            screened = byArm.size, screenDelta = best.second,
            gain = gain, t = t,
            candidate = vr["CAND"]!!, incumbent = vr["MINE"]!!,
            validateSeeds = validateSeeds, opponents = vOpp.size,
            elapsedMs = System.currentTimeMillis() - started,
        )
    }
}
