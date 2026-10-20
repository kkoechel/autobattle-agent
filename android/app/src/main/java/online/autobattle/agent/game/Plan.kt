package online.autobattle.agent.game

import org.json.JSONObject

/**
 * How the deck PLAYS, as opposed to what is in it.
 *
 * AutoBattle gives a player two things: a hundred-card list and a battle
 * plan. The app has only ever had opinions about the first. Every deck it
 * has ever built shipped the same plan fields, hardcoded in Archetype.build
 * -- play in the archetype's own order, never reserve energy, aim at
 * whatever is least armoured. That is a defensible default and it was never
 * a decision anybody made.
 *
 * THE PLAYER IS ASKED GENERAL QUESTIONS AND THE AGENT MAKES THE ATOMIC
 * DECISIONS. The engine's battlePlan struct has sixteen fields with
 * vocabularies like "least_armor" and "first_spend", and asking a player to
 * set energy_hold_scope is asking them to do the agent's job. So the two
 * things a person actually has an opinion about -- how the deck carries
 * itself, and what it goes after -- are what get asked, and each answer
 * derives the fields underneath it. The agent then SAYS what it derived,
 * because that is how a player learns there is an energy reservation at all.
 *
 * Every field written here was checked against shell/engine/engine.go:
 *
 *   target_preference    read by selectTarget() for every effect that
 *                        reaches for an enemy permanent (engine.go:5744).
 *   energy_hold          +scope, the gate at the top of playOneCard()
 *                        (engine.go:6308).
 *   card_order_hold      the reservation protecting card_order[0]
 *                        (engine.go:6353).
 *
 * THE DEFAULTS REPRODUCE TODAY'S PLAN EXACTLY, down to the order the keys
 * are written in. A player who never answers a plan question gets
 * byte-identical JSON to what the app built before this
 * file existed, which is what makes these questions safe to add to a live
 * league rather than a silent re-tuning of everybody's deck.
 *
 * No posture is claimed to be an improvement. Each states its cost, because
 * the measured lesson of this project is that the search finds what works
 * and reasoning about mechanisms does not -- eight mechanism-backed swaps
 * proposed early on all made the deck worse. A player choosing a posture is
 * saying how they want their agent to play, which is a preference and not a
 * result.
 */
data class PlanPrefs(
    val tempo: Tempo = Tempo.PRESS,
    val aim: Aim = Aim.MOST_DAMAGE,
) {
    /**
     * How the deck carries itself: the general idea, from which the two
     * energy fields and the order reservation follow.
     *
     * No two postures derive the same fields. A posture that came out
     * identical to another would be a label on identical work, which is the
     * decorative kind of setting this game is supposed to avoid -- so the
     * default is named honestly as PRESS rather than offered twice as
     * "press" and "steady".
     *
     * Both reservations have a live failure report behind them, recorded at
     * engine.go:6322. Spending freely is how a cost-4 creature went twenty
     * turns unplayed while cheap cards took its energy every turn -- "custom
     * card order isn't working right". Holding is the fix, and it is scoped
     * to card_order[0] alone because an earlier version that protected
     * whichever ranked card sat in hand chained holds indefinitely and
     * starved the deck -- "my deck is horrible now, APS doesn't get played
     * at all".
     */
    enum class Tempo(
        val hold: Int,
        val scope: String,
        /** Reserve basic energy for the top of the playing order. */
        val protectBest: Boolean,
    ) {
        /** Everything, every turn. Today's hardcoded behaviour. */
        PRESS(0, "every_turn", false),

        /** One quiet opening, then normal play, with the best card protected. */
        BUILD(5, "first_play", true),

        /** Never act on a poor turn, all game. */
        NO_WEAK_TURNS(3, "every_turn", true),
    }

    /**
     * What the deck goes after, when a card reaches for something of theirs
     * without naming it.
     *
     * Only values selectTarget() implements. It has two switches and the
     * first wins: lowest/highest_melee, most_armor and lowest/highest_cost
     * are handled there, weakest/strongest/least_armor in the second, and
     * ANYTHING ELSE falls through to a random pick. A typo is not a compile
     * error, it is a silently random deck -- which is why these are an enum
     * and not strings at the call site.
     */
    enum class Aim(val field: String) {
        /** Least armoured, so the least of our damage is soaked. Today's. */
        MOST_DAMAGE("least_armor"),

        /** Lowest health: their board empties fastest. */
        CLEAR_THE_BOARD("weakest"),

        /** Costliest permanent: a fair guess at their best card. */
        THEIR_BEST("highest_cost"),
    }

    /**
     * Write the derived fields onto a plan.
     *
     * Mutates the object it is given, which is always a copy at the call
     * sites. Mutate.apply deep-copies a plan through
     * JSONObject(plan.toString()) and so carries these down every mutation,
     * which is why writing them once at build time is enough.
     */
    fun applyTo(plan: JSONObject): JSONObject {
        // WRITTEN IN THE ORDER THE OLD HARDCODED PLAN USED THEM:
        // card_order_hold, energy_hold, target_preference. JSONObject keeps
        // insertion order, so a default PlanPrefs over the archetype's
        // play_priority and card_order serialises to the same bytes the app
        // has always submitted -- not merely the same fields. That is worth
        // one odd-looking ordering: it means adding these questions to a
        // live league cannot quietly re-tune the deck of a player who never
        // answers one.
        plan.put("card_order_hold", tempo.protectBest)
        plan.put("energy_hold", tempo.hold)
        // Absent rather than 0 when there is no hold, for the same reason.
        if (tempo.hold > 0) plan.put("energy_hold_scope", tempo.scope)
        else plan.remove("energy_hold_scope")
        plan.put("target_preference", aim.field)
        return plan
    }

    /**
     * What the agent decided, in its own words.
     *
     * THIS IS THE HALF THE PLAYER LEARNS FROM. They answered a general
     * question; this is the agent reporting the atomic decisions it took on
     * the back of it, which is the only way anybody finds out that an energy
     * reservation exists. Phrased as orders the deck follows, not as fields.
     */
    fun decisions(): List<String> {
        val out = ArrayList<String>()
        when (tempo) {
            Tempo.PRESS ->
                out.add("play whatever we can afford, every turn")
            Tempo.BUILD -> {
                out.add("hold our opening until we have five energy, then play on normally")
                out.add("keep energy aside for the best card in the order")
            }
            Tempo.NO_WEAK_TURNS -> {
                out.add("skip any turn that opens under three energy")
                out.add("keep energy aside for the best card in the order")
            }
        }
        out.add(when (aim) {
            Aim.MOST_DAMAGE -> "aim at whatever is least armoured"
            Aim.CLEAR_THE_BOARD -> "aim at whatever has the least health left"
            Aim.THEIR_BEST -> "aim at whatever cost them most"
        })
        return out
    }

    /** The parts of the standing brief a player reads, when not default. */
    fun summary(): List<String> {
        val bits = ArrayList<String>()
        when (tempo) {
            Tempo.PRESS -> {}
            Tempo.BUILD -> bits.add("building to one big turn")
            Tempo.NO_WEAK_TURNS -> bits.add("refusing weak turns")
        }
        when (aim) {
            Aim.MOST_DAMAGE -> {}
            Aim.CLEAR_THE_BOARD -> bits.add("clearing their board")
            Aim.THEIR_BEST -> bits.add("going after their best card")
        }
        return bits
    }

    fun toJson(): JSONObject = JSONObject()
        .put("tempo", tempo.name).put("aim", aim.name)

    companion object {
        fun fromJson(o: JSONObject?): PlanPrefs {
            if (o == null) return PlanPrefs()
            // valueOf throws on a value written by a later version of the app
            // and since removed; a plan preference is not worth losing the
            // whole directive over.
            fun <T : Enum<T>> pick(key: String, all: Array<T>, dflt: T): T {
                val v = o.optString(key, "")
                return all.firstOrNull { it.name == v } ?: dflt
            }
            return PlanPrefs(
                tempo = pick("tempo", Tempo.values(), Tempo.PRESS),
                aim = pick("aim", Aim.values(), Aim.MOST_DAMAGE),
            )
        }
    }
}
