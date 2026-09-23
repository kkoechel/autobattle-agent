"""The battle plan: the whole strategy surface the engine exposes.

Every field is optional and omitted fields take engine defaults. Field names
and value sets come from the DeckUpdateRequest schema in api/openapi.json --
all three engines (Go, Python, JS) read the same keys.

Only 20 of the 70 decks in a live Standard cohort set a plan at all, but both
of the top two do, and both use play_priority=card_order. That is the first
thing worth testing.
"""
from __future__ import annotations

import random

# Ordered so coordinate descent touches the highest-leverage fields first.
# Each entry is (field, [candidate values]).
SCALAR_FIELDS: list[tuple[str, list]] = [
    ("play_priority", ["random", "cheapest", "costliest", "draw_order",
                       "card_order", "type_order", "tag_order"]),
    ("energy_hold", [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]),
    ("energy_hold_scope", ["every_turn", "first_play", "first_spend"]),
    # 'weakest'/'strongest' compare HP and also filter the pool to HP-bearing
    # permanents only -- that filtering is a strategy change in itself, not
    # just a tiebreak.
    ("target_preference", ["random", "weakest", "strongest", "least_armor"]),
    ("self_target_preference", ["random", "weakest", "strongest", "least_armor"]),
    # For additional costs (sacrifice/utilise), 'weakest' means CHEAPEST when
    # choosing from a pile, not lowest HP.
    ("cost_preference", ["random", "weakest", "strongest", "least_armor"]),
    ("discard_priority", ["random", "cheapest", "costliest", "lowest_priority"]),
]

# Fields that only do anything when play_priority is set to match.
CONDITIONAL_ON_PLAY_PRIORITY = {
    "card_order": "card_order",
    "card_order_hold": "card_order",
    "type_order": "type_order",
    "tag_order": "tag_order",
}

DEFAULT_PLAN: dict = {}


def is_live_field(plan: dict, field: str) -> bool:
    """False when a field cannot affect play given the rest of the plan --
    e.g. energy_hold_scope with energy_hold 0, or card_order without
    play_priority=card_order. Skipping dead fields keeps the search honest:
    a 'no improvement' result on a dead field is not evidence about anything.
    """
    need = CONDITIONAL_ON_PLAY_PRIORITY.get(field)
    if need and plan.get("play_priority") != need:
        return False
    if field == "energy_hold_scope" and not plan.get("energy_hold"):
        return False
    return True


def neighbors(plan: dict, field: str, values: list, cards: list[int] | None = None,
              card_info: dict[int, dict] | None = None) -> list[dict]:
    """Every one-field variation of `plan`, excluding the plan itself.

    For play_priority the variation also carries whatever companion list the
    value needs, so each candidate is the strategy it is named after rather
    than a silent fallback to draw order.
    """
    out = []
    for v in values:
        if plan.get(field) == v:
            continue
        p = dict(plan)
        p[field] = v
        if field == "play_priority" and cards is not None:
            key = COMPANION_LIST.get(v)
            if key and not p.get(key):
                p.update(seed_companion(v, cards, card_info))
                if key and not p.get(key):
                    continue  # cannot seed it -> would be a no-op, skip it
        out.append(p)
    return out


# play_priority values whose sort is a SILENT NO-OP when their companion list
# is empty. In validate.go each is guarded by `if len(plan.X) > 0`, so the hand
# keeps its existing order -- which is draw order. Offering these to a search
# without seeding the list does not test three strategies; it tests draw_order
# three times under three names, and then reports the winner under whichever
# name it happened to try first.
COMPANION_LIST = {
    "card_order": "card_order",
    "type_order": "type_order",
    "tag_order": "tag_order",
}


def seed_companion(value: str, cards: list[int],
                   card_info: dict[int, dict] | None = None) -> dict:
    """The extra keys a play_priority value needs to actually do anything."""
    key = COMPANION_LIST.get(value)
    if key is None:
        return {}
    if key == "card_order":
        return {"card_order": card_order_from_deck(cards)}
    if card_info is None:
        return {}
    counts: dict[str, int] = {}
    for cid in cards:
        info = card_info.get(cid) or {}
        if key == "type_order":
            t = info.get("subtype") or info.get("supertype")
            if t:
                counts[t] = counts.get(t, 0) + 1
        else:
            for tag in info.get("tags") or []:
                counts[tag] = counts.get(tag, 0) + 1
    ranked = [k for k, _ in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))]
    return {key: ranked} if ranked else {}


def card_order_from_deck(cards: list[int]) -> list[int]:
    """A card_order seed: distinct card ids, most-copies first.

    A deck built around 13 copies of one card is telling you what it wants to
    cast; copy count is a better prior than cost for an opening ranking.
    """
    counts: dict[int, int] = {}
    for c in cards:
        counts[c] = counts.get(c, 0) + 1
    return [cid for cid, _ in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))]


def shuffle_card_order(plan: dict, rng: random.Random, swaps: int = 2) -> dict:
    """A local permutation move: swap a few adjacent-ish pairs in card_order."""
    order = list(plan.get("card_order") or [])
    if len(order) < 2:
        return dict(plan)
    p = dict(plan)
    for _ in range(swaps):
        i = rng.randrange(len(order))
        j = min(len(order) - 1, max(0, i + rng.choice([-3, -2, -1, 1, 2, 3])))
        order[i], order[j] = order[j], order[i]
    p["card_order"] = order
    return p


def describe(plan: dict) -> str:
    if not plan:
        return "(engine defaults)"
    parts = []
    for k in ("play_priority", "energy_hold", "energy_hold_scope",
              "target_preference", "self_target_preference", "cost_preference",
              "discard_priority", "card_order_hold"):
        if k in plan and is_live_field(plan, k):
            parts.append(f"{k}={plan[k]}")
    if plan.get("card_order") and is_live_field(plan, "card_order"):
        parts.append(f"card_order[{len(plan['card_order'])}]")
    return " ".join(parts) or "(engine defaults)"
