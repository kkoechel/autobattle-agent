package online.autobattle.agent.game

import kotlin.random.Random
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
/**
 * Work an answer sets off, beyond changing the brief.
 *
 * `apply` returns a Directive and nothing else, which is the right shape for
 * almost everything here -- an answer changes the standing instructions and
 * the agent gets on with it. Two of the battle-plan answers are not like
 * that: one starts a measurement, and the other decides which question comes
 * next. Neither is expressible as a change to the Directive, and smuggling
 * them in as one would have put UI state into the brief.
 */
enum class Act {
    NONE,

    /** Play every ordering against the field and keep what wins. */
    TUNE_PLAN,

    /** The player would rather say; ask them the two preference questions. */
    PLAN_BY_HAND,
}

data class Choice(
    val label: String,
    val blurb: String,
    /**
     * Work this answer sets off beyond the brief. Declared BEFORE `apply`
     * deliberately: almost every Choice here passes `apply` as a trailing
     * lambda, and Kotlin binds a trailing lambda to the LAST parameter, so
     * putting `act` at the end silently re-pointed all of them.
     */
    val act: Act = Act.NONE,
    /** Unchanged by default, which is what a decline is. */
    val apply: (Directive) -> Directive = { it },
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
        /** Whose face the agent is wearing, and so how it phrases things. */
        voice: Voice,
        myHandle: String?,
        standings: JSONObject?,
        myDeckCardIds: Set<Int>,
        idea: JSONObject?,
        /**
         * How many cards in the player's deck carry a supplement.
         *
         * Passed in rather than derived here: this layer has the card IDS of
         * the deck but not the catalogue that would say what they do, and
         * handing it one just to count a tag would make every caller load a
         * megabyte of card data to ask a question.
         */
        supplementCards: Int = 0,
        /**
         * Ask even if one has already been asked this round.
         *
         * The per-round cap stops the agent nagging, but it also meant a
         * player who WANTED to talk had nothing to tap: the screen offered
         * only "rehearse it" and "discard it". Asking is not nagging.
         */
        force: Boolean = false,
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
                "“${voice.bannedCard()}”",
                listOf(
                    Choice("Rebuild around it", "Keeps the present direction and " +
                        "works around the gap.") { it },
                    Choice("Begin again", "Abandons the idea and starts from nothing.") {
                        it.copy(seedCard = null, seedCardName = null,
                            themes = emptyList(), themeLabels = emptyList())
                    },
                ), dismissible = false)
        }

        if (me != null && mem.lastPlacement > 0 &&
            me.optInt("placement") > mem.lastPlacement && leader != null &&
            d.target == null && "passed" !in mem.dismissed) {
            val who = leader.optString("handle")
            return Question("passed",
                "“${voice.slipped(me.optInt("placement"), who,
                    leader.optString("deck_name"))}”",
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
                "“${voice.stuck(mem.barrenRuns)}”",
                listOf(
                    Choice("Something else entirely",
                        "Discards the present direction and casts wide.") {
                        it.copy(seedCard = null, seedCardName = null,
                            themes = emptyList(), themeLabels = emptyList(),
                            breadth = Directive.Breadth.WIDE)
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
                "“${voice.giveUp(d.target)}”",
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
                "“${voice.breadth()}”",
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
                "“${voice.original()}”",
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

        // --- THE BATTLE PLAN ------------------------------------------
        //
        // How the deck PLAYS, which the app never mentioned until now. Every
        // deck it has built shipped the same plan fields, hardcoded, and the
        // player had no say in the half of the game that is not the card
        // list.
        //
        // GENERAL QUESTIONS, ATOMIC DECISIONS TAKEN BY THE AGENT. The
        // engine's plan has sixteen fields and vocabularies like
        // "first_spend"; a player has opinions about how their deck should
        // carry itself, not about energy_hold_scope. So each answer here is
        // a general idea, PlanPrefs derives the fields from it, and the
        // agent reports what it derived -- which is also the only way a
        // player ever finds out there is an energy reservation to have an
        // opinion about.
        //
        // ASKED ONLY ONCE THERE IS A DECK IN PLAY. A standing order about
        // what to attack means nothing to somebody who has not yet seen a
        // round scored, and the first run already asks two questions before
        // the agent will do anything. So these wait for the player to be in
        // the standings, and come one at a time.
        if (me != null) {
            // MEASURED BY DEFAULT, DICTATED ON REQUEST.
            //
            // The first version of this asked the player to choose a posture
            // outright, and that was the wrong default: a trial on the live
            // field moved every one of eight decks under the tempo orders --
            // one by eight wins -- while the aim orders moved only five of
            // them, and one deck was flat under everything including a
            // control. WHICH ORDERS MATTER IS A PROPERTY OF THE DECK IN HAND,
            // so a fixed answer is a guess no matter how well argued. The
            // agent can play all nine and find out in about a minute, which
            // makes this measurable rather than arguable.
            //
            // The preference route stays, because a player is allowed to want
            // their deck to play a particular way whatever the numbers say.
            if ("plan-how" !in mem.answered) {
                return Question("plan-how",
                    "\u201c${voice.planHow()}\u201d",
                    listOf(
                        Choice("Play them and keep what wins",
                            "I play our hundred cards nine ways against the " +
                            "live field, then re-play the leader on seeds it " +
                            "has not seen. If nothing beats how we play now " +
                            "by enough to be sure, I leave it alone and say " +
                            "so. About a minute.",
                            act = Act.TUNE_PLAN),
                        Choice("I'll tell you how to play",
                            "Two questions about how the deck should carry " +
                            "itself, and I work out the rest. Quicker, and it " +
                            "is your preference rather than a measurement.",
                            act = Act.PLAN_BY_HAND),
                        Choice(voice.notNow(), voice.notNowWhy()) { it },
                    ))
            }
            if (mem.planByHand && "plan-tempo" !in mem.answered) {
                return Question("plan-tempo",
                    "\u201c${voice.planTempo()}\u201d",
                    listOf(
                        Choice("Press them from the first turn",
                            "Plays whatever we can afford, every turn. Nothing " +
                            "sits idle \u2014 and nothing is ever saved for, " +
                            "either. This is how I have played until now.") {
                            it.copy(plan = it.plan.copy(
                                tempo = PlanPrefs.Tempo.PRESS))
                        },
                        Choice("Build to one big turn",
                            "I hold the opening back until we have five " +
                            "energy, then play on as normal, and keep energy " +
                            "aside for our best card. The first turns are " +
                            "empty, which a fast deck punishes.") {
                            it.copy(plan = it.plan.copy(
                                tempo = PlanPrefs.Tempo.BUILD))
                        },
                        Choice("Never take a weak turn",
                            "I sit out any turn that opens under three energy, " +
                            "all game. Our turns are worth more and there are " +
                            "fewer of them \u2014 and we stand idle whenever we " +
                            "are poor.") {
                            it.copy(plan = it.plan.copy(
                                tempo = PlanPrefs.Tempo.NO_WEAK_TURNS))
                        },
                        Choice(voice.notNow(), voice.notNowWhy()) { it },
                    ))
            }
            // ONLY ASKED OF A DECK THAT HOLDS SUPPLEMENT CARDS.
            //
            // These orders decide whether to pay a card's optional extra
            // cost, and a deck with no such card behaves identically however
            // they are set. Asking anyway would be a setting that changes
            // nothing -- the decorative kind this screen exists to avoid --
            // and the count in the question is what makes it legible: the
            // player is told why they are being asked.
            if (mem.planByHand && "plan-extras" !in mem.answered &&
                supplementCards > 0) {
                return Question("plan-extras",
                    "\u201c${voice.planExtras(supplementCards)}\u201d",
                    listOf(
                        Choice("Whenever we can afford it",
                            "I take the bonus every time the cost can be met. " +
                            "More happens, and some turns go entirely on one " +
                            "card. This is what I have done until now.") {
                            it.copy(plan = it.plan.copy(
                                extras = PlanPrefs.Extras.PAY))
                        },
                        Choice("Only with energy to spare",
                            "I take it only when three energy would still be " +
                            "left afterwards, so the bonus never costs us the " +
                            "rest of the turn.") {
                            it.copy(plan = it.plan.copy(
                                extras = PlanPrefs.Extras.SPARE_ONLY))
                        },
                        Choice("Never \u2014 play them plain",
                            "I always decline. The cards still get played, " +
                            "they just do the smaller thing, and the energy " +
                            "goes on another card instead.") {
                            it.copy(plan = it.plan.copy(
                                extras = PlanPrefs.Extras.NEVER))
                        },
                        Choice(voice.notNow(), voice.notNowWhy()) { it },
                    ))
            }
            if (mem.planByHand && "plan-aim" !in mem.answered) {
                return Question("plan-aim",
                    "\u201c${voice.planAim()}\u201d",
                    listOf(
                        Choice("Push our damage furthest",
                            "I pick whatever is least armoured, so the least " +
                            "of what we do is soaked up. What I have done " +
                            "until now, for want of being told otherwise.") {
                            it.copy(plan = it.plan.copy(
                                aim = PlanPrefs.Aim.MOST_DAMAGE))
                        },
                        Choice("Clear their board",
                            "I finish whatever has the least health left. " +
                            "Their side empties fastest, and their biggest " +
                            "threat is the last thing standing.") {
                            it.copy(plan = it.plan.copy(
                                aim = PlanPrefs.Aim.CLEAR_THE_BOARD))
                        },
                        Choice("Go after their best card",
                            "I go for whatever cost them most, whatever state " +
                            "it is in. A fair guess at their best card and a " +
                            "poor one at their most dangerous right now.") {
                            it.copy(plan = it.plan.copy(
                                aim = PlanPrefs.Aim.THEIR_BEST))
                        },
                        Choice(voice.notNow(), voice.notNowWhy()) { it },
                    ))
            }
        }

        // --- IDEAS ----------------------------------------------------
        // At most one per scored round, so the agent has opinions without
        // becoming a popup.
        if (!force && roundId <= mem.lastIdeaRound) return null

        // ONCE A PLAN IS SET, ITERATE ON IT INSTEAD OF PITCHING PAST IT.
        //
        // Suggesting a different card to a player who has already chosen one
        // reads as the agent having forgotten, and accepting would silently
        // replace the plan they picked. But going silent is just as wrong:
        // choosing a card should open the question of HOW to build it, not
        // close the conversation. So while a plan is set the agent offers
        // angles on that plan, and nothing else.
        val plan = d.seedCardName ?: d.themeLabel
        if (plan != null) return angle(d, mem, plan, roundId, voice)

        // A CARD AND A THEME ARE EQUAL WAYS TO START.
        //
        // The card idea used to be tried first and the theme only reached
        // when there was no card to name -- and /idea nearly always names
        // one, so in practice every unprompted suggestion was "build around
        // this specific card". A player who wants to build mill, or poison,
        // and does not care which card carries it was never offered that.
        // Now the two alternate, and whichever is asked carries the other as
        // a choice, so a theme is always one tap away.
        val card = cardIdea(idea, mem, roundId, voice)
        val theme = themeIdea(mem, idea, roundId, voice)
        return if (roundId % 2 == 0) (card ?: theme) else (theme ?: card)
    }

    /** One card worth building around, with a theme offered beside it. */
    private fun cardIdea(idea: JSONObject?, mem: Memory, roundId: Int,
                         voice: Voice): Question? {
        if (idea == null || idea.optInt("card_id") <= 0) return null
        val nm = idea.optString("name")
        val alt = untriedTheme(mem, roundId)
        return Question("idea-card",
            "“$nm. ${idea.optString("reason")} ${voice.ask("investigate")}”",
            listOfNotNull(
                Choice("Investigate $nm", idea.optString("text")) {
                    it.copy(seedCard = idea.optInt("card_id"), seedCardName = nm,
                        themes = emptyList(), themeLabels = emptyList())
                },
                alt?.let { (tag, label) ->
                    Choice("Build $label instead",
                        "No card in particular \u2014 I pick the whole deck " +
                            "for $label and let the best one carry it.") {
                        it.copy(themes = listOf(tag), themeLabels = listOf(label),
                            seedCard = null, seedCardName = null)
                    }
                },
                Choice(voice.notNow(), voice.notNowWhy()) { it },
            ))
    }

    /**
     * A theme nobody here has tried yet, for a player with no plan.
     *
     * Drawn on the round rather than at random so the menu does not change
     * under a player who is reading it, which is the same reason the angle
     * menu is seeded.
     */
    private fun untriedTheme(mem: Memory, roundId: Int): Pair<String, String>? =
        pickTheme(mem, roundId)?.first

    /**
     * The theme to propose, and whether it is genuinely new.
     *
     * RECYCLES once all sixteen have been tried, rather than returning null.
     * Going silent is what made the agent card-only to begin with: the tried
     * set only ever grows, so "no untried theme" eventually becomes permanent
     * and a player who prefers themes would quietly stop being offered any. A
     * theme tried weeks ago against a field that has moved since is worth
     * proposing again -- it just must not be described as one we have never
     * attempted.
     */
    private fun pickTheme(mem: Memory, roundId: Int): Pair<Pair<String, String>, Boolean>? {
        if (THEMES.isEmpty()) return null
        val untried = THEMES.filter { it.first !in mem.triedThemes }
        val pool = untried.ifEmpty { THEMES }
        return pool[Random(roundId.toLong()).nextInt(pool.size)] to untried.isNotEmpty()
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
    /**
     * Angles on a plan the player has already chosen.
     *
     * SEVERAL AT ONCE, not one. A single yes/no reads as the agent having
     * one idea; a short menu shows the player what directions exist and
     * lets them see which ones have already been taken -- an angle that is
     * currently in force is simply not offered again.
     *
     * Availability is read off the DIRECTIVE rather than remembered, which
     * is what makes that work: switch away from a combo shell and the combo
     * shell is offered again, because it is genuinely available again.
     *
     * NO ABANDON OPTION HERE. It sat on every angle and made walking away
     * the easiest thing on the screen, which is the opposite of iterating.
     * Leaving is still one tap through "Build around...", and the agent
     * offers it itself once the angles are exhausted.
     */
    private fun angle(d: Directive, mem: Memory, plan: String, roundId: Int,
                      voice: Voice): Question? {
        data class Angle(val take: String, val why: String,
                         val apply: (Directive) -> Directive)

        // Kept apart so the menu can be MIXED. There are sixteen pairings
        // and five structural angles, so drawing three at random gave three
        // pairings nearly every time -- a menu of variations on one idea,
        // which is not a choice of directions.
        val structural = ArrayList<Angle>()
        val adds = ArrayList<Angle>()      // build the combination up
        val pairings = ArrayList<Angle>()  // swap one out, or drop one
        val options = structural
        if (d.style != Directive.Style.COMBO) options += Angle(
            "Build a tight shell",
            "Few distinct cards, each at its limit, and no slots spent on " +
                "the field's staples.",
            { it.copy(style = Directive.Style.COMBO) })
        if (d.style != Directive.Style.AGGRO) options += Angle(
            "Keep the curve low",
            "Nothing expensive. We win early or not at all.",
            { it.copy(style = Directive.Style.AGGRO) })
        if (d.style != Directive.Style.DEFENSIVE) options += Angle(
            "Play the long game",
            "Shields and structures, and outlasting them.",
            { it.copy(style = Directive.Style.DEFENSIVE) })
        if (d.breadth != Directive.Breadth.DEEP) options += Angle(
            "Refine it properly",
            "Fewer drafts, twelve passes of refinement on the best.",
            { it.copy(breadth = Directive.Breadth.DEEP) })
        if (!d.original) options += Angle(
            "Keep clear of the field",
            "Refuses any deck sharing too much with a rival.",
            { it.copy(original = true) })
        // ADD, DROP, SWAP -- the three moves on a combination.
        //
        // Three tags is the cap. Past that the affinity set is wide enough
        // that nearly every card scores against it, which is the same as
        // having no theme at all, and the deck stops being about anything.
        val cur = d.themes
        val lab = d.themeLabels
        fun key(tags: List<String>) = tags.sorted().joinToString(",")
        if (cur.size < 3) {
            for ((tag, label) in THEMES) {
                if (tag in cur) continue
                if (key(cur + tag) in mem.triedCombos) continue
                adds += Angle(
                    if (cur.isEmpty()) "Pursue a $label angle" else "Add $label to it",
                    if (cur.isEmpty()) "Weighs the supporting cards toward $label."
                    else "Weighs them toward ${(lab + label).joinToString(" + ")}.",
                    { it.copy(themes = cur + tag, themeLabels = lab + label) })
            }
        }
        // Dropping matters as much as adding: a player who has stacked three
        // tags and lost ground needs a way back that is not "start over".
        if (cur.size > 1) {
            for (i in cur.indices) {
                pairings += Angle(
                    "Drop ${lab[i]}",
                    "Leaves ${(lab - lab[i]).joinToString(" + ")}.",
                    { it.copy(themes = cur - cur[i], themeLabels = lab - lab[i]) })
            }
        }
        if (cur.size == 1) {
            for ((tag, label) in THEMES) {
                if (tag in cur || key(listOf(tag)) in mem.triedCombos) continue
                pairings += Angle(
                    "Try $label instead",
                    "Drops ${lab.joinToString(" + ")} for $label.",
                    { it.copy(themes = listOf(tag), themeLabels = listOf(label)) })
            }
        }

        if (structural.isEmpty() && pairings.isEmpty() && adds.isEmpty()) {
            if ("angle-done" in mem.answered) return null
            return Question("angle-done",
                "\u201c${voice.anglesDone(plan)}\u201d",
                listOf(
                    Choice("Leave it", "Clears the plan, and I will start " +
                        "suggesting cards again.") {
                        it.copy(seedCard = null, seedCardName = null,
                            themes = emptyList(), themeLabels = emptyList())
                    },
                    Choice("Stay with it", "We keep building around $plan.") { it },
                ))
        }

        // Seeded on the round so the menu is stable while a player reads it;
        // a fresh shuffle on every recomposition would move the options
        // under their thumb.
        // One structural angle and two combination moves. Combinations are
        // where the variety is -- there are far more of them than there are
        // ways to change the build -- and taking two structural first put
        // the interesting half of the menu behind a coin flip.
        val rng = Random(roundId.toLong())
        // One of each kind where possible: a move that GROWS the combination,
        // a move that changes it, and a change to how it is built. Drawn
        // purely at random the menu kept showing three ways to swap one tag
        // for another, which is the loop this was meant to break.
        val pick = adds.shuffled(rng).take(1) +
            pairings.shuffled(rng).take(1) +
            structural.shuffled(rng).take(1)
        val shown = (pick + (adds + pairings + structural).shuffled(rng))
            .distinct().take(3).shuffled(rng)
        return Question("angles",
            "\u201c$plan, then. ${voice.which()}\u201d",
            shown.map { Choice(it.take, it.why, apply = it.apply) } +
                Choice(voice.notNow(), voice.notNowWhy()),
        )
    }

    private fun themeIdea(mem: Memory, idea: JSONObject?, roundId: Int,
                          voice: Voice): Question? {
        val (pair, fresh) = pickTheme(mem, roundId) ?: return null
        val (tag, label) = pair
        val rng = Random(roundId.toLong() xor 0x5EEDL)
        val (style, styleWord, styleBlurb) = STYLES[rng.nextInt(STYLES.size)]
        val nm = idea?.optString("name")?.takeIf {
            it.isNotBlank() && (idea.optInt("card_id") > 0)
        }
        return Question("idea-theme",
            "“A $label $styleWord deck. " +
                (if (fresh) "We have never attempted one. "
                 else "We have tried $label before, but not like this. ") +
                "${voice.ask("try it")}”",
            listOfNotNull(
                Choice("Attempt it", styleBlurb) {
                    it.copy(themes = listOf(tag), themeLabels = listOf(label), style = style,
                        seedCard = null, seedCardName = null)
                },
                // The card idea the agent would otherwise have led with, so
                // declining a theme is not the same as declining both.
                nm?.let { n ->
                    Choice("Build around $n instead", idea.optString("text")) {
                        it.copy(seedCard = idea.optInt("card_id"), seedCardName = n,
                            themes = emptyList(), themeLabels = emptyList())
                    }
                },
                Choice(voice.notNow(), voice.notNowWhy()) { it },
            ))
    }
}
