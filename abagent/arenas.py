"""Which cards an arena will actually accept.

is_playable() answers "can this card go in a deck at all" -- retired, VIP,
token, deck_limit 0. It says nothing about the ARENA, and the arenas differ
sharply: Pauper is common+infinite, Journeyman is everything EXCEPT common,
Legendary is legendary+infinite. A generator that ignores this builds a deck
the site revalidates and unregisters, and the symptom is an agent that looks
busy while entering no cohorts at all -- which has happened on this site
before, with an illegal Pauper deck running in real cohorts for seven hours.

The v1 API does not expose rules_json, so the allowed set is declared here and
CHECKED against the arena's live field on every run. Declaring alone would
drift silently the first time an arena's rules changed; checking alone would
inherit whatever the current field happens to play, which is a subset and not
a rule. Disagreement is reported rather than resolved, because a rule I cannot
read is not one I should guess at.
"""
from __future__ import annotations

from .moves import is_playable

# slug -> allowed rarities. None means "no rarity restriction".
# Derived from inc/arena_eligibility.php's rules_json contract and confirmed
# against each arena's live field on 2026-10-13.
ARENA_RARITIES: dict[str, set[str] | None] = {
    "pure": None,
    "pauper": {"common", "infinite"},
    "journeyman": {"uncommon", "rare", "mythic", "infinite"},
    "legendary": {"legendary", "infinite"},
    "chaos": None,
    "bot_challenge": None,
    "champion": None,
    "champion_standard": None,
    "tournament_standard": None,
    "tournament_pauper_tournament": {"common", "infinite"},
}

# Arenas whose decks are not freely built, so an agent cannot enter them by
# writing a deck: the pool is drawn per event, or the format picks pre-made
# teams, or entry is gated on a champion the generators never set.
NOT_BUILDABLE = {"random", "draft", "battleteams", "beginner"}


def allowed_rarities(slug: str) -> set[str] | None:
    return ARENA_RARITIES.get(slug, None)


def legal_catalog(catalog: dict[int, dict], slug: str) -> dict[int, dict]:
    """The generation catalogue for one arena.

    Filtering the catalogue is the single intervention point that covers every
    generator at once -- tag archetypes, measured archetypes, combo clusters,
    mutations and counter rounds all read it. The HARNESS keeps the full card
    list, because opponents play cards we may not: filtering the payload's
    card map would make their decks silently inert and every measurement a
    fiction.
    """
    allow = allowed_rarities(slug)
    if allow is None:
        return catalog
    return {cid: c for cid, c in catalog.items()
            if (c.get("rarity") in allow) or not is_playable(c)}


def check_against_field(meta_decks: list[dict], catalog: dict[int, dict],
                        slug: str, log=print) -> bool:
    """Does the declared rule match what the arena's decks actually contain?

    A rarity in the field but not in our set means the declaration is too
    narrow and we are handicapping ourselves. The reverse -- a rarity we allow
    that nobody plays -- is not evidence of anything and is not reported.
    """
    allow = allowed_rarities(slug)
    if allow is None:
        return True
    seen = {catalog.get(cid, {}).get("rarity")
            for d in meta_decks for cid in set(d.get("cards") or [])}
    seen.discard(None)
    extra = seen - allow
    if extra:
        log(f"arena: {slug} decks contain {sorted(extra)}, which our rule "
            f"excludes — the declaration in arenas.py is out of date")
        return False
    return True


# ---------------------------------------------------------------------------
# Tournaments
#
# Four daily tournaments, each with a 24h presubmission window, so there is
# always exactly one open cohort to enter. Entry is free -- no autogold is
# spent anywhere in POST /cohort/register or api/cohort.php -- but it is
# explicitly NOT standing:
#
#     $standing = empty($arena['is_tournament']);
#
# so a tournament writes cohort_deck_entries and never user_arena_decks. One
# registration buys one event, which is why this has to be re-run rather than
# asserted once. Re-registering the same deck is idempotent (ON DUPLICATE KEY
# UPDATE registered_at).
#
# The v1 API has no route that lists arenas, so these are declared, and the
# authority on whether a deck may enter stays the site's own deck_meets_rules()
# -- we skip what we can prove illegal and let a 400 answer the rest.
TOURNAMENTS: dict[str, set[str] | None] = {
    "tournament_standard": None,                      # rules {}
    "tournament_nightly_standard_tournament": None,   # rules []
    "tournament_pauper_tournament": {"common", "infinite"},
}

# requires_champion, and no generator ever sets a champion_id -- plans.py
# treats the champion fields as permanently dead. Listing it with a reason
# beats leaving a reader to wonder whether it was forgotten.
TOURNAMENTS_SKIPPED = {
    "tournament_champion_standard_tournament":
        "requires a champion; the generators never set one",
}


def tournament_rarities(slug: str) -> set[str] | None:
    return TOURNAMENTS.get(slug)


def deck_fits(cards: list[int], catalog: dict[int, dict], slug: str) -> bool:
    """Does this card list satisfy the rarity rule we have declared for `slug`?

    Only the rarity rule. singleton and allowed_card_ids are not used by any
    tournament we enter, and a card the catalogue does not know is treated as
    a fit rather than a miss: guessing "illegal" would silently skip an event
    over a stale cards.json, and the site rejects it cleanly anyway.
    """
    allow = TOURNAMENTS.get(slug)
    if allow is None:
        return True
    for cid in set(cards):
        c = catalog.get(cid)
        if c is not None and c.get("rarity") not in allow:
            return False
    return True


def illegal_cards(cards: list[int], catalog: dict[int, dict],
                  slug: str) -> list[int]:
    """Which of these cards this arena's rarity rule forbids.

    Deliberately NOT "which cards are missing from the legal catalogue".
    Membership conflates two different things: a rarity the arena disallows,
    and a card the catalogue simply does not know about. In a restricted arena
    those look the same, but in `pure` -- where nothing is filtered -- the only
    way to be absent is to be unknown, so a membership test would refuse to
    write a perfectly legal Standard deck the moment one card was retired
    between the fetch and the write. That is a wedge, not a guard.

    An unknown card is therefore treated as not-provably-illegal, matching
    deck_fits(): the site's own deck_meets_rules() is the authority and
    rejects it cleanly, whereas guessing "illegal" here fails silently.
    """
    allow = allowed_rarities(slug)
    if allow is None:
        return []
    bad = []
    for cid in sorted(set(cards)):
        c = catalog.get(cid)
        if c is not None and c.get("rarity") not in allow:
            bad.append(cid)
    return bad
