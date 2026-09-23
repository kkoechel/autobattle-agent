"""abagent — offline deck/plan optimisation against the live AutoBattle meta."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import sys
import time
import urllib.request

from .api import Api, ApiError
from . import history, second
from .harness import Harness, opponents_from_meta
from .plans import card_order_from_deck, describe
from .search import climb, counter_round, optimise

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VAR = os.path.join(ROOT, "var")
BIN = os.path.join(ROOT, "bin")
VALIDATOR = os.path.join(BIN, "validate_linux_amd64")


def _p(name: str) -> str:
    return os.path.join(VAR, name)


def _load_or(name: str, default=None):
    try:
        return _load(name)
    except (FileNotFoundError, ValueError):
        return {} if default is None else default


def _load(name: str):
    with open(_p(name)) as fh:
        return json.load(fh)


def _save(name: str, obj) -> None:
    os.makedirs(VAR, exist_ok=True)
    tmp = _p(name + ".tmp")
    with open(tmp, "w") as fh:
        json.dump(obj, fh)
    os.replace(tmp, _p(name))


def expand(deck: dict) -> list[int]:
    """deck_cards rows -> the engine's flat card list, one entry per copy."""
    out: list[int] = []
    for c in deck["cards"]:
        out.extend([int(c["card_id"])] * int(c["quantity"]))
    return out


# --- commands ------------------------------------------------------------

def check_api(api: Api) -> None:
    """Notice when the server's API changes under us.

    Worth doing every cycle rather than never: the register endpoint gained an
    `arena` parameter in 1.9.0 that removed an entire class of workaround from
    this client, and nothing would have told us. New paths are reported by
    name so a capability we should be using does not sit unnoticed.
    """
    prev = _load_or("api_seen.json")
    try:
        doc, etag = api.spec(prev.get("etag"))
    except Exception as e:                      # never block a cycle on this
        print(f"api: version check failed ({e.__class__.__name__}), continuing")
        return
    if doc is None:                             # 304, nothing changed
        return
    version, paths = str(doc["info"]["version"]), set(doc["paths"])

    if prev.get("version") != version:
        print(f"api: version {prev.get('version', '(first run)')} -> {version}")
    added = paths - set(prev.get("paths") or [])
    removed = set(prev.get("paths") or []) - paths
    if prev and added:
        print(f"api: NEW endpoints: {', '.join(sorted(added))}")
    if prev and removed:
        print(f"api: REMOVED endpoints: {', '.join(sorted(removed))}")
    _save("api_seen.json", {"version": version, "paths": sorted(paths),
                            "etag": etag})


def cmd_fetch(args, api: Api) -> int:
    check_api(api)
    os.makedirs(BIN, exist_ok=True)
    vv = api.validator_version()
    want = vv["go"]["version"]
    have = None
    if os.path.exists(VALIDATOR):
        import subprocess
        have = subprocess.run([VALIDATOR, "-version"], capture_output=True,
                              text=True).stdout.strip().split()[-1].lstrip("v")
    if have != want:
        url = vv["go"]["urls"]["linux_amd64"]
        print(f"validator {have or 'absent'} -> v{want}, downloading {url}")
        with urllib.request.urlopen(url, timeout=180) as r:
            blob = r.read()
        with urllib.request.urlopen(url + ".sha256", timeout=60) as r:
            want_sum = r.read().decode().split()[0]
        got = hashlib.sha256(blob).hexdigest()
        if got != want_sum:
            print(f"FATAL sha256 mismatch: {got} != {want_sum}", file=sys.stderr)
            return 1
        with open(VALIDATOR, "wb") as fh:
            fh.write(blob)
        os.chmod(VALIDATOR, 0o755)
        print(f"validator v{want} verified ({len(blob)} bytes)")
    else:
        print(f"validator v{have} already current")

    # The FULL catalog: fields=slim/rules drop effects_json and stats_json,
    # which the engine needs, and playable=1 drops token cards -- a deck that
    # creates tokens silently no-ops if the token definitions are missing.
    cards = api.cards()
    _save("cards.json", cards)
    print(f"cards: {len(cards)} (full catalog, tokens included)")

    meta = api.meta(args.arena)
    _save(f"meta_{args.arena}.json", meta)
    print(f"meta {args.arena}: {len(meta['decks'])} decks from cohort "
          f"{meta['cohort_id']} finalized {meta['finalized_at']}")
    return 0


def card_info_map(catalog: list[dict]) -> dict[int, dict]:
    return {int(c["id"]): {"subtype": c.get("subtype"),
                           "supertype": c.get("supertype"),
                           "tags": c.get("tags") or []} for c in catalog}


def _setup(args, api: Api):
    cards_cat = _load("cards.json")
    meta = _load(f"meta_{args.arena}.json")
    deck = api.deck(args.deck_id)
    mine = expand(deck)
    opps = opponents_from_meta(meta, exclude_deck_ids={args.deck_id})
    h = Harness(VALIDATOR, cards_cat, workdir=VAR)
    return h, deck, mine, opps, card_info_map(cards_cat)


def cmd_baseline(args, api: Api) -> int:
    h, deck, mine, opps, _info = _setup(args, api)
    plan = deck.get("battle_plan") or {}
    print(f"deck {args.deck_id} '{deck['name']}' — {len(mine)} cards, "
          f"{len(set(mine))} distinct")
    print(f"plan: {describe(plan)}")
    print(f"field: {len(opps)} opponents from {args.arena}")
    seeds = [1_000_000 + i for i in range(args.seeds)]
    t = time.time()
    s = h.evaluate([("me", mine, plan)], opps, seeds)["me"]
    print(f"\n{s}")
    print(f"({len(opps) * args.seeds} matches in {time.time() - t:.1f}s)")
    return 0


def cmd_climb(args, api: Api) -> int:
    h, deck, mine, opps, info = _setup(args, api)
    start = dict(deck.get("battle_plan") or {})
    if args.seed_card_order and not start.get("card_order"):
        start["card_order"] = card_order_from_deck(mine)
    t = time.time()
    plan, score, hist = climb(h, mine, opps, start, card_info=info,
                              sweep_seeds=args.sweep_seeds,
                              confirm_seeds=args.confirm_seeds,
                              max_sweeps=args.sweeps, min_t=args.min_t,
                              confirm_top=args.confirm_top,
                              rng=random.Random(args.rng))
    print(f"\n{sum(1 for s in hist if s.confirmed)} confirmed of {len(hist)} "
          f"challengers, {time.time() - t:.0f}s")
    _save(f"plan_{args.deck_id}.json", plan)
    print(f"wrote var/plan_{args.deck_id}.json")
    if args.apply:
        api.update_deck(args.deck_id, battle_plan=plan)
        print(f"applied to deck {args.deck_id}")
        if args.register:
            r = api.register(args.deck_id)
            print(f"registered: {json.dumps(r)[:200]}")
    else:
        print("(not applied — pass --apply to write it to the deck)")
    return 0


def best_rebase(h, api, mine, plan, store, opps, seeds, top_n=12, window=24, log=print):
    """The strongest recent deck that also beats ours, measured head to head.

    Candidates come from a WINDOWED record, not one cohort's rank and not
    all-time. One cohort is a coin flip -- we adopted a deck on a single #1
    finish whose all-time mean rank was 14. All-time is no better, because
    deck_id survives a rewrite: that same deck stepped from ~33 wins to ~61
    inside one cohort when its owner rebuilt it.

    The net is deliberately wide (12, not 4). The top decks sit within about
    two wins of each other, which is well inside a single cohort's noise, so a
    deck sitting 8th on mean rank is not meaningfully worse than one sitting
    3rd -- and it is far more likely to be a DIFFERENT archetype, which is the
    entire point of re-basing. A narrow net just fetches another copy of the
    basin we are already in.

    Every candidate is scored in ONE batch against a common field with all
    candidates and ourselves removed, so nobody is credited for beating a copy
    of itself and every comparison sees identical opposition.
    """
    recs = history.deck_records(store, min_appearances=3, window=window)[:top_n]
    cands = []
    for rec in recs:
        try:
            pub = api.public_deck(rec.deck_id)
        except Exception:
            continue
        ids = pub.get("card_ids") or []
        if not ids:
            for c in pub.get("cards") or []:
                ids.extend([int(c["card_id"])] * int(c["quantity"]))
        if len(ids) >= 90:
            cands.append((rec, ids, pub.get("battle_plan") or {}))
    if not cands:
        return None

    drop = {r.deck_id for r, _, _ in cands}
    field = [o for o in opps if o.deck_id not in drop]
    log(f"rebase: {len(cands)} candidates from the last {window} cohorts, "
        f"{len(field)} common opponents")

    batch = [("ours", mine, plan)]
    batch += [(f"c{i}", ids, bp) for i, (_, ids, bp) in enumerate(cands)]
    res = h.evaluate(batch, field, seeds)
    ours = res["ours"]

    best = None
    for i, (rec, ids, bp) in enumerate(cands):
        sc = res[f"c{i}"]
        gain = sc.wins - ours.wins
        if sc.key > ours.key and (best is None or gain > best[1]):
            best = ({"deck_id": rec.deck_id, "deck_name": rec.name,
                     "rank": round(rec.mean_rank), "cards": ids,
                     "battle_plan": bp}, gain)
    if best:
        log(f"rebase: best is {best[0]['deck_name'][:24]} "
            f"(mean rank {best[0]['rank']}) at {best[1]:+.1f}W")
    return best


def cmd_adopt(args, api: Api) -> int:
    """Re-base our deck onto a list from the field, measured first.

    Hill climbing cannot cross a valley. Our deck sits ~19 wins behind the top
    of the field and the best single swap found so far is worth +1.5, so the
    incremental path stalls in its own basin long before #1. Every deck is
    public -- GET /meta returns each finalized cohort deck's full card list
    and battle plan -- so the cheap move is to start from a basin that is
    already good and search from there.

    Nothing is adopted unadopted-and-unmeasured: the candidate and the
    incumbent are scored head to head on shared seeds first, and a list that
    does not actually beat ours is refused however well it placed. A cohort
    rank is one seed per pairing; our own measurement is not.
    """
    h, deck, mine, opps, _info = _setup(args, api)
    meta = _load(f"meta_{args.arena}.json")
    src = next((d for d in meta["decks"] if d["rank"] == args.from_rank), None)
    if not src:
        print(f"no deck at rank {args.from_rank} in the cached {args.arena} meta")
        return 1

    # Score both against the same field, excluding the candidate itself so it
    # is not credited for beating a copy of itself.
    field = [o for o in opps if o.deck_id != src.get("deck_id")]
    seeds = [660000 + i for i in range(args.seeds)]
    plan = src.get("battle_plan") or {}
    res = h.evaluate([("ours", mine, deck.get("battle_plan") or {}),
                      ("theirs", src["cards"], plan)], field, seeds)
    ours, theirs = res["ours"], res["theirs"]
    print(f"ours            {ours}")
    print(f"rank {src['rank']} {src['deck_name'][:18]:18} {theirs}")

    if theirs.key <= ours.key:
        print(f"refusing: rank {src['rank']} does not beat our deck on {args.seeds} "
              f"shared seeds ({theirs.wins - ours.wins:+.1f}W)")
        return 0
    if not args.apply:
        print(f"would adopt ({theirs.wins - ours.wins:+.1f}W) — pass --apply")
        return 0

    counts: dict[int, int] = {}
    for cid in src["cards"]:
        counts[cid] = counts.get(cid, 0) + 1
    api.update_deck(args.deck_id,
                    cards=[{"card_id": c, "quantity": q} for c, q in sorted(counts.items())],
                    battle_plan=plan)
    print(f"adopted rank {src['rank']} '{src['deck_name']}' into deck "
          f"{args.deck_id}: {sum(counts.values())} cards, {len(counts)} distinct, "
          f"{theirs.wins - ours.wins:+.1f}W")
    print("the cycle will iterate from here")
    return 0


CKPT_PREFIX = "abagent ckpt"


def checkpoint(api: Api, cards: list[int], plan: dict, label: str,
               keep: int = 4, log=print) -> int | None:
    """Save the current list as its own deck before something changes it.

    Every gain here is measured offline against a snapshot of the field, and
    the live cohort is the thing that actually decides. A re-base or an
    accepted swap can therefore look right and still be worse in play, and
    without a copy the previous list is simply gone -- PUT /decks/{id}
    overwrites in place and keeps no history.

    Slots are finite (17 by default, from user_deck_limit), so the oldest
    checkpoints are pruned rather than accumulated. Only decks this agent
    named are ever deleted.
    """
    # Prune first. The v1 API does not enforce the 17-slot limit today
    # (route_decks_post validates the name and inserts, with no slot check),
    # but it is being added -- and once it is, creating at the cap fails and
    # a prune that runs afterwards never runs at all.
    _prune_checkpoints(api, keep - 1, log)

    try:
        counts: dict[int, int] = {}
        for cid in cards:
            counts[cid] = counts.get(cid, 0) + 1
        made = api.create_deck(f"{CKPT_PREFIX} {label}"[:80])
        did = int(made["deck"]["id"] if "deck" in made else made["id"])
        api.update_deck(did, cards=[{"card_id": c, "quantity": q}
                                    for c, q in sorted(counts.items())],
                        battle_plan=plan)
        log(f"checkpoint: saved deck {did} '{CKPT_PREFIX} {label}'")
    except ApiError as e:
        log(f"checkpoint: could not save ({e}) — continuing unsaved")
        return None

    return did


def _prune_checkpoints(api: Api, keep: int, log=print) -> None:
    """Keep the newest `keep` agent checkpoints, delete the rest.

    Self-imposed rather than required: the website's user_deck_limit (17 plus
    purchased slots) lives in api/decks.php and the v1 path never consults it.
    But an account past that limit is awkward in the web UI -- api/decks.php
    carries a comment about the API and the UI having disagreed before, and
    "a 100th deck the UI insisted was over the limit" is exactly the mess this
    would recreate. Only decks this agent named are ever deleted.
    """
    try:
        mine = [d for d in api.decks()
                if str(d.get("name", "")).startswith(CKPT_PREFIX)]
        mine.sort(key=lambda d: d.get("updated_at") or "", reverse=True)
        for old in mine[max(0, keep):]:
            api.delete_deck(int(old["id"]))
            log(f"checkpoint: pruned {old['id']} '{old['name']}'")
    except ApiError as e:
        log(f"checkpoint: prune skipped ({e})")


def cmd_ask(args, api: Api) -> int:
    """Write the proposal question to a file, or validate answers from one.

    Two halves of the same loop, usable with no Anthropic key at all:
      abagent ask            -> var/question.txt, hand it to any Claude session
      abagent ask --answers  -> read proposals back, measure them, apply a winner

    The measurement is identical either way. A proposal is a hypothesis
    whatever produced it, and the engine is what decides.
    """
    from . import propose as P
    from .moves import as_counter, swap, swap_ranks
    from .search import sweep_and_confirm

    h, deck, mine, opps, _info = _setup(args, api)
    catalog = {int(c["id"]): c for c in _load("cards.json")}
    plan = deck.get("battle_plan") or {}
    counts = dict(as_counter(mine))

    if not args.answers:
        seeds = [330000 + i for i in range(args.seeds)]
        sc = h.evaluate([("me", mine, plan)], opps, seeds)["me"]
        by = {o.slot_id: o for o in opps}
        focus = []
        for sid, (w, l, d) in sc.per_opponent.items():
            n = max(1, w + l + d)
            if w / n >= 0.98:
                continue
            focus.append({"name": by[sid].name, "win": 100 * w / n,
                          "draw": 100 * d / n, "loss": 100 * l / n,
                          "counts": dict(as_counter(by[sid].cards))})
        focus.sort(key=lambda f: -(f["loss"] + 0.5 * f["draw"]))
        text = P.build_question(counts, plan, focus[:args.focus], catalog, args.n)
        path = _p("question.txt")
        with open(path, "w") as fh:
            fh.write(text)
        print(f"wrote {path} ({len(text)} chars, ~{len(text)//4} tokens)")
        print(f"current: {sc}")
        print("answer it with any Claude session, save the JSON array, then:")
        print(f"  python3 -m abagent.cli --deck-id {args.deck_id} ask "
              f"--answers <file.json>")
        return 0

    rows = P.extract_json(open(args.answers).read())
    if not rows:
        print("no parseable proposals in that file")
        return 1
    print(f"{len(rows)} proposals; building candidates")

    ranks = swap_ranks(len(plan.get("card_order") or []))
    cands, seen = [], []
    for r in rows:
        limit = int(catalog.get(r["add"], {}).get("deck_limit") or 1)
        for rank in ranks[:2]:
            out = swap(mine, plan, r["cut"], r["add"],
                       min(r["qty"], limit), limit, rank=rank)
            if out:
                cands.append(out)
                seen.append(r)
    if not cands:
        print("none of the proposals were legal swaps in this deck")
        return 1

    got = sweep_and_confirm(h, mine, plan, cands, opps, random.Random(5),
                            args.sweep_seeds, 21, args.min_t, 1,
                            args.validate_seeds)
    if not got:
        print("no proposal survived validation")
        return 0
    cards, newplan, gain, t = got
    idx = next(i for i, c in enumerate(cands) if c == (cards, newplan))
    r = seen[idx]
    nm = lambda c: catalog.get(c, {}).get("name", c)
    print(f"ACCEPTED -{r['qty']} {nm(r['cut'])} +{r['qty']} {nm(r['add'])}"
          f"  {gain:+.1f}W t={t:.1f}")
    print(f"  model's reason: {r['why']}")
    if args.apply:
        checkpoint(api, mine, plan, f"{time.strftime('%m-%d %H:%M')} pre-proposal")
        c2: dict[int, int] = {}
        for cid in cards:
            c2[cid] = c2.get(cid, 0) + 1
        api.update_deck(args.deck_id,
                        cards=[{"card_id": c, "quantity": q} for c, q in sorted(c2.items())],
                        battle_plan=newplan)
        print(f"applied to deck {args.deck_id}")
    else:
        print("(not applied — pass --apply)")
    return 0


def enter_tournaments(api: Api, deck_id: int, log=print) -> int:
    """Enter every open tournament this deck is legal for.

    Tournaments do not carry a standing registration -- route_cohort_register
    sets $standing = empty($arena['is_tournament']), so user_arena_decks is
    never written and each daily instance must be entered on its own. Nothing
    warns you about this: a deck registered once simply stops appearing, and a
    deck registered long ago keeps appearing in instances nobody chose it for.
    Found exactly that: a month-old deck sitting in tonight's nightly
    tournament while the tuned one played only the 10-minute cohorts.

    Legality is left to the server. deck_meets_rules runs on registration with
    an explicit arena, so a Standard deck gets a clean 400 from the Pauper and
    Champion tournaments rather than silently entering a format it cannot win.

    Tournaments are also the format this whole approach suits best: ~77 matches
    per pairing instead of one, so the seed variance that dominates a single
    cohort is averaged away, and a deck that is genuinely better wins.
    """
    entered = 0
    try:
        cohorts = api.cohort().get("open_cohorts") or []
    except ApiError as e:
        log(f"tournaments: could not list cohorts ({e})")
        return 0

    for c in cohorts:
        slug = c.get("arena_slug") or ""
        if "tournament" not in slug:
            continue
        mine = c.get("my_deck") or {}
        if mine.get("deck_id") == deck_id:
            continue
        try:
            res = api.register(deck_id, arena=slug)
            log(f"tournaments: entered {slug} (closes {c['closes_at'][:16]})"
                + (f", replacing '{mine.get('name')}'" if mine else ""))
            entered += 1
        except ApiError as e:
            if e.status in (400, 403):      # illegal deck or gated arena
                continue
            log(f"tournaments: {slug} failed ({e})")
    return entered


def register_when_targetable(api: Api, arena: str, deck_id: int,
                             wait_limit: int = 240, poll: int = 5) -> bool:
    """Register only while `arena` is the cohort register() will actually hit.

    POST /cohort/register takes no arena: it enters whichever open cohort
    closes soonest, over all ~12 arenas, which rotate on ~53-second offsets.
    Calling at the wrong moment either 403s (the next arena is playtester-only)
    or -- far worse -- silently enters a DIFFERENT arena, where a deck tuned
    for Standard is either illegal or simply wrong, and nothing says so.

    So we wait for the target arena to reach the front of the queue. That
    window is about 50 seconds wide and comes round every 10 minutes.
    """
    # API >= 1.9.0 takes an explicit arena, which is durable (it writes
    # user_arena_decks) and validates the deck against that arena's rules.
    # That makes the whole timing dance below unnecessary -- it stays only as
    # a fallback for a server that predates the parameter.
    try:
        res = api.register(deck_id, arena=arena)
        print(f"register: {res.get('message') or 'entered ' + arena}"
              f"{' (standing)' if res.get('standing') else ''}")
        return True
    except ApiError as e:
        if e.status not in (400, 404, 422):
            raise
        print(f"register: server did not accept an explicit arena ({e.status}); "
              f"falling back to waiting for the rotation")

    end = time.time() + wait_limit
    while True:
        nxt = api.next_closing_cohort()
        if nxt and nxt.get("arena_slug") == arena:
            api.register(deck_id)
            print(f"register: entered {arena} cohort {nxt['cohort_key'][:12]} "
                  f"({nxt['seconds_remaining']}s before it closed)")
            return True
        if time.time() >= end:
            where = nxt.get("arena_slug") if nxt else "nothing"
            print(f"register: gave up after {wait_limit}s -- {where} kept the "
                  f"front of the queue, and registering there would not have "
                  f"been {arena}")
            return False
        if nxt:
            print(f"register: waiting for {arena}; {nxt['arena_slug']} closes "
                  f"first in {nxt['seconds_remaining']}s", flush=True)
        time.sleep(poll)


def cmd_clone(args, api: Api) -> int:
    """Copy a deck into a new one for the agent to own.

    The agent gets its own deck rather than editing an existing one: a human's
    deck is not ours to overwrite, and keeping the original intact means
    re-registering it is the whole rollback.
    """
    src = api.deck(args.source)
    new = api.create_deck(args.name)
    deck_id = int(new["deck"]["id"] if "deck" in new else new["id"])
    cards = [{"card_id": int(c["card_id"]), "quantity": int(c["quantity"])}
             for c in src["cards"]]
    api.update_deck(deck_id, cards=cards, battle_plan=src.get("battle_plan") or {})
    total = sum(c["quantity"] for c in cards)
    print(f"created deck {deck_id} '{args.name}' from {args.source}: "
          f"{total} cards, {len(cards)} distinct")
    print(f"point the agent at it:  --deck-id {deck_id}")
    return 0


def maintain_second(args, api: Api, h, cards, plan, opps,
                    repick: bool | None = None, log=print) -> None:
    """Keep the pass alive and the second slot holding the right deck.

    Three things, cheapest first, because this runs every cycle:
      - renew the pass only when the remembered expiry is close (there is no
        endpoint to ask, and asking by buying costs 177 AG a time)
      - re-assert the standing second deck, which is idempotent and the only
        way to notice a slot that silently emptied
      - re-pick the complement, but only on the slow path

    The re-pick is slow-path on purpose. What the pass pays is E[max(primary,
    second)], and the right complement is the one that stumbles in DIFFERENT
    cohorts, not the strongest one -- a correlation of 0.88 turned a 63.7-win
    deck into +0.3 while a 62.0-win deck at 0.16 was worth +0.8. That ranking
    only changes when the metagame does, so checking it every ten minutes
    would burn the budget to re-learn the same answer.
    """
    if not args.second_deck_id:
        return
    state = _load_or("second.json")
    exp = second.ensure_pass(api, state.get("pass_expiry"),
                             min_days=args.pass_min_days, log=log)
    if exp != state.get("pass_expiry"):
        state["pass_expiry"] = exp
    second.assert_second(api, args.arena_type_id, args.second_deck_id, log=log)

    if args.repick_complement if repick is None else repick:
        store = _load_or(f"history_{args.arena}.json")
        recs = history.deck_records(store, min_appearances=3, window=24)[:6]
        cands = []
        for r in recs:
            if r.deck_id in (args.deck_id, args.second_deck_id):
                continue
            try:
                pub = api.public_deck(r.deck_id)
            except ApiError:
                continue
            ids = pub.get("card_ids") or []
            if not ids:
                for c in pub.get("cards") or []:
                    ids.extend([int(c["card_id"])] * int(c["quantity"]))
            if len(ids) >= 90:
                cands.append((r.name, ids, pub.get("battle_plan") or {}))
        if cands:
            log("second: re-picking the complement by E[max], not by strength")
            best = second.pick_complement(
                h, cards, plan, cands, opps,
                [args.rng_base + i for i in range(args.complement_seeds)], log=log)
            if best:
                name, bcards, bplan, em, gain = best
                counts: dict[int, int] = {}
                for cid in bcards:
                    counts[cid] = counts.get(cid, 0) + 1
                api.update_deck(args.second_deck_id,
                                cards=[{"card_id": c, "quantity": q}
                                       for c, q in sorted(counts.items())],
                                battle_plan=bplan)
                log(f"second: slot now holds {name} (E[max] {em:.1f}, {gain:+.1f})")
                state["complement"] = name
    _save("second.json", state)


def cmd_cycle(args, api: Api) -> int:
    """One unattended pass: refresh, climb within a budget, register if better.

    Timed to land between cohorts. Standard closes at :04:58 and finalizes
    ~43s later, so firing at :06 gets a freshly finalized field and leaves
    ~9 minutes before the next close.
    """
    t0 = time.time()
    t0_seed = int(t0) % 1_000_000
    cmd_fetch(args, api)
    h, deck, mine, opps, info = _setup(args, api)
    start = dict(deck.get("battle_plan") or {})

    # Budget against the cohort we are actually trying to enter, not a fixed
    # number. Overshooting means registering after the close, which does not
    # error -- it silently enters the NEXT cohort instead, so the agent loses
    # a window and the log looks entirely normal.
    catalog = {int(c["id"]): c for c in _load("cards.json")}
    meta_decks = _load(f"meta_{args.arena}.json")["decks"]

    # Registration is now standing (POST /cohort/register with an arena writes
    # user_arena_decks), so missing a close no longer costs an entry -- the
    # deck is in every future cohort regardless. Landing an edit before the
    # close only decides whether it takes effect this cohort or the next one,
    # which makes the margin a preference rather than a correctness rule.
    budget = args.budget
    live = api.open_cohort_for(args.arena)
    if live:
        room = int(live["seconds_remaining"]) - args.margin
        if room < budget:
            print(f"cycle: {live['seconds_remaining']}s to close, trimming "
                  f"search budget {budget}s -> {max(room, 0)}s")
            budget = max(room, 0)
    if budget < args.min_budget:
        print(f"cycle: {budget}s is below the {args.min_budget}s floor — "
              f"a search this short cannot finish a single validation, "
              f"skipping rather than burning the box for nothing")
        return 0

    deadline = t0 + budget
    rng = random.Random(int(t0))

    # Play order and card list FIRST. The scalar surface is close to
    # exhausted -- the last full run confirmed 2 of 8 challengers and the
    # final sweep found nothing -- while an unbiased validation costs ~125s,
    # so leading with scalar sweeps would spend the whole budget confirming
    # nothing and hit the deadline before a single swap was tried.
    cards, plan, swaps = optimise(h, mine, opps, meta_decks, catalog, start,
                                  card_info=info, sweep_seeds=args.sweep_seeds,
                                  confirm_seeds=args.confirm_seeds,
                                  min_t=args.min_t, confirm_top=args.confirm_top,
                                  validate_seeds=args.validate_seeds,
                                  min_gain=args.min_gain,
                                  rounds=args.rounds, rng=rng, deadline=deadline)
    if args.tournaments:
        enter_tournaments(api, args.deck_id)
    maintain_second(args, api, h, mine, start, opps, repick=False)

    confirmed = list(swaps)

    # Then the standing instructions, with whatever budget is left -- they
    # can shift once the card list moves under them.
    plan, score, hist = climb(h, cards, opps, plan, card_info=info,
                              sweep_seeds=args.sweep_seeds,
                              confirm_seeds=args.confirm_seeds,
                              max_sweeps=args.sweeps, min_t=args.min_t,
                              confirm_top=args.confirm_top,
                              validate_seeds=args.validate_seeds,
                              min_gain=args.min_gain,
                              rng=rng, deadline=deadline)
    confirmed += [f"{s.field}={s.after!r} {s.gain:+.1f}W" for s in hist if s.confirmed]

    if not confirmed and time.time() < deadline + args.rebase_grace:
        # Stalled. Before changing basin, try the cheaper thing: attack the
        # few matchups that still cost anything. Once most of the field is a
        # clean sweep, a fix worth a whole matchup is worth well under a win
        # overall, and the full-field search cannot see it through the
        # dilution. The counter round screens against those decks alone.
        got = counter_round(h, cards, plan, opps, catalog, rng,
                            validate_seeds=args.validate_seeds,
                            min_t=args.min_t, min_gain=args.min_gain,
                            focus_k=args.focus)
        if got:
            cards, plan, gain, t = got
            counts2: dict[int, int] = {}
            for cid in cards:
                counts2[cid] = counts2.get(cid, 0) + 1
            api.update_deck(args.deck_id,
                            cards=[{"card_id": c, "quantity": q}
                                   for c, q in sorted(counts2.items())],
                            battle_plan=plan)
            print(f"cycle: counter-tech accepted {gain:+.1f}W t={t:.1f}")
            register_when_targetable(api, args.arena, args.deck_id,
                                     wait_limit=args.register_wait)
            return 0

    if not confirmed:
        # Still stuck: consider a different basin. Incremental swaps cannot
        # cross a valley, and the goal is rank 1, not a local optimum.
        # Stalled: the complement is worth re-ranking now, before the more
        # expensive re-base. A shifted metagame changes which deck stumbles
        # in different cohorts from ours, which is the whole basis of the pick.
        if args.second_deck_id:
            maintain_second(args, api, h, cards, plan, opps, repick=True)
        if args.rebase and time.time() < deadline + args.rebase_grace:
            try:
                store = history.fetch(api, args.arena, args.history_cohorts,
                                      _load_or("history_%s.json" % args.arena))
                _save(f"history_{args.arena}.json", store)
            except Exception as e:
                print(f"cycle: history unavailable ({e.__class__.__name__})")
                store = {}
            cand = best_rebase(h, api, cards, plan, store, opps,
                               [t0_seed + i for i in range(args.rebase_seeds)],
                               top_n=args.rebase_top) if store else None
            if cand and cand[1] >= args.rebase_min:
                d, gain = cand
                checkpoint(api, cards, plan,
                           f"{time.strftime('%m-%d %H:%M')} pre-rebase")
                counts: dict[int, int] = {}
                for cid in d["cards"]:
                    counts[cid] = counts.get(cid, 0) + 1
                api.update_deck(args.deck_id,
                                cards=[{"card_id": c, "quantity": q}
                                       for c, q in sorted(counts.items())],
                                battle_plan=d.get("battle_plan") or {})
                print(f"cycle: search stalled — re-based onto rank {d['rank']} "
                      f"'{d['deck_name'][:28]}' ({gain:+.1f}W)")
                register_when_targetable(api, args.arena, args.deck_id,
                                         wait_limit=args.register_wait)
                return 0
            print(f"cycle: search stalled and no field deck beats ours by "
                  f"{args.rebase_min}W — holding")
            return 0
        print(f"cycle: no confirmed improvement in {time.time() - t0:.0f}s, "
              f"leaving deck {args.deck_id} as-is")
        return 0

    deck_cards = None
    if cards != mine:
        counts: dict[int, int] = {}
        for cid in cards:
            counts[cid] = counts.get(cid, 0) + 1
        deck_cards = [{"card_id": c, "quantity": q} for c, q in sorted(counts.items())]
    api.update_deck(args.deck_id, cards=deck_cards, battle_plan=plan)
    _save(f"plan_{args.deck_id}.json", plan)
    print(f"cycle: applied {len(confirmed)} change(s) to deck {args.deck_id} "
          f"— {describe(plan)}")
    # climb returns no score when it skipped for lack of budget, which is a
    # normal outcome now that the joint search runs first and can use it all.
    if score is not None:
        print(f"cycle: projected {score}")

    try:
        ok = register_when_targetable(api, args.arena, args.deck_id,
                                      wait_limit=args.register_wait)
    except ApiError as e:
        # The plan is already saved on the deck, so a failed registration
        # costs this window, not the work.
        print(f"cycle: registration refused ({e}) — plan is saved, "
              f"next cycle will try again")
        return 0
    if not ok:
        print("cycle: plan saved but not registered this window")
    return 0


def cmd_results(args, api: Api) -> int:
    rows = api.results(arena=args.arena, deck_id=args.deck_id, limit=args.limit)
    print(f"{'closed':20} {'arena':10} {'place':>8} {'W-L-D':>12} {'win%':>6}")
    for r in rows["results"]:
        print(f"{r['closes_at']:20} {r['arena']['slug']:10} "
              f"{r['placement']:>3}/{r['slot_count']:<4} "
              f"{r['wins']:>3}-{r['losses']}-{r['draws']:<4} {r['win_pct']:>6}")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="abagent")
    ap.add_argument("--arena", default="pure")
    ap.add_argument("--deck-id", type=int, default=None)
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("fetch").set_defaults(fn=cmd_fetch)

    b = sub.add_parser("baseline")
    b.add_argument("--seeds", type=int, default=21)
    b.set_defaults(fn=cmd_baseline)

    c = sub.add_parser("climb")
    c.add_argument("--sweep-seeds", type=int, default=21,
                   help="seeds for the screening pass (5 cannot resolve a 1.5W effect)")
    c.add_argument("--confirm-seeds", type=int, default=21)
    c.add_argument("--sweeps", type=int, default=6)
    c.add_argument("--rng", type=int, default=20260922)
    c.add_argument("--min-gain", type=float, default=0.4,
                   help="wins a change must be worth to be applied at all")
    c.add_argument("--min-t", type=float, default=2.0,
                   help="standard errors a confirmed gain must clear")
    c.add_argument("--confirm-top", type=int, default=3,
                   help="how many sweep leaders get a confirmation round")
    c.add_argument("--seed-card-order", action="store_true",
                   help="start card_order from the deck's own copy counts")
    c.add_argument("--apply", action="store_true")
    c.add_argument("--register", action="store_true")
    c.set_defaults(fn=cmd_climb)

    k = sub.add_parser("ask")
    k.add_argument("--answers", help="JSON file of proposals to validate")
    k.add_argument("--n", type=int, default=8)
    k.add_argument("--focus", type=int, default=6)
    k.add_argument("--seeds", type=int, default=41)
    k.add_argument("--sweep-seeds", type=int, default=21)
    k.add_argument("--validate-seeds", type=int, default=161)
    k.add_argument("--min-t", type=float, default=2.0)
    k.add_argument("--apply", action="store_true")
    k.set_defaults(fn=cmd_ask)

    a = sub.add_parser("adopt")
    a.add_argument("--from-rank", type=int, default=1)
    a.add_argument("--seeds", type=int, default=61)
    a.add_argument("--apply", action="store_true")
    a.set_defaults(fn=cmd_adopt)

    n = sub.add_parser("clone")
    n.add_argument("--source", type=int, required=True)
    n.add_argument("--name", default="abagent Standard")
    n.set_defaults(fn=cmd_clone)

    y = sub.add_parser("cycle")
    y.add_argument("--budget", type=int, default=420,
                   help="seconds of search before it must stop (default 420)")
    y.add_argument("--sweep-seeds", type=int, default=21)
    y.add_argument("--confirm-seeds", type=int, default=21)
    y.add_argument("--sweeps", type=int, default=4)
    y.add_argument("--min-gain", type=float, default=0.4,
                   help="wins a change must be worth to be applied at all")
    y.add_argument("--min-t", type=float, default=2.0)
    y.add_argument("--confirm-top", type=int, default=3)
    y.add_argument("--validate-seeds", type=int, default=161,
                   help="seeds for the final unbiased test of a chosen move")
    y.add_argument("--rounds", type=int, default=3,
                   help="order+swap rounds after the scalar search")
    y.add_argument("--register-wait", type=int, default=240,
                   help="seconds to wait for the arena to reach the front")
    y.add_argument("--rebase", action="store_true", default=True,
                   help="on a stall, adopt a field deck that measurably beats ours")
    y.add_argument("--no-rebase", dest="rebase", action="store_false")
    y.add_argument("--rebase-seeds", type=int, default=31)
    y.add_argument("--rebase-top", type=int, default=12,
                   help="how far down the windowed ranking to consider")
    y.add_argument("--second-deck-id", type=int,
                   default=int(os.environ.get("ABAGENT_SECOND_DECK_ID") or 0) or None,
                   help="deck held in the Double Entry Pass slot")
    y.add_argument("--arena-type-id", type=int, default=1)
    y.add_argument("--pass-min-days", type=int, default=10)
    y.add_argument("--complement-seeds", type=int, default=61)
    y.add_argument("--rng-base", type=int, default=6100)
    y.add_argument("--repick-complement", action="store_true", default=False,
                   help="re-rank complements by E[max]; slow, use on a stall")
    y.add_argument("--tournaments", action="store_true", default=True,
                   help="enter open tournaments this deck is legal for")
    y.add_argument("--no-tournaments", dest="tournaments", action="store_false")
    y.add_argument("--focus", type=int, default=6,
                   help="how many costly matchups the counter round targets")
    y.add_argument("--history-cohorts", type=int, default=60,
                   help="cohorts of standings to keep for judging decks")
    y.add_argument("--rebase-min", type=float, default=3.0,
                   help="wins a field deck must beat ours by to re-base")
    y.add_argument("--rebase-grace", type=int, default=240,
                   help="seconds past the search deadline a re-base may still use")
    y.add_argument("--min-budget", type=int, default=150,
                   help="skip the cycle entirely below this many seconds")
    y.add_argument("--margin", type=int, default=45,
                   help="seconds to leave between finishing and the cohort close")
    y.set_defaults(fn=cmd_cycle)

    r = sub.add_parser("results")
    r.add_argument("--limit", type=int, default=15)
    r.set_defaults(fn=cmd_results)

    args = ap.parse_args(argv)
    api = Api()
    if args.deck_id is None and args.cmd == "cycle":
        raise SystemExit("cycle requires an explicit --deck-id: it edits and "
                         "registers that deck unattended")
    if args.deck_id is None and args.cmd in ("baseline", "climb", "adopt", "ask"):
        args.deck_id = api.me()["active_deck_id"]
        print(f"(using active deck {args.deck_id})")
    return args.fn(args, api)


if __name__ == "__main__":
    sys.exit(main())
