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
from .harness import Harness, Opponent, opponents_from_meta
from .plans import card_order_from_deck, describe
from .search import climb, counter_round, optimise

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Each agent instance gets its own var/: the two accounts have different
# decks, histories and pass expiries, and sharing a cache directory would
# have one silently overwriting the other's state.
VAR = os.environ.get("ABAGENT_VAR") or os.path.join(ROOT, "var")
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


GAMEPLAY_FIELDS = ("cost", "deck_limit", "rarity", "supertype", "subtype",
                   "is_retired", "is_vip")


def card_fingerprints(catalog: list[dict]) -> dict[str, str]:
    """A hash per card over the fields that change how it PLAYS.

    Deliberately not the whole row: rules_text, flavour, art and ratings move
    without altering a single simulation, and a detector that fires on those
    is one nobody reads.
    """
    import hashlib
    out = {}
    for c in catalog:
        parts = [str(c.get(f)) for f in GAMEPLAY_FIELDS]
        parts.append(json.dumps(c.get("effects_json"), sort_keys=True))
        parts.append(json.dumps(c.get("stats_json"), sort_keys=True))
        parts.append(json.dumps(sorted(c.get("tags") or [])))
        out[str(c["id"])] = hashlib.sha256("|".join(parts).encode()).hexdigest()[:16]
    return out


def detect_card_changes(catalog: list[dict], our_cards: set[int], log=print) -> set[int]:
    """Cards whose rules moved since the last fetch. Returns the changed ids.

    This matters more than it looks. Every measurement the agent holds was
    taken under the rules in force at the time -- the tuned card_order, the
    census, the archetype scores, the historical standings. A balance change
    silently invalidates all of it, and nothing about it is visible to the
    search: it compares candidates against the incumbent, so a change that
    moves the whole format leaves every comparison looking normal while the
    absolute answer has shifted underneath.

    Observed: Warrior Bee went 2 -> 3 energy, fifteen copies of it in our
    deck, and the measured cost was -0.7W. Six other cards moved in the same
    edit, two of them fifteen-copy staples of the explorer's deck.
    """
    now = card_fingerprints(catalog)
    prev = _load_or("card_fingerprints.json")
    _save("card_fingerprints.json", now)
    if not prev:
        return set()

    changed = {int(cid) for cid, h in now.items()
               if cid in prev and prev[cid] != h}
    if not changed:
        return set()

    names = {int(c["id"]): c.get("name") for c in catalog}
    mine = changed & our_cards
    log(f"cards: {len(changed)} card(s) changed rules — "
        + ", ".join(f"{names.get(c, c)}" for c in sorted(changed)[:6]))
    if mine:
        log(f"cards: {len(mine)} of them are IN OUR DECK "
            f"({', '.join(str(names.get(c, c)) for c in sorted(mine))}) — "
            f"re-baselining rather than waiting for the hourly slot")
    return changed


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
    prev_ids: set[int] = set()
    try:
        deck = api.deck(args.deck_id) if getattr(args, "deck_id", None) else None
        prev_ids = {int(c["card_id"]) for c in (deck or {}).get("cards", [])}
    except ApiError:
        pass
    changed = detect_card_changes(cards, prev_ids)
    _save("cards.json", cards)
    if changed:
        # Anything measured under the old rules is now an opinion. The
        # explored-seed list is cleared for the changed cards specifically, so
        # the explorer re-tests a card whose rules moved instead of skipping
        # it forever on the strength of a measurement that no longer applies.
        st = _load_or("explore.json")
        if st.get("explored"):
            st["explored"] = sorted(set(st["explored"]) - changed)
            _save("explore.json", st)
        sp = _load_or("slowpath.json")
        sp["last"] = 0          # force the slow path on this cycle
        _save("slowpath.json", sp)
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
    # Prune first: at the cap a create fails, and a prune that runs afterwards
    # never runs at all. The limit is enforced now, and since API 1.13.0
    # GET /decks reports slots.available directly, so this can be checked
    # rather than discovered as a 400.
    _prune_checkpoints(api, keep - 1, log)
    try:
        sl = api.slots()
        if sl.get("available") == 0 and not sl.get("admin_exempt"):
            log(f"checkpoint: no slots free ({sl.get('used')}/{sl.get('limit')}), "
                f"skipping the snapshot rather than failing the cycle")
            return None
    except ApiError:
        pass

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
    # A ceiling for the WHOLE cycle, not just the search. The search deadline
    # governs optimise/climb only, and everything after it -- counter round,
    # history fetch, re-base, complement re-pick -- is unbounded. Each is a
    # full-field evaluation costing 1-3 minutes, and raising min_gain to 0.4
    # made stalling the normal outcome, so what was designed as a rare
    # escalation now runs almost every cycle. Measured effect: a 260s search
    # inside a 7m33s-to-9m18s cycle, overrunning the 10-minute timer, which
    # then silently skips firings because systemd will not schedule a service
    # that is still active.
    hard = t0 + args.cycle_max
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

    # Entering a stage is not the same as finishing it: the counter round ends
    # in a 161-seed full-field validation that alone runs ~2.5 minutes, so it
    # needs room to COMPLETE, not merely to start. Checking only "is there time
    # left" is how a ceiling gets overshot by the length of its last stage.
    if (not confirmed and time.time() + args.stage_reserve < hard
            and time.time() < deadline + args.rebase_grace):
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
        # The expensive stall stages are rate-limited as well as time-boxed.
        # They answer questions that change with the metagame, not with the
        # cohort, so asking every ten minutes re-learns the same answer at
        # full price.
        slow = _load_or("slowpath.json")
        due = time.time() - float(slow.get("last") or 0) > args.slow_every
        # Honour the flag rather than forcing it. When the second slot is a
        # DISCOVERY slot, re-picking a complement into it would overwrite
        # whatever archetype is being measured -- the two uses of the slot are
        # mutually exclusive and the cycle must not assume the competing one.
        if due and args.second_deck_id and time.time() + args.stage_reserve < hard:
            maintain_second(args, api, h, cards, plan, opps,
                            repick=args.repick_complement)
        if due:
            slow["last"] = time.time()
            _save("slowpath.json", slow)
        if (args.rebase and due and time.time() + args.stage_reserve < hard
                and time.time() < deadline + args.rebase_grace):
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


def cmd_explore(args, api: Api) -> int:
    """Generate archetypes and rotate the best into the Double Entry slot.

    This agent never touches the account's primary deck. That deck belongs to
    a person, it holds the placement, and the whole reason the rental slot is
    usable for experiments is that only the better-placing entry is paid -- a
    failed experiment costs nothing precisely because the primary is still
    there. Writing to it would spend the thing that makes exploring free.

    Seeds are taken a slice at a time and the offset advances each run, so a
    cycle stays inside its budget and successive cycles cover the space.
    """
    import collections
    import hashlib
    import statistics

    from .archetype import generate, name_for, recent_seeds, theme_of

    cmd_fetch(args, api)
    cat_list = _load("cards.json")
    catalog = {int(c["id"]): c for c in cat_list}
    meta = _load(f"meta_{args.arena}.json")
    h = Harness(VALIDATOR, cat_list, workdir=VAR)

    played = set()
    for d in meta["decks"]:
        played |= set(d["cards"])
    from .moves import is_playable
    seeds_all = sorted(i for i, c in catalog.items()
                       if i not in played and is_playable(c)
                       and (c.get("rules_text") or "").strip())
    # Two explorers share one catalogue. Partitioning by card id keeps them off
    # each other's archetypes -- without it both would rank the same generated
    # decks the same way and spend twice the CPU to learn the same thing.
    if args.seed_mod > 1:
        seeds_all = [i for i in seeds_all if i % args.seed_mod == args.seed_rem]
    state = _load_or("explore.json")
    done = set(state.get("explored") or [])

    # New cards jump the queue. One that shipped this morning has never been
    # built around by anyone, which is a stronger claim than "unplayed" -- an
    # old unplayed card may simply have been tried and found wanting.
    fresh = [c for c in recent_seeds(catalog, args.new_days)
             if c not in done and (args.seed_mod <= 1
                                   or c % args.seed_mod == args.seed_rem)]
    if fresh:
        names = ", ".join(catalog[c]["name"] for c in fresh[:4])
        print(f"explore: {len(fresh)} card(s) added in the last {args.new_days}d "
              f"take priority — {names}")

    room = max(0, args.slice - len(fresh))
    off = int(state.get("offset") or 0) % max(1, len(seeds_all))
    rotating = seeds_all[off:off + room] or seeds_all[:room]
    state["offset"] = (off + room) % max(1, len(seeds_all))
    slice_ = (fresh + [c for c in rotating if c not in fresh])[:args.slice]
    state["explored"] = sorted(done | set(slice_))
    print(f"explore: {len(slice_)} seeds ({len(fresh)} new, "
          f"rotation at {off} of {len(seeds_all)})")

    # Candidates come from two places, and the second matters more.
    #
    # Tag archetypes build a deck from scratch around a seed. They are
    # coherent and they are weak: 12-16 wins against a field of 60-win decks,
    # because a consistent theme is not the same as a working deck.
    #
    # Mutations take a list that demonstrably works -- the top of the recent
    # standings -- and substitute in cards the metagame has never played. The
    # shell supplies the engine, the curve and the play order; the unplayed
    # pool supplies the surprise. An unorthodox deck that also FUNCTIONS is
    # far likelier to be one swap from a 60-win deck than ten swaps from a
    # blank sheet.
    archs, seen = [], set()
    for a in generate(slice_, catalog, meta["decks"]):
        key = tuple(sorted(collections.Counter(a.cards).items()))
        if key not in seen:
            seen.add(key)
            archs.append(a)

    mutants = []
    if args.mutate:
        from .archetype import Archetype, mutate
        # Shells come from the meta we already fetched this cycle, not from
        # the history store. Only cmd_cycle populates that store, so the
        # explorers never had one -- and the symptom was silent: "0 mutations
        # of the top 0 decks", printed every run while the best candidate
        # source produced nothing at all and the tag archetypes carried the
        # whole search. GET /meta already returns each deck's full card list
        # ordered by finishing rank, which is exactly what a shell needs.
        top = sorted(meta["decks"], key=lambda d: d.get("rank", 999))[:args.mutate_from]
        rng = random.Random(int(time.time()))
        pool = [c for c in seeds_all]          # unplayed, already partitioned
        for rec in top:
            ids = rec.get("cards") or []
            if len(ids) < 90:
                continue
            pub = {"card_ids": ids, "battle_plan": rec.get("battle_plan") or {}}
            rec = type("R", (), {"name": rec.get("deck_name", "?"),
                                 "deck_id": rec.get("deck_id")})()
            for _ in range(args.mutate_each):
                m = mutate(ids, pub.get("battle_plan") or {}, catalog, pool,
                           rng, swaps=args.mutate_swaps)
                if not m:
                    continue
                mcards, mplan, mlog = m
                key = tuple(sorted(collections.Counter(mcards).items()))
                if key in seen:
                    continue
                seen.add(key)
                nm = catalog.get(mlog[0][1], {}).get("name", "?")
                mutants.append(Archetype(
                    seed=mlog[0][1], seed_name=f"{rec.name[:14]}+{nm[:14]}",
                    cards=mcards, plan=mplan,
                    theme=theme_of(mlog[0][1], catalog),
                    members=sorted(collections.Counter(mcards).items(),
                                   key=lambda kv: -kv[1])))
        print(f"explore: {len(mutants)} mutations of the top "
              f"{len(top)} decks, {len(archs)} tag archetypes")
        archs = mutants + archs
    if not archs:
        print("explore: no archetypes from this slice")
        _save("explore.json", state)
        return 0

    # Record how the deck currently in the slot actually did, before anything
    # replaces it. Live results are the only check on the offline screen.
    tried = dict(state.get("tried") or {})
    cur = state.get("current")
    if cur:
        try:
            # The slot keeps one deck_id while its CONTENTS rotate, so
            # GET /results returns the id's whole history regardless of what
            # is in it now. Without the install time every new deck inherits
            # its predecessors' record -- three different decks in a row all
            # reported an identical "48.3W, rank 13.9", which is what finally
            # gave it away.
            since = str(state.get("installed_at") or "")
            rows = [r for r in api.results(arena=args.arena,
                                           deck_id=args.second_deck_id,
                                           limit=12)["results"]
                    if not since or r["closes_at"] > since]
            if rows:
                w = statistics.fmean(r["wins"] for r in rows)
                rk = statistics.fmean(r["placement"] for r in rows)
                tried.setdefault(cur, {}).update(
                    {"live_wins": round(w, 1), "live_rank": round(rk, 1),
                     "cohorts": len(rows)})
                print(f"explore: {cur} played {len(rows)} live cohorts — "
                      f"{w:.1f}W, mean rank {rk:.1f}")
            else:
                print(f"explore: {cur} has no finished cohorts yet")
        except ApiError:
            pass

    incumbent = api.deck(args.second_deck_id)
    inc_cards, inc_plan = expand(incumbent), (incumbent.get("battle_plan") or {})
    allopp = opponents_from_meta(meta, exclude_deck_ids={args.deck_id, args.second_deck_id})
    screen = allopp[::max(1, len(allopp) // 24)][:24]
    block = [args.rng_base + i for i in range(21)]

    # --- REFINE ---------------------------------------------------------
    # Scanning alone finds interesting decks and throws them away: rotate,
    # measure once, mark tried, never return. The point of finding a good
    # starting point is to iterate on it, so a deck that screens well becomes
    # the FOCUS and the next cycles mutate the focus itself rather than moving
    # on. Small swaps this time -- the deck already works, so the question is
    # what improves it, not what replaces it.
    focus = state.get("focus")
    if focus:
        from .archetype import mutate
        rng = random.Random(int(time.time()))
        pool = [c for c in seeds_all]
        kids = []
        for _ in range(args.refine_tries):
            m = mutate(focus["cards"], focus["plan"], catalog, pool, rng,
                       swaps=1)
            if m:
                kids.append(m)
        if kids:
            b = [("__focus__", focus["cards"], focus["plan"])]
            b += [(f"k{i}", c, p) for i, (c, p, _) in enumerate(kids)]
            sc = h.evaluate(b, screen, block)
            base = sc.pop("__focus__")
            best_i, best_w = None, base.wins
            for i in range(len(kids)):
                if sc[f"k{i}"].wins > best_w:
                    best_i, best_w = i, sc[f"k{i}"].wins
            if best_i is not None:
                c, p, log = kids[best_i]
                nm = catalog.get(log[0][1], {}).get("name", "?")
                focus.update({"cards": c, "plan": p, "screen": round(best_w, 1),
                              "stale": 0,
                              "history": (focus.get("history") or []) + [nm]})
                counts = collections.Counter(c)
                api.update_deck(args.second_deck_id, name=focus["name"][:80],
                                cards=[{"card_id": k, "quantity": v}
                                       for k, v in sorted(counts.items())],
                                battle_plan=p)
                if best_w > float(state.get("best_screen") or 0):
                    state["best_screen"] = round(best_w, 1)
                    state["best_deck"] = {"name": focus["name"], "cards": c,
                                          "plan": p, "screen": round(best_w, 1)}
                print(f"explore: refined {focus['name']} "
                      f"{base.wins:.1f}W -> {best_w:.1f}W (+{nm})")
                state["installed_at"] = time.strftime("%Y-%m-%d %H:%M:%S",
                                                      time.gmtime())
                state["focus"] = focus
                state["tried"] = tried
                _save("explore.json", state)
                return 0
            focus["stale"] = int(focus.get("stale") or 0) + 1
            print(f"explore: {focus['name']} not improved "
                  f"({focus['stale']}/{args.refine_patience}), best "
                  f"{focus.get('screen')}W")
            if focus["stale"] < args.refine_patience:
                state["focus"] = focus
                state["tried"] = tried
                _save("explore.json", state)
                return 0
            print(f"explore: banking {focus['name']} at {focus.get('screen')}W "
                  f"after {len(focus.get('history') or [])} refinements; scanning again")
            tried.setdefault(focus["name"], {})["refined_to"] = focus.get("screen")
            state["focus"] = None

    # --- SCAN -----------------------------------------------------------
    batch = [("__inc__", inc_cards, inc_plan)]
    batch += [(f"a{i}", a.cards, a.plan) for i, a in enumerate(archs)]
    sc = h.evaluate(batch, screen, block)
    inc = sc.pop("__inc__")

    # The floor is anchored to the BEST deck ever screened, not to the
    # incumbent. Anchoring it to the incumbent made it a ratchet with no
    # bottom: every slightly-worse pick lowered the bar for the next one, and
    # over a day the screens fell from 16-20 wins to 4-9 while the live
    # results sank to rank 30.
    best_ever = max([float(v.get("screen_wins") or 0) for v in tried.values()]
                    + [float(state.get("best_screen") or 0), inc.wins])
    floor = best_ever * args.floor_frac

    # Identity is the CARD LIST, not the name. name_for() draws on ~34 tag
    # words, so genuinely different decks collide on a name constantly -- and
    # keying `tried` by name meant the first "Ember Fury" blackballed every
    # later deck that happened to be called one. 26 names had excluded most of
    # the space, including every good deck found so far.
    def ident(a):
        return hashlib.sha256(
            repr(sorted(collections.Counter(a.cards).items())).encode()
        ).hexdigest()[:16]

    seen_ids = set(state.get("tried_ids") or [])
    ranked = sorted(((sc[f"a{i}"].wins, i) for i in range(len(archs))), reverse=True)
    pick = None
    for w, i in ranked:
        if ident(archs[i]) in seen_ids or w < floor:
            continue
        pick = (w, i, name_for(archs[i].theme, archs[i].seed_name))
        break

    if not pick:
        # Holding means holding whatever happens to be loaded, which after a
        # bad rotation is a bad deck. Fall back to the best list ever found
        # and refine that instead of sitting on a 4-win pile.
        bb = state.get("best_deck")
        if bb and inc.wins < best_ever * args.floor_frac:
            counts = collections.Counter(bb["cards"])
            api.update_deck(args.second_deck_id, name=bb["name"][:80],
                            cards=[{"card_id": c, "quantity": q}
                                   for c, q in sorted(counts.items())],
                            battle_plan=bb["plan"])
            state["current"] = bb["name"]
            state["installed_at"] = time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime())
            state["focus"] = {"name": bb["name"], "cards": bb["cards"],
                              "plan": bb["plan"], "screen": bb.get("screen"),
                              "stale": 0, "history": []}
            print(f"explore: nothing new above the {floor:.1f}W floor; "
                  f"restoring best known '{bb['name']}' "
                  f"({bb.get('screen')}W) and refining it")
        else:
            print(f"explore: nothing new above the {floor:.1f}W floor "
                  f"({len(seen_ids)} decks tried); holding")
        state["tried"] = tried
        _save("explore.json", state)
        return 0

    w, i, nm = pick
    best = archs[i]
    counts = collections.Counter(best.cards)
    api.update_deck(args.second_deck_id, name=nm[:80],
                    cards=[{"card_id": c, "quantity": q} for c, q in sorted(counts.items())],
                    battle_plan=best.plan)
    tried.setdefault(nm, {})["screen_wins"] = round(w, 1)
    seen_ids.add(ident(best))
    state["tried_ids"] = sorted(seen_ids)
    state["tried"] = tried
    state["current"] = nm
    state["installed_at"] = time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime())
    if w > float(state.get("best_screen") or 0):
        state["best_screen"] = round(w, 1)
        state["best_deck"] = {"name": nm, "cards": best.cards,
                              "plan": best.plan, "screen": round(w, 1)}

    # A deck that screens better than anything tried so far is a starting
    # point worth developing, not another sample.
    # Any pick above the floor becomes the focus. The first version also
    # required it to beat the best deck ever tried AND the incumbent, which
    # sounds prudent and never fired once: the scan keeps producing decks
    # clustered within a win of each other, so the bar was always just above
    # whatever had been found. Scanning to find a starting point and then
    # never developing one is the failure mode this exists to fix.
    if w >= args.refine_floor:
        state["focus"] = {"name": nm, "cards": best.cards, "plan": best.plan,
                          "screen": round(w, 1), "stale": 0, "history": []}
        print(f"explore: rotating slot -> '{nm}' ({w:.1f}W screen) and MAKING IT "
              f"THE FOCUS — next cycles will iterate on it")
    else:
        print(f"explore: rotating slot -> '{nm}' ({w:.1f}W screen, incumbent "
              f"{inc.wins:.1f}W) — {best.summary(catalog, 4)}")
    _save("explore.json", state)
    return 0


def cmd_beat(args, api: Api) -> int:
    """Find a deck that beats ONE named deck.

    A different objective from everything else here, and a much cheaper one.
    Against the full field a counter to a single deck is worth well under a
    win -- the effect is diluted 69:1 -- but measured against that deck alone
    it is enormous, and one opponent costs a sixty-ninth as much, so hundreds
    of candidates can be screened for the price of a few.

    Candidates come from both directions: every generated archetype, and every
    deck the field already plays. A deck that beats the target may well exist
    already.

    The field score is reported alongside but never used to select. A pure
    counter is allowed to be bad against everything else -- that is a coherent
    thing to want, and hiding it behind an aggregate would defeat the request.
    """
    from .archetype import generate, name_for
    from .search import paired_t
    from .moves import is_playable
    import collections

    cmd_fetch(args, api)
    cat_list = _load("cards.json")
    catalog = {int(c["id"]): c for c in cat_list}
    meta = _load(f"meta_{args.arena}.json")
    h = Harness(VALIDATOR, cat_list, workdir=VAR)

    tgt = api.public_deck(args.target)
    tids = tgt.get("card_ids") or []
    if not tids:
        for c in tgt.get("cards") or []:
            tids.extend([int(c["card_id"])] * int(c["quantity"]))
    target = Opponent(slot_id="T", cards=tids,
                      battle_plan=tgt.get("battle_plan") or {},
                      name=tgt.get("name", str(args.target)),
                      deck_id=args.target)
    print(f"target: {target.name} (deck {args.target}), "
          f"{len(tids)} cards, {len(set(tids))} distinct")

    seeds = sorted(i for i, c in catalog.items()
                   if is_playable(c) and (c.get("rules_text") or "").strip())
    cands, seen = [], set()
    for a in generate(seeds, catalog, meta["decks"]):
        key = tuple(sorted(collections.Counter(a.cards).items()))
        if key not in seen:
            seen.add(key)
            cands.append((name_for(a.theme, a.seed_name), a.cards, a.plan))
    for d in meta["decks"]:
        if d.get("deck_id") != args.target:
            cands.append((f"[field] {d['deck_name'][:22]}", d["cards"],
                          d.get("battle_plan") or {}))
    print(f"screening {len(cands)} candidates against that one deck")

    block = [args.rng_base + i for i in range(args.seeds)]
    ranked = []
    for start in range(0, len(cands), 60):
        chunk = cands[start:start + 60]
        res = h.evaluate([(f"c{start+i}", c, p) for i, (_, c, p) in enumerate(chunk)],
                         [target], block)
        for i, (nm, c, p) in enumerate(chunk):
            sc = res[f"c{start+i}"]
            # Score.wins is the MEAN wins per seed across the opponent set, so
            # against a single opponent it is already the win rate. Dividing
            # by the seed count again reported every candidate at 0% -- which
            # looked like "nothing beats this deck" rather than like a bug,
            # because an unbeatable deck is a believable finding.
            ranked.append((sc.wins, nm, start + i))
    ranked.sort(reverse=True)

    print(f"\n{'candidate':34} {'win% vs target':>15}")
    for wr, nm, _ in ranked[:10]:
        print(f"{nm[:34]:34} {wr * 100:>14.0f}%")

    # Confirm the leaders on unseen seeds, and report what they do to the
    # rest of the field so the trade-off is visible rather than implied.
    top = ranked[:args.confirm]
    vblock = [args.rng_base + 7777 + i for i in range(args.validate_seeds)]
    field = opponents_from_meta(meta, exclude_deck_ids={args.target})
    print(f"\nconfirming top {len(top)} on {args.validate_seeds} unseen seeds:")
    print(f"{'candidate':34} {'win%':>9} {'draw%':>6} {'vs field':>9}")
    best = None
    for wr, nm, idx in top:
        _, c, p = cands[idx]
        v = h.evaluate([("x", c, p)], [target], vblock)["x"]
        f = h.evaluate([("x", c, p)], field, vblock[:41])["x"]
        rate = v.wins
        print(f"{nm[:34]:34} {rate*100:>8.0f}%  {v.draws*100:>3.0f}%d "
              f"{f.wins:>8.1f}W")
        if best is None or rate > best[0]:
            best = (rate, nm, idx, f.wins)
    if best:
        print(f"\nbest counter: {best[1]} — beats {target.name} "
              f"{best[0]*100:.0f}% of the time, scores {best[3]:.1f}W vs the field")
        _save(f"counter_{args.target}.json",
              {"name": best[1], "cards": cands[best[2]][1], "plan": cands[best[2]][2],
               "win_rate_vs_target": round(best[0], 3), "field_wins": round(best[3], 1)})
        print(f"wrote var/counter_{args.target}.json")
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
    y.add_argument("--stage-reserve", type=int, default=200,
                   help="time a slow stage needs to FINISH, not just to start")
    y.add_argument("--cycle-max", type=int, default=420,
                   help="hard wall-clock ceiling for the entire cycle")
    y.add_argument("--slow-every", type=int, default=3600,
                   help="seconds between re-base / complement re-pick attempts")
    y.add_argument("--min-budget", type=int, default=150,
                   help="skip the cycle entirely below this many seconds")
    y.add_argument("--margin", type=int, default=45,
                   help="seconds to leave between finishing and the cohort close")
    y.set_defaults(fn=cmd_cycle)

    e = sub.add_parser("explore")
    e.add_argument("--second-deck-id", type=int,
                   default=int(os.environ.get("ABAGENT_SECOND_DECK_ID") or 0) or None)
    e.add_argument("--slice", type=int, default=40)
    e.add_argument("--new-days", type=int, default=7,
                   help="treat cards added this recently as priority seeds")
    e.add_argument("--validate-seeds", type=int, default=161)
    e.add_argument("--mutate", action="store_true", default=True,
                   help="mutate top decks with unplayed cards (primary source)")
    e.add_argument("--no-mutate", dest="mutate", action="store_false")
    e.add_argument("--mutate-from", type=int, default=8,
                   help="how many top decks to use as shells")
    e.add_argument("--mutate-each", type=int, default=4)
    e.add_argument("--mutate-swaps", type=int, default=3)
    e.add_argument("--seed-mod", type=int, default=1,
                   help="partition the seed space across explorers")
    e.add_argument("--seed-rem", type=int, default=0)
    e.add_argument("--refine-tries", type=int, default=8,
                   help="single-card mutations of the focus deck per cycle")
    e.add_argument("--refine-patience", type=int, default=4,
                   help="cycles without improvement before banking the focus")
    e.add_argument("--refine-floor", type=float, default=15.0,
                   help="screen wins a deck needs to become the focus")
    e.add_argument("--floor-frac", type=float, default=0.6,
                   help="skip archetypes below this fraction of the incumbent")
    e.add_argument("--rng-base", type=int, default=9100)
    e.set_defaults(fn=cmd_explore)

    b = sub.add_parser("beat")
    b.add_argument("--target", type=int, required=True, help="deck id to beat")
    b.add_argument("--seeds", type=int, default=41)
    b.add_argument("--validate-seeds", type=int, default=121)
    b.add_argument("--confirm", type=int, default=6)
    b.add_argument("--rng-base", type=int, default=3300)
    b.set_defaults(fn=cmd_beat)

    r = sub.add_parser("results")
    r.add_argument("--limit", type=int, default=15)
    r.set_defaults(fn=cmd_results)

    args = ap.parse_args(argv)
    api = Api()
    if args.deck_id is None and args.cmd == "cycle":
        raise SystemExit("cycle requires an explicit --deck-id: it edits and "
                         "registers that deck unattended")
    if args.deck_id is None and args.cmd in ("baseline", "climb", "adopt", "ask",
                                             "explore"):
        args.deck_id = api.me()["active_deck_id"]
        print(f"(using active deck {args.deck_id})")
    return args.fn(args, api)


if __name__ == "__main__":
    sys.exit(main())
