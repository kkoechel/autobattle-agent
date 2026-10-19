package online.autobattle.agent.game

import mobile.Mobile
import online.autobattle.agent.engine.Aggregator
import online.autobattle.agent.engine.Archetype
import online.autobattle.agent.engine.Catalogue
import online.autobattle.agent.engine.Deck
import online.autobattle.agent.engine.Mutate
import online.autobattle.agent.engine.PayloadBuilder
import online.autobattle.agent.engine.Score
import org.json.JSONArray
import org.json.JSONObject

/**
 * Train a deck against the live field, on this phone, and submit the winner.
 *
 * This is the whole game loop. The server holds who is playing and scores the
 * rounds; everything expensive and interesting happens here, because the
 * engine on this device is byte-identical to the one the server scores with.
 *
 * The field is small -- a handful of decks rather than the live game's 86 --
 * which makes the loop far cheaper than the advisor's "build me a deck".
 * Measured on a Galaxy S22 at 150-213 matches/sec: 40 archetypes against 8
 * opponents over 15 seeds is ~4,800 matches, around half a minute.
 */
object Compete {

    /**
     * One random single-card substitution.
     *
     * Copies are drawn from SUCCESSIVE cut candidates rather than one slot,
     * the same way Builder does it. The lowest-ranked cards in a tuned list
     * are usually 1-ofs, so taking a whole playset out of one slot is rarely
     * possible and swapping a single copy barely changes the deck -- the
     * result reads as "nothing happened" for a full generation of compute.
     */
    private fun mutation(
        cards: List<Int>, plan: JSONObject, cat: Catalogue,
        addPool: List<Int>, rnd: kotlin.random.Random,
    ): Mutate.Result? {
        if (addPool.isEmpty()) return null
        val have = cards.groupingBy { it }.eachCount()
        val add = addPool[rnd.nextInt(addPool.size)]
        if (have.containsKey(add)) return null
        var need = minOf(cat.deckLimit(add), 5)
        if (need <= 0) return null

        val cuts = Mutate.cutCandidates(cards, plan)
        val swaps = ArrayList<Mutate.Swap>()
        var ci = 0
        while (need > 0 && ci < cuts.size) {
            val cut = cuts[ci]; ci++
            if (cut == add) continue
            val avail = have[cut] ?: 0
            if (avail <= 0) continue
            val take = minOf(need, avail)
            swaps.add(Mutate.Swap(cut, add, take))
            need -= take
        }
        if (swaps.isEmpty()) return null
        return Mutate.apply(cards, plan, swaps, cat)
    }

    /** Cards two decks share, counting copies. */
    private fun shared(a: List<Int>, b: List<Int>): Int {
        val ca = a.groupingBy { it }.eachCount()
        val cb = b.groupingBy { it }.eachCount()
        return ca.entries.sumOf { (k, v) -> minOf(v, cb[k] ?: 0) }
    }

    data class Progress(val step: String, val done: Int, val total: Int)

    data class Outcome(
        val name: String,
        val cards: List<Int>,
        val plan: JSONObject,
        val wins: Double,
        val opponents: Int,
        val screened: Int,
        val climbed: Int,
        /** True when an originality request could not be met this run. */
        val relaxed: Boolean = false,
    )

    /** Every opponent's current deck, as the engine wants them. */
    private fun opponentsOf(field: JSONArray, meHandle: String?): List<Deck> {
        val out = ArrayList<Deck>()
        for (i in 0 until field.length()) {
            val d = field.getJSONObject(i)
            if (meHandle != null && d.optString("handle") == meHandle) continue
            val entries = d.optJSONArray("cards") ?: continue
            val flat = ArrayList<Int>(100)
            for (j in 0 until entries.length()) {
                val e = entries.getJSONObject(j)
                repeat(e.getInt("quantity")) { flat.add(e.getInt("card_id")) }
            }
            if (flat.isEmpty()) continue
            out.add(Deck("o$i", flat, d.optJSONObject("battle_plan"),
                d.optString("handle"), null))
        }
        return out
    }

    /** The field's decks as a meta array, which Archetype.staples() reads. */
    private fun metaOf(field: JSONArray): JSONArray {
        val meta = JSONArray()
        for (i in 0 until field.length()) {
            val d = field.getJSONObject(i)
            val entries = d.optJSONArray("cards") ?: continue
            val flat = JSONArray()
            for (j in 0 until entries.length()) {
                val e = entries.getJSONObject(j)
                repeat(e.getInt("quantity")) { flat.put(e.getInt("card_id")) }
            }
            meta.put(JSONObject().put("cards", flat)
                .put("battle_plan", d.optJSONObject("battle_plan") ?: JSONObject()))
        }
        return meta
    }

    private fun score(
        cat: Catalogue, arms: List<Deck>, opponents: List<Deck>,
        seeds: List<Int>, workers: Int,
    ): Map<String, Score> {
        val payload = PayloadBuilder(cat.raw).build(arms, opponents, seeds)
        val env = JSONObject(Mobile.run(payload, workers.toLong()))
        return Aggregator.aggregate(
            env.getJSONArray("results").toString(), arms, seeds, opponents.size)
    }

    /** How the deck you already have does against the field as it stands. */
    /**
     * One match the trial actually played, kept so it can be watched.
     *
     * The seed and the seat are both needed: sim() alternates who goes
     * first by seed INDEX, and replaying with the wrong seat produces a
     * different game from the one that was scored.
     */
    data class Game(
        val seed: Int,
        val opponent: String,
        val youFirst: Boolean,
        /** "won", "lost" or "drew", from your side. */
        val outcome: String,
    )

    data class Trial(
        val deckName: String,
        val wins: Double,
        val opponents: Int,
        val seeds: Int,
        val beaten: Int,
        val lostTo: Int,
        val even: Int,
        val bestAgainst: String?,
        val worstAgainst: String?,
        /** Everything played, so a game can be picked out and watched. */
        val games: List<Game> = emptyList(),
        /** What a replay needs, carried rather than re-fetched. */
        val mine: Deck? = null,
        val rivals: List<Deck> = emptyList(),
        val cardDefs: String = "",
        /**
         * id -> the DISPLAY fields: art, rules text, rarity, subtype.
         *
         * The engine's definitions carry none of these, which is correct --
         * it does not need them. But the replay draws its cards with the
         * main game's buildCardSVG, and that reads thumbnail_url/image_url
         * for the art, rules_text for the body and rarity for the frame. Sent
         * separately so the definitions the two ENGINES compute from stay
         * byte-identical.
         */
        val display: String = "",
    )

    /**
     * Play the current deck against the field WITHOUT changing it.
     *
     * The point is to give a player something to do while a round is
     * pending, other than retraining. Rounds are scored every 15 minutes, so
     * the honest answer to "is my deck any good" used to be "wait" -- and
     * the available button threw the deck away and built another, which is
     * the one thing a player wanting to see their deck work should not do.
     *
     * Nothing here writes: no deck is submitted and no directive changes.
     */
    fun testDeck(
        client: GameClient,
        myHandle: String?,
        seedCount: Int = 31,
        workers: Int = 4,
        onProgress: (Progress) -> Unit = {},
    ): Trial {
        onProgress(Progress("Consulting the catalogue", 0, 1))
        val (cardsJson, _) = client.cards()
        val cat = Catalogue(cardsJson)

        onProgress(Progress("Examining the field", 0, 1))
        val field = client.field()
        val opponents = opponentsOf(field, myHandle)
        require(opponents.isNotEmpty()) { "there is nobody to play against yet" }

        val me = client.me().optJSONObject("deck")
            ?: error("you do not have a deck yet")
        val entries = me.optJSONArray("cards") ?: error("your deck is empty")
        val flat = ArrayList<Int>(100)
        for (j in 0 until entries.length()) {
            val e = entries.getJSONObject(j)
            repeat(e.getInt("quantity")) { flat.add(e.getInt("card_id")) }
        }
        val mine = Deck("me", flat, me.optJSONObject("battle_plan"),
            me.optString("name"), null)

        // A seed block none of the training stages use (7k screen, 20k climb,
        // 50k verify). Re-reading the seeds a deck was tuned on would flatter
        // it, which is the same winner's curse the three-block split exists
        // to avoid.
        val block = (0 until seedCount).map { 90_000 + it }
        onProgress(Progress("Playing it out", 0, 1))
        // Run it here rather than through score(), because the per-match
        // rows are what make a game watchable afterwards -- the aggregate
        // cannot say WHICH seed lost.
        val builder = PayloadBuilder(cat.raw)
        val payload = builder.build(listOf(mine), opponents, block)
        val env = JSONObject(Mobile.run(payload, workers.toLong()))
        val raw = env.getJSONArray("results")
        val s = Aggregator.aggregate(raw.toString(), listOf(mine), block, opponents.size)["me"]
            ?: error("the engine returned no result")

        val nameOfSlot = opponents.associate { it.slotId to it.name }
        val seatOf = block.withIndex().associate { (i, seed) -> seed to (i % 2 == 0) }
        val games = ArrayList<Game>(raw.length())
        for (i in 0 until raw.length()) {
            val r = raw.getJSONObject(i)
            val parts = r.getString("match_id").split("|")
            if (parts.size != 3) continue
            val (sid, oid, seedStr) = parts
            if (sid != "me") continue
            val seed = seedStr.toIntOrNull() ?: continue
            val winner = if (r.isNull("winner_slot")) null else r.getString("winner_slot")
            games.add(Game(
                seed = seed,
                opponent = nameOfSlot[oid] ?: oid,
                youFirst = seatOf[seed] ?: true,
                outcome = when (winner) {
                    null -> "drew"
                    sid -> "won"
                    else -> "lost"
                },
            ))
        }

        val handleOf = opponents.associate { it.slotId to it.name }
        var beaten = 0
        var lost = 0
        var even = 0
        var best: String? = null
        var bestMargin = Int.MIN_VALUE
        var worst: String? = null
        var worstMargin = Int.MAX_VALUE
        for ((slot, wld) in s.perOpponent) {
            val (w, l, _) = wld
            if (w > l) beaten++ else if (l > w) lost++ else even++
            val margin = w - l
            val nm = handleOf[slot] ?: slot
            if (margin > bestMargin) { bestMargin = margin; best = nm }
            if (margin < worstMargin) { worstMargin = margin; worst = nm }
        }
        return Trial(me.optString("name"), s.wins, opponents.size, block.size,
            beaten, lost, even, best, worst,
            games = games, mine = mine, rivals = opponents,
            cardDefs = builder.cardDefs, display = displayMap(cardsJson))
    }

    /**
     * id -> the fields buildCardSVG draws with, which the engine does not carry.
     *
     * Shared by both replay paths rather than written twice: the rehearsal and
     * the league replay must draw cards the same way, and two copies of this
     * would drift the first time a field was added.
     */
    private fun displayMap(cardsJson: JSONArray): String =
        JSONObject().apply {
            for (i in 0 until cardsJson.length()) {
                val c = cardsJson.getJSONObject(i)
                val d = JSONObject()
                for (k in listOf("subtype", "rules_text", "rarity",
                                 "image_url", "thumbnail_url", "flavor_text")) {
                    val v = c.optString(k)
                    // optString yields the four-character word "null" for a
                    // JSON null, which would print as card text.
                    if (v.isNotBlank() && v != "null") d.put(k, v)
                }
                if (d.length() > 0) put(c.getInt("id").toString(), d)
            }
        }.toString()

    /**
     * One LEAGUE round, replayed from the decks that actually played it.
     *
     * Different from testDeck in the one way that matters: the decks and the
     * seed block come from the round's snapshot rather than from the field as
     * it stands now, so these are the games the league scored, not fresh ones
     * between today's lists.
     *
     * Per-match outcomes are re-derived here rather than stored server-side.
     * The round was scored by the Go validator on the coordinator and this is
     * the Go engine on the phone, so agreement is expected but not assumed --
     * the aggregate is compared against what the league recorded, and a
     * disagreement is reported instead of being papered over. A replay that
     * silently disagrees with the standings is worse than no replay.
     */
    data class RoundReplay(
        val roundId: Int,
        val finishedAt: String,
        val trial: Trial,
        /** Wins/losses/draws as the LEAGUE recorded them, if it recorded any. */
        val scored: Triple<Int, Int, Int>?,
        /** The same, re-derived on this device. */
        val derived: Triple<Int, Int, Int>,
    ) {
        val agrees: Boolean get() = scored == null || scored == derived
    }

    private fun flatten(entries: JSONArray?): List<Int> {
        val out = ArrayList<Int>(100)
        for (i in 0 until (entries?.length() ?: 0)) {
            val e = entries!!.getJSONObject(i)
            repeat(e.getInt("quantity")) { out.add(e.getInt("card_id")) }
        }
        return out
    }

    fun replayRound(
        client: GameClient,
        myHandle: String?,
        round: Int = 0,
        workers: Int = 4,
        onProgress: (Progress) -> Unit = {},
    ): RoundReplay {
        onProgress(Progress("Consulting the catalogue", 0, 1))
        val (cardsJson, _) = client.cards()
        val cat = Catalogue(cardsJson)

        onProgress(Progress("Opening the record", 0, 1))
        val env = client.replay(round)
        val arr = env.getJSONArray("decks")
        var mine: Deck? = null
        var scored: Triple<Int, Int, Int>? = null
        val rivals = ArrayList<Deck>()
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            val cards = flatten(d.optJSONArray("cards"))
            if (cards.isEmpty()) continue
            if (d.optBoolean("is_me")) {
                mine = Deck("me", cards, d.optJSONObject("battle_plan"),
                    d.optString("deck_name"), null)
                if (!d.isNull("wins")) {
                    scored = Triple(d.optInt("wins"), d.optInt("losses"),
                        d.optInt("draws"))
                }
            } else {
                rivals.add(Deck("o${rivals.size}", cards,
                    d.optJSONObject("battle_plan"), d.optString("handle"), null))
            }
        }
        requireNotNull(mine) { "your deck did not play that round" }
        require(rivals.isNotEmpty()) { "nobody else played that round" }

        // THE ROUND'S OWN SEEDS, not a fresh block. Recorded with the round
        // precisely because they came from an environment variable: replaying
        // on a guess would produce a different game from the one scored.
        val seed0 = env.optInt("seed0")
        val count = env.optInt("seeds")
        require(count > 0) { "that round recorded no seeds" }
        val seeds = (0 until count).map { seed0 + it }

        onProgress(Progress("Playing the round back", 0, 1))
        val builder = PayloadBuilder(cat.raw)
        val payload = builder.build(listOf(mine), rivals, seeds)
        val out = JSONObject(Mobile.run(payload, workers.toLong()))
        val raw = out.getJSONArray("results")
        val s = Aggregator.aggregate(raw.toString(), listOf(mine), seeds, rivals.size)["me"]
            ?: error("the engine returned no result")

        val nameOfSlot = rivals.associate { it.slotId to it.name }
        // Seats alternate by seed INDEX, not by seed value -- the same rule
        // sim() uses, and getting it wrong replays a different game.
        val seatOf = seeds.withIndex().associate { (i, sd) -> sd to (i % 2 == 0) }
        val games = ArrayList<Game>(raw.length())
        for (i in 0 until raw.length()) {
            val r = raw.getJSONObject(i)
            val parts = r.getString("match_id").split("|")
            if (parts.size != 3) continue
            val (sid, oid, seedStr) = parts
            if (sid != "me") continue
            val sd = seedStr.toIntOrNull() ?: continue
            val winner = if (r.isNull("winner_slot")) null else r.getString("winner_slot")
            games.add(Game(sd, nameOfSlot[oid] ?: oid, seatOf[sd] ?: true,
                when (winner) { null -> "drew"; sid -> "won"; else -> "lost" }))
        }

        var beaten = 0; var lost = 0; var even = 0
        var best: String? = null; var bestMargin = Int.MIN_VALUE
        var worst: String? = null; var worstMargin = Int.MAX_VALUE
        for ((slot, wld) in s.perOpponent) {
            val (w, l, _) = wld
            if (w > l) beaten++ else if (l > w) lost++ else even++
            val margin = w - l
            val nm = nameOfSlot[slot] ?: slot
            if (margin > bestMargin) { bestMargin = margin; best = nm }
            if (margin < worstMargin) { worstMargin = margin; worst = nm }
        }

        // Rounded the way bin/round.py rounds, so a comparison against the
        // stored row is a comparison of the same quantity. Draws take the
        // remainder there and must take it here too, or every round would
        // look like a disagreement.
        val dw = Math.round(s.wins).toInt()
        val dl = Math.round(s.losses).toInt()
        val derived = Triple(dw, dl, maxOf(0, rivals.size - dw - dl))

        val trial = Trial(mine.name, s.wins, rivals.size, seeds.size,
            beaten, lost, even, best, worst,
            games = games, mine = mine, rivals = rivals,
            cardDefs = builder.cardDefs, display = displayMap(cardsJson))
        return RoundReplay(env.optInt("round_id"),
            env.optString("finished_at"), trial, scored, derived)
    }

    /**
     * Screen a spread of archetypes, then climb the best one.
     *
     * The climb is what actually pays. Measured against the live field on
     * 2026-10-14: the best deck built cold scored 19.4W where the same search
     * climbed sixteen generations reached 20.7W on unseen seeds. Building and
     * stopping leaves most of the available strength on the table.
     */
    fun train(
        client: GameClient,
        myHandle: String?,
        directive: Directive = Directive(),
        seedCount: Int = directive.seedCount,
        screenSeeds: Int = 15,
        // The climb gets MORE seeds than the design sweep, and they are not
        // the same job. Ranking 36 very different decks is coarse work that
        // 15 seeds settles; deciding whether one swapped card helped is a
        // far smaller effect, and at 8 opponents x 15 seeds it sits inside
        // the noise -- the first run on a real phone reported "improved 0
        // times" because generation 1 could not see any mutation as better.
        // Measured the same day on the live field: a saturated or coarse
        // screen cannot select improvements, and the plateau it reports
        // belongs to the instrument, not the deck.
        climbSeeds: Int = 31,
        generations: Int = directive.generations,
        mutationsPerGen: Int = 10,
        // One barren generation is not a plateau. Stopping on the first
        // failure makes the whole climb a coin flip on ten random swaps.
        patience: Int = 2,
        workers: Int = 4,
        onProgress: (Progress) -> Unit = {},
    ): Outcome {
        onProgress(Progress("Consulting the catalogue", 0, 1))
        val (cardsJson, banned) = client.cards()
        val cat = Catalogue(cardsJson)

        onProgress(Progress("Examining the field", 0, 1))
        val field = client.field()
        val opponents = opponentsOf(field, myHandle)
        require(opponents.isNotEmpty()) { "nobody to play against yet" }
        val meta = metaOf(field)
        // Three DISJOINT seed blocks. The sweep picks a winner, the climb
        // judges changes to it, and the verdict is measured on seeds neither
        // of them saw. Overlapping them is how a winner's curse gets quoted
        // to the player as fact: the best of 36 decks measured on 15 seeds is
        // biased upward by the selection itself, and re-reading the same
        // seeds confirms the bias instead of testing it. The first device run
        // reported 3.8 wins and the server independently scored the same deck
        // at 1.
        val seeds = (0 until screenSeeds).map { 7_000 + it }

        // Banned cards are excluded HERE as well as rejected by the server.
        // Letting the generator build around a banned card would mean a long
        // local search ending in a 400, which reads as a bug to the player.
        val allSeeds = (0 until cardsJson.length())
            .map { cardsJson.getJSONObject(it).getInt("id") }
            .filter { it !in banned && cat.isPlayable(it) }
            .sorted()
        // A theme narrows what the agent builds AROUND, not what it may use:
        // the seed carries the theme and the rest of the deck is still chosen
        // on merit. Narrowing the whole pool would mean 79 poison cards and
        // nothing to support them.
        // Any of the chosen tags makes a card a candidate seed. Requiring
        // all of them would empty the pool the moment a player combined two.
        val themed = directive.themes.takeIf { it.isNotEmpty() }?.let { tags ->
            allSeeds.filter { id -> cat.tags(id).any { it in tags } }
        }?.takeIf { it.size >= 8 }
        val cheap = directive.maxSeedCost?.let { mx ->
            (themed ?: allSeeds).filter { cat.cost(it) <= mx }
        }?.takeIf { it.size >= 8 }
        // A card the player agreed to build around is not a preference, it
        // is the instruction: the agent builds THAT deck and spends the whole
        // budget making it work, rather than screening it against 35 others
        // and quietly discarding it.
        val candidateSeeds = when {
            directive.seedCard != null && directive.seedCard in allSeeds ->
                listOf(directive.seedCard)
            cheap != null -> cheap
            themed != null -> themed
            else -> allSeeds
        }
        val stride = maxOf(1, candidateSeeds.size / seedCount)
        val picked = candidateSeeds.filterIndexed { i, _ -> i % stride == 0 }.take(seedCount)

        val built = ArrayList<Archetype.Built>()
        picked.forEachIndexed { i, s ->
            onProgress(Progress("Drafting", i + 1, picked.size))
            val b = Archetype.build(s, cat, meta,
                stapleSlots = directive.stapleSlots, pool = directive.pool,
                // With a named seed the theme no longer picks seeds, so it
                // weighs the support instead -- otherwise "around Turingue,
                // pursuing poison" would change nothing at all.
                bias = directive.themes)
                ?: return@forEachIndexed
            if (b.cards.size != 100 || b.cards.any { it in banned }) return@forEachIndexed
            // Originality is a filter on what may be SUBMITTED, applied before
            // anything is scored: asking for something of our own and then
            // handing back a near-copy because it tested well would make the
            // instruction decorative.
            val cap = directive.maxOverlap
            if (cap != null && opponents.any { o -> shared(b.cards, o.cards) > cap }) {
                return@forEachIndexed
            }
            built.add(b)
        }
        // Falling back is better than failing: in a small field an originality
        // cap can reject everything, and "your agent did nothing" is a worse
        // answer than "it could not avoid the field this time".
        var relaxed = false
        if (built.isEmpty() && directive.maxOverlap != null) {
            relaxed = true
            picked.forEach { s ->
                val b = Archetype.build(s, cat, meta,
                    stapleSlots = directive.stapleSlots, pool = directive.pool,
                    bias = directive.themes)
                    ?: return@forEach
                if (b.cards.size == 100 && b.cards.none { it in banned }) built.add(b)
            }
        }
        require(built.isNotEmpty()) { "could not build a legal deck" }

        // Targeting changes who the agent SELECTS against, never who it is
        // finally judged against -- a deck built to beat one rival routinely
        // loses ground to the rest, and the verdict has to be able to say so.
        val selectAgainst = directive.target
            ?.let { t -> opponents.filter { it.name == t } }
            ?.takeIf { it.isNotEmpty() } ?: opponents

        onProgress(Progress("Comparing ${built.size} drafts", 0, 1))
        val arms = built.mapIndexed { i, b -> Deck("c$i", b.cards, b.plan, b.seedName, null) }
        val screened = score(cat, arms, selectAgainst, seeds, workers)
        var bestI = arms.indices.maxByOrNull { screened["c$it"]?.wins ?: -1.0 }!!
        var cur = built[bestI].cards
        var curPlan = built[bestI].plan
        var curWins = screened["c$bestI"]!!.wins
        val name = built[bestI].seedName

        val addPool = candidateSeeds
        val rnd = kotlin.random.Random(System.nanoTime())

        // Climb: each generation keeps the best mutation that beats the
        // current deck, and stops the moment one does not. Stopping early is
        // the common case near a local optimum and costs nothing.
        var climbed = 0
        var barren = 0
        val climbBlock = (0 until climbSeeds).map { 20_000 + it }
        for (g in 1..generations) {
            onProgress(Progress("Refining, pass $g of $generations", g, generations))
            val kids = ArrayList<Pair<List<Int>, JSONObject>>()
            repeat(mutationsPerGen) {
                val m = mutation(cur, curPlan, cat, addPool, rnd)
                if (m != null && m.cards.none { c -> c in banned }) {
                    kids.add(m.cards to m.plan)
                }
            }
            if (kids.isEmpty()) break
            val gArms = ArrayList<Deck>()
            gArms.add(Deck("cur", cur, curPlan, name, null))
            kids.forEachIndexed { i, k -> gArms.add(Deck("k$i", k.first, k.second, name, null)) }
            val s = score(cat, gArms, selectAgainst, climbBlock, workers)
            val base = s["cur"]?.wins ?: break
            val bi = kids.indices.maxByOrNull { s["k$it"]?.wins ?: -1.0 } ?: break
            val bw = s["k$bi"]?.wins ?: break
            if (bw <= base) {
                barren++
                if (barren >= patience) break
                continue
            }
            barren = 0
            cur = kids[bi].first
            curPlan = kids[bi].second
            curWins = bw
            climbed++
        }

        // ALWAYS re-measure, on seeds used by neither stage. Quoting the
        // block a deck was selected on reports the selection, not the deck.
        onProgress(Progress("Verifying", 1, 1))
        val verify = (0 until climbSeeds).map { 50_000 + it }
        val finalWins = score(cat, listOf(Deck("fin", cur, curPlan, name, null)),
            opponents, verify, workers)["fin"]?.wins ?: curWins
        return Outcome(name, cur, curPlan, finalWins, opponents.size, built.size,
            climbed, relaxed)
    }
}
