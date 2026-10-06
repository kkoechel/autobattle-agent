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
    """Cards the field runs across archetypes, most widespread first.

    Cards with no rules text are excluded even though they are among the most
    widespread. Straw Man-at-Arms is in 33 of 70 decks, second only to Ancient
    Power Station -- not because it does anything, but because it is what
    everyone pads to 100 with. Counting deck presence alone mistakes that
    popularity for utility, and it put 15 blank cards into every generated
    deck: 15% of a list, in a format that plays itself, where a dead draw is
    simply a wasted turn.
    """
    seen: dict[int, int] = {}
    for d in meta_decks:
        for cid in set(d["cards"]):
            seen[cid] = seen.get(cid, 0) + 1
    from .moves import is_playable
    # Tie-break on card id, explicitly. Sorting on count alone leaves ties in
    # dict insertion order, which here comes from iterating set(d["cards"]) --
    # so which of six cards tied at 21 decks made the cut was decided by
    # CPython's set iteration order. Staples are added until the deck is full,
    # so that implementation detail was choosing cards for every generated
    # deck, and it is not reproducible in another language or guaranteed
    # across interpreter versions.
    ranked = sorted(seen.items(), key=lambda kv: (-kv[1], kv[0]))
    return [cid for cid, _ in ranked
            if is_playable(catalog.get(cid))
            and (catalog.get(cid, {}).get("rules_text") or "").strip()][:top]


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

    # Tie-break on card id, explicitly. Affinity and cost leave genuine ties --
    # Elvish Poetry and Honorable Knowledge are identical on both for a
    # bounce/draw/honor theme -- and without a third key the winner is decided
    # by catalog dict insertion order, which is the order GET /cards happened
    # to return. cards.json is not sorted by id (it starts 904, 683, 835), so
    # a generated deck depended on a remote ORDER BY.
    ranked = sorted(
        (c for c in catalog if c != seed and is_playable(catalog[c])),
        key=lambda c: (-affinity(c, tset, catalog), int(catalog[c].get("cost") or 0), c))

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

    # Top up what we already chose before reaching for filler. The first
    # version padded straight to 100 with Straw Man-at-Arms, whose rules text
    # is empty -- 15% of every generated deck was a card that does nothing,
    # in a format where the deck is played for you and a dead draw is simply a
    # wasted turn. More copies of a card the archetype actually wants is
    # strictly better than a blank, and only genuinely runs out at the point
    # every pick is at its deck_limit.
    if total < size:
        for i, (cid, q) in enumerate(picks):
            if total >= size:
                break
            headroom = min(limit(cid) - q, size - total)
            if headroom > 0:
                picks[i] = (cid, q + headroom)
                total += headroom
    if total < size:                       # genuinely nothing left to add
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
# Alternatives, so a theme is not locked to one adjective and one noun. With
# 34 of each the name space was small enough that two unrelated decks collided
# constantly, which is what the hash suffix was papering over.
EXTRA_ADJECTIVES: dict[str, list[str]] = {
    "poison": ["Creeping", "Septic"], "damage": ["Scorching", "Riotous"],
    "melee": ["Brazen", "Relentless"], "honor": ["Solemn", "Vaunted"],
    "shield": ["Steadfast", "Warded"], "life": ["Flourishing", "Gentle"],
    "draw": ["Patient", "Endless"], "mill": ["Quiet", "Creeping"],
    "discard": ["Barren", "Spent"], "energy": ["Restless", "Humming"],
    "token-copy": ["Countless", "Swarming"], "scaling": ["Mounting", "Swelling"],
    "exile": ["Forgotten", "Silent"], "bounce": ["Rolling", "Returning"],
    "recycle": ["Enduring", "Turning"], "structure": ["Walled", "Anchored"],
    "relic": ["Hoarded", "Gilded"], "soldier": ["Disciplined", "Massed"],
    "beast": ["Untamed", "Prowling"], "divine": ["Hallowed", "Luminous"],
    "station": ["Whirring", "Geared"], "elemental": ["Howling", "Churning"],
    "scholar": ["Quiet", "Lettered"], "utilize": ["Thrifty", "Resourceful"],
    "drawback": ["Reckless", "Costly"], "destroy": ["Ruinous", "Breaking"],
    "sacrifice": ["Devoted", "Willing"], "symmetric": ["Even", "Mirrored"],
}

EXTRA_NOUNS: dict[str, list[str]] = {
    "poison": ["Bloom", "Rot"], "damage": ["Cinders", "Wrath"],
    "melee": ["Charge", "Advance"], "honor": ["Vow", "Creed"],
    "shield": ["Rampart", "Vigil"], "life": ["Verdure", "Mercy"],
    "draw": ["Library", "Current"], "mill": ["Silence", "Dust"],
    "discard": ["Hunger", "Waste"], "energy": ["Dynamo", "Tide"],
    "token-copy": ["Multitude", "Tide"], "scaling": ["Ascent", "Groundswell"],
    "exile": ["Absence", "Hush"], "bounce": ["Reprise", "Eddy"],
    "recycle": ["Cycle", "Revival"], "structure": ["Redoubt", "Hold"],
    "relic": ["Vault", "Trove"], "soldier": ["Column", "Muster"],
    "beast": ["Pack", "Thicket"], "divine": ["Vespers", "Halo"],
    "station": ["Works", "Foundry"], "elemental": ["Gale", "Maelstrom"],
    "scholar": ["Treatise", "Margin"], "utilize": ["Salvage", "Yield"],
    "drawback": ["Bargain", "Toll"], "destroy": ["Undoing", "Collapse"],
    "sacrifice": ["Offering", "Oblation"], "symmetric": ["Balance", "Accord"],
}

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


def name_for(theme: list[str], fallback: str,
             cards: list[int] | None = None,
             taken: set[str] | None = None) -> str:
    """A deck name a person could have chosen.

    This used to append four hex characters of a hash of the card list, so a
    refined deck got a visibly different name. kkoechel pointed out what that
    actually communicates: "Thrifty Bargain 719e" tells every player on the
    gallery that a bot made it. The agents are meant to be indistinguishable
    from a person playing well, and a hex suffix is a tell.

    The problem the suffix solved was real -- two different decks both called
    "Iron Fury" in one log line, a discovery you could not tell from the thing
    it replaced. The better answer is the one kkoechel gave: a NEW deck picks a
    new name, and a refined deck keeps its own, because it is the same deck
    getting better rather than a different one. Progress is visible in the
    journal and the screen numbers, which is where it belongs.

    Variety instead of a hash: each theme word carries several adjectives and
    nouns and the result is cast into one of a few phrasings, so the space is
    thousands of names rather than 34. `taken` rules out anything already on
    the board -- our own decks and other players' -- so collisions are
    resolved against reality rather than hoped away.
    """
    import hashlib

    known = [t for t in theme if t in TAG_WORDS]
    if not known:
        base_adj, base_noun = None, None
    else:
        base_adj = TAG_WORDS[known[0]][0]
        base_noun = TAG_WORDS[known[1] if len(known) > 1 else known[0]][1]
    if not base_adj or not base_noun:
        return fallback

    adjs = [base_adj] + EXTRA_ADJECTIVES.get(known[0], [])
    nouns = [base_noun] + EXTRA_NOUNS.get(known[-1], [])

    # Deterministic, so the same deck proposes the same name first -- but the
    # determinism is in the ORDER tried, never visible in the output.
    h = 0
    if cards:
        h = int(hashlib.sha1(
            ",".join(str(c) for c in sorted(cards)).encode()).hexdigest()[:8], 16)

    # Patterns that are grammatical whatever words land in them. An earlier
    # set included "{adj}'s {noun}" and produced "Relentless's Wrath" and
    # "Iron's Cinders" -- a possessive needs a name, not an adjective, and a
    # deck called that reads as generated just as loudly as a hex suffix did.
    patterns = [
        "{adj} {noun}",
        "The {adj} {noun}",
        "{noun} of the {adj}",
        "{noun} Eternal",
        "Rise of the {adj} {noun}",
        "Last {noun} of the {adj}",
    ]
    taken = {t.lower() for t in (taken or set())}
    for k in range(len(patterns) * len(adjs) * len(nouns)):
        i = h + k
        nm = patterns[i % len(patterns)].format(
            adj=adjs[(i // len(patterns)) % len(adjs)],
            noun=nouns[(i // (len(patterns) * len(adjs))) % len(nouns)])
        if nm.lower() not in taken:
            return nm
    return fallback


def mutate(cards: list[int], plan: dict, catalog: dict[int, dict],
           pool: list[int], rng, swaps: int = 3, qty_cap: int = 10
           ) -> tuple[list[int], dict, list[tuple[int, int, int]]] | None:
    """Perturb a WORKING deck with cards nobody plays.

    The two generators here fail in opposite directions. Field mining only
    ever proposes a card someone already runs, so it cannot be unorthodox by
    construction. Tag archetypes build from scratch and produce naive shells:
    they score 12-16 wins against a field of 60-win decks, because a coherent
    theme is not the same as a working deck.

    What neither does is combine them -- take a list that demonstrably works
    and substitute in cards the metagame has never tried. The shell supplies
    the engine, the curve and the play order; the pool supplies the surprise.
    That is where an unorthodox deck that also functions is most likely to be.

    Cuts are biased toward the back of card_order, the cards the deck itself
    plays last, so the engine that makes it work is left intact. Returns
    (cards, plan, [(cut, add, qty)]) or None if nothing legal could be built.
    """
    import collections
    counts = collections.Counter(cards)
    order = list(plan.get("card_order") or [])
    rank = {cid: i for i, cid in enumerate(order)}
    # Worst-ranked first, and unranked cards count as worst.
    # Never cut a card some tutor in this deck searches for.
    #
    # Targets are deliberately placed LAST in the play order so an 18-energy
    # payoff is never cast off the top -- and victims are taken from the back
    # of that order, so without this the very next refinement removes the
    # target again and the deck oscillates: closure adds it, mutate cuts it,
    # closure adds it back, forever.
    protected = set()
    for cid in counts:
        for tgt, _n in _tutor_targets(catalog.get(cid) or {}):
            protected.add(tgt)
    victims = [c for c in sorted(counts, key=lambda c: -rank.get(c, 9999))
               if c not in protected]
    adds = [c for c in pool if c not in counts and is_playable(catalog.get(c))]
    if not adds or not victims:
        return None
    rng.shuffle(adds)

    new = collections.Counter(counts)
    log: list[tuple[int, int, int]] = []
    for cut in victims[:swaps * 3]:
        if len(log) >= swaps or not adds:
            break
        add = adds.pop()
        limit = int(catalog.get(add, {}).get("deck_limit") or 0)
        qty = min(new.get(cut, 0), limit, qty_cap)
        if qty <= 0:
            continue
        new[cut] -= qty
        if new[cut] <= 0:
            del new[cut]
        new[add] = new.get(add, 0) + qty
        log.append((cut, add, qty))
    if not log:
        return None

    # The new cards inherit the play-order slots of what they replaced, except
    # they are placed one rank EARLIER: a card that was being played last was
    # chosen for removal precisely because it was not worth playing, and
    # inheriting that slot would test the newcomer under the same handicap.
    neworder = [c for c in order if c in new]
    for cut, add, _ in log:
        pos = rank.get(cut, len(neworder))
        neworder.insert(max(0, min(pos - 1, len(neworder))), add)
    for cid in new:
        if cid not in neworder:
            neworder.append(cid)

    out: list[int] = []
    for cid, n in sorted(new.items()):
        out.extend([cid] * n)
    if len(out) != len(cards):
        return None
    p = dict(plan)
    p["card_order"] = neworder
    return out, p, log


def _tutor_targets(card: dict) -> list[tuple[int, int]]:
    """(card_id, amount) this card searches the LIBRARY for, by name.

    Only `tutor`. `create_token_copy` also names a card_id -- 71 cards use it
    against the tutor's 16 -- but a token is created from the definition, not
    drawn from your deck, so the definition only has to reach the ENGINE, which
    it does because the payload carries the whole catalogue. Including token
    definitions as deck slots would burn fifteen cards on something that is
    never drawn.
    """
    import json as _json
    ej = card.get("effects_json")
    if isinstance(ej, str):
        try:
            ej = _json.loads(ej)
        except ValueError:
            return []
    out: list[tuple[int, int]] = []

    def walk(o):
        if isinstance(o, dict):
            if o.get("type") == "tutor" and isinstance(o.get("card_id"), int):
                out.append((o["card_id"], max(1, int(o.get("amount") or 1))))
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)

    walk(ej or {})
    return out


def ensure_tutor_targets(cards: list[int], plan: dict, catalog: dict[int, dict],
                         log=None) -> tuple[list[int], dict]:
    """Put the card a tutor searches for into the deck that runs the tutor.

    A tutor whose target is absent is a blank. Deck 51580 shipped with Last
    Scholar of Gghulbb and no Gghulbb, Larval Stage, so its whole payoff --
    "when removed from play, search your library for Gghulbb and put it into
    play" -- did nothing at all. No generator could notice: affinity() matches
    tags, compose() ranks measured singles, and neither reads effects_json.

    The targets that matter are the ones no generator would ever pick on its
    own. Gghulbb costs 18 and Starry-Eyed Horror of Ay costs 13; a curve-aware
    builder excludes them on sight, which is exactly why a card exists to cheat
    them into play.

    So they go in at the MINIMUM the tutor needs and LAST in the play order.
    They are there to be found, not cast: an 18-energy card played off the top
    is a brick, and adding one to the front of the order would cost more than
    the tutor gains. Slots come from the deck's own lowest-priority cards.

    Iterated to a fixed point, since a tutored card may itself tutor.

    WHAT THIS IS WORTH, measured rather than assumed: on deck 51580 at 78
    opponents x 301 cohorts, +0.02 wins, t=0.04. Nothing. A 61-cohort run
    first said +0.93 at t=0.85 and that was noise.

    It is kept anyway, and not as a disguised win. Last Scholar of Gghulbb is
    a 1-of whose tutor fires only when it leaves play, and the card it fetches
    costs 18 -- the mechanism is genuinely broken and the magnitude is still
    nil, because one card in a hundred decided across 78 opponents cannot move
    an aggregate. What it buys is a deck that means what it says: a human
    reading the gallery sees a tutor with its payoff rather than a card doing
    nothing, and these decks are meant to be interesting to look at, not only
    to win. It measured free, so that costs nothing.

    Where it could actually matter is a deck running several copies of a
    tutor. Most multi-copy tutors fetch Ancient Power Station, which nearly
    every deck already runs, so they are satisfied by accident today.
    """
    cur = list(cards)
    order = list(plan.get("card_order") or [])
    added: list[tuple[int, int]] = []

    for _ in range(4):                       # fixed point; depth is 1 today
        have = set(cur)
        want: dict[int, int] = {}
        for cid in have:
            for tgt, amount in _tutor_targets(catalog.get(cid) or {}):
                if tgt in have or tgt == cid:
                    continue
                if not is_playable(catalog.get(tgt)):
                    continue
                lim = int(catalog.get(tgt, {}).get("deck_limit") or 0)
                n = min(amount, lim)
                if n > 0:
                    want[tgt] = max(want.get(tgt, 0), n)
        if not want:
            break

        # Slots come from the back of the play order, the cards this deck
        # itself ranked last -- the same prior every other cut here uses.
        #
        # But never from a tutor target. Targets are appended to the BACK of
        # the order, which is exactly where this looks for victims, so with
        # two targets to place the second one cut the first: the log read
        # "added 1x Warboss, 1x Yugyrf, 1x Warboss, 1x Yugyrf" as the fixed
        # point chased itself, and the deck shipped without Warboss anyway.
        rank = {c: i for i, c in enumerate(order)}
        counts: dict[int, int] = {}
        for c in cur:
            counts[c] = counts.get(c, 0) + 1
        keep = set(want)
        for cid in counts:
            for t, _n in _tutor_targets(catalog.get(cid) or {}):
                keep.add(t)
        spare = [c for c in sorted(counts, key=lambda c: -rank.get(c, 10_000))
                 if c not in keep]

        for tgt, n in sorted(want.items()):
            need = n
            for victim in spare:
                if need <= 0:
                    break
                if victim == tgt or counts.get(victim, 0) <= 0:
                    continue
                take = min(need, counts[victim])
                counts[victim] -= take
                need -= take
            if need > 0:                     # nothing left to give up
                continue
            counts[tgt] = counts.get(tgt, 0) + n
            added.append((tgt, n))
            if tgt not in order:
                order.append(tgt)            # last: found, not cast

        cur = []
        for c, q in sorted(counts.items()):
            cur.extend([c] * q)
        order = [c for c in order if counts.get(c, 0) > 0]
        for c in counts:
            if c not in order:
                order.append(c)

    if not added:
        return cards, plan
    if len(cur) != len(cards):               # never ship a wrong-sized deck
        return cards, plan
    if log:
        nm = lambda c: (catalog.get(c, {}).get("name") or f"#{c}")
        log("tutor closure: added " + ", ".join(f"{n}x {nm(t)}" for t, n in added))
    new_plan = dict(plan)
    new_plan["card_order"] = order
    return cur, new_plan


# Effects that WANT the tag on the other side of the table. A card that
# destroys every [bee] is hate for bee decks, not a bee payoff, and reading it
# as a dependency says the opposite of the truth.
_HOSTILE_EFFECTS = {
    "destroy_tagged", "exile_tagged", "discard_tagged", "bounce_tagged",
    "steal_tagged", "damage_tagged",
}


def _tag_deps(card: dict, catalog: dict[int, dict] | None = None) -> set[str]:
    """Tags this card needs YOUR OWN board to supply.

    Read from effects_json, because a card's own tags say what it IS and the
    dependency says what it NEEDS. Three things are deliberately not
    dependencies, each of which made Enemy Hive look inert in a deck where it
    is one of the best cards:

      side/target_player = opponent — Enemy Hive's cost scales with the bees
        the OPPONENT has, which is the opposite of needing bees.
      destroy/exile of a tag — "destroy all [bee] permanents" is hate. A deck
        playing it wants no bees of its own.
      tags the card creates itself — Enemy Hive makes Bee Drones every turn
        and Greedy Dragon makes Gold Coins, so each supplies its own tag.

    Without all three, pruning would have cut 10 copies of the top combo hub
    out of two decks.
    """
    import json as _json
    ej = card.get("effects_json")
    if isinstance(ej, str):
        try:
            ej = _json.loads(ej)
        except ValueError:
            return set()
    out: set[str] = set()

    def walk(o, hostile=False):
        if isinstance(o, dict):
            typ = o.get("type")
            here = hostile or (typ in _HOSTILE_EFFECTS)
            foreign = (o.get("side") == "opponent"
                       or o.get("target_player") == "opponent")
            if not here and not foreign:
                t = o.get("tag")
                if isinstance(t, str) and t:
                    out.add(t)
                for k in ("tags", "only_tags"):
                    v = o.get(k)
                    if isinstance(v, list):
                        out.update(x for x in v if isinstance(x, str))
            for v in o.values():
                walk(v, here)
        elif isinstance(o, list):
            for v in o:
                walk(v, hostile)

    walk(ej or {})

    # Whatever this card puts on the board itself satisfies its own needs.
    if catalog:
        for tok, _n in _token_defs(card):
            out -= set(catalog.get(tok, {}).get("tags") or [])
    return out


def _token_defs(card: dict) -> list[tuple[int, int]]:
    """(card_id, amount) this card creates as tokens."""
    import json as _json
    ej = card.get("effects_json")
    if isinstance(ej, str):
        try:
            ej = _json.loads(ej)
        except ValueError:
            return []
    out: list[tuple[int, int]] = []

    def walk(o):
        if isinstance(o, dict):
            if o.get("type") == "create_token_copy" and isinstance(o.get("card_id"), int):
                out.append((o["card_id"], max(1, int(o.get("amount") or 1))))
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)

    walk(ej or {})
    return out


def dead_clause_cards(cards: list[int], catalog: dict[int, dict]) -> dict[int, set[str]]:
    """Cards in this deck whose every tag-gated effect has no enabler here.

    Deck 42179 ran 10 copies of Battlefield Engineer and no structures at all.
    Both of its effects are gated on [structure] -- the cost reduction AND the
    shield generation -- so ten slots, a tenth of the deck, were a vanilla
    2-cost body. No generator could see it: affinity() reads the card's own
    tags, which say what it IS, while the dependency lives in effects_json and
    says what it NEEDS.

    A card's own tags count towards satisfying it, because a creature that
    buffs creatures enables itself.
    """
    present: set[str] = set()
    for cid in set(cards):
        present.update(catalog.get(cid, {}).get("tags") or [])
    out: dict[int, set[str]] = {}
    for cid in set(cards):
        need = _tag_deps(catalog.get(cid) or {}, catalog)
        if not need:
            continue
        missing = {t for t in need if t not in present}
        if missing and missing == need:      # every gated effect is inert
            out[cid] = missing
    return out


def prune_dead_clauses(cards: list[int], plan: dict, catalog: dict[int, dict],
                       log=None) -> tuple[list[int], dict]:
    """Drop cards whose gated effects are all inert, and redistribute the slots.

    Measured on deck 42179 at 78 opponents x 201 cohorts, against the deck as
    it shipped:

        cut the 10 dead Engineers for more of what worked   +1.77, t=7.93
        keep 5 and add 5 structures to turn them on         +1.53, t=6.80

    Both clear the bar, and cutting wins -- so this prunes rather than
    enables. That is a bigger effect than it sounds next to the tutor closure
    (+0.02): a dead tutor is one card that does nothing, while ten dead
    Engineers are ten slots competing for play priority with cards that work.
    Dilution is cheap; opportunity cost is not.

    Slots go to the deck's own highest-priority cards, up to their deck_limit
    -- the cards this deck already decided to play first.
    """
    counts: dict[int, int] = {}
    for c in cards:
        counts[c] = counts.get(c, 0) + 1
    dead = dead_clause_cards(cards, catalog)

    # Never prune something another card needs: a tutor's target, or the only
    # carrier of a tag something else depends on.
    protected: set[int] = set()
    for cid in counts:
        for tgt, _n in _tutor_targets(catalog.get(cid) or {}):
            protected.add(tgt)
    for cid in counts:
        if cid in dead:
            continue
        for t in _tag_deps(catalog.get(cid) or {}, catalog):
            carriers = [c for c in counts if t in (catalog.get(c, {}).get("tags") or [])]
            if len(carriers) <= 1:
                protected.update(carriers)
    dead = {c: m for c, m in dead.items() if c not in protected}
    if not dead:
        return cards, plan

    order = list(plan.get("card_order") or [])
    rank = {c: i for i, c in enumerate(order)}
    freed = sum(counts[c] for c in dead)
    for c in dead:
        del counts[c]

    # Redistribute to what this deck already plays first.
    for cid in sorted(counts, key=lambda c: rank.get(c, 10_000)):
        if freed <= 0:
            break
        head = min(int(catalog.get(cid, {}).get("deck_limit") or 0) - counts[cid], freed)
        if head > 0:
            counts[cid] += head
            freed -= head
    if freed > 0:
        counts[INFINITE_FILLER] = counts.get(INFINITE_FILLER, 0) + freed
        freed = 0

    out: list[int] = []
    for c, q in sorted(counts.items()):
        out.extend([c] * q)
    if len(out) != len(cards):
        return cards, plan
    if log:
        nm = lambda c: (catalog.get(c, {}).get("name") or f"#{c}")
        log("pruned inert: " + ", ".join(
            f"{nm(c)} (needs {'/'.join(sorted(m))})" for c, m in dead.items()))
    new_plan = dict(plan)
    new_plan["card_order"] = [c for c in order if c in counts]
    return out, new_plan
