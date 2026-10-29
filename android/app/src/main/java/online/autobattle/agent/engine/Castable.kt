package online.autobattle.agent.engine

import org.json.JSONArray
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
 * THE TABLE USED TO KNOW THREE METRICS. shields_gained, life_gained and
 * honor_gained -- and the loop below read `SELF_METRIC_SOURCES[metric] ?:
 * continue`, so any other self-side gate was not judged leniently, it was
 * not judged at all. Muck Crawler needs poison_cured, so it passed as
 * castable in a deck with no cure in it, and on 2026-10-29 FlowCheck's live
 * league deck was found running TEN copies of it, built right here. The same
 * blindness in the Python sibling put one copy each into two live arena decks
 * on the main site. A table that silently passes what it does not know is
 * worse than no table at all, because everything downstream reports clean.
 *
 * So this is now a port of abagent/castable.py, which is itself a port of the
 * main app's inc/deck_castability.php, whose tables are read from
 * js/battle-engine.js. Keep the three in step; the Python one carries the
 * commentary on each table's provenance.
 *
 * Provable classes, all read from the card's own effects_json:
 *
 *   SELF GATE      play_requires on a turn_tally metric the controller drives
 *                  themselves, now for all twelve such metrics, with the
 *                  producer's own prerequisite checked (a cure tallies only
 *                  what it REMOVED, so curing with no poison on you opens
 *                  nothing).
 *   STAT GATE      play_requires on Life/Honor/Shields/Poison that the
 *                  opening state does not satisfy and the deck cannot reach.
 *   MANDATORY COST additional_cost demanding honour or shields the deck can
 *                  never produce. Grim Veteran wants 7 Honor to play and is a
 *                  blank in a deck that makes none.
 *   COST FLOOR     cost_scaling's minimum beyond anything the game reaches.
 *                  NOT A PROOF -- it assumes a ceiling. Behind provenOnly.
 *
 * Gates that depend on the OPPONENT are deliberately not judged: a deck
 * cannot know what it will face, and guessing would refuse good cards.
 * Whether an opponent COULD open a self-side gate for you is read across the
 * whole catalogue, and withholds a verdict rather than making one.
 *
 * NO CHAOS HANDLING, deliberately. The main app must allow for a Chaos
 * cohort roll poisoning or recycling for you, which turns some proofs into
 * "only in some cohorts". The agent league has no arenas and no rolls -- a
 * round is a plain round-robin -- so a deck built here faces none of that and
 * the stronger verdict is the correct one. If the league ever grows arenas,
 * abagent/castable.py has the table and the argument.
 *
 * NOT PORTED, so a clean answer here is weaker than the main app's: the
 * "must utilize/sacrifice a <supertype>" target proof, and the fixpoint that
 * catches a card whose only enabler is itself dead. Archetype makes one pass
 * on purpose; see its comment.
 */
object Castable {

    /** Energy a long game can plausibly reach, for the cost-floor test. */
    private const val REACHABLE_ENERGY = 25

    /** mkPlayer(): Life starts at 100, Honor and Shields at 0. */
    private val START = mapOf("life" to 100, "honor" to 0, "shields" to 0, "poison" to 0)

    /**
     * Metrics every turn produces with no card's help -- the turn's own draw,
     * playing a card, spending energy. A gate on one of these is a question
     * of amount and sequencing, never of a missing producer.
     */
    private val BASELINE_METRICS = setOf("cards_drawn", "cards_played", "energy_spent")

    private val POST_PLAY_EVENTS = setOf("end_of_turn", "after_combat")
    private val REACTIVE_EVENTS = setOf("on_death", "on_leave", "on_discard", "each_opponent_turn")
    private val KNOWN_SECTIONS =
        setOf("triggers", "passives", "play_requires", "additional_cost", "supplement")

    /**
     * What drives a metric or a stat.
     *
     * requiresStat names a stat the producer CONSUMES: remove_poison tallies
     * Math.min(amount, your poison), so with no poison on you it tallies
     * nothing and the gate stays shut.
     */
    private class Spec(
        val effects: Set<String> = emptySet(),
        val costs: Set<String> = emptySet(),
        val passives: Set<String> = emptySet(),
        val requiresStat: String? = null,
        val opp: Set<String> = emptySet(),
    )

    /**
     * What tallies a metric for the CONTROLLER. Combat links are absent on
     * purpose: combat is turn step 6c, the play phase is step 5, and both
     * players' tallies reset at the next turn start -- so nothing in combat
     * can ever open a "this turn" gate.
     */
    private val SELF_TALLY = mapOf(
        "life_gained" to Spec(effects = setOf("gain_life")),
        "honor_gained" to Spec(effects = setOf("gain_honor")),
        "honor_lost" to Spec(effects = setOf("lose_honor", "pay_honor_and"), costs = setOf("honor")),
        "shields_gained" to Spec(effects = setOf("gain_shield")),
        "poison_gained" to Spec(effects = setOf("gain_poison")),
        // The only tally site is remove_poison; turn-start decay is a bare
        // poison-- with no tally, so a deck cannot wait the gate out.
        "poison_cured" to Spec(effects = setOf("remove_poison"), requiresStat = "poison"),
        "cards_recycled" to Spec(
            effects = setOf("shuffle_discard_to_library", "recycle_self", "recycle_and"),
            costs = setOf("recycle"), passives = setOf("reshuffle"),
        ),
        "cards_milled" to Spec(effects = setOf("mill_self")),
        "cards_exiled" to Spec(
            effects = setOf("exile_self_deck", "exile_self_hand", "exile_self_discard",
                "exile_discard_and", "exile_tagged", "exile_deck_and_manifest"),
            costs = setOf("exile_discard"), passives = setOf("gravecast"),
        ),
        "cards_discarded" to Spec(effects = setOf("discard_self", "discard_and"), costs = setOf("discard")),
        // gain_poison belongs here too: poison ticks through dmgPlayer() at
        // the start of your NEXT turn, before you play.
        "life_lost" to Spec(
            effects = setOf("deal_damage_to_self", "pay_life_and", "gain_poison"), costs = setOf("life"),
        ),
        // toDiscardOrExile() tallies every permanent of yours that leaves play.
        "permanents_died" to Spec(
            effects = setOf("sacrifice_and", "sacrifice_own_permanent", "destroy_self",
                "destroy_tagged", "destroy_random_tagged", "destroy_target", "kill_target",
                "destroy_permanents_by_cost", "deal_damage_to_permanents_by_cost",
                "deal_damage_to_random_creature", "deal_damage_to_random_tagged",
                "deal_damage_to_tagged", "deal_damage_to_weakest_creature",
                "deal_damage_to_own_random_creature", "adjust_health"),
            // v4.52: a Saga is sacrificed after its last chapter.
            costs = setOf("sacrifice"), passives = setOf("saga"),
        ),
    )

    /**
     * How an OPPONENT's card could hand you a metric, making a self-side gate
     * unjudgeable rather than dead. Metrics absent here -- poison_cured,
     * honor_gained, shields_gained -- have no effect anywhere that gives them
     * to the other player, which is why a missing cure is a proof.
     */
    private val OPP_TALLY = mapOf(
        "life_gained" to setOf("opponent_gain_life"),
        "poison_gained" to setOf("poison_opponent"),
        "cards_milled" to setOf("mill_opponent"),
        "cards_exiled" to setOf("exile_opponent_deck", "exile_opponent_hand",
            "exile_opponent_discard", "exile_tagged", "exile_deck_and_manifest"),
        "cards_recycled" to setOf("recycle_opponent"),
        "cards_discarded" to setOf("discard_opponent"),
        "honor_lost" to setOf("reduce_opponent_honor"),
        "life_lost" to setOf("deal_damage_to_player", "poison_opponent"),
        "permanents_died" to setOf("destroy_tagged", "destroy_random_tagged", "destroy_target",
            "kill_target", "destroy_permanents_by_cost", "deal_damage_to_permanents_by_cost",
            "deal_damage_to_random_creature", "deal_damage_to_random_tagged",
            "deal_damage_to_tagged", "deal_damage_to_weakest_creature", "adjust_health"),
        "cards_drawn" to setOf("opponent_draw_card"),
    )

    /**
     * Persistent stats. Any gain at ANY time counts, combat links included,
     * because the stat carries into the turn the card is played.
     */
    private val STAT_GAIN = mapOf(
        "life" to Spec(effects = setOf("gain_life"), passives = setOf("lifelink"),
            opp = setOf("opponent_gain_life")),
        "honor" to Spec(effects = setOf("gain_honor"), passives = setOf("honorlink")),
        "shields" to Spec(effects = setOf("gain_shield"), passives = setOf("shieldlink")),
        "poison" to Spec(effects = setOf("gain_poison"), opp = setOf("poison_opponent")),
    )

    /**
     * One card's effect types, bucketed by WHEN they can fire: [pre] can
     * happen before or during your play phase, [any] ever, [reactive] on the
     * other player's turn.
     */
    private class Facts {
        val pre = HashSet<String>()
        val any = HashSet<String>()
        val reactive = HashSet<String>()
        val passives = HashSet<String>()
        val tokenCopies = ArrayList<Pair<Int, Boolean>>()   // card id, is the opponent's
        var playSelf = false
        var gates: JSONArray? = null
        var cost: JSONObject? = null
    }

    private class Derived(
        val facts: Map<Int, Facts>,
        val oppTally: Set<String>,
        val oppStat: Set<String>,
    )

    private var derivedFor: Catalogue? = null
    private var derivedCache: Derived? = null

    /**
     * Facts for the whole catalogue, built once per Catalogue.
     *
     * Opponent routes are catalogue-wide, so they cannot be answered from a
     * deck list -- and walking eleven hundred cards per card per deck would
     * be hopeless on a phone. Keyed on the Catalogue's identity and
     * synchronized, because deck building runs off the main thread.
     */
    @Synchronized
    private fun derived(cat: Catalogue): Derived {
        derivedCache?.let { if (derivedFor === cat) return it }
        val facts = cat.byId.mapValues { facts(it.value) }
        val oppTally = OPP_TALLY.filterValues { types ->
            facts.values.any { f -> types.any { it in f.reactive } }
        }.keys
        val oppStat = STAT_GAIN.filterValues { spec ->
            spec.opp.isNotEmpty() && facts.values.any { f -> spec.opp.any { it in f.any } }
        }.keys
        val d = Derived(facts, oppTally, oppStat)
        derivedFor = cat
        derivedCache = d
        return d
    }

    private fun walk(node: Any?, phase: String, f: Facts) {
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), phase, f)
            is JSONObject -> {
                val t = node.optString("type", "")
                if (t.isNotEmpty()) {
                    f.any.add(t)
                    if (phase != "post") f.pre.add(t)
                    if (phase == "reactive") f.reactive.add(t)
                    if (t == "create_token_copy") {
                        f.tokenCopies.add(
                            node.optInt("card_id", 0) to (node.optString("side", "") == "opponent"),
                        )
                    }
                    if (t == "play_self") f.playSelf = true
                }
                // Every child, not only effect/then/else: a producer under a
                // key this walk did not know would read as MISSING, and the
                // caller cuts cards on that verdict.
                val keys = node.keys()
                while (keys.hasNext()) {
                    val v = node.opt(keys.next())
                    if (v is JSONObject || v is JSONArray) walk(v, phase, f)
                }
            }
        }
    }

    private fun facts(card: JSONObject): Facts {
        val f = Facts()
        val eff = card.optJSONObject("effects_json") ?: return f
        f.gates = eff.optJSONArray("play_requires")
        f.cost = eff.optJSONObject("additional_cost")
        eff.optJSONArray("triggers")?.let { trs ->
            for (i in 0 until trs.length()) {
                val tr = trs.optJSONObject(i) ?: continue
                val ev = tr.optString("event", "")
                val phase = when (ev) {
                    in POST_PLAY_EVENTS -> "post"
                    in REACTIVE_EVENTS -> "reactive"
                    else -> "pre"
                }
                walk(tr.opt("effect"), phase, f)
            }
        }
        eff.optJSONArray("passives")?.let { ps ->
            for (i in 0 until ps.length()) {
                val p = ps.optJSONObject(i) ?: continue
                p.optString("type", "").takeIf { it.isNotEmpty() }?.let { f.passives.add(it) }
                val keys = p.keys()
                while (keys.hasNext()) {
                    val v = p.opt(keys.next())
                    if (v is JSONObject || v is JSONArray) walk(v, "reactive", f)
                }
            }
        }
        f.cost?.opt("effect")?.let { walk(it, "pre", f) }
        when (val sup = eff.opt("supplement")) {
            is JSONArray -> for (i in 0 until sup.length()) walk(sup.optJSONObject(i)?.opt("effect"), "pre", f)
            is JSONObject -> walk(sup.opt("effect"), "pre", f)
        }
        val keys = eff.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k in KNOWN_SECTIONS) continue
            val v = eff.opt(k)
            if (v is JSONObject || v is JSONArray) walk(v, "pre", f)
        }
        return f
    }

    /** The name of a card in [others] that drives what [spec] lists, or null. */
    private fun producer(others: List<Pair<Int, Facts>>, spec: Spec, phase: String, cat: Catalogue): String? {
        for ((id, f) in others) {
            val bucket = if (phase == "pre") f.pre else f.any
            if (spec.effects.any { it in bucket }) return cat.name(id)
            if (spec.costs.any { f.cost?.opt(it) != null && f.cost?.optInt(it, 0) != 0 }) return cat.name(id)
            if (spec.passives.any { it in f.passives }) return cat.name(id)
        }
        return null
    }

    /**
     * Does this comparison need anything to HAPPEN? "at most 0" is satisfied
     * by doing nothing, so it is never evidence that a card is stuck.
     */
    private fun needsSome(op: String, amount: Double): Boolean =
        (op == "gte" && amount > 0) || (op == "gt" && amount >= 0) || (op == "eq" && amount > 0)

    /**
     * Why a card can never be played here, or null if it can.
     *
     * provenOnly drops the cost-floor test, which assumes an energy ceiling
     * rather than proving one. Pass it wherever the verdict CUTS the card.
     */
    fun deadReason(
        cid: Int,
        deck: Collection<Int>,
        cat: Catalogue,
        provenOnly: Boolean = false,
    ): String? {
        val d = derived(cat)
        val me = d.facts[cid] ?: return null

        // A card that reaches the board without being CAST is not dead
        // weight, whatever its gate says: play_self skips the gate outright,
        // and a token copy of it enters without anyone casting anything.
        if (me.playSelf) return null
        if (deck.any { o -> d.facts[o]?.tokenCopies?.any { !it.second && it.first == cid } == true }) return null

        // Judged against the REST of the deck. A card cannot open its own
        // gate: the gate is checked before it is played, so its own on-enter
        // effect has not happened yet.
        val others = deck.toSet().filter { it != cid }.mapNotNull { id -> d.facts[id]?.let { id to it } }

        me.gates?.let { gates ->
            for (i in 0 until gates.length()) {
                val g = gates.optJSONObject(i) ?: continue
                val kind = g.optString("kind", "")
                val side = if (g.optString("side", "self") == "opponent") "opponent" else "self"
                val op = g.optString("op", "gte")
                val amount = g.optDouble("amount", 0.0)
                if (!needsSome(op, amount)) continue

                if (kind == "turn_tally") {
                    val m = g.optString("metric", "")
                    // Not ours to judge, produced by simply taking a turn, or
                    // a metric this file does not know -- which is left alone
                    // rather than passed, and is why this file was rewritten.
                    if (side == "opponent" || m in BASELINE_METRICS) continue
                    val spec = SELF_TALLY[m] ?: continue
                    if (producer(others, spec, "pre", cat) != null) {
                        val need = spec.requiresStat ?: continue
                        val statSpec = STAT_GAIN[need] ?: continue
                        if (producer(others, statSpec, "any", cat) != null) continue
                        if (need in d.oppStat) continue
                        return "needs self $m, but nothing can ever give you $need to remove"
                    }
                    if (m in d.oppTally) continue
                    return "needs self $m"
                }

                if (kind == "stat") {
                    val s = g.optString("stat", "")
                    if (side == "opponent" || (op != "gte" && op != "gt")) continue
                    val spec = STAT_GAIN[s] ?: continue
                    val start = START[s] ?: continue
                    val alreadyTrue = if (op == "gte") start >= amount else start > amount
                    if (alreadyTrue) continue
                    if (producer(others, spec, "any", cat) != null) continue
                    if (s in d.oppStat) continue
                    return "needs self $s $op ${amount.toInt()} (starts at $start, nothing raises it)"
                }
            }
        }

        me.cost?.let { ac ->
            for (s in listOf("honor", "shields")) {
                val owed = ac.optInt(s, 0)
                if (owed <= 0) continue
                val spec = STAT_GAIN[s] ?: continue
                if (producer(others, spec, "any", cat) != null) continue
                if (s in d.oppStat) continue
                return "costs $owed ${if (s == "honor") "honour" else s} to play and the deck gains none"
            }
        }

        if (!provenOnly) {
            val floor = (cat.byId[cid]?.optJSONObject("effects_json")
                ?.optJSONObject("cost_scaling")?.optInt("minimum", -1)) ?: -1
            if (floor > REACHABLE_ENERGY) return "costs at least $floor energy"
        }
        return null
    }
}
