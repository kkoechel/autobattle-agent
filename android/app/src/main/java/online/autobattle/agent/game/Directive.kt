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
    /** A tag to build around, or null for "whatever looks best". */
    val theme: String? = null,
    val themeLabel: String? = null,
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

    fun toJson(): JSONObject = JSONObject()
        .put("theme", theme ?: JSONObject.NULL)
        .put("themeLabel", themeLabel ?: JSONObject.NULL)
        .put("breadth", breadth.name)
        .put("original", original)
        .put("target", target ?: JSONObject.NULL)
        .put("seedCard", seedCard ?: JSONObject.NULL)
        .put("seedCardName", seedCardName ?: JSONObject.NULL)
        .put("style", style.name)

    companion object {
        private const val PREFS = "abagent"
        private const val KEY = "directive"

        fun load(ctx: Context): Directive = try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null) ?: return Directive()
            val o = JSONObject(raw)
            Directive(
                theme = o.optString("theme").ifBlank { null },
                themeLabel = o.optString("themeLabel").ifBlank { null },
                breadth = Breadth.valueOf(o.optString("breadth", "BALANCED")),
                original = o.optBoolean("original", false),
                target = o.optString("target").ifBlank { null },
                seedCard = o.optInt("seedCard", 0).takeIf { it > 0 },
                seedCardName = o.optString("seedCardName").ifBlank { null },
                style = runCatching { Style.valueOf(o.optString("style", "ANY")) }
                    .getOrDefault(Style.ANY),
            )
        } catch (e: Exception) {
            // A directive that cannot be read is not worth crashing over;
            // the default is a perfectly playable agent.
            Directive()
        }

        fun save(ctx: Context, d: Directive) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, d.toJson().toString()).apply()
        }
    }
}
