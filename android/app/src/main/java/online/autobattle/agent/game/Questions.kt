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
                "“One of our cards has been struck from the format. The deck " +
                    "is illegal as it stands. Shall I rebuild around what " +
                    "remains, or begin from something new?”",
                listOf(
                    Choice("Rebuild around it", "Keeps the present direction and " +
                        "works around the gap.") { it },
                    Choice("Begin again", "Abandons the idea and starts from nothing.") {
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
                "“We have slipped to ${me.optInt("placement")}. $who leads " +
                    "with ${leader.optString("deck_name")}. Shall I build " +
                    "expressly against them?”",
                listOf(
                    Choice("Go after $who", "Aimed at their deck. A specimen bred " +
                        "against one rival tends to lose ground to the rest.") {
                        it.copy(target = who)
                    },
                    Choice("No, the field entire", "Measured against everyone, " +
                        "which is what the standings actually reward.") {
                        it.copy(target = null)
                    },
                ))
        }

        if (mem.barrenRuns >= 2 && "stuck" !in mem.dismissed) {
            return Question("stuck",
                "“${mem.barrenRuns} attempts, no improvement. I would call " +
                    "this deck finished. Shall I change my method?”",
                listOf(
                    Choice("Something else entirely",
                        "Discards the present direction and casts wide.") {
                        it.copy(seedCard = null, seedCardName = null, theme = null,
                            themeLabel = null, breadth = Directive.Breadth.WIDE)
                    },
                    Choice("Deeper on this one",
                        "Fewer drafts, far more refinement. The next " +
                            "improvement is sometimes simply further down.") {
                        it.copy(breadth = Directive.Breadth.DEEP)
                    },
                    Choice("Carry on", "No change to the method.") { it },
                ))
        }

        if (d.target != null && me != null && mem.lastPlacement > 0 &&
            me.optInt("placement") >= mem.lastPlacement && "giveup" !in mem.dismissed) {
            return Question("giveup",
                "“Pursuing ${d.target} has not moved us up. Persist?”",
                listOf(
                    Choice("Let them be", "Back to the field entire.") {
                        it.copy(target = null)
                    },
                    Choice("Persist", "Some matchups take several attempts.") { it },
                ))
        }

        // --- ONBOARDING -----------------------------------------------
        if ("breadth" !in mem.answered) {
            return Question("breadth",
                "“I can sketch a great many decks, or take one idea and " +
                    "refine it properly. Not both in one sitting. Which?”",
                listOf(
                    Choice("Many rough ideas", "Sixty drafts. Sensible when neither " +
                        "of us yet knows what we are looking for.") {
                        it.copy(breadth = Directive.Breadth.WIDE)
                    },
                    Choice("One, properly", "Eighteen drafts, then twelve passes of " +
                        "refinement on the best. Refinement is where most " +
                        "of the strength is found.") {
                        it.copy(breadth = Directive.Breadth.DEEP)
                    },
                    Choice("Some of each", "The middle course.") {
                        it.copy(breadth = Directive.Breadth.BALANCED)
                    },
                ), dismissible = false)
        }
        if ("original" !in mem.answered) {
            return Question("original",
                "“Shall I draw on the cards the winning decks already use, " +
                    "or avoid them and find something of our own?”",
                listOf(
                    Choice("What is known to work", "Quicker to a sound deck. You " +
                        "will resemble everyone else.") { it.copy(original = false) },
                    Choice("Something of our own", "Refuses any deck sharing too " +
                        "much with a rival. Slower, and the only route to " +
                        "something nobody has seen.") {
                        it.copy(original = true)
                    },
                ), dismissible = false)
        }

        // --- IDEAS ----------------------------------------------------
        // At most one per scored round, so the agent has opinions without
        // becoming a popup.
        if (roundId <= mem.lastIdeaRound) return null

        // ONCE A PLAN IS SET, ITERATE ON IT INSTEAD OF PITCHING PAST IT.
        //
        // Suggesting a different card to a player who has already chosen one
        // reads as the agent having forgotten, and accepting would silently
        // replace the plan they picked. But going silent is just as wrong:
        // choosing a card should open the question of HOW to build it, not
        // close the conversation. So while a plan is set the agent offers
        // angles on that plan, and nothing else.
        val plan = d.seedCardName ?: d.themeLabel
        if (plan != null) return angle(d, mem, plan)

        if (idea != null && idea.optInt("card_id") > 0) {
            val nm = idea.optString("name")
            return Question("idea-card",
                "“$nm. ${idea.optString("reason")} Shall I investigate?”",
                listOf(
                    Choice("Investigate $nm", idea.optString("text")) {
                        it.copy(seedCard = idea.optInt("card_id"), seedCardName = nm,
                            theme = null, themeLabel = null)
                    },
                    Choice("Not that one", "We carry on as we are.") { it },
                ))
        }

        return themeIdea(mem)
    }

    /**
     * Angles on a plan the player has already chosen.
     *
     * Each angle is asked at most once, by giving it its own question id and
     * letting the existing `answered` set remember it -- no new state, and a
     * declined angle does not come round again. When they are exhausted the
     * agent says so and offers to move on, which is the only way back to
     * fresh card suggestions short of clearing the plan by hand.
     *
     * Every angle is a REAL change to how the deck is built, not a flavour
     * label: style decides the staple budget and the seed pool, breadth
     * decides how much of the budget goes into refinement.
     */
    private fun angle(d: Directive, mem: Memory, plan: String): Question? {
        data class Angle(
            val id: String,
            val ask: String,
            val take: String,
            val why: String,
            val apply: (Directive) -> Directive,
        )
        val angles = listOf(
            Angle("angle-combo",
                "\u201c$plan, then. Shall I build a tight shell around it \u2014 " +
                    "few distinct cards, each at its limit?\u201d",
                "Build the tight shell",
                "Spends no slots on the field's staples. Measured stronger " +
                    "on average than spreading them.",
                { it.copy(style = Directive.Style.COMBO) }),
            Angle("angle-aggro",
                "\u201cShall I keep the curve low around $plan? Nothing " +
                    "expensive, and we win early or not at all.\u201d",
                "Keep the curve low",
                "Seeds only from cheap cards.",
                { it.copy(style = Directive.Style.AGGRO) }),
            Angle("angle-defensive",
                "\u201cShall I build $plan defensively \u2014 shields and " +
                    "structures, and outlasting them?\u201d",
                "Play the long game",
                "Shields and structures over pressure.",
                { it.copy(style = Directive.Style.DEFENSIVE) }),
            Angle("angle-deep",
                "\u201cI could stop sketching alternatives and refine $plan " +
                    "properly instead. Twelve passes on one list.\u201d",
                "Refine it properly",
                "Fewer drafts, far more refinement. Refinement is where most " +
                    "of the strength is found.",
                { it.copy(breadth = Directive.Breadth.DEEP) }),
            Angle("angle-original",
                "\u201cShall I keep $plan away from what the winning decks " +
                    "play, even if it costs us a little?\u201d",
                "Something of our own",
                "Refuses any deck sharing too much with a rival.",
                { it.copy(original = true) }),
        ).filterNot { it.id in mem.answered }

        if (angles.isEmpty()) {
            if ("angle-done" in mem.answered) return null
            return Question("angle-done",
                "\u201cI have tried every angle I have on $plan. Shall we " +
                    "leave it and look elsewhere?\u201d",
                listOf(
                    Choice("Leave it", "Clears the plan, and I will start " +
                        "suggesting cards again.") {
                        it.copy(seedCard = null, seedCardName = null,
                            theme = null, themeLabel = null)
                    },
                    Choice("Stay with it", "We keep building around $plan.") { it },
                ))
        }

        val a = angles.random()
        return Question(a.id, a.ask,
            listOf(
                Choice(a.take, a.why, a.apply),
                Choice("Not that angle", "We carry on as we are.") { it },
                Choice("Stop building around $plan",
                    "Back to whatever looks best.") {
                    it.copy(seedCard = null, seedCardName = null,
                        theme = null, themeLabel = null)
                },
            ))
    }

    /** A theme nobody here has tried yet, for a player with no plan. */
    private fun themeIdea(mem: Memory): Question? {
        val untried = THEMES.filter { it.first !in mem.triedThemes }
        if (untried.isNotEmpty()) {
            val (tag, label) = untried.random()
            val (style, styleWord, styleBlurb) = STYLES.random()
            return Question("idea-theme",
                "“A $label $styleWord deck. We have never attempted one. " +
                    "Shall we?”",
                listOf(
                    Choice("Attempt it", styleBlurb) {
                        it.copy(theme = tag, themeLabel = label, style = style,
                            seedCard = null, seedCardName = null)
                    },
                    Choice("Another time", "We carry on as we are.") { it },
                ))
        }
        return null
    }
}
