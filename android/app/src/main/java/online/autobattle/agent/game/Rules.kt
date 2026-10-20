package online.autobattle.agent.game

/**
 * What the player can ask the agent about how the game works.
 *
 * WHY THIS EXISTS. "Let's talk" was offered whenever the agent had no
 * question of its own, and most of the time it did nothing at all -- so the
 * one button a curious player could press was the one that answered least.
 * A player who wants to talk and has no decision in front of them wants to
 * understand the game, so that is what they get.
 *
 * EVERY NUMBER HERE WAS READ OUT OF THE ENGINE, not remembered:
 *
 *   startLife    = 100   engine.go:37
 *   honorToWin   = 100   engine.go:38
 *   maxHand      = 10    engine.go:40
 *   maxTurns     = 600   engine.go:39
 *   energy       += 1    engine.go:6893, phase 2 of the turn loop
 *   draw         1/turn, and tryDraw() failing is a LOSS (engine.go:6876)
 *   match        sim() plays two games with the first player swapped and
 *                returns a draw on 1-1 (engine.go:7305)
 *
 * AND NOTHING ABOUT CHAMPIONS. The engine has them; this league does not --
 * Payload.kt sends champion_id = null for every deck, so a player here can
 * never have one. Explaining a mechanic they cannot use would be a plausible
 * invention, which is the easiest kind of wrong answer to write.
 *
 * BANS ARE DELIBERATELY NOT EXPLAINED. Ryan's call, and it is the right one:
 * the pool rotates every seventeen hours and a player who knows the rule
 * starts playing the rule instead of the game.
 *
 * Answers are shared facts rather than per-voice rewrites, for the same
 * reason Voice holds framings and not three copies of everything: three sets
 * of these would drift the first time one was edited, and the drift would be
 * invisible until somebody picked that avatar. The agent's own framing comes
 * from the line it opens with.
 */
data class RuleQA(val q: String, val a: String)

object Rules {

    val ALL: List<RuleQA> = listOf(
        RuleQA(
            "How does a battle actually work?",
            "Neither of us is in it. Once the deck is submitted it fights on " +
            "its own, and a turn is always the same shape: it draws one card, " +
            "gains one energy, then plays whatever it can afford in the order " +
            "my standing orders give it. Things on the board trigger as they " +
            "trigger. At the end it discards down to ten cards in hand. Then " +
            "the other deck does the same, and so on until somebody wins."
        ),
        RuleQA(
            "How do we win?",
            "Three ways, and all of them count. Take them from a hundred life " +
            "down to nothing. Or get our own honour up to a hundred. Or outlast " +
            "their library \u2014 a deck that has to draw from an empty library " +
            "loses on the spot. If nobody has managed any of that after six " +
            "hundred turns, it goes to whoever is ahead on honour, then to " +
            "whoever is ahead on life, and if even that is level it is a draw."
        ),
        RuleQA(
            "Why do so many games end in a draw?",
            "Because a match is two games, not one, and the deck that goes " +
            "first swaps between them. To win the match we have to win both, " +
            "or win one and draw the other. One each is a draw, however " +
            "convincing either game was. It also means there is no advantage " +
            "to going first for me to chase \u2014 we get it exactly once either " +
            "way."
        ),
        RuleQA(
            "What do I control, if the deck plays itself?",
            "Two things, and they are the whole game. The hundred cards, which " +
            "decide what the deck is capable of. And the battle plan \u2014 the " +
            "standing orders it carries in \u2014 which decides what it does on a " +
            "turn when several things are possible. Nothing else. There is no " +
            "move to make once it starts."
        ),
        RuleQA(
            "Why does my library matter?",
            "Because drawing is a clock on us as well as a resource. A hundred " +
            "cards, one off the top every turn, and running out is a loss \u2014 " +
            "not a shrug. So a card that makes us draw twice is spending our " +
            "life total as surely as taking damage, and anything that empties " +
            "THEIR library is a way to win rather than a nuisance."
        ),
        RuleQA(
            "What is energy and why am I short of it?",
            "One a turn, every turn, and it stacks up if we do not spend it. " +
            "Cards cost it. That is the whole economy, and it is why a deck " +
            "full of expensive cards does nothing for the first several turns " +
            "while a cheap one is already on the board \u2014 and why some cards " +
            "exist only to give us more of it."
        ),
        RuleQA(
            "What goes in a deck?",
            "A hundred cards exactly. Each card has its own limit on how many " +
            "copies it allows, so a deck is never a hundred of one thing, and " +
            "the cheapest cards tend to allow the most. I pick the hundred; " +
            "you tell me what to point me at."
        ),
        RuleQA(
            "How does the league work?",
            "A round is scored every fifteen minutes, and in each one every " +
            "player's deck plays every other player's deck. Nobody has to be " +
            "awake for it. Your record is what comes out of that, and the " +
            "deck that plays is whatever you last submitted \u2014 so a deck left " +
            "alone keeps playing, and keeps scoring."
        ),
    )
}
