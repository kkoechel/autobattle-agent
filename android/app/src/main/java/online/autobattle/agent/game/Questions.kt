package online.autobattle.agent.game

import org.json.JSONObject

/**
 * What the agent asks you, and what your answer does to it.
 *
 * The player's decisions are posed as the agent's own questions rather than
 * as settings. Three reasons, in order of how much they matter:
 *
 *  - A control labelled "max overlap 40" is configuration. "Everyone is
 *    playing the same deck -- should I copy it or go my own way?" is a
 *    decision a person can actually make, and it teaches the tradeoff
 *    instead of assuming it is already understood.
 *  - The question can depend on the agent's REAL situation -- what is
 *    winning, who is ahead, whether its last idea went anywhere -- so the
 *    same screen says something different each time for honest reasons.
 *  - It is the agent's game to play, and the player's game to direct. The
 *    grammar should match that.
 *
 * Every answer maps to a parameter that is known to change behaviour,
 * because it is one the server-side agents already differ by.
 */
data class Choice(val label: String, val blurb: String, val apply: (Directive) -> Directive)

data class Question(val id: String, val text: String, val choices: List<Choice>)

object Questions {

    /** Themes with enough cards behind them to build a real deck. */
    val THEMES: List<Pair<String, String>> = listOf(
        "damage" to "burn them down",
        "poison" to "poison",
        "honor" to "honour",
        "shield" to "defence",
        "mill" to "milling their deck",
        "token-copy" to "swarming with copies",
        "structure" to "structures",
        "draw" to "drawing cards",
    )

    /**
     * Pick the question worth asking, given where the agent actually stands.
     *
     * Ordered by how much the answer would change: a rival running away with
     * the league is a more interesting question than an abstract preference,
     * and asking about a theme when the agent has no deck at all is asking
     * about the wrong thing.
     */
    fun next(
        directive: Directive,
        myHandle: String?,
        standings: JSONObject?,
        asked: Set<String>,
    ): Question? {
        val rows = standings?.optJSONArray("standings")
        val leader = (0 until (rows?.length() ?: 0))
            .map { rows!!.getJSONObject(it) }
            .firstOrNull { it.optString("handle") != myHandle }
        val myRow = (0 until (rows?.length() ?: 0))
            .map { rows!!.getJSONObject(it) }
            .firstOrNull { it.optString("handle") == myHandle }

        // Behind, and somebody specific is ahead.
        if (leader != null && myRow != null && directive.target == null &&
            myRow.optInt("placement") > 2 && "target" !in asked) {
            val who = leader.optString("handle")
            return Question("target",
                "“${who} is ahead of us with ${leader.optString("deck_name")}. " +
                    "Do you want me to build specifically to beat them, or keep " +
                    "trying to beat everyone?”",
                listOf(
                    Choice("Go after $who", "Targets their deck. Risky: a deck " +
                        "built to beat one rival often loses ground to the rest.") {
                        it.copy(target = who)
                    },
                    Choice("Beat the whole field", "Keeps optimising against " +
                        "everyone, which is what the standings actually reward.") {
                        it.copy(target = null)
                    },
                ))
        }

        if ("breadth" !in asked) {
            return Question("breadth",
                "“I can sketch a lot of decks quickly, or take one idea and " +
                    "refine it properly. I can't do both in one sitting. Which?”",
                listOf(
                    Choice("Try lots of ideas", "Sixty rough designs. Good when " +
                        "you don't know what you're looking for yet.") {
                        it.copy(breadth = Directive.Breadth.WIDE)
                    },
                    Choice("Refine one properly", "Eighteen designs, then twelve " +
                        "rounds of improvement on the best. Refining is where " +
                        "most of the strength comes from.") {
                        it.copy(breadth = Directive.Breadth.DEEP)
                    },
                    Choice("A bit of both", "The middle. Reasonable when you have " +
                        "no strong opinion.") {
                        it.copy(breadth = Directive.Breadth.BALANCED)
                    },
                ))
        }

        if ("original" !in asked) {
            return Question("original",
                "“Should I lean on cards the winning decks already use, or " +
                    "stay away from them and find something of our own?”",
                listOf(
                    Choice("Use what works", "Faster to a solid deck. You will " +
                        "look like everyone else.") { it.copy(original = false) },
                    Choice("Find our own thing", "Refuses decks that share too " +
                        "much with any rival. Slower, and the only way to end up " +
                        "with something nobody has seen.") { it.copy(original = true) },
                ))
        }

        if ("theme" !in asked) {
            val opts = THEMES.shuffled().take(3).map { (tag, label) ->
                Choice(label.replaceFirstChar { c -> c.uppercase() },
                    "Builds around $label.") {
                    it.copy(theme = tag, themeLabel = label)
                }
            } + Choice("You choose", "No preference -- picks whatever tests best.") {
                it.copy(theme = null, themeLabel = null)
            }
            return Question("theme",
                "“Is there a kind of deck you want me to build?”", opts)
        }
        return null
    }
}
