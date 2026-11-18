"""Entering the weekly leagues, and spending the one deck change a day.

WHY THIS IS NOT THE ARENA PATH. An arena registration is standing: write the
deck, register once, and every future cohort plays whatever that deck
currently holds. A league entry is the other shape -- it may be changed only
once per league DAY (league_deck_change_block_reason) -- and since phase 96 it
plays a SNAPSHOT of the cards taken at entry or change, not the live deck.

That snapshot is what makes one dedicated deck per league correct. Before it,
rewriting an entered deck changed what played while the daily rule was never
consulted and change_count never moved; this module was first written with a
PAIR of decks to avoid using that loophole from the inside. The main session
closed it the same evening, so the pair is gone: write the league deck, then
re-submit it, and the re-submit is the change. `deck_edited_since_entry` is
the server telling us the live deck has drifted from what plays.

THE TIMING, from shell/league_scheduler.php rather than assumed. A day's
cohort is built from the entries at the day boundary, so a change made during
day N is first played on day N+1 -- and the previous day's cohort closes at
that same instant and finalizes on a later tick, so a day's results and the
next day's lock fall inside the same ten-minute window. Two consequences:

  * a change on the LAST day can never play: its cohort already exists;
  * waiting for a day's final standings means missing the lock they would
    inform, so the signal to act on is the day's PARTIAL standings.

With days_total = 5 that leaves four changes that can matter: day 1 plays
what was entered, days 2-5 play what the previous day's change produced.

NO LOCAL STATE, AND NO RE-IMPLEMENTED RULES. GET /leagues reports
`can_change_deck_now` and `change_block_reason` -- the website's own
league_deck_change_block_reason -- so this module asks rather than computing
day arithmetic a second time. It keeps only the two judgements the server
cannot make for us: whether a change could still PLAY (the last-day case) and
whether we have anything new to submit.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from . import arenas

# Expansion ids and display names are what GET /leagues reports
# (`allowed_expansions`, `allowed_expansion_names`); the only expansion signal
# on a CARD is its slug tag (arenas.EXPANSIONS). The authoritative id->slug
# map is expansion_slugs() in the main app's inc/expansions.php, which is a
# production DB read and not available here, so this is the bridge -- and it
# is deliberately only as wide as what has been confirmed.
#
# An unmapped expansion is NOT guessed. Guessing which slug "Outworld" means
# would at best be right and at worst build a deck from the wrong 200 cards
# and enter it, and an entry that fails the format is skipped by every day's
# payload -- the agent would look entered and play nothing all week. Unmapped
# means hold, loudly.
#
# WHAT STOPS A WRONG MAPPING REACHING A LIVE LEAGUE: the server validates the
# deck on PUT and answers 409 naming the illegal cards, so the worst case of a
# bad entry here is a refused submission that gets logged -- not a silent
# illegal entry that plays nothing for a week. That is why mapping the
# rotation's remaining names from the main session's own statement of the
# order (to-agent.md, 2026-11-17: "Skeletron's Invasion -> Starter Set ->
# Khaos -> Outworld") is acceptable where guessing would not be.
#
# TO RETIRE THIS: ask the main session for `allowed_expansion_slugs` in the
# league view, one line beside allowed_expansion_names.
EXPANSION_NAME_TO_SLUG: dict[str, str] = {
    "Skeletron's Invasion": "skeletron-inv",   # confirmed live, league 10
    "Starter Set": "starter",                  # from the stated rotation order
    "Khaos": "khaos",
    "Outworld": "outworld",
    "Underworld": "underworld",                # not in the rotation; declared
}


@dataclass(frozen=True)
class League:
    """One league as GET /leagues reports it, plus our own entry in it."""
    slug: str
    name: str
    rarities: frozenset[str] | None = None      # format_rules.allowed_rarities
    expansions: frozenset[str] = frozenset()    # slugs, mapped from names
    unmapped_expansions: tuple[str, ...] = ()   # names we could not map
    singleton: bool = False
    requires_champion: bool = False
    day: int = 0                                # current_day; 0 = not started
    days_total: int = 5
    entries_closed: bool = False
    can_change_now: bool = True
    change_block_reason: str | None = None
    entered_deck_id: int | None = None
    last_change_day: int | None = None
    change_count: int = 0
    deck_edited_since_entry: bool = False
    entered_deck_legal: bool | None = None      # None = the server did not say

    @property
    def entered(self) -> bool:
        return self.entered_deck_id is not None


@dataclass(frozen=True)
class Decision:
    """What to do about one league, and why -- the why is for the log."""
    act: str                                    # "enter" | "submit" | "hold"
    reason: str
    deck_id: int | None = None
    illegal: list[int] = field(default_factory=list)
    # "deck": held ONLY for want of a league deck, so a caller that can make
    # one should make it and ask again. Any other hold means making a deck
    # would be waste -- which it was, the first time this ran live: three
    # decks created for three leagues and two of them immediately held as
    # illegal.
    need: str | None = None


def legal_pool(catalog: dict[int, dict], lg: League) -> dict[int, dict]:
    """The generation catalogue for this league: its rarities AND expansions."""
    return arenas.legal_catalog_rules(catalog, lg.rarities, lg.expansions or None)


def deck_blockers(cards: list[int], catalog: dict[int, dict],
                  lg: League) -> list[int]:
    """Cards this league provably forbids. Empty is not a promise of legality
    -- an unknown or unfiled card is not provably illegal and the site is the
    authority -- it only means we have no reason to expect a refusal."""
    return arenas.illegal_by_rules(cards, catalog, lg.rarities,
                                   lg.expansions or None)


def decide(lg: League, working_cards: list[int], catalog: dict[int, dict],
           league_deck_id: int | None,
           league_deck: tuple[list[int], dict] | None = None,
           working_plan: dict | None = None) -> Decision:
    """Enter, submit, or hold -- for ONE league. Pure, so every gate is
    testable without touching the API.

    `league_deck_id` is the dedicated deck this module keeps for the league;
    None means one has not been made yet. The gates are in an order that
    matters, and each is a fact rather than a preference.
    """
    # 1. Formats the generators cannot build for at all. A champion arena is
    #    gated on a champion nothing here ever sets, and singleton would make
    #    every 4-of illegal.
    if lg.requires_champion:
        return Decision("hold", "needs a champion, which the generators never set")
    if lg.singleton and len(set(working_cards)) != len(working_cards):
        return Decision("hold", "singleton league and this deck has duplicates")

    # 2. An expansion we cannot map is an expansion we cannot build for. See
    #    EXPANSION_NAME_TO_SLUG: building from the wrong pool is worse than
    #    not entering, because an illegal entry plays nothing all week while
    #    looking entered.
    if lg.unmapped_expansions:
        return Decision("hold", "expansion(s) not mapped to a card tag: "
                                + ", ".join(lg.unmapped_expansions))

    # 3. Nothing to do if we are not in it and can no longer get in. Entries
    #    for a series league close AT starts_at, so there is no late entry.
    if lg.entries_closed and not lg.entered:
        return Decision("hold", "entries closed and we are not in it")

    # 4. Never submit a deck the league forbids.
    bad = deck_blockers(working_cards, catalog, lg)
    if bad:
        return Decision("hold", f"{len(bad)} card(s) this league forbids",
                        illegal=bad)

    # 5. Getting in at all beats every refinement. Before the league starts
    #    this is free and unlimited anyway (day 0).
    if not lg.entered:
        if league_deck_id is None:
            return Decision("hold", "no league deck yet (one must be created)",
                            need="deck")
        if not lg.can_change_now:
            return Decision("hold", f"cannot enter: {lg.change_block_reason}")
        return Decision("enter", f"entering {lg.name}", deck_id=league_deck_id)

    # 6. On the final day nothing can be gained: that day's cohort was built
    #    at the day boundary and there is no day after it. This outranks even
    #    fixing an illegal entry, which by then is past saving.
    if lg.day >= lg.days_total:
        return Decision("hold", f"day {lg.day} of {lg.days_total} is the last "
                                f"-- a change now could never play")

    # 7. The server owns the rule; we quote its answer rather than deriving
    #    our own from last_change_day.
    if not lg.can_change_now:
        return Decision("hold", lg.change_block_reason or "the server says not now")

    # 8. An entered deck the league rejects scores nothing for the rest of the
    #    week, so it is worth the day's change whatever else is true.
    if lg.entered_deck_legal is False:
        return Decision("submit", "entered deck is ILLEGAL for this league",
                        deck_id=lg.entered_deck_id)

    # 9. Submit only something new -- and judge that OURSELVES.
    #
    #    `deck_edited_since_entry` is the natural signal and it cannot be
    #    trusted yet. MEASURED 2026-11-18, bisected from outside the DB: it
    #    reads true the instant after a successful submit whenever the deck
    #    carries a non-empty battle plan. Submit with battle_plan {} -> false;
    #    restore the real plan and submit again -> true; nothing else touched,
    #    consecutive change_counts. league_entry_is_stale() compares the two
    #    battle_plan_json values as re-encoded STRINGS, and the cards side is
    #    an array (order-preserving) while a plan is an object, so only the
    #    plan trips it -- the two columns do not serialise their keys in the
    #    same order. Reported to the main session; one line fixes it (compare
    #    the decoded arrays with PHP's ==, which ignores key order).
    #
    #    Acting on it meanwhile would spend the day's ONE change every day on
    #    a list already playing, and then have nothing left when the search
    #    actually found something. So compare the league deck against the
    #    working deck, which is a fact we can check, and treat a difference in
    #    either the cards or the plan as the thing to submit.
    if lg.entered_deck_id != (league_deck_id or lg.entered_deck_id):
        return Decision("submit", "entry points at a different deck",
                        deck_id=league_deck_id)
    if league_deck is not None:
        have_cards, have_plan = league_deck
        if sorted(have_cards) == sorted(working_cards) and \
                have_plan == (working_plan or {}):
            return Decision("hold", "the league deck already holds this list"
                            + (" (server says edited; see decide() gate 9)"
                               if lg.deck_edited_since_entry else ""))
        what = ("cards" if sorted(have_cards) != sorted(working_cards)
                else "battle plan")
        return Decision("submit", f"the working {what} has moved on",
                        deck_id=lg.entered_deck_id)
    if not lg.deck_edited_since_entry:
        return Decision("hold", "what plays is already this list")

    return Decision("submit", "the deck has changed since it was entered",
                    deck_id=lg.entered_deck_id)


def parse(row: dict) -> League:
    """One GET /leagues row -> League.

    Falls to SAFE values, never optimistic ones: unknown legality is None and
    not True, an unknown day is 0, and an expansion name with no slug lands in
    unmapped_expansions rather than being dropped -- a silently dropped
    restriction is how a deck gets built from the wrong pool.
    """
    entry = row.get("entry") or {}
    rules = row.get("format_rules") or {}
    rar = rules.get("allowed_rarities")
    names = [str(n) for n in (row.get("allowed_expansion_names") or [])]
    mapped = {EXPANSION_NAME_TO_SLUG[n] for n in names if n in EXPANSION_NAME_TO_SLUG}
    unmapped = tuple(n for n in names if n not in EXPANSION_NAME_TO_SLUG)
    withdrawn = bool(entry.get("withdrawn"))
    return League(
        slug=str(row.get("slug") or ""),
        name=str(row.get("name") or row.get("slug") or "a league"),
        rarities=frozenset(str(r) for r in rar) if rar else None,
        expansions=frozenset(mapped),
        unmapped_expansions=unmapped,
        singleton=bool(rules.get("singleton")),
        requires_champion=bool(rules.get("requires_champion")),
        day=int(row.get("current_day") or 0),
        days_total=int(row.get("days_total") or 5),
        entries_closed=bool(row.get("entries_closed")),
        can_change_now=bool(row.get("can_change_deck_now")),
        change_block_reason=row.get("change_block_reason"),
        entered_deck_id=(int(entry["deck_id"])
                         if entry.get("deck_id") and not withdrawn else None),
        last_change_day=(int(entry["last_change_day"])
                         if entry.get("last_change_day") is not None else None),
        change_count=int(entry.get("change_count") or 0),
        deck_edited_since_entry=bool(entry.get("deck_edited_since_entry")),
        entered_deck_legal=(bool(entry["deck_legal_now"])
                            if "deck_legal_now" in entry else None),
    )


def fetch(api, log=print) -> list[League]:
    """Every league we could act on. A 404 means the routes are not deployed,
    which is a state of the world rather than an error."""
    try:
        rows = api.leagues()
    except Exception as e:
        if getattr(e, "status", None) == 404:
            log("league: GET /leagues is not deployed yet — nothing to do")
            return []
        log(f"league: could not read the leagues ({e})")
        return []
    out = []
    for r in rows:
        try:
            out.append(parse(r))
        except Exception as e:
            log(f"league: skipping an unreadable row ({e})")
    return out


# ---------------------------------------------------------------------------
# Carrying a decision out.
#
# One deck per league, named for its slug. Slots: 3 concurrent series out of
# 17 free decks (route_decks_post), and decks belonging to leagues that have
# finished are renamed and reused rather than accumulating -- at three new
# leagues a week the limit would otherwise arrive in a month. Renamed and not
# deleted, because DELETE is the only irreversible call here.
LEAGUE_PREFIX = "[lg]"


def deck_name_for(lg: League) -> str:
    return f"{LEAGUE_PREFIX} {lg.slug}"[:80]


def _deck_id(made: dict) -> int:
    return int(made["deck"]["id"] if "deck" in made else made["id"])


def ensure_deck(api, lg: League, decks: list[dict], live_slugs: set[str],
                log=print) -> int | None:
    """This league's deck id: ours by name, else an orphan renamed, else new."""
    want = deck_name_for(lg)
    for d in decks:
        if str(d.get("name") or "") == want:
            return int(d["id"])
    for d in decks:
        nm = str(d.get("name") or "")
        if nm.startswith(LEAGUE_PREFIX) and not any(
                nm.startswith(f"{LEAGUE_PREFIX} {s}") for s in live_slugs):
            try:
                api.update_deck(int(d["id"]), name=want)
                log(f"league: reusing orphaned deck {d['id']} as '{want}'")
                return int(d["id"])
            except Exception as e:
                log(f"league: could not rename deck {d['id']} ({e})")
    try:
        did = _deck_id(api.create_deck(want))
        log(f"league: created deck {did} '{want}'")
        return did
    except Exception as e:
        log(f"league: could not create '{want}' ({e})")
        return None


def execute(api, lg: League, d: Decision, deck_id: int,
            cards: list[int], plan: dict, log=print) -> bool:
    """Write the league deck, then submit it. Returns True if the entry moved.

    The order is not arbitrary: the deck is written BEFORE it is submitted,
    because the submit is what takes the snapshot that plays. Submitting
    first would freeze the old list and spend the day's change doing it.
    """
    if d.act == "hold":
        log(f"league {lg.slug}: {d.reason}")
        return False

    counts: dict[int, int] = {}
    for cid in cards:
        counts[cid] = counts.get(cid, 0) + 1
    try:
        api.update_deck(deck_id,
                        cards=[{"card_id": c, "quantity": q}
                               for c, q in sorted(counts.items())],
                        battle_plan=plan)
    except Exception as e:
        log(f"league {lg.slug}: could not write deck {deck_id} ({e})")
        return False
    try:
        out = api.set_league_deck(lg.slug, deck_id)
    except Exception as e:
        # A refusal is the server enforcing its own rule, which is the
        # arrangement -- not a failure to report as one.
        log(f"league {lg.slug}: the server refused ({e})")
        return False
    changed = out.get("changed") if isinstance(out, dict) else None
    log(f"league {lg.slug}: {d.reason} — deck {deck_id}, {len(cards)} cards "
        f"({len(counts)} distinct), day {lg.day}"
        + ("" if changed is None else f", changed={changed}"))
    return True
