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

Answer ONLY with a JSON array, no prose around it:
[{"cut": <card_id>, "add": <card_id>, "qty": <int>, "why": "<one sentence>"}]"""


def _catalogue_block(catalog: dict[int, dict], exclude: set[int]) -> str:
    """The pool of cards we could add, as compact lines."""
    lines = []
    for cid, c in sorted(catalog.items()):
        if cid in exclude or c.get("is_retired"):
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
    return (f"{SYSTEM}\n\n"
            f"=== OUR DECK (play-order rank; lower is cast sooner) ===\n"
            f"{_deck_block(counts, catalog, plan.get('card_order') or [])}\n\n"
            f"=== THE ONLY MATCHUPS STILL COSTING US ANYTHING ===\n"
            f"(we beat 53 of 69 opponents outright)\n{_focus_block(focus, catalog)}\n\n"
            f"=== CARD POOL (id|name|cost|type|limit|rules) ===\n{pool}\n\n"
            f"=== TASK ===\n"
            f"Propose {n} swaps that break those specific matchups without "
            f"giving up the ones we already win. Prefer cards no listed "
            f"opponent plays. Respect each card's limit. Keep the deck at 100 "
            f"cards: qty out must equal qty in.")


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
    question = (
        f"Our deck (play-order rank shown; lower is cast sooner):\n"
        f"{_deck_block(counts, catalog, plan.get('card_order') or [])}\n\n"
        f"We beat 53 of 69 opponents outright. These are the only matchups "
        f"still costing us anything:\n{_focus_block(focus, catalog)}\n\n"
        f"Propose {n} swaps that break those specific matchups without "
        f"giving up the ones we already win. Prefer cards no listed opponent "
        f"plays. Respect each card's limit. Keep the deck at 100 cards: qty "
        f"out must equal qty in."
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
                {"type": "text", "text": "CARD POOL (id|name|cost|type|limit|rules):\n" + pool,
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
