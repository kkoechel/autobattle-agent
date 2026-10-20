package online.autobattle.agent.game

/**
 * How the agent speaks, which depends on whose face it is wearing.
 *
 * WHY THIS IS NOT THREE COPIES OF EVERY LINE. Almost everything the agent
 * says is a fact with a framing around it -- "Shall I investigate?",
 * "I could not avoid the field's cards", "Done. <deck>, revision 4". The
 * numbers and card names are the same whoever is talking; what changes is
 * the modal verb, the contractions and the length of the sentence. So the
 * framings are per-voice and the facts are shared. Three full sets of
 * forty-odd strings would drift apart the first time one of them was
 * edited, and the drift would be invisible until a player happened to pick
 * that avatar.
 *
 * The register is doing a job beyond flavour: it is the difference between
 * an advisor who defers and one who pushes. Keep the WIZARD precise and
 * bloodless, the BRUTE blunt and impatient, the SPRITE warm and hedging --
 * and keep all three honest. None of them may overstate a result.
 */
enum class Voice {
    /** Scholarly and exact. The register the app had before there were three. */
    WIZARD,

    /** Blunt. Short sentences, contractions, no ceremony. */
    BRUTE,

    /** Warm and encouraging, without being soft about the numbers. */
    SPRITE;

    /** "Shall I investigate?" -- the agent proposing to do something. */
    fun ask(what: String): String = when (this) {
        WIZARD -> "Shall I $what?"
        BRUTE -> "Want me to $what?"
        SPRITE -> "Would you like me to $what?"
    }

    /** Reporting a finished piece of work, before the numbers. */
    fun done(): String = when (this) {
        WIZARD -> "Done."
        BRUTE -> "That's done."
        SPRITE -> "All finished!"
    }

    /** "I could not avoid the field's cards" -- admitting a limit. */
    fun cannot(what: String): String = when (this) {
        WIZARD -> "I could not $what"
        BRUTE -> "Couldn't $what"
        SPRITE -> "I wasn't able to $what"
    }

    /** A judgement the agent is offering, not a fact it measured. */
    fun reckon(what: String): String = when (this) {
        WIZARD -> "I would call $what"
        BRUTE -> "That's $what, plainly"
        SPRITE -> "I'd say $what"
    }

    /** Nothing has happened since last time. */
    fun nothingNew(): String = when (this) {
        WIZARD -> "Nothing new yet."
        BRUTE -> "Nothing yet."
        SPRITE -> "Nothing new just yet."
    }

    /** The agent is mid-task. */
    fun working(step: String): String = when (this) {
        WIZARD -> "$step…"
        BRUTE -> "$step. Give it a moment."
        SPRITE -> "$step…"
    }

    /** What the decline option on a question is called. */
    fun notNow(): String = when (this) {
        WIZARD -> "Not just now"
        BRUTE -> "Leave it"
        SPRITE -> "Maybe later"
    }

    /** And the blurb under it. */
    fun notNowWhy(): String = when (this) {
        WIZARD -> "We carry on as we are."
        BRUTE -> "We keep going."
        SPRITE -> "We'll stay as we are for now."
    }

    /**
     * A card in the deck has just been banned.
     *
     * Written out per voice rather than assembled from fragments: it is the
     * line a player is most likely to be looking at, because the ban pool
     * rotates and any deck holding a banned card shows it until answered.
     * A line that prominent is worth three real sentences.
     */
    fun bannedCard(): String = when (this) {
        WIZARD ->
            "One of our cards has been struck from the format. The deck is " +
            "illegal as it stands. Shall I rebuild around what remains, or " +
            "begin from something new?"
        BRUTE ->
            "One of our cards is out. The deck's illegal as it stands. " +
            "Rebuild round what's left, or start over?"
        SPRITE ->
            "One of our cards has been struck from the format, so the deck " +
            "isn't legal any more. Shall we rebuild around what's left, or " +
            "begin something new?"
    }

    /** You have been overtaken, and the leader is named. */
    fun slipped(place: Int, who: String, deck: String): String = when (this) {
        WIZARD -> "We have slipped to $place. $who leads with $deck. " +
            "Shall I build expressly against them?"
        BRUTE -> "We're down to $place. $who is ahead with $deck. " +
            "Want me to go straight at them?"
        SPRITE -> "We've slipped to $place \u2014 $who is ahead now, with $deck. " +
            "Shall we build something aimed at them?"
    }

    /** Several runs in a row have found nothing. */
    fun stuck(runs: Int): String = when (this) {
        WIZARD -> "$runs attempts, no improvement. I would call this deck " +
            "finished. Shall I change my method?"
        BRUTE -> "$runs tries, nothing gained. This deck's done. " +
            "Want me to work differently?"
        SPRITE -> "That's $runs attempts without a gain. I think this deck " +
            "has given us what it has. Shall we try another way?"
    }

    /** Hunting one rival has not paid off. */
    fun giveUp(target: String): String = when (this) {
        WIZARD -> "Pursuing $target has not moved us up. Persist?"
        BRUTE -> "Chasing $target isn't working. Keep at it?"
        SPRITE -> "Going after $target hasn't moved us up. Shall we keep trying?"
    }

    /** The opening question: breadth against depth. */
    fun breadth(): String = when (this) {
        WIZARD -> "I can sketch a great many decks, or take one idea and " +
            "refine it properly. Not both in one sitting. Which?"
        BRUTE -> "I can rough out a lot of decks, or take one and work it " +
            "properly. Not both. Which is it?"
        SPRITE -> "I could sketch lots of decks, or take a single idea and " +
            "really refine it \u2014 not both at once, I'm afraid. Which sounds better?"
    }

    /** The other opening question: copy the field, or avoid it. */
    fun original(): String = when (this) {
        WIZARD -> "Shall I draw on the cards the winning decks already use, " +
            "or avoid them and find something of our own?"
        BRUTE -> "Do I use what the winning decks run, or keep clear and " +
            "find our own?"
        SPRITE -> "Would you like me to borrow from the decks that are " +
            "winning, or steer clear and find something of our own?"
    }

    /** Every angle on the current plan has been tried. */
    fun anglesDone(plan: String): String = when (this) {
        WIZARD -> "I have tried every angle I have on $plan. Shall we leave " +
            "it and look elsewhere?"
        BRUTE -> "That's every angle I've got on $plan. Drop it?"
        SPRITE -> "I've tried every angle I can think of on $plan. Shall we " +
            "leave it there and look somewhere new?"
    }

    /** A round landed and you are in it. */
    fun scored(round: Int, place: Int, of: Int, w: Int, l: Int, d: Int): String =
        when (this) {
            WIZARD -> "Round $round is scored. You placed $place of $of, " +
                "on $w-$l-$d."
            BRUTE -> "Round $round is in. $place of $of, $w-$l-$d."
            SPRITE -> "Round $round is scored \u2014 you came $place of $of, " +
                "on $w-$l-$d."
        }

    /** A round landed but you were not in it. */
    fun scoredBare(round: Int): String = when (this) {
        WIZARD -> "Round $round is scored."
        BRUTE -> "Round $round is in."
        SPRITE -> "Round $round is scored."
    }

    /** The agent is looking something up. */
    fun letMeLook(): String = when (this) {
        WIZARD -> "Let me look\u2026"
        BRUTE -> "Hold on\u2026"
        SPRITE -> "Let me have a look\u2026"
    }

    /** The deck is submitted and the round has not come round yet. */
    fun notPlayedYet(): String = when (this) {
        WIZARD -> "It has not been played yet."
        BRUTE -> "Hasn't played yet."
        SPRITE -> "It hasn't had its round yet."
    }

    /** A banned card, and whether it was in your list. */
    fun banLanded(name: String, inMine: Boolean): String = when {
        inMine && this == WIZARD -> "It is in the deck we are playing, so that " +
            "deck is now illegal. I can build around the hole when you are ready."
        inMine && this == BRUTE -> "It's in the deck we're running, so that " +
            "deck's illegal now. Say when and I'll build round the hole."
        inMine -> "It's in the deck we're playing, so that deck isn't legal " +
            "any more. I can build around the gap whenever you're ready."
        this == WIZARD -> "We were not playing it. Nothing of ours changes."
        this == BRUTE -> "We weren't running it. Changes nothing for us."
        else -> "We weren't playing it, so nothing of ours changes."
    }

    /** Agreeing to sit out a few rounds. */
    fun beingPatient(rounds: Int): String {
        // "report after 1 rounds" appeared the moment the interval was changed
        // for a test. The wording must not assume the number.
        // "report after 1 rounds" appeared the moment the interval changed,
        // and the player can now pick any of five. The wording must not
        // assume the number.
        val n = if (rounds == 1) "one round" else "$rounds rounds"
        return when (this) {
            WIZARD -> "Very well. I shall leave it to play for $n and bring " +
                "you word."
            BRUTE -> "Right. I'll leave it be for $n, then find you."
            SPRITE -> "Good idea \u2014 I'll let it play for $n and come find " +
                "you after."
        }
    }

    /**
     * The battle plan, explained once and then asked about.
     *
     * THE EXPLANATION IS PART OF THE FIRST QUESTION, not a separate screen.
     * A player who has got this far knows the agent builds a hundred-card
     * list; nothing has ever told them the deck also carries standing
     * orders, so the first plan question has to say what a plan IS before
     * it can sensibly ask anything about one. The second assumes it.
     *
     * Both are deliberately general. The agent works out the energy
     * thresholds and reservations itself and reports them afterwards
     * through planDecided(); asking a player for a number would be handing
     * them the agent's job.
     */
    fun planTempo(): String = when (this) {
        WIZARD ->
            "A word on how these battles are fought: nobody fights them. " +
            "The hundred cards decide what our deck is capable of, and the " +
            "battle plan \u2014 a few standing orders it carries into every " +
            "match \u2014 decides what it does with them. I have been writing " +
            "that plan myself and never asked. So: how should our deck " +
            "carry itself?"
        BRUTE ->
            "Something you should know: nobody plays these battles. The " +
            "deck does. The cards say what it can do; the battle plan says " +
            "how it behaves \u2014 a few standing orders, that's all. I've " +
            "been writing them myself without asking. How should it carry " +
            "itself?"
        SPRITE ->
            "Here's a thing worth knowing \u2014 nobody actually plays these " +
            "battles, the deck plays itself. The hundred cards decide what " +
            "it can do, and the battle plan is the handful of standing " +
            "orders it takes in with it. I've been writing those myself and " +
            "never thought to ask! How would you like it to carry itself?"
    }

    /** The other general order: what the deck goes after. */
    fun planAim(): String = when (this) {
        WIZARD ->
            "The other standing order worth your opinion. When one of our " +
            "cards reaches for something of theirs and does not name what, " +
            "what are we trying to take away from them?"
        BRUTE ->
            "Other order worth asking about. When something of ours picks " +
            "its own target, what are we trying to take off them?"
        SPRITE ->
            "There's one more order worth your say. When one of our cards " +
            "goes looking for something of theirs, what should we be trying " +
            "to take away?"
    }

    /**
     * What the agent decided underneath a general answer.
     *
     * The teaching half. A player said "build to one big turn"; this is the
     * agent naming the two atomic orders that follow from it, so the
     * mechanism stops being invisible. The decisions themselves come from
     * PlanPrefs.decisions() -- shared facts, framed per voice.
     */
    fun planDecided(orders: List<String>): String {
        val list = when (orders.size) {
            0 -> return done()
            1 -> orders[0]
            else -> orders.dropLast(1).joinToString(", ") + ", and " + orders.last()
        }
        return when (this) {
            WIZARD -> "Very good. Standing orders, then: I shall $list."
            BRUTE -> "Right. Orders are: $list. That's it."
            SPRITE -> "Lovely \u2014 so that's me told: I'll $list."
        }
    }

    /** How it addresses the player when it wants a decision. */
    fun which(): String = when (this) {
        WIZARD -> "Which?"
        BRUTE -> "Well?"
        SPRITE -> "What do you think?"
    }

    companion object {
        /**
         * Which voice goes with which face.
         *
         * Kept here rather than on the Avatar so that the ui layer owns the
         * picture and this layer owns the words -- Questions.kt has no
         * business importing a drawable id to find out how to phrase a
         * sentence.
         */
        fun forAvatar(key: String?): Voice = when (key) {
            "brute" -> BRUTE
            "sprite" -> SPRITE
            else -> WIZARD
        }
    }
}
