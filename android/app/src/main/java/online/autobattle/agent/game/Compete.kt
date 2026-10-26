package online.autobattle.agent.game

import mobile.Mobile
import online.autobattle.agent.engine.Aggregator
import online.autobattle.agent.engine.Archetype
import online.autobattle.agent.engine.Catalogue
import online.autobattle.agent.engine.Deck
import online.autobattle.agent.engine.Mutate
import online.autobattle.agent.engine.PayloadBuilder
import online.autobattle.agent.engine.Score
import online.autobattle.agent.engine.Stats
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

    private fun fmt(w: Double): String = String.format("%.1fW", w)

    /**
     * A mutation as the player sees it: what went in, what came out.
     *
     * A mutation adds copies of ONE card and takes them from whichever cards
     * the deck already ranked lowest, so the cuts can be several -- joined
     * here rather than shown as separate rows, because "Apothecary and Elvish
     * Experiment out" is one decision, not two.
     */
    private fun attemptOf(m: Mutate.Result, cat: Catalogue): Attempt {
        val addQty = m.swaps.sumOf { it.qty }
        val addId = m.swaps.firstOrNull()?.add
        val adds = if (addId == null) "?" else "${cat.name(addId)} x$addQty"
        val cuts = m.swaps.groupBy { it.cut }
            .map { (cid, sw) -> "${cat.name(cid)} x${sw.sumOf { it.qty }}" }
            .joinToString(", ")
        return Attempt(adds, cuts)
    }

    /** Cards two decks share, counting copies. */
    private fun shared(a: List<Int>, b: List<Int>): Int {
        val ca = a.groupingBy { it }.eachCount()
        val cb = b.groupingBy { it }.eachCount()
        return ca.entries.sumOf { (k, v) -> minOf(v, cb[k] ?: 0) }
    }

    data class Progress(
        val step: String,
        val done: Int,
        val total: Int,
        /**
         * What is actually on the bench this pass, for the player to watch.
         *
         * Null for the stages that genuinely are one long wait (drafting,
         * verifying); only the refine loop has something to show, because
         * only it works card by card.
         */
        val bench: Bench? = null,
    )

    /**
     * One refinement pass, as a person would see it happen.
     *
     * WHY THIS EXISTS. The climb already does, every pass, exactly what the
     * dojo fiction describes: it picks up ten candidate swaps, tries each one
     * in the deck, measures them, and keeps one or puts them all back. All of
     * that was thrown away and replaced with the string "Refining, pass 3 of
     * 12" -- two minutes of blank progress bar over the most interesting work
     * the app does. Nothing here is invented or paced for effect: the
     * attempts are the real candidates, the numbers are the real measurement,
     * and `kept` is the real verdict.
     *
     * Emitted TWICE per pass: once with the attempts laid out and no scores,
     * once with the scores and the verdict. That is the honest rhythm,
     * because the ten are measured together in a single engine call -- 
     * revealing them one at a time would be a pacing lie.
     */
    data class Bench(
        val pass: Int,
        val of: Int,
        val attempts: List<Attempt>,
        /** Null while they are still being measured. */
        val verdict: String? = null,
    )

    /**
     * A deck the server already measured against this field.
     *
     * Carried with its figure so the player can be told what they are
     * starting from. `wins` is out of `opponents` as of `round`, not a rate:
     * a shell measured against 39 decks is not comparable to one measured
     * against 12, and a percentage would hide that.
     */
    data class Shell(
        val name: String,
        val cards: List<Int>,
        val plan: JSONObject,
        val wins: Double,
        val opponents: Int,
        val round: Int,
        val distinct: Int,
    )

    /**
     * Read GET /shells into Shells.
     *
     * HERE RATHER THAN AT EITHER CALL SITE, because there are two: the
     * picker shows the pool and the training service resolves one by name at
     * build time. Parsed twice is parsed two ways eventually.
     */
    fun readShells(client: GameClient): List<Shell> {
        val arr = client.shells()
        val out = ArrayList<Shell>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val entries = o.optJSONArray("cards") ?: continue
            val cards = ArrayList<Int>()
            for (j in 0 until entries.length()) {
                val e = entries.getJSONObject(j)
                repeat(e.getInt("quantity")) { cards.add(e.getInt("card_id")) }
            }
            // A shell that is not a legal hundred is a server-side bug, and
            // offering it would surface as the player's deck being rejected.
            if (cards.size != 100) continue
            out.add(Shell(
                name = o.optString("name"),
                cards = cards,
                plan = o.optJSONObject("battle_plan") ?: JSONObject(),
                wins = o.optDouble("wins", 0.0),
                opponents = o.optInt("opponents"),
                round = o.optInt("round"),
                distinct = o.optInt("distinct_cards"),
            ))
        }
        return out
    }

    data class Attempt(
        /** "Rickity Power Station x5" */
        val adds: String,
        /** What came out to make room, usually one card. */
        val cuts: String,
        /** Wins against the field, once measured. */
        val wins: Double? = null,
        val kept: Boolean = false,
    )

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

    /**
     * What the plan trial found, and whether it is worth believing.
     *
     * Rows are every ordering that was played, best first, each with its
     * paired gain against the deck's CURRENT plan rather than against a
     * notional default -- the player wants to know whether to change what
     * they have, and that is the only comparison that answers it.
     */
    data class PlanTrial(
        val deckName: String,
        val rows: List<PlanRow>,
        /** The ordering to adopt, or null when nothing earned the change. */
        val best: PlanPrefs?,
        val bestGain: Double,
        val bestT: Double,
        val current: PlanPrefs?,
        val screenSeeds: Int,
        val verifySeeds: Int,
        val opponents: Int,
    )

    data class PlanRow(
        val prefs: PlanPrefs,
        val label: String,
        val wins: Double,
        /** Against the current plan, on the screening block. */
        val gain: Double,
    )

    /**
     * MEASURE the standing orders against the live field instead of deriving
     * them from a table.
     *
     * Every plan field is independent of the hundred cards, so one payload
     * can carry the same card list under every ordering at once: they play
     * the same opponents on the same seeds, which makes this a paired
     * comparison for free rather than N separate trials to be reconciled
     * afterwards.
     *
     * SCREEN THEN VERIFY, on disjoint blocks, because nine orderings against
     * one baseline is nine chances to be fooled. At t >= 2.0 each comparison
     * is about one-in-forty to pass on noise alone, so across nine of them a
     * false winner is better than a one-in-five event -- and adopting one
     * would be exactly the "tuned to its seed block" failure Stats warns
     * about. So the screen only NOMINATES, and the nomination is re-played on
     * seeds it has never seen before the accept gate is applied to it. That
     * is the same three-stage discipline train() uses, for the same reason.
     *
     * Blocks 60k/61k: 7k, 20k, 50k and 90k already belong to the screen, the
     * climb, the verify and the rehearsal, and the league itself plays from
     * 1000. Re-reading seeds a deck was tuned on would flatter it.
     */
    fun tunePlan(
        client: GameClient,
        myHandle: String?,
        screenSeeds: Int = 21,
        verifySeeds: Int = 31,
        workers: Int = 4,
        onProgress: (Progress) -> Unit = {},
    ): PlanTrial {
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
        val livePlan = me.optJSONObject("battle_plan") ?: JSONObject()

        // Every combination. Nine is small enough to play exhaustively and
        // the probe on the live field showed why it must be: tempo swung one
        // deck's record by eight wins while barely touching another's, and a
        // third was flat under everything. Which axis matters is a property
        // of the deck in hand, so there is nothing to prune in advance.
        // THE SUPPLEMENT AXIS ONLY EXISTS IF THE DECK CAN USE IT.
        //
        // Those orders decide whether to pay a card's OPTIONAL extra cost, so
        // a deck holding no supplement cards behaves identically under all
        // three -- measuring them would triple the run to compare a setting
        // against itself, and the "nothing was better" it reported would be
        // arithmetic rather than a finding. Fifteen cards in the pool carry a
        // supplement, so most decks hold none.
        val supplementCards = flat.count { "supplement" in cat.tags(it) }
        val extrasAxis = if (supplementCards > 0) PlanPrefs.Extras.values().toList()
                         else listOf(PlanPrefs.Extras.PAY)
        val variants = ArrayList<Pair<String, PlanPrefs>>()
        for (t in PlanPrefs.Tempo.values()) {
            for (a in PlanPrefs.Aim.values()) {
                for (e in extrasAxis) variants.add(label(t, a, e) to PlanPrefs(t, a, e))
            }
        }

        // "As we play now" is its own slot and the reference for every gain.
        // It is NOT assumed to be one of the nine: a starter deck arrives with
        // whatever plan the starter carried.
        val arms = ArrayList<Deck>()
        arms.add(Deck("cur", flat, JSONObject(livePlan.toString()), "as we play now"))
        variants.forEachIndexed { i, (lab, prefs) ->
            arms.add(Deck("p$i", flat, prefs.applyTo(JSONObject(livePlan.toString())), lab))
        }

        onProgress(Progress("Playing ${variants.size} ways of ordering ourselves", 0, 1))
        val screenBlock = (0 until screenSeeds).map { 60_000 + it }
        val screened = score(cat, arms, opponents, screenBlock, workers)
        val cur = screened["cur"] ?: error("the engine returned no result")

        val rows = variants.mapIndexedNotNull { i, (lab, prefs) ->
            val sc = screened["p$i"] ?: return@mapIndexedNotNull null
            PlanRow(prefs, lab, sc.wins, Stats.pairedT(sc, cur).first)
        }.sortedByDescending { it.wins }

        // The leader NOMINATES; it does not win. Anything that did not even
        // lead the screen cannot be adopted, so only this one is re-played.
        val leader = rows.firstOrNull()
        var best: PlanPrefs? = null
        var gain = 0.0
        var tStat = 0.0
        if (leader != null) {
            onProgress(Progress("Checking ${leader.label} on fresh seeds", 0, 1))
            val verifyBlock = (0 until verifySeeds).map { 61_000 + it }
            val again = score(cat, listOf(
                Deck("cur", flat, JSONObject(livePlan.toString()), "as we play now"),
                Deck("win", flat, leader.prefs.applyTo(JSONObject(livePlan.toString())),
                     leader.label),
            ), opponents, verifyBlock, workers)
            val a = again["win"]
            val b = again["cur"]
            if (a != null && b != null) {
                val (g, t) = Stats.pairedT(a, b)
                gain = g
                tStat = t
                // The same gate the rest of the app uses for advice. A plan
                // that cannot clear it is not an improvement a player would
                // ever see in a placement, and churning their deck for it
                // would be superstition with a progress bar.
                if (Stats.accept(g, t)) best = leader.prefs
            }
        }

        return PlanTrial(me.optString("name"), rows, best, gain, tStat,
            readPrefs(livePlan), screenSeeds, verifySeeds, opponents.size)
    }

    /** A pairing named the way the player was asked about it. */
    private fun label(t: PlanPrefs.Tempo, a: PlanPrefs.Aim,
                      e: PlanPrefs.Extras = PlanPrefs.Extras.PAY): String {
        val tl = when (t) {
            PlanPrefs.Tempo.PRESS -> "press"
            PlanPrefs.Tempo.BUILD -> "build to one big turn"
            PlanPrefs.Tempo.NO_WEAK_TURNS -> "no weak turns"
        }
        val al = when (a) {
            PlanPrefs.Aim.MOST_DAMAGE -> "damage furthest"
            PlanPrefs.Aim.CLEAR_THE_BOARD -> "clear their board"
            PlanPrefs.Aim.THEIR_BEST -> "their best card"
        }
        val el = when (e) {
            PlanPrefs.Extras.PAY -> ""
            PlanPrefs.Extras.NEVER -> ", no extras"
            PlanPrefs.Extras.SPARE_ONLY -> ", extras when flush"
        }
        return "$tl, $al$el"
    }

    /**
     * Which of the nine the deck is playing now, if any.
     *
     * Only for display -- the trial measures against the live plan object
     * itself, so a deck carrying something outside the nine is still compared
     * honestly. Null means "not one of the orderings we can offer".
     */
    private fun readPrefs(plan: JSONObject): PlanPrefs? {
        val hold = plan.optInt("energy_hold", 0)
        val scope = plan.optString("energy_hold_scope", "every_turn")
        val co = plan.optBoolean("card_order_hold", false)
        val aimField = plan.optString("target_preference", "")
        val tempo = PlanPrefs.Tempo.values().firstOrNull {
            it.hold == hold && it.protectBest == co &&
                (hold == 0 || it.scope == scope)
        } ?: return null
        val aim = PlanPrefs.Aim.values().firstOrNull { it.field == aimField } ?: return null
        val off = plan.optString("supplements", "") == "off"
        val reserve = plan.optInt("supplement_reserve", 0)
        val extras = PlanPrefs.Extras.values().firstOrNull {
            it.off == off && it.reserve == reserve
        } ?: return null
        return PlanPrefs(tempo, aim, extras)
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
        /**
         * HOW it ended: "health", "deck_out", "honor", "dishonor" or "draw".
         *
         * The engine has always reported this, along with the tallies below,
         * and nothing read them. They are what lets a player ask for a game
         * that SHOWS something rather than one that merely went a certain
         * way, and they cost nothing -- they arrive with the result.
         */
        val reason: String = "",
        val turns: Int = 0,
        /**
         * What that side DID, and the units differ -- read engine.go's
         * withStats() before using them for anything but picking a game.
         *
         *   bestTurn / poisonTurn   a PER-TURN PEAK, not a total: the
         *                           biggest single turn, measured as the
         *                           snapshot delta against the opponent at
         *                           each turn boundary.
         *   mill / honor            cumulative totals.
         *
         * All four are the MAX across the two games of the best-of-2, not a
         * sum. Attribution verified across 2,646 matches of round 255 -- the
         * winner holds the higher number in 626/700 deck_out, 779/850 health
         * and 57/57 honor finishes -- and the exceptions are what a
         * best-of-2 peak allows, which is why these choose a game to WATCH
         * and are never presented as the story of the match.
         */
        val yourBestTurn: Int = 0,
        val theirBestTurn: Int = 0,
        val yourPoisonTurn: Int = 0,
        val yourMill: Int = 0,
        val yourHonor: Int = 0,
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
     * "Watch a game that demonstrates X", derived from the games in hand.
     *
     * Built from what was actually played rather than from a fixed menu, so
     * an option that would match nothing is never offered -- the alternative
     * is a button that silently does nothing, which reads as a broken app
     * rather than as an empty set.
     *
     * Where a label is comparative ("the longest") it picks the extreme
     * rather than filtering on a threshold. A fixed threshold ages badly:
     * games ran 30 to 198 turns in one round of this league and there is no
     * reason the next format should sit in the same band.
     */
    data class Pick(val label: String, val why: String, val game: Game)

    fun picks(games: List<Game>): List<Pick> {
        if (games.isEmpty()) return emptyList()
        val out = ArrayList<Pick>()
        fun add(label: String, why: String, g: Game?) {
            if (g != null) out.add(Pick(label, why, g))
        }
        games.filter { it.outcome == "won" }.randomOrNull()
            ?.let { add("We won", "A match we took.", it) }
        games.filter { it.outcome == "lost" }.randomOrNull()
            ?.let { add("We lost", "A match that got away.", it) }
        add("The longest", "It went ${games.maxOf { it.turns }} turns.",
            games.maxByOrNull { it.turns })
        add("The quickest", "Over in ${games.minOf { it.turns }} turns.",
            games.minByOrNull { it.turns })
        games.filter { it.yourPoisonTurn > 0 }.maxByOrNull { it.yourPoisonTurn }
            ?.let { add("Poison at work",
                "${it.yourPoisonTurn} poison in a single turn.", it) }
        games.filter { it.reason == "deck_out" && it.yourMill > it.turns }
            .maxByOrNull { it.yourMill }
            ?.let { add("Decking them out", "We milled ${it.yourMill} cards.", it) }
        games.filter { it.reason == "honor" }.maxByOrNull { it.yourHonor }
            ?.let { add("Won on honour", "Not a drop of damage needed.", it) }
        // Phrased as the TURN it was, because that is what the number is --
        // the biggest single turn, not the damage dealt over the match.
        games.maxByOrNull { it.yourBestTurn }?.takeIf { it.yourBestTurn > 0 }
            ?.let { add("Our biggest turn",
                "${it.yourBestTurn} off them in one turn.", it) }
        games.maxByOrNull { it.theirBestTurn }?.takeIf { it.theirBestTurn > 0 }
            ?.let { add("Their biggest turn",
                "${it.theirBestTurn} off us in one turn.", it) }
        // Two labels landing on the same match reads as a bug, so the first
        // -- the more specific -- keeps it. "Any" is appended AFTER the
        // dedupe and never removed by it: it is the one option that should
        // always be there, and it vanished whenever its draw happened to
        // collide with a named pick.
        return out.distinctBy { it.game.seed to it.game.opponent } +
            Pick("Any", "Whatever comes up.", games.random())
    }

    /**
     * One result row as a Game, with the tallies attributed to YOU.
     *
     * The _a/_b suffixes are POSITIONAL -- verified by running the same two
     * decks in the same two seats under different slot ids and getting
     * identical numbers -- and the payload seats you first on even seed
     * indices, so you are slot_a exactly when youFirst.
     */
    private fun gameOf(r: JSONObject, opponent: String, seed: Int,
                       youFirst: Boolean, mySlot: String): Game {
        val winner = if (r.isNull("winner_slot")) null else r.getString("winner_slot")
        fun mine(k: String) = r.optInt(if (youFirst) "${k}_a" else "${k}_b")
        fun theirs(k: String) = r.optInt(if (youFirst) "${k}_b" else "${k}_a")
        return Game(
            seed = seed, opponent = opponent, youFirst = youFirst,
            outcome = when (winner) { null -> "drew"; mySlot -> "won"; else -> "lost" },
            reason = r.optString("reason"),
            turns = r.optInt("turns"),
            yourBestTurn = mine("dmg"), theirBestTurn = theirs("dmg"),
            yourPoisonTurn = mine("poison"), yourMill = mine("mill"),
            yourHonor = mine("honor"),
        )
    }

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
        /** Play this out instead of the entered deck. See `mine` below. */
        bench: Workbench? = null,
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

        // THE DECK UNDER TEST, which is not necessarily the entered one.
        //
        // This read `client.me().deck` unconditionally, which quietly meant
        // you could only rehearse an idea you had ALREADY committed to --
        // the opposite of what a rehearsal is for. A bench deck is exactly
        // the case worth playing out, because nothing has been risked on it
        // yet.
        val mine = if (bench != null) {
            Deck("me", bench.cards, bench.plan, bench.name, null)
        } else {
            val me = client.me().optJSONObject("deck")
                ?: error("you do not have a deck yet")
            val entries = me.optJSONArray("cards") ?: error("your deck is empty")
            val flat = ArrayList<Int>(100)
            for (j in 0 until entries.length()) {
                val e = entries.getJSONObject(j)
                repeat(e.getInt("quantity")) { flat.add(e.getInt("card_id")) }
            }
            Deck("me", flat, me.optJSONObject("battle_plan"), me.optString("name"), null)
        }

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
            games.add(gameOf(r, nameOfSlot[oid] ?: oid, seed,
                seatOf[seed] ?: true, sid))
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
        return Trial(mine.name.ifBlank { "your deck" }, s.wins, opponents.size, block.size,
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
            games.add(gameOf(r, nameOfSlot[oid] ?: oid, sd,
                seatOf[sd] ?: true, sid))
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
        /**
         * Start from this instead of drafting.
         *
         * A QUICK BUILD IS THE SAME CLIMB, SHORTER. The expensive stages are
         * skipped -- the seed pool, the drafts, the screen that ranks them --
         * because the server has already done that work against this field.
         * Everything after is untouched: the same mutation pool, the same
         * bench the player watches, the same verify on seeds nothing was
         * selected on. So a quick build is as honest as a deep one; there is
         * simply less of it.
         */
        shell: Shell? = null,
        mutationsPerGen: Int = 10,
        // One barren generation is not a plateau. Stopping on the first
        // failure makes the whole climb a coin flip on ten random swaps.
        patience: Int = 2,
        workers: Int = 4,
        onProgress: (Progress) -> Unit = {},
    ): Outcome {
        onProgress(Progress("Consulting the catalogue", 0, 1))
        val (cardsJson, banned) = client.cards()
        // Ban-aware, so the generator cannot pick a card that would make
        // the deck illegal -- rather than building freely and discarding the
        // result afterwards, which is how a build came to fail outright.
        val cat = Catalogue(cardsJson, banned)

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
        // A SHELL DRAFTS NOTHING. The pool above is still computed because
        // the climb reaches into `allSeeds` for what it may add; the drafting
        // that follows is the expensive part and a shell replaces its whole
        // output, so there is nothing to draft.
        val usingShell = shell != null && shell.cards.none { it in banned }
        val picked = if (usingShell) emptyList()
                     else candidateSeeds.filterIndexed { i, _ -> i % stride == 0 }.take(seedCount)

        val built = ArrayList<Archetype.Built>()
        picked.forEachIndexed { i, s ->
            onProgress(Progress("Drafting", i + 1, picked.size))
            val b = Archetype.build(s, cat, meta,
                stapleSlots = directive.stapleSlots, pool = directive.pool,
                // With a named seed the theme no longer picks seeds, so it
                // weighs the support instead -- otherwise "around Turingue,
                // pursuing poison" would change nothing at all.
                bias = directive.themes, prefs = directive.plan)
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
        if (!usingShell && built.isEmpty() && directive.maxOverlap != null) {
            relaxed = true
            picked.forEach { s ->
                val b = Archetype.build(s, cat, meta,
                    stapleSlots = directive.stapleSlots, pool = directive.pool,
                    bias = directive.themes, prefs = directive.plan)
                    ?: return@forEach
                if (b.cards.size == 100 && b.cards.none { it in banned }) built.add(b)
            }
        }
        require(built.isNotEmpty() || usingShell) { "could not build a legal deck" }

        // Targeting changes who the agent SELECTS against, never who it is
        // finally judged against -- a deck built to beat one rival routinely
        // loses ground to the rest, and the verdict has to be able to say so.
        val selectAgainst = directive.target
            ?.let { t -> opponents.filter { it.name == t } }
            ?.takeIf { it.isNotEmpty() } ?: opponents

        // NOTHING TO MEASURE YET WHEN A SHELL IS GIVEN. The climb re-scores
        // `cur` alongside its mutations in one payload every pass (see the
        // gArms list below), so the figure carried in here is only a
        // fallback for the case where the very first pass finds no legal
        // mutation. The server's own number is the honest thing to put there
        // -- it is what that deck actually did against this field.
        var cur: List<Int>
        var curPlan: JSONObject
        var curWins: Double
        val name: String
        // A SHELL IS A STARTING POINT, NOT AN EXEMPTION. The climb refuses
        // mutations that add a banned card, but the shell is the BASE and was
        // never checked against the ban list -- so a pool built before a ban
        // handed back a deck the league then refused, after two minutes of
        // work, with nothing but "HTTP 400" to show for it. The server no
        // longer serves such a shell; this is the second line, because the
        // pool can go stale between being fetched and being submitted.
        if (shell != null && !usingShell) {
            onProgress(Progress("${shell.name} has a card that is now banned", 0, 1))
        }
        if (usingShell && shell != null) {
            onProgress(Progress("Reading ${shell.name}", 0, 1))
            cur = shell.cards
            curPlan = shell.plan
            curWins = shell.wins
            name = shell.name
        } else {
            onProgress(Progress("Comparing ${built.size} drafts", 0, 1))
            val arms = built.mapIndexed { i, b -> Deck("c$i", b.cards, b.plan, b.seedName, null) }
            val screened = score(cat, arms, selectAgainst, seeds, workers)
            val bestI = arms.indices.maxByOrNull { screened["c$it"]?.wins ?: -1.0 }!!
            cur = built[bestI].cards
            curPlan = built[bestI].plan
            curWins = screened["c$bestI"]!!.wins
            name = built[bestI].seedName
        }

        // WHAT THE CLIMB MAY ADD IS NOT WHAT IT BUILDS AROUND.
        //
        // This was `candidateSeeds`, and that silently disabled refinement in
        // the case the agent pushes hardest. Building around a named card
        // collapses candidateSeeds to exactly that card, so every mutation
        // tried to add a card the deck already held, mutation() returned null
        // every time, `kids` came back empty and the climb broke on pass 1
        // having done NOTHING. The run then reported "improved 0 times",
        // which reads as "tried and found nothing" rather than "never tried",
        // and the whole build finished in under two seconds. Found by trying
        // to WATCH the refinement: there was nothing there to watch.
        //
        // A themed build had the same fault in weaker form -- addPool became
        // just the themed cards, which is the "79 poison cards and nothing to
        // support them" failure the seed-pool comment above warns about.
        //
        // The legal universe is the right pool: the seed decides what the
        // deck is about, the climb may reach for anything it is allowed to
        // play. mutation() already skips cards the deck already holds.
        val addPool = allSeeds
        val rnd = kotlin.random.Random(System.nanoTime())

        // Climb: each generation keeps the best mutation that beats the
        // current deck, and stops the moment one does not. Stopping early is
        // the common case near a local optimum and costs nothing.
        var climbed = 0
        var barren = 0
        val climbBlock = (0 until climbSeeds).map { 20_000 + it }
        for (g in 1..generations) {
            // Keep the whole Mutate.Result, not just (cards, plan): `swaps`
            // is what names the cards, and it was being dropped on the floor.
            val kids = ArrayList<Mutate.Result>()
            repeat(mutationsPerGen) {
                val m = mutation(cur, curPlan, cat, addPool, rnd)
                if (m != null && m.cards.none { c -> c in banned }) {
                    kids.add(m)
                }
            }
            if (kids.isEmpty()) break

            // On the bench, before anything is measured.
            val laid = kids.map { k -> attemptOf(k, cat) }
            onProgress(Progress("Refining, pass $g of $generations", g, generations,
                Bench(g, generations, laid)))

            val gArms = ArrayList<Deck>()
            gArms.add(Deck("cur", cur, curPlan, name, null))
            kids.forEachIndexed { i, k -> gArms.add(Deck("k$i", k.cards, k.plan, name, null)) }
            val s = score(cat, gArms, selectAgainst, climbBlock, workers)
            val base = s["cur"]?.wins ?: break
            val bi = kids.indices.maxByOrNull { s["k$it"]?.wins ?: -1.0 } ?: break
            val bw = s["k$bi"]?.wins ?: break
            val better = bw > base

            // The same bench again, now with what each one actually did and
            // which (if any) earned its place.
            val scored = kids.mapIndexed { i, k ->
                attemptOf(k, cat).copy(wins = s["k$i"]?.wins,
                                       kept = better && i == bi)
            }
            onProgress(Progress("Refining, pass $g of $generations", g, generations,
                Bench(g, generations, scored,
                      verdict = if (better)
                          "kept ${scored[bi].adds} (${fmt(bw)} against ${fmt(base)})"
                      else "none beat ${fmt(base)} \u2014 all of them go back")))

            if (!better) {
                barren++
                if (barren >= patience) break
                continue
            }
            barren = 0
            cur = kids[bi].cards
            curPlan = kids[bi].plan
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
        // built.size is 0 for a quick build, which is the truth: it drafted
        // nothing. The caller says "started from <name>" rather than
        // "considered N drafts".
    }
}
