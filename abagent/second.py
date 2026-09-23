"""Keep the Double Entry Pass slot filled with the right deck.

The pass lets one account enter two decks per cohort and pays only the
better-placing one. That makes the second slot worth more than it looks and
also easy to waste: what it buys is the MAXIMUM of two draws, and the maximum
of two correlated draws is worth almost nothing. Measured at 81 seeds against
a 64.0-win primary, the strongest available complement (63.7W, correlation
0.88 -- it was our own adopted list) was worth +0.3, while a weaker one
(62.0W, correlation 0.16) was worth +0.8. Picking by strength buys a third of
the available gain.

There is no GET for rental state, so nothing here can read back what is set.
Every assertion is therefore idempotent and simply re-applied: set_arena_deck
is cheap and safe to repeat, and a slot that silently emptied is otherwise
invisible until someone notices a cohort had one entry instead of two.

Reporting caveat that matters more than it sounds: when two decks are entered,
GET /results returns a single row per cohort and flags the RENTAL as is_you --
not the better-placing one. Observed live: the primary took rank 2 with 63
wins while /results reported rank 5 with 61. Any logic keyed on /results is
therefore reading the wrong deck, which is why our_rows() matches on deck ids
against the full standings instead.
"""
from __future__ import annotations

import datetime
import statistics

from .api import Api, ApiError


def our_rows(api: Api, cohort_id: int, deck_ids: set[int]) -> list[dict]:
    """Every row in a cohort belonging to us, by deck id.

    is_you is not usable for this: with a rental entered it marks the rental
    alone, so the primary's row -- often the better one -- is unflagged.
    """
    full = api._call("GET", f"/results/{cohort_id}")
    return [r for r in (full.get("standings") or [])
            if r.get("deck_id") in deck_ids]


def best_placement(api: Api, cohort_id: int, deck_ids: set[int]) -> dict | None:
    rows = our_rows(api, cohort_id, deck_ids)
    return min(rows, key=lambda r: r["rank"]) if rows else None


def ensure_pass(api: Api, expiry: str | None, min_days: int = 10,
                log=print) -> str | None:
    """Top the pass up when it is running out. Returns the new expiry.

    `expiry` must be remembered by the caller, because there is no endpoint
    that reports it -- the only way to learn the date is to buy a week and
    read the response. The first draft of this called purchase() to find out
    how much time was left, which spends 177 AG per call: on a ten-minute
    timer that is 25,488 AG a day to read a date.

    Renewal extends from the existing expiry rather than from now, so buying
    early wastes nothing and there is no reason to cut it fine. A lapsed pass
    does not error anywhere -- the second entry just stops, and the only
    symptom is cohorts quietly holding one of our decks instead of two.
    """
    today = datetime.date.today()
    if expiry:
        try:
            left = (datetime.date.fromisoformat(expiry[:10]) - today).days
        except ValueError:
            left = -1
        if left >= min_days:
            return expiry                    # nothing to do, nothing spent

    try:
        r = api._call("POST", "/deck_rental/purchase", body={})
    except ApiError as e:
        log(f"pass: renewal failed ({e})")
        return expiry
    exp = r.get("expires_at") or expiry
    try:
        left = (datetime.date.fromisoformat(exp[:10]) - today).days
    except (ValueError, TypeError):
        return exp
    log(f"pass: renewed, expires {exp[:10]} ({left}d)")
    return exp


def assert_second(api: Api, arena_type_id: int, deck_id: int, log=print) -> bool:
    """(Re-)assert the standing second deck. Idempotent, so just do it."""
    try:
        api._call("POST", "/deck_rental/set_arena_deck",
                  body={"arena_type_id": arena_type_id, "deck_id": deck_id})
        return True
    except ApiError as e:
        log(f"second: could not set deck {deck_id} ({e})")
        return False


def expected_max(a_by_seed: dict[int, int], b_by_seed: dict[int, int]) -> float:
    """Mean of max(A, B) per seed -- what the pass actually pays out."""
    shared = sorted(set(a_by_seed) & set(b_by_seed))
    if not shared:
        return 0.0
    return statistics.fmean(max(a_by_seed[s], b_by_seed[s]) for s in shared)


def pick_complement(harness, ours_cards: list[int], ours_plan: dict,
                    candidates: list[tuple[str, list[int], dict]],
                    opponents, seeds: list[int], log=print):
    """The candidate maximising E[max(ours, it)], not the strongest one.

    Both are scored on the same seeds so the per-seed maximum is a real
    pairing rather than two independent averages combined by arithmetic.
    """
    batch = [("__ours__", ours_cards, ours_plan)]
    batch += [(f"c{i}", c, p) for i, (_, c, p) in enumerate(candidates)]
    res = harness.evaluate(batch, opponents, seeds)
    ours = res["__ours__"]

    best = None
    for i, (name, cards, plan) in enumerate(candidates):
        sc = res[f"c{i}"]
        em = expected_max(ours.wins_by_seed, sc.wins_by_seed)
        gain = em - ours.wins
        log(f"  {name[:24]:24} {sc.wins:5.1f}W  E[max] {em:5.1f}  {gain:+.1f}")
        if best is None or em > best[3]:
            best = (name, cards, plan, em, gain)
    return best
