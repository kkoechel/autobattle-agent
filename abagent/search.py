"""Coordinate descent over the battle plan.

Two things keep this from chasing noise:

1. Common random numbers. Every candidate in a sweep -- the incumbent
   included -- is played on the same seeds against the same opponents, so the
   comparison is paired and the seed variance largely cancels.

2. A paired confirmation round on FRESH seeds before anything is accepted.
   Picking the best of ~36 noisy estimates overestimates it (the winner's
   curse); a single cohort has a ~6-win SD, so the sweep's winner is often
   just the luckiest draw. The confirmation replays challenger and incumbent
   on the same unseen seeds and requires the per-seed win DIFFERENCE to clear
   `min_t` standard errors. Testing only the sign of the difference is not
   enough: at 21 seeds the SE is ~1.7 wins, so a "+0.4W confirmed" step is a
   coin flip that landed the right way, and stacking several of them tunes
   the plan to the seed block rather than to the game.
"""
from __future__ import annotations

import random
import statistics
import time
from dataclasses import dataclass

from .harness import Harness, Opponent, Score
from .plans import SCALAR_FIELDS, describe, is_live_field, neighbors


@dataclass
class Step:
    field: str
    before: object
    after: object
    gain: float
    t: float
    confirmed: bool


def paired_t(a: Score, b: Score) -> tuple[float, float]:
    """(mean win difference a-b, t statistic) over shared seeds.

    Both sides played the same seeds against the same opponents, so the
    per-seed difference cancels most of the field/seed variance -- a paired
    comparison resolves a gain several times smaller than either side's own
    single-cohort spread.
    """
    seeds = sorted(set(a.wins_by_seed) & set(b.wins_by_seed))
    diffs = [a.wins_by_seed[s] - b.wins_by_seed[s] for s in seeds]
    if len(diffs) < 2:
        return (0.0, 0.0)
    mean = statistics.fmean(diffs)
    sd = statistics.stdev(diffs)
    if sd == 0:
        return (mean, float("inf") if mean else 0.0)
    return (mean, mean / (sd / len(diffs) ** 0.5))


def _seed_block(rng: random.Random, n: int) -> list[int]:
    return [rng.randrange(1, 2_000_000_000) for _ in range(n)]


def climb(harness: Harness, cards: list[int], opponents: list[Opponent],
          start_plan: dict | None = None, card_info: dict[int, dict] | None = None,
          sweep_seeds: int = 5,
          confirm_seeds: int = 21, max_sweeps: int = 6, min_t: float = 2.0,
          confirm_top: int = 3, validate_seeds: int = 41,
          rng: random.Random | None = None, deadline: float | None = None,
          log=print) -> tuple[dict, Score, list[Step]]:
    rng = rng or random.Random(20260922)
    plan = dict(start_plan or {})
    history: list[Step] = []

    seeds = _seed_block(rng, confirm_seeds)
    incumbent = harness.evaluate([("incumbent", cards, plan)], opponents, seeds)["incumbent"]
    log(f"start  {describe(plan)}")
    log(f"       {incumbent}")

    for sweep in range(1, max_sweeps + 1):
        changed = False
        for field, values in SCALAR_FIELDS:
            # A shared box gets the work it was promised and no more: stop at
            # the deadline rather than running long into the next cohort.
            if deadline and time.time() > deadline:
                log(f"sweep{sweep}: deadline reached, stopping early")
                return plan, incumbent, history
            if not is_live_field(plan, field) and field != "play_priority":
                continue
            cand = neighbors(plan, field, values, cards, card_info)
            if not cand:
                continue

            sweep_block = _seed_block(rng, sweep_seeds)
            batch = [("__incumbent__", cards, plan)]
            batch += [(f"{field}={v!r}#{i}", cards, p)
                      for i, (v, p) in enumerate(zip(
                          [p[field] for p in cand], cand))]
            scored = harness.evaluate(batch, opponents, sweep_block)

            base = scored.pop("__incumbent__")
            ahead = [(lbl, sc) for lbl, sc in scored.items() if sc.key > base.key]
            if not ahead:
                continue

            # Confirm the top few, not just the leader. A 5-seed sweep ranks
            # ~10 values with a ~6-win SD each, so which one comes first is
            # substantially luck: observed live, the same field on the same
            # deck yielded energy_hold 5 -> 0 (+2.5W, t=2.4) on one run and
            # nothing on another, purely because 3 and 4 happened to lead the
            # sweep there and 0 never got a confirmation round.
            ahead.sort(key=lambda kv: kv[1].key, reverse=True)
            block = _seed_block(rng, confirm_seeds)
            trials = [cand[int(lbl.rsplit("#", 1)[1])] for lbl, _ in ahead[:confirm_top]]
            batch = [("keep", cards, plan)]
            batch += [(f"try{i}", cards, tp) for i, tp in enumerate(trials)]
            chk = harness.evaluate(batch, opponents, block)

            best_trial, best_gain, best_t = None, 0.0, 0.0
            for i, tp in enumerate(trials):
                gain, t = paired_t(chk[f"try{i}"], chk["keep"])
                if gain > 0 and t >= min_t and gain > best_gain:
                    best_trial, best_gain, best_t = tp, gain, t

            if best_trial is None:
                lead = trials[0]
                gain, t = paired_t(chk["try0"], chk["keep"])
                history.append(Step(field, plan.get(field), lead[field], gain, t, False))
                log(f"sweep{sweep} {field}: {len(trials)} candidate(s) led the "
                    f"sweep, none confirmed (best {gain:+.1f}W t={t:.1f} "
                    f"< {min_t}) -- rejected as noise")
                continue

            # Taking the best of several on the confirmation block makes that
            # block selection data. One final pre-specified test of the single
            # survivor, on seeds nothing was chosen on, is what makes the
            # accepted number mean what it says.
            vblock = _seed_block(rng, validate_seeds)
            vchk = harness.evaluate(
                [("keep", cards, plan), ("try", cards, best_trial)], opponents, vblock)
            best_gain, best_t = paired_t(vchk["try"], vchk["keep"])
            if not (best_gain > 0 and best_t >= min_t):
                history.append(Step(field, plan.get(field), best_trial[field],
                                    best_gain, best_t, False))
                log(f"sweep{sweep} {field}: {best_trial[field]!r} confirmed but "
                    f"failed validation ({best_gain:+.1f}W t={best_t:.1f}) "
                    f"-- rejected")
                continue

            history.append(Step(field, plan.get(field), best_trial[field],
                                best_gain, best_t, True))
            log(f"sweep{sweep} {field}: {plan.get(field)!r} -> {best_trial[field]!r}"
                f"  {best_gain:+.1f}W  t={best_t:.1f}  CONFIRMED"
                f"  (of {len(trials)} confirmed)")
            plan = best_trial
            incumbent = vchk["try"]
            changed = True

        if not changed:
            log(f"sweep{sweep}: no confirmed improvement, stopping")
            break

    log(f"final  {describe(plan)}")
    log(f"       {incumbent}")
    return plan, incumbent, history


def sweep_and_confirm(harness: Harness, base_cards: list[int], base_plan: dict,
                      cands: list[tuple[list[int], dict]], opponents: list[Opponent],
                      rng: random.Random, sweep_seeds: int, confirm_seeds: int,
                      min_t: float, confirm_top: int, validate_seeds: int = 41
                      ) -> tuple[list[int], dict, float, float] | None:
    """Select cheaply, narrow, then VALIDATE the single survivor.

    Three stages, and the third is not optional. Selecting the best of ~16
    sweep candidates and then taking the best of three on the confirmation
    block makes that block selection data too -- the winner's curse, one level
    up from where it was first fixed. Measured: a Void Flower swap that
    "confirmed" at +3.8W t=2.6 was worth +1.48W over 124 fresh seeds, and
    individual blocks of it ranged from -0.6W (t=-0.30) to +3.3W (t=2.47).

    So the chosen move faces one final pre-specified comparison, on seeds
    nothing has been selected on, with no max taken over anything. That test
    is unbiased because there is only one of it.
    """
    if not cands:
        return None

    block = _seed_block(rng, sweep_seeds)
    batch = [("__base__", base_cards, base_plan)]
    batch += [(f"c{i}", c, p) for i, (c, p) in enumerate(cands)]
    scored = harness.evaluate(batch, opponents, block)
    base = scored.pop("__base__")

    ahead = [(lbl, sc) for lbl, sc in scored.items() if sc.key > base.key]
    if not ahead:
        return None
    ahead.sort(key=lambda kv: kv[1].key, reverse=True)
    picks = [cands[int(lbl[1:])] for lbl, _ in ahead[:confirm_top]]

    block = _seed_block(rng, confirm_seeds)
    batch = [("keep", base_cards, base_plan)]
    batch += [(f"try{i}", c, p) for i, (c, p) in enumerate(picks)]
    chk = harness.evaluate(batch, opponents, block)

    best = None
    for i, (c, p) in enumerate(picks):
        gain, t = paired_t(chk[f"try{i}"], chk["keep"])
        if gain > 0 and t >= min_t and (best is None or gain > best[2]):
            best = (c, p, gain, t)
    if best is None:
        return None

    # Stage three: one pre-specified test of the survivor, on unseen seeds.
    cards, plan = best[0], best[1]
    block = _seed_block(rng, validate_seeds)
    final = harness.evaluate(
        [("keep", base_cards, base_plan), ("try", cards, plan)], opponents, block)
    gain, t = paired_t(final["try"], final["keep"])
    if gain > 0 and t >= min_t:
        return (cards, plan, gain, t)
    return None


def optimise(harness: Harness, cards: list[int], opponents: list[Opponent],
             meta_decks: list[dict], catalog: dict[int, dict],
             plan: dict | None = None, card_info: dict[int, dict] | None = None,
             sweep_seeds: int = 5, confirm_seeds: int = 21, min_t: float = 2.0,
             confirm_top: int = 3, rounds: int = 4, order_samples: int = 12,
             swap_samples: int = 16, rng: random.Random | None = None,
             deadline: float | None = None, log=print
             ) -> tuple[list[int], dict, list[str]]:
    """Plan, play order and card list, alternating -- because they interact.

    Order first within each round: a swap inherits the card_order slot of the
    card it replaces, so a better-ordered deck gives every subsequent swap a
    more meaningful slot to inherit.
    """
    from .moves import (add_candidates, as_counter, cut_candidates,
                        field_signal, order_moves, swap, swap_ranks)

    rng = rng or random.Random()
    plan = dict(plan or {})
    changes: list[str] = []
    present, copies = field_signal(meta_decks)
    nm = lambda c: (catalog.get(c, {}).get("name") or f"#{c}")

    for rnd in range(1, rounds + 1):
        moved = False

        if deadline and time.time() > deadline:
            log(f"round{rnd}: deadline reached, stopping")
            break

        # --- play order -------------------------------------------------
        cands = [(cards, p) for p in order_moves(plan, rng, order_samples)]
        got = sweep_and_confirm(harness, cards, plan, cands, opponents, rng,
                                sweep_seeds, confirm_seeds, min_t, confirm_top)
        if got:
            _, plan, gain, t = got
            head = ", ".join(nm(c) for c in (plan.get("card_order") or [])[:3])
            log(f"round{rnd} order: {gain:+.1f}W t={t:.1f} CONFIRMED "
                f"(now leads with {head})")
            changes.append(f"order {gain:+.1f}W")
            moved = True

        if deadline and time.time() > deadline:
            log(f"round{rnd}: deadline reached after order, stopping")
            break

        # --- card swaps, proposed by the field ---------------------------
        mine = as_counter(cards)
        adds = add_candidates(mine, present, copies)
        cuts = cut_candidates(mine, present, plan)
        # Each (cut, add) pair is tried at several card_order ranks, because
        # where the new card sits decides whether it is ever cast -- inheriting
        # the cut card's slot buries it at the back by construction.
        ranks = swap_ranks(len(plan.get("card_order") or []))
        cands, seen = [], []
        for add, typical in adds:
            limit = int(catalog.get(add, {}).get("deck_limit") or 1)
            for cut in cuts[:4]:
                qty = min(typical, limit, mine.get(cut, 0))
                if qty <= 0:
                    continue
                for rank in ranks:
                    out = swap(cards, plan, cut, add, qty, limit, rank=rank)
                    if out:
                        cands.append(out)
                        seen.append((cut, add, qty, rank))
                if len(cands) >= swap_samples:
                    break
            if len(cands) >= swap_samples:
                break

        got = sweep_and_confirm(harness, cards, plan, cands, opponents, rng,
                               sweep_seeds, confirm_seeds, min_t, confirm_top)
        if got:
            new_cards, new_plan, gain, t = got
            idx = next(i for i, (c, p) in enumerate(cands)
                       if c == new_cards and p == new_plan)
            cut, add, qty, rank = seen[idx]
            log(f"round{rnd} swap: -{qty} {nm(cut)} +{qty} {nm(add)} "
                f"@order[{rank}]  {gain:+.1f}W t={t:.1f} CONFIRMED")
            changes.append(f"-{qty} {nm(cut)} +{qty} {nm(add)} {gain:+.1f}W")
            cards, plan = new_cards, new_plan
            moved = True

        if not moved:
            log(f"round{rnd}: nothing confirmed, stopping")
            break

    return cards, plan, changes
