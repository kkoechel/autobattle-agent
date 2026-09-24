package online.autobattle.agent.chat

import online.autobattle.agent.engine.Catalogue

/**
 * Turns a typed sentence into one of the handful of things the app can do.
 *
 * This is a grammar and not a model on purpose. The vocabulary is closed --
 * four verbs over the catalogue's own tag list plus deck references -- and a
 * grammar over a closed vocabulary needs no key, no backend, no per-user cost,
 * and cannot invent a card or a mechanic that does not exist. We also measured
 * that an LLM added nothing to deck QUALITY: eight mechanism-backed swaps it
 * proposed all made the deck worse, and the search did the work.
 *
 * The honesty rule here is that an unparsed sentence says so. Guessing an
 * intent is worse than admitting one was not understood, because the actions
 * behind these verbs take 30 seconds of the phone's CPU and a wrong guess
 * spends all of it before the player learns they were misheard.
 */
sealed interface Intent {
    /** Score a deck against the live field. */
    data class Analyse(val deck: DeckRef) : Intent

    /** Generate a deck around a theme. `heard` is the user's own wording. */
    data class Build(val tag: String, val heard: String) : Intent

    /** Search for changes to a deck we already have. */
    data class Improve(val deck: DeckRef) : Intent

    /** Find answers to one named opponent. */
    data class Beat(val who: String) : Intent

    data object Help : Intent

    /** Not understood. Carries what we DID recognise, so the reply is useful. */
    data class Unsure(val heard: String, val nearestTags: List<String>) : Intent
}

sealed interface DeckRef {
    data object Active : DeckRef
    data class Id(val id: Int) : DeckRef
}

class Grammar(tagVocabulary: Set<String>) {

    /**
     * Phrases a player might type, mapped to the tag the engine knows.
     *
     * Only the ones where a player's word and the engine's word genuinely
     * differ. "poison" needs no alias; "lifegain" does, because the tag is
     * "life" and nobody calls it that. Entries pointing at a tag the
     * catalogue does not carry are dropped at construction rather than
     * silently matching nothing.
     */
    private val aliases = mapOf(
        "lifegain" to "life", "life gain" to "life", "heal" to "life",
        "healing" to "life", "gain life" to "life",
        "burn" to "damage", "direct damage" to "damage", "removal" to "destroy",
        "kill" to "destroy", "destruction" to "destroy",
        "toxic" to "poison", "venom" to "poison",
        "armor" to "shield", "armour" to "shield", "defence" to "shield",
        "defense" to "shield", "defensive" to "shield", "block" to "shield",
        "ramp" to "energy", "mana" to "energy", "acceleration" to "energy",
        "card draw" to "draw", "cantrip" to "draw",
        "decking" to "mill", "deck out" to "mill", "milling" to "mill",
        "tokens" to "token-copy", "swarm" to "token-copy", "go wide" to "token-copy",
        "copies" to "token-copy", "copy" to "token-copy",
        "elves" to "elf", "wolves" to "beast", "bees" to "bee",
        "recursion" to "recycle", "recur" to "recycle", "graveyard" to "recycle",
        "sac" to "sacrifice", "consume" to "utilize",
        "buff" to "anthem-melee", "lord" to "anthem-melee", "pump" to "anthem-melee",
        "aggro" to "melee", "aggressive" to "melee", "attack" to "melee",
        "honour" to "honor", "search" to "tutor", "cure" to "antidote",
        "bounce" to "bounce", "return" to "bounce",
        "growth" to "scaling", "grow" to "scaling", "snowball" to "scaling",
    )

    /** Normalised phrase -> canonical tag. Longest phrases matched first. */
    private val tagIndex: Map<String, String> = buildMap {
        for (t in tagVocabulary) {
            put(t.lowercase(), t)
            put(t.lowercase().replace('-', ' '), t)
        }
        // An alias may not shadow a real tag. "sacrifice" is itself a tag on 25
        // cards; an earlier draft aliased it to "utilize", and because aliases
        // were applied after the vocabulary it overwrote the direct match --
        // asking for a sacrifice deck silently built a different archetype.
        // Aliases whose TARGET is not in the vocabulary are dropped too:
        // "elves" pointed at a tag carried by 3 cards, which cannot headline
        // a 100-card list.
        for ((phrase, tag) in aliases) {
            if (phrase in this) continue
            if (tag in tagVocabulary) put(phrase, tag)
        }
    }

    private val vocab = tagVocabulary.toList().sorted()

    private val verbs = mapOf(
        "build" to setOf("build", "make", "create", "design", "generate", "brew",
                         "construct", "craft"),
        "improve" to setOf("improve", "tune", "optimise", "optimize", "upgrade",
                           "refine", "strengthen", "fix", "better", "sharpen"),
        "analyse" to setOf("analyse", "analyze", "score", "rate", "review",
                           "check", "evaluate", "assess", "test"),
        "beat" to setOf("beat", "counter", "answer", "stop", "handle", "defeat"),
    )

    private val helpWords = setOf("help", "commands", "what", "how", "who", "hi", "hello")

    private val OWN_DECK = Regex("""\bmy (deck|list|build|decks)\b""")

    fun parse(raw: String): Intent {
        val norm = normalise(raw)
        if (norm.isBlank()) return Intent.Help
        val words = norm.split(" ").filter { it.isNotEmpty() }

        if (words.size <= 4 && words.any { it in helpWords } && findTag(norm) == null
            && findVerb(words) == null) return Intent.Help

        val verb = findVerb(words)
        val tag = findTag(norm)

        // "beat Hymn" -- the target is a deck NAME, so it is whatever follows
        // the verb rather than anything from the tag vocabulary. Checked
        // before the tag branch, or "beat the poison deck" would be read as a
        // request to build one.
        if (verb == "beat") {
            val who = afterVerb(words, verbs["beat"]!!)
            return if (who.isBlank()) Intent.Unsure(raw.trim(), nearest(norm))
            else Intent.Beat(who)
        }

        if (verb == "build") {
            return if (tag != null) Intent.Build(tag, raw.trim())
            else Intent.Unsure(raw.trim(), nearest(norm))
        }

        if (verb == "improve") return Intent.Improve(deckRef(norm))
        if (verb == "analyse") return Intent.Analyse(deckRef(norm))

        // No verb, but the message is plainly about the player's own deck:
        // "how is my deck doing", "my list any good". Analyse is the safe
        // default to fall back on -- it is the cheap action (about three
        // seconds against thirty for a build) and it is the obvious reading.
        // This is checked BEFORE the bare-theme rule so that a question
        // mentioning a theme in passing is answered rather than acted on.
        if (OWN_DECK.containsMatchIn(norm)) return Intent.Analyse(deckRef(norm))

        // A bare theme is a build request -- "poison", "bees" -- but only when
        // that is essentially the whole message, so a long sentence mentioning
        // poison in passing is not silently acted on.
        if (tag != null && words.size <= 3) return Intent.Build(tag, raw.trim())

        return Intent.Unsure(raw.trim(), nearest(norm))
    }

    /**
     * A few themes worth suggesting, as examples in the greeting.
     *
     * Deliberately the commonest ones rather than a random sample: a
     * suggestion the generator cannot build a real deck around teaches the
     * player the wrong vocabulary on their first interaction.
     */
    fun someThemes(n: Int): List<String> =
        listOf("poison", "melee", "draw", "shield", "mill", "damage", "honor",
               "recycle", "token-copy", "energy")
            .filter { it in tagIndex.values }
            .take(n)

    /** Tags the catalogue actually carries, nearest to what was typed. */
    fun nearest(norm: String, limit: Int = 3): List<String> {
        val words = norm.split(" ").filter { it.length > 2 }
        if (words.isEmpty()) return emptyList()
        return vocab
            .map { t -> t to words.minOf { w -> distance(w, t.replace('-', ' ')) } }
            .filter { it.second <= 3 }
            .sortedWith(compareBy({ it.second }, { it.first }))
            .take(limit)
            .map { it.first }
    }

    private fun deckRef(norm: String): DeckRef {
        Regex("""\b(\d{3,})\b""").find(norm)?.let {
            return DeckRef.Id(it.groupValues[1].toInt())
        }
        return DeckRef.Active
    }

    private fun findVerb(words: List<String>): String? {
        for (w in words) for ((k, set) in verbs) if (w in set) return k
        return null
    }

    private fun afterVerb(words: List<String>, set: Set<String>): String {
        val i = words.indexOfFirst { it in set }
        if (i < 0) return ""
        return words.drop(i + 1)
            .filterNot { it in setOf("the", "a", "an", "my", "this", "that", "deck") }
            .joinToString(" ")
            .trim()
    }

    /**
     * Longest match wins: "token copy" is a different mechanic from "token",
     * and scanning shortest-first would resolve it to the wrong one.
     */
    private fun findTag(norm: String): String? {
        val words = norm.split(" ").filter { it.isNotEmpty() }
        for (n in minOf(3, words.size) downTo 1) {
            for (i in 0..(words.size - n)) {
                val phrase = words.subList(i, i + n).joinToString(" ")
                tagIndex[phrase]?.let { return it }
                tagIndex[depluralise(phrase)]?.let { return it }
            }
        }
        return null
    }

    private fun depluralise(s: String) = when {
        s.endsWith("ies") && s.length > 4 -> s.dropLast(3) + "y"
        s.endsWith("es") && s.length > 3 -> s.dropLast(2)
        s.endsWith("s") && !s.endsWith("ss") && s.length > 3 -> s.dropLast(1)
        else -> s
    }

    private fun normalise(s: String): String =
        s.lowercase().map { if (it.isLetterOrDigit() || it == '-') it else ' ' }
            .joinToString("").replace(Regex("\\s+"), " ").trim()

    private fun distance(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val sub = prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, sub)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    companion object {
        /** Themes worth offering: tags on playable cards, minus the noise. */
        fun vocabularyOf(cat: Catalogue): Set<String> {
            val noise = setOf(
                "common", "uncommon", "rare", "mythic", "legendary", "infinite",
                "token", "creature", "relic", "spell", "structure", "enchantment",
            )
            val counts = HashMap<String, Int>()
            for (id in cat.byId.keys) {
                if (!cat.isPlayable(id)) continue
                for (t in cat.tags(id)) {
                    if (t in noise || t.startsWith("trigger-")) continue
                    counts[t] = (counts[t] ?: 0) + 1
                }
            }
            // A tag on two cards cannot headline a 100-card deck. Offering it
            // produces a list that is 98% staples and reports back as the
            // theme the player asked for, which is a lie by construction.
            return counts.filterValues { it >= 6 }.keys
        }
    }
}
