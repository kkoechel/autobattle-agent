package online.autobattle.agent.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a deck FOR a card, rather than around an existing one.
 *
 * Measuring a card by substituting it into a tuned deck answers how well it
 * FITS that deck, which is a different question -- a card needs its enablers
 * before it can show what it does. A census of all 115 unplayed cards against
 * a tuned poison deck found nothing, every time, and the best of them scored
 * no better than a blank.
 */
object Archetype {

    /** Tags describing provenance or timing rather than purpose. */
    private val NOISE_TAGS = setOf(
        "common", "uncommon", "rare", "mythic", "legendary", "infinite", "token",
        "trigger-start-of-turn", "trigger-enter", "trigger-death", "trigger-end-of-turn",
        "creature", "relic", "spell", "structure", "enchantment",
    )

    const val INFINITE_FILLER = 57   // Straw Man-at-Arms

    data class Built(
        val seed: Int,
        val seedName: String,
        val theme: List<String>,
        val picks: List<Pair<Int, Int>>,   // (cardId, quantity), in play order
        val cards: List<Int>,
        val plan: JSONObject,
    )

    fun themeOf(seed: Int, cat: Catalogue): List<String> =
        cat.tags(seed).filterNot { it in NOISE_TAGS }.sorted()

    /**
     * How much of the theme a card shares, normalised by its own breadth.
     *
     * Without the normalisation a card carrying twenty tags outranks one
     * carrying exactly the two that define the archetype, purely by covering
     * more ground.
     */
    fun affinity(id: Int, theme: Set<String>, cat: Catalogue): Double {
        val tags = cat.tags(id).filterNot { it in NOISE_TAGS }.toSet()
        if (tags.isEmpty() || theme.isEmpty()) return 0.0
        return tags.intersect(theme).size / Math.sqrt((tags + theme).size.toDouble())
    }

    /**
     * Cards the field runs across archetypes, most widespread first.
     *
     * Cards with no rules text are excluded even though they are among the
     * most widespread. Straw Man-at-Arms is in 33 of 70 decks -- second only
     * to Ancient Power Station -- not because it does anything but because it
     * is what everyone pads to 100 with. Counting deck presence alone mistook
     * that popularity for utility and put 15 blank cards into every generated
     * deck.
     */
    fun staples(meta: JSONArray, cat: Catalogue, top: Int = 10): List<Int> {
        val seen = HashMap<Int, Int>()
        for (i in 0 until meta.length()) {
            val cards = meta.getJSONObject(i).getJSONArray("cards")
            val distinct = HashSet<Int>()
            for (j in 0 until cards.length()) distinct.add(cards.getInt(j))
            for (id in distinct) seen[id] = (seen[id] ?: 0) + 1
        }
        return seen.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .filter { cat.isPlayable(it.key) && cat.rulesText(it.key).isNotEmpty() }
            .take(top)
            .map { it.key }
    }

    fun build(
        seed: Int,
        cat: Catalogue,
        meta: JSONArray,
        size: Int = 100,
        stapleSlots: Int = 30,
        pool: Int = 14,
    ): Built? {
        if (!cat.isPlayable(seed)) return null
        val theme = themeOf(seed, cat)
        if (theme.isEmpty()) return null
        val tset = theme.toSet()

        val picks = ArrayList<Pair<Int, Int>>()
        picks.add(seed to cat.deckLimit(seed))
        var total = cat.deckLimit(seed)

        val ranked = cat.byId.keys
            .filter { it != seed && cat.isPlayable(it) }
            .sortedWith(compareByDescending<Int> { affinity(it, tset, cat) }.thenBy { cat.cost(it) }
                .thenBy { it })

        val room = size - stapleSlots
        for (id in ranked.take(pool)) {
            if (total >= room) break
            if (affinity(id, tset, cat) <= 0.0) break
            val q = minOf(cat.deckLimit(id), room - total)
            if (q > 0) { picks.add(id to q); total += q }
        }

        for (id in staples(meta, cat, 10)) {
            if (total >= size) break
            if (picks.any { it.first == id }) continue
            val q = minOf(cat.deckLimit(id), size - total, 15)
            if (q > 0) { picks.add(id to q); total += q }
        }

        // Top up cards already chosen before reaching for filler. Padding
        // straight to 100 with a blank is 15% of a list doing nothing, in a
        // format where the deck plays itself and a dead draw is a wasted turn.
        if (total < size) {
            for (i in picks.indices) {
                if (total >= size) break
                val (id, q) = picks[i]
                val headroom = minOf(cat.deckLimit(id) - q, size - total)
                if (headroom > 0) { picks[i] = id to (q + headroom); total += headroom }
            }
        }
        if (total < size) { picks.add(INFINITE_FILLER to (size - total)); total = size }

        val cards = ArrayList<Int>(size)
        for ((id, q) in picks) repeat(q) { cards.add(id) }
        if (cards.size != size) return null

        // card_order is worth more than any other plan field, and an
        // archetype's own ordering is the one thing a generator gets right for
        // free: seed first, then synergy, then generic staples.
        val plan = JSONObject()
            .put("play_priority", "card_order")
            .put("card_order", JSONArray(picks.map { it.first }))
            .put("card_order_hold", false)
            .put("energy_hold", 0)
            .put("target_preference", "least_armor")

        return Built(seed, cat.name(seed), theme, picks, cards, plan)
    }
}
