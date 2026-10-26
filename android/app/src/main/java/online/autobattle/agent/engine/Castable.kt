package online.autobattle.agent.engine

import org.json.JSONObject

/**
 * Can this deck ever actually PLAY this card?
 *
 * WHY THIS EXISTS. A census of real play across the league found 4.6 cards
 * per hundred that never reach the board, and the worst deck wasted 36 of
 * them. The clearest case: a 72.1% poison deck running ten copies of Shield
 * Soldier, whose text is "To Play: you must have gained at least 1 Shields
 * this turn", in a list containing nothing whatsoever that gains shields.
 * Ten slots that could never be spent, in a deck that was winning anyway.
 *
 * WHAT THIS CAN AND CANNOT DO. It answers "impossible", never "unlikely".
 * ShadowDrake's Orbelavundiquethessaromphantilus needs 3 Life gained in a
 * turn and the deck has exactly one card that gains life, so it IS castable
 * -- about once every three hundred games. That is a terrible card in that
 * deck and this check will not say so, because it is not false. Only playing
 * the deck reveals the merely futile; this catches the provably dead, which
 * is the part worth refusing for free.
 *
 * Three provable classes, all read from the card's own effects_json:
 *
 *   COST FLOOR     cost_scaling has a minimum, and that minimum is beyond
 *                  anything the game reaches. The One-Sided Coin of Xyx
 *                  floors at 33 energy, which at one energy a turn is turn
 *                  33 with nothing else ever spent.
 *   MANDATORY COST additional_cost demanding honour, shields or a sacrifice
 *                  the deck can never produce. Grim Veteran wants 7 Honor to
 *                  play and is a blank in a deck that makes none.
 *   SELF GATE      play_requires on a turn_tally metric driven by the
 *                  controller's OWN actions. If nothing in the deck gains
 *                  shields, shields_gained >= 1 is unreachable, and that is
 *                  inferable from the list alone.
 *
 * Gates that depend on the OPPONENT are deliberately not judged: a deck
 * cannot know what it will face, and guessing would refuse good cards.
 */
object Castable {

    /** Energy a long game can plausibly reach, for the cost-floor test. */
    private const val REACHABLE_ENERGY = 25

    /**
     * Metrics a deck drives itself, and the effect types that drive them.
     *
     * Only self-side tallies appear here. "cards_exiled by the opponent" is
     * not something a deck list can rule out.
     */
    private val SELF_METRIC_SOURCES = mapOf(
        "shields_gained" to listOf("gain_shield"),
        "life_gained" to listOf("gain_life"),
        "honor_gained" to listOf("gain_honor"),
    )

    /** Why a card can never be played here, or null if it can. */
    fun deadReason(cid: Int, deck: Collection<Int>, cat: Catalogue): String? {
        val card = cat.byId[cid] ?: return null
        val eff = card.optJSONObject("effects_json") ?: return null

        eff.optJSONObject("cost_scaling")?.let { cs ->
            val floor = cs.optInt("minimum", -1)
            if (floor > REACHABLE_ENERGY) return "costs at least $floor energy"
        }

        eff.optJSONObject("additional_cost")?.let { ac ->
            unpayable(ac, deck, cat)?.let { return it }
        }

        val reqs = eff.optJSONArray("play_requires")
        if (reqs != null) {
            for (i in 0 until reqs.length()) {
                val r = reqs.optJSONObject(i) ?: continue
                if (r.optString("kind") != "turn_tally") continue
                if (r.optString("side") != "self") continue
                val metric = r.optString("metric")
                val sources = SELF_METRIC_SOURCES[metric] ?: continue
                if (!deckCanProduce(deck, cat, sources)) {
                    return "needs $metric and nothing in the deck produces it"
                }
            }
        }
        return null
    }

    /** A mandatory cost the deck can never meet. */
    private fun unpayable(ac: JSONObject, deck: Collection<Int>, cat: Catalogue): String? {
        val honor = ac.optInt("honor", 0)
        if (honor > 0 && !deckCanProduce(deck, cat, listOf("gain_honor"))) {
            return "costs $honor honour to play and the deck gains none"
        }
        val shields = ac.optInt("shields", 0)
        if (shields > 0 && !deckCanProduce(deck, cat, listOf("gain_shield"))) {
            return "costs $shields shields to play and the deck gains none"
        }
        return null
    }

    /**
     * Does any card in the deck carry one of these effect types?
     *
     * Searches the raw effects_json text rather than walking every nesting
     * shape -- triggers, passives, supplements and additional costs all hold
     * effects, and new shapes get added. A substring search over the card's
     * own JSON cannot miss one, and the cost of a false POSITIVE here is
     * only that a dead card survives, which is the status quo.
     */
    private fun deckCanProduce(
        deck: Collection<Int>, cat: Catalogue, effectTypes: List<String>,
    ): Boolean = deck.any { id ->
        val raw = cat.byId[id]?.optJSONObject("effects_json")?.toString() ?: return@any false
        effectTypes.any { raw.contains("\"$it\"") }
    }
}
