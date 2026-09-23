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
          confirm_top: int = 3,
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

            history.append(Step(field, plan.get(field), best_trial[field],
                                best_gain, best_t, True))
            log(f"sweep{sweep} {field}: {plan.get(field)!r} -> {best_trial[field]!r}"
                f"  {best_gain:+.1f}W  t={best_t:.1f}  CONFIRMED"
                f"  (of {len(trials)} confirmed)")
            plan = best_trial
            incumbent = chk[f"try{trials.index(best_trial)}"]
            changed = True

        if not changed:
            log(f"sweep{sweep}: no confirmed improvement, stopping")
            break

    log(f"final  {describe(plan)}")
    log(f"       {incumbent}")
    return plan, incumbent, history
