package online.autobattle.agent.chat

import mobile.Mobile
import online.autobattle.agent.engine.Aggregator
import online.autobattle.agent.engine.Archetype
import online.autobattle.agent.engine.Catalogue
import online.autobattle.agent.engine.Deck
import online.autobattle.agent.engine.Mutate
import online.autobattle.agent.engine.Names
import online.autobattle.agent.engine.PayloadBuilder
import online.autobattle.agent.engine.Score
import online.autobattle.agent.engine.Stats
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Build me a poison deck" -- generate candidates two ways, measure both, and
 * return whichever actually won.
 *
 * Both generators are here because they fail in opposite directions and the
 * difference is large enough to decide the feature.
 *
 *   from a theme    coherent, naive, and WEAK: 12-16 wins of 24 screened
 *   from a shell    a working meta list with themed cards swapped in: 21 of 24
 *
 * For scale, the tuned deck this player already owns takes 65 of 76 live. So
 * handing back a from-scratch archetype and calling it a recommendation would
 * be handing them something materially worse than what they have. The theme
 * build is still generated -- occasionally it wins, and when it loses the
 * margin is the most honest thing we can show -- but the shell mutation is
 * what usually survives.
 */
class Builder(
    private val cat: Catalogue,
    private val metaDecks: JSONArray,
) {

    /** Copies of each themed card a mutation aims to place. */
    private val PER_THEME_CARD = 5

    data class Candidate(
        val name: String,
        val origin: String,          // "theme" or a shell's deck name
        val cards: List<Int>,
        val plan: JSONObject,
        val seedCard: Int,
        /**
         * The cards that actually carry the requested theme.
         *
         * Shown instead of the deck's most numerous cards. A mutation puts 15
         * themed copies into a 100-card shell, so ranking by quantity lists
         * the SHELL back to the player: "build me a poison deck" returned
         * Venom Blight with Men of Artifice, War Profiteer and Treasure Horde
         * at the top and no poison card anywhere in sight.
         */
        val themeCards: List<Int>,
    )

    data class Outcome(
        val best: Candidate,
        val score: Score,
        /** Best deck built purely from the theme, and best themed mutation. */
        val bestTheme: Pair<Candidate, Score>?,
        val bestShell: Pair<Candidate, Score>?,
        val considered: Int,
        val opponents: Int,
        val seeds: Int,
        val elapsedMs: Long,
        /** The field's strongest deck, measured on THIS screen. */
        val controlName: String,
        val control: Score,
        /** Paired mean difference against the control, over shared seeds. */
        val gain: Double,
        val t: Double,
    ) {
        /**
         * Whether the margin survives the project's own acceptance gate.
         *
         * A raw win difference is not a finding. At 9 seeds the standard error
         * is over a win, so the +0.6 this feature first reported in green had
         * t of about 0.5 -- indistinguishable from the field's best deck, and
         * presented as beating it.
         */
        val beatsField: Boolean get() = Stats.accept(gain, t)
    }

    /** Cards carrying the theme, strongest headline first. */
    fun seedsFor(tag: String, limit: Int = 8): List<Int> =
        cat.byId.keys
            .filter { cat.isPlayable(it) && tag in cat.tags(it) && cat.rulesText(it).isNotEmpty() }
            // deck_limit first: a card you may run fifteen of can actually
            // define a list, where a 1-of cannot no matter how strong it is.
            // Then cost, so the headline is castable rather than aspirational.
            .sortedWith(compareByDescending<Int> { cat.deckLimit(it) }
                .thenBy { cat.cost(it) }.thenBy { it })
            .take(limit)

    /**
     * Shells to mutate: the strongest decks in the field.
     *
     * Ranked by the meta's own record rather than by anything we compute --
     * these are live results over many cohorts, which is a better estimate of
     * a shell's strength than a 3-seed screen could ever be.
     */
    private fun shells(n: Int): List<Pair<String, JSONObject>> =
        (0 until metaDecks.length()).map { metaDecks.getJSONObject(it) }
            .sortedWith(compareByDescending<JSONObject> { it.optDouble("win_pct", 0.0) }
                .thenBy { it.optInt("deck_id", 0) })
            .take(n)
            .map { (it.optString("deck_name").ifBlank { "deck ${it.optInt("deck_id")}" }) to it }

    fun candidates(tag: String, themeCount: Int = 4, shellCount: Int = 4): List<Candidate> {
        val out = ArrayList<Candidate>()
        val seeds = seedsFor(tag, themeCount * 2)

        for (s in seeds.take(themeCount)) {
            val b = Archetype.build(s, cat, metaDecks) ?: continue
            out.add(Candidate(
                // The REQUESTED tag leads the name. Naming from the seed's own
                // tags alone produced "Verdant Fury" for a poison request,
                // because the seed happened to carry life and melee too.
                name = Names.forTheme(listOf(tag) + b.theme.filter { it != tag }, b.seedName),
                origin = "theme", cards = b.cards, plan = b.plan, seedCard = s,
                themeCards = b.picks.map { it.first }.filter { tag in cat.tags(it) },
            ))
        }

        // Mutations: put the theme's best cards into a list that already
        // works, cutting whatever that list itself ranked last.
        //
        // Enough copies to matter. The first version swapped one card per
        // slot, because the lowest-ranked cards in a tuned list are usually
        // 1-ofs -- so "build me a poison deck" returned Hymn with three single
        // cards changed and called it Venom Blight. Copies are now taken from
        // successive cut candidates until each themed card reaches its target.
        val themed = seeds.take(3)
        for ((shellName, d) in shells(shellCount)) {
            val cards = d.getJSONArray("cards").let { a -> (0 until a.length()).map { a.getInt(it) } }
            val plan = d.optJSONObject("battle_plan") ?: JSONObject()
            val cuts = Mutate.cutCandidates(cards, plan)
            val have = cards.groupingBy { it }.eachCount()

            val swaps = ArrayList<Mutate.Swap>()
            var ci = 0
            for (add in themed) {
                var need = minOf(PER_THEME_CARD, cat.deckLimit(add))
                while (need > 0 && ci < cuts.size) {
                    val cut = cuts[ci]
                    ci++
                    if (cut == add) continue
                    val avail = have[cut] ?: 0
                    if (avail <= 0) continue
                    val take = minOf(need, avail)
                    swaps.add(Mutate.Swap(cut, add, take))
                    need -= take
                }
            }
            val m = Mutate.apply(cards, plan, swaps, cat) ?: continue
            val added = m.swaps.sumOf { it.qty }
            out.add(Candidate(
                name = "${Names.forTheme(listOf(tag), tag)} (on $shellName)",
                origin = shellName, cards = m.cards, plan = m.plan,
                seedCard = themed.firstOrNull() ?: 0,
                themeCards = m.swaps.map { it.add },
            ))
        }
        return out
    }

    /**
     * Screen every candidate on one shared block of seeds, then report.
     *
     * Opponents are subsampled with a stride rather than truncated, so the
     * screen spans the field's whole strength range instead of only its top.
     * Every candidate meets the same opponents on the same seeds, which is
     * what makes the ordering between them meaningful even though the screen
     * is far too small to accept any single one as an improvement.
     */
    fun run(
        tag: String,
        opponentCount: Int = 24,
        seedCount: Int = 9,
        workers: Int = 4,
        onProgress: (String) -> Unit = {},
    ): Outcome? {
        val started = System.currentTimeMillis()
        val cands = candidates(tag)
        if (cands.isEmpty()) return null
        onProgress("Generated ${cands.size} candidates; simulating\u2026")

        val all = (0 until metaDecks.length()).map { metaDecks.getJSONObject(it) }
        if (all.isEmpty()) return null

        // The control arm is the field's strongest deck, run through the SAME
        // screen as the candidates rather than compared by its live win_pct.
        // Scaling a live percentage onto a 24-deck sample would be comparing
        // two different measurements, and the census made exactly that mistake
        // once already: a cluster of cards tied at "+0.5" turned out to be the
        // going rate for a blank, visible only once a control was added.
        val control = all.maxWithOrNull(
            compareBy<JSONObject> { it.optDouble("win_pct", 0.0) }.thenByDescending { it.optInt("deck_id", 0) })!!
        val controlId = control.optInt("deck_id", -1)

        val pool = all.filter { it.optInt("deck_id", -2) != controlId }
        val stride = maxOf(1, pool.size / opponentCount)
        val chosen = pool.filterIndexed { i, _ -> i % stride == 0 }.take(opponentCount)
        if (chosen.isEmpty()) return null

        fun deckOf(d: JSONObject, slot: String): Deck {
            val a = d.getJSONArray("cards")
            return Deck(slot, (0 until a.length()).map { a.getInt(it) },
                d.optJSONObject("battle_plan"), d.optString("deck_name"), d.optInt("deck_id", -1))
        }

        val opponents = chosen.mapIndexed { i, d -> deckOf(d, "O$i") }
        val seedList = (0 until seedCount).map { 2_000_000 + it }

        val arms = ArrayList<Deck>()
        cands.forEachIndexed { i, c -> arms.add(Deck("C$i", c.cards, c.plan, c.name, null)) }
        arms.add(deckOf(control, "CTRL"))

        val payload = PayloadBuilder(cat.raw).build(arms, opponents, seedList)
        val env = JSONObject(Mobile.run(payload, workers.toLong()))
        val scored = Aggregator.aggregate(
            env.getJSONArray("results").toString(), arms, seedList, opponents.size)

        val ranked = cands.mapIndexed { i, c -> c to scored["C$i"]!! }
            // Exactly the live tie-break: wins descending, then losses
            // ascending. Verified against a 70-deck cohort with 0 violations
            // across all 69 adjacent pairs.
            .sortedWith(compareByDescending<Pair<Candidate, Score>> { it.second.wins }
                .thenBy { it.second.losses })

        val ctrl = scored["CTRL"]!!
        val (gain, t) = Stats.pairedT(ranked[0].second, ctrl)

        return Outcome(
            best = ranked[0].first, score = ranked[0].second,
            bestTheme = ranked.firstOrNull { it.first.origin == "theme" },
            bestShell = ranked.firstOrNull { it.first.origin != "theme" },
            considered = cands.size, opponents = opponents.size, seeds = seedCount,
            elapsedMs = System.currentTimeMillis() - started,
            controlName = control.optString("deck_name").ifBlank { "the field's best" },
            control = ctrl, gain = gain, t = t,
        )
    }
}
