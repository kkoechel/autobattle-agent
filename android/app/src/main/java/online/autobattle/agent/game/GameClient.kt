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
