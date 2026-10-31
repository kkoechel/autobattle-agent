#!/usr/bin/env python3
"""Can a run finish inside the wall instead of being killed at it?

Measured on subgames-nyc1 over 12h to 2026-10-31 19:40: the agents wanted
35,580 CPU-seconds and the league 7,631, against 43,200 available -- 100.0%
of one core -- and 18,448 of those seconds, 42.7% of the whole box, went on
runs that were killed at their TimeoutStartSec with nothing saved. abagent
was killed on 32 of 38 runs while needing 112 CPU-seconds against a 900s
wall: it was getting 15% of a core.

The first fix for that is serialising the agents so each gets the whole core.
This is the second: a run that still cannot finish should stop and keep what
it has, and a scan too big for the time left should not be started at full
size. The numbers below are the real per-deck screening costs off the box.

Run: python3 tools/check_deadline.py
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from abagent.cli import screen_cap

fails = []


def case(label, got, want):
    ok = got == want
    print(f"  {'ok  ' if ok else 'FAIL'}  {label}: {got!r}")
    if not ok:
        fails.append(f"{label}: got {got!r}, wanted {want!r}")


def n(remaining, per_deck, want, **kw):
    return screen_cap(remaining, per_deck, want, **kw)[0]


print("MEASURED — rook's scan, the worst case on the box.")
print("23 minutes of wall for a 21-deck batch, killed at 25, thirteen times:")
# 1400s observed for ~21 decks = ~67s/deck under 6-way contention.
case("1200s left, 67s/deck, 19 candidates -> a batch that fits",
     n(1200, 67.0, 19), 14)
case("serialised, the same scan costs ~11s/deck and nothing is capped",
     n(1200, 11.0, 19), 19)

print("\nMEASURED — the agents that never time out, for contrast.")
print("quill 64s and vane 53s per completed RUN, so a few s/deck:")
case("1200s left, 4s/deck, 19 candidates", n(1200, 4.0, 19), 19)

print("\nCONSTRUCTED — an unknown cost must not cap:")
case("first scan ever, no measurement yet", n(1200, None, 19), 19)
case("a zero measurement is not a measurement", n(1200, 0.0, 19), 19)
case("negative, from a clock that moved", n(1200, -5.0, 19), 19)

print("\nCONSTRUCTED — no deadline set means no cap:")
case("--deadline 0 (off)", n(None, 67.0, 19), 19)

print("\nCONSTRUCTED — the floor. A cap that reaches zero would stop the")
print("scan happening at all, and stop it silently, which is worse than an")
print("overrun you can see in the journal:")
case("60s left at 67s/deck would fit 0 — floor holds at 6",
     n(60, 67.0, 19), 6)
case("1s left, the same floor", n(1, 500.0, 19), 6)
case("already past the deadline", n(-300, 67.0, 19), 6)
case("floor is never more than we actually have",
     n(60, 67.0, 3), 3)

print("\nCONSTRUCTED — the 20% margin is for the install and save that")
print("follow the screen, which are not free:")
case("exactly enough for 19 without margin, so 15 with it",
     n(19 * 67.0, 67.0, 19), 15)

print("\nCONSTRUCTED — the explanation is only produced when it capped:")
got = screen_cap(1200, 67.0, 19)
case("capped: reason given", bool(got[1]), True)
got = screen_cap(1200, 4.0, 19)
case("not capped: reason empty", got[1], "")

print()
if fails:
    print(f"{len(fails)} failure(s):")
    for f in fails:
        print(f"  - {f}")
    sys.exit(1)
print("0 failure(s)")
