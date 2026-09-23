"""Move families that change the card list and the play order together.

Two reasons they are one module and not two.

The mechanical one: in validate.go's sortHand, a card absent from card_order
gets rank 9999 and therefore sorts last. Once a deck runs
play_priority=card_order -- which is worth ~+17 wins and so every tuned deck
will -- a card added without being ranked is close to inert. Search the card
list alone and every candidate addition measures as worthless, for a reason
that has nothing to do with the card.

The empirical one: the field's best decks are separated from ours mostly by
which cards they play, and our own biggest confirmed gain so far came from
the order. Neither question is answerable while the other is held still.

Candidates come from the arena's own finalized cohort, which is a labelled
dataset: 70 real decks, full card lists, and the rank each one finished at.
That is a source of HYPOTHESES, not conclusions -- mean finishing rank per
card is thoroughly confounded (many of the standouts are deck_limit-1
legendaries, so the decks running them are also the better-resourced
accounts). Everything proposed here still has to survive the same paired
t-test against our own deck before it is believed.
"""
from __future__ import annotations

import collections
import random


# --- reading the field ---------------------------------------------------

def field_signal(meta_decks: list[dict], top_n: int = 15, min_decks: int = 4
                 ) -> tuple[collections.Counter, collections.Counter]:
    """(how many top decks run each card, total copies across those decks)."""
    present, copies = collections.Counter(), collections.Counter()
    for d in meta_decks[:top_n]:
        for cid, n in collections.Counter(d["cards"]).items():
            present[cid] += 1
            copies[cid] += n
    for cid in [c for c, n in present.items() if n < min_decks]:
        del present[cid], copies[cid]
    return present, copies


def add_candidates(mine: collections.Counter, present: collections.Counter,
                   copies: collections.Counter) -> list[tuple[int, int]]:
    """(card_id, typical copies) the top decks run and we do not, best first."""
    out = []
    for cid, n in present.most_common():
        if cid in mine:
            continue
        out.append((cid, max(1, round(copies[cid] / n))))
    return out


def cut_candidates(mine: collections.Counter, present: collections.Counter,
                   plan: dict) -> list[int]:
    """Our cards the top decks do not run, worst-ranked in card_order first.

    Ordering by card_order rank rather than by copy count puts the cards the
    deck itself has already deprioritised at the front of the queue, which is
    a better prior for "this slot is spare" than rarity or cost.
    """
    order = plan.get("card_order") or []
    rank = {cid: i for i, cid in enumerate(order)}
    spare = [cid for cid in mine if present.get(cid, 0) == 0]
    return sorted(spare, key=lambda c: -rank.get(c, 9999))


# --- the moves themselves ------------------------------------------------

def as_counter(cards: list[int]) -> collections.Counter:
    return collections.Counter(cards)


def flatten(counter: collections.Counter) -> list[int]:
    out: list[int] = []
    for cid, n in sorted(counter.items()):
        out.extend([cid] * n)
    return out


def swap(cards: list[int], plan: dict, cut: int, add: int, qty: int,
         deck_limit: int, rank: int | None = None) -> tuple[list[int], dict] | None:
    """Replace `qty` copies of `cut` with `add`, placed at `rank` in card_order.

    Deck size is held at exactly 100 -- the swap is quantity-neutral, so a
    deck that was legal stays legal.

    `rank` matters more than it looks. The obvious default, letting `add`
    inherit the card_order slot `cut` occupied, is actively wrong here: cut
    candidates are chosen precisely because the deck has already deprioritised
    them, so they sit at the BACK of the order. Inheriting that slot buries
    every addition at rank ~39 of 40, where sortHand reaches it last and it
    barely gets played -- and the search then concludes, for every strong card
    it tries, that the card is worthless. Appending would do the same thing.
    The caller must therefore try more than one rank per swap and let the
    measurement decide; `rank=None` keeps the inherit behaviour for callers
    that genuinely want it.
    """
    have = as_counter(cards)
    qty = min(qty, have.get(cut, 0), deck_limit)
    if qty <= 0 or cut == add:
        return None

    have[cut] -= qty
    if have[cut] <= 0:
        del have[cut]
    have[add] = have.get(add, 0) + qty
    if have[add] > deck_limit:
        return None

    order = [c for c in (plan.get("card_order") or []) if c in have or c == add]
    if add in order:
        order.remove(add)
    if rank is None:
        prev = list(plan.get("card_order") or [])
        rank = prev.index(cut) if cut in prev else len(order)
    order.insert(max(0, min(rank, len(order))), add)

    new_plan = dict(plan)
    new_plan["card_order"] = order
    return flatten(have), new_plan


def swap_ranks(order_len: int) -> list[int]:
    """Where to try a newly added card: the front, and a couple of depths.

    The front is not a guess -- card_order[0] is the entry the engine reserves
    hold-energy for, and the early ranks are simply what it reaches first.
    """
    cands = [0, 1, max(0, order_len // 4), max(0, order_len // 2)]
    return sorted(set(cands))


def reorder(plan: dict, src: int, dst: int) -> dict | None:
    """Move the card at position `src` in card_order to position `dst`."""
    order = list(plan.get("card_order") or [])
    if not order or src == dst or not (0 <= src < len(order)) or not (0 <= dst < len(order)):
        return None
    cid = order.pop(src)
    order.insert(dst, cid)
    p = dict(plan)
    p["card_order"] = order
    return p


def order_moves(plan: dict, rng: random.Random, n: int = 12,
                promote_bias: float = 0.6) -> list[dict]:
    """A sample of single-card repositionings in card_order.

    Biased towards promotions: the front of the order is where the decisions
    actually get made, since hold-energy reservation keys on card_order[0]
    specifically and the early ranks are what the engine reaches for first.
    Moving card 31 to card 32 is almost always a no-op.
    """
    order = plan.get("card_order") or []
    if len(order) < 3:
        return []
    out, seen = [], set()
    for _ in range(n * 4):
        if len(out) >= n:
            break
        src = rng.randrange(len(order))
        if rng.random() < promote_bias:
            dst = rng.randrange(0, max(1, src)) if src else 0
        else:
            dst = rng.randrange(len(order))
        if (src, dst) in seen or src == dst:
            continue
        seen.add((src, dst))
        p = reorder(plan, src, dst)
        if p:
            out.append(p)
    return out
