"""Ask Claude for swaps the field does not already demonstrate.

Every other idea source here reads what the field ALREADY PLAYS: field mining
takes cards from the top 15, counter mining takes them from the decks that
trouble us, re-basing takes a whole list. Against six opponents who have all
converged on similar builds, that well is dry -- it can only ever propose
what someone is already running.

This reads `rules_text` instead, so it can propose a card nobody in the arena
plays. That is its entire job. It is not a judge: every proposal goes through
the same screen-then-validate path as any other candidate, and its stated
reasoning is never evidence. The model is a hypothesis generator pointed at a
precise question -- "we draw 73% against 1drop, what breaks that stall
without costing the other 68 matchups" -- and the validator answers it.

Cost: the card catalogue is ~45k tokens and is sent as a stable cached prefix,
so a call is roughly 2 cents on Haiku 4.5. It runs only when the deterministic
search has stalled, which is a few times a day, not every cycle.
"""
from __future__ import annotations

import json
import os
import re

MODEL = "claude-haiku-4-5"
MAX_TOKENS = 4000

SYSTEM = """You propose card swaps for AutoBattle, an autobattler where a 100-card \
deck and its standing instructions play themselves with no human input.

You are one hypothesis generator among several, and the others already mine \
what the field plays. Your value is proposing cards NOBODY in the arena \
currently runs, justified from the rules text. A suggestion that merely \
copies a top deck is worthless here.

Every proposal is measured by the real engine before anything is adopted, so \
do not hedge toward safety -- a bold wrong suggestion costs one cheap \
simulation, and a timid one costs the whole exercise. Be specific about the \
mechanism you expect.

A SYNERGY MUST BE PROPOSED AS A PACKAGE. Single swaps are measured one at a \
time, so a card that only works beside its partners will measure as worthless \
and be thrown away -- a gravecast body with nothing to fill the discard pile, \
a milled payoff with no self-mill, a Typeburst source with too few cards of \
its type. If the idea is an engine, send it as one package and it is measured \
whole. Prefer 3-6 swaps in a package: enough for the engine to function, few \
enough that the deck it came from is still doing what it did.

Answer ONLY with a JSON array, no prose around it. Single swaps look like:
[{"cut": <card_id>, "add": <card_id>, "qty": <int>, "why": "<one sentence>"}]
A package is one element of that same array:
[{"package": "<name>", "why": "<the mechanism, one sentence>",
  "swaps": [{"cut": <id>, "add": <id>, "qty": <int>}, ...]}]
Mix both freely. List a package's swaps in the order the deck should reach \
them: the adds take card_order slots from the front in the order given, and a \
payoff the deck never reaches is not an engine."""


# ── What the card text does not say ─────────────────────────────────────────
#
# WHY THIS EXISTS. The pool block hands the model `rules_text` and nothing
# else, and rules_text prints a KEYWORD without ever saying what the keyword
# does. A card reading "Gravecast /1" looks strictly worse than the same card
# without it; in fact it may be played out of the DISCARD PILE for 1, which
# turns every discard and self-mill effect in the deck from a drawback into an
# enabler. A model reading the pool literally cannot see that, so it proposed
# swaps on raw stats and never once built one of these engines.
#
# Each entry states the rule, what it combines with, and -- where there is one
# -- the card that undoes it. The tensions matter as much as the synergies:
# Recycle and Gravecast want opposite things from the discard pile, and a
# proposal that stacks both is worse than either alone.
#
# COUNTS AND EXAMPLES ARE COMPUTED FROM THE LIVE CATALOGUE, never written down
# here. A briefing with hardcoded numbers rots the first time a card is
# retired, and one that advertises a mechanic no longer in the pool is worse
# than silence -- it spends proposals on decks that cannot be built. A section
# whose detector finds nothing is dropped entirely.
#
# Every rule below was read out of shell/engine/engine.go at 4.49, not
# remembered: gravecast at playOneCard's combined hand+discard loop,
# supplement at canPaySupplement/paySupplement (v4.48), the temp-energy
# expiry and its ephemeral_persists exception at the end-of-turn clear
# (v4.40), utilize at the phase-0 refresh, and deck-out at tryDraw.

def _live(catalog: dict[int, dict]) -> list[dict]:
    return [c for c in catalog.values()
            if not c.get("is_retired") and not c.get("is_vip")]


def _rt(c: dict) -> str:
    return c.get("rules_text") or ""


def _wincon_clause(c: dict) -> str:
    """The clause that actually states the win, not the card's first clause.

    These cards carry Indestructible, Feast and cost-reduction text as well,
    and taking rules_text's first clause printed "Indestructible; Feast;
    Costs 1 less per Book you control" -- three true statements that say
    nothing about how the card wins.
    """
    for part in _rt(c).split(" \u00b7 "):
        if "win the game" in part:
            return f"{c.get('name')}: {part.strip()}"
    return f"{c.get('name')}: {_rt(c)[:70]}"


def _mechanics_block(catalog: dict[int, dict]) -> str:
    import re

    cards = _live(catalog)

    def count(pred) -> int:
        return sum(1 for c in cards if pred(c))

    def has_tag(tag):
        return lambda c: tag in (c.get("tags") or [])

    def says(pattern):
        return lambda c: re.search(pattern, _rt(c)) is not None

    out: list[str] = []

    def section(n: int, text: str) -> None:
        # Nothing in the pool, nothing to say about it.
        if n > 0:
            out.append(text.strip())

    # GRAVECAST. The example is the biggest discount currently in the pool,
    # worked out from the printed cost, because "cheaper from the discard than
    # from hand" is the whole point and is true of only about half of them.
    gc = [c for c in cards if has_tag("gravecast")(c)]
    best = ""
    pairs = []
    for c in gc:
        m = re.search(r"Gravecast\s*\u26a1?(\d+)", _rt(c))
        if m:
            pairs.append((c.get("cost", 0) - int(m.group(1)), c, int(m.group(1))))
    pairs.sort(key=lambda t: -t[0])
    if pairs and pairs[0][0] > 0:
        save, c, g = pairs[0]
        cheaper = sum(1 for d, _, _ in pairs if d > 0)
        best = (f" The discount is often large: {c.get('name')} costs "
                f"{c.get('cost')} from hand and {g} from the discard, and "
                f"{cheaper} of {len(pairs)} are cheaper from the discard.")
    section(len(gc), f"""
GRAVECAST (ability_tags carry "gravecast"; {len(gc)} cards). May be played
straight out of YOUR DISCARD PILE for the printed Gravecast cost.{best}
So discarding one is not a loss and milling your own deck is not a cost --
every "discard a card" drawback in the pool becomes an enabler, and a deck
built on these wants effects that fill its own discard pile early.
COUNTER-SYNERGY: Recycle shuffles the discard pile back into the library, so
it takes gravecast targets away. That tension is with GRAVECAST only -- see
RECYCLE below, which is the opposite for a milled payoff.""")

    # The self-mill payoff pair the user named: milling your own library is how
    # you cast these.
    milled = count(says(r"[Ww]hen this is milled"))
    selfmill = count(says(r"[Mm]ill .*your own deck"))
    section(milled, f"""
MILLED PAYOFFS ({milled} cards print "When this is milled: ..."). Milling your
OWN deck triggers them, usually playing the card for free, so they are cast
WITHOUT ever being drawn or paid for. {selfmill} cards mill your own deck to
order, which is what turns this from an accident into an engine.
THE COST IS REAL: a deck that must draw from an empty library LOSES on the
spot, so your library is a second life total and self-mill spends it. The
engine is worth it when the payoff is free casts, not when it is merely
card flow.""")

    sup = count(has_tag("supplement"))
    section(sup, f"""
SUPPLEMENT ({sup} cards, engine v4.48). An OPTIONAL extra cost: pay it when
the card is played and it does more. Unlike a normal additional cost it never
stops the card being played, so a supplement card is never dead -- but it only
pays off with ENERGY TO SPARE on the turn it lands. A deck leaning on these
wants ramp or a low curve, not both the supplement and a full hand to empty.
The standing orders decide whether to pay at all ("supplements": "off") and
how much energy to hold back ("supplement_reserve"), so a supplement deck
whose orders refuse them is paying for nothing.""")

    temp = count(says(r"Temp Energy"))
    burst = count(says(r"Typeburst"))
    banker = [c for c in cards if "Temp Energy becomes permanent" in _rt(c)]
    section(temp, f"""
TEMP ENERGY and TYPEBURST ({temp} cards grant Temp Energy; {burst} are
Typeburst). Temp Energy EXPIRES AT THE END OF THE TURN, and Typeburst energy
is further restricted to one card type -- so it is only real if the deck can
spend it the same turn on cards of that type. A Typeburst enchantment deck
with four enchantments wastes most of what it makes.
THE EXCEPTION: {len(banker)} card(s) -- {', '.join(c.get('name','?') for c in banker) or 'none currently'}
-- make your Temp Energy permanent, which banks it instead. With one of those
on board the restriction stays but the expiry does not, and burst energy
becomes a ramp engine rather than a one-turn trick.""")

    util = count(says(r"Utilize"))
    section(util, f"""
UTILIZE ({util} cards). Exhausts one of YOUR OWN permanents as a cost; it
refreshes at the start of your next turn. The cost is paid in board presence,
not cards or energy, so cheap wide boards and token swarms pay for these
almost for free, while a deck of three expensive permanents cannot afford the
same card at all. "To Play: Utilize a relic" means the card is simply
uncastable without a spare relic.""")

    wincon = [c for c in cards if "you win the game" in _rt(c)]
    section(len(wincon), f"""
ALTERNATE WIN CONDITIONS ({len(wincon)} cards). These win OUTRIGHT on a board
state rather than on damage: {'; '.join(_wincon_clause(c) for c in wincon[:4])}.
A deck that stalls forever and cannot close is exactly the deck one of these
converts into a winner, and the threshold is the deckbuilding constraint --
proposing the payoff without the cards that reach the number is proposing a
dead card.""")

    rec = count(says(r"Recycle"))
    section(rec, f"""
RECYCLE ({rec} cards). Shuffles cards from the discard pile back into the
library. It cuts two ways and which way depends on what the deck is built on:
  WITH A MILLED PAYOFF it is a LOOP. Recycle puts the payoff back into the
  library where self-mill can hit it again, and it is also the answer to
  decking out, which is what makes a self-mill deck survivable at all. Mill,
  trigger, recycle, mill again.
  WITH GRAVECAST it is a LEAK. The gravecast card wants to SIT in the discard
  pile where it can be cast from; recycling shuffles it away into the library.
A deck can run both engines, but then recycle is serving one and robbing the
other, and the quantities are the whole argument -- do not propose recycle as
generically good.""")

    if not out:
        return ""
    return ("=== MECHANICS THE CARD TEXT DOES NOT EXPLAIN ===\n"
            "The pool prints these keywords without defining them. Counts are "
            "from the live pool.\n\n" + "\n\n".join(out))


def _catalogue_block(catalog: dict[int, dict], exclude: set[int]) -> str:
    """The pool of cards we could add, as compact lines."""
    lines = []
    for cid, c in sorted(catalog.items()):
        if cid in exclude or c.get("is_retired"):
            continue
        # TOKENS ARE NOT ADDABLE. deck_limit 0 means the card cannot go in a
        # deck at all -- it only ever arrives by being summoned. 25 of them
        # were in the pool the model was asked to choose from, so a share of
        # every proposal it made was spent on cards that can never be played,
        # and `int(deck_limit or 1)` downstream turned the 0 into a 1 and
        # built the illegal deck without complaint.
        if int(c.get("deck_limit") or 0) < 1:
            continue
        rules = (c.get("rules_text") or "").strip().replace("\n", " ")
        if not rules:
            continue
        lines.append(f"{cid}|{c.get('name')}|cost {c.get('cost')}|"
                     f"{c.get('supertype')}/{c.get('subtype')}|"
                     f"limit {c.get('deck_limit')}|{rules}")
    return "\n".join(lines)


def _deck_block(counts: dict[int, int], catalog: dict[int, dict],
                order: list[int]) -> str:
    rank = {cid: i for i, cid in enumerate(order)}
    lines = []
    for cid, n in sorted(counts.items(), key=lambda kv: rank.get(kv[0], 9999)):
        c = catalog.get(cid, {})
        rules = (c.get("rules_text") or "").strip().replace("\n", " ")
        lines.append(f"{cid}|x{n}|{c.get('name')}|cost {c.get('cost')}|"
                     f"play-order {rank.get(cid, '-')}|{rules}")
    return "\n".join(lines)


def _focus_block(focus: list[dict], catalog: dict[int, dict]) -> str:
    out = []
    for f in focus:
        names = []
        for cid, n in sorted(f["counts"].items(), key=lambda kv: -kv[1])[:14]:
            names.append(f"{n}x {catalog.get(cid, {}).get('name', cid)}")
        out.append(f"- {f['name']}: we win {f['win']:.0f}%, draw {f['draw']:.0f}%, "
                   f"lose {f['loss']:.0f}%. They play: {', '.join(names)}")
    return "\n".join(out)


def extract_json(text: str) -> list[dict]:
    """Parse the model's array, tolerating fences or stray prose.

    Defensive on purpose: this is the one place a model's output touches the
    pipeline, and a parse failure must degrade to "no proposals this cycle"
    rather than raise inside an unattended timer run.
    """
    text = text.strip()
    fence = re.search(r"```(?:json)?\s*(.+?)```", text, re.S)
    if fence:
        text = fence.group(1).strip()
    start, end = text.find("["), text.rfind("]")
    if start < 0 or end <= start:
        return []
    try:
        data = json.loads(text[start:end + 1])
    except json.JSONDecodeError:
        return []
    out = []
    for row in data if isinstance(data, list) else []:
        if not isinstance(row, dict):
            continue
        # A PACKAGE: several swaps adopted and measured as one change. Kept in
        # the same array as single swaps so the model can mix them, and
        # normalised here so every consumer sees one shape.
        if isinstance(row.get("swaps"), list):
            swaps = []
            for sw in row["swaps"]:
                try:
                    swaps.append({"cut": int(sw["cut"]), "add": int(sw["add"]),
                                  "qty": max(1, int(sw.get("qty", 1)))})
                except (KeyError, TypeError, ValueError):
                    continue
            if swaps:
                out.append({"package": str(row.get("package", "engine"))[:60],
                            "swaps": swaps,
                            "why": str(row.get("why", ""))[:200]})
            continue
        try:
            out.append({"cut": int(row["cut"]), "add": int(row["add"]),
                        "qty": max(1, int(row.get("qty", 1))),
                        "why": str(row.get("why", ""))[:200]})
        except (KeyError, TypeError, ValueError):
            continue
    return out


def build_question(counts: dict[int, int], plan: dict, focus: list[dict],
                   catalog: dict[int, dict], n: int = 8) -> str:
    """The full prompt, as text -- for answering without an API key.

    The API path and this path pose exactly the same question; only the
    courier differs. A Claude session the user already pays for can answer
    the file and hand the proposals back, and they go through the identical
    screen-then-validate gauntlet. Nothing about the agent's judgement of a
    proposal depends on where the proposal came from.
    """
    pool = _catalogue_block(catalog, set(counts))
    mech = _mechanics_block(catalog)
    return (f"{SYSTEM}\n\n"
            + (f"{mech}\n\n" if mech else "")
            + 
            f"=== OUR DECK (play-order rank; lower is cast sooner) ===\n"
            f"{_deck_block(counts, catalog, plan.get('card_order') or [])}\n\n"
            f"=== THE ONLY MATCHUPS STILL COSTING US ANYTHING ===\n"
            f"(we beat 53 of 69 opponents outright)\n{_focus_block(focus, catalog)}\n\n"
            f"=== CARD POOL (id|name|cost|type|limit|rules) ===\n{pool}\n\n"
            f"=== TASK ===\n"
            f"Propose {n} changes that break those specific matchups without "
            f"giving up the ones we already win. Prefer cards no listed "
            f"opponent plays. Respect each card's limit. Keep the deck at 100 "
            f"cards: qty out must equal qty in. At least one should be a "
            f"PACKAGE building an engine from the mechanics above -- that is "
            f"the thing the field's own decks cannot teach us.")


def propose(counts: dict[int, int], plan: dict, focus: list[dict],
            catalog: dict[int, dict], n: int = 8,
            model: str = MODEL, log=print) -> list[dict]:
    """Ask for `n` swaps. Returns [] on any failure -- never raises."""
    try:
        import anthropic
    except ImportError:
        log("propose: anthropic SDK not installed, skipping")
        return []
    if not os.environ.get("ANTHROPIC_API_KEY"):
        log("propose: ANTHROPIC_API_KEY not set, skipping")
        return []

    pool = _catalogue_block(catalog, set(counts))
    mech = _mechanics_block(catalog)
    mech = (mech + "\n\n") if mech else ""
    question = (
        f"Our deck (play-order rank shown; lower is cast sooner):\n"
        f"{_deck_block(counts, catalog, plan.get('card_order') or [])}\n\n"
        f"We beat 53 of 69 opponents outright. These are the only matchups "
        f"still costing us anything:\n{_focus_block(focus, catalog)}\n\n"
        f"Propose {n} changes that break those specific matchups without "
        f"giving up the ones we already win. Prefer cards no listed opponent "
        f"plays. Respect each card's limit. Keep the deck at 100 cards: qty "
        f"out must equal qty in. At least one should be a PACKAGE building an "
        f"engine from the mechanics above -- that is the thing the field's own "
        f"decks cannot teach us."
    )

    try:
        client = anthropic.Anthropic()
        msg = client.messages.create(
            model=model,
            max_tokens=MAX_TOKENS,
            system=[
                {"type": "text", "text": SYSTEM},
                # The catalogue is identical every call, so it goes behind a
                # cache breakpoint and the volatile question comes after it.
                # The briefing rides INSIDE the cached segment with the
                # pool it is computed from: both change only when the
                # catalogue does, so teaching the model the mechanics costs
                # nothing per call after the first.
                {"type": "text",
                 "text": mech + "CARD POOL (id|name|cost|type|limit|rules):\n" + pool,
                 "cache_control": {"type": "ephemeral"}},
            ],
            messages=[{"role": "user", "content": question}],
        )
    except Exception as e:                       # network, auth, rate limit
        log(f"propose: call failed ({e.__class__.__name__}), skipping")
        return []

    text = "".join(b.text for b in msg.content if getattr(b, "type", "") == "text")
    rows = extract_json(text)
    u = msg.usage
    log(f"propose: {len(rows)} proposals | in {u.input_tokens} "
        f"(cache write {getattr(u, 'cache_creation_input_tokens', 0)}, "
        f"read {getattr(u, 'cache_read_input_tokens', 0)}) out {u.output_tokens}")
    return rows
