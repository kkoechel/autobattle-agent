package online.autobattle.agent.engine

/**
 * Put a kept deck back together after a ban.
 *
 * A saved deck is a SNAPSHOT and never changes -- that is what makes it
 * worth keeping. When the format moves under it, repair produces a NEW list
 * from it rather than editing the one on the shelf, so the original is still
 * there to come back to if the card is released again.
 *
 * The replacement is chosen by what the deck is ABOUT, not by what is good
 * in general. A banned card is pulled, and the hole is filled first by
 * leaning harder on the cards the deck already plays, then by cards that
 * share its tags -- which is the same thing the ban measurement does when it
 * works out what a card was worth, so repair and the ban that caused it
 * reason the same way.
 */
object Repair {

    data class Result(
        val cards: List<Pair<Int, Int>>,
        /** Card ids that had to go. */
        val removed: List<Int>,
        /** How many slots were refilled, and with what. */
        val added: List<Pair<Int, Int>>,
    )

    /**
     * @param entries the saved list, as card id to quantity
     * @return null when the deck cannot be made legal at all
     */
    fun repair(
        entries: List<Pair<Int, Int>>,
        banned: Set<Int>,
        cat: Catalogue,
        size: Int = 100,
    ): Result? {
        val removed = entries.filter { it.first in banned }.map { it.first }
        if (removed.isEmpty()) return Result(entries, emptyList(), emptyList())

        val kept = entries.filter { it.first !in banned && cat.isPlayable(it.first) }
            .toMutableList()
        var missing = size - kept.sumOf { it.second }
        if (missing <= 0) return Result(kept, removed, emptyList())

        // What the deck is about: the tags its remaining cards carry, weighted
        // by how many copies it runs. Taken from the deck rather than from the
        // banned card, so repairing does not chase the thing that was removed.
        val weight = HashMap<String, Int>()
        for ((id, qty) in kept) for (t in cat.tags(id)) weight[t] = (weight[t] ?: 0) + qty
        val theme = weight.entries.sortedByDescending { it.value }.take(6)
            .map { it.key }.toSet()

        val added = ArrayList<Pair<Int, Int>>()

        // 1. Lean on what is already there. A deck that loses a card usually
        //    wants more of its own best cards, not a stranger.
        kept.sortByDescending { Archetype.affinity(it.first, theme, cat) }
        for (i in kept.indices) {
            if (missing <= 0) break
            val (id, qty) = kept[i]
            val room = (cat.deckLimit(id) - qty).coerceAtLeast(0)
            val take = minOf(room, missing)
            if (take > 0) {
                kept[i] = id to (qty + take)
                added.add(id to take)
                missing -= take
            }
        }

        // 2. Then the closest cards it does not yet play.
        if (missing > 0) {
            val have = kept.map { it.first }.toSet()
            val pool = cat.byId.keys
                .filter { it !in have && it !in banned && cat.isPlayable(it) &&
                          cat.rulesText(it).isNotEmpty() }
                .sortedWith(
                    compareByDescending<Int> { Archetype.affinity(it, theme, cat) }
                        .thenBy { cat.cost(it) }
                        .thenBy { it })
            for (id in pool) {
                if (missing <= 0) break
                val take = minOf(cat.deckLimit(id), missing)
                if (take <= 0) continue
                kept.add(id to take)
                added.add(id to take)
                missing -= take
            }
        }

        // A deck that cannot be filled legally is not a deck. Better to say so
        // than to submit 97 cards and have the server reject it.
        if (missing != 0) return null
        return Result(kept.sortedByDescending { it.second }, removed, added)
    }
}
