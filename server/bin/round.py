#!/usr/bin/env python3
"""Run one round-robin over every player's current deck and post the results.

A privileged client of the coordinator API, not a second thing that knows the
schema -- it reads GET /field and writes POST /round, so all database access
stays in one PHP file.

Scoring lives here rather than on the phones. At beta scale a full round-robin
is a few thousand matches, which is seconds on this box, and computing it
server-side means there is no result to forge and no consensus protocol to get
wrong. That stops being free somewhere in the hundreds of players; the
shard-and-consensus design is what replaces it then.

Reuses abagent's Harness so the validator payload is built by the same code
the agents have been using for weeks, rather than a reimplementation that
would drift.
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

sys.path.insert(0, "/opt/abagent")
from abagent import harness  # noqa: E402

# The public HTTPS origin, not http://127.0.0.1 with a Host header:
# certbot installs a 301 to HTTPS, and urllib following that redirect
# is a cross-origin hop that loses the Authorization header. Going
# straight to the real origin is also what the phone does, so this
# exercises the same path players will.
BASE = os.environ.get("AGENTGAME_BASE", "https://agent.autobattle.online/api")
TOKEN = os.environ.get("AGENTGAME_TOKEN", "")
ADMIN = os.environ.get("AGENTGAME_ADMIN", "")
CARDS = os.environ.get("AGENTGAME_CARDS",
                       "/var/www/games/agentgame/var/cards.json")
VALIDATOR = os.environ.get("AGENTGAME_VALIDATOR",
                           "/opt/abagent/bin/validate_linux_amd64")
WORKDIR = os.environ.get("AGENTGAME_WORK", "/var/www/games/agentgame/var")
SEEDS = int(os.environ.get("AGENTGAME_SEEDS", "21"))
# Set this ONLY to pin a block by hand. Left unset -- which is how it has
# always been deployed -- the block comes from GET /next-round, which rotates
# it once per season. See the SEED_* constants in inc/boot.php for why.
SEED0_OVERRIDE = os.environ.get("AGENTGAME_SEED0")


def call(path: str, payload=None, admin=False):
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
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode())


def expand(entries) -> list[int]:
    out: list[int] = []
    for e in entries or []:
        out += [int(e["card_id"])] * int(e["quantity"])
    return out


def main() -> int:
    if not TOKEN or not ADMIN:
        print("AGENTGAME_TOKEN and AGENTGAME_ADMIN must be set", file=sys.stderr)
        return 2
    field = call("/field")["decks"]
    decks = [d for d in field if d.get("cards")]
    if len(decks) < 2:
        print(f"round: only {len(decks)} deck(s) registered; nothing to play")
        return 0

    cat_list = json.load(open(CARDS))
    h = harness.Harness(VALIDATOR, cat_list, workdir=WORKDIR)
    # WHICH SEEDS, asked rather than assumed. The season rule lives in
    # inc/boot.php; reimplementing it here would be a second copy to drift.
    #
    # A failure here does not stop the round. Scoring on last season's block
    # is a worse round than it could have been; scoring no round at all is
    # strictly worse than that, and the league has just come off a five-hour
    # outage. Whatever block is used is recorded in rounds.seed0, so the
    # result is never a lie about itself -- only the journal line below says
    # it was not the intended one.
    if SEED0_OVERRIDE is not None:
        seed0 = int(SEED0_OVERRIDE)
        print(f"round: seed block pinned by AGENTGAME_SEED0 to {seed0}")
    else:
        try:
            nxt = call("/next-round", admin=True)
            seed0 = int(nxt["seed0"])
            print(f"round: round {nxt['round_no']} is season {nxt['season']}, "
                  f"seeds {seed0}-{seed0 + SEEDS - 1}")
        except Exception as e:
            seed0 = 1000
            print(f"round: WARNING could not read /next-round ({e}); "
                  f"falling back to the legacy block {seed0}", file=sys.stderr)
    block = [seed0 + i for i in range(SEEDS)]

    # Every deck is scored against every OTHER deck. Harness.evaluate already
    # does candidate-vs-opponents in one validator invocation, so one call per
    # deck with the rest as opponents is both the simplest expression of a
    # round-robin and the cheapest -- the cards map is ~900KB and process
    # startup is the fixed cost, so folding opponents into one payload
    # amortises both.
    rows, unscored = [], []
    for i, me in enumerate(decks):
        others = [harness.Opponent(slot_id=f"o{j}", cards=expand(o["cards"]),
                                   battle_plan=o.get("battle_plan") or {},
                                   name=o["handle"])
                  for j, o in enumerate(decks) if j != i]
        try:
            sc = h.evaluate([("me", expand(me["cards"]), me.get("battle_plan") or {})],
                            others, block, drop_crashers=True, log=print)
        except RuntimeError as e:
            # A validator call that cannot END is the one failure this loop had
            # no answer for. drop_crashers absorbs a PANIC and returns {}, which
            # the branch below scores 0; a TIMEOUT re-raises by design, because
            # bisecting one costs more than the round has. Uncaught, that raise
            # exits 1 and scores NOBODY -- which is exactly what happened on
            # 2026-11-24, three consecutive rounds dead at the two-minute mark.
            #
            # The rule for this is already written above: a worse round beats no
            # round. So the deck is left OUT and the count travels with the
            # result. Deliberately NOT scored 0 like a panic is: a timeout is as
            # likely to be this box under contention as a bad deck, and a 0
            # recorded against a player because the box was busy is a lie about
            # that player, where a missing row is only a gap.
            unscored.append(me["handle"])
            print(f"round: {me['handle']}'s deck NEVER FINISHED; left unscored "
                  f"-- {e}", file=sys.stderr)
            continue
        if "me" not in sc:
            print(f"round: {me['handle']}'s deck crashed the engine; scored 0")
            rows.append({"player_id": me["player_id"], "deck_name": me["deck_name"],
                         "revision": me["revision"], "wins": 0,
                         "losses": len(others), "draws": 0})
            continue
        s = sc["me"]
        # Rounded so the three still SUM to the opponent count. Rounding each
        # independently loses or gains one -- the first scored round recorded
        # "1-3-3" in an 8-opponent league, which is 7 and reads as a bug.
        # Wins and losses are rounded, and draws take the remainder, because
        # draws are the residual quantity anyway.
        w, l = round(s.wins), round(s.losses)
        d = max(0, len(others) - w - l)
        rows.append({"player_id": me["player_id"], "deck_name": me["deck_name"],
                     "revision": me["revision"], "wins": w,
                     "losses": l, "draws": d})
        print(f"  {me['handle']:<18} {s.wins:5.1f}W {s.losses:5.1f}L "
              f"{s.draws:5.1f}D  ({me['deck_name'][:28]})")

    # The lists exactly as they played, so the round can be WATCHED later and
    # not merely read. Taken from the same `decks` the scoring loop used, not
    # re-fetched: /field between the two calls could return a deck a player
    # edited mid-round, and a replay of a list that did not play is a lie
    # dressed as a record.
    snaps = [{"player_id": d["player_id"], "handle": d["handle"],
              "deck_name": d["deck_name"], "revision": d["revision"],
              "cards": d["cards"], "battle_plan": d.get("battle_plan") or {}}
             for d in decks]

    # Two is the smallest field that can be a round-robin at all. Below that
    # the standings would move on a handful of surviving decks and read as a
    # real result, so nothing is posted and the unit fails visibly instead.
    if len(rows) < 2:
        print(f"round: only {len(rows)} of {len(decks)} deck(s) could be "
              f"scored; not posting a round", file=sys.stderr)
        return 1

    note = f"round-robin over {len(rows)} decks"
    if unscored:
        # In the record, not only in the journal. The file's own standard: a
        # result is never a lie about itself.
        who = (", ".join(unscored) if len(unscored) <= 5
               else f"{len(unscored)} decks")
        note += f"; {len(unscored)} unscored (never finished): {who}"
    out = call("/round", {"results": rows, "seeds": SEEDS, "seed0": seed0,
                          "decks": snaps, "note": note},
               admin=True)
    print(f"round {out['round_id']}: {out['decks']} decks scored"
          + (f", {len(unscored)} unscored" if unscored else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
