"""Behaviour test for league.py -- every gate, parse(), and the write path.

Pure logic plus a recording stub, so it needs no API. Pass a saved
GET /leagues payload as argv[1] to also parse the real thing.

    python3 tools/test_league.py [leagues.json]
"""
import json, sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
from abagent.league import (League, Decision, decide, parse, legal_pool,
                            window_hold, submittable,
                            field_from_entries, key_from,
                            _iso_epoch as _iso,
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

print("7b. WHEN to spend the day's one change (the submission window)")
NOW = 1_800_000_000.0
H = 3600.0
PLAN = {"card_order": [KHAOS]}
def mid(**kw):
    """A mid-league league with something new to submit."""
    base = dict(day=2, next_day_at=NOW + 5 * H)
    base.update(kw)
    return lg(**base)
new_deck = dict(league_deck=([KHAOS], dict(PLAN)), working_plan=dict(PLAN))
d = decide(mid(), WORK, CAT, DECK, now=NOW, **new_deck)
check("5h left of the day -> HOLD the change",
      d.act == "hold" and "holding today's one change" in d.reason, d)
check("...and it says what it would have submitted",
      "would otherwise submit" in d.reason, d)
d = decide(mid(next_day_at=NOW + 1 * H), WORK, CAT, DECK, now=NOW, **new_deck)
check("1h left -> submit", d.act == "submit", d)
d = decide(mid(next_day_at=NOW + 2 * H - 1), WORK, CAT, DECK, now=NOW, **new_deck)
check("exactly inside the 2h window -> submit", d.act == "submit", d)
d = decide(mid(day=0, last_change_day=None), WORK, CAT, DECK, now=NOW, **new_deck)
check("day 0 is exempt: changes are free, submit now", d.act == "submit", d)
d = decide(mid(entered_deck_legal=False), WORK, CAT, DECK, now=NOW, **new_deck)
check("an ILLEGAL entry never waits for the window", d.act == "submit", d)
d = decide(mid(next_day_at=None), WORK, CAT, DECK, now=NOW, **new_deck)
check("unknown boundary -> inert, submit as before", d.act == "submit", d)
d = decide(mid(), WORK, CAT, DECK, now=NOW,
           league_deck=(list(WORK), dict(PLAN)), working_plan=dict(PLAN))
check("nothing new -> the no-op hold, NOT the window hold",
      d.act == "hold" and "already holds this list" in d.reason, d)
d = decide(mid(), WORK, CAT, DECK, now=NOW, window_s=6 * H, **new_deck)
check("a wider window submits sooner", d.act == "submit", d)
check("seconds_left_today does the arithmetic",
      abs(mid().seconds_left_today(NOW) - 5 * H) < 1, mid().seconds_left_today(NOW))

print("7c. submittable(): the PRE-FLIGHT refinement runs before it spends CPU")
check("an entry with time left and a change in hand -> go",
      submittable(mid(next_day_at=NOW + 1 * H), now=NOW) is None,
      submittable(mid(next_day_at=NOW + 1 * H), now=NOW))
check("day 0 -> go (changes are free and unlimited)",
      submittable(mid(day=0, last_change_day=None), now=NOW) is None)
w = submittable(mid(), now=NOW)
check("5h left of the day -> the WINDOW hold, worded as decide()'s is",
      w is not None and "holding today's one change" in w, w)
check("...and it is literally the same function decide() uses",
      w == window_hold(mid(), NOW), (w, window_hold(mid(), NOW)))
check("not entered -> refuses, and says refine never enters",
      "never enters" in (submittable(mid(entered_deck_id=None), now=NOW) or ""),
      submittable(mid(entered_deck_id=None), now=NOW))
check("the last day -> refuses (a change then could never play)",
      "could never play" in (submittable(mid(day=5), now=NOW) or ""),
      submittable(mid(day=5), now=NOW))
check("the server says no -> quotes the server",
      submittable(mid(can_change_now=False,
                      change_block_reason="already changed today"),
                  now=NOW) == "already changed today")
check("a blocked league with no reason still refuses",
      submittable(mid(can_change_now=False), now=NOW) is not None)
check("an unmapped expansion -> refuses rather than build from the wrong pool",
      "not mapped" in (submittable(mid(unmapped_expansions=("Mystery Set",)),
                                   now=NOW) or ""))
# The one deliberate divergence from decide(): gate 8. decide() submits an
# ILLEGAL entry whatever the window says, because it scores nothing until it
# is fixed. submittable() does NOT special-case it -- a pre-flight that let an
# illegal entry through would send refinement off to search for a legal list
# it then could not submit until the window anyway.
check("an illegal entry is NOT excused by the pre-flight (decide() owns that)",
      submittable(mid(entered_deck_legal=False), now=NOW) is not None,
      submittable(mid(entered_deck_legal=False), now=NOW))
check("...while decide() still submits it immediately",
      decide(mid(entered_deck_legal=False), WORK, CAT, DECK, now=NOW,
             **new_deck).act == "submit")
check("an unknown day boundary is inert, not a hold",
      submittable(mid(next_day_at=None), now=NOW) is None)
check("window_hold is None on day 0 whatever the clock says",
      window_hold(mid(day=0), NOW) is None)

print("7d. field_from_entries(): the field refinement screens against")
class FieldApi:
    """Just enough of Api for field_from_entries: leagues, decks, deck."""
    def __init__(self, entered, cards=None, named=None, rows=None):
        self.entered, self.named = entered, named
        self.cards = cards if cards is not None else [KHAOS] * 100
        self.rows = rows
    def leagues(self):
        if self.rows is not None:
            return self.rows
        return [{"slug": "khaos-wk1", "name": "E", "current_day": 2,
                 "days_total": 5,
                 "entry": {"deck_id": self.entered} if self.entered else {}}]
    def decks(self):
        return [{"id": self.named, "name": "[lg] khaos-wk1"}] if self.named else []
    def deck(self, did):
        q = {}
        for c in self.cards:
            q[c] = q.get(c, 0) + 1
        return {"id": did, "name": f"deck {did}",
                "cards": [{"card_id": k, "quantity": v} for k, v in q.items()],
                "battle_plan": {}}

quiet = lambda *_: None
f = field_from_entries([("a", FieldApi(601)), ("b", FieldApi(602))],
                       "khaos-wk1", log=quiet)
check("two entered accounts -> a field of two",
      f is not None and len(f["decks"]) == 2, f and len(f["decks"]))
check("each deck carries its flat 100-card list",
      all(len(d["cards"]) == 100 for d in f["decks"]))
check("no entries at all -> None, never an empty field",
      field_from_entries([("a", FieldApi(None))], "khaos-wk1", log=quiet) is None)
check("a deck that is not 100 cards is skipped, not entered as a cripple",
      field_from_entries([("a", FieldApi(601, cards=[KHAOS] * 7))],
                         "khaos-wk1", log=quiet) is None)
f = field_from_entries([("a", FieldApi(601)), ("b", FieldApi(601))],
                       "khaos-wk1", log=quiet)
check("the same deck seen twice appears once", len(f["decks"]) == 1, f)
f = field_from_entries([("a", FieldApi(None, named=700))], "khaos-wk1",
                       log=quiet)
check("not entered but the named deck exists -> used, so a field can be "
      "described before anyone is in it",
      f is not None and f["decks"][0]["deck_id"] == 700, f)
check("a league we cannot see contributes nothing",
      field_from_entries([("a", FieldApi(601, rows=[]))], "khaos-wk1",
                         log=quiet) is None)

print("7e. key_from(): AB_API_KEY, and never the wrong variable")
import tempfile
with tempfile.TemporaryDirectory() as td:
    good = os.path.join(td, "good.env")
    open(good, "w").write("# a comment\nABAGENT_DECK_ID=5\nAB_API_KEY='sek ret'\n")
    check("reads AB_API_KEY and strips its quotes", key_from(good) == "sek ret",
          repr(key_from(good)))
    bad = os.path.join(td, "bad.env")
    open(bad, "w").write("ABAGENT_API_KEY=nope\n")
    check("ABAGENT_API_KEY is NOT the variable -> None, which the caller "
          "must report rather than skip silently", key_from(bad) is None)
    check("a missing file is None, not an exception",
          key_from(os.path.join(td, "nope.env")) is None)

print("7f. an entry another loop owns is never refined")
from abagent.cli import cycle_owned, CYCLE_OWNED
check("main's Standard entry is cycle's, whatever week it is",
      cycle_owned("abagent", "standard-league-nov-22")
      and cycle_owned("abagent", "standard-league-nov-29"),
      CYCLE_OWNED)
check("main's OTHER entries are refinable -- cycle cannot write them",
      not cycle_owned("abagent", "pauper-league-nov-22")
      and not cycle_owned("abagent", "expansion-league-skeletron-s-invasion-nov-22"))
check("a bot's Standard entry is its own: no bot unit touches a league",
      not cycle_owned("abagent-rook", "standard-league-nov-22")
      and not cycle_owned("abagent-wren", "standard-league-nov-22"))
# The prefix must not match a name that merely CONTAINS it, or a future
# "pauper-standard-league" would be skipped for no reason.
check("the prefix anchors at the start",
      not cycle_owned("abagent", "x-standard-league-nov-22"))
# "abagent" is a prefix of every bot's name, so a substring test on the
# account would have frozen all nine Standard entries instead of one.
check("the account is matched whole, not as a prefix",
      not cycle_owned("abagent-ember", "standard-league-nov-22"))

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
STARTS = ["2026-11-23T04:00:00Z", "2026-11-24T04:00:00Z", "2026-11-25T04:00:00Z"]
p2 = parse({"slug": "s", "current_day": 2, "day_starts": STARTS})
check("day N's next boundary is day_starts[N]",
      p2.next_day_at == _iso("2026-11-25T04:00:00Z"), p2.next_day_at)
p3 = parse({"slug": "s", "current_day": 0, "day_starts": STARTS})
check("day 0 points at day 1's start",
      p3.next_day_at == _iso("2026-11-23T04:00:00Z"), p3.next_day_at)
p4 = parse({"slug": "s", "current_day": 3, "day_starts": STARTS})
check("the last day has no next boundary", p4.next_day_at is None, p4.next_day_at)
check("a malformed stamp does not raise",
      parse({"slug": "s", "current_day": 0,
             "day_starts": ["not a date"]}).next_day_at is None)

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
    check("day 0 boundary = the advertised Sun 20:00 PST start",
          all(l.next_day_at == _iso("2026-11-23T04:00:00Z") for l in got.values()),
          [l.next_day_at for l in got.values()])

print(f"\n{len(fails)} failure(s)" if fails else "\nall assertions pass")
sys.exit(1 if fails else 0)
