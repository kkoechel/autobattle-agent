#!/usr/bin/env python3
"""Does castable.py still find the card the whole dead-card hunt is about?

Muck Crawler -- "To Play: you must have cured at least 1 Poison this turn" --
is the known positive. It has now been found three times: ten copies in
FlintVale's "Brazen Blight" on 2026-10-26, and one copy each in two live
agent decks on 2026-10-29, AFTER the generator had been wired to
castable.dead_cards. It kept passing because SELF_METRIC_SOURCES knew three
metrics and poison_cured was not one of them, so the gate was never examined
and every report downstream said clean.

So this exists to make that specific silence impossible. It is the sibling of
the main app's `php shell/deck_dead_cards.php --canary`, and the verdicts
below were taken from that command on 2026-10-29.

    python3 tools/check_castable.py [path/to/cards.json]

The catalogue defaults to var/cards.json, or $AGENT_CARDS. It must contain
every card named here: opponent routes are read catalogue-WIDE, so a partial
catalogue silently changes verdicts ("nothing can ever poison you" becomes
true when you only look at seventeen cards). An inadequate catalogue is a
failure, never a pass -- refresh with `python3 -m abagent.cli fetch`.
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from abagent import castable           # noqa: E402

MUCK_CRAWLER = 1139      # needs self poison_cured
VIROXX_AGENT = 1109      # needs self poison_gained >= 3 -- REACHABLE here, must stay
APOTHECARY = 4           # removes 1 Poison from yourself at start of turn
MAGEFLESH_BEETLE = 779   # poisons YOU, 2 per spell played

# FlintVale's deck as the 2026-10-26 handover found it: ten dead Crawlers.
# Ids pinned, not names, and kept here because the agent has since rewritten
# the deck and no version history was kept. Mirrors DC_CANARY_DECK in the main
# app's inc/deck_castability.php.
BRAZEN_BLIGHT = {
    1189: 15, 828: 15, 1: 15, 21: 10, MUCK_CRAWLER: 10, MAGEFLESH_BEETLE: 7,
    700: 7, 821: 7, 517: 3, VIROXX_AGENT: 3, 555: 3, 683: 1, 748: 1, 679: 1,
    30: 1, 401: 1,
}

# The two live decks the main app's monitor reported on 2026-10-29, pinned as
# they stood at 14:02 UTC. Each had been rewritten by its agent that morning
# and each rewrite kept one Muck Crawler.
#
# 102186 already runs Mageflesh Beetle, so it CAN poison itself -- and still
# cannot cure, which is the half that matters. 51580 can do neither.
FLINT_102186 = {
    1: 15, 517: 15, 828: 15, 1189: 15, 21: 10, 700: 7, MAGEFLESH_BEETLE: 7,
    821: 7, 30: 1, 80: 1, 679: 1, 683: 1, 748: 1, 888: 1, 921: 1, 1035: 1,
    MUCK_CRAWLER: 1,
}
ROOK_51580 = {
    1: 17, 260: 15, 1086: 15, 1115: 15, 782: 10, 983: 10, 1002: 10, 995: 3,
    679: 1, 683: 1, 723: 1, 1123: 1, MUCK_CRAWLER: 1,
}


def flatten(deck: dict[int, int]) -> list[int]:
    return [cid for cid, n in deck.items() for _ in range(n)]


def plus(deck: dict[int, int], *add: int) -> dict[int, int]:
    """The same deck with one copy of each card added, held at 100 cards.

    The slots come off the biggest pile, because a deck is a hundred cards and
    a fixture that quietly runs 102 is answering a question nobody asked.
    """
    out = dict(deck)
    for cid in add:
        out[cid] = out.get(cid, 0) + 1
        biggest = max((c for c in out if c not in add), key=lambda c: out[c])
        out[biggest] -= 1
    return out


def dead(deck: dict[int, int], catalog: dict, chaos: bool) -> dict[int, str]:
    return castable.dead_cards(flatten(deck), catalog, proven_only=True, chaos=chaos)


def main(argv: list[str]) -> int:
    root = Path(__file__).resolve().parent.parent
    path = Path(argv[1]) if len(argv) > 1 else Path(os.environ.get("AGENT_CARDS") or root / "var/cards.json")
    if not path.exists():
        print(f"no catalogue at {path} -- `python3 -m abagent.cli fetch`")
        return 1
    raw = json.load(open(path))
    cards = raw["cards"] if isinstance(raw, dict) else raw
    catalog = {int(c["id"]): c for c in cards}

    need = set(BRAZEN_BLIGHT) | set(FLINT_102186) | set(ROOK_51580) | {APOTHECARY, MAGEFLESH_BEETLE}
    missing = sorted(need - set(catalog))
    if missing:
        print(f"catalogue {path} ({len(catalog)} cards) is missing {missing}")
        print("  a verdict read off a catalogue this thin is not a verdict --")
        print("  refresh it with `python3 -m abagent.cli fetch`")
        return 1

    fails: list[str] = []

    def want_dead(label: str, deck: dict[int, int], cid: int, chaos: bool) -> None:
        d = dead(deck, catalog, chaos)
        if cid in d:
            print(f"  ok      {label}: {catalog[cid]['name']} -- {d[cid]}")
        else:
            others = ", ".join(catalog[c]["name"] for c in d) or "nothing"
            fails.append(f"{label}: {catalog[cid]['name']} NOT flagged (flagged: {others})")

    def want_alive(label: str, deck: dict[int, int], cid: int, chaos: bool) -> None:
        d = dead(deck, catalog, chaos)
        if cid not in d:
            print(f"  ok      {label}: {catalog[cid]['name']} is not called dead")
        else:
            fails.append(f"{label}: {catalog[cid]['name']} WRONGLY flagged -- {d[cid]}")

    # The known positive, under both arena settings. Two things keep the
    # verdict steady: poison_cured has no Chaos roll behind it, and as
    # measured on 2026-10-29 the chaos flag cannot change ANY verdict (see
    # dead_reason's docstring). So these six lines are one claim about Muck
    # Crawler and not two -- worth having when the tables move, not evidence
    # that arena handling works.
    for chaos in (True, False):
        tag = "chaos" if chaos else "pure "
        want_dead(f"Brazen Blight   [{tag}]", BRAZEN_BLIGHT, MUCK_CRAWLER, chaos)
        want_dead(f"deck 102186     [{tag}]", FLINT_102186, MUCK_CRAWLER, chaos)
        want_dead(f"deck 51580      [{tag}]", ROOK_51580, MUCK_CRAWLER, chaos)

    # Viroxx Agent in that same list needs 3 self poison_gained and the seven
    # Beetles supply it: rare, reachable, must never be cut.
    want_alive("Brazen Blight   [reach]", BRAZEN_BLIGHT, VIROXX_AGENT, True)

    # Give each deck what it was missing and the verdict must go away.
    want_alive("102186 + cure   [fixed]", plus(FLINT_102186, APOTHECARY), MUCK_CRAWLER, True)
    want_alive("51580 + both    [fixed]", plus(ROOK_51580, APOTHECARY, MAGEFLESH_BEETLE), MUCK_CRAWLER, False)

    # A cure with nothing to cure: an OPPONENT can still poison you, so this
    # is not a proof and must stay unjudged. The promise at the top of
    # castable.py, as a test.
    want_alive("51580 + cure    [opp]  ", plus(ROOK_51580, APOTHECARY), MUCK_CRAWLER, False)

    # Nothing else in these decks may be called dead: the main app's detector
    # reports exactly one finding on each, and a port that finds more is
    # cutting live cards.
    for label, deck, chaos in (("Brazen Blight", BRAZEN_BLIGHT, True),
                               ("deck 102186", FLINT_102186, True),
                               ("deck 51580", ROOK_51580, False)):
        extra = {c: w for c, w in dead(deck, catalog, chaos).items() if c != MUCK_CRAWLER}
        if extra:
            fails.append(f"{label}: extra findings the main app does not have: "
                         + "; ".join(f"{catalog[c]['name']} ({w})" for c, w in extra.items()))
        else:
            print(f"  ok      {label}: no finding beyond Muck Crawler")

    for f in fails:
        print(f"  FAILED  {f}")
    print(f"{len(fails)} failure(s), catalogue {path.name} ({len(catalog)} cards)")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
