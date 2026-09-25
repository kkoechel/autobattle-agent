package online.autobattle.agent.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What was tried, and what it turned out to be worth.
 *
 * This is the part of the app that teaches the game. A rank tells a player
 * where they finished; a line saying a card led a 107-card census by +0.6 and
 * then measured +0.0 against the full field teaches them that a screen and a
 * result are different things, which is the single most useful idea in here.
 *
 * Rejections are kept and shown, not discarded. They are the majority of the
 * record and they carry most of the information: a deck already at rank 1-2
 * will reject nearly everything, and a journal that only listed successes
 * would imply the search had stalled rather than converged.
 *
 * Stored as one JSON array in the app's private files. Capped, because this
 * is a notebook rather than an audit log -- nothing downstream depends on an
 * entry still being here.
 */
object Journal {

    private const val FILE = "journal.json"
    private const val CAP = 200

    data class Entry(
        val at: Long,
        val kind: String,            // "build" or "improve"
        val deck: String,
        val headline: String,
        val detail: String,
        val wins: Double,
        val opponents: Int,
        val seeds: Int,
        /** Null when the run only screened and never validated. */
        val gain: Double?,
        val t: Double?,
        val accepted: Boolean?,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("at", at).put("kind", kind).put("deck", deck)
            .put("headline", headline).put("detail", detail)
            .put("wins", wins).put("opponents", opponents).put("seeds", seeds)
            .put("gain", gain ?: JSONObject.NULL)
            .put("t", t ?: JSONObject.NULL)
            .put("accepted", accepted ?: JSONObject.NULL)

        companion object {
            fun from(o: JSONObject) = Entry(
                at = o.optLong("at"),
                kind = o.optString("kind"),
                deck = o.optString("deck"),
                headline = o.optString("headline"),
                detail = o.optString("detail"),
                wins = o.optDouble("wins", 0.0),
                opponents = o.optInt("opponents"),
                seeds = o.optInt("seeds"),
                gain = if (o.isNull("gain")) null else o.optDouble("gain"),
                t = if (o.isNull("t")) null else o.optDouble("t"),
                accepted = if (o.isNull("accepted")) null else o.optBoolean("accepted"),
            )
        }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /** Newest first. A corrupt file reads as empty rather than throwing. */
    fun all(ctx: Context): List<Entry> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { Entry.from(a.getJSONObject(it)) }
                .sortedByDescending { it.at }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun append(ctx: Context, e: Entry) {
        val kept = (listOf(e) + all(ctx)).take(CAP)
        val a = JSONArray()
        for (x in kept) a.put(x.toJson())
        try {
            file(ctx).writeText(a.toString())
        } catch (e: Exception) {
            // A journal that cannot be written must not take the answer with
            // it: the measurement the player asked for is already on screen.
        }
    }

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }
}
