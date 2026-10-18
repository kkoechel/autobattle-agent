package online.autobattle.agent.game

import org.json.JSONObject

/**
 * What the agent says to you, and what your answer does to it.
 *
 * Posed as the agent's own questions rather than as settings. A control
 * reading "max overlap 40" is configuration; "everyone is playing the same
 * deck, should I copy it or go our own way?" is a decision a person can
 * make, and it teaches the tradeoff instead of assuming it is understood.
 *
 * Three registers, in priority order:
 *
 *  TRIGGERED  something actually changed -- a rival passed you, two runs
 *             found nothing, a card in your deck got banned. These are the
 *             reason the agent is worth opening twice a day.
 *  ONBOARDING the standing preferences, asked once at the start.
 *  IDEAS      the agent proposing something unprompted: a specific card
 *             nobody plays, or a style worth a try. Rare by design -- an
 *             agent that suggests something every single refresh is noise,
 *             so ideas are capped at one per scored round.
 *
 * Every answer maps to a parameter known to change behaviour, because it is
 * one the server-side agents already differ by. Nothing here is a label on
 * identical work.
 */
data class Choice(
    val label: String,
    val blurb: String,
    val apply: (Directive) -> Directive,
)

data class Question(
    val id: String,
    val text: String,
    val choices: List<Choice>,
    /** Remembered as declined, so the same trigger does not nag. */
    val dismissible: Boolean = true,
)

object Questions {

    /**
     * Themes a player can ask for, and the agent can propose.
     *
     * Every tag here has at least 25 playable cards in the live catalogue,
     * counted after retired, vip and token cards are excluded -- below that
     * there is no deck to build and the generator just returns staples with
     * a theme-coloured seed. Counts at the time of writing are in the
     * comments, so a tag that shrinks can be spotted rather than silently
     * producing worse decks.
     */
    val THEMES: List<Pair<String, String>> = listOf(
        "damage" to "damage",            // 141
        "draw" to "card draw",           // 133
        "token-copy" to "token swarm",   // 126
        "honor" to "honour",             // 110
        "energy" to "energy ramp",       // 104
        "life" to "lifegain",            //  80
        "poison" to "poison",            //  79
        "destroy" to "removal",          //  79
        "shield" to "defence",           //  75
        "mill" to "milling",             //  64
        "recycle" to "recursion",        //  64
        "structure" to "structures",     //  64
        "tutor" to "tutoring",           //  34
        "sacrifice" to "sacrifice",      //  33
        "exile" to "exile",              //  29
        "dishonor" to "dishonour",       //  26
    )

    private val STYLES = listOf(
        Triple(Directive.Style.COMBO, "combo",
            "A tight shell: few distinct cards, each at its limit. Measured " +
                "stronger on average than spreading across the field's staples."),
        Triple(Directive.Style.AGGRO, "aggressive",
            "Nothing expensive. Wins early or not at all."),
        Triple(Directive.Style.DEFENSIVE, "defensive",
            "Shields and structures, and outlasting them."),
    )

    fun next(
        d: Directive,
        mem: Memory,
        myHandle: String?,
        standings: JSONObject?,
        myDeckCardIds: Set<Int>,
        idea: JSONObject?,
    ): Question? {
        val rows = standings?.optJSONArray("standings")
        val all = (0 until (rows?.length() ?: 0)).map { rows!!.getJSONObject(it) }
        val me = all.firstOrNull { it.optString("handle") == myHandle }
        val leader = all.firstOrNull { it.optString("handle") != myHandle }
        val roundId = standings?.optJSONObject("round")?.optInt("id") ?: 0

        // --- TRIGGERED ------------------------------------------------
        val bannedArr = standings?.optJSONArray("banned")
        val hit = (0 until (bannedArr?.length() ?: 0))
            .map { bannedArr!!.getInt(it) }.firstOrNull { it in myDeckCardIds }
        if (hit != null) {
            return Question("banned",
                "“One of our cards has been banned. The deck is illegal as " +
                    "it stands. Do you want me to rebuild around what is left, " +
                    "or start from something fresh?”",
                listOf(
                    Choice("Rebuild what we have", "Keeps the current direction " +
                        "and works around the hole.") { it },
                    Choice("Start fresh", "Drops the idea and begins again.") {
                        it.copy(seedCard = null, seedCardName = null, theme = null,
                            themeLabel = null)
                    },
                ), dismissible = false)
        }

        if (me != null && mem.lastPlacement > 0 &&
            me.optInt("placement") > mem.lastPlacement && leader != null &&
            d.target == null && "passed" !in mem.dismissed) {
            val who = leader.optString("handle")
            return Question("passed",
                "“We have slipped to ${me.optInt("placement")}. $who is ahead " +
                    "with ${leader.optString("deck_name")}. Do you want me to " +
                    "build specifically to beat them?”",
                listOf(
                    Choice("Go after $who", "Targets their deck. Risky: a deck " +
                        "built to beat one rival often loses ground to the rest.") {
                        it.copy(target = who)
                    },
                    Choice("No, beat the field", "Keeps optimising against " +
                        "everyone, which is what the standings reward.") {
                        it.copy(target = null)
                    },
                ))
        }

        if (mem.barrenRuns >= 2 && "stuck" !in mem.dismissed) {
            return Question("stuck",
                "“I have tried ${mem.barrenRuns} times now and found nothing " +
                    "better. I think this deck is as good as it gets. Shall I " +
                    "change how I am working?”",
                listOf(
                    Choice("Try something completely different",
                        "Throws out the current direction and casts wide.") {
                        it.copy(seedCard = null, seedCardName = null, theme = null,
                            themeLabel = null, breadth = Directive.Breadth.WIDE)
                    },
                    Choice("Dig deeper on this one",
                        "Fewer designs, far more refinement. Sometimes the next " +
                            "improvement is just further down.") {
                        it.copy(breadth = Directive.Breadth.DEEP)
                    },
                    Choice("Keep going as we are", "No change.") { it },
                ))
        }

        if (d.target != null && me != null && mem.lastPlacement > 0 &&
            me.optInt("placement") >= mem.lastPlacement && "giveup" !in mem.dismissed) {
            return Question("giveup",
                "“Targeting ${d.target} has not moved us up. Keep at it?”",
                listOf(
                    Choice("Stop targeting", "Back to beating the whole field.") {
                        it.copy(target = null)
                    },
                    Choice("Keep at it", "Some matchups take a few attempts.") { it },
                ))
        }

        // --- ONBOARDING -----------------------------------------------
        if ("breadth" !in mem.answered) {
            return Question("breadth",
                "“I can sketch a lot of decks quickly, or take one idea and " +
                    "refine it properly. I can't do both in one sitting. Which?”",
                listOf(
                    Choice("Try lots of ideas", "Sixty rough designs. Good when " +
                        "you don't know what you are looking for yet.") {
                        it.copy(breadth = Directive.Breadth.WIDE)
                    },
                    Choice("Refine one properly", "Eighteen designs, then twelve " +
                        "rounds of improvement on the best. Refining is where " +
                        "most of the strength comes from.") {
                        it.copy(breadth = Directive.Breadth.DEEP)
                    },
                    Choice("A bit of both", "The middle.") {
                        it.copy(breadth = Directive.Breadth.BALANCED)
                    },
                ), dismissible = false)
        }
        if ("original" !in mem.answered) {
            return Question("original",
                "“Should I lean on cards the winning decks already use, or " +
                    "stay away from them and find something of our own?”",
                listOf(
                    Choice("Use what works", "Faster to a solid deck. You will " +
                        "look like everyone else.") { it.copy(original = false) },
                    Choice("Find our own thing", "Refuses decks that share too " +
                        "much with any rival. Slower, and the only way to end " +
                        "up with something nobody has seen.") {
                        it.copy(original = true)
                    },
                ), dismissible = false)
        }

        // --- IDEAS ----------------------------------------------------
        // At most one per scored round, so the agent has opinions without
        // becoming a popup.
        if (roundId <= mem.lastIdeaRound) return null

        if (idea != null && idea.optInt("card_id") > 0) {
            val nm = idea.optString("name")
            return Question("idea-card",
                "“Do you want me to build around $nm? ${idea.optString("reason")}”",
                listOf(
                    Choice("Yes, build around $nm", idea.optString("text")) {
                        it.copy(seedCard = idea.optInt("card_id"), seedCardName = nm,
                            theme = null, themeLabel = null)
                    },
                    Choice("Not that one", "Carries on as we are.") { it },
                ))
        }

        val untried = THEMES.filter { it.first !in mem.triedThemes }
        if (untried.isNotEmpty()) {
            val (tag, label) = untried.random()
            val (style, styleWord, styleBlurb) = STYLES.random()
            return Question("idea-theme",
                "“What if we built a $label $styleWord deck? We have never " +
                    "tried it.”",
                listOf(
                    Choice("Let's try it", styleBlurb) {
                        it.copy(theme = tag, themeLabel = label, style = style,
                            seedCard = null, seedCardName = null)
                    },
                    Choice("Maybe later", "Carries on as we are.") { it },
                ))
        }
        return null
    }
}
