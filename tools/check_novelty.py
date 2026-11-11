#!/usr/bin/env python3
"""Can the re-base still write another account's deck verbatim?

MEASURED, 2026-11-11. abagent's deck 40806 fielded a list sharing 100 of 100
cards with deck 144280, another account's "5 Finger Discount (Clone)", for
four days -- twice the MAX_OVERLAP this package calls copying. It got there
through `best_rebase`, which adopted the candidate's own 100 cards and never
consulted `novelty` at all, while the explorers were refusing the same thing
in `keep_over_rotate`. Ten `cycle: applied` events in the fortnight were all
battle-plan tweaks; not one card moved.

The two multisets below are the real lists from that day, which is why LIVE
and SRC are identical dict literals: that is the bug, written down.

Run: python3 tools/check_novelty.py
"""
import sys, os, collections

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from abagent import novelty
from abagent.archetype import derive_from, INFINITE_FILLER

# deck 40806 "Golden Hive" (ShadowDrake) as it stood on 2026-11-11.
LIVE = {
    30: 1, 149: 10, 679: 1, 683: 1, 747: 1, 748: 1, 969: 3, 985: 8,
    993: 10, 1007: 1, 1022: 10, 1142: 15, 1147: 15, 1149: 10, 1156: 6,
    1378: 7
}
# deck 144280 "5 Finger Discount (Clone)" (PT-P942), the deck it had adopted.
SRC = {
    30: 1, 149: 10, 679: 1, 683: 1, 747: 1, 748: 1, 969: 3, 985: 8,
    993: 10, 1007: 1, 1022: 10, 1142: 15, 1147: 15, 1149: 10, 1156: 6,
    1378: 7
}
# "abagent ckpt 11-07 05:20 pre-rebase" (id 173754) -- what 40806 held in the
# minute before it copied SRC, i.e. the last list that was actually its own.
OWN = {
    57: 3, 413: 7, 462: 11, 621: 10, 710: 7, 820: 7, 822: 7, 849: 7,
    925: 7, 949: 1, 969: 3, 1033: 1, 1035: 1, 1093: 1, 1109: 3, 1145: 10,
    1157: 7, 1321: 7
}
DECK_LIMIT = {
    30: 1, 57: 100, 149: 15, 413: 7, 462: 15, 621: 10, 679: 1, 683: 1,
    710: 7, 747: 1, 748: 1, 820: 7, 822: 7, 849: 7, 925: 7, 949: 1, 969: 3,
    985: 15, 993: 10, 1007: 1, 1022: 15, 1033: 1, 1035: 1, 1093: 1,
    1109: 3, 1142: 15, 1145: 10, 1147: 15, 1149: 10, 1156: 10, 1157: 7,
    1321: 10, 1378: 7
}

expand = lambda m: [c for c, q in sorted(m.items()) for _ in range(q)]
CATALOG = {c: {"id": c, "deck_limit": q, "is_retired": False, "is_vip": False,
              "rarity": "infinite" if c == INFINITE_FILLER else "common"}
           for c, q in DECK_LIMIT.items()}
FIELD = [{"deck_id": 144280, "deck_name": "5 Finger Discount (Clone)",
          "cards": expand(SRC)}]
OURS = {40806, 42200}
quiet = lambda *_a, **_k: None

fails = []


def case(label, got, want):
    ok = got == want
    print(f"  {'ok  ' if ok else 'FAIL'}  {label}: {got!r}")
    if not ok:
        fails.append(f"{label}: got {got!r}, wanted {want!r}")


def check(label, cond, detail=""):
    print(f"  {'ok  ' if cond else 'FAIL'}  {label}" + (f"   {detail}" if detail else ""))
    if not cond:
        fails.append(label)


print("MEASURED — the list that was live for four days:")
case("40806 vs the field it was copied from: shared cards",
     novelty.overlap(expand(LIVE), expand(SRC)), 100)
case("...so it is not novel", novelty.is_novel(expand(LIVE), FIELD, OURS), False)
case("overlap counts copies, not distinct cards",
     novelty.overlap([7, 7, 7, 7, 7], [7, 7, 7]), 3)

print("the deck is already the source — there is nothing of ours to blend:")
case("derive_from refuses rather than re-copying",
     derive_from(expand(SRC), expand(LIVE), CATALOG, FIELD, OURS, log=quiet), None)

print("blending the source's core with the last list that was ours:")
got = derive_from(expand(SRC), expand(OWN), CATALOG, FIELD, OURS, log=quiet)
check("a list is produced at all", got is not None)
if got:
    cards, order = got
    cnt = collections.Counter(cards)
    over = novelty.overlap(cards, expand(SRC))
    worst, _who = novelty.nearest(cards, FIELD, OURS)
    overs = {c: (q, DECK_LIMIT.get(c, 0)) for c, q in cnt.items()
             if q > DECK_LIMIT.get(c, 0)}
    case("exactly 100 cards", len(cards), 100)
    check("no card over its deck_limit", not overs, str(overs)[:80])
    check("keeps a usable core of the source", over > 0, f"shares {over}/100")
    check("is not a copy of the source", over <= novelty.MAX_OVERLAP,
          f"shares {over}/100, cap {novelty.MAX_OVERLAP}")
    check("aims at the p90 of real pairs, not merely at the cap",
          over <= novelty.CORE_TARGET, f"{over} vs target {novelty.CORE_TARGET}")
    check("novel against the field as well as the source",
          worst <= novelty.MAX_OVERLAP, f"worst {worst}/100")
    check("card_order names every distinct card in the deck",
          set(order) == set(cnt), f"{len(order)} ordered, {len(cnt)} distinct")
    check("at most 15 slots are rules-text-free filler",
          cnt.get(INFINITE_FILLER, 0) <= 15, f"{cnt.get(INFINITE_FILLER, 0)} blanks")

print("a source card the catalogue has never heard of:")
case("refuses rather than silently fielding 93 cards",
     derive_from([999999] * 100, expand(OWN), CATALOG, FIELD, OURS, log=quiet), None)

print("our own list is one card, so the rest could only be blanks:")
case("refuses rather than padding half a deck with a blank",
     derive_from(expand(SRC), [413] * 7, CATALOG, FIELD, OURS, log=quiet), None)

print("an empty source:")
case("refuses", derive_from([], expand(OWN), CATALOG, FIELD, OURS, log=quiet), None)

print()
if fails:
    print(f"{len(fails)} failure(s)")
    for f in fails:
        print("  -", f)
    sys.exit(1)
print("0 failure(s)")
