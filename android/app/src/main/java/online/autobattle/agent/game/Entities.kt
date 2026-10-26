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

    /**
     * Which kind of competitor a row is.
     *
     * `is_bot` was one bit and said only "not a person", which was enough
     * while the house agents were the only non-humans. They are not any
     * more: the good-themed NPC Paragons are also bots, and presenting them
     * as Dimensional Gods would be exactly backwards -- they are the
     * player's peers, not the thing the player is climbing towards.
     *
     * Read from `faction`, falling back on is_bot so a row from a league
     * that has not been migrated still reads as an entity rather than
     * silently becoming a person.
     */
    enum class Kind { PERSON, GOD, PARAGON }

    fun kindOf(isBot: Int, faction: String?): Kind = when {
        faction == "god" -> Kind.GOD
        faction == "paragon" -> Kind.PARAGON
        isBot == 1 -> Kind.GOD
        else -> Kind.PERSON
    }

    /** True when this row is a house agent of either faction. */
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
        return if (deckName.isBlank()) "$title ${bare(handle)}"
        else "$title ${bare(handle)}, $deckName"
    }

    /**
     * The registered handle with its scaffolding removed.
     *
     * Half the house agents were registered as "Agent Quill", "Agent Rook"
     * and so on, which is how the deploy script names them rather than
     * anything about the character -- and titling them produces "Archon Agent
     * Rook", three nouns deep. The prefix is dropped for display only; the
     * handle itself is what the league knows them by and is never rewritten.
     */
    private fun bare(handle: String): String =
        handle.removePrefix("Agent ").ifBlank { handle }

    /** The short form, where a row has its own line for the deck name. */
    fun titled(handle: String, placement: Int): String =
        "${rank(placement)} ${bare(handle)}"
}
