package online.autobattle.agent.engine

/**
 * Names generated decks from their theme tags, so a deck arrives describing
 * itself. "archetype #47" tells a player nothing; "Rust and Ransom" says it
 * breaks its own relics for profit, which is what that deck does.
 */
object Names {

    private val TAG_WORDS: Map<String, Pair<String, String>> = mapOf(
        "poison" to ("Venom" to "Blight"),
        "damage" to ("Ember" to "Ruin"),
        "melee" to ("Iron" to "Fury"),
        "honor" to ("Gilded" to "Oath"),
        "shield" to ("Bulwark" to "Aegis"),
        "life" to ("Verdant" to "Grace"),
        "draw" to ("Whispering" to "Archive"),
        "mill" to ("Hollow" to "Oblivion"),
        "discard" to ("Ashen" to "Famine"),
        "energy" to ("Surging" to "Current"),
        "token-copy" to ("Teeming" to "Swarm"),
        "scaling" to ("Rising" to "Crescendo"),
        "cost-scaling" to ("Thrifty" to "Bargain"),
        "cost-modifier" to ("Patron" to "Tithe"),
        "exile" to ("Vanishing" to "Void"),
        "bounce" to ("Tidal" to "Undertow"),
        "anthem" to ("Banner" to "Chorus"),
        "on-tag-leave" to ("Rust" to "Ransom"),
        "drawback" to ("Bitter" to "Price"),
        "utilize" to ("Scavenging" to "Salvage"),
        "recycle" to ("Eternal" to "Return"),
        "structure" to ("Bastion" to "Keep"),
        "relic" to ("Reliquary" to "Hoard"),
        "soldier" to ("Marching" to "Legion"),
        "beast" to ("Feral" to "Wild"),
        "elf" to ("Sylvan" to "Court"),
        "dragon" to ("Wyrm" to "Pyre"),
        "bee" to ("Golden" to "Hive"),
        "divine" to ("Radiant" to "Choir"),
        "station" to ("Clockwork" to "Engine"),
        "elemental" to ("Storm" to "Tempest"),
        "token" to ("Legion" to "Host"),
        "scholar" to ("Studious" to "Codex"),
        "trigger-death" to ("Mourning" to "Wake"),
    )

    /**
     * Adjective from the first known tag, noun from the second, so the result
     * reads as a phrase rather than two nouns stapled together. Unknown tags
     * fall back to the seed card, which is at least specific.
     *
     * Names are NOT identity: the vocabulary is ~34 words, so genuinely
     * different decks collide constantly. Keying a "already tried" set on the
     * name once blackballed every good deck the generator had found.
     */
    fun forTheme(theme: List<String>, fallback: String): String {
        val known = theme.filter { it in TAG_WORDS }
        if (known.isEmpty()) return fallback
        if (known.size == 1) {
            val (adj, noun) = TAG_WORDS[known[0]]!!
            return "$adj $noun"
        }
        return "${TAG_WORDS[known[0]]!!.first} ${TAG_WORDS[known[1]]!!.second}"
    }
}
