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
data class Keyword(val slug: String, val label: String, val short: String)

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
    /**
     * The card exactly as the catalogue sent it. Kept because the main game's
     * own card renderer (window.buildCardSVG) takes the whole card object --
     * effects_json, tags, flavour and all -- and reconstructing that from the
     * fields above would drift from it the first time the main game adds one.
     */
    val raw: String = "",
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

    /**
     * JSONObject.optString returns the four-character string "null" for a
     * JSON null, not the default -- which is how a card with no subtype
     * rendered as "Enchantment · null" on screen.
     */
    private fun str(o: JSONObject, key: String): String {
        if (o.isNull(key)) return ""
        val v = o.optString(key, "")
        return if (v == "null") "" else v
    }

    fun parse(cards: JSONArray): Map<Int, CardInfo> {
        val out = HashMap<Int, CardInfo>(cards.length())
        for (i in 0 until cards.length()) {
            val c = cards.getJSONObject(i)
            val stats = c.optJSONObject("stats_json")
                ?: runCatching { JSONObject(c.optString("stats_json", "{}")) }.getOrNull()
            val id = c.getInt("id")
            out[id] = CardInfo(
                id = id,
                name = str(c, "name").ifBlank { "#$id" },
                cost = c.optInt("cost", 0),
                rarity = str(c, "rarity"),
                supertype = str(c, "supertype"),
                subtype = str(c, "subtype"),
                rules = str(c, "rules_text").trim(),
                imageUrl = str(c, "image_url").takeIf { it.isNotBlank() },
                melee = stats?.optInt("melee", -1)?.takeIf { it >= 0 },
                health = stats?.optInt("health", -1)?.takeIf { it >= 0 },
                armor = stats?.optInt("armor", -1)?.takeIf { it >= 0 },
                raw = c.toString(),
            )
        }
        return out
    }

    fun parseKeywords(o: JSONObject): Map<String, Keyword> {
        val out = HashMap<String, Keyword>()
        for (slug in o.keys()) {
            val v = o.optJSONObject(slug) ?: continue
            val label = v.optString("label", slug)
            if (label.isBlank()) continue
            out[label.lowercase()] = Keyword(slug, label, v.optString("short", ""))
        }
        return out
    }

    /**
     * Which words in a line of card text are worth tapping.
     *
     * Deliberately NOT a scan for every card name in the catalogue: a card
     * called "Shield" would turn the word "shield" into a link in half the
     * game's text. Only two things are matched -- printed keywords, which are
     * a closed list of 72, and tokens, which are the things card text
     * actually references by name and which a player has no other way to
     * look up.
     */
    fun spans(
        line: String,
        keywords: Map<String, Keyword>,
        tokens: Map<String, CardInfo>,
    ): List<Triple<IntRange, Keyword?, CardInfo?>> {
        val terms = (keywords.keys + tokens.keys).sortedByDescending { it.length }
        val out = ArrayList<Triple<IntRange, Keyword?, CardInfo?>>()
        val taken = BooleanArray(line.length)
        for (t in terms) {
            var from = 0
            while (true) {
                val i = line.indexOf(t, from, ignoreCase = true)
                if (i < 0) break
                from = i + 1
                val end = i + t.length
                // Whole words only: "Mill" must not match inside "Miller".
                val okStart = i == 0 || !line[i - 1].isLetterOrDigit()
                val okEnd = end >= line.length || !line[end].isLetterOrDigit()
                if (!okStart || !okEnd) continue
                if ((i until end).any { taken[it] }) continue
                for (j in i until end) taken[j] = true
                out.add(Triple(i until end, keywords[t], tokens[t]))
            }
        }
        return out.sortedBy { it.first.first }
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
