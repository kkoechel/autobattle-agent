"""Offline fitness: score candidate decks against a real arena field.

The validator binary is the same engine that scores live cohorts, so a result
here matches the real game exactly for a given seed. The only divergence from
live is seed choice, which is why everything below is averaged over several
seeds -- a single pass has roughly a +/-5 win (+/-7pp) spread, measured against
the live Standard standings.

Cohort placement sorts by wins desc, then losses asc (verified: 0 violations
across all 69 adjacent pairs of a live 70-deck Standard cohort). Fitness here
is exactly that, so the offline objective is the live objective.
"""
from __future__ import annotations

import json
import os
import statistics
import subprocess
import tempfile
from dataclasses import dataclass, field

# Engine payloads want the subtype under 'card_type', and tag effects match
# against 'tags' -- NOT 'ability_tags', which is a display-only derived set
# that differs on 342 of 859 cards and fails silently when wrong.
CARD_FIELDS = ("name", "cost", "supertype", "effects_json", "stats_json")


def cards_map(catalog: list[dict]) -> dict[str, dict]:
    out = {}
    for c in catalog:
        cid = int(c["id"])
        out[str(cid)] = {
            "card_id": cid,
            "name": c.get("name"),
            "cost": c.get("cost", 0),
            "supertype": c.get("supertype"),
            "card_type": c.get("subtype"),
            "effects_json": c.get("effects_json"),
            "stats_json": c.get("stats_json"),
            "tags": c.get("tags") or [],
        }
    return out


@dataclass
class Opponent:
    slot_id: str
    cards: list[int]
    battle_plan: dict = field(default_factory=dict)
    name: str = ""
    deck_id: int | None = None


def opponents_from_meta(meta: dict, exclude_deck_ids: set[int] | None = None) -> list[Opponent]:
    exclude = exclude_deck_ids or set()
    out = []
    for i, d in enumerate(meta["decks"]):
        if d.get("deck_id") in exclude:
            continue
        out.append(Opponent(
            slot_id=f"O{i}",
            cards=d["cards"],
            battle_plan=d.get("battle_plan") or {},
            name=d.get("deck_name", ""),
            deck_id=d.get("deck_id"),
        ))
    return out


@dataclass
class Score:
    """One candidate's simulated cohort record, averaged over seeds."""
    wins: float
    losses: float
    draws: float
    wins_sd: float
    n_seeds: int
    n_opponents: int
    per_opponent: dict[str, tuple[int, int, int]]  # slot_id -> (w, l, d) totals
    wins_by_seed: dict[int, int] = field(default_factory=dict)

    @property
    def wins_se(self) -> float:
        """Standard error of the mean -- the number that says whether a
        difference is real. wins_sd is the spread of a SINGLE cohort (what
        you'd see live); dividing by sqrt(n) is what the search must test
        against."""
        return self.wins_sd / (self.n_seeds ** 0.5) if self.n_seeds > 1 else 0.0

    @property
    def key(self) -> tuple[float, float]:
        """Live placement order: more wins first, then fewer losses."""
        return (self.wins, -self.losses)

    def __str__(self) -> str:
        return (f"{self.wins:5.1f}W {self.losses:5.1f}L {self.draws:5.1f}D "
                f"(SE {self.wins_se:.2f}, single-cohort SD {self.wins_sd:.1f}, "
                f"{self.n_seeds} seeds x {self.n_opponents} opponents)")


class Harness:
    def __init__(self, validator: str, catalog: list[dict], workdir: str | None = None):
        if not os.access(validator, os.X_OK):
            raise SystemExit(f"validator not executable: {validator}")
        self.validator = validator
        self.cards = cards_map(catalog)
        self.workdir = workdir

    def evaluate(self, candidates: list[tuple[str, list[int], dict]],
                 opponents: list[Opponent], seeds: list[int]) -> dict[str, Score]:
        """Score many candidates in ONE validator invocation.

        candidates: (label, card_id list with repeats, battle_plan)

        Batching matters: the cards map is ~800KB and process startup is fixed
        cost, so folding K candidates into one payload amortizes both. Slots
        are cheap; matches are the only thing that scales.
        """
        if not candidates or not opponents or not seeds:
            raise ValueError("need candidates, opponents and seeds")

        slots, matches = [], []
        for o in opponents:
            slots.append({"slot_id": o.slot_id, "cards": o.cards,
                          "battle_plan": o.battle_plan, "champion_id": None})

        label_by_slot = {}
        for k, (label, cards, plan) in enumerate(candidates):
            sid = f"C{k}"
            label_by_slot[sid] = label
            slots.append({"slot_id": sid, "cards": cards,
                          "battle_plan": plan or {}, "champion_id": None})
            for o in opponents:
                for si, s in enumerate(seeds):
                    # Alternate seats by seed INDEX, not by seed value, so
                    # every candidate plays seed i in the same chair. That
                    # keeps the comparison paired -- the whole reason these
                    # share seeds -- while giving each candidate half its
                    # games first and half second.
                    #
                    # Seat is not alternated by the live game: cohorts pair
                    # each deck once with slot_a fixed by slot order, so a
                    # deck plays every match from the same chair. Measured
                    # seat advantage was +0.4pp, but with 720 matches a side
                    # the standard error is ~2.6pp -- "not detectable" rather
                    # than "zero", and a real 2pp effect is ~1.4 wins across
                    # 69 pairings against a 0.4-win acceptance threshold. It
                    # cancels in paired A/B comparisons, where every candidate
                    # sat in the same chair; it does NOT cancel when a harness
                    # number is compared to a live one, which is exactly what
                    # adoption and re-basing decisions do.
                    first, second = ((sid, o.slot_id) if si % 2 == 0
                                     else (o.slot_id, sid))
                    matches.append({"match_id": f"{sid}|{o.slot_id}|{s}",
                                    "slot_a": first, "slot_b": second,
                                    "seed": s})

        results = self._run({"cards": self.cards, "slots": slots,
                             "matches": matches, "chaos_effect": ""})

        # Per (candidate, seed) tally, so each seed is one simulated cohort and
        # the spread across seeds is a real error bar rather than a guess.
        tally: dict[str, dict[int, list[int]]] = {sid: {s: [0, 0, 0] for s in seeds}
                                                  for sid in label_by_slot}
        per_opp: dict[str, dict[str, list[int]]] = {sid: {} for sid in label_by_slot}
        for r in results:
            # Attribution is by slot_id, not by seat, so it is unaffected by
            # the alternation above: winner_slot names the winning slot
            # whichever chair it sat in.
            sid, oid, seed = r["match_id"].split("|")
            seed = int(seed)
            w = r["winner_slot"]
            idx = 2 if w is None else (0 if w == sid else 1)
            tally[sid][seed][idx] += 1
            per_opp[sid].setdefault(oid, [0, 0, 0])[idx] += 1

        scored = {}
        for sid, label in label_by_slot.items():
            per_seed = [tally[sid][s] for s in seeds]
            wins = [x[0] for x in per_seed]
            scored[label] = Score(
                wins=statistics.fmean(wins),
                losses=statistics.fmean(x[1] for x in per_seed),
                draws=statistics.fmean(x[2] for x in per_seed),
                wins_sd=statistics.stdev(wins) if len(wins) > 1 else 0.0,
                n_seeds=len(seeds),
                n_opponents=len(opponents),
                per_opponent={k: tuple(v) for k, v in per_opp[sid].items()},
                wins_by_seed={s: tally[sid][s][0] for s in seeds},
            )
        return scored

    def _run(self, payload: dict) -> list[dict]:
        with tempfile.NamedTemporaryFile("w", suffix=".json", dir=self.workdir,
                                         delete=False) as fh:
            json.dump(payload, fh)
            path = fh.name
        try:
            proc = subprocess.run([self.validator, "--local-payload", path],
                                  capture_output=True, text=True)
            if proc.returncode != 0:
                raise RuntimeError(f"validator exited {proc.returncode}: "
                                   f"{proc.stderr[-2000:]}")
            return json.loads(proc.stdout)
        finally:
            os.unlink(path)
