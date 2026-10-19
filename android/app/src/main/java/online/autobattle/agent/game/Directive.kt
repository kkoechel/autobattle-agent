package online.autobattle.agent.game

import android.content.Context
import org.json.JSONObject

/**
 * The standing instructions a player has given their agent.
 *
 * This is the whole decision surface of the game. The agent does the work;
 * the player decides what KIND of work, and those choices are the only thing
 * distinguishing two players who both press the same button. They are the
 * same knobs the server-side agents differ by -- breadth, novelty, depth and
 * who to target -- which is why each one is known to change behaviour rather
 * than being a plausible-sounding dial.
 */
data class Directive(
    /**
     * The tags to build around -- a COMBINATION, not one choice.
     *
     * A single tag made every themed deck a variation on one idea, and a
     * player iterating would cycle through sixteen of them and come back
     * round. Poison plus ramp plus card draw is a different deck from any
     * of the three, and there are far more combinations than tags, which is
     * what keeps iterating from going stale.
     *
     * Empty means "whatever looks best".
     */
    val themes: List<String> = emptyList(),
    val themeLabels: List<String> = emptyList(),
    /** Many shallow sketches, or few ideas refined hard. */
    val breadth: Breadth = Breadth.BALANCED,
    /** Copy what wins, or stay away from what the field already plays. */
    val original: Boolean = false,
    /** A rival handle to build against, or null to beat the field at large. */
    val target: String? = null,
    /** A specific card to build around, when the agent proposed one. */
    val seedCard: Int? = null,
    val seedCardName: String? = null,
    val style: Style = Style.ANY,
) {
    enum class Breadth { WIDE, BALANCED, DEEP }

    /** The first tag, for the one place that can only use one: seed choice. */
    val theme: String? get() = themes.firstOrNull()

    /** All of them, for anything the player reads. */
    val themeLabel: String? get() =
        themeLabels.joinToString(" + ").ifBlank { null }

    /**
     * Each style maps to a real change in how the deck is built. None of
     * them is a label on identical behaviour -- a style that did not change
     * the deck would be the decorative kind of setting this game is supposed
     * to avoid.
     */
    enum class Style { ANY, AGGRO, COMBO, DEFENSIVE }

    /** COMBO builds the dense, staple-free shape: few distinct cards, each
     *  at its deck limit. Measured in Pauper at +2.69W on the average
     *  candidate (t=+4.96, better on 50 of 60 seeds) against the default
     *  build, which spends 30 of 100 slots on whatever the field plays. */
    val stapleSlots: Int get() = if (style == Style.COMBO) 0 else 30
    val pool: Int get() = if (style == Style.COMBO) 8 else 14
    /** AGGRO seeds only from cheap cards, so the curve is actually low. */
    val maxSeedCost: Int? get() = if (style == Style.AGGRO) 2 else null

    /** Candidate designs to sketch before choosing one to refine. */
    val seedCount: Int get() = when (breadth) {
        Breadth.WIDE -> 60; Breadth.BALANCED -> 36; Breadth.DEEP -> 18
    }

    /** Refinement rounds spent on the chosen design. */
    val generations: Int get() = when (breadth) {
        Breadth.WIDE -> 3; Breadth.BALANCED -> 6; Breadth.DEEP -> 12
    }

    /**
     * Shared cards allowed with any one rival deck, when originality is
     * asked for. 40 rather than the server agents' 30: this field is small
     * and heavily seeded from the same generator, so decks overlap more here
     * by construction, and 30 would reject almost everything buildable.
     */
    val maxOverlap: Int? get() = if (original) 40 else null

    fun summary(): String {
        val bits = ArrayList<String>()
        bits.add(when (breadth) {
            Breadth.WIDE -> "trying lots of ideas"
            Breadth.BALANCED -> "balancing breadth and depth"
            Breadth.DEEP -> "refining one idea hard"
        })
        seedCardName?.let { bits.add("building around $it") }
        if (seedCardName == null) themeLabel?.let { bits.add("building around $it") }
        else themeLabel?.let { bits.add("pursuing $it") }
        when (style) {
            Style.AGGRO -> bits.add("keeping the curve low")
            Style.COMBO -> bits.add("going for a tight combo shell")
            Style.DEFENSIVE -> bits.add("playing defensively")
            Style.ANY -> {}
        }
        if (original) bits.add("avoiding what others play")
        target?.let { bits.add("gunning for $it") }
        return bits.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }

    /**
     * Null fields are OMITTED, not written as JSON null.
     *
     * Android's JSONObject.optString returns the STRING "null" for a JSON
     * null, and "null" is not blank -- so `optString(k).ifBlank { null }`
     * read every absent field back as the four-character word. The brief
     * then read "Trying lots of ideas, building around null, gunning for
     * null." on every relaunch, because saving and reloading was enough to
     * turn absent into present. Omitting the key makes optString return its
     * real default.
     */
    fun toJson(): JSONObject = JSONObject()
        .put("themes", org.json.JSONArray(themes))
        .put("themeLabels", org.json.JSONArray(themeLabels))
        .put("breadth", breadth.name)
        .put("original", original)
        .putOpt("target", target)
        .putOpt("seedCard", seedCard)
        .putOpt("seedCardName", seedCardName)
        .put("style", style.name)

    companion object {
        private const val PREFS = "abagent"
        private const val KEY = "directive"

        fun load(ctx: Context): Directive = try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null) ?: return Directive()
            val o = JSONObject(raw)
            Directive(
                // Migrates a directive saved when a theme was one tag.
                themes = list(o, "themes") ?: listOfNotNull(str(o, "theme")),
                themeLabels = list(o, "themeLabels")
                    ?: listOfNotNull(str(o, "themeLabel")),
                breadth = Breadth.valueOf(o.optString("breadth", "BALANCED")),
                original = o.optBoolean("original", false),
                target = str(o, "target"),
                seedCard = o.optInt("seedCard", 0).takeIf { it > 0 },
                seedCardName = str(o, "seedCardName"),
                style = runCatching { Style.valueOf(o.optString("style", "ANY")) }
                    .getOrDefault(Style.ANY),
            )
        } catch (e: Exception) {
            // A directive that cannot be read is not worth crashing over;
            // the default is a perfectly playable agent.
            Directive()
        }

        /**
         * An optional string, read defensively. Handles both JSON null and
         * the literal "null" that older saves already contain -- a phone
         * that has been running since before the fix repairs itself the
         * first time its directive is loaded, without anyone clearing data.
         */
        /** A saved list, or null if this save predates it. */
        private fun list(o: JSONObject, key: String): List<String>? {
            val a = o.optJSONArray(key) ?: return null
            return (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
        }

        private fun str(o: JSONObject, key: String): String? {
            if (o.isNull(key)) return null
            val v = o.optString(key, "")
            return if (v.isBlank() || v == "null") null else v
        }

        fun save(ctx: Context, d: Directive) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, d.toJson().toString()).apply()
        }
    }
}
