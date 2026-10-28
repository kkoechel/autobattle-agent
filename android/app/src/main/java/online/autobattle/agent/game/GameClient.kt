package online.autobattle.agent.game

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client for the Agent game coordinator at agent.autobattle.online.
 *
 * Deliberately separate from ApiClient rather than a second base URL on it.
 * They are different services with different auth lifetimes and different
 * blast radius: ApiClient holds a key that can rewrite a deck on the player's
 * real autobattle.online account, and this one can only ever touch the game.
 * Keeping them apart means a bug here cannot reach the main game.
 */
class GameException(val status: Int, val body: String) :
    RuntimeException("HTTP $status: ${body.take(300)}")

class GameClient(private val token: String?, private val base: String = BASE) {

    companion object {
        const val BASE = "https://agent.autobattle.online/api"

        /** Join is the one call made before a token exists. */
        fun join(handle: String, base: String = BASE): JSONObject =
            GameClient(null, base).call("POST", "/join",
                JSONObject().put("handle", handle))
    }

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val conn = (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "AutoBattleAgent-Android")
            connectTimeout = 15_000
            readTimeout = 120_000
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.bufferedWriter().use { it.write(body.toString()) }
            }
        }
        val status = conn.responseCode
        val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        conn.disconnect()
        if (status !in 200..299) throw GameException(status, text)
        return JSONObject(text)
    }

    fun me(): JSONObject = call("GET", "/me")

    /**
     * Card id -> everything needed to show a card. One fetch serves both the
     * deck lists and the card popups; pulling the catalogue twice for names
     * and then details would be a megabyte each time.
     */
    fun cardIndex(): Map<Int, online.autobattle.agent.game.CardInfo> {
        val (arr, _) = cards()
        return CardIndex.parse(arr)
    }

    /**
     * The whole catalogue plus the ban list. Served whole on purpose: the
     * engine needs effects_json and stats_json, and a slim variant would
     * leave generated decks silently inert.
     */
    fun cards(): Pair<JSONArray, Set<Int>> {
        val o = call("GET", "/cards")
        val banned = mutableSetOf<Int>()
        val b = o.optJSONArray("banned") ?: JSONArray()
        for (i in 0 until b.length()) banned += b.getInt(i)
        return o.getJSONArray("cards") to banned
    }

    /** Everyone's current deck, so iteration happens against real opposition. */
    fun field(): JSONArray = call("GET", "/field").getJSONArray("decks")

    fun standings(): JSONObject = call("GET", "/standings")

    /** A card the agent could suggest building around, chosen server-side. */
    fun idea(): JSONObject = call("GET", "/idea")

    /**
     * The quick-build pool: archetypes the server has already measured
     * against this field, with their card lists.
     *
     * The point of fetching the hundred cards rather than a name is that the
     * phone starts FROM one of these. Candidate generation was never what
     * made a build take six minutes -- measuring is, it depends on a field
     * that moves, and this is that measurement already done.
     */
    fun shells(): JSONArray = call("GET", "/shells").optJSONArray("shells") ?: JSONArray()

    /**
     * Form over a window of recent rounds. One round is noisy enough that a
     * player cannot tell improvement from luck by looking at it.
     */
    /** The printed-keyword glossary, so card text can explain itself. */
    fun keywords(): Map<String, online.autobattle.agent.game.Keyword> =
        CardIndex.parseKeywords(call("GET", "/keywords").getJSONObject("keywords"))

    /**
     * What is currently out of the format, with names and why.
     *
     * Public, because a player whose deck just became illegal needs to be
     * able to find out what happened without owning the deck that caused it.
     */
    fun bans(): JSONArray = call("GET", "/bans").getJSONArray("banned")

    /**
     * The decks this player has kept.
     *
     * Snapshots held by the coordinator rather than on the phone: a deck
     * worth keeping should survive a reinstall, and the whole point is not
     * losing it.
     */
    fun savedDecks(): JSONArray =
        call("GET", "/decks/saved").getJSONArray("saved")

    /** Keep the deck currently in play. Saving the same list twice is a no-op. */
    fun saveDeck(note: String? = null): JSONObject =
        call("POST", "/decks/saved", JSONObject().apply { note?.let { put("note", it) } })

    /** Put a kept deck back into play. Re-validated server-side first. */
    fun restoreDeck(id: Int): JSONObject =
        call("POST", "/decks/saved/restore", JSONObject().put("id", id))

    /** Retitle a kept deck. Empty clears the note. */
    fun noteDeck(id: Int, note: String): JSONObject =
        call("POST", "/decks/saved/note",
            JSONObject().put("id", id).put("note", note))

    fun deleteSaved(id: Int): JSONObject =
        call("POST", "/decks/saved/delete", JSONObject().put("id", id))

    /**
     * This player's own round-by-round record, newest first.
     *
     * Separate from leaderboard() because an average answers a different
     * question: a steady climb and a flat line with one good round average
     * the same, and "is it getting better" is the thing a player actually
     * wants to know.
     */
    fun history(rounds: Int = 40): JSONArray =
        call("GET", "/history?rounds=$rounds").getJSONArray("rounds")

    /**
     * A scored round's decks, exactly as they played, plus its seed block.
     *
     * Needed because `results` records which REVISION played and `/field`
     * holds only the current list, so a finished round could be read but not
     * watched. Omit the round for the newest one that has a snapshot -- which
     * is not necessarily the newest round, since snapshots are pruned.
     */
    fun replay(round: Int = 0): JSONObject =
        call("GET", if (round > 0) "/replay?round=$round" else "/replay")

    fun leaderboard(rounds: Int = 48): JSONArray =
        call("GET", "/leaderboard?rounds=$rounds").getJSONArray("table")

    /**
     * The season ladder: points accumulated over a fixed block of rounds.
     *
     * Returns the WHOLE envelope, not just the table, because the table is
     * unreadable without it -- a total means nothing until you know how many
     * of the season's rounds have been played, and a player sitting near the
     * bottom needs to be told whether that is their deck or the fact that
     * they joined yesterday.
     *
     * `season` is a season number; 0 asks for the one in progress.
     */
    fun season(season: Int = 0): JSONObject =
        call("GET", if (season > 0) "/season?season=$season" else "/season")

    /** Throw the current deck away and take a fresh starter. */
    fun resetDeck(): JSONObject = call("POST", "/deck/reset", JSONObject())

    fun putDeck(name: String, cards: List<Int>, plan: JSONObject?): Int {
        val counts = cards.groupingBy { it }.eachCount()
        val arr = JSONArray()
        for ((id, q) in counts.toSortedMap()) {
            arr.put(JSONObject().put("card_id", id).put("quantity", q))
        }
        val body = JSONObject()
            .put("name", name)
            .put("cards", arr)
            .put("battle_plan", plan ?: JSONObject())
        return call("PUT", "/deck", body).optInt("revision", 0)
    }
}
