#!/usr/bin/env python3
"""Do the v4.74 payment keys open a gate, and does a gravecast `pay` stay out
of the hand cost?

The table diff in check_castable_parity.py proves the names match the
reference. It cannot prove they are READ, and one of the v4.74 changes is not
a table row at all: a gravecast passive's `pay` is a second cost site, paid
only when the card is cast from the discard, INSTEAD of additional_cost.

Both directions matter and they pull opposite ways:

  a missing producer INVENTS a dead card. castable reaches "dead" by finding
  no producer in the deck, so a payment key it has not learned turns a live
  card into one the bot generator cuts.

  a `pay` counted as a hand cost INVENTS a gate. "costs 5 honor to play" is a
  verdict about casting from hand, and a gravecast pay is not payable from
  hand, so folding it into additional_cost would condemn a card that is fine.

Synthetic catalogues on purpose: opponent routes are read catalogue-wide, so a
tiny catalogue makes every verdict strict, which is what a test of the strict
path needs. Dark Dealer 1559 is the live card behind this (engine v4.75).

    python3 tools/test_castable_v474.py
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from abagent import castable  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, detail: str = "") -> None:
    print(("  ok   " if cond else "  FAIL ") + name
          + (("  -- " + detail) if detail and not cond else ""))
    if not cond:
        FAILED.append(name)


def card(cid: int, name: str, **eff) -> tuple[int, dict]:
    return cid, {"id": cid, "name": name, "supertype": "Relic", "effects_json": eff}


def gate(cid: int, name: str, metric: str, amount: int = 1) -> tuple[int, dict]:
    return card(cid, name, play_requires=[{"kind": "turn_tally", "metric": metric,
                                           "op": "gte", "amount": amount}])


def verdicts(*cards) -> dict[int, str]:
    cat = dict(cards)
    # chaos=False: the Chaos rolls can poison and mill for you, which would
    # withhold exactly the verdicts under test. The arena is not the subject.
    return castable.dead_cards(list(cat), cat, chaos=False)


def main() -> int:
    print("CONTROL -- the gate must bite when nothing can open it.")
    g_poison = gate(9001, "Poison Gate", "poison_gained")
    d = verdicts(g_poison)
    check("a poison_gained gate alone is dead", 9001 in d, str(d))

    print("\nv4.74 COST KEYS as producers (a missing one invents a dead card).")
    for n, (key, metric, gid) in enumerate([
            ("poison", "poison_gained", 9001),
            ("mill", "cards_milled", 9011),
            ("exile_library", "cards_exiled", 9012)], start=1):
        g = gate(gid, f"{metric} Gate", metric)
        payer = card(9100 + n, f"{key} Payer", additional_cost={key: 2})
        d = verdicts(g, payer)
        check(f"additional_cost {key!r} opens a {metric} gate", gid not in d,
              f"still dead: {d.get(gid)!r}")

    print("\n  and 'poison' feeds life_lost too, because poison ticks damage.")
    g_life = gate(9013, "Life Lost Gate", "life_lost")
    d = verdicts(g_life, card(9113, "Poison Payer", additional_cost={"poison": 2}))
    check("additional_cost 'poison' opens a life_lost gate", 9013 not in d,
          f"still dead: {d.get(9013)!r}")

    print("\nGRAVECAST `pay` -- a second cost site, read for producers.")
    g_mill = gate(9021, "Mill Gate", "cards_milled")
    dealer = card(9022, "Dark Dealer", passives=[{"type": "gravecast", "cost": 2,
                                                  "pay": {"mill": 2}}])
    d = verdicts(g_mill, dealer)
    check("a gravecast pay 'mill' opens a cards_milled gate", 9021 not in d,
          f"still dead: {d.get(9021)!r}")

    print("\n  ...but NOT folded into the hand cost, which would invent a gate.")
    # additional_cost {honor: 5} in a deck with no honor gain IS dead -- that is
    # the existing verdict and the thing a pay must not be mistaken for.
    hand = card(9031, "Honor Hand Cost", additional_cost={"honor": 5})
    d = verdicts(hand)
    check("control: an honor HAND cost with no gain is dead", 9031 in d, str(d))
    grave = card(9032, "Honor Grave Cost",
                 passives=[{"type": "gravecast", "cost": 2, "pay": {"honor": 5}}])
    d = verdicts(grave)
    check("the same cost in a gravecast pay is NOT dead", 9032 not in d,
          f"wrongly dead: {d.get(9032)!r}")

    print("\n`pay_and` counts as a producer wherever a payment can tally.")
    for gid, metric in [(9041, "cards_milled"), (9042, "permanents_died"),
                        (9043, "cards_discarded"), (9044, "honor_lost")]:
        g = gate(gid, f"{metric} Gate", metric)
        payer = card(9140, "Pay And",
                     triggers=[{"event": "on_enter",
                                "effect": {"type": "pay_and", "pay": {"mill": 1},
                                           "then": [{"type": "gain_energy", "amount": 1}]}}])
        d = verdicts(g, payer)
        check(f"pay_and opens a {metric} gate", gid not in d,
              f"still dead: {d.get(gid)!r}")

    print()
    if FAILED:
        print(f"{len(FAILED)} failure(s):")
        for f in FAILED:
            print(f"  - {f}")
        return 1
    print("all checks passed")
    return 0


raise SystemExit(main())
