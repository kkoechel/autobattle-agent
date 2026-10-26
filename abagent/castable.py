"""Can this deck ever actually PLAY this card?

A census of real play found 4.6 cards per hundred that never reach the board,
worst deck 36. The clearest case: a 72.1% poison deck running ten copies of
Shield Soldier -- "To Play: you must have gained at least 1 Shields this
turn" -- in a list containing nothing that gains shields.

ANSWERS "IMPOSSIBLE", NEVER "UNLIKELY". ShadowDrake's
Orbelavundiquethessaromphantilus needs 3 Life gained in one turn and that
deck holds exactly one card that gains life, so it IS castable -- about once
every three hundred games. A terrible card there, and this will not say so,
because saying so would be false. Only playing the deck finds the merely
futile; this refuses the provably dead, which is free.

Gates that depend on the OPPONENT are deliberately not judged: a deck cannot
know what it will face, and guessing would refuse good cards.
"""
from __future__ import annotations

import json

# Energy a long game plausibly reaches, for the cost-floor test.
REACHABLE_ENERGY = 25

# Metrics a deck drives ITSELF, and the effect types that drive them.
SELF_METRIC_SOURCES = {
    "shields_gained": ("gain_shield",),
    "life_gained": ("gain_life",),
    "honor_gained": ("gain_honor",),
}


def _effects(card: dict) -> dict:
    e = card.get("effects_json")
    if isinstance(e, str):
        try:
            e = json.loads(e)
        except ValueError:
            return {}
    return e if isinstance(e, dict) else {}


def _deck_can_produce(deck_ids, catalog, effect_types) -> bool:
    """Does any card in the deck carry one of these effect types?

    Searches the raw effects_json text rather than walking every nesting
    shape -- triggers, passives, supplements and additional costs all hold
    effects, and new shapes keep being added. A substring search cannot miss
    one, and a false positive only lets a dead card survive, which is the
    status quo.
    """
    for cid in set(deck_ids):
        raw = json.dumps(_effects(catalog.get(cid, {})))
        if any(f'"{t}"' in raw for t in effect_types):
            return True
    return False


def dead_reason(cid: int, deck_ids, catalog: dict) -> str | None:
    """Why this card can never be played in this deck, or None."""
    eff = _effects(catalog.get(cid, {}))
    if not eff:
        return None

    cs = eff.get("cost_scaling") or {}
    floor = cs.get("minimum")
    if isinstance(floor, int) and floor > REACHABLE_ENERGY:
        return f"costs at least {floor} energy"

    ac = eff.get("additional_cost") or {}
    honor = ac.get("honor") or 0
    if honor and not _deck_can_produce(deck_ids, catalog, ("gain_honor",)):
        return f"costs {honor} honour to play and the deck gains none"
    shields = ac.get("shields") or 0
    if shields and not _deck_can_produce(deck_ids, catalog, ("gain_shield",)):
        return f"costs {shields} shields to play and the deck gains none"

    for r in eff.get("play_requires") or []:
        if r.get("kind") != "turn_tally" or r.get("side") != "self":
            continue
        sources = SELF_METRIC_SOURCES.get(r.get("metric"))
        if sources and not _deck_can_produce(deck_ids, catalog, sources):
            return f"needs {r.get('metric')} and nothing in the deck produces it"
    return None


def dead_cards(deck_ids, catalog: dict) -> dict[int, str]:
    """Every provably unplayable card in this deck, with its reason."""
    out = {}
    for cid in set(deck_ids):
        why = dead_reason(cid, deck_ids, catalog)
        if why:
            out[cid] = why
    return out
