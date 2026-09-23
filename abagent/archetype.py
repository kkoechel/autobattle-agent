"""Build whole decks around a seed card, instead of sprinkling cards into one.

The census answered "does this card improve puoisson v12" for all 115 unplayed
cards and the answer was no, every time. That is not evidence the cards are
bad. Substituting into a tuned deck measures FIT, and a card needs its
enablers -- search, recursion, ramp, a curve built for it -- before it can
show what it does. Two cards that only work together, dropped into a poison
shell, test neither.

So this constructs a deck for the seed rather than around an incumbent:

  seed        at its deck_limit, ranked first in the play order
  synergy     cards sharing the seed's tags, by overlap
  staples     what the field runs regardless of archetype, which is a
              measured set rather than a guess -- Ancient Power Station is in
              42 of 70 decks, Quick Study and Mana Spring in 21
  filler      infinite-rarity cards to reach exactly 100

Most of these decks will be bad. That is the point: a generator that only
produces good decks is not exploring, and the second-entry pass means a bad
experiment costs nothing -- only the better-placing of the two entries is
paid, so the primary deck keeps the placement.
"""
from __future__ import annotations

from dataclasses import dataclass, field as dfield

from .moves import is_playable

# Tags that describe provenance or triggers rather than what a card is for.
# Overlapping on 'common' or 'trigger-start-of-turn' is not synergy.
NOISE_TAGS = {
    "common", "uncommon", "rare", "mythic", "legendary", "infinite", "token",
    "trigger-start-of-turn", "trigger-enter", "trigger-death", "trigger-end-of-turn",
    "creature", "relic", "spell", "structure", "enchantment",
}

INFINITE_FILLER = 57          # Straw Man-at-Arms


@dataclass
class Archetype:
    seed: int
    seed_name: str
    cards: list[int]
    plan: dict
    theme: list[str] = dfield(default_factory=list)
    members: list[tuple[int, int]] = dfield(default_factory=list)

    def summary(self, catalog: dict[int, dict], n: int = 6) -> str:
        nm = lambda c: (catalog.get(c, {}).get("name") or f"#{c}")
        top = ", ".join(f"{q}x {nm(c)}" for c, q in self.members[:n])
        return f"[{'/'.join(self.theme[:3])}] {top}"


def theme_of(seed: int, catalog: dict[int, dict]) -> list[str]:
    return [t for t in (catalog.get(seed, {}).get("tags") or [])
            if t not in NOISE_TAGS]


def affinity(cid: int, theme: set[str], catalog: dict[int, dict]) -> float:
    """How much of the theme this card shares, normalised by its own breadth.

    Normalising matters: without it a card carrying twenty tags outranks a
    card carrying exactly the two that define the archetype, purely by
    covering more ground.
    """
    tags = {t for t in (catalog.get(cid, {}).get("tags") or []) if t not in NOISE_TAGS}
    if not tags or not theme:
        return 0.0
    return len(tags & theme) / (len(tags | theme) ** 0.5)


def staples(meta_decks: list[dict], catalog: dict[int, dict], top: int = 10
            ) -> list[int]:
    """Cards the field runs across archetypes, most widespread first."""
    seen: dict[int, int] = {}
    for d in meta_decks:
        for cid in set(d["cards"]):
            seen[cid] = seen.get(cid, 0) + 1
    from .moves import is_playable
    ranked = sorted(seen.items(), key=lambda kv: -kv[1])
    return [cid for cid, _ in ranked if is_playable(catalog.get(cid))][:top]


def build(seed: int, catalog: dict[int, dict], meta_decks: list[dict],
          size: int = 100, staple_slots: int = 30, pool: int = 14
          ) -> Archetype | None:
    """One deck built for `seed`. None when the seed cannot legally headline."""
    limit = lambda c: int(catalog.get(c, {}).get("deck_limit") or 0)
    if not is_playable(catalog.get(seed)):
        return None

    theme = theme_of(seed, catalog)
    if not theme:
        return None
    tset = set(theme)

    picks: list[tuple[int, int]] = [(seed, limit(seed))]
    total = limit(seed)

    ranked = sorted(
        (c for c in catalog if c != seed and is_playable(catalog[c])),
        key=lambda c: (-affinity(c, tset, catalog), int(catalog[c].get("cost") or 0)))

    room = size - staple_slots
    for cid in ranked[:pool]:
        if total >= room:
            break
        if affinity(cid, tset, catalog) <= 0:
            break
        q = min(limit(cid), room - total)
        if q > 0:
            picks.append((cid, q))
            total += q

    for cid in staples(meta_decks, catalog, top=10):
        if total >= size:
            break
        if any(cid == c for c, _ in picks):
            continue
        q = min(limit(cid), size - total, 15)
        if q > 0:
            picks.append((cid, q))
            total += q

    if total < size:                       # pad to exactly 100
        picks.append((INFINITE_FILLER, size - total))
        total = size

    cards: list[int] = []
    for cid, q in picks:
        cards.extend([cid] * q)
    if len(cards) != size:
        return None

    # card_order is worth more than any other plan field, and an archetype's
    # own ordering is the one thing a generator can get right for free: the
    # seed first, then its synergy pieces, then the generic staples.
    plan = {
        "play_priority": "card_order",
        "card_order": [c for c, _ in picks],
        "card_order_hold": False,
        "energy_hold": 0,
        "target_preference": "least_armor",
    }
    return Archetype(seed=seed, seed_name=catalog.get(seed, {}).get("name", str(seed)),
                     cards=cards, plan=plan, theme=theme,
                     members=sorted(picks, key=lambda kv: -kv[1]))


def recent_seeds(catalog: dict[int, dict], days: int = 7,
                 now: str | None = None) -> list[int]:
    """Cards added in the last `days`, newest first.

    Every card carries created_at, so this needs no snapshot diffing -- and
    new cards are the single best exploration target available. Nobody has
    built around one that shipped this morning, by definition, so it cannot
    already be priced into the metagame the way an old unplayed card can be.
    Around 80 cards arrived in September and the field plays only 2 of the 10
    newest.

    Deliberately NOT filtered by whether the field already plays the card.
    "A deck exists that runs one copy" and "anyone has built around it" are
    different claims, and only the second is what this is looking for.
    """
    import datetime
    today = datetime.date.fromisoformat(now) if now else datetime.date.today()
    cutoff = (today - datetime.timedelta(days=days)).isoformat()
    fresh = [(str(c.get("created_at") or ""), cid) for cid, c in catalog.items()
             if str(c.get("created_at") or "")[:10] >= cutoff
             and is_playable(c) and (c.get("rules_text") or "").strip()]
    return [cid for _, cid in sorted(fresh, reverse=True)]


def generate(seeds: list[int], catalog: dict[int, dict], meta_decks: list[dict],
             **kw) -> list[Archetype]:
    out = []
    for s in seeds:
        a = build(s, catalog, meta_decks, **kw)
        if a:
            out.append(a)
    return out


# Names come from the theme tags, so a generated deck arrives describing
# itself. "abagent explore #47" tells a human nothing; "Rust and Ransom"
# tells them it breaks its own relics for profit, which is what the deck does.
TAG_WORDS: dict[str, tuple[str, str]] = {
    "poison":        ("Venom", "Blight"),
    "damage":        ("Ember", "Ruin"),
    "melee":         ("Iron", "Fury"),
    "honor":         ("Gilded", "Oath"),
    "shield":        ("Bulwark", "Aegis"),
    "life":          ("Verdant", "Grace"),
    "draw":          ("Whispering", "Archive"),
    "mill":          ("Hollow", "Oblivion"),
    "discard":       ("Ashen", "Famine"),
    "energy":        ("Surging", "Current"),
    "token-copy":    ("Teeming", "Swarm"),
    "scaling":       ("Rising", "Crescendo"),
    "cost-scaling":  ("Thrifty", "Bargain"),
    "cost-modifier": ("Patron", "Tithe"),
    "exile":         ("Vanishing", "Void"),
    "bounce":        ("Tidal", "Undertow"),
    "anthem":        ("Banner", "Chorus"),
    "on-tag-leave":  ("Rust", "Ransom"),
    "drawback":      ("Bitter", "Price"),
    "utilize":       ("Scavenging", "Salvage"),
    "recycle":       ("Eternal", "Return"),
    "structure":     ("Bastion", "Keep"),
    "relic":         ("Reliquary", "Hoard"),
    "soldier":       ("Marching", "Legion"),
    "beast":         ("Feral", "Wild"),
    "elf":           ("Sylvan", "Court"),
    "dragon":        ("Wyrm", "Pyre"),
    "bee":           ("Golden", "Hive"),
    "divine":        ("Radiant", "Choir"),
    "station":       ("Clockwork", "Engine"),
    "elemental":     ("Storm", "Tempest"),
    "token":         ("Legion", "Host"),
    "scholar":       ("Studious", "Codex"),
    "trigger-death": ("Mourning", "Wake"),
}


def name_for(theme: list[str], fallback: str) -> str:
    """An evocative name from the two most characteristic theme tags.

    Adjective from the first, noun from the second, so the name reads as a
    phrase rather than two nouns stapled together. Unknown tags fall back to
    the seed card, which is at least specific.
    """
    known = [t for t in theme if t in TAG_WORDS]
    if not known:
        return fallback
    if len(known) == 1:
        adj, noun = TAG_WORDS[known[0]]
        return f"{adj} {noun}"
    adj = TAG_WORDS[known[0]][0]
    noun = TAG_WORDS[known[1]][1]
    return f"{adj} {noun}"
