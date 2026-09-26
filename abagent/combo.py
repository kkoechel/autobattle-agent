"""Find which cards make each other better, one theme at a time.

The agent had two generators and both of them copy. `mutate()` takes a deck
from the top of the field and swaps three cards into it, which produces
competitive lists precisely because it inherits someone else's work -- measured
2026-09-26, the two agents' decks shared 97 of 100 cards with each other while
holding ranks 1 and 2. `archetype.build()` is the one that could produce
something genuinely new and it screens 4-10 of 24, so it never survives a
rotation.

The reason build() fails is that `affinity()` ranks cards by TAG OVERLAP, which
is a lexical notion of synergy. It gathers cards that are ABOUT the same thing
rather than cards that MAKE EACH OTHER BETTER. A station deck needs a payoff
that scales with stations; a soldier deck needs anthems; mill needs a win
condition. None of that is visible in a tag.

So this measures the thing tags stand in for. Within one theme:

  1. score every card in the theme against a blank, in a themed shell
  2. take the best of them and test every PAIR for super-additivity --
     is AB worth more than A plus B?
  3. keep what survives, and hand the generator measured combos instead of
     tag overlap

Pairs are the cheap unit that tag overlap cannot express, and a theme is small
enough to sweep: 12 shortlisted cards is 66 pairs, which at 24 opponents and 5
cohorts is about 90 seconds on the droplet. Findings accumulate across runs, so
the map of what works together grows rather than being rediscovered.
"""
from __future__ import annotations

import json
import os
from dataclasses import dataclass, field

from . import archetype, explore
from .moves import is_playable

# Tags that describe provenance or timing, never a deck's plan.
NOISE = {
    "common", "uncommon", "rare", "mythic", "legendary", "infinite", "token",
    "creature", "relic", "spell", "structure", "enchantment",
}

MIN_THEME_CARDS = 6      # below this a tag cannot headline a 100-card deck
SHORTLIST = 12           # 12 singles -> 66 pairs


def themes(catalog: dict[int, dict], min_cards: int = MIN_THEME_CARDS) -> list[str]:
    """Every tag with enough playable cards to build around, commonest first."""
    counts: dict[str, int] = {}
    for cid, c in catalog.items():
        if not is_playable(c):
            continue
        for t in (c.get("tags") or []):
            if t in NOISE or t.startswith("trigger-"):
                continue
            counts[t] = counts.get(t, 0) + 1
    return [t for t, n in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))
            if n >= min_cards]


def theme_cards(catalog: dict[int, dict], tag: str) -> list[int]:
    """Playable cards carrying the tag, in a deterministic order.

    Sorted by deck_limit first: a card you may run fifteen of can define a
    list, a 1-of cannot however strong it is. Id breaks ties explicitly, so
    the sweep is reproducible across runs and languages.
    """
    return sorted(
        (cid for cid, c in catalog.items()
         if is_playable(c) and tag in (c.get("tags") or [])
         and (c.get("rules_text") or "").strip()),
        key=lambda c: (-int(catalog[c].get("deck_limit") or 0),
                       int(catalog[c].get("cost") or 0), c),
    )


@dataclass
class Confirmed:
    """A pair re-measured properly, with the interaction contrast tested.

    Synergy is a difference OF differences, so its error is bigger than any
    single arm's -- roughly sqrt(3) times, since AB, A and B each carry their
    own. At the 5 seeds the screen uses, the standard error on synergy is
    about 0.9 wins, which makes every number the screen produces smaller than
    its own noise. The first run of this duly reported eight "super-additive"
    soldier pairs topping out at +0.6, every one of them indistinguishable
    from zero.

    So the screen ranks and this decides, on its own seed block, with a paired
    t over the per-seed contrast.
    """
    a: int
    b: int
    a_name: str
    b_name: str
    synergy: float
    t: float
    joint: float
    additive: float

    @property
    def significant(self) -> bool:
        """Distinguishable from zero, whatever its size."""
        return self.t >= 2.0

    @property
    def real(self) -> bool:
        from .search import MIN_GAIN
        return self.synergy >= MIN_GAIN and self.t >= 2.0

    def __str__(self) -> str:
        # Three states, not two. Void Station + Nexus Station measured +0.2 at
        # t=3.4 -- comfortably distinguishable from zero and far too small to
        # move a placement, since a single cohort has a ~2-win SD. Calling
        # that "noise" is as wrong as calling it a finding.
        if self.real:
            verdict = "REAL"
        elif self.significant:
            verdict = "real but too small to matter"
        else:
            verdict = "noise"
        return (f"{self.a_name[:20]:22} + {self.b_name[:20]:22} "
                f"synergy {self.synergy:+5.1f}  t {self.t:+5.1f}  {verdict}")


@dataclass
class Combo:
    a: int
    b: int
    a_name: str
    b_name: str
    joint: float            # AB versus the control arm
    additive: float         # A + B, each versus the same control
    synergy: float          # joint - additive; > 0 means they need each other

    def __str__(self) -> str:
        return (f"{self.a_name[:20]:22} + {self.b_name[:20]:22} "
                f"joint {self.joint:+5.1f}  additive {self.additive:+5.1f}  "
                f"synergy {self.synergy:+5.1f}")


@dataclass
class ThemeReport:
    tag: str
    shell_seed: int
    shell_screen: float
    singles: list = field(default_factory=list)     # explore.CardResult
    combos: list[Combo] = field(default_factory=list)
    confirmed: list[Confirmed] = field(default_factory=list)

    def best_pairs(self, n: int = 5) -> list[Combo]:
        return self.combos[:n]


def _shell_for(tag: str, catalog: dict[int, dict], meta_decks: list[dict]):
    """A themed deck to measure inside.

    Deliberately the theme's own naive archetype rather than a strong meta
    list. Substituting soldier cards into a poison deck measures how well they
    fit a poison deck, which is the exact error this module exists to correct;
    a card has to sit among its own enablers before it can show what it does.
    The shell being weak is fine -- everything is scored against a blank in the
    same shell, so the comparison is internal.
    """
    for seed in theme_cards(catalog, tag)[:6]:
        built = archetype.build(seed, catalog, meta_decks)
        if built:
            return built
    return None


def _cut_slot(built) -> int | None:
    """Which card the census replaces: the shell's own lowest priority.

    Every candidate replaces the SAME card at the same play rank, so the only
    thing varying between measurements is the card itself.
    """
    order = built.plan.get("card_order") or []
    for cid in reversed(order):
        if sum(q for c, q in built.members if c == cid) >= 5:
            return cid
    return order[-1] if order else None


def sweep(h, catalog: dict[int, dict], meta_decks: list[dict], tag: str,
          opponents, seeds: list[int], shortlist: int = SHORTLIST,
          qty: int = 5, log=print) -> ThemeReport | None:
    """Census a theme's cards, then test the best of them in pairs."""
    built = _shell_for(tag, catalog, meta_decks)
    if not built:
        log(f"combo: no legal shell for '{tag}'")
        return None
    cut = _cut_slot(built)
    if cut is None:
        return None

    pool = theme_cards(catalog, tag)
    pool = [c for c in pool if c != built.seed]
    if len(pool) < 2:
        log(f"combo: '{tag}' has too few cards to pair")
        return None

    log(f"combo: '{tag}' -- {len(pool)} cards in a "
        f"{catalog[built.seed].get('name')} shell, cutting "
        f"{catalog.get(cut, {}).get('name')}")

    singles = explore.census(h, built.cards, built.plan, opponents, pool,
                             catalog, seeds, cut=cut, qty=qty, log=log)
    if not singles:
        return None

    top = [r.card_id for r in singles[:shortlist]]
    pairs = [(a, b) for i, a in enumerate(top) for b in top[i + 1:]]
    by_id = {r.card_id: r.delta for r in singles}

    log(f"combo: testing {len(pairs)} pairs from the top {len(top)}")
    raw = explore.interactions(h, built.cards, built.plan, opponents, pairs,
                               by_id, catalog, seeds, cut=cut, qty=qty, log=log)

    combos = [Combo(a, b, na, nb, joint, add, syn)
              for (a, b, na, nb, joint, add, syn) in raw]

    return ThemeReport(tag=tag, shell_seed=built.seed, shell_screen=0.0,
                       singles=singles, combos=combos), built, cut


def confirm(h, catalog: dict[int, dict], built, cut: int, opponents,
            pairs: list[tuple[int, int]], seeds: list[int], qty: int = 5,
            log=print) -> list[Confirmed]:
    """Re-measure a handful of pairs on their own seed block, with a t.

    Every arm the contrast needs is run together -- the control, each card
    alone, and each pair -- so all four terms share seeds and the interaction
    is a genuine paired quantity rather than four independent averages
    subtracted from one another.
    """
    if not pairs:
        return []
    lim = lambda c: int(catalog.get(c, {}).get("deck_limit") or 0)
    nm = lambda c: (catalog.get(c, {}).get("name") or f"#{c}")

    arms = []
    cv = explore._variant(built.cards, built.plan, cut, explore.CONTROL_CARD,
                          qty, lim(explore.CONTROL_CARD) or qty, 2)
    if not cv:
        return []
    arms.append(("ctrl", *cv))

    singles = sorted({c for p in pairs for c in p})
    for c in singles:
        v = explore._variant(built.cards, built.plan, cut, c,
                             min(qty, lim(c)), lim(c), 2)
        if v:
            arms.append((f"s{c}", *v))
    usable = []
    for a, b in pairs:
        v = explore._variant(built.cards, built.plan, cut, a, min(qty, lim(a)), lim(a), 2)
        if not v:
            continue
        v2 = explore._variant(v[0], v[1], cut, b, min(qty, lim(b)), lim(b), 3)
        if not v2:
            continue
        arms.append((f"p{a}_{b}", *v2))
        usable.append((a, b))
    if not usable:
        return []

    log(f"combo: confirming {len(usable)} pairs over {len(seeds)} cohorts")
    res = h.evaluate(arms, opponents, seeds)
    ctrl = res["ctrl"].wins_by_seed

    import statistics
    out = []
    for a, b in usable:
        ab = res[f"p{a}_{b}"].wins_by_seed
        sa = res.get(f"s{a}")
        sb = res.get(f"s{b}")
        if not sa or not sb:
            continue
        shared = sorted(set(ab) & set(sa.wins_by_seed) & set(sb.wins_by_seed) & set(ctrl))
        if len(shared) < 3:
            continue
        # The interaction contrast, per seed: (AB - ctrl) - (A - ctrl) - (B - ctrl)
        # collapses to AB - A - B + ctrl.
        d = [ab[s] - sa.wins_by_seed[s] - sb.wins_by_seed[s] + ctrl[s] for s in shared]
        mean = statistics.fmean(d)
        sd = statistics.stdev(d) if len(d) > 1 else 0.0
        t = 0.0 if sd == 0 else mean / (sd / (len(d) ** 0.5))
        joint = statistics.fmean(ab[s] - ctrl[s] for s in shared)
        add = statistics.fmean(
            (sa.wins_by_seed[s] - ctrl[s]) + (sb.wins_by_seed[s] - ctrl[s]) for s in shared)
        out.append(Confirmed(a, b, nm(a), nm(b), mean, t, joint, add))
    out.sort(key=lambda c: -c.synergy)
    return out


# ── persistence ────────────────────────────────────────────────────────────
# Findings accumulate. The point of measuring a theme is that the next run
# does not have to measure it again, and that the generator gets a growing map
# rather than one report.

def load(path: str) -> dict:
    try:
        return json.load(open(path))
    except (FileNotFoundError, ValueError):
        return {"themes": {}, "combos": []}


def save(path: str, store: dict) -> None:
    tmp = path + ".tmp"
    with open(tmp, "w") as fh:
        json.dump(store, fh, indent=1)
    os.replace(tmp, path)


def record(store: dict, rep: ThemeReport, keep_combos: int = 8) -> dict:
    """Bank a theme. Only CONFIRMED pairs enter the shared combo list.

    Screen-ranked pairs stay attached to their theme and go no further: they
    were ordered by a statistic smaller than its own error, and letting them
    into the store would build a generator on noise.
    """
    store.setdefault("themes", {})[rep.tag] = {
        "shell_seed": rep.shell_seed,
        # Twenty, not twelve: compose() fills 75 slots from these, and a
        # 12-card list runs out and falls back to blanks.
        "singles": [[r.card_id, r.name, round(r.delta, 2)] for r in rep.singles[:20]],
        "measured_pairs": len(rep.combos),
        "confirmed": [[c.a, c.b, round(c.synergy, 2), round(c.t, 2)]
                      for c in rep.confirmed],
    }
    kept = {(c["a"], c["b"]): c for c in store.get("combos", [])}
    for c in rep.confirmed[:keep_combos]:
        if not c.real:
            continue
        kept[(c.a, c.b)] = {
            "a": c.a, "b": c.b, "a_name": c.a_name, "b_name": c.b_name,
            "tag": rep.tag, "joint": round(c.joint, 2),
            "additive": round(c.additive, 2), "synergy": round(c.synergy, 2),
            "t": round(c.t, 2),
        }
    store["combos"] = sorted(kept.values(), key=lambda c: -c["synergy"])[:200]
    return store


def next_theme(store: dict, catalog: dict[int, dict]) -> str:
    """The commonest theme not yet measured; otherwise the stalest."""
    done = set((store.get("themes") or {}).keys())
    for t in themes(catalog):
        if t not in done:
            return t
    order = themes(catalog)
    return order[0] if order else ""


def compose(store: dict, tag: str, catalog: dict[int, dict],
            meta_decks: list[dict], size: int = 100, staple_slots: int = 25):
    """Build a deck for a theme from MEASURED card value, not tag overlap.

    This is the replacement for archetype.affinity(). Instead of ranking the
    theme's cards by how many tags they share with a seed -- a lexical proxy
    that produced decks screening 4-10 of 24 -- it ranks them by what each one
    actually measured against a blank card in that theme's own shell.

    Cards that measured at or below zero are excluded outright. They are, by
    the measurement, worse than an empty slot; tag overlap could never tell
    the difference and put them in anyway.

    One honest caveat, because this project has made the mistake repeatedly:
    the deltas were measured at five copies substituted into ONE slot of the
    theme's naive shell, and they are being reused to rank cards for a
    different deck. That is a transfer, and transfers have been wrong here
    before -- a +17W card_order effect, a "-17W" cheapest swap. It is a much
    closer transfer than tag overlap (same theme, same shell family) and every
    deck built this way is screened live before it is kept, but the deltas are
    a PRIOR, not a promise.
    """
    info = (store.get("themes") or {}).get(tag) or {}
    ranked = [(int(cid), float(d)) for cid, _name, d in (info.get("singles") or [])
              if float(d) > 0 and is_playable(catalog.get(int(cid)))]
    if not ranked:
        return None

    limit = lambda c: int(catalog.get(c, {}).get("deck_limit") or 0)
    picks: list[tuple[int, int]] = []
    total = 0
    room = size - staple_slots
    for cid, _d in ranked:
        if total >= room:
            break
        q = min(limit(cid), room - total)
        if q > 0:
            picks.append((cid, q))
            total += q

    for cid in archetype.staples(meta_decks, catalog, top=10):
        if total >= size:
            break
        if any(cid == c for c, _ in picks):
            continue
        q = min(limit(cid), size - total, 15)
        if q > 0:
            picks.append((cid, q))
            total += q

    # Top up what we already chose before reaching for a blank: more copies of
    # a card that MEASURED positive beats a card that measured nothing at all.
    if total < size:
        for i, (cid, q) in enumerate(picks):
            if total >= size:
                break
            head = min(limit(cid) - q, size - total)
            if head > 0:
                picks[i] = (cid, q + head)
                total += head
    if total < size:
        picks.append((archetype.INFINITE_FILLER, size - total))
        total = size

    cards: list[int] = []
    for cid, q in picks:
        cards.extend([cid] * q)
    if len(cards) != size:
        return None

    # Play order follows the measurement too, best first -- the one part of a
    # battle plan a generator can get right for free, and worth more than any
    # other plan field.
    plan = {
        "play_priority": "card_order",
        "card_order": [c for c, _ in picks],
        "card_order_hold": False,
        "energy_hold": 0,
        "target_preference": "least_armor",
    }
    seed = picks[0][0]
    return archetype.Archetype(
        seed=seed, seed_name=f"measured {tag}",
        cards=cards, plan=plan,
        theme=[tag],
        members=sorted(((c, q) for c, q in picks), key=lambda kv: -kv[1]),
    )


def measured_archetypes(store: dict, catalog: dict[int, dict],
                        meta_decks: list[dict]) -> list:
    """One composed deck per theme that has been swept."""
    out = []
    for tag in sorted((store.get("themes") or {}).keys()):
        a = compose(store, tag, catalog, meta_decks)
        if a:
            out.append(a)
    return out
