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


# ---------------------------------------------------------------------------
# EXPANSIONS. Arenas do not restrict these; LEAGUES do (inc/leagues.php), and
# a league's pool is EXACTLY the ticked expansions plus the infinite basics.
#
# The site stores rules_json.allowed_expansions as expansion IDs and pins
# cards.expansion_id per league at entry close (league_pin_expansions), so a
# card refiled mid-week cannot retroactively make a rival's deck illegal. The
# agent can see NEITHER: GET /cards exposes no expansion field at all. What it
# can see is a TAG per card, and that tag is a faithful proxy -- measured
# against the live payload on 2026-11-18, not assumed:
#
#   1422 cards; 1090 carry exactly one of the tags below; NONE carries two;
#   the 332 carrying none are EXACTLY the retired set -- set identity, not a
#   matching count -- so no PLAYABLE card is unfiled.
#
# Playable cards and total copies per expansion, same measurement, which is
# also the answer to "can a deck even be built for this week":
#
#   starter 423 cards / 4162 copies   khaos        202 / 1862
#   outworld      194 / 1797          skeletron-inv 179 / 1621
#   underworld     36 /  383          (a deck needs 100 copies)
#
# starter's 423 INCLUDES the three basics below -- they are filed under it --
# so an expansion's legal pool is "cards filed here, plus the basics", which
# is one set and not a sum. tools/test_expansions.py asserts it that way.
#
# Declared rather than discovered, for the same reason ARENA_RARITIES is: the
# payload's 325 tags give no way to tell an expansion tag from an ability tag,
# so the list is written down and then CHECKED -- see unfiled_playable().
EXPANSIONS: frozenset[str] = frozenset({
    "starter", "skeletron-inv", "khaos", "outworld", "underworld"})

# The always-legal basics, by rarity rather than by expansion: 3 cards (57
# Straw Man-at-Arms, 1 Ancient Power Station, 811 Brindeloid Warrior), each
# deck_limit 100. archetype.py already calls these "filler".
BASIC_RARITY = "infinite"


def expansions_of(card: dict) -> set[str]:
    """Which declared expansions this card is filed under (0 or 1 in practice)."""
    return EXPANSIONS & set(card.get("tags") or ())


def unfiled_playable(catalog: dict[int, dict]) -> list[int]:
    """Playable cards carrying none of the declared expansion tags.

    This is the CHECK half of declaring EXPANSIONS. A non-empty answer does
    not mean those cards are illegal -- it means a new expansion exists that
    this list does not know about, and every expansion-restricted deck built
    meanwhile is quietly drawing from a pool that is missing it. Reported, not
    resolved, exactly as check_against_field() handles a rarity surprise.

    The infinite basics are exempt: they are legal in every league whatever
    they are filed under, so their filing cannot be evidence of anything.
    (On the live payload all three DO carry a tag, so this exemption is a
    guard rather than a current need -- but a basic filed nowhere would
    otherwise raise a false alarm on every single run, which is how a check
    gets ignored.)
    """
    return sorted(cid for cid, c in catalog.items()
                  if is_playable(c)
                  and c.get("rarity") != BASIC_RARITY
                  and not expansions_of(c))


def legal_catalog(catalog: dict[int, dict], slug: str,
                  expansions: set[str] | frozenset[str] | None = None,
                  ) -> dict[int, dict]:
    """The generation catalogue for one arena, or one league.

    `expansions` restricts the pool to exactly those expansions plus the
    infinite basics, which is what a league does and no arena does. None or
    empty means no expansion restriction, so every existing caller is
    unchanged.

    Filtering the catalogue is the single intervention point that covers every
    generator at once -- tag archetypes, measured archetypes, combo clusters,
    mutations and counter rounds all read it. The HARNESS keeps the full card
    list, because opponents play cards we may not: filtering the payload's
    card map would make their decks silently inert and every measurement a
    fiction.
    """
    allow = allowed_rarities(slug)
    out = catalog if allow is None else {
        cid: c for cid, c in catalog.items()
        if (c.get("rarity") in allow) or not is_playable(c)}
    if not expansions:
        return out

    # GENERATION is strict where judging is lenient, and the asymmetry is
    # deliberate -- the same one this module already applies to rarity. A
    # playable card we cannot prove is in the allowed expansions costs us
    # nothing but a slightly smaller pool if we skip it, and costs a whole
    # league entry if we build with it: an entry whose deck fails the format
    # is skipped by every day's payload, so the agent would look entered and
    # never play a round (inc/leagues.php:193). illegal_cards() below keeps
    # the opposite rule, because refusing to WRITE a deck over a card we
    # merely do not recognise is the wedge its own docstring warns about.
    want = frozenset(expansions)
    unknown = want - EXPANSIONS
    if unknown:
        raise ValueError(f"unknown expansion(s) {sorted(unknown)}; "
                         f"declared: {sorted(EXPANSIONS)}")
    return {cid: c for cid, c in out.items()
            if not is_playable(c)
            or c.get("rarity") == BASIC_RARITY
            or (expansions_of(c) & want)}


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


def illegal_cards(cards: list[int], catalog: dict[int, dict], slug: str,
                  expansions: set[str] | frozenset[str] | None = None,
                  ) -> list[int]:
    """Which of these cards this arena's rarity rule -- or a league's
    expansion rule -- forbids.

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
    want = frozenset(expansions or ())
    if allow is None and not want:
        return []
    bad = []
    for cid in sorted(set(cards)):
        c = catalog.get(cid)
        if c is None:
            continue                      # unknown: not provably illegal
        if allow is not None and c.get("rarity") not in allow:
            bad.append(cid)
            continue
        # An expansion restriction forbids a card FILED elsewhere. A card
        # filed nowhere is unfiled, not foreign -- see unfiled_playable() --
        # and the site is the authority on it.
        if want and c.get("rarity") != BASIC_RARITY:
            filed = expansions_of(c)
            if filed and not (filed & want):
                bad.append(cid)
    return bad
