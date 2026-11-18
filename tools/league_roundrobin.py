#!/usr/bin/env python3
"""Score every deck in a field file against every OTHER deck in it.

A league whose entrants are all ours is a self-play meta, and the question
that matters before it starts is not "is this deck strong" but "does one of
them dominate the other eight". One evaluate() does it: all N as candidates
against all N as opponents, then each deck's self-match is subtracted using
Score.per_opponent, which is exact rather than approximate -- a deck playing
itself is noise that would otherwise flatter the decks with the fewest draws.

    PYTHONPATH=/opt/abagent python3 tools/league_roundrobin.py \
        --field /opt/abagent/var/field_exp.json [--seeds 61]
"""
from __future__ import annotations

import argparse
import json
import os
import sys

# The repo when run from it, /opt/abagent when run from a copy on the host --
# these are one-off operator tools and they get copied around, so finding the
# package is their job rather than the caller's.
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
if not os.path.isdir(os.path.join(sys.path[0], "abagent")):
    sys.path.insert(0, "/opt/abagent")
from abagent.cli import VALIDATOR, VAR, _load                 # noqa: E402
from abagent.harness import Harness, opponents_from_meta      # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--field", required=True)
    ap.add_argument("--seeds", type=int, default=61)
    ap.add_argument("--seed0", type=int, default=770001)
    a = ap.parse_args()

    field = json.load(open(a.field))
    decks = field["decks"]
    if len(decks) < 2:
        print("need at least two decks")
        return 1
    cat_list = _load("cards.json")
    h = Harness(VALIDATOR, cat_list, workdir=VAR)
    opps = opponents_from_meta(field)                 # slot_id = O{i}
    cands = [(f"D{i}", d["cards"], d.get("battle_plan") or {})
             for i, d in enumerate(decks)]
    block = [a.seed0 + i for i in range(a.seeds)]
    print(f"round robin: {len(decks)} decks x {a.seeds} seeds, "
          f"self-matches excluded")
    scores = h.evaluate(cands, opps, block)

    rows = []
    for i, d in enumerate(decks):
        sc = scores[f"D{i}"]
        sw, sl, sd = sc.per_opponent.get(f"O{i}", (0, 0, 0))
        n = max(1, sc.n_seeds)
        rows.append((sc.wins - sw / n, sc.losses - sl / n, sc.draws - sd / n,
                     d.get("deck_name", "?"), d.get("deck_id")))
    rows.sort(key=lambda r: -r[0])
    width = len(decks) - 1
    print(f"  {'W':>5} {'L':>5} {'D':>5}  of {width} opponents")
    for w, l, dr, name, did in rows:
        print(f"  {w:5.2f} {l:5.2f} {dr:5.2f}  {str(did):>7}  {name[:46]}")
    spread = rows[0][0] - rows[-1][0]
    print(f"spread: {spread:.2f} wins between best and worst of {width}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
