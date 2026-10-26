package online.autobattle.agent.game

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A deck that has been built but not entered in the league.
 *
 * NAMED Workbench AND NOT Bench because Compete already has a Bench: the row
 * of candidate cards the dojo lays out during a refine pass. Both are honest
 * uses of the word, and the collision is SILENT -- inside Compete, `Bench`
 * resolves to the nested one, so a parameter typed `Bench?` compiles against
 * the wrong type and fails somewhere else entirely.
 *
 * WHY THIS EXISTS. Building used to replace the entered deck outright, so a
 * player with an idea while a round was pending paid for it by throwing away
 * the only thing that would have told them whether the LAST idea worked. The
 * agent holds itself to a stricter rule than that: its climb refuses to swap
 * a card for one that did not measure better (Compete.train, `better = bw >
 * base`). The bench applies the same rule one level up -- a whole deck is not
 * swapped for one that has not been measured against the one it replaces.
 *
 * It is a bench and not a gate. Nothing is locked: the player may enter this
 * deck whenever they like. What has changed is that doing so is now a
 * decision rather than a side effect of pressing "build".
 *
 * ON THE PHONE, DELIBERATELY. The obvious place for it is a second row
 * server-side, and that would be a bug: `awaitingRound` in ui/Arena.kt is a
 * string comparison of the last round's `finished_at` against the deck row's
 * `updated_at`, and that column is ON UPDATE CURRENT_TIMESTAMP. Any write to
 * the deck row -- including one meant only to park a deck here -- would make
 * the app believe a fresh deck was pending and engage the brake against
 * itself. Keeping the bench off the server makes that impossible rather than
 * merely avoided.
 *
 * The cost of that choice is honest: a bench deck does not survive clearing
 * the app's data or moving device. A deck worth keeping goes on the shelf,
 * which is server-side for exactly that reason (GameClient.savedDecks).
 */
data class Workbench(
    /** The deck's name, as the builder chose it. */
    val name: String,
    /** The hundred cards, flat, with repeats -- the shape putDeck wants. */
    val cards: List<Int>,
    val plan: JSONObject,
    /**
     * What it did on seeds it had never seen, and against how many
     * opponents. Carried so the agent can say what this deck is worth
     * WITHOUT replaying it: the figures are already paid for, and a bench
     * deck the player cannot compare is just a deck they have forgotten.
     */
    val wins: Double,
    val opponents: Int,
    /** Round the league was on when it was built, so staleness can be said. */
    val builtAtRound: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("cards", JSONArray(cards))
        .put("plan", plan)
        .put("wins", wins)
        .put("opponents", opponents)
        .put("builtAtRound", builtAtRound)

    companion object {
        private const val PREFS = "abagent"
        private const val KEY = "bench"

        fun load(ctx: Context): Workbench? = try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
            if (raw.isNullOrBlank()) null else {
                val o = JSONObject(raw)
                val a = o.optJSONArray("cards") ?: JSONArray()
                val cards = (0 until a.length()).map { a.getInt(it) }
                // A bench deck that is not a legal hundred is not a deck.
                // Reading one back means something wrote it wrong, and
                // offering it would surface as the league rejecting the
                // player's submission.
                if (cards.size != 100) null else Workbench(
                    name = o.optString("name", "Unnamed"),
                    cards = cards,
                    plan = o.optJSONObject("plan") ?: JSONObject(),
                    wins = o.optDouble("wins", 0.0),
                    opponents = o.optInt("opponents", 0),
                    builtAtRound = o.optInt("builtAtRound", 0),
                )
            }
        } catch (e: Exception) {
            // A bench we cannot read is not worth crashing over; the entered
            // deck is untouched either way.
            null
        }

        fun save(ctx: Context, b: Workbench) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, b.toJson().toString()).apply()
        }

        /** Entered, or thrown away. Either way it is no longer waiting. */
        fun clear(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY).apply()
        }
    }
}
