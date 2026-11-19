#!/usr/bin/env python3
"""Write a meta-shaped FIELD file for the league decks we ourselves entered.

Why this exists. `bootstrap --league` screens candidates against an arena's
meta, which for a restricted league measures the wrong thing: in an expansion
league every opponent draws from the same pool, so "beats the Standard field"
is not the question. The league's actual field is knowable here because it is
our own entries -- GET /leagues reports only the caller's entry, but we hold
every caller's key.

    python3 tools/league_field.py --league <slug> --out field.json \
        --env /etc/abagent.env --env /etc/abagent-ember.env ...

Each --env is a file holding AB_API_KEY=... (and anything else); the key is
read, used, and never printed. An account with no deck for that league is
skipped with a note, not an error -- a field of eight is still a field.

The work is in abagent.league.field_from_entries(), so this tool and
`abagent league-refine` cannot drift apart. REBUILD BETWEEN RUNS: see that
function's docstring for the convergence it prevents.
"""
from __future__ import annotations

import argparse
import json
import os
import sys

# The repo when run from it, /opt/abagent when run from a copy on the host --
# these are one-off operator tools and they get copied around, so finding the
# package is their job rather than the caller's.
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
if not os.path.isdir(os.path.join(sys.path[0], "abagent")):
    sys.path.insert(0, "/opt/abagent")
from abagent.api import Api                                      # noqa: E402
from abagent.league import field_from_entries, key_from          # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--league", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--env", action="append", required=True)
    a = ap.parse_args()

    accounts = []
    for env in a.env:
        who = os.path.basename(env).replace(".env", "")
        key = key_from(env)
        if not key:
            print(f"  {who}: no AB_API_KEY")
            continue
        accounts.append((who, Api(key)))

    out = field_from_entries(accounts, a.league)
    if out is None:
        print("no decks found — refusing to write an empty field")
        return 1
    tmp = a.out + ".tmp"
    with open(tmp, "w") as fh:
        json.dump(out, fh)
    os.replace(tmp, a.out)
    print(f"wrote {a.out}: {len(out['decks'])} deck(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
