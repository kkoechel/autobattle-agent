"""abagent — offline deck/plan optimisation against the live AutoBattle meta."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import sys
import time
import urllib.request

from .api import Api
from .harness import Harness, opponents_from_meta
from .plans import card_order_from_deck, describe
from .search import climb

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VAR = os.path.join(ROOT, "var")
BIN = os.path.join(ROOT, "bin")
VALIDATOR = os.path.join(BIN, "validate_linux_amd64")


def _p(name: str) -> str:
    return os.path.join(VAR, name)


def _load(name: str):
    with open(_p(name)) as fh:
        return json.load(fh)


def _save(name: str, obj) -> None:
    os.makedirs(VAR, exist_ok=True)
    tmp = _p(name + ".tmp")
    with open(tmp, "w") as fh:
        json.dump(obj, fh)
    os.replace(tmp, _p(name))


def expand(deck: dict) -> list[int]:
    """deck_cards rows -> the engine's flat card list, one entry per copy."""
    out: list[int] = []
    for c in deck["cards"]:
        out.extend([int(c["card_id"])] * int(c["quantity"]))
    return out


# --- commands ------------------------------------------------------------

def cmd_fetch(args, api: Api) -> int:
    os.makedirs(BIN, exist_ok=True)
    vv = api.validator_version()
    want = vv["go"]["version"]
    have = None
    if os.path.exists(VALIDATOR):
        import subprocess
        have = subprocess.run([VALIDATOR, "-version"], capture_output=True,
                              text=True).stdout.strip().split()[-1].lstrip("v")
    if have != want:
        url = vv["go"]["urls"]["linux_amd64"]
        print(f"validator {have or 'absent'} -> v{want}, downloading {url}")
        with urllib.request.urlopen(url, timeout=180) as r:
            blob = r.read()
        with urllib.request.urlopen(url + ".sha256", timeout=60) as r:
            want_sum = r.read().decode().split()[0]
        got = hashlib.sha256(blob).hexdigest()
        if got != want_sum:
            print(f"FATAL sha256 mismatch: {got} != {want_sum}", file=sys.stderr)
            return 1
        with open(VALIDATOR, "wb") as fh:
            fh.write(blob)
        os.chmod(VALIDATOR, 0o755)
        print(f"validator v{want} verified ({len(blob)} bytes)")
    else:
        print(f"validator v{have} already current")

    # The FULL catalog: fields=slim/rules drop effects_json and stats_json,
    # which the engine needs, and playable=1 drops token cards -- a deck that
    # creates tokens silently no-ops if the token definitions are missing.
    cards = api.cards()
    _save("cards.json", cards)
    print(f"cards: {len(cards)} (full catalog, tokens included)")

    meta = api.meta(args.arena)
    _save(f"meta_{args.arena}.json", meta)
    print(f"meta {args.arena}: {len(meta['decks'])} decks from cohort "
          f"{meta['cohort_id']} finalized {meta['finalized_at']}")
    return 0


def _setup(args, api: Api):
    cards_cat = _load("cards.json")
    meta = _load(f"meta_{args.arena}.json")
    deck = api.deck(args.deck_id)
    mine = expand(deck)
    opps = opponents_from_meta(meta, exclude_deck_ids={args.deck_id})
    h = Harness(VALIDATOR, cards_cat, workdir=VAR)
    return h, deck, mine, opps


def cmd_baseline(args, api: Api) -> int:
    h, deck, mine, opps = _setup(args, api)
    plan = deck.get("battle_plan") or {}
    print(f"deck {args.deck_id} '{deck['name']}' — {len(mine)} cards, "
          f"{len(set(mine))} distinct")
    print(f"plan: {describe(plan)}")
    print(f"field: {len(opps)} opponents from {args.arena}")
    seeds = [1_000_000 + i for i in range(args.seeds)]
    t = time.time()
    s = h.evaluate([("me", mine, plan)], opps, seeds)["me"]
    print(f"\n{s}")
    print(f"({len(opps) * args.seeds} matches in {time.time() - t:.1f}s)")
    return 0


def cmd_climb(args, api: Api) -> int:
    h, deck, mine, opps = _setup(args, api)
    start = dict(deck.get("battle_plan") or {})
    if args.seed_card_order and not start.get("card_order"):
        start["card_order"] = card_order_from_deck(mine)
    t = time.time()
    plan, score, hist = climb(h, mine, opps, start,
                              sweep_seeds=args.sweep_seeds,
                              confirm_seeds=args.confirm_seeds,
                              max_sweeps=args.sweeps, min_t=args.min_t,
                              rng=random.Random(args.rng))
    print(f"\n{sum(1 for s in hist if s.confirmed)} confirmed of {len(hist)} "
          f"challengers, {time.time() - t:.0f}s")
    _save(f"plan_{args.deck_id}.json", plan)
    print(f"wrote var/plan_{args.deck_id}.json")
    if args.apply:
        api.update_deck(args.deck_id, battle_plan=plan)
        print(f"applied to deck {args.deck_id}")
        if args.register:
            r = api.register(args.deck_id)
            print(f"registered: {json.dumps(r)[:200]}")
    else:
        print("(not applied — pass --apply to write it to the deck)")
    return 0


def cmd_cycle(args, api: Api) -> int:
    """One unattended pass: refresh, climb within a budget, register if better.

    Timed to land between cohorts. Standard closes at :04:58 and finalizes
    ~43s later, so firing at :06 gets a freshly finalized field and leaves
    ~9 minutes before the next close.
    """
    t0 = time.time()
    cmd_fetch(args, api)
    h, deck, mine, opps = _setup(args, api)
    start = dict(deck.get("battle_plan") or {})

    deadline = t0 + args.budget
    plan, score, hist = climb(h, mine, opps, start,
                              sweep_seeds=args.sweep_seeds,
                              confirm_seeds=args.confirm_seeds,
                              max_sweeps=args.sweeps, min_t=args.min_t,
                              rng=random.Random(int(t0)),
                              deadline=deadline)

    confirmed = [s for s in hist if s.confirmed]
    if not confirmed:
        print(f"cycle: no confirmed improvement in {time.time() - t0:.0f}s, "
              f"leaving deck {args.deck_id} as-is")
        return 0

    api.update_deck(args.deck_id, battle_plan=plan)
    reg = api.register(args.deck_id)
    print(f"cycle: applied {len(confirmed)} change(s) to deck {args.deck_id} "
          f"and registered — {describe(plan)}")
    print(f"cycle: projected {score}")
    print(f"cycle: {json.dumps(reg)[:200]}")
    _save(f"plan_{args.deck_id}.json", plan)
    return 0


def cmd_results(args, api: Api) -> int:
    rows = api.results(arena=args.arena, deck_id=args.deck_id, limit=args.limit)
    print(f"{'closed':20} {'arena':10} {'place':>8} {'W-L-D':>12} {'win%':>6}")
    for r in rows["results"]:
        print(f"{r['closes_at']:20} {r['arena']['slug']:10} "
              f"{r['placement']:>3}/{r['slot_count']:<4} "
              f"{r['wins']:>3}-{r['losses']}-{r['draws']:<4} {r['win_pct']:>6}")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="abagent")
    ap.add_argument("--arena", default="pure")
    ap.add_argument("--deck-id", type=int, default=None)
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("fetch").set_defaults(fn=cmd_fetch)

    b = sub.add_parser("baseline")
    b.add_argument("--seeds", type=int, default=21)
    b.set_defaults(fn=cmd_baseline)

    c = sub.add_parser("climb")
    c.add_argument("--sweep-seeds", type=int, default=5)
    c.add_argument("--confirm-seeds", type=int, default=21)
    c.add_argument("--sweeps", type=int, default=6)
    c.add_argument("--rng", type=int, default=20260922)
    c.add_argument("--min-t", type=float, default=2.0,
                   help="standard errors a confirmed gain must clear")
    c.add_argument("--seed-card-order", action="store_true",
                   help="start card_order from the deck's own copy counts")
    c.add_argument("--apply", action="store_true")
    c.add_argument("--register", action="store_true")
    c.set_defaults(fn=cmd_climb)

    y = sub.add_parser("cycle")
    y.add_argument("--budget", type=int, default=420,
                   help="seconds of search before it must stop (default 420)")
    y.add_argument("--sweep-seeds", type=int, default=5)
    y.add_argument("--confirm-seeds", type=int, default=21)
    y.add_argument("--sweeps", type=int, default=4)
    y.add_argument("--min-t", type=float, default=2.0)
    y.set_defaults(fn=cmd_cycle)

    r = sub.add_parser("results")
    r.add_argument("--limit", type=int, default=15)
    r.set_defaults(fn=cmd_results)

    args = ap.parse_args(argv)
    api = Api()
    if args.deck_id is None and args.cmd in ("baseline", "climb", "cycle"):
        args.deck_id = api.me()["active_deck_id"]
        print(f"(using active deck {args.deck_id})")
    return args.fn(args, api)


if __name__ == "__main__":
    sys.exit(main())
