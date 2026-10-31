#!/usr/bin/env python3
"""Does a banked "it stopped improving" verdict ever expire?

Until 2026-10-31 it did not. `refine_patience` banked a deck, the hold branch
read the bank as permanent, and the agent spent every cycle afterwards on a
scan it then rejected. Sixteen hours after the rotation guard shipped, four of
eight explorers sat at focus=None holding a banked deck -- and between them
they climbed twice. The guard was right to hold the deck; nothing was right
about never touching it again.

The FIRST FOUR cases below are the real entries off subgames-nyc1, copied
verbatim at 2026-10-31 18:30 UTC. The rest are constructed. That line is drawn
on purpose: an earlier survey of these same agents reported a conclusion that
turned out to be an artifact of its own method, so what is measured and what
is assumed are labelled separately here.

Run: python3 tools/check_rebank.py
"""
import sys, os, calendar, time
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from abagent.cli import bank_is_live

fails = []
NOW = calendar.timegm(time.strptime("2026-10-31 18:30:00", "%Y-%m-%d %H:%M:%S"))


def ago(hours):
    return time.strftime("%Y-%m-%d %H:%M:%S",
                         time.gmtime(NOW - hours * 3600))


def case(label, entry, cap, want_live):
    live, why = bank_is_live(entry, cap, now=NOW)
    ok = live == want_live
    print(f"  {'ok  ' if ok else 'FAIL'}  {label}: "
          f"{'HOLD' if live else 'CLIMB'} — {why}")
    if not ok:
        fails.append(f"{label}: live={live}, wanted {want_live}")


print("MEASURED — the four frozen agents, as found on the box.")
print("None of them carries banked_at, so every one expires on contact,")
print("which is the whole reason the missing timestamp means 'expired':")
case("quill 'The Riotous Yield' banked at 15.3W",
     {"screen_wins": 15.3, "live_wins": 82.2, "live_rank": 11.9,
      "cohorts": 12, "refined_to": 15.3}, 6.0, False)
case("rook 'Silence of the Hollow' banked at 16.0W",
     {"screen_wins": 16.6, "live_wins": 77.4, "live_rank": 17.9,
      "cohorts": 12, "refined_to": 16.0}, 6.0, False)
case("sable 'Rise of the Golden Hive' banked at 23.2W",
     {"screen_wins": 17.3, "live_wins": 71.1, "live_rank": 1.1,
      "cohorts": 12, "refined_to": 23.2}, 6.0, False)
case("wren 'Last Wrath of the Riotous' banked at 22.5W",
     {"screen_wins": 19.5, "live_wins": 27.7, "live_rank": 1.6,
      "cohorts": 12, "refined_to": 22.5}, 6.0, False)

print("\nCONSTRUCTED — a bank written by the patched code, as it ages:")
case("banked 0.5h ago", {"refined_to": 16.0, "banked_at": ago(0.5)},
     6.0, True)
case("banked 5.9h ago, just inside the cap",
     {"refined_to": 16.0, "banked_at": ago(5.9)}, 6.0, True)
case("banked exactly 6.0h ago — the cap is inclusive, so it expires",
     {"refined_to": 16.0, "banked_at": ago(6.0)}, 6.0, False)
case("banked 11.0h ago", {"refined_to": 16.0, "banked_at": ago(11.0)},
     6.0, False)

print("\nCONSTRUCTED — what is not a bank at all:")
case("never banked, only screened", {"screen_wins": 13.7}, 6.0, False)
case("no entry for this deck", None, 6.0, False)
case("refined_to explicitly null", {"refined_to": None}, 6.0, False)

print("\nCONSTRUCTED — a deck banked at 0.0W is still banked.")
print("The branch this replaces tested the truthiness of refined_to, so a")
print("deck that climbed to nothing read as never banked:")
case("banked at 0.0W, 1h ago",
     {"refined_to": 0.0, "banked_at": ago(1.0)}, 6.0, True)

print("\nCONSTRUCTED — timestamps that cannot be trusted:")
case("banked_at is not a timestamp",
     {"refined_to": 16.0, "banked_at": "last Tuesday"}, 6.0, False)
case("banked_at is an int", {"refined_to": 16.0, "banked_at": 1762000000},
     6.0, False)
case("banked_at is empty", {"refined_to": 16.0, "banked_at": ""}, 6.0, False)
print("  (a clock that ran backwards leaves a bank in the future: it holds,")
print("   because a negative age is still under any positive cap)")
case("banked 2h in the future",
     {"refined_to": 16.0, "banked_at": ago(-2.0)}, 6.0, True)

print("\nCONSTRUCTED — the cap is an operator dial:")
case("--rebank-after 0 banks nothing, ever",
     {"refined_to": 16.0, "banked_at": ago(0.001)}, 0.0, False)
case("--rebank-after 720 (30d) keeps a month-old bank",
     {"refined_to": 16.0, "banked_at": ago(500.0)}, 720.0, True)

print()
if fails:
    print(f"{len(fails)} failure(s):")
    for f in fails:
        print(f"  - {f}")
    sys.exit(1)
print("0 failure(s)")
