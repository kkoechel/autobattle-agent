"""Aggregate many cohorts, because one cohort is a sample, not a verdict.

A cohort is 69 matches at one seed per pairing. A deck's win count has a
single-cohort SD of about 6 wins on a mid-table list, so the deck that
finishes #1 in any given cohort is partly the deck that got the seeds. Worse,
each cohort is also one draw of the FIELD: 70 particular opponents, most of
which change by the next run. Tuning against a single snapshot fits both kinds
of noise at once.

Two things this makes possible:

1. Pick adoption targets on consistency across cohorts rather than one
   cohort's rank. A list that is top-5 in thirty cohorts is strong; a list
   that was #1 once may just have had a good day, and by the next cohort the
   crown had in fact moved to a different deck entirely.

2. Evaluate against a UNION of recent fields rather than the latest one, so
   an improvement has to hold against the metagame rather than against
   Tuesday.

What the API gives us: GET /results pages our own cohorts, GET
/results/{cohort_id} returns full 70-deck standings for any cohort we played
in, and GET /decks/{id}/public resolves any deck id -- not only ours -- to its
card list and battle plan. Together that is a real historical corpus, and it
accumulates on its own for as long as the agent keeps entering cohorts.
"""
from __future__ import annotations

import collections
import statistics
from dataclasses import dataclass


@dataclass
class DeckRecord:
    """One deck's record across cohorts.

    Keyed on deck_id, which players may EDIT -- the id survives a rewrite of
    the list, so a long history can describe cards the deck no longer plays.
    `updated_at` from GET /decks/{id}/public is the guard: a deck changed more
    recently than most of its record has a record about a different deck.
    """
    deck_id: int
    name: str
    owner: str
    appearances: int
    mean_rank: float
    best_rank: int
    mean_wins: float
    wins_sd: float
    top5: int

    # Decks in a Standard cohort average ~35 wins of 69. New entries are
    # shrunk towards that rather than believed.
    PRIOR_WINS = 35.0
    PRIOR_WEIGHT = 12.0

    @property
    def consistency(self) -> float:
        """Mean wins, shrunk towards the field average by sample size.

        Subtracting a standard error does not work here, and the failure is
        not subtle: a deck seen twice with identical results has SD 0, so the
        penalty is 0, and it outranks a deck seen 118 times at a higher mean.
        That is how a two-cohort newcomer beat Hymn (118 appearances, mean
        64.4) in the first version of this.

        Shrinkage does what the SE was supposed to: a deck is believed in
        proportion to how often it has actually been seen. Two cohorts at 65
        wins scores 40.0; a hundred and eighteen at 64.4 scores 61.6.
        """
        n = self.appearances
        return ((n * self.mean_wins + self.PRIOR_WEIGHT * self.PRIOR_WINS)
                / (n + self.PRIOR_WEIGHT))


def fetch(api, arena: str, want: int, cached: dict | None = None,
          log=print) -> dict:
    """Standings for the most recent `want` cohorts we played in.

    Incremental: cohorts already in `cached` are never re-fetched. GETs are
    not rate limited, but a hundred of them per cycle for data that cannot
    change is still rude.
    """
    store = dict(cached or {})
    have = set(store)
    rows, before = [], None
    while len(rows) < want:
        page = api.results(arena=arena, limit=min(100, want - len(rows)),
                           before=before)
        got = page.get("results") or []
        rows.extend(got)
        before = page.get("next_before")
        if not before or not got:
            break

    fetched = 0
    for r in rows:
        cid = str(r["cohort_id"])
        if cid in have:
            continue
        try:
            full = api._call("GET", f"/results/{r['cohort_id']}")
        except Exception as e:
            log(f"history: {r['cohort_id']} unavailable ({e.__class__.__name__})")
            continue
        store[cid] = {"closes_at": r["closes_at"],
                      "standings": full.get("standings") or []}
        fetched += 1
    if fetched:
        log(f"history: +{fetched} cohorts ({len(store)} held)")
    return store


def our_placements(store: dict, deck_ids: set[int]) -> dict[str, dict]:
    """Our best row per cohort, by deck id rather than by the is_you flag.

    With a rental entered, GET /results reports one row per cohort and marks
    the RENTAL as is_you even when the primary placed better -- observed live
    at rank 2 / 63 wins reported as rank 5 / 61. Anything judging our own
    performance has to look at the standings and match ids, or it grades the
    wrong deck.
    """
    out = {}
    for cid, cohort in store.items():
        rows = [r for r in cohort["standings"] if r.get("deck_id") in deck_ids]
        if rows:
            out[cid] = min(rows, key=lambda r: r["rank"])
    return out


def deck_records(store: dict, min_appearances: int = 3,
                 window: int | None = 24) -> list[DeckRecord]:
    """Per-deck performance over the most recent `window` cohorts, best first.

    The window is not a detail, it is the point. Players rewrite decks, and
    deck_id survives the rewrite, so a record covering all history averages
    two different decks together. Observed directly: RunicShieldbearer's deck
    sat at ~33 wins and rank 19 for six hours, then stepped to ~61 wins and
    rank 3 within one cohort and stayed there. Its all-time mean of 42.1 wins
    describes neither the deck that was nor the deck that is.

    So: one cohort is too few to judge a deck, and all of them are too many.
    `window=None` aggregates everything, which is only useful for studying how
    the metagame itself has moved.
    """
    cohorts = sorted(store.values(), key=lambda c: c["closes_at"])
    if window:
        cohorts = cohorts[-window:]

    seen: dict[int, list] = collections.defaultdict(list)
    meta: dict[int, tuple[str, str]] = {}
    for cohort in cohorts:
        for row in cohort["standings"]:
            did = row.get("deck_id")
            if not did:
                continue
            seen[did].append((row["rank"], row["wins"]))
            meta[did] = (row.get("name") or "", row.get("owner") or "")

    out = []
    for did, rows in seen.items():
        if len(rows) < min_appearances:
            continue
        ranks = [r for r, _ in rows]
        wins = [w for _, w in rows]
        name, owner = meta[did]
        out.append(DeckRecord(
            deck_id=did, name=name, owner=owner, appearances=len(rows),
            mean_rank=statistics.fmean(ranks), best_rank=min(ranks),
            mean_wins=statistics.fmean(wins),
            wins_sd=statistics.stdev(wins) if len(wins) > 1 else 0.0,
            top5=sum(1 for r in ranks if r <= 5),
        ))
    out.sort(key=lambda d: -d.consistency)
    return out


def union_field(api, records: list[DeckRecord], size: int = 70,
                log=print) -> list[dict]:
    """A field drawn from the whole history, not one cohort.

    Sampled across the ranking rather than taken from the top, because a field
    of nothing but the best decks is not the field we actually play: beating
    it says little about a cohort that is mostly mid-table.
    """
    if not records:
        return []
    step = max(1, len(records) // size)
    chosen = records[::step][:size]
    out = []
    for rec in chosen:
        try:
            deck = api.public_deck(rec.deck_id)
        except Exception:
            continue
        ids = deck.get("card_ids")
        if not ids:
            ids = []
            for c in deck.get("cards") or []:
                ids.extend([int(c["card_id"])] * int(c["quantity"]))
        if len(ids) < 90:
            continue
        out.append({"deck_id": rec.deck_id, "deck_name": rec.name,
                    "cards": ids, "battle_plan": deck.get("battle_plan") or {},
                    "rank": round(rec.mean_rank)})
    log(f"history: field of {len(out)} decks drawn from {len(records)} tracked")
    return out
