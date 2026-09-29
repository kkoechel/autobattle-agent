"""Find clauses in a deck that cannot fire, and say which ones cost anything.

kkoechel found four of these by reading the deck gallery, which is a better
bug-finding channel than the search has been: none of the three generators
opens effects_json, so none of them can see a clause that is inert. What the
gallery cannot do is tell a defect from an untidiness, and measured over ~200
cohorts each the four landed in three different places:

  10x Battlefield Engineer, no structures     +2.53 wins, t=7.59 to prune
  1x tutor with no target                     +0.02 -- cosmetic
  10x Men of Artifice, relics cost 13/15      -0.25 to prune, -0.92 to enable
  Scheming Librarian's honor clause           matchup-dependent, not inert

So the report separates them on the rule those measurements imply: an inert
card costs wins only if it still gets PLAYED. A dead 2-cost body occupies a
board slot and play priority against cards that work; a 0-cost card whose
additional cost can never be paid is never cast at all, and dilution is
roughly free. The second kind is worth knowing about and not worth fixing --
replacing ten harmless dead slots with ten copies of a genuinely weak card
that does get cast measured worse than leaving them alone.
"""
from __future__ import annotations

import collections
from dataclasses import dataclass

from .archetype import _tag_deps, _tutor_targets
from .moves import is_playable

# A cost this high means the consumer waits most of the game for it.
LATE = 8


@dataclass
class Finding:
    card: int
    name: str
    copies: int
    kind: str                 # dead-tag | unpayable-cost | missing-tutor-target
    detail: str
    cast: bool                # does this card actually reach the board?
    slots: int                # copies, i.e. how much of the deck is involved

    @property
    def severity(self) -> str:
        """Measured, not guessed -- see the module docstring."""
        if self.kind == "missing-tutor-target":
            return "cosmetic"
        if not self.cast:
            return "cosmetic"
        return "costs wins" if self.slots >= 5 else "minor"

    def __str__(self) -> str:
        return (f"[{self.severity:10}] {self.copies:2}x {self.name[:28]:30} "
                f"{self.kind:22} {self.detail}")


def _additional_costs(card: dict) -> list[tuple[str, str]]:
    """(kind, what) this card must give up to be played at all.

    Men of Artifice costs 0 energy and "To Play: Utilize a relic" -- so its
    real cost is a relic on the board, and a deck whose cheapest relic costs
    15 cannot cast a free card until turn fifteen.
    """
    import json as _json
    ej = card.get("effects_json")
    if isinstance(ej, str):
        try:
            ej = _json.loads(ej)
        except ValueError:
            return []
    ac = (ej or {}).get("additional_cost")
    if not isinstance(ac, dict):
        return []
    out = []
    for key in ("utilize", "sacrifice"):
        v = ac.get(key)
        if isinstance(v, str) and v:
            out.append((key, v))
    return out


def _supply(cards: list[int], what: str, catalog: dict[int, dict]
            ) -> tuple[int, int | None]:
    """(copies, cheapest cost) of cards in the deck that can pay `what`."""
    copies, cheapest = 0, None
    for cid, q in collections.Counter(cards).items():
        c = catalog.get(cid) or {}
        if what in (c.get("tags") or []) or c.get("supertype") == what:
            copies += q
            cost = int(c.get("cost") or 0)
            cheapest = cost if cheapest is None else min(cheapest, cost)
    return copies, cheapest


def scan(cards: list[int], plan: dict, catalog: dict[int, dict]) -> list[Finding]:
    counts = collections.Counter(cards)
    present: set[str] = set()
    for cid in counts:
        present.update(catalog.get(cid, {}).get("tags") or [])

    out: list[Finding] = []
    for cid, q in counts.items():
        card = catalog.get(cid) or {}
        name = card.get("name") or f"#{cid}"
        cost = int(card.get("cost") or 0)

        # Can this card reach the board at all? A blocking additional cost the
        # deck cannot pay early is what makes an inert card harmless rather
        # than expensive, so it is computed first and reused below.
        blocked = None
        for kind, what in _additional_costs(card):
            supply, cheapest = _supply(cards, what, catalog)
            if supply == 0:
                blocked = f"needs to {kind} a {what}; the deck has none"
            elif cheapest is not None and cheapest >= LATE:
                blocked = (f"needs to {kind} a {what}; cheapest in deck costs "
                           f"{cheapest}, and there are {supply} of them for "
                           f"{q} copies")
            elif supply < q:
                blocked = (f"needs to {kind} a {what}; only {supply} in the "
                           f"deck for {q} copies")
        cast = blocked is None

        if blocked:
            out.append(Finding(cid, name, q, "unpayable-cost", blocked, cast, q))

        need = _tag_deps(card, catalog)
        missing = {t for t in need if t not in present}
        if missing and missing == need:
            out.append(Finding(
                cid, name, q, "dead-tag",
                f"every gated effect needs [{'/'.join(sorted(missing))}], "
                f"which nothing here carries", cast, q))

        # Deduped: The Golden Princess carries two tutor effects naming the
        # same card, and reporting one finding twice reads as two problems.
        for tgt in sorted({t for t, _n in _tutor_targets(card)}):
            if is_playable(catalog.get(tgt)) and tgt not in counts:
                tn = (catalog.get(tgt) or {}).get("name") or f"#{tgt}"
                out.append(Finding(
                    cid, name, q, "missing-tutor-target",
                    f"searches for {tn}, which is not in the deck", cast, q))

    order = {"costs wins": 0, "minor": 1, "cosmetic": 2}
    out.sort(key=lambda f: (order[f.severity], -f.slots, f.name))
    return out


def report(decks: list[tuple[str, list[int], dict]], catalog: dict[int, dict]) -> str:
    """One readable block per deck, worst first, with a verdict line."""
    lines: list[str] = []
    total = collections.Counter()
    for label, cards, plan in decks:
        found = scan(cards, plan, catalog)
        lines.append(f"\n{label}  ({len(cards)} cards)")
        if not found:
            lines.append("    coherent -- nothing inert")
            continue
        for f in found:
            lines.append("    " + str(f))
            total[f.severity] += 1
    head = ("AutoBattle deck coherence\n"
            "=========================\n"
            f"{len(decks)} deck(s): "
            + ", ".join(f"{n} {k}" for k, n in sorted(total.items()))
            + ("" if total else "nothing inert anywhere") + "\n"
            "\ncosts wins  an inert card that still gets CAST -- it takes a board\n"
            "            slot and play priority from a card that works. Pruning\n"
            "            one such case measured +2.53 wins, t=7.59.\n"
            "minor       the same, but on fewer than five slots.\n"
            "cosmetic    inert and never cast, or a missing tutor target. Measured\n"
            "            -0.25 to +0.02: worth knowing, not worth fixing. Adding\n"
            "            enablers for one of these measured -0.92, t=-2.81.\n")
    return head + "\n".join(lines) + "\n"
