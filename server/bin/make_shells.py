#!/usr/bin/env python3
"""Build the quick-build pool: the BEST measured deck per archetype.

The sibling of make_starters.py, and the difference is the whole point.
`starters` targets the field's own median, because a first deck that wins
removes the game and one that loses to everything reproduces the problem a
starter exists to solve. A SHELL is different: the player picked it, and is
about to spend a minute refining it. It should be the strongest version of
that idea this field has seen.

The phone then runs three or four refine passes on it instead of the twelve
a cold climb needs. Candidate generation was never the cost -- composing all
eighty-one themes takes half a second of pure CPU. Measuring is the cost, it
depends on a field that moves, and it is what this precomputes.

WHY IT DOES NOT RUN EVERY ROUND. agentgame-round.timer fires every thirty
minutes and a round takes eighteen to twenty to score thirty-nine decks, so
the box is busy most of the time already. This measures twenty-four
candidates against the whole field and costs about as much as a round; at
four-hourly it is affordable, and the field barely moves faster than that
while the bot decks are frozen.

NEAR-TWINS ARE ONE SHELL, NOT TWO. Composing the eight gods' decks from
different tag sets produced two BYTE-IDENTICAL hundreds, because
{demon,horror,sacrifice} and {sacrifice,drawback,piercing} land on the same
archetype. A list of near-twins is a list of one, so a candidate sharing
most of its cards with one already kept is dropped however well it measured.
"""
from __future__ import annotations

import collections
import json
import os
import sys
import urllib.request

sys.path.insert(0, "/opt/abagent")
from abagent import harness  # noqa: E402
from abagent.archetype import build, prune_dead_clauses  # noqa: E402
from abagent.moves import is_playable  # noqa: E402

BASE = os.environ.get("AGENTGAME_BASE", "https://agent.autobattle.online/api")
TOKEN = os.environ.get("AGENTGAME_TOKEN", "")
ADMIN = os.environ.get("AGENTGAME_ADMIN", "")
CARDS = os.environ.get("AGENTGAME_CARDS",
                       "/var/www/games/agentgame/var/cards.json")
VALIDATOR = os.environ.get("AGENTGAME_VALIDATOR",
                           "/opt/abagent/bin/validate_linux_amd64")
WORKDIR = os.environ.get("AGENTGAME_WORK", "/var/www/games/agentgame/var")
N_CAND = int(os.environ.get("SHELL_CANDIDATES", "24"))
N_KEEP = int(os.environ.get("SHELL_KEEP", "12"))
SEEDS = int(os.environ.get("SHELL_SEEDS", "21"))
# Two shells sharing more than this much of their hundred are the same deck
# wearing two names.
MAX_SHARED = int(os.environ.get("SHELL_MAX_SHARED", "60"))
# A block of its own, so a shell is never measured on seeds the league scores
# with (1000..1020) or that make_starters used (4_400_000..).
SEED0 = int(os.environ.get("SHELL_SEED0", "5_200_000".replace("_", "")))


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
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.loads(r.read().decode())


def expand(entries):
    out = []
    for e in entries or []:
        out += [int(e["card_id"])] * int(e["quantity"])
    return out


def overlap(a: list[int], b: list[int]) -> int:
    ca, cb = collections.Counter(a), collections.Counter(b)
    return sum((ca & cb).values())


def main() -> int:
    if not TOKEN or not ADMIN:
        print("AGENTGAME_TOKEN and AGENTGAME_ADMIN must be set", file=sys.stderr)
        return 2
    field = [d for d in call("/field")["decks"] if d.get("cards")]
    if len(field) < 2:
        print("field too small to measure against")
        return 0
    standings = call("/standings")
    round_no = int((standings.get("round") or {}).get("id") or 0)

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
    block = [SEED0 + i for i in range(SEEDS)]

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
        # The same guard the agents use. A shell is handed to a player as a
        # good starting point; one with ten cards it can never cast is not.
        cards, plan = prune_dead_clauses(a.cards, a.plan, catalog)
        if len(cards) != 100:
            continue
        built[f"c{sd}"] = (a.seed_name, cards, plan)
        cands.append((f"c{sd}", cards, plan))
    if not cands:
        print("could not build any candidate")
        return 1

    print(f"measuring {len(cands)} candidates vs {len(opps)} field decks, "
          f"{SEEDS} seeds, round {round_no}", flush=True)
    sc = h.evaluate(cands, opps, block, drop_crashers=True, log=print)

    ranked = sorted(((v.wins, k) for k, v in sc.items()), reverse=True)
    keep, kept_lists = [], []
    for wins, k in ranked:
        if len(keep) >= N_KEEP:
            break
        name, cards, plan = built[k]
        twin = next((n for n, lst in kept_lists if overlap(cards, lst) > MAX_SHARED), None)
        if twin:
            print(f"  skip  {name[:30]:<30} {wins:5.2f}W  "
                  f"(shares >{MAX_SHARED} cards with {twin[:24]})")
            continue
        counts = collections.Counter(cards)
        keep.append({
            "name": name,
            "cards": [{"card_id": c, "quantity": q} for c, q in sorted(counts.items())],
            "battle_plan": plan,
            "wins": round(wins, 2),
            "opponents": len(opps),
            "round": round_no,
        })
        kept_lists.append((name, cards))
        print(f"  keep  {name[:30]:<30} {wins:5.2f}W  {len(counts)} distinct")

    if not keep:
        print("nothing survived de-duplication")
        return 1
    out = call("/shells", {"shells": keep}, admin=True)
    print(f"stored {out['shells']} shells")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
