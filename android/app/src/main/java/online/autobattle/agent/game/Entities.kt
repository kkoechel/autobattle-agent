package online.autobattle.agent.game

/**
 * The house agents, as the game presents them.
 *
 * WHO IS AN ENTITY AND WHO IS A PERSON. The coordinator already answers this:
 * `is_bot` arrives on every standings, field and leaderboard row, set on the
 * eight house agents and on nobody else. A real player's deck is never
 * dressed up as a monster -- they are opponents, not antagonists, and the
 * flag is the only thing allowed to make that distinction. Guessing from a
 * handle would be wrong the first time somebody registers as "Agent Smith".
 *
 * NO TABLE OF NAMES. The obvious implementation is a map from handle to a
 * hand-written epithet, and it rots the moment a ninth agent is spun up --
 * which happens, because the agents are created by a deploy script rather
 * than by hand. It is also unnecessary: the agents NAME THEIR OWN DECKS, and
 * those names already read like what we want. The live league is currently
 * fielding "Countless Swarm", "Oath Eternal", "Last Cinders of the Ember"
 * and "The Flourishing Verdure". Nothing a person could write by hand would
 * fit the deck better than the name the deck chose for itself, and this
 * version cannot go stale.
 *
 * The honorific scales with standing rather than identity, so the entity a
 * player is actually losing to reads as the biggest threat on the board --
 * which is the thing a 90s card-battler cares about.
 */
object Entities {

    /** True when this standings/field/leaderboard row is a house agent. */
    fun isEntity(isBot: Int): Boolean = isBot == 1

    /**
     * What to call one, given where it stands.
     *
     * `placement` is 1-based; 0 or negative means unplaced, which happens to
     * an agent that has not been scored yet and must not be promoted to a
     * god on the strength of a missing number.
     */
    fun rank(placement: Int): String = when {
        placement <= 0 -> "Dimensional"
        placement == 1 -> "Sovereign"
        placement <= 3 -> "Archon"
        placement <= 8 -> "Harbinger"
        else -> "Thrall"
    }

    /**
     * The full style: "Sovereign SableCourt, Countless Swarm".
     *
     * The deck name is the epithet, so it changes when the entity rebuilds --
     * which is correct. These things are supposed to shift shape.
     */
    fun styled(handle: String, deckName: String, placement: Int): String {
        val title = rank(placement)
        return if (deckName.isBlank()) "$title $handle"
        else "$title $handle, $deckName"
    }

    /** The short form, where a row has its own line for the deck name. */
    fun titled(handle: String, placement: Int): String = "${rank(placement)} $handle"
}
