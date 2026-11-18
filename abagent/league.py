"""Entering the weekly leagues, and spending the one deck change a day.

WHY THIS IS A SEPARATE MODULE FROM THE ARENA PATH. An arena registration is
standing: write the deck, register once, and every future cohort includes
whatever that deck currently holds. A league is the opposite shape -- the
entry points at a deck id, each day's cohort freezes whatever that deck held
when the day began, and the deck the entry POINTS AT may be changed only once
per league day (inc/leagues.php league_deck_change_block_reason).

Which means the arena habit is actively wrong here. abagent rewrites its
working deck as often as it finds an improvement -- hourly -- and the
once-a-day rule governs the deck ID, not the deck's CONTENTS. Enter with the
working deck and the league deck changes every hour while change_count never
moves: a loophole, available to any player with the deck builder, which Ryan
has asked the main session to close by snapshotting the cards at change time.
Until then, and after, this module does not use it: it keeps a SEPARATE
league deck, frozen between promotions, and spends the real daily change to
move the working deck's list into it.

THE TIMING, measured from the scheduler rather than assumed
(shell/league_scheduler.php). A day's cohort is built from the entries at the
day boundary, so a change made during day N is first played on day N+1 --
and the previous day's cohort closes at that same instant and finalizes on a
later tick, so a day's results and the next day's lock fall inside the same
ten-minute window. Two consequences:

  * a change on the LAST day can never play: its cohort already exists;
  * waiting for a day's final standings means missing the lock they would
    inform, so the signal to act on is the day's PARTIAL standings.

With days_total = 5 that leaves four changes that can matter, which is the
whole experiment: day 1 plays what was entered, days 2-5 play what the
previous day's change produced.

NO LOCAL STATE. The server already reports last_change_day per entry, and it
is the authority on the rule -- a stored "I changed today" would be a second
copy of a fact we are told, wrong after any refusal, any clock skew, any
manual entry Ryan makes from the website.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from . import arenas


@dataclass(frozen=True)
class League:
    """One league as GET /leagues reports it, plus our own entry in it."""
    slug: str
    name: str
    format_slug: str                     # arena slug whose rules it copied
    expansions: frozenset[str] = frozenset()
    day: int = 0                         # 0 = not started; changes are free
    days_total: int = 5
    entries_closed: bool = False
    entered_deck_id: int | None = None
    last_change_day: int | None = None
    change_count: int = 0
    entered_deck_legal: bool | None = None   # None = the server did not say

    @property
    def entered(self) -> bool:
        return self.entered_deck_id is not None


@dataclass(frozen=True)
class Decision:
    """What to do about one league, and why -- the why is for the log."""
    act: str                             # "enter" | "promote" | "hold"
    reason: str
    deck_id: int | None = None
    illegal: list[int] = field(default_factory=list)


def legal_pool(catalog: dict[int, dict], lg: League) -> dict[int, dict]:
    """The generation catalogue for this league: its format AND its expansions."""
    return arenas.legal_catalog(catalog, lg.format_slug, lg.expansions or None)


def deck_blockers(cards: list[int], catalog: dict[int, dict],
                  lg: League) -> list[int]:
    """Cards this league provably forbids. Empty is not a promise of legality
    -- an unknown or unfiled card is not provably illegal and the site is the
    authority -- it only means we have no reason to expect a refusal."""
    return arenas.illegal_cards(cards, catalog, lg.format_slug,
                               lg.expansions or None)


def decide(lg: League, working_cards: list[int], catalog: dict[int, dict],
           league_deck_id: int | None,
           entered_cards: list[int] | None = None) -> Decision:
    """Enter, promote, or hold -- for ONE league. Pure, so it is testable
    without a route that does not exist yet.

    `league_deck_id` is the dedicated frozen deck this module keeps for the
    league; None means one has not been made. `entered_cards` is what the
    entered deck currently holds, when known.

    Order matters and each gate is a measured fact, not a preference:
    """
    # 1. Nothing to do if we are not in it and can no longer get in. Entries
    #    for a series league close AT starts_at -- entry_closes_at is set
    #    equal to it (inc/leagues.php) -- so there is no late entry.
    if lg.entries_closed and not lg.entered:
        return Decision("hold", "entries closed and we are not in it")

    # 2. Never write a deck the league forbids. An entry that fails the
    #    format is not refused at play time, it is SKIPPED by every day's
    #    payload: the agent would look entered and never play a round.
    bad = deck_blockers(working_cards, catalog, lg)
    if bad:
        return Decision("hold", f"{len(bad)} card(s) this league forbids",
                        illegal=bad)

    # 3. Getting in at all beats every refinement, so this precedes the
    #    daily-change gate: before the league starts, day 0, changes are free
    #    and unlimited anyway.
    if not lg.entered:
        if league_deck_id is None:
            return Decision("hold", "no league deck yet (one must be created)")
        return Decision("enter", f"entering {lg.name}", deck_id=league_deck_id)

    # 4. On the final day nothing can be gained: that day's cohort was built
    #    at the day boundary and there is no day after it to play. This sits
    #    ABOVE the illegal-entry check below deliberately -- an illegal entry
    #    on the last day is already past saving.
    if lg.day >= lg.days_total:
        return Decision("hold", f"day {lg.day} of {lg.days_total} is the last "
                                f"-- a change now could never play")

    # 5. An entered deck the league rejects scores nothing all week. Fixing
    #    it is worth the day's change whatever else is true.
    if lg.entered_deck_legal is False:
        if lg.day > 0 and (lg.last_change_day or 0) >= lg.day:
            return Decision("hold", "entered deck is ILLEGAL but today's "
                                    "change is already spent")
        return Decision("promote", "entered deck is ILLEGAL for this league",
                        deck_id=lg.entered_deck_id)

    # 6. The rule itself. Day 0 is exempt: before the league starts a change
    #    is still just entering.
    if lg.day > 0 and (lg.last_change_day or 0) >= lg.day:
        return Decision("hold", f"today's change is spent "
                                f"(last_change_day {lg.last_change_day})")

    # 7. Promote only something new. The working deck is already a monotone
    #    improvement -- nothing lands on it that did not beat the baseline on
    #    unseen seeds -- so an identical list means the search found nothing
    #    since the last promotion, not that a promotion is due.
    if entered_cards is not None and sorted(entered_cards) == sorted(working_cards):
        return Decision("hold", "the entered deck already holds this list")

    return Decision("promote", "promoting the working list", deck_id=lg.entered_deck_id)


def parse(row: dict) -> League:
    """One GET /leagues row -> League.

    Tolerant on purpose: the route does not exist yet, so this is the single
    place to correct when its real shape lands. A field we do not get back
    falls to the safe value -- unknown legality is None and not True, an
    unknown day is 0 (free changes, which the server will refuse if wrong).
    """
    entry = row.get("entry") or {}
    exps = row.get("expansions") or row.get("allowed_expansions") or []
    return League(
        slug=str(row.get("slug") or ""),
        name=str(row.get("name") or row.get("slug") or "a league"),
        format_slug=str(row.get("format") or row.get("arena") or "pure"),
        expansions=frozenset(str(e) for e in exps if isinstance(e, str)),
        day=int(row.get("day") or 0),
        days_total=int(row.get("days_total") or 5),
        entries_closed=bool(row.get("entries_closed")),
        entered_deck_id=(int(entry["deck_id"]) if entry.get("deck_id") else None),
        last_change_day=(int(entry["last_change_day"])
                         if entry.get("last_change_day") is not None else None),
        change_count=int(entry.get("change_count") or 0),
        entered_deck_legal=(bool(entry["deck_legal_now"])
                            if "deck_legal_now" in entry else None),
    )


def fetch(api, log=print) -> list[League]:
    """Every league we could act on. A 404 means the route is not deployed
    yet, which is not an error here -- it is the current state of the world."""
    try:
        rows = api.leagues()
    except Exception as e:                       # ApiError or transport
        status = getattr(e, "status", None)
        if status == 404:
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
# WHY TWO DECKS PER LEAGUE AND NOT ONE. The obvious implementation keeps one
# dedicated deck, enters it, and rewrites it when there is something better.
# That implementation cheats. The entry points at a deck id and the day's
# cohort reads that deck's CONTENTS at the day boundary, so rewriting the deck
# the entry already points at changes what plays while
# league_deck_change_block_reason is never consulted and change_count never
# moves -- the same loophole this module exists to avoid, just reached from
# inside.
#
# So each league gets a PAIR. Whichever deck the entry is not currently
# pointing at is the scratch one: write the new list there, then spend the
# day's change to point the entry at it. Every content change the league sees
# therefore passes through the server's rule, and change_count is an honest
# record of what we did. The entered deck is never written while it is
# entered.
#
# Slot arithmetic: 2 decks x 3 concurrent series = 6, against a limit of 17
# free (route_decks_post, api/v1/index.php) -- and orphaned pairs from
# finished leagues are renamed and reused rather than accumulating, because at
# three new leagues a week the limit would otherwise arrive in a fortnight.
LEAGUE_PREFIX = "[lg]"


def deck_names_for(lg: League) -> tuple[str, str]:
    """The pair of deck names this league uses. Trimmed to the API's 80."""
    stem = f"{LEAGUE_PREFIX} {lg.slug}"[:76]
    return (f"{stem} A", f"{stem} B")


def _deck_id(made: dict) -> int:
    return int(made["deck"]["id"] if "deck" in made else made["id"])


def ensure_pair(api, lg: League, decks: list[dict], live_slugs: set[str],
                log=print) -> tuple[int, int] | None:
    """The league's two deck ids, reusing or creating as needed.

    Reuse order: our own names first; then any `[lg]` deck belonging to a
    league that is no longer live (an orphan -- renamed, not deleted, because
    DELETE is the one irreversible call here and a rename is enough); then a
    new deck, which fails cleanly at the 17-deck limit.
    """
    by_name = {str(d.get("name") or ""): int(d["id"]) for d in decks}
    want = deck_names_for(lg)
    out: list[int] = []
    orphans = [int(d["id"]) for d in decks
               if str(d.get("name") or "").startswith(LEAGUE_PREFIX)
               and not any(str(d.get("name") or "").startswith(
                   f"{LEAGUE_PREFIX} {s}") for s in live_slugs)]
    for name in want:
        if name in by_name:
            out.append(by_name[name])
            continue
        if orphans:
            did = orphans.pop(0)
            try:
                api.update_deck(did, name=name)
                log(f"league: reusing orphaned deck {did} as '{name}'")
                out.append(did)
                continue
            except Exception as e:
                log(f"league: could not rename deck {did} ({e})")
        try:
            did = _deck_id(api.create_deck(name))
            log(f"league: created deck {did} '{name}'")
            out.append(did)
        except Exception as e:
            log(f"league: could not create '{name}' ({e})")
            return None
    return (out[0], out[1])


def execute(api, lg: League, d: Decision, pair: tuple[int, int],
            cards: list[int], plan: dict, log=print) -> bool:
    """Write the scratch deck, then spend the day's change to enter it.

    Returns True when the league's entry moved. The order is not arbitrary:
    the deck is written BEFORE the entry points at it, so the entry never
    names a list that does not exist yet, and the entered deck is never the
    one being written.
    """
    if d.act == "hold":
        log(f"league {lg.slug}: {d.reason}")
        return False

    scratch = pair[1] if lg.entered_deck_id == pair[0] else pair[0]
    counts: dict[int, int] = {}
    for cid in cards:
        counts[cid] = counts.get(cid, 0) + 1
    try:
        api.update_deck(scratch,
                        cards=[{"card_id": c, "quantity": q}
                               for c, q in sorted(counts.items())],
                        battle_plan=plan)
    except Exception as e:
        log(f"league {lg.slug}: could not write deck {scratch} ({e})")
        return False
    try:
        api.set_league_deck(lg.slug, scratch)
    except Exception as e:
        # A refusal here is the server enforcing its own rule, which is the
        # arrangement -- it is not an error to report as a failure.
        log(f"league {lg.slug}: the server refused the change ({e})")
        return False
    log(f"league {lg.slug}: {d.reason} — deck {scratch}, "
        f"{len(cards)} cards ({len(counts)} distinct), day {lg.day}")
    return True
