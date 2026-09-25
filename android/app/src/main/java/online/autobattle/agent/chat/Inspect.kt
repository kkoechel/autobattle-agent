package online.autobattle.agent.chat

import online.autobattle.agent.engine.Archetype
import online.autobattle.agent.engine.Catalogue
import online.autobattle.agent.engine.Deck
import online.autobattle.agent.engine.Score
import online.autobattle.agent.engine.Stats
import org.json.JSONArray
import org.json.JSONObject

/**
 * "What does Bomber Bee do in my deck?"
 *
 * The searches answer what the app thinks is worth changing. This answers a
 * question the player brought: it measures ONE named card, in whichever
 * directions make sense for it, and reports every direction rather than the
 * best one.
 *
 * All variants are measured in a single run against the full field on one
 * shared seed block, so they are paired with each other as well as with the
 * current deck. That pairing is the whole reason these numbers can be
 * compared: the seeds are the dominant source of variance, and two arms that
 * did not share them are two independent estimates rather than a comparison.
 *
 * Unlike the census, these figures are validated -- full field, 61 cohorts --
 * because the player is asking about one card and there is no winner's curse
 * to correct for. Nothing was selected for being the best of many.
 */
class Inspect(
    private val cat: Catalogue,
    private val metaDecks: JSONArray,
    private val deckId: Int,
    private val deckName: String,
    private val deckCards: List<Int>,
    private val deckPlan: JSONObject,
) {

    data class Variant(
        val label: String,
        val cards: List<Int>,
        val plan: JSONObject,
        val gain: Double,
        val t: Double,
        val wins: Double,
    ) {
        val recommended: Boolean get() = Stats.accept(gain, t)

        /**
         * A significant LOSS, by the same bar mirrored.
         *
         * This is the most informative answer a cut can give and the first
         * version called it noise: Stats.accept only tests one direction, so
         * "cut all 4 Bomber Bee: -0.8 wins, t=-2.1" was reported as
         * indistinguishable when |t| clears 2.0 and the card is demonstrably
         * earning its slot. For a cut variant that is the whole point of
         * asking.
         */
        val harmful: Boolean get() = gain <= -Stats.MIN_GAIN && t <= -Stats.MIN_T
    }

    data class Result(
        val deckId: Int,
        val deckName: String,
        val card: Int,
        val cardName: String,
        val rulesText: String,
        val copiesNow: Int,
        val deckLimit: Int,
        val variants: List<Variant>,
        val incumbent: Score,
        val seeds: Int,
        val opponents: Int,
        val elapsedMs: Long,
    )

    /**
     * Cut every copy and refill with the engine's designated blank.
     *
     * Against a blank, not against nothing: the deck must stay at 100 cards,
     * and "what is this card worth" means "what is it worth over an empty
     * slot". Measuring it against whatever else we might have added instead
     * answers a different and much harder question.
     */
    private fun withoutCard(card: Int): Pair<List<Int>, JSONObject>? {
        val have = deckCards.groupingBy { it }.eachCount()
        val n = have[card] ?: return null
        val swaps = listOf(online.autobattle.agent.engine.Mutate.Swap(
            card, Archetype.INFINITE_FILLER, n))
        val m = online.autobattle.agent.engine.Mutate.apply(deckCards, deckPlan, swaps, cat)
            ?: return null
        return m.cards to m.plan
    }

    fun run(
        card: Int,
        validateSeeds: Int = 61,
        workers: Int = 4,
        onProgress: (String) -> Unit = {},
    ): Result? {
        val started = System.currentTimeMillis()
        if (cat.byId[card] == null) return null
        val name = cat.name(card)
        val limit = cat.deckLimit(card)
        val now = deckCards.count { it == card }

        onProgress("Measuring $name in your deck over $validateSeeds cohorts…")

        val arms = ArrayList<Deck>()
        arms.add(Deck("MINE", deckCards, deckPlan, deckName, deckId))
        val labels = LinkedHashMap<String, Pair<String, Pair<List<Int>, JSONObject>>>()

        if (now > 0) {
            withoutCard(card)?.let {
                labels["V0"] = "without it (cut all $now)" to it
            }
            if (now < limit) {
                val extra = minOf(limit - now, 5)
                variantOf(deckCards, deckPlan, cat, card, extra)?.let {
                    labels["V1"] = "$extra more (${now + extra} total)" to (it.cards to it.plan)
                }
            }
        } else {
            for (q in listOf(minOf(5, limit), limit).distinct().filter { it > 0 }) {
                variantOf(deckCards, deckPlan, cat, card, q)?.let {
                    val placed = it.swaps.sumOf { s -> s.qty }
                    if (placed > 0) labels["V$q"] = "add $placed" to (it.cards to it.plan)
                }
            }
        }
        if (labels.isEmpty()) return null

        for ((slot, v) in labels) arms.add(Deck(slot, v.second.first, v.second.second, v.first, null))

        val field = fieldOpponents(metaDecks, deckId, Int.MAX_VALUE)
        val seeds = (0 until validateSeeds).map { 8_000_000 + it }
        val res = simulate(cat, arms, field, seeds, workers)
        val mine = res["MINE"]!!

        val variants = labels.map { (slot, v) ->
            val sc = res[slot]!!
            val (gain, t) = Stats.pairedT(sc, mine)
            Variant(v.first, v.second.first, v.second.second, gain, t, sc.wins)
        }.sortedByDescending { it.gain }

        return Result(
            deckId = deckId, deckName = deckName,
            card = card, cardName = name,
            rulesText = cat.rulesText(card),
            copiesNow = now, deckLimit = limit,
            variants = variants, incumbent = mine,
            seeds = validateSeeds, opponents = field.size,
            elapsedMs = System.currentTimeMillis() - started,
        )
    }
}
