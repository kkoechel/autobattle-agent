package online.autobattle.agent.api

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val status: Int, val body: String) :
    RuntimeException("HTTP $status: ${body.take(300)}")

/**
 * The AutoBattle v1 API.
 *
 * HttpURLConnection rather than a client library: this makes about five GETs,
 * and the largest by far is the card catalogue, where the cost is parsing
 * ~870KB rather than anything the HTTP layer does.
 *
 * Reads are not rate limited by the server; writes are capped at 1/sec for
 * ordinary accounts. Nothing here writes yet — an ordinary player's key gets
 * 403 on every /decks route, since deck CRUD needs an admin-granted flag.
 */
class ApiClient(private val apiKey: String, private val base: String = BASE) {

    companion object {
        const val BASE = "https://autobattle.online/api/v1"
        const val PROFILE_URL = "https://autobattle.online/game/profile.php"
    }

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val conn = (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $apiKey")
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
        if (status !in 200..299) throw ApiException(status, text)
        return JSONObject(text)
    }

    /** Verifies the key and returns the account. */
    fun me(): JSONObject = call("GET", "/me").getJSONObject("user")

    /**
     * The FULL catalogue. `fields=slim|rules` drops effects_json and
     * stats_json, which the engine needs to simulate anything, and
     * `playable=1` drops token definitions — a deck that creates tokens then
     * silently does nothing, with no error to notice.
     */
    fun cards(): JSONArray = call("GET", "/cards").getJSONArray("cards")

    /** An arena's latest finalized cohort: every deck's list and plan. */
    fun meta(arena: String): JSONObject = call("GET", "/meta?arena=$arena")

    fun deck(id: Int): JSONObject = call("GET", "/decks/$id").getJSONObject("deck")

    /** Any deck's list by id, including other players'. Works on a plain key. */
    fun publicDeck(id: Int): JSONObject = call("GET", "/decks/$id/public").getJSONObject("deck")

    fun results(arena: String? = null, limit: Int = 25): JSONArray {
        val q = buildString {
            append("/results?limit=").append(limit)
            if (arena != null) append("&arena=").append(arena)
        }
        return call("GET", q).getJSONArray("results")
    }
}
