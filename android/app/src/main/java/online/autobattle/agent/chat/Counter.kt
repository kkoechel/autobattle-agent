package online.autobattle.agent.chat

import online.autobattle.agent.engine.Catalogue
import online.autobattle.agent.engine.Deck
import online.autobattle.agent.engine.Score
import online.autobattle.agent.engine.Stats
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Beat Hymn": fix one named matchup without losing the tournament.
 *
 * Once a deck beats most of the field its remaining upside is concentrated in
 * a few opponents, and against the whole field that upside is DILUTED, 73:1.
 * Turning a 0-21 matchup into a 21-0 one is worth about one win of 73 -- below
 * what any affordable number of seeds can resolve if you measure it across the
 * field. So candidates are screened against the named deck ALONE, where the
 * effect is undiluted and obvious, and scoring one opponent instead of
 * seventy-three is also what buys the seeds to see it.
 *
 * Acceptance is the other way round. The screen makes good candidates
 * findable; it never decides whether one is kept. A card that wins this
 * matchup and costs two wins elsewhere is not a counter, so the survivor is
 * judged on TOTAL wins against the full field, on a fresh seed block, against
 * the same gate as everything else. Both numbers are shown, because the
 * player asked about one matchup and the answer is usually about all of them.
 */
class Counter(
    private val cat: Catalogue,
    private val metaDecks: JSONArray,
    private val deckId: Int,
    private val deckName: String,
    private val deckCards: List<Int>,
    private val deckPlan: JSONObject,
) {

    data class Target(val deckId: Int, val name: String, val owner: String, val json: JSONObject)

    data class Record(val wins: Int, val losses: Int, val draws: Int) {
        override fun toString() = "$wins–$losses–$draws"
    }

    data class Result(
        val deckName: String,
        val target: Target,
        val before: Record,
        val after: Record,
        val h2hSeeds: Int,
        val add: Int,
        val addName: String,
        val addQty: Int,
        val removed: List<Pair<Int, Int>>,
        val fromTheirList: Boolean,
        val screened: Int,
        val fieldGain: Double,
        val fieldT: Double,
        val candidate: Score,
        val incumbent: Score,
        val validateSeeds: Int,
        val opponents: Int,
        val elapsedMs: Long,
    ) {
        /** Did the named matchup actually move? */
        val matchupImproved: Boolean get() = after.wins > before.wins
        /** Is it worth keeping? Decided on the FIELD, never on the matchup. */
        val keeps: Boolean get() = Stats.accept(fieldGain, fieldT)
    }

    /** Resolve a typed name to a deck in the field. Exact, then prefix, then contains. */
    fun find(query: String): Target? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val all = (0 until metaDecks.length()).map { metaDecks.getJSONObject(it) }
            .filter { it.optInt("deck_id", -1) != deckId }
        fun mk(d: JSONObject) = Target(
            d.optInt("deck_id", -1), d.optString("deck_name"), d.optString("owner"), d)

        all.firstOrNull { it.optString("deck_name").lowercase() == q }?.let { return mk(it) }
        all.firstOrNull { it.optString("deck_name").lowercase().startsWith(q) }?.let { return mk(it) }
        all.firstOrNull { it.optString("deck_name").lowercase().contains(q) }?.let { return mk(it) }
        // Owners too: "beat stinky magoo" is a reasonable thing to type.
        all.firstOrNull { it.optString("owner").lowercase().contains(q) }?.let { return mk(it) }
        return null
    }

    /** Deck names we could have meant, for an unrecognised query. */
    fun names(limit: Int = 6): List<String> =
        (0 until metaDecks.length()).map { metaDecks.getJSONObject(it) }
            .filter { it.optInt("deck_id", -1) != deckId }
            .sortedBy { it.optInt("rank", 999) }
            .mapNotNull { it.optString("deck_name").ifBlank { null } }
            .take(limit)

    private fun recordOf(s: Score, slot: String): Record {
        val t = s.perOpponent[slot] ?: return Record(0, 0, 0)
        return Record(t.first, t.second, t.third)
    }

    fun run(
        target: Target,
        qty: Int = 5,
        h2hSeeds: Int = 101,
        screenSeeds: Int = 41,
        validateSeeds: Int = 61,
        workers: Int = 4,
        onProgress: (String) -> Unit = {},
    ): Result? {
        val started = System.currentTimeMillis()
        val them = deckOf(target.json, "T")
        val mine = deckCards.toSet()

        // Candidates: what THEY play that we do not, first -- a deck that beats
        // us is a working answer to our deck and its list is the cheapest
        // description of why -- then the cards nobody in the field plays at
        // all, which is where anything genuinely new has to come from.
        val fromThem = counterCandidates(mine, them.cards, cat).map { it.first }
        val played = HashSet<Int>()
        for (i in 0 until metaDecks.length()) {
            val a = metaDecks.getJSONObject(i).getJSONArray("cards")
            for (j in 0 until a.length()) played.add(a.getInt(j))
        }
        val unplayed = cat.byId.keys.filter {
            cat.isPlayable(it) && it !in played && it !in mine &&
                cat.rulesText(it).isNotEmpty()
        }.sorted()
        val pool = (fromThem + unplayed).distinct()
        if (pool.isEmpty()) return null

        // ---- stage 1: screen against the named deck alone -----------------
        onProgress("Trying ${pool.size} cards against ${target.name}…")
        val sSeeds = (0 until screenSeeds).map { 6_000_000 + it }
        val arms = ArrayList<Deck>()
        arms.add(Deck("MINE", deckCards, deckPlan, deckName, deckId))
        val byArm = HashMap<String, Int>()
        for (c in pool) {
            val v = variantOf(deckCards, deckPlan, cat, c, minOf(qty, cat.deckLimit(c)))
                ?: continue
            val slot = "A${byArm.size}"
            byArm[slot] = c
            arms.add(Deck(slot, v.cards, v.plan, cat.name(c), null))
        }
        if (byArm.isEmpty()) return null

        val screen = simulate(cat, arms, listOf(them), sSeeds, workers)
        val mineH2H = screen["MINE"]!!.wins
        val best = byArm.entries
            .map { (slot, cid) -> cid to (screen[slot]!!.wins - mineH2H) }
            .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
            .first()

        // ---- stage 2: judge it on the whole field -------------------------
        onProgress("Checking ${cat.name(best.first)} against the rest of the field…")
        val v = variantOf(deckCards, deckPlan, cat, best.first,
            minOf(qty, cat.deckLimit(best.first))) ?: return null

        val field = fieldOpponents(metaDecks, deckId, Int.MAX_VALUE).toMutableList()
        // The named deck must be IN the field for this to be an honest total:
        // dropping it would score the counter on everything except the thing
        // it was built for.
        val targetSlot = field.firstOrNull { it.deckId == target.deckId }?.slotId
            ?: "T2".also { field.add(deckOf(target.json, it)) }

        val vSeeds = (0 until validateSeeds).map { 7_000_000 + it }
        val vArms = listOf(
            Deck("MINE", deckCards, deckPlan, deckName, deckId),
            Deck("CAND", v.cards, v.plan, cat.name(best.first), null),
        )
        val vr = simulate(cat, vArms, field, vSeeds, workers)
        val (gain, t) = Stats.pairedT(vr["CAND"]!!, vr["MINE"]!!)

        return Result(
            deckName = deckName, target = target,
            before = recordOf(vr["MINE"]!!, targetSlot),
            after = recordOf(vr["CAND"]!!, targetSlot),
            h2hSeeds = validateSeeds,
            add = best.first, addName = cat.name(best.first),
            addQty = v.swaps.sumOf { it.qty },
            removed = v.swaps.map { it.cut to it.qty },
            fromTheirList = best.first in fromThem,
            screened = byArm.size,
            fieldGain = gain, fieldT = t,
            candidate = vr["CAND"]!!, incumbent = vr["MINE"]!!,
            validateSeeds = validateSeeds, opponents = field.size,
            elapsedMs = System.currentTimeMillis() - started,
        )
    }
}
