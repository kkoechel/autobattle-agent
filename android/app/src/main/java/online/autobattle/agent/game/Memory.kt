package online.autobattle.agent.game

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the agent remembers between sessions, so it can notice CHANGE.
 *
 * Without this the question list was a one-shot: four questions, answered
 * once, and then an agent with nothing further to say. Worse, it lived in
 * `remember`, so a restart re-asked the same four as if the conversation had
 * never happened. An agent worth opening twice a day has to react to what
 * actually happened while you were gone -- somebody passed you, your last two
 * runs found nothing, a card in your deck got banned -- and that means
 * holding on to what things looked like last time.
 */
data class Memory(
    /** Onboarding questions already put to the player at least once. */
    val answered: Set<String> = emptySet(),
    val lastPlacement: Int = 0,
    val lastLeader: String? = null,
    /** Consecutive runs that improved nothing. */
    val barrenRuns: Int = 0,
    /** Themes the agent has actually been asked to build. */
    val triedThemes: Set<String> = emptySet(),
    /**
     * Combinations already put into play, each as its sorted tags joined by
     * commas. Tracking single tags was not enough once a plan could hold
     * several: "poison" having been tried says nothing about whether
     * "poison + card draw" has.
     */
    val triedCombos: Set<String> = emptySet(),
    /**
     * The round to report back on, or 0 for none.
     *
     * Set when the player chooses to be patient. Kept in Memory rather
     * than the Directive because it changes nothing about how a deck is
     * built -- it is a promise the agent made, not an instruction.
     */
    val waitUntilRound: Int = 0,
    /** The newest ban this player has been shown, so it is announced once. */
    val lastBanSeen: Int = 0,
    /** Round id when a spontaneous idea was last floated, so it stays rare. */
    val lastIdeaRound: Int = 0,
    /** Questions declined, so the agent does not nag on the same trigger. */
    val dismissed: Set<String> = emptySet(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("answered", JSONArray(answered.toList()))
        .put("lastPlacement", lastPlacement)
        // Omitted rather than JSON null: optString would read it back
        // as the string "null", which then compares unequal to every real
        // handle and makes the "you passed someone" trigger misfire.
        .putOpt("lastLeader", lastLeader)
        .put("barrenRuns", barrenRuns)
        .put("triedThemes", JSONArray(triedThemes.toList()))
        .put("triedCombos", JSONArray(triedCombos.toList()))
        .put("lastBanSeen", lastBanSeen)
        .put("lastIdeaRound", lastIdeaRound)
        .put("waitUntilRound", waitUntilRound)
        .put("dismissed", JSONArray(dismissed.toList()))

    companion object {
        private const val PREFS = "abagent"
        private const val KEY = "agent_memory"

        private fun strings(o: JSONObject, k: String): Set<String> {
            val a = o.optJSONArray(k) ?: return emptySet()
            return (0 until a.length()).map { a.getString(it) }.toSet()
        }

        fun load(ctx: Context): Memory = try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null) ?: return Memory()
            val o = JSONObject(raw)
            Memory(
                answered = strings(o, "answered"),
                lastPlacement = o.optInt("lastPlacement", 0),
                lastLeader = o.optString("lastLeader", "")
                    .takeIf { it.isNotBlank() && it != "null" },
                barrenRuns = o.optInt("barrenRuns", 0),
                triedThemes = strings(o, "triedThemes"),
                triedCombos = strings(o, "triedCombos"),
                lastBanSeen = o.optInt("lastBanSeen", 0),
                lastIdeaRound = o.optInt("lastIdeaRound", 0),
                waitUntilRound = o.optInt("waitUntilRound", 0),
                dismissed = strings(o, "dismissed"),
            )
        } catch (e: Exception) {
            Memory()
        }

        fun save(ctx: Context, m: Memory) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, m.toJson().toString()).apply()
        }
    }
}
