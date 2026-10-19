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
        WIZARD -> step
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
