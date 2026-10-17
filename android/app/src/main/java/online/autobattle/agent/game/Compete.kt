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

    data class Progress(val step: String, val done: Int, val total: Int)

    data class Outcome(
        val name: String,
        val cards: List<Int>,
        val plan: JSONObject,
        val wins: Double,
        val opponents: Int,
        val screened: Int,
        val climbed: Int,
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
        seedCount: Int = 36,
        screenSeeds: Int = 15,
        generations: Int = 8,
        mutationsPerGen: Int = 10,
        workers: Int = 4,
        onProgress: (Progress) -> Unit = {},
    ): Outcome {
        onProgress(Progress("Fetching cards", 0, 1))
        val (cardsJson, banned) = client.cards()
        val cat = Catalogue(cardsJson)

        onProgress(Progress("Fetching the field", 0, 1))
        val field = client.field()
        val opponents = opponentsOf(field, myHandle)
        require(opponents.isNotEmpty()) { "nobody to play against yet" }
        val meta = metaOf(field)
        val seeds = (0 until screenSeeds).map { 7000 + it }

        // Banned cards are excluded HERE as well as rejected by the server.
        // Letting the generator build around a banned card would mean a long
        // local search ending in a 400, which reads as a bug to the player.
        val candidateSeeds = (0 until cardsJson.length())
            .map { cardsJson.getJSONObject(it).getInt("id") }
            .filter { it !in banned && cat.isPlayable(it) }
            .sorted()
        val stride = maxOf(1, candidateSeeds.size / seedCount)
        val picked = candidateSeeds.filterIndexed { i, _ -> i % stride == 0 }.take(seedCount)

        val built = ArrayList<Archetype.Built>()
        picked.forEachIndexed { i, s ->
            onProgress(Progress("Designing decks", i + 1, picked.size))
            val b = Archetype.build(s, cat, meta) ?: return@forEachIndexed
            if (b.cards.size == 100 && b.cards.none { it in banned }) built.add(b)
        }
        require(built.isNotEmpty()) { "could not build a legal deck" }

        onProgress(Progress("Testing ${built.size} decks", 0, 1))
        val arms = built.mapIndexed { i, b -> Deck("c$i", b.cards, b.plan, b.seedName, null) }
        val screened = score(cat, arms, opponents, seeds, workers)
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
        for (g in 1..generations) {
            onProgress(Progress("Improving (round $g)", g, generations))
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
            val s = score(cat, gArms, opponents, seeds, workers)
            val base = s["cur"]?.wins ?: break
            val bi = kids.indices.maxByOrNull { s["k$it"]?.wins ?: -1.0 } ?: break
            val bw = s["k$bi"]?.wins ?: break
            if (bw <= base) break
            cur = kids[bi].first
            curPlan = kids[bi].second
            curWins = bw
            climbed++
        }

        return Outcome(name, cur, curPlan, curWins, opponents.size, built.size, climbed)
    }
}
