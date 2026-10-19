#!/usr/bin/env python3
"""Suspend one card that the GOOD decks lean on, then rotate the pool.

A privileged client of the coordinator API, like bin/round.py: it reads a
scored round and posts the decision, so every database write stays in one
PHP file.

WHY THIS IS NOT A MEASUREMENT ANY MORE
--------------------------------------
It used to rebuild each candidate's deck without the card, replay both over
the same opponents and seeds, and call the win gap the card's contribution.
That was principled and it did not survive contact with its own numbers.
Measured against a fixed 31-deck field, repeating it with a different sample
of the same card's decks gave:

    Enemy Hive      +8.93  +1.60  +8.93  +5.40  +1.73      spread 7.33
    Enchantress     +1.20  -0.87  +1.20  -0.00  -1.03      spread 2.23

The RANKING was stable -- the same card won every repeat -- but the figure
moved by a factor of five on the sample alone, and doubling the decks drawn
barely narrowed it. So five minutes of simulation per run bought a number too
vague to publish, to make a choice that reverses itself in five days anyway.

A BAN HERE IS A SHAKE-UP, NOT A VERDICT. At one ban per seventeen hours and
a pool of seven, a card is out for about five days and then comes back. That
tenure is what makes a cheap rule safe: a wrong call costs the format five
days, which is less than the measurement's own error bar was worth.

WHAT IT PICKS, AND WHY NOT "MOST PLAYED"
----------------------------------------
Popularity alone is the wrong signal and the field says so plainly. In round
269, Fire Djinn was in 20 of 31 decks -- but 4 of the top ten and 9 of the
bottom ten, and when it was still being measured it came out at roughly zero.
It is what the weak decks play. Banning it would have hit the bottom of the
field and left the top untouched.

So the rule is LIFT: how much more of the top third of the field plays a card
than the bottom third. That finds the cards which distinguish good decks from
bad ones, rather than the ones everybody happens to own.

Be honest about its resolution. With thirty-one decks a third is ten, so the
counts are small and "4 of the top 10" versus "3 of the top 10" is not a real
difference -- in that round ten cards tied at three. The rule picks a card
that is plausibly carrying good decks, not provably the best one. Ties are
broken at random, seeded on the round, so the choice is reproducible for a
given round without always favouring the same corner of the catalogue.

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

BASE = os.environ.get("AGENTGAME_BASE", "https://agent.autobattle.online/api")
TOKEN = os.environ.get("AGENTGAME_TOKEN", "")
ADMIN = os.environ.get("AGENTGAME_ADMIN", "")
CARDS = os.environ.get("AGENTGAME_CARDS", "/var/www/games/agentgame/var/cards.json")

# How many cards stay suspended at once. With the timer at seventeen hours
# this is also the tenure: seven is just under five days out of the format.
KEEP = int(os.environ.get("AGENTGAME_BAN_KEEP", "7"))

# A candidate must appear in at least this many of the top decks. Without a
# floor the rule would happily ban a card that one good deck plays and nobody
# else owns, which says nothing about the format.
MIN_TOP = int(os.environ.get("AGENTGAME_BAN_MIN_TOP", "3"))

# The field has to be big enough to HAVE a top and a bottom. Below this a
# "third" is two or three decks and the comparison is meaningless.
MIN_FIELD = int(os.environ.get("AGENTGAME_BAN_MIN_FIELD", "9"))

# --dry prints the table and the card it would pick, and posts nothing. The
# rule is cheap enough to run on demand, so there is no reason to find out
# what it does by letting it do it.
DRY = "--dry" in sys.argv


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


def main() -> int:
    if not ADMIN:
        print("AGENTGAME_ADMIN must be set", file=sys.stderr)
        return 2

    # The round SNAPSHOT, not the live field. /field would pair decks as they
    # are now with placements from when they were scored, and an agent that
    # retrained in between is a different deck wearing an old result.
    env = call("/replay", admin=True)
    decks = [d for d in env.get("decks", []) if d.get("placement")]
    decks.sort(key=lambda d: d["placement"])
    if len(decks) < MIN_FIELD:
        print(f"ban: only {len(decks)} placed decks; need {MIN_FIELD}")
        return 0

    cat = {int(c["id"]): c for c in json.load(open(CARDS))}
    banned = {int(b["card_id"]) for b in call("/bans")["banned"]}

    cut = max(3, len(decks) // 3)
    top, bottom = decks[:cut], decks[-cut:]

    def eligible(cid: int) -> bool:
        c = cat.get(cid)
        if not c or cid in banned:
            return False
        if (c.get("rarity") or "") == "infinite":      # baseline, never filler
            return False
        return not (c.get("is_retired") or c.get("is_vip"))

    def tally(group):
        out: dict[int, int] = {}
        for d in group:
            # Per DECK, not per copy: a card in four copies of one deck is
            # still one deck choosing it, and counting copies would just
            # rank by deck_limit.
            for cid in {int(e["card_id"]) for e in d.get("cards") or []}:
                if eligible(cid):
                    out[cid] = out.get(cid, 0) + 1
        return out

    t, b = tally(top), tally(bottom)
    rid = env.get("round_id", 0)
    print(f"round {rid}: {len(decks)} placed decks, top/bottom third = {cut}, "
          f"keep={KEEP}, min_top={MIN_TOP}")

    scored = []
    for cid, n_top in t.items():
        if n_top < MIN_TOP:
            continue
        lift = (n_top - b.get(cid, 0)) / cut
        scored.append((lift, n_top, cid))
    if not scored:
        print(f"ban: no card is in {MIN_TOP} or more of the top {cut} decks "
              f"-- the field is too spread out to point at one")
        return 0

    # Random among equals, seeded on the round: reproducible for a given
    # round, and it does not quietly always pick the lowest card id out of a
    # ten-way tie.
    rng = random.Random(rid)
    rng.shuffle(scored)
    scored.sort(key=lambda s: (-s[0], -s[1]))

    for lift, n_top, cid in scored[:8]:
        print(f"  {cat[cid]['name'][:34]:34s} {n_top:>2}/{cut} top, "
              f"{b.get(cid, 0):>2}/{cut} bottom   lift {lift:+.2f}")

    lift, n_top, cid = scored[0]
    reason = (f"in {n_top} of the top {cut} decks and {b.get(cid, 0)} of the "
              f"bottom {cut} (round {rid})")
    if DRY:
        print(f"WOULD BAN {cat[cid]['name']} -- {reason}")
        print("  (dry run, nothing posted)")
        return 0
    print(f"BANNED {cat[cid]['name']} -- {reason}")
    res = call("/bans", {"card_id": cid, "reason": reason, "keep": KEEP},
               admin=True)
    for r in res.get("released", []):
        print(f"  released {r['name']} (pool is {KEEP})")
    print("  now banned: " + ", ".join(
        b2["name"] for b2 in call("/bans")["banned"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
