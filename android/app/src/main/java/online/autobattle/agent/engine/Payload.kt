package online.autobattle.agent.engine

import org.json.JSONArray
import org.json.JSONObject

/** One deck in a simulation: a flat card list with repeats, plus its plan. */
data class Deck(
    val slotId: String,
    val cards: List<Int>,
    val battlePlan: JSONObject?,
    val name: String = "",
    val deckId: Int? = null,
)

/**
 * Builds the JSON the engine's --local-payload mode expects.
 *
 * The card map is built once and cached as a string. It is ~570KB of the
 * payload and identical across every call for a given catalogue, so rebuilding
 * it per evaluation would cost more than the simulation for small runs.
 */
class PayloadBuilder(catalogue: JSONArray) {

    private val cardsJson: String = buildCards(catalogue)

    /**
     * Copies the fields the engine actually reads.
     *
     * `tags`, never `ability_tags`: tags is what the engine matches tag
     * effects against, ability_tags is a derived display set that omits
     * creature types and differs on 342 of 859 cards. Getting it wrong does
     * not error — tag effects quietly find no targets and the numbers come
     * back confident and wrong.
     *
     * The subtype is copied to `card_type`, which is the engine's name for it.
     */
    private fun buildCards(catalogue: JSONArray): String {
        val out = JSONObject()
        for (i in 0 until catalogue.length()) {
            val c = catalogue.getJSONObject(i)
            val id = c.getInt("id")
            val e = JSONObject()
            e.put("card_id", id)
            e.put("name", c.optString("name"))
            e.put("cost", c.optInt("cost", 0))
            e.put("supertype", c.opt("supertype") ?: JSONObject.NULL)
            e.put("card_type", c.opt("subtype") ?: JSONObject.NULL)
            e.put("effects_json", c.opt("effects_json") ?: JSONObject.NULL)
            e.put("stats_json", c.opt("stats_json") ?: JSONObject.NULL)
            e.put("tags", c.optJSONArray("tags") ?: JSONArray())
            out.put(id.toString(), e)
        }
        return out.toString()
    }

    /**
     * candidates vs every opponent, on each seed.
     *
     * Seats alternate by seed INDEX rather than seed value, so every candidate
     * plays seed i from the same chair and comparisons stay paired — that
     * pairing is the whole reason candidates share a seed block. The live game
     * does not alternate (a cohort fixes slot_a by slot order), but which chair
     * a deck gets is then an accident of ordering, and measured seat advantage
     * was +0.4pp with a standard error of 2.6pp: too small to detect, too large
     * to assume away when a harness number is compared against a live one.
     */
    fun build(candidates: List<Deck>, opponents: List<Deck>, seeds: List<Int>): String {
        val slots = JSONArray()
        for (o in opponents) slots.put(slotOf(o))
        for (c in candidates) slots.put(slotOf(c))

        val matches = JSONArray()
        for (c in candidates) {
            for (o in opponents) {
                for ((si, s) in seeds.withIndex()) {
                    val first = if (si % 2 == 0) c.slotId else o.slotId
                    val second = if (si % 2 == 0) o.slotId else c.slotId
                    matches.put(
                        JSONObject()
                            .put("match_id", "${c.slotId}|${o.slotId}|$s")
                            .put("slot_a", first)
                            .put("slot_b", second)
                            .put("seed", s)
                    )
                }
            }
        }

        // Assembled as text so the cached card map is not re-parsed.
        return StringBuilder(cardsJson.length + matches.toString().length + 4096)
            .append("{\"cards\":").append(cardsJson)
            .append(",\"slots\":").append(slots)
            .append(",\"matches\":").append(matches)
            .append(",\"chaos_effect\":\"\"}")
            .toString()
    }

    private fun slotOf(d: Deck) = JSONObject()
        .put("slot_id", d.slotId)
        .put("cards", JSONArray(d.cards))
        .put("battle_plan", d.battlePlan ?: JSONObject())
        .put("champion_id", JSONObject.NULL)
}
