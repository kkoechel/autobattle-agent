#!/usr/bin/env python3
"""Does the staleness check still catch a stall, now that a hold is movement?

The rotation guard added on 2026-10-31 installs nothing when the best
candidate screens below what is already in the slot. That is the fix working,
and under the original check -- which read installed_at alone -- a run of
those reads as an idle agent.

Health did go from 2 problems to 4 within half an hour of that deploy, and
that was NOT the guard: flint, pike, rook and sable had all last installed
hours earlier (19:58, 17:34, 21:24, 15:46 against a 00:12:33 deploy) and none
had held_at set at all. Two of them crossed the 3h line on their own. Their
decks are plateaued and the check is right about them.

So held_at now counts as movement. The risk in that change is the obvious one:
a check that stops failing is worth nothing, and this agent has stalled
silently five times. Hence the first three cases.

Run: python3 tools/check_health_idle.py
"""
import datetime, json, os, sys, tempfile
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from abagent.health import check_explorer

fails = []


def ago(h):
    return (datetime.datetime.utcnow()
            - datetime.timedelta(hours=h)).strftime("%Y-%m-%d %H:%M:%S")


def case(label, state, want_pass):
    d = tempfile.mkdtemp()
    json.dump(state, open(os.path.join(d, "explore.json"), "w"))
    ok, msg = check_explorer(d, max_idle_h=3.0)[0]
    good = (ok == want_pass)
    print(f"  {'ok  ' if good else 'FAIL'}  {label}: "
          f"{'pass' if ok else 'FAIL'} — {msg}")
    if not good:
        fails.append(f"{label}: wanted {'pass' if want_pass else 'FAIL'}")


BASE = {"tried": {"x": {}}, "tried_ids": ["a"], "best_screen": 10.0}

print("A REAL STALL MUST STILL BE CAUGHT:")
case("installed 9h ago, never held", {**BASE, "installed_at": ago(9)}, False)
case("no timestamps at all", dict(BASE), False)
case("installed 9h and held 9h — idle on both",
     {**BASE, "installed_at": ago(9), "held_at": ago(9)}, False)

print("a deliberate hold is a decision, so it counts as movement:")
# vane, 2026-10-31 01:25:35, the case this exists for.
case("installed 9h ago, held 0.4h ago",
     {**BASE, "installed_at": ago(9), "held_at": ago(0.4),
      "held_reason": "incumbent 13.7W beat the best candidate at 13.5W"}, True)
case("never installed, held 1h ago", {**BASE, "held_at": ago(1)}, True)

print("unchanged where nothing was ever held:")
case("installed 0.5h ago", {**BASE, "installed_at": ago(0.5)}, True)
case("installed 2.9h ago, just inside max_idle",
     {**BASE, "installed_at": ago(2.9)}, True)
case("installed 3.1h ago, just outside",
     {**BASE, "installed_at": ago(3.1)}, False)

print()
if fails:
    print(f"{len(fails)} failure(s)")
    for f in fails:
        print("  -", f)
    sys.exit(1)
print("0 failure(s)")
