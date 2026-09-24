package online.autobattle.agent.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * Perturbs a WORKING deck with cards nobody plays.
 *
 * The other generators fail in opposite directions. Mining the field can only
 * propose a card someone already runs, so it cannot be unorthodox by
 * construction. Building from a theme produces coherent but naive shells --
 * they score 12-16 wins against a field of 60-win decks, because a consistent
 * theme is not a working deck.
 *
 * This combines them: a list that demonstrably works supplies the engine, the
 * curve and the play order; the unplayed pool supplies the surprise. It is the
 * generator that actually produces good decks (21.0W screens against 12-16W
 * from scratch).
 */
object Mutate {

    data class Swap(val cut: Int, val add: Int, val qty: Int)
    data class Result(val cards: List<Int>, val plan: JSONObject, val swaps: List<Swap>)

    /**
     * Applies an explicit list of substitutions.
     *
     * The caller chooses what to swap. The Python original shuffles a
     * candidate pool internally, which cannot be reproduced across languages
     * -- so the choice is lifted out, which also makes this deterministic and
     * directly testable rather than testable only by its invariants.
     */
    fun apply(cards: List<Int>, plan: JSONObject, swaps: List<Swap>, cat: Catalogue): Result? {
        val have = HashMap<Int, Int>()
        for (c in cards) have[c] = (have[c] ?: 0) + 1

        val order = ArrayList<Int>()
        plan.optJSONArray("card_order")?.let { a ->
            for (i in 0 until a.length()) order.add(a.getInt(i))
        }
        val rankOf = order.withIndex().associate { (i, c) -> c to i }

        val applied = ArrayList<Swap>()
        for (s in swaps) {
            val limit = cat.deckLimit(s.add)
            val qty = minOf(s.qty, have[s.cut] ?: 0, limit)
            if (qty <= 0 || s.cut == s.add) continue
            have[s.cut] = (have[s.cut] ?: 0) - qty
            if ((have[s.cut] ?: 0) <= 0) have.remove(s.cut)
            val now = (have[s.add] ?: 0) + qty
            if (now > limit) continue
            have[s.add] = now
            applied.add(Swap(s.cut, s.add, qty))
        }
        if (applied.isEmpty()) return null

        // The newcomer is placed one rank EARLIER than the card it replaced.
        // Cut candidates are chosen precisely because the deck deprioritised
        // them, so they sit at the back; inheriting that slot would bury the
        // replacement and test it under the same handicap that got the other
        // card cut.
        val newOrder = ArrayList(order.filter { have.containsKey(it) })
        for (s in applied) {
            if (newOrder.contains(s.add)) continue
            val pos = rankOf[s.cut] ?: newOrder.size
            newOrder.add(maxOf(0, minOf(pos - 1, newOrder.size)), s.add)
        }
        for (id in have.keys) if (!newOrder.contains(id)) newOrder.add(id)

        val out = ArrayList<Int>(cards.size)
        for ((id, n) in have.entries.sortedBy { it.key }) repeat(n) { out.add(id) }
        if (out.size != cards.size) return null

        val newPlan = JSONObject(plan.toString()).put("card_order", JSONArray(newOrder))
        return Result(out, newPlan, applied)
    }

    /**
     * Cut candidates: our cards, worst-ranked in card_order first.
     *
     * Ordering by play rank rather than copy count puts the cards the deck
     * itself already deprioritised at the front of the queue, which is a
     * better prior for "this slot is spare" than rarity or cost -- and it
     * leaves the engine that makes the deck work intact.
     */
    fun cutCandidates(cards: List<Int>, plan: JSONObject): List<Int> {
        val order = ArrayList<Int>()
        plan.optJSONArray("card_order")?.let { a ->
            for (i in 0 until a.length()) order.add(a.getInt(i))
        }
        val rankOf = order.withIndex().associate { (i, c) -> c to i }
        return cards.distinct().sortedByDescending { rankOf[it] ?: 9999 }
    }
}
