"""Which cards does this deck never actually play, against this field?

The static guard in castable.py answers "impossible". This answers "never
happened", which is a larger and more useful set: a census of the live league
found about 400 wasted copies where static analysis found 18. The difference
is cards that CAN be cast and never are -- a gate that needs three life
gained in a turn when the deck has one life source, a card sitting so low in
the play order the deck never reaches it.

Needs the instrumented engine at /opt/abagent/bin/census, built by
tools/census/build.sh, which refuses to produce a binary unless it returns a
byte-identical result hash to a clean build of the same source. A counter
that perturbs what it counts would be worthless.

SERVER ONLY by decision: a census is real matches, not inference, and the
phone already takes two minutes to build a deck.
"""
from __future__ import annotations

import json
import os
import subprocess
import tempfile

CENSUS_BIN = os.environ.get("ABAGENT_CENSUS",
                            "/opt/abagent/bin/census")


def available() -> bool:
    return os.path.isfile(CENSUS_BIN) and os.access(CENSUS_BIN, os.X_OK)


def never_played(cards: list[int], plan: dict, opponents, seeds: list[int],
                 catalog: list[dict], workdir: str | None = None) -> set[int]:
    """Cards in `cards` that never reached the board across these matches.

    Returns an empty set when the census binary is missing, rather than
    raising: an agent on a host without it should carry on unchanged, not
    fall over. The caller cannot tell "nothing was wasted" from "no census
    ran", so callers that care should check available() first.
    """
    if not available() or not cards or not opponents or not seeds:
        return set()

    slots = [{"slot_id": "me", "cards": cards, "battle_plan": plan or {},
              "champion_id": None}]
    matches = []
    for i, o in enumerate(opponents):
        sid = f"o{i}"
        slots.append({"slot_id": sid, "cards": list(o.cards),
                      "battle_plan": getattr(o, "battle_plan", {}) or {},
                      "champion_id": None})
        for s in seeds:
            matches.append({"match_id": f"me|{sid}|{s}", "slot_a": "me",
                            "slot_b": sid, "seed": s})

    payload = {"cards": {str(c["id"]): c for c in catalog},
               "slots": slots, "matches": matches, "chaos_effect": ""}
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False,
                                     dir=workdir) as fh:
        json.dump(payload, fh)
        path = fh.name
    try:
        proc = subprocess.run([CENSUS_BIN, path], capture_output=True, text=True)
        if proc.returncode != 0:
            return set()
        out = json.loads(proc.stdout)
    except (ValueError, OSError):
        return set()
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass

    key = (out.get("slot_keys") or {}).get("me")
    played = {int(c) for c in (out.get("played") or {}).get(key, {})}
    return {c for c in set(cards) if c not in played}


def prune(cards: list[int], dead: set[int], catalog: dict[int, dict],
          filler: int) -> list[int] | None:
    """Drop the dead and give their slots to cards already in the deck.

    Same rule the generators use for a cut: more copies of something that
    earns its place beats a card that never gets played. Returns None if
    the result would not be the same size, which the caller must treat as
    "leave the deck alone" -- a short deck is illegal and worse than waste.
    """
    if not dead:
        return None
    import collections
    cnt = collections.Counter(cards)
    freed = sum(cnt[c] for c in dead)
    keep = [(c, n) for c, n in cnt.items() if c not in dead]
    if not keep:
        return None
    limit = lambda c: int(catalog.get(c, {}).get("deck_limit") or 0)
    keep.sort(key=lambda kv: -kv[1])
    for i, (c, n) in enumerate(keep):
        if freed <= 0:
            break
        head = min(limit(c) - n, freed)
        if head > 0:
            keep[i] = (c, n + head)
            freed -= head
    out = [c for c, n in keep for _ in range(n)]
    if freed > 0:
        out += [filler] * freed
    return out if len(out) == len(cards) else None
