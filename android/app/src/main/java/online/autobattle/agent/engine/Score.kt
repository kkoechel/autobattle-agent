package online.autobattle.agent.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * One candidate's simulated cohort record, averaged over seeds.
 *
 * Each seed is one whole simulated cohort, so the spread across seeds is a
 * real error bar rather than a guess — and it is large: a single cohort has a
 * standard deviation of roughly 6 wins on a mid-table deck, which is why every
 * comparison here is paired and nothing is believed on one pass.
 */
data class Score(
    val wins: Double,
    val losses: Double,
    val draws: Double,
    val winsSd: Double,
    val seeds: Int,
    val opponents: Int,
    val winsBySeed: Map<Int, Int>,
    /** slot_id -> (wins, losses, draws) totals across all seeds. */
    val perOpponent: Map<String, Triple<Int, Int, Int>>,
) {
    /** Live placement order: more wins first, then fewer losses. */
    val key: Pair<Double, Double> get() = Pair(wins, -losses)

    /** Error on the MEAN. winsSd is the spread of a single cohort. */
    val winsSe: Double get() = if (seeds > 1) winsSd / Math.sqrt(seeds.toDouble()) else 0.0

    override fun toString() =
        "%.1fW %.1fL %.1fD (SE %.2f, single-cohort SD %.1f, %d seeds x %d opponents)"
            .format(wins, losses, draws, winsSe, winsSd, seeds, opponents)
}

object Aggregator {

    /**
     * Folds engine results into a Score per candidate.
     *
     * Attribution is by slot id, not by seat: winner_slot names the winning
     * slot whichever chair it sat in, so the seat alternation in PayloadBuilder
     * needs no counterpart here.
     */
    fun aggregate(resultsJson: String, candidates: List<Deck>, seeds: List<Int>, opponents: Int):
            Map<String, Score> {
        val results = JSONArray(resultsJson)
        val perSeed = HashMap<String, HashMap<Int, IntArray>>()
        val perOpp = HashMap<String, HashMap<String, IntArray>>()
        for (c in candidates) {
            perSeed[c.slotId] = HashMap<Int, IntArray>().apply {
                for (s in seeds) put(s, IntArray(3))
            }
            perOpp[c.slotId] = HashMap()
        }

        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val parts = r.getString("match_id").split("|")
            if (parts.size != 3) continue
            val (sid, oid, seedStr) = parts
            val seed = seedStr.toIntOrNull() ?: continue
            val buckets = perSeed[sid] ?: continue

            val winner = if (r.isNull("winner_slot")) null else r.getString("winner_slot")
            val idx = when (winner) {
                null -> 2
                sid -> 0
                else -> 1
            }
            buckets[seed]?.let { it[idx]++ }
            perOpp[sid]!!.getOrPut(oid) { IntArray(3) }[idx]++
        }

        return candidates.associate { c ->
            val rows = seeds.map { perSeed[c.slotId]!![it]!! }
            val wins = rows.map { it[0] }
            c.slotId to Score(
                wins = wins.average(),
                losses = rows.map { it[1] }.average(),
                draws = rows.map { it[2] }.average(),
                winsSd = sd(wins),
                seeds = seeds.size,
                opponents = opponents,
                winsBySeed = seeds.zip(wins).toMap(),
                perOpponent = perOpp[c.slotId]!!.mapValues { (_, v) -> Triple(v[0], v[1], v[2]) },
            )
        }
    }

    /** Sample standard deviation, matching Python's statistics.stdev. */
    private fun sd(xs: List<Int>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        return Math.sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
    }
}
