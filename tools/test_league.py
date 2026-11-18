"""Behaviour test for league.decide() -- every gate, and the orderings.

Pure function, no API, so this runs today against a route that does not
exist yet. python3 tools/test_league.py
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
from abagent.league import League, decide, parse, legal_pool

KHAOS, STARTER, BASIC = 10, 11, 13
CAT = {
    KHAOS:   {"rarity": "common",   "deck_limit": 4, "tags": ["khaos", "common"]},
    STARTER: {"rarity": "common",   "deck_limit": 4, "tags": ["starter", "common"]},
    BASIC:   {"rarity": "infinite", "deck_limit": 100, "tags": ["starter"]},
}
WORK = [KHAOS, KHAOS, BASIC]
DECK = 500            # our dedicated league deck

def lg(**kw):
    base = dict(slug="khaos-wk1", name="Expansion League", format_slug="pure",
                expansions=frozenset({"khaos"}), day=2, days_total=5,
                entered_deck_id=DECK, last_change_day=1)
    base.update(kw)
    return League(**base)

fails = []
def check(name, cond, detail=""):
    print(("  ok   " if cond else "  FAIL ") + name + ("" if cond else f"  <- {detail}"))
    if not cond: fails.append(name)

print("1. getting in")
d = decide(lg(entered_deck_id=None, last_change_day=None, day=0), WORK, CAT, DECK)
check("not entered -> enter", d.act == "enter" and d.deck_id == DECK, d)
d = decide(lg(entered_deck_id=None, last_change_day=None, day=0), WORK, CAT, None)
check("no league deck -> hold", d.act == "hold" and "league deck" in d.reason, d)
d = decide(lg(entered_deck_id=None, last_change_day=None, entries_closed=True),
           WORK, CAT, DECK)
check("closed and not in it -> hold", d.act == "hold", d)
d = decide(lg(entries_closed=True), WORK, CAT, DECK, entered_cards=[KHAOS])
check("closed but entered -> still promotes", d.act == "promote", d)

print("2. legality is judged against the LEAGUE's pool, not the arena's")
d = decide(lg(), [STARTER, STARTER, BASIC], CAT, DECK)
check("a foreign-expansion card holds the entry", d.act == "hold" and d.illegal == [STARTER], d)
d = decide(lg(expansions=frozenset()), [STARTER, BASIC], CAT, DECK, entered_cards=[KHAOS])
check("no expansion limit -> the same deck is fine", d.act == "promote", d)
check("the basic is never illegal", decide(lg(), [KHAOS, BASIC], CAT, DECK,
      entered_cards=[KHAOS]).act == "promote")

print("3. the once-a-day rule, which the SERVER owns")
check("spent today -> hold", decide(lg(last_change_day=2), WORK, CAT, DECK).act == "hold")
check("available today -> promote",
      decide(lg(last_change_day=1), WORK, CAT, DECK, entered_cards=[KHAOS]).act == "promote")
d = decide(lg(day=0, last_change_day=0), WORK, CAT, DECK, entered_cards=[KHAOS])
check("day 0 changes are FREE (0 >= 0 must not block)", d.act == "promote", d)

print("4. the last day can never pay")
d = decide(lg(day=5, days_total=5, last_change_day=1), WORK, CAT, DECK, entered_cards=[KHAOS])
check("day 5 of 5 -> hold", d.act == "hold" and "last" in d.reason, d)
d = decide(lg(day=5, days_total=5, last_change_day=1, entered_deck_legal=False),
           WORK, CAT, DECK)
check("...even to fix an ILLEGAL entry (precedence)", d.act == "hold" and "last" in d.reason, d)
d = decide(lg(day=4, days_total=5, last_change_day=1, entered_deck_legal=False),
           WORK, CAT, DECK)
check("but mid-week an illegal entry IS worth the change", d.act == "promote", d)
d = decide(lg(day=4, last_change_day=4, entered_deck_legal=False), WORK, CAT, DECK)
check("illegal entry + change spent -> hold, and says why",
      d.act == "hold" and "ILLEGAL" in d.reason, d)

print("5. do not spend a change on nothing")
d = decide(lg(), WORK, CAT, DECK, entered_cards=list(reversed(WORK)))
check("same list in another order -> hold", d.act == "hold", d)
d = decide(lg(), WORK, CAT, DECK, entered_cards=[KHAOS])
check("a different list -> promote", d.act == "promote" and d.deck_id == DECK, d)
d = decide(lg(), WORK, CAT, DECK, entered_cards=None)
check("unknown entered list -> promote (cannot prove it is a no-op)", d.act == "promote", d)

print("6. parse() falls to SAFE values, not optimistic ones")
p = parse({"slug": "s", "format": "pauper", "expansions": ["khaos"],
           "day": 3, "days_total": 5,
           "entry": {"deck_id": 7, "last_change_day": 2, "deck_legal_now": False}})
check("fields land", (p.format_slug, p.day, p.entered_deck_id, p.last_change_day,
                      p.entered_deck_legal) == ("pauper", 3, 7, 2, False), p)
check("expansions parsed", p.expansions == frozenset({"khaos"}), p.expansions)
bare = parse({"slug": "s"})
check("missing legality is None, NOT True", bare.entered_deck_legal is None, bare)
check("missing entry means not entered", not bare.entered, bare)
check("missing day is 0", bare.day == 0, bare)

print("7. the pool a generator would build from")
pool = legal_pool(CAT, lg())
check("khaos league pool = khaos + basic", set(pool) == {KHAOS, BASIC}, sorted(pool))
pool = legal_pool(CAT, lg(format_slug="pauper", expansions=frozenset()))
check("pauper, no expansion limit", set(pool) == {KHAOS, STARTER, BASIC}, sorted(pool))

# --- the write path, against a recording stub ------------------------------
from abagent.league import ensure_pair, execute, deck_names_for, Decision

class StubApi:
    def __init__(self, decks=None, fail=None, next_id=900):
        self.decks_ = decks or []
        self.calls = []
        self.fail = fail or set()
        self.next_id = next_id
    def create_deck(self, name):
        if "create" in self.fail: raise RuntimeError("Deck limit reached (17 of 17).")
        self.next_id += 1
        self.calls.append(("create", name))
        self.decks_.append({"id": self.next_id, "name": name})
        return {"deck": {"id": self.next_id}}
    def update_deck(self, deck_id, cards=None, battle_plan=None, name=None):
        if "update" in self.fail: raise RuntimeError("boom")
        self.calls.append(("update", deck_id, name if name else
                           sum(c["quantity"] for c in cards)))
        return {}
    def set_league_deck(self, slug, deck_id):
        if "set" in self.fail: raise RuntimeError("You have already changed your deck today.")
        self.calls.append(("set", slug, deck_id))
        return {}

print("8. the deck pair")
api = StubApi()
pair = ensure_pair(api, lg(), [], {"khaos-wk1"})
check("creates two decks", pair == (901, 902), pair)
check("named A and B", [c[1] for c in api.calls] == list(deck_names_for(lg())),
      api.calls)
a, b = deck_names_for(lg())
api = StubApi(decks=[{"id": 7, "name": a}, {"id": 8, "name": b}])
check("reuses its own pair", ensure_pair(api, lg(), api.decks_, {"khaos-wk1"}) == (7, 8))
check("and writes nothing to do it", api.calls == [], api.calls)
api = StubApi(decks=[{"id": 7, "name": "[lg] old-league A"},
                     {"id": 8, "name": "[lg] old-league B"}])
pair = ensure_pair(api, lg(), api.decks_, {"khaos-wk1"})
check("renames orphans instead of creating", pair == (7, 8), pair)
check("by rename, never delete",
      all(c[0] == "update" for c in api.calls) and len(api.calls) == 2, api.calls)
api = StubApi(fail={"create"})
check("the 17-deck limit fails cleanly",
      ensure_pair(api, lg(), [], {"khaos-wk1"}) is None)

print("9. a promotion never writes the deck that is currently entered")
for entered, expect in ((901, 902), (902, 901)):
    api = StubApi()
    L = lg(entered_deck_id=entered)
    ok = execute(api, L, Decision("promote", "x", deck_id=entered), (901, 902),
                 WORK, {"card_order": [KHAOS]})
    kinds = [c[0] for c in api.calls]
    check(f"entered {entered} -> writes {expect}",
          ok and api.calls[0] == ("update", expect, len(WORK)), api.calls)
    check(f"entered {entered} -> then points the entry at {expect}",
          api.calls[1] == ("set", "khaos-wk1", expect), api.calls)
    check(f"entered {entered} -> deck written BEFORE the entry moves",
          kinds == ["update", "set"], kinds)

print("10. refusals and holds")
api = StubApi()
check("a hold writes nothing",
      execute(api, lg(), Decision("hold", "spent"), (901, 902), WORK, {}) is False
      and api.calls == [], api.calls)
api = StubApi(fail={"set"})
ok = execute(api, lg(entered_deck_id=901), Decision("promote", "x"), (901, 902), WORK, {})
check("a server refusal is not a crash", ok is False)
check("...and the entered deck is still untouched",
      all(not (c[0] == "update" and c[1] == 901) for c in api.calls), api.calls)
api = StubApi(fail={"update"})
ok = execute(api, lg(entered_deck_id=901), Decision("promote", "x"), (901, 902), WORK, {})
check("a failed write never spends the change",
      ok is False and not any(c[0] == "set" for c in api.calls), api.calls)

print(f"\n{len(fails)} failure(s)" if fails else "\nall assertions pass")
sys.exit(1 if fails else 0)
