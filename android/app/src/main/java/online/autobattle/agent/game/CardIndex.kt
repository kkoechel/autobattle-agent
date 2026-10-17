package online.autobattle.agent.game

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything needed to SHOW a card, not just simulate it.
 *
 * The agent proposes cards by name -- "do you want me to build around Rabbit
 * Mob?" -- which is an unanswerable question if you have never seen the card.
 * The engine only ever needed ids and effects; a player needs the art, the
 * cost, the stats and the text.
 */
data class CardInfo(
    val id: Int,
    val name: String,
    val cost: Int,
    val rarity: String,
    val supertype: String,
    val subtype: String,
    val rules: String,
    val imageUrl: String?,
    val melee: Int?,
    val health: Int?,
    val armor: Int?,
) {
    val typeLine: String
        get() = listOf(supertype, subtype).filter { it.isNotBlank() }.joinToString(" · ")

    val statLine: String?
        get() {
            val bits = ArrayList<String>()
            melee?.let { bits.add("$it melee") }
            health?.let { bits.add("$it health") }
            armor?.takeIf { it > 0 }?.let { bits.add("$it armor") }
            return bits.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }
}

object CardIndex {

    /**
     * Card art lives on the main game's public uploads path. Read-only,
     * unauthenticated static files -- fetching them needs no account and
     * changes nothing over there, which keeps the rule that this game never
     * touches the main app. The coupling is that art breaks if those paths
     * ever move; the cards themselves would still work.
     */
    const val ART_BASE = "https://autobattle.online"

    fun parse(cards: JSONArray): Map<Int, CardInfo> {
        val out = HashMap<Int, CardInfo>(cards.length())
        for (i in 0 until cards.length()) {
            val c = cards.getJSONObject(i)
            val stats = c.optJSONObject("stats_json")
                ?: runCatching { JSONObject(c.optString("stats_json", "{}")) }.getOrNull()
            val id = c.getInt("id")
            out[id] = CardInfo(
                id = id,
                name = c.optString("name", "#$id"),
                cost = c.optInt("cost", 0),
                rarity = c.optString("rarity", ""),
                supertype = c.optString("supertype", ""),
                subtype = c.optString("subtype", ""),
                rules = c.optString("rules_text", "").trim(),
                imageUrl = c.optString("image_url", "").takeIf { it.isNotBlank() },
                melee = stats?.optInt("melee", -1)?.takeIf { it >= 0 },
                health = stats?.optInt("health", -1)?.takeIf { it >= 0 },
                armor = stats?.optInt("armor", -1)?.takeIf { it >= 0 },
            )
        }
        return out
    }

    private val cache = HashMap<String, ImageBitmap?>()

    /**
     * Fetches card art, cached in memory for the session.
     *
     * Hand-rolled rather than adding an image library: this is one image at a
     * time, shown in a dialog, and a 3MB dependency to decode a bitmap is a
     * poor trade in an app whose whole size budget is the engine.
     */
    fun art(path: String): ImageBitmap? {
        synchronized(cache) { if (cache.containsKey(path)) return cache[path] }
        val bmp = runCatching {
            val conn = (URL(ART_BASE + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "AutoBattleAgent-Android")
            }
            conn.inputStream.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        }.getOrNull()
        synchronized(cache) { cache[path] = bmp }
        return bmp
    }
}
