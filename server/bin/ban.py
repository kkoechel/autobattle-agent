#!/usr/bin/env python3
"""Decide one ban by MEASURING what a card is worth, then rotate the pool.

A privileged client of the coordinator API, like bin/round.py: it reads the
field and posts the decision, so every database write stays in one PHP file.

WHAT "TOO STRONG" MEANS HERE. Not "played a lot" -- the most-played card in
this game is a free baseline, and popularity measures familiarity as much as
power. A card is a ban candidate when TAKING IT OUT COSTS ITS DECK WINS: the
deck is rebuilt without it, both versions play the same opponents on the same
seeds, and the gap is the card's contribution. That is the same measure the
scoring side of the design uses, which is the point -- one definition, used
for both.

INFINITE RARITY IS EXEMPT. Those cards are the baseline every deck may play,
not filler someone chose; banning one changes what the game IS rather than
what the field looks like.
"""
from __future__ import annotations

import json
import os
import random
import sys
import urllib.request

sys.path.insert(0, "/opt/abagent")
from abagent import harness  # noqa: E402

BASE = os.environ.get("AGENTGAME_BASE", "https://agent.autobattle.online/api")
TOKEN = os.environ.get("AGENTGAME_TOKEN", "")
ADMIN = os.environ.get("AGENTGAME_ADMIN", "")
CARDS = os.environ.get("AGENTGAME_CARDS", "/var/www/games/agentgame/var/cards.json")
VALIDATOR = os.environ.get("AGENTGAME_VALIDATOR",
                           "/opt/abagent/bin/validate_linux_amd64")
WORKDIR = os.environ.get("AGENTGAME_WORK", "/var/www/games/agentgame/var")

# How many cards stay suspended at once. A guess, and deliberately a visible
# one: every run prints the full table it measured, so the right number is
# something this data can eventually answer rather than something settled by
# argument.
KEEP = int(os.environ.get("AGENTGAME_BAN_KEEP", "7"))
# A card must be worth at least this many wins a round to its deck. Below it
# the measurement cannot tell the card from noise, and banning noise just
# removes cards at random.
MIN_COST = float(os.environ.get("AGENTGAME_BAN_MIN", "0.75"))
CANDIDATES = int(os.environ.get("AGENTGAME_BAN_CANDIDATES", "6"))
DECKS_PER_CARD = int(os.environ.get("AGENTGAME_BAN_DECKS", "2"))
SEEDS = int(os.environ.get("AGENTGAME_BAN_SEEDS", "15"))
SEED0 = int(os.environ.get("AGENTGAME_BAN_SEED0", "60000"))


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
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.loads(r.read().decode())


def expand(entries):
    out = []
    for e in entries or []:
        out += [int(e["card_id"])] * int(e["quantity"])
    return out


def without(entries, card_id, cat):
    """The same deck with one card taken out and the hole filled.

    Filled by topping up the cards the deck ALREADY plays, most-played first,
    up to each one's deck limit. Replacing with a random legal card would
    measure "this card versus a stranger"; topping up measures what the deck
    does when it has to lean on what it already has, which is what actually
    happens when a card is banned.
    """
    kept = [dict(e) for e in entries if int(e["card_id"]) != card_id]
    need = sum(int(e["quantity"]) for e in entries) - sum(int(e["quantity"]) for e in kept)
    kept.sort(key=lambda e: -int(e["quantity"]))
    for e in kept:
        if need <= 0:
            break
        limit = int(cat[int(e["card_id"])].get("deck_limit") or 0)
        room = max(0, limit - int(e["quantity"]))
        take = min(room, need)
        e["quantity"] = int(e["quantity"]) + take
        need -= take
    return (kept, need)          # need > 0 means it could not be refilled


def main() -> int:
    if not TOKEN or not ADMIN:
        print("AGENTGAME_TOKEN and AGENTGAME_ADMIN must be set", file=sys.stderr)
        return 2

    field = [d for d in call("/field")["decks"] if d.get("cards")]
    if len(field) < 4:
        print(f"ban: only {len(field)} decks; nothing worth measuring")
        return 0

    cat_list = json.load(open(CARDS))
    cat = {int(c["id"]): c for c in cat_list}
    banned = {int(b["card_id"]) for b in call("/bans")["banned"]}

    # Who plays what, over the live field.
    plays: dict[int, list] = {}
    for d in field:
        for e in d["cards"]:
            plays.setdefault(int(e["card_id"]), []).append(d)

    def eligible(cid: int) -> bool:
        c = cat.get(cid)
        if not c or cid in banned:
            return False
        if (c.get("rarity") or "") == "infinite":      # baseline, never filler
            return False
        if c.get("is_retired") or c.get("is_vip"):
            return False
        return len(plays[cid]) >= 2                    # one deck is one deck

    pool = sorted((cid for cid in plays if eligible(cid)),
                  key=lambda c: -len(plays[c]))[:CANDIDATES]
    if not pool:
        print("ban: nothing eligible")
        return 0

    h = harness.Harness(VALIDATOR, cat_list, workdir=WORKDIR)
    block = [SEED0 + i for i in range(SEEDS)]
    rng = random.Random(1234)

    print(f"measuring {len(pool)} cards over {len(field)} decks, "
          f"{SEEDS} seeds, keep={KEEP}, min={MIN_COST}")
    scored = []
    for cid in pool:
        decks = plays[cid][:]
        rng.shuffle(decks)
        costs = []
        for d in decks[:DECKS_PER_CARD]:
            cut, short = without(d["cards"], cid, cat)
            if short:
                continue                   # could not refill legally; skip it
            others = [harness.Opponent(slot_id=f"o{j}", cards=expand(o["cards"]),
                                       battle_plan=o.get("battle_plan") or {},
                                       name=o["handle"])
                      for j, o in enumerate(field) if o is not d]
            sc = h.evaluate(
                [("with", expand(d["cards"]), d.get("battle_plan") or {}),
                 ("without", expand(cut), d.get("battle_plan") or {})],
                others, block, drop_crashers=True, log=lambda *a: None)
            if "with" in sc and "without" in sc:
                costs.append(sc["with"].wins - sc["without"].wins)
        if costs:
            cost = sum(costs) / len(costs)
            scored.append((cost, cid, len(plays[cid]), len(costs)))
            print(f"  {cat[cid]['name'][:34]:34s} cost {cost:+.2f} wins  "
                  f"({len(plays[cid])} decks, {len(costs)} measured)")

    if not scored:
        print("ban: nothing could be measured")
        return 0
    scored.sort(reverse=True)
    cost, cid, ndecks, nmeas = scored[0]
    if cost < MIN_COST:
        print(f"ban: the strongest measured only {cost:+.2f}, under {MIN_COST} "
              f"-- nothing banned (the field is not warped enough to act on)")
        return 0

    reason = (f"removing it cost its deck {cost:.2f} wins a round "
              f"across {nmeas} deck(s); played by {ndecks}")
    res = call("/bans", {"card_id": cid, "reason": reason, "keep": KEEP},
               admin=True)
    print(f"BANNED {cat[cid]['name']} -- {reason}")
    for r in res.get("released", []):
        print(f"  released {r['name']} (pool is {KEEP})")
    print("  now banned: " + ", ".join(b["name"] for b in res["banned"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
