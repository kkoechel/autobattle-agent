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

PORTED FROM THE MAIN APP, 2026-10-29. This module used to know three metrics
-- shields_gained, life_gained, honor_gained -- and passed every other
self-side turn_tally gate as castable. So Muck Crawler ("To Play: you must
have cured at least 1 Poison this turn") read as fine in a deck with no cure
in it, which is the exact card the whole dead-card hunt started from: on
2026-10-29 the main app's nightly monitor found it again in two live agent
decks, 102186 at 1 copy and 51580 at 1 copy, after the generator had already
been wired to this check. A table that silently passes what it does not know
is worse than no table, because everything downstream reports clean.

The reference implementation is inc/deck_castability.php in the main repo,
whose tables are read from js/battle-engine.js. This is its port and must not
drift from it. Four rules keep a "dead" verdict sound, and they are the
reference's, quoted because they are what makes the answer safe to act on:

 1. Producers are OVER-counted. An effect counts wherever it appears on a
    card, even behind a wrapper that may never pay. Over-counting can only
    HIDE a dead card; it can never invent one.
 2. Only what the deck itself can do is a proof. If an opponent's card could
    open the gate, or a Chaos cohort roll could, nothing is returned.
 3. A gate this file does not understand is UNANALYZED, never passing --
    that is the fault above, and it must not come back.
 4. The energy cost is not a proof. REACHABLE_ENERGY is a judgement and lives
    behind proven_only.

WHAT IS NOT PORTED, so a clean result here is not a clean result there:
the "must utilize/sacrifice a <supertype>" target proof, which needs
supertype words, token copies, reanimation and the coinlink token. The main
app finds those; this does not. Run the main app's
`php shell/deck_dead_cards.php --deck=ID` when the answer has to be complete.
"""
from __future__ import annotations

import json

# Energy a long game plausibly reaches, for the cost-floor test.
#
# NOTE WHAT KIND OF NUMBER THIS IS. Energy is +1 a turn and a match runs to
# 600, so 33 energy is reachable by a deck that simply never spends -- this
# constant is a judgement about what a deck PLAUSIBLY reaches, not a proof
# that it cannot. It is therefore the one test in this module that breaks the
# promise at the top of the file, and callers who ACT on a verdict (cutting
# the card) must pass proven_only=True to leave it out. Generation-time
# filtering may use it: preferring not to draft a 33-cost card is cheap and
# reversible, where cutting one from a finished deck is neither.
REACHABLE_ENERGY = 25

# mkPlayer(): life starts at STARTING_LIFE (100); honor and shields at 0.
START = {"life": 100, "honor": 0, "shields": 0, "poison": 0}

# Metrics every turn produces with no card's help -- tryDraw() tallies the
# turn's own draw, playOneCard() tallies cards_played, spendEnergy() tallies
# energy_spent. A gate on one of these is a question of amount and sequencing,
# never of a missing producer, so it is never dead.
BASELINE_METRICS = frozenset({"cards_drawn", "cards_played", "energy_spent"})

# What tallies a metric for the CONTROLLER. Combat links are absent on
# purpose: combat is turn step 6c, the play phase is step 5, and both players'
# tallies reset at the next turn start -- so nothing in combat can ever open a
# "this turn" gate.
SELF_TALLY: dict[str, dict] = {
    "life_gained":     {"effects": ("gain_life",)},
    "honor_gained":    {"effects": ("gain_honor",)},
    "honor_lost":      {"effects": ("lose_honor", "pay_honor_and"), "costs": ("honor",)},
    "shields_gained":  {"effects": ("gain_shield",)},
    "poison_gained":   {"effects": ("gain_poison",)},
    # The ONLY tally site is remove_poison, and it counts what it REMOVED --
    # Math.min(amount, your poison) -- so a cure with no poison on you opens
    # nothing. Turn-start decay is a bare poison-- with no tally at all, which
    # is why a deck cannot wait the gate out. Hence requires_stat.
    "poison_cured":    {"effects": ("remove_poison",), "requires_stat": "poison"},
    "cards_recycled":  {"effects": ("shuffle_discard_to_library", "recycle_self", "recycle_and"),
                        "costs": ("recycle",), "passives": ("reshuffle",)},
    "cards_milled":    {"effects": ("mill_self",)},
    "cards_exiled":    {"effects": ("exile_self_deck", "exile_self_hand", "exile_self_discard",
                                    "exile_discard_and", "exile_tagged", "exile_deck_and_manifest"),
                        "costs": ("exile_discard",), "passives": ("gravecast",)},
    "cards_discarded": {"effects": ("discard_self", "discard_and"), "costs": ("discard",)},
    # gain_poison belongs here too: poison ticks through dmgPlayer() at the
    # start of your NEXT turn, before you play.
    "life_lost":       {"effects": ("deal_damage_to_self", "pay_life_and", "gain_poison"),
                        "costs": ("life",)},
    # toDiscardOrExile() tallies every permanent of yours that leaves play.
    "permanents_died": {"effects": ("sacrifice_and", "sacrifice_own_permanent", "destroy_self",
                                    "destroy_tagged", "destroy_random_tagged", "destroy_target",
                                    "kill_target", "destroy_permanents_by_cost",
                                    "deal_damage_to_permanents_by_cost",
                                    "deal_damage_to_random_creature", "deal_damage_to_random_tagged",
                                    "deal_damage_to_tagged", "deal_damage_to_weakest_creature",
                                    "deal_damage_to_own_random_creature", "adjust_health"),
                        # v4.52: a Saga is sacrificed after its last chapter (a
                        # looping one never is -- over-counted on purpose).
                        "costs": ("sacrifice",), "passives": ("saga",)},
}

# What hands a metric to the controller's OPPONENT. Read here in one
# direction only: these are the ways an OPPONENT's card could open a self-side
# gate for you, which makes the gate unjudgeable rather than dead. Metrics
# absent from this table -- poison_cured, honor_gained, shields_gained -- have
# no effect anywhere that gives them to the other player, which is why a
# missing cure is a proof and a missing self-poison is not.
OPP_TALLY: dict[str, tuple[str, ...]] = {
    "life_gained":     ("opponent_gain_life",),
    "poison_gained":   ("poison_opponent",),
    "cards_milled":    ("mill_opponent",),
    "cards_exiled":    ("exile_opponent_deck", "exile_opponent_hand", "exile_opponent_discard",
                        "exile_tagged", "exile_deck_and_manifest"),
    "cards_recycled":  ("recycle_opponent",),
    "cards_discarded": ("discard_opponent",),
    "honor_lost":      ("reduce_opponent_honor",),
    "life_lost":       ("deal_damage_to_player", "poison_opponent"),
    "permanents_died": ("destroy_tagged", "destroy_random_tagged", "destroy_target", "kill_target",
                        "destroy_permanents_by_cost", "deal_damage_to_permanents_by_cost",
                        "deal_damage_to_random_creature", "deal_damage_to_random_tagged",
                        "deal_damage_to_tagged", "deal_damage_to_weakest_creature", "adjust_health"),
    "cards_drawn":     ("opponent_draw_card",),
}

# Persistent stats -- a Life/Honor/Shields amount rather than a per-turn
# tally. Any gain at ANY time counts, combat links included, because the stat
# carries into the turn the card is played.
STAT_GAIN: dict[str, dict] = {
    "life":    {"effects": ("gain_life",),   "passives": ("lifelink",),   "opp": ("opponent_gain_life",)},
    "honor":   {"effects": ("gain_honor",),  "passives": ("honorlink",),  "opp": (), "champion_melee": True},
    "shields": {"effects": ("gain_shield",), "passives": ("shieldlink",), "opp": ()},
    "poison":  {"effects": ("gain_poison",), "passives": (),              "opp": ("poison_opponent",)},
}

# Chaos Arena turn-start effects that feed a metric -- CHAOS_TURN_START_EFFECTS
# in the engine. Two are rolled per cohort, so one of these opens a gate in
# SOME cohorts only: never "dead", never "fine".
CHAOS_EFFECTS: dict[str, str] = {
    "poison_tick": "gain_poison",            "mill_tick": "mill_self",
    "self_creature_damage": "deal_damage_to_own_random_creature",
    "recycle_tick": "recycle_and",           "player_damage_3": "deal_damage_to_self",
    "honor_loss_1": "lose_honor",            "discard_tick": "discard_self",
    "sacrifice_creature": "sacrifice_own_permanent",
    "sacrifice_relic": "sacrifice_own_permanent", "life_gain_3": "gain_life",
}

# Trigger events that fire after the play phase: no use to a "this turn" gate.
POST_PLAY_EVENTS = frozenset({"end_of_turn", "after_combat"})
# Events that can fire during the OTHER player's turn; passives can too.
REACTIVE_EVENTS = frozenset({"on_death", "on_leave", "on_discard", "each_opponent_turn"})

# Keys of effects_json this module reads by name. Anything else at the top
# level is still walked, under rule 1.
_KNOWN_SECTIONS = frozenset({"triggers", "passives", "play_requires", "additional_cost", "supplement"})


def _effects(card: dict) -> dict:
    e = card.get("effects_json")
    if isinstance(e, str):
        try:
            e = json.loads(e)
        except ValueError:
            return {}
    return e if isinstance(e, dict) else {}


def _walk(node, when: str, f: dict) -> None:
    """Record every effect type on a card, bucketed by WHEN it can fire.

    'pre' is "can happen before or during your play phase", 'any' is ever,
    'reactive' is "can fire on the other player's turn". Every child key is
    walked, not only effect/then/else: a producer under a key this did not
    know would read as MISSING, and a caller cuts cards on that verdict.
    """
    if isinstance(node, list):
        for x in node:
            _walk(x, when, f)
        return
    if not isinstance(node, dict):
        return
    t = node.get("type")
    if isinstance(t, str) and t:
        f["any"].add(t)
        if when != "post":
            f["pre"].add(t)
        if when == "reactive":
            f["reactive"].add(t)
        if t == "create_token_copy":
            f["tokens"].append((int(node.get("card_id") or 0), (node.get("side") or "") == "opponent"))
        if t == "play_self":
            f["play_self"] = True
    for v in node.values():
        if isinstance(v, (dict, list)):
            _walk(v, when, f)


def _facts(cid: int, card: dict) -> dict:
    e = _effects(card)
    f = {
        "id": cid,
        "name": str(card.get("name") or cid),
        "pre": set(), "any": set(), "reactive": set(), "passives": set(),
        "tokens": [], "play_self": False,
        "gates": e.get("play_requires") if isinstance(e.get("play_requires"), list) else [],
        "cost": e.get("additional_cost") if isinstance(e.get("additional_cost"), dict) else {},
    }
    triggers = e.get("triggers")
    for tr in triggers if isinstance(triggers, list) else []:
        if not isinstance(tr, dict):
            continue
        ev = str(tr.get("event") or "")
        when = "post" if ev in POST_PLAY_EVENTS else ("reactive" if ev in REACTIVE_EVENTS else "pre")
        _walk(tr.get("effect"), when, f)
    passives = e.get("passives")
    for p in passives if isinstance(passives, list) else []:
        if not isinstance(p, dict):
            continue
        if isinstance(p.get("type"), str):
            f["passives"].add(p["type"])
        for v in p.values():
            if isinstance(v, (dict, list)):
                _walk(v, "reactive", f)
    if isinstance(f["cost"].get("effect"), (dict, list)):
        _walk(f["cost"]["effect"], "pre", f)
    sup = e.get("supplement")
    if isinstance(sup, list):
        _walk([s.get("effect") for s in sup if isinstance(s, dict)], "pre", f)
    elif isinstance(sup, dict):
        _walk(sup.get("effect"), "pre", f)
    for k, v in e.items():
        if k not in _KNOWN_SECTIONS and isinstance(v, (dict, list)):
            _walk(v, "pre", f)
    return f


# Catalogue facts, built once and reused.
#
# WHY A STRONG REFERENCE IS HELD. The key is id(catalog), and an id can be
# reused once the object it named is freed -- which would hand a second
# catalogue the first one's verdicts. Keeping the catalogue itself in the
# entry makes that impossible: while the entry lives the object lives, so its
# id cannot be handed to anything else. Two entries is enough for the one
# catalogue a run uses, and the cache is dropped when it grows past that.
_CACHE: dict[int, tuple[dict, dict]] = {}


def catalogue(catalog: dict[int, dict]) -> dict:
    """Facts for every card, plus which routes an OPPONENT could take."""
    key = id(catalog)
    hit = _CACHE.get(key)
    if hit is not None and hit[0] is catalog:
        return hit[1]

    facts = {int(cid): _facts(int(cid), c) for cid, c in catalog.items() if isinstance(c, dict)}
    # During YOUR turn an opponent's card acts only through a passive or an
    # on_death/on_leave/on_discard trigger, so a self-side gate can only be
    # opened for you from the 'reactive' bucket. A persistent stat carries
    # over, so for those any time counts.
    opp_tally = {m for m, types in OPP_TALLY.items()
                 if any(f["reactive"] & set(types) for f in facts.values())}
    opp_stat = {s for s, spec in STAT_GAIN.items()
                if spec["opp"] and any(f["any"] & set(spec["opp"]) for f in facts.values())}
    cat = {"facts": facts, "opp_tally": opp_tally, "opp_stat": opp_stat}

    if len(_CACHE) >= 2:
        _CACHE.clear()
    _CACHE[key] = (catalog, cat)
    return cat


def _produces(cards: list[dict], spec: dict, when: str) -> str | None:
    """The name of a card in `cards` that drives what `spec` lists, or None."""
    for f in cards:
        if f[when] & set(spec.get("effects", ())):
            return f["name"]
        for k in spec.get("costs", ()):
            if f["cost"].get(k):
                return f["name"]
        if f["passives"] & set(spec.get("passives", ())):
            return f["name"]
    return None


def _chaos_can(spec: dict) -> bool:
    """Could a Chaos cohort roll drive this?"""
    return any(t in spec.get("effects", ()) for t in CHAOS_EFFECTS.values())


def _needs_some(op: str, amount: float) -> bool:
    """Does this comparison need anything to HAPPEN?

    Only "at least / more than" can; "at most 0" is satisfied by doing
    nothing, so it is never evidence that a card is stuck.
    """
    return (op == "gte" and amount > 0) or (op == "gt" and amount >= 0) or (op == "eq" and amount > 0)


def dead_reason(cid: int, deck_ids, catalog: dict,
                proven_only: bool = False, chaos: bool = True,
                champion_melee: bool = False, cat: dict | None = None) -> str | None:
    """Why this card can never be played in this deck, or None.

    proven_only drops the cost-floor test, which is a plausibility judgement
    rather than a proof -- see REACHABLE_ENERGY. Pass it whenever the verdict
    will be acted on rather than merely preferred.

    chaos=True, the default, means "assume this deck may play in the Chaos
    arena", where a cohort roll can poison, mill, discard or sacrifice for
    you. It is the conservative setting: it can only withhold a verdict. Pass
    chaos=False when the arena is known not to be Chaos and the stronger
    answer is wanted.

    THE FLAG IS INERT TODAY, and knowing that is the point of saying so.
    Measured against the tables above on 2026-10-29: every metric a Chaos
    roll can drive also has an OPPONENT route, which already withholds the
    verdict, so no value of chaos changes any answer. It is kept because
    these tables are data read from the engine and either list can move --
    drop an opponent route and the flag starts deciding -- but nobody should
    reason as though passing chaos=False buys a stronger answer right now.
    """
    cat = cat if cat is not None else catalogue(catalog)
    facts = cat["facts"]
    me = facts.get(cid)
    if me is None:
        return None

    # Judged against the REST of the deck. A card cannot open its own gate:
    # the gate is checked before it is played, so its own on-enter effect has
    # not happened yet.
    others = [facts[c] for c in dict.fromkeys(deck_ids) if c != cid and c in facts]

    for g in me["gates"]:
        if not isinstance(g, dict):
            continue
        kind = str(g.get("kind") or "")
        side = "opponent" if g.get("side") == "opponent" else "self"
        op = str(g.get("op") or "gte")
        try:
            amount = float(g.get("amount") or 0)
        except (TypeError, ValueError):
            continue
        if not _needs_some(op, amount):
            continue

        if kind == "turn_tally":
            m = str(g.get("metric") or "")
            # Not ours to judge, or produced by simply taking a turn, or a
            # metric this module does not know -- rule 3, never "passing".
            if side == "opponent" or m in BASELINE_METRICS or m not in SELF_TALLY:
                continue
            spec = SELF_TALLY[m]
            if _produces(others, spec, "pre") is not None:
                need = spec.get("requires_stat")
                if need is None or _produces(others, STAT_GAIN[need], "any") is not None:
                    continue
                # A producer that only counts what is there to take -- a cure
                # needs poison ON you -- opens the gate only if something
                # supplies it. 2026-10-27: Quarantine Barricade "cured" for
                # Miracle Recovery in a deck that never poisons itself.
                if chaos and _chaos_can(STAT_GAIN[need]):
                    continue
                if need in cat["opp_stat"]:
                    continue
                return f"needs self {m}, but nothing can ever give you {need} to remove"
            if chaos and _chaos_can(spec):
                continue
            if m in cat["opp_tally"]:
                continue
            return f"needs self {m}"

        if kind == "stat":
            s = str(g.get("stat") or "")
            if side == "opponent" or op not in ("gte", "gt") or s not in STAT_GAIN:
                continue
            start = START[s]
            already_true = start >= amount if op == "gte" else start > amount
            if already_true:
                continue        # holds in the opening state, before anything happens
            spec = STAT_GAIN[s]
            if _produces(others, spec, "any") is not None:
                continue
            if spec.get("champion_melee") and champion_melee:
                continue
            if chaos and _chaos_can(spec):
                continue
            if s in cat["opp_stat"]:
                continue
            return f"needs self {s} {op} {amount:g} (starts at {start}, nothing raises it)"

    for s in ("honor", "shields"):
        owed = me["cost"].get(s) or 0
        if not owed:
            continue
        spec = STAT_GAIN[s]
        if _produces(others, spec, "any") is not None:
            continue
        if spec.get("champion_melee") and champion_melee:
            continue
        if chaos and _chaos_can(spec):
            continue
        if s in cat["opp_stat"]:
            continue
        return f"costs {owed} {s} to play and the deck gains none"

    if not proven_only:
        floor = (_effects(catalog.get(cid, {})).get("cost_scaling") or {}).get("minimum")
        if isinstance(floor, int) and floor > REACHABLE_ENERGY:
            return f"costs at least {floor} energy"
    return None


def dead_cards(deck_ids, catalog: dict, proven_only: bool = False,
               chaos: bool = True, champion_melee: bool = False) -> dict[int, str]:
    """Every provably unplayable card in this deck, with its reason.

    Iterated to a fixpoint, because a card whose only enabler is itself dead
    is dead too. Each round judges every card against the SAME surviving set
    and removes that round's dead together: removing one mid-round would let a
    card die of an enabler that died earlier in the same round, which is a
    different (and weaker) claim than the one this makes.
    """
    cat = catalogue(catalog)
    facts = cat["facts"]
    deck = [c for c in dict.fromkeys(deck_ids) if c in facts]
    alive = list(deck)
    out: dict[int, str] = {}
    for _ in range(10):
        died = []
        for c in alive:
            why = dead_reason(c, alive, catalog, proven_only=proven_only, chaos=chaos,
                              champion_melee=champion_melee, cat=cat)
            if why:
                died.append(c)
                out[c] = why
        if not died:
            break
        alive = [c for c in alive if c not in died]

    # A card that can reach the board WITHOUT being cast is not dead weight,
    # whatever its gate says: play_self skips the gate outright, and a token
    # copy of it enters without anyone casting anything.
    enters_anyway = {t for c in deck for (t, opp) in facts[c]["tokens"] if not opp}
    return {c: why for c, why in out.items()
            if not (facts[c]["play_self"] or c in enters_anyway)}
