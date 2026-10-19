package online.autobattle.agent.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * The card catalogue, indexed and pre-digested for the generator.
 *
 * `banned` is part of playability, not a filter applied afterwards. The
 * generator used to draw supporting cards from the whole catalogue and the
 * finished deck was then thrown away if it contained any banned card --
 * which, with a rotating pool, meant a build could simply fail. Observed on
 * the device with only two cards out of the format: "could not build a legal
 * deck", from an agent that had every legal card available to it. Excluding
 * them where cards are CHOSEN makes every deck legal by construction.
 */
class Catalogue(val raw: JSONArray, val banned: Set<Int> = emptySet()) {

    val byId: Map<Int, JSONObject> = buildMap {
        for (i in 0 until raw.length()) {
            val c = raw.getJSONObject(i)
            put(c.getInt("id"), c)
        }
    }

    fun name(id: Int): String = byId[id]?.optString("name") ?: "#$id"
    fun cost(id: Int): Int = byId[id]?.optInt("cost", 0) ?: 0
    fun deckLimit(id: Int): Int = byId[id]?.optInt("deck_limit", 0) ?: 0
    fun rulesText(id: Int): String = byId[id]?.optString("rules_text", "")?.trim() ?: ""

    fun tags(id: Int): Set<String> {
        val a = byId[id]?.optJSONArray("tags") ?: return emptySet()
        return (0 until a.length()).map { a.getString(it) }.toSet()
    }

    /**
     * Can this card legally go in a deck we build?
     *
     * One predicate rather than a filter repeated at each call site: the
     * catalogue is 38% retired (332 of 865) and a missed check is silent --
     * the engine simulates a retired card perfectly happily, so a deck built
     * around one measures fine offline and is only corrected live.
     */
    fun isPlayable(id: Int): Boolean {
        val c = byId[id] ?: return false
        // Banned first: it is the cheapest check and the one that changes
        // under the player, every seventeen hours.
        if (id in banned) return false
        if (c.optBoolean("is_retired", false) || c.optInt("is_retired", 0) == 1) return false
        if (c.optBoolean("is_vip", false) || c.optInt("is_vip", 0) == 1) return false
        if (c.optString("rarity") == "token") return false
        return deckLimit(id) > 0
    }
}
