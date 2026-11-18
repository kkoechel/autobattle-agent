#!/usr/bin/env python3
"""Build a meta-shaped FIELD file from the league decks we ourselves entered.

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
from abagent.api import Api, ApiError                       # noqa: E402
from abagent.league import deck_name_for, fetch             # noqa: E402


def key_from(env_path: str) -> str | None:
    """AB_API_KEY out of a shell-style env file. Never logged."""
    try:
        with open(env_path) as fh:
            for line in fh:
                line = line.strip()
                if line.startswith("AB_API_KEY="):
                    return line.split("=", 1)[1].strip().strip("'\"")
    except OSError as e:
        print(f"  {env_path}: cannot read ({e.strerror})")
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--league", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--env", action="append", required=True)
    a = ap.parse_args()

    decks, seen = [], set()
    for env in a.env:
        who = os.path.basename(env).replace(".env", "")
        key = key_from(env)
        if not key:
            print(f"  {who}: no AB_API_KEY")
            continue
        api = Api(key)
        lgs = [x for x in fetch(api, log=lambda *_: None) if x.slug == a.league]
        if not lgs:
            print(f"  {who}: league not visible")
            continue
        lg = lgs[0]
        want = deck_name_for(lg)
        # The deck the ENTRY points at, not merely one named for the league:
        # an entered deck is the thing opponents will actually meet, and the
        # named deck may have been built but never submitted.
        did = lg.entered_deck_id
        if not did:
            by_name = {str(d.get("name") or ""): int(d["id"]) for d in api.decks()}
            did = by_name.get(want)
            if not did:
                print(f"  {who}: not entered and no '{want}'")
                continue
            print(f"  {who}: not entered; using '{want}' ({did})")
        if did in seen:
            continue
        try:
            row = api.deck(did)
        except ApiError as e:
            print(f"  {who}: deck {did} unreadable ({e.status})")
            continue
        cards = [int(c["card_id"]) for c in (row.get("cards") or [])
                 for _ in range(int(c["quantity"]))]
        if len(cards) != 100:
            print(f"  {who}: deck {did} has {len(cards)} cards, skipping")
            continue
        seen.add(did)
        decks.append({"deck_id": did,
                      "deck_name": str(row.get("name") or f"deck {did}"),
                      "cards": cards,
                      "battle_plan": row.get("battle_plan") or {}})
        print(f"  {who}: {row.get('name')} ({did}), "
              f"{len(set(cards))} distinct")

    if not decks:
        print("no decks found — refusing to write an empty field")
        return 1
    out = {"decks": decks, "cohort_id": 0,
           "finalized_at": f"synthetic field for {a.league}"}
    tmp = a.out + ".tmp"
    with open(tmp, "w") as fh:
        json.dump(out, fh)
    os.replace(tmp, a.out)
    print(f"wrote {a.out}: {len(decks)} deck(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
