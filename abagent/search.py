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


# Statistical significance is not the same as being worth doing. With 161
# seeds the harness resolves a 0.1-win difference, but a single cohort has a
# ~2.2-win SD, so a 0.1-win change is a twentieth of the noise in the only
# outcome that matters -- it cannot move placement, and accepting it just
# rewrites the deck for nothing.
#
# Measured over 131 validation tests on the adopted list, the distribution of
# gains was symmetric about zero (+0.0 x59, -0.0 x24, +0.1 x20, -0.1 x10),
# which is what a search finds when there is nothing left to find. At t>=2.0
# one-sided, chance alone predicts ~3 false accepts in that many tests, and 8
# changes were accepted -- including discard_priority walking cheapest ->
# costliest -> lowest_priority, banking +0.1W per step.
MIN_GAIN = 0.4


def accept(gain: float, t: float, min_t: float, min_gain: float = MIN_GAIN) -> bool:
    """Both gates: big enough to matter, and clear enough to believe."""
    return gain >= min_gain and t >= min_t


def _seed_block(rng: random.Random, n: int) -> list[int]:
    return [rng.randrange(1, 2_000_000_000) for _ in range(n)]


def climb(harness: Harness, cards: list[int], opponents: list[Opponent],
          start_plan: dict | None = None, card_info: dict[int, dict] | None = None,
          sweep_seeds: int = 21,
          confirm_seeds: int = 21, max_sweeps: int = 6, min_t: float = 2.0,
          confirm_top: int = 3, validate_seeds: int = 161,
          min_gain: float = MIN_GAIN, rng: random.Random | None = None, deadline: float | None = None,
          log=print) -> tuple[dict, Score | None, list[Step]]:
    rng = rng or random.Random(20260922)
    plan = dict(start_plan or {})
    history: list[Step] = []

    # The baseline is itself a full evaluation, so check the clock before
    # paying for it. Without this a 4-second budget still bought 86 seconds of
    # scoring before the first deadline check inside the sweep loop.
    if deadline and time.time() >= deadline:
        log("climb: no budget left, skipping")
        return plan, None, history

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

            # Two stages, not three. The screen now runs at enough seeds to
            # actually rank (5 could not: ~3.4 SE against ~1.5W effects), so
            # the middle round that re-picked among the top few was choosing
            # on noise a second time and reintroducing the selection bias it
            # existed to remove. Screen once, then test the single leader.
            ahead.sort(key=lambda kv: kv[1].key, reverse=True)
            best_trial = cand[int(ahead[0][0].rsplit("#", 1)[1])]

            vblock = _seed_block(rng, validate_seeds)
            vchk = harness.evaluate(
                [("keep", cards, plan), ("try", cards, best_trial)], opponents, vblock)
            best_gain, best_t = paired_t(vchk["try"], vchk["keep"])
            if not accept(best_gain, best_t, min_t, min_gain):
                history.append(Step(field, plan.get(field), best_trial[field],
                                    best_gain, best_t, False))
                log(f"sweep{sweep} {field}: {best_trial[field]!r} led the screen, "
                    f"failed validation ({best_gain:+.1f}W t={best_t:.1f}) "
                    f"-- rejected")
                continue

            history.append(Step(field, plan.get(field), best_trial[field],
                                best_gain, best_t, True))
            log(f"sweep{sweep} {field}: {plan.get(field)!r} -> {best_trial[field]!r}"
                f"  {best_gain:+.1f}W  t={best_t:.1f}  VALIDATED"
                f"  (led a {len(cand)}-candidate screen)")
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
                      min_t: float, confirm_top: int, validate_seeds: int = 161,
                      min_gain: float = MIN_GAIN
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

    # Stage one: screen. This used to run at 5 seeds, which does not work.
    # The paired SE is ~1.38 wins at 31 seeds, so at 5 it is ~3.44 -- and the
    # effects actually on offer here are ~1.5 wins, i.e. 0.44 SE. A screen at
    # that resolution is not ranking candidates, it is shuffling them, and the
    # expensive validation downstream was being spent on whichever candidate
    # noise happened to favour. Fewer candidates with enough seeds to separate
    # them beats more candidates sorted at random, for the same total cost.
    block = _seed_block(rng, sweep_seeds)
    batch = [("__base__", base_cards, base_plan)]
    batch += [(f"c{i}", c, p) for i, (c, p) in enumerate(cands)]
    scored = harness.evaluate(batch, opponents, block)
    base = scored.pop("__base__")

    ahead = [(lbl, sc) for lbl, sc in scored.items() if sc.key > base.key]
    if not ahead:
        return None
    ahead.sort(key=lambda kv: kv[1].key, reverse=True)
    cards, plan = cands[int(ahead[0][0][1:])]

    # Stage two: one pre-specified test of that single survivor, on seeds
    # nothing was selected on. One candidate means no max is taken over
    # anything, which is what makes the number unbiased. The old middle stage
    # is gone: with a screen that resolves, it was choosing among candidates
    # again and reintroducing the selection bias it was meant to remove.
    block = _seed_block(rng, validate_seeds)
    final = harness.evaluate(
        [("keep", base_cards, base_plan), ("try", cards, plan)], opponents, block)
    gain, t = paired_t(final["try"], final["keep"])
    if accept(gain, t, min_t, min_gain):
        return (cards, plan, gain, t)
    return None


def optimise(harness: Harness, cards: list[int], opponents: list[Opponent],
             meta_decks: list[dict], catalog: dict[int, dict],
             plan: dict | None = None, card_info: dict[int, dict] | None = None,
             sweep_seeds: int = 21, confirm_seeds: int = 21, min_t: float = 2.0,
             confirm_top: int = 3, validate_seeds: int = 161,
             min_gain: float = MIN_GAIN, rounds: int = 4, order_samples: int = 6,
             swap_samples: int = 6, rng: random.Random | None = None,
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
                                sweep_seeds, confirm_seeds, min_t, confirm_top,
                                validate_seeds, min_gain)
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
                               sweep_seeds, confirm_seeds, min_t, confirm_top,
                               validate_seeds, min_gain)
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


def counter_round(harness: Harness, cards: list[int], plan: dict,
                  opponents: list[Opponent], catalog: dict[int, dict],
                  rng: random.Random, screen_seeds: int = 41,
                  validate_seeds: int = 161, min_t: float = 2.0,
                  focus_k: int = 6, samples: int = 10,
                  min_focus_gain: float = 0.5, min_gain: float = MIN_GAIN,
                  log=print
                  ) -> tuple[list[int], dict, float, float] | None:
    """Target the few matchups that still cost us, screened against them alone.

    Once a deck beats most of the field, its remaining upside is concentrated
    in a handful of opponents -- for ours, 53 of 69 matchups are already ~100%
    wins and everything left sits in six decks. Fixing one of those is worth
    well under a win across the full field, which is below what any affordable
    number of seeds can resolve. The effect is not small; it is DILUTED, 69:1.

    So candidates are screened against the focus opponents only. There the
    effect is undiluted and obvious, and scoring six opponents instead of
    sixty-nine is also ten times cheaper, which buys the seeds to see it.

    Acceptance is unchanged: the survivor must improve TOTAL wins against the
    full field, significantly, on unseen seeds. The focus set makes good
    candidates findable; it never decides whether one is kept. A counter that
    wins a matchup and loses the tournament is not a counter.
    """
    from .moves import (as_counter, counter_candidates, focus_opponents,
                        swap, swap_ranks)

    block = _seed_block(rng, screen_seeds)
    base_full = harness.evaluate([("me", cards, plan)], opponents, block)["me"]
    focus_ids = focus_opponents(base_full.per_opponent, focus_k)
    if not focus_ids:
        return None
    focus = [o for o in opponents if o.slot_id in focus_ids]
    log(f"counter: targeting {', '.join(o.name[:18] for o in focus)}")

    mine = as_counter(cards)
    adds = counter_candidates(mine, [o.cards for o in focus], catalog)
    if not adds:
        return None

    order = plan.get("card_order") or []
    rank_of = {cid: i for i, cid in enumerate(order)}
    spare = sorted((c for c in mine), key=lambda c: -rank_of.get(c, 9999))
    ranks = swap_ranks(len(order))

    cands, seen = [], []
    for add, qty in adds:
        limit = int(catalog.get(add, {}).get("deck_limit") or 1)
        for cut in spare[:3]:
            for r in ranks[:2]:
                out = swap(cards, plan, cut, add, min(qty, limit), limit, rank=r)
                if out:
                    cands.append(out)
                    seen.append((cut, add, min(qty, limit)))
            if len(cands) >= samples:
                break
        if len(cands) >= samples:
            break
    if not cands:
        return None

    # Screen against the focus decks only -- the whole point of the round.
    fblock = _seed_block(rng, screen_seeds)
    batch = [("__base__", cards, plan)]
    batch += [(f"c{i}", c, p) for i, (c, p) in enumerate(cands)]
    scored = harness.evaluate(batch, focus, fblock)
    base = scored.pop("__base__")
    ahead = [(lbl, sc) for lbl, sc in scored.items() if sc.key > base.key]
    if not ahead:
        log(f"counter: none of {len(cands)} candidates beat the focus set")
        return None
    ahead.sort(key=lambda kv: kv[1].key, reverse=True)
    idx = int(ahead[0][0][1:])
    new_cards, new_plan = cands[idx]
    cut, add, qty = seen[idx]
    fgain = ahead[0][1].wins - base.wins

    # The screen ranks on (wins, -losses), so a candidate can lead having
    # gained no wins at all -- only the loss tiebreak, which over six
    # opponents is noise. The point of screening against the focus set is
    # that a real fix looks BIG there; promoting a flat one just spends
    # ~145s of full-field validation to reject it.
    if fgain < min_focus_gain:
        log(f"counter: best candidate gains only {fgain:+.1f} vs the focus "
            f"set (<{min_focus_gain}) -- not worth validating")
        return None
    nm = lambda c: (catalog.get(c, {}).get("name") or f"#{c}")
    log(f"counter: -{qty} {nm(cut)} +{qty} {nm(add)} gains {fgain:+.1f} "
        f"vs the focus set; checking the whole field")

    # Acceptance: total wins, full field, unseen seeds, one pre-specified test.
    vblock = _seed_block(rng, validate_seeds)
    chk = harness.evaluate([("keep", cards, plan), ("try", new_cards, new_plan)],
                           opponents, vblock)
    gain, t = paired_t(chk["try"], chk["keep"])
    if accept(gain, t, min_t, min_gain):
        return (new_cards, new_plan, gain, t)
    log(f"counter: rejected -- {gain:+.1f}W t={t:.1f} across the full field")
    return None
