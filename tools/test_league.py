"""Behaviour test for league.py -- every gate, parse(), and the write path.

Pure logic plus a recording stub, so it needs no API. Pass a saved
GET /leagues payload as argv[1] to also parse the real thing.

    python3 tools/test_league.py [leagues.json]
"""
import json, sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
from abagent.league import (League, Decision, decide, parse, legal_pool,
                            ensure_deck, execute, deck_name_for,
                            EXPANSION_NAME_TO_SLUG)

KHAOS, STARTER, BASIC, RARE = 10, 11, 13, 14
CAT = {
    KHAOS:   {"rarity": "common",   "deck_limit": 4, "tags": ["khaos", "common"]},
    STARTER: {"rarity": "common",   "deck_limit": 4, "tags": ["starter", "common"]},
    BASIC:   {"rarity": "infinite", "deck_limit": 100, "tags": ["starter"]},
    RARE:    {"rarity": "rare",     "deck_limit": 2, "tags": ["khaos", "rare"]},
}
WORK = [KHAOS, KHAOS, BASIC]
DECK = 500

def lg(**kw):
    base = dict(slug="khaos-wk1", name="Expansion League",
                expansions=frozenset({"khaos"}), day=2, days_total=5,
                entered_deck_id=DECK, last_change_day=1, can_change_now=True,
                deck_edited_since_entry=True)
    base.update(kw)
    return League(**base)

fails = []
def check(name, cond, detail=""):
    print(("  ok   " if cond else "  FAIL ") + name + ("" if cond else f"  <- {detail}"))
    if not cond: fails.append(name)

print("1. formats the generators cannot build for")
check("a champion league holds",
      decide(lg(requires_champion=True), WORK, CAT, DECK).act == "hold")
check("singleton + duplicates holds",
      decide(lg(singleton=True), WORK, CAT, DECK).act == "hold")
check("singleton + no duplicates is fine",
      decide(lg(singleton=True), [KHAOS, BASIC], CAT, DECK).act == "submit")

print("2. an expansion we cannot map is NEVER guessed")
d = decide(lg(expansions=frozenset(), unmapped_expansions=("Outworld",)), WORK, CAT, DECK)
check("unmapped -> hold", d.act == "hold" and "Outworld" in d.reason, d)

print("3. getting in")
d = decide(lg(entered_deck_id=None, last_change_day=None, day=0), WORK, CAT, DECK)
check("not entered -> enter", d.act == "enter" and d.deck_id == DECK, d)
d = decide(lg(entered_deck_id=None, day=0), WORK, CAT, None)
check("no deck yet -> hold, flagged as needing one",
      d.act == "hold" and d.need == "deck", d)
check("an ILLEGAL deck does NOT ask for a league deck",
      decide(lg(entered_deck_id=None, day=0), [STARTER, STARTER], CAT, None).need is None,
      decide(lg(entered_deck_id=None, day=0), [STARTER, STARTER], CAT, None))
check("closed and not in it -> hold",
      decide(lg(entered_deck_id=None, entries_closed=True), WORK, CAT, DECK).act == "hold")
d = decide(lg(entered_deck_id=None, day=0, can_change_now=False,
              change_block_reason="Entries for this league have closed."),
           WORK, CAT, DECK)
check("server says no -> hold, quoting it", d.act == "hold" and "closed" in d.reason, d)

print("4. legality is judged against the LEAGUE's own rules")
d = decide(lg(), [STARTER, STARTER, BASIC], CAT, DECK)
check("foreign expansion holds", d.act == "hold" and d.illegal == [STARTER], d)
d = decide(lg(rarities=frozenset({"common", "infinite"})), [KHAOS, RARE, BASIC], CAT, DECK)
check("a rare in a pauper league holds", d.act == "hold" and d.illegal == [RARE], d)
check("the basic is never illegal",
      decide(lg(), [KHAOS, BASIC], CAT, DECK).act == "submit")
check("no limits at all -> anything goes",
      decide(lg(expansions=frozenset()), [STARTER, RARE], CAT, DECK).act == "submit")

print("5. the server owns the daily rule; we quote its answer")
d = decide(lg(can_change_now=False,
              change_block_reason="You have already changed your deck today."),
           WORK, CAT, DECK)
check("spent today -> hold with the server's words",
      d.act == "hold" and "already changed" in d.reason, d)
check("available -> submit", decide(lg(), WORK, CAT, DECK).act == "submit")

print("6. the last day can never pay")
d = decide(lg(day=5, days_total=5), WORK, CAT, DECK)
check("day 5 of 5 -> hold", d.act == "hold" and "last" in d.reason, d)
d = decide(lg(day=5, days_total=5, entered_deck_legal=False), WORK, CAT, DECK)
check("...even to fix an ILLEGAL entry (precedence)",
      d.act == "hold" and "last" in d.reason, d)
d = decide(lg(day=4, entered_deck_legal=False, deck_edited_since_entry=False),
           WORK, CAT, DECK)
check("mid-week an illegal entry IS worth the change", d.act == "submit", d)
d = decide(lg(day=4, entered_deck_legal=False, can_change_now=False,
              change_block_reason="already changed today"), WORK, CAT, DECK)
check("but not when the change is spent", d.act == "hold", d)

print("7. do not spend the day's one change on nothing")
PLAN = {"card_order": [KHAOS]}
d = decide(lg(deck_edited_since_entry=False), WORK, CAT, DECK)
check("no league deck given, server says no drift -> hold",
      d.act == "hold" and "already this list" in d.reason, d)
d = decide(lg(deck_edited_since_entry=False, entered_deck_id=999), WORK, CAT, DECK)
check("entry on the WRONG deck -> submit ours", d.act == "submit" and d.deck_id == DECK, d)
d = decide(lg(deck_edited_since_entry=True), WORK, CAT, DECK)
check("no league deck given, server says drift -> submit", d.act == "submit", d)

# The measured false positive: the server says edited the instant after a
# successful submit whenever the deck carries a battle plan.
d = decide(lg(deck_edited_since_entry=True), WORK, CAT, DECK,
           league_deck=(list(WORK), dict(PLAN)), working_plan=dict(PLAN))
check("identical league deck -> HOLD even though the server says edited",
      d.act == "hold" and "server says edited" in d.reason, d)
d = decide(lg(deck_edited_since_entry=False), WORK, CAT, DECK,
           league_deck=([KHAOS], dict(PLAN)), working_plan=dict(PLAN))
check("different CARDS -> submit, naming cards",
      d.act == "submit" and "cards" in d.reason, d)
d = decide(lg(deck_edited_since_entry=False), WORK, CAT, DECK,
           league_deck=(list(WORK), {"card_order": [BASIC]}), working_plan=dict(PLAN))
check("same cards, different PLAN -> submit, naming the plan",
      d.act == "submit" and "battle plan" in d.reason, d)
d = decide(lg(), WORK, CAT, DECK,
           league_deck=(list(reversed(WORK)), dict(PLAN)), working_plan=dict(PLAN))
check("the same multiset in another order is not a change", d.act == "hold", d)

print("8. parse() falls to safe values")
p = parse({"slug": "s", "name": "S", "current_day": 3, "days_total": 5,
           "entries_closed": False, "can_change_deck_now": True,
           "format_rules": {"allowed_rarities": ["common", "infinite"], "singleton": 1},
           "allowed_expansions": [12],
           "allowed_expansion_names": ["Skeletron's Invasion"],
           "entry": {"deck_id": 7, "last_change_day": 2, "change_count": 4,
                     "deck_edited_since_entry": True, "deck_legal_now": False,
                     "withdrawn": False}})
check("rarities from format_rules", p.rarities == frozenset({"common", "infinite"}), p.rarities)
check("singleton read", p.singleton is True)
check("expansion NAME mapped to a tag", p.expansions == frozenset({"skeletron-inv"}), p.expansions)
check("nothing left unmapped", p.unmapped_expansions == (), p.unmapped_expansions)
check("entry read", (p.entered_deck_id, p.change_count, p.deck_edited_since_entry,
                     p.entered_deck_legal) == (7, 4, True, False), p)
u = parse({"slug": "s", "allowed_expansion_names": ["Æther Vault"]})
check("an unknown name is KEPT as unmapped, not dropped",
      u.unmapped_expansions == ("\u00c6ther Vault",) and u.expansions == frozenset(), u)
check("every rotation name maps to a tag arenas.py declares",
      all(v in __import__("abagent.arenas", fromlist=["x"]).EXPANSIONS
          for v in EXPANSION_NAME_TO_SLUG.values()),
      sorted(EXPANSION_NAME_TO_SLUG.values()))
for nm in ("Starter Set", "Khaos", "Outworld"):
    check(f"rotation week '{nm}' is mapped",
          parse({"slug": "s", "allowed_expansion_names": [nm]}).unmapped_expansions == ())
bare = parse({"slug": "s"})
check("missing legality is None, not True", bare.entered_deck_legal is None)
check("missing entry means not entered", not bare.entered)
check("missing can_change is False, not True", bare.can_change_now is False)
w = parse({"slug": "s", "entry": {"deck_id": 7, "withdrawn": True}})
check("a withdrawn entry is not entered", not w.entered, w)

print("9. the pool a generator builds from")
check("khaos league pool", set(legal_pool(CAT, lg())) == {KHAOS, BASIC, RARE},
      sorted(legal_pool(CAT, lg())))
check("pauper khaos pool drops the rare",
      set(legal_pool(CAT, lg(rarities=frozenset({"common", "infinite"})))) == {KHAOS, BASIC},
      sorted(legal_pool(CAT, lg(rarities=frozenset({"common", "infinite"})))))

# --- the write path, against a recording stub ------------------------------
class StubApi:
    def __init__(self, decks=None, fail=None, next_id=900, changed=True):
        self.decks_ = decks or []; self.calls = []
        self.fail = fail or set(); self.next_id = next_id; self.changed = changed
    def create_deck(self, name):
        if "create" in self.fail: raise RuntimeError("Deck limit reached (17 of 17).")
        self.next_id += 1; self.calls.append(("create", name))
        self.decks_.append({"id": self.next_id, "name": name})
        return {"deck": {"id": self.next_id}}
    def update_deck(self, deck_id, cards=None, battle_plan=None, name=None):
        if "update" in self.fail: raise RuntimeError("boom")
        self.calls.append(("update", deck_id,
                           name if name else sum(c["quantity"] for c in cards)))
        return {}
    def set_league_deck(self, slug, deck_id):
        if "set" in self.fail:
            raise RuntimeError("You have already changed your deck today.")
        self.calls.append(("set", slug, deck_id)); return {"changed": self.changed}

print("10. the league deck")
api = StubApi()
check("creates one named for the slug",
      ensure_deck(api, lg(), [], {"khaos-wk1"}) == 901
      and api.calls == [("create", deck_name_for(lg()))], api.calls)
api = StubApi(decks=[{"id": 7, "name": deck_name_for(lg())}])
check("reuses its own, writing nothing",
      ensure_deck(api, lg(), api.decks_, {"khaos-wk1"}) == 7 and api.calls == [], api.calls)
api = StubApi(decks=[{"id": 8, "name": "[lg] finished-league"}])
check("renames an orphan rather than creating",
      ensure_deck(api, lg(), api.decks_, {"khaos-wk1"}) == 8
      and api.calls == [("update", 8, deck_name_for(lg()))], api.calls)
api = StubApi(decks=[{"id": 8, "name": "[lg] khaos-wk1-other"}])
check("a LIVE league's deck is not an orphan",
      ensure_deck(api, lg(), api.decks_, {"khaos-wk1-other"}) == 901, api.calls)
check("the deck limit fails cleanly",
      ensure_deck(StubApi(fail={"create"}), lg(), [], set()) is None)

print("11. write, THEN submit -- the submit is what snapshots")
api = StubApi()
ok = execute(api, lg(), Decision("submit", "x", deck_id=DECK), DECK, WORK, {"card_order": [KHAOS]})
check("both calls, in that order",
      ok and [c[0] for c in api.calls] == ["update", "set"], api.calls)
check("the deck got the whole list",
      api.calls[0] == ("update", DECK, len(WORK)), api.calls)
check("then the entry was submitted", api.calls[1] == ("set", "khaos-wk1", DECK), api.calls)
api = StubApi()
check("a hold writes nothing",
      execute(api, lg(), Decision("hold", "spent"), DECK, WORK, {}) is False
      and api.calls == [], api.calls)
api = StubApi(fail={"update"})
ok = execute(api, lg(), Decision("submit", "x"), DECK, WORK, {})
check("a failed write never submits",
      ok is False and not any(c[0] == "set" for c in api.calls), api.calls)
api = StubApi(fail={"set"})
check("a refusal is reported, not raised",
      execute(api, lg(), Decision("submit", "x"), DECK, WORK, {}) is False)

if len(sys.argv) > 1:
    print(f"12. the real payload ({sys.argv[1]})")
    rows = json.load(open(sys.argv[1]))["leagues"]
    got = {r["slug"]: parse(r) for r in rows}
    check("all three parse", len(got) == 3, sorted(got))
    std = got["standard-league-nov-22"]
    pau = got["pauper-league-nov-22"]
    exp = got["expansion-league-skeletron-s-invasion-nov-22"]
    check("standard: no rarity limit, no expansions",
          std.rarities is None and not std.expansions and not std.unmapped_expansions, std)
    check("pauper: common+infinite", pau.rarities == frozenset({"common", "infinite"}), pau.rarities)
    check("expansion league maps to skeletron-inv",
          exp.expansions == frozenset({"skeletron-inv"}) and not exp.unmapped_expansions, exp)
    check("all at day 0, entries open",
          all(l.day == 0 and not l.entries_closed and l.can_change_now
              for l in got.values()), [(l.slug, l.day, l.entries_closed) for l in got.values()])
    check("5 days each", all(l.days_total == 5 for l in got.values()))

print(f"\n{len(fails)} failure(s)" if fails else "\nall assertions pass")
sys.exit(1 if fails else 0)
