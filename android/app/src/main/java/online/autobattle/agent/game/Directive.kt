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
    /**
     * How the deck plays once it is built, as against what is in it.
     *
     * Its defaults are exactly the plan the app hardcoded before these were
     * askable, so an untouched directive builds the same deck it always did.
     */
    val plan: PlanPrefs = PlanPrefs(),
    /**
     * The measured archetype this build starts from, by name.
     *
     * Persisted with the rest so a later rebuild repeats the choice instead
     * of quietly falling back to a cold climb -- the player picked a starting
     * point, and "build it again" should mean the same thing the second time.
     * The NAME rather than the list: the pool is rebuilt every few hours
     * against a moving field, and starting from a stale copy of a deck would
     * be worse than starting from the current one with the same idea.
     */
    val shellName: String? = null,
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

    /**
     * The measured deck this build actually starts from.
     *
     * RESOLVED WHERE IT IS READ, not where it is set. A shell and a theme are
     * two ways of saying the same thing -- what the deck is ABOUT -- so they
     * cannot both apply. Eight places in the app set a theme or a seed card,
     * and sooner or later one of them is the one that forgets to clear the
     * shell: that is not hypothetical, it shipped. Picking "poison" after
     * starting from a shell rebuilt the shell and ignored the theme, and the
     * only sign was the pass count saying 4 where a themed build says 12.
     *
     * Deciding it on the read side means no setter can get it wrong, and the
     * rule is stated once where it can be checked.
     */
    val shell: String? get() =
        if (themes.isEmpty() && seedCard == null) shellName else null

    /**
     * Refinement rounds spent on the chosen design.
     *
     * A quick build takes four whatever the breadth says. Breadth describes
     * how widely to search for an IDEA, and a shell is the idea already
     * chosen and already measured -- the big wins are found, so twelve more
     * passes would mostly be the bench reporting that nothing beat the
     * current deck. Four is enough to show real work and to let the deck
     * adapt to a field that has moved since the pool was built.
     */
    val generations: Int get() = if (shell != null) 4 else when (breadth) {
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
        bits.addAll(plan.summary())
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
        .put("plan", plan.toJson())
        .putOpt("shellName", shellName)

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
                plan = PlanPrefs.fromJson(o.optJSONObject("plan")),
                shellName = str(o, "shellName"),
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
