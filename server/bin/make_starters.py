#!/usr/bin/env python3
"""Build the starter pool: decks that land MID-TABLE against the live field.

A new player's first act used to be a cold archetype build, which comes last
-- the house agents have been iterating for days, and the first phone-built
deck scored 1-3-3 against them. Joining, waiting two minutes while the phone
heats up, and coming last is a bad first five minutes.

Mid-table is the target, not "good". A starter that beats the field removes
the game; one that loses to everything reproduces the problem. So candidates
are generated, measured against the real field, and the pool is the band
around the field's own median.

Replaces the pool rather than appending: decks that were mid-table against a
field that has since moved on are no longer mid-table.
"""
from __future__ import annotations

import json
import os
import statistics as st
import sys
import urllib.request

sys.path.insert(0, "/opt/abagent")
from abagent import harness  # noqa: E402
from abagent.archetype import build  # noqa: E402
from abagent.moves import is_playable  # noqa: E402

BASE = os.environ.get("AGENTGAME_BASE", "https://agent.autobattle.online/api")
TOKEN = os.environ.get("AGENTGAME_TOKEN", "")
ADMIN = os.environ.get("AGENTGAME_ADMIN", "")
CARDS = os.environ.get("AGENTGAME_CARDS", "/var/www/games/agentgame/var/cards.json")
VALIDATOR = os.environ.get("AGENTGAME_VALIDATOR",
                           "/opt/abagent/bin/validate_linux_amd64")
WORKDIR = os.environ.get("AGENTGAME_WORK", "/var/www/games/agentgame/var")
N_CAND = int(os.environ.get("STARTER_CANDIDATES", "48"))
N_KEEP = int(os.environ.get("STARTER_KEEP", "8"))
SEEDS = int(os.environ.get("STARTER_SEEDS", "31"))


def call(path, payload=None, admin=False):
    req = urllib.request.Request(
        BASE + path,
        data=None if payload is None else json.dumps(payload).encode(),
        method="POST" if payload is not None else "GET")
    if admin:
        req.add_header("X-Admin-Token", ADMIN)
    else:
        req.add_header("Authorization", "Bearer " + TOKEN)
    if payload is not None:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.loads(r.read().decode())


def expand(entries):
    out = []
    for e in entries or []:
        out += [int(e["card_id"])] * int(e["quantity"])
    return out


def main() -> int:
    if not TOKEN or not ADMIN:
        print("AGENTGAME_TOKEN and AGENTGAME_ADMIN must be set", file=sys.stderr)
        return 2
    field = [d for d in call("/field")["decks"] if d.get("cards")]
    if len(field) < 2:
        print("field too small to calibrate against")
        return 0
    cat_list = json.load(open(CARDS))
    catalog = {int(c["id"]): c for c in cat_list}
    banned = set(call("/cards").get("banned") or [])

    opps = [harness.Opponent(slot_id=f"o{i}", cards=expand(d["cards"]),
                             battle_plan=d.get("battle_plan") or {},
                             name=d["handle"])
            for i, d in enumerate(field)]
    meta = [{"cards": expand(d["cards"]),
             "battle_plan": d.get("battle_plan") or {}} for d in field]
    h = harness.Harness(VALIDATOR, cat_list, workdir=WORKDIR)
    block = [4_400_000 + i for i in range(SEEDS)]

    seeds = sorted(c for c in catalog
                   if c not in banned and is_playable(catalog[c])
                   and (catalog[c].get("rules_text") or "").strip())
    stride = max(1, len(seeds) // N_CAND)
    picked = seeds[::stride][:N_CAND]

    cands, built = [], {}
    for sd in picked:
        a = build(sd, catalog, meta)
        if not a or len(a.cards) != 100 or (banned & set(a.cards)):
            continue
        built[f"c{sd}"] = a
        cands.append((f"c{sd}", a.cards, a.plan))
    if not cands:
        print("could not build any candidate")
        return 1
    print(f"measuring {len(cands)} candidates vs {len(opps)} field decks, "
          f"{SEEDS} seeds", flush=True)
    sc = h.evaluate(cands, opps, block, drop_crashers=True, log=print)

    # The field's OWN median, measured the same way, is the target. Using an
    # absolute win count would drift the moment the field got stronger.
    fs = []
    for i, d in enumerate(field):
        others = [o for j, o in enumerate(opps) if j != i]
        s = h.evaluate([("f", expand(d["cards"]), d.get("battle_plan") or {})],
                       others, block, drop_crashers=True, log=print)
        if "f" in s:
            fs.append(s["f"].wins)
    target = st.median(fs) if fs else len(opps) / 2.0
    print(f"field median {target:.2f}W of {len(opps)}; "
          f"picking the {N_KEEP} candidates closest to it", flush=True)

    ranked = sorted(((abs(v.wins - target), k) for k, v in sc.items()))
    keep = []
    for _, k in ranked[:N_KEEP]:
        a = built[k]
        counts: dict[int, int] = {}
        for c in a.cards:
            counts[c] = counts.get(c, 0) + 1
        keep.append({
            "name": a.seed_name,
            "cards": [{"card_id": c, "quantity": q} for c, q in sorted(counts.items())],
            "battle_plan": a.plan,
            "wins": round(sc[k].wins, 2),
            "opponents": len(opps),
        })
        print(f"  {sc[k].wins:5.2f}W  {a.seed_name[:34]}")

    out = call("/starters", {"starters": keep}, admin=True)
    print(f"stored {out['starters']} starters")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
