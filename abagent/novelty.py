"""Tell a new deck apart from a copy of someone else's.

Ranking well is uninteresting if the deck got there by cloning the deck above
it. Measured 2026-09-26, the two agents held ranks 1 and 2 with lists sharing
97 of 100 cards -- with each other, and with the meta deck they were both
mutated from. That is the search working exactly as specified and producing
nothing worth looking at.

So novelty becomes a constraint rather than a hope, and the threshold comes
from the field rather than from taste. Across all 2701 pairs of a live
74-deck cohort:

    median   6 shared cards        p90   42
    p75     25                     p95   70

The field is genuinely diverse; the mass above 70 is literal duplicates
(three players running an identical "Power Surge", and the shared beginner
decks). 50 sits above the 90th percentile of real pairs and well below the
duplicates, so it admits the 25-30 cards of common staples every deck runs
while rejecting a list that is somebody else's with three cards changed.

The important asymmetry: overlap with OUR OWN decks does not count. Mutating
a stranger's top deck is copying; mutating our own is iterating, which is the
thing we want to keep doing slowly.
"""
from __future__ import annotations

import collections

# Above the 90th percentile of real pairwise overlap, below the duplicates.
MAX_OVERLAP = 50


def overlap(a: list[int], b: list[int]) -> int:
    """Cards in common, counting copies -- 5x of a card shared 3 times is 3."""
    return sum((collections.Counter(a) & collections.Counter(b)).values())


def nearest(cards: list[int], meta_decks: list[dict],
            own_deck_ids: set[int] | None = None) -> tuple[int, dict | None]:
    """The most similar deck in the field that is not ours, and by how much."""
    own = own_deck_ids or set()
    worst, who = 0, None
    for d in meta_decks:
        if d.get("deck_id") in own:
            continue
        ids = d.get("cards") or []
        if len(ids) < 50:
            continue
        n = overlap(cards, ids)
        if n > worst:
            worst, who = n, d
    return worst, who


def is_novel(cards: list[int], meta_decks: list[dict],
             own_deck_ids: set[int] | None = None,
             max_overlap: int = MAX_OVERLAP) -> bool:
    return nearest(cards, meta_decks, own_deck_ids)[0] <= max_overlap


def describe(cards: list[int], meta_decks: list[dict],
             own_deck_ids: set[int] | None = None) -> str:
    n, who = nearest(cards, meta_decks, own_deck_ids)
    if who is None:
        return "shares nothing with the field"
    return (f"shares {n}/100 with '{str(who.get('deck_name'))[:22]}' "
            f"(rank {who.get('rank')})")
