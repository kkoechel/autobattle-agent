package online.autobattle.agent.chat

import mobile.Mobile
import online.autobattle.agent.engine.Aggregator
import online.autobattle.agent.engine.Catalogue
import online.autobattle.agent.engine.Deck
import online.autobattle.agent.engine.Mutate
import online.autobattle.agent.engine.PayloadBuilder
import online.autobattle.agent.engine.Score
import org.json.JSONArray
import org.json.JSONObject

/**
 * The mechanics both searches share: cut a slot, fill it, run the engine.
 *
 * Shared rather than copied because this is precisely where the subtle bugs
 * live. The improver's control arm had to be split per cut depth once it
 * turned out that scoring a 1-of against a 5-slot control hands the 1-of the
 * cost of four blank cards; a second copy of that logic would have had to
 * learn the same lesson separately, or quietly not learn it.
 */

/**
 * Which of our cards to give up, worst-ranked in card_order first, until
 * `qty` copies have been found.
 *
 * `add` is left at 0 for the caller to fill in, so every candidate cuts the
 * same slots and the only thing varying between measurements is the card that
 * replaces them.
 */
internal fun cutsFor(cards: List<Int>, plan: JSONObject, qty: Int): List<Mutate.Swap> {
    val have = cards.groupingBy { it }.eachCount()
    val out = ArrayList<Mutate.Swap>()
    var need = qty
    for (c in Mutate.cutCandidates(cards, plan)) {
        if (need <= 0) break
        val avail = have[c] ?: 0
        if (avail <= 0) continue
        val take = minOf(need, avail)
        out.add(Mutate.Swap(c, 0, take))
        need -= take
    }
    return out
}

internal fun variantOf(
    cards: List<Int>, plan: JSONObject, cat: Catalogue, add: Int, qty: Int,
): Mutate.Result? =
    Mutate.apply(cards, plan, cutsFor(cards, plan, qty).map { it.copy(add = add) }, cat)

/** The field, minus our own deck, sampled with a stride so it spans the range. */
internal fun fieldOpponents(meta: JSONArray, excludeDeckId: Int, limit: Int): List<Deck> {
    val all = (0 until meta.length()).map { meta.getJSONObject(it) }
        .filter { it.optInt("deck_id", -1) != excludeDeckId }
    val stride = maxOf(1, all.size / limit)
    return all.filterIndexed { i, _ -> i % stride == 0 }.take(limit)
        .mapIndexed { i, d -> deckOf(d, "O$i") }
}

internal fun deckOf(d: JSONObject, slot: String): Deck {
    val a = d.getJSONArray("cards")
    return Deck(slot, (0 until a.length()).map { a.getInt(it) },
        d.optJSONObject("battle_plan"), d.optString("deck_name"), d.optInt("deck_id", -1))
}

internal fun simulate(
    cat: Catalogue, arms: List<Deck>, opps: List<Deck>, seeds: List<Int>, workers: Int,
): Map<String, Score> {
    val payload = PayloadBuilder(cat.raw).build(arms, opps, seeds)
    val env = JSONObject(Mobile.run(payload, workers.toLong()))
    return Aggregator.aggregate(
        env.getJSONArray("results").toString(), arms, seeds, opps.size)
}

/**
 * Cards a deck plays that we do not.
 *
 * A deck that beats us is a working answer to our deck, and its list is the
 * cheapest available description of why. Narrower than mining the top of the
 * field: it asks what THIS deck has, not what good decks have in general.
 */
internal fun counterCandidates(
    mine: Set<Int>, theirs: List<Int>, cat: Catalogue,
): List<Pair<Int, Int>> {
    val counts = theirs.groupingBy { it }.eachCount()
    return counts.entries
        .filter { it.key !in mine && cat.isPlayable(it.key) }
        .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
        .map { it.key to maxOf(1, it.value) }
}
