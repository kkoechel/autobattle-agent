"""Find cards and combinations nobody plays, by measuring them.

The optimiser answers "is this deck better"; this answers "what does this card
DO", which is a different question and needs a different shape. It is not
trying to win the next cohort -- it is trying to find something interesting,
and most of what it measures will be worthless. That is the expected outcome
of exploration and not a failure of it.

The field plays 394 of 515 playable cards, so the unexplored space is about
121 cards. Small enough to sweep exhaustively, which is the whole opportunity:
every one of them can be given a real measurement rather than an opinion.

Two things this deliberately does differently from the optimiser:

1. It reports effect sizes for everything, including negatives. A card that
   costs 8 wins is as informative as one that gains 2 -- more so, if it tells
   you why. The optimiser throws that away because it only wants the winner.

2. It looks for INTERACTION, not just value. A pair whose joint effect beats
   the sum of its parts is a combo; a pair that merely adds is two cards. The
   test is the same one an experimentalist would use, and it needs all three
   measurements (A, B, and AB) against the same baseline.
"""
from __future__ import annotations

import itertools
from dataclasses import dataclass

from .harness import Harness, Opponent
from .moves import as_counter, swap, swap_ranks


@dataclass
class CardResult:
    card_id: int
    name: str
    qty: int
    wins: float
    delta: float          # versus the untouched shell

    def __str__(self) -> str:
        return f"{self.name[:26]:26} x{self.qty:<2} {self.wins:5.1f}W {self.delta:+5.1f}"


def _variant(shell: list[int], plan: dict, cut: int, add: int, qty: int,
             limit: int, rank: int):
    return swap(shell, plan, cut, add, qty, limit, rank=rank)


def census(h: Harness, shell: list[int], plan: dict, opponents: list[Opponent],
           candidates: list[int], catalog: dict[int, dict], seeds: list[int],
           cut: int, qty: int = 5, rank: int = 2, batch_size: int = 40,
           log=print) -> list[CardResult]:
    """Substitute each candidate into one shell slot and measure it.

    Every candidate replaces the SAME cards at the SAME play-order rank, so
    the only thing varying between measurements is the card itself. Without
    that the numbers would compare cards against different decks and mean
    nothing -- which is the usual way this kind of survey goes wrong.
    """
    base = h.evaluate([("shell", shell, plan)], opponents, seeds)["shell"]
    log(f"shell: {base}")
    nm = lambda c: (catalog.get(c, {}).get("name") or f"#{c}")

    out: list[CardResult] = []
    for start in range(0, len(candidates), batch_size):
        chunk = candidates[start:start + batch_size]
        batch, keep = [], []
        for cid in chunk:
            limit = int(catalog.get(cid, {}).get("deck_limit") or 0)
            if limit <= 0:                 # deck_limit 0 cannot be played at all
                continue
            v = _variant(shell, plan, cut, cid, min(qty, limit), limit, rank)
            if v:
                batch.append((f"c{cid}", *v))
                keep.append((cid, min(qty, limit)))
        if not batch:
            continue
        res = h.evaluate(batch, opponents, seeds)
        for (cid, q) in keep:
            sc = res[f"c{cid}"]
            out.append(CardResult(cid, nm(cid), q, sc.wins, sc.wins - base.wins))
        log(f"  measured {min(start + batch_size, len(candidates))}/{len(candidates)}")

    out.sort(key=lambda r: -r.delta)
    return out


def interactions(h: Harness, shell: list[int], plan: dict,
                 opponents: list[Opponent], pairs: list[tuple[int, int]],
                 singles: dict[int, float], catalog: dict[int, dict],
                 seeds: list[int], cut: int, qty: int = 5, rank: int = 2,
                 log=print) -> list[tuple]:
    """Test pairs for super-additivity: is AB worth more than A plus B?

    `singles` must come from the SAME shell, cut slot and rank as the pairs,
    or the additive prediction it is compared against is not a prediction of
    anything. Synergy is (observed AB) - (A + B), both measured as deltas from
    the same baseline.
    """
    base = h.evaluate([("shell", shell, plan)], opponents, seeds)["shell"]
    nm = lambda c: (catalog.get(c, {}).get("name") or f"#{c}")

    batch, keep = [], []
    for a, b in pairs:
        la = int(catalog.get(a, {}).get("deck_limit") or 0)
        lb = int(catalog.get(b, {}).get("deck_limit") or 0)
        if la <= 0 or lb <= 0:
            continue
        v = _variant(shell, plan, cut, a, min(qty, la), la, rank)
        if not v:
            continue
        v2 = _variant(v[0], v[1], cut, b, min(qty, lb), lb, rank + 1)
        if not v2:
            continue
        batch.append((f"p{a}_{b}", *v2))
        keep.append((a, b))
    if not batch:
        return []

    res = h.evaluate(batch, opponents, seeds)
    out = []
    for a, b in keep:
        joint = res[f"p{a}_{b}"].wins - base.wins
        additive = singles.get(a, 0.0) + singles.get(b, 0.0)
        out.append((a, b, nm(a), nm(b), joint, additive, joint - additive))
    out.sort(key=lambda r: -r[6])
    return out


def pair_shortlist(results: list[CardResult], top: int = 12) -> list[tuple[int, int]]:
    """Pairs drawn from the best singles.

    Exhaustive pairing is out of reach -- 121 cards is 7260 pairs -- and the
    cards most likely to combine are the ones that already do something. This
    will miss a pair of individually-weak cards that only work together, which
    is a real blind spot and the honest cost of making the search finite.
    """
    ids = [r.card_id for r in results[:top]]
    return list(itertools.combinations(ids, 2))
