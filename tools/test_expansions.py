"""Behaviour test for arenas.py's expansion rule.

Synthetic cases run anywhere. Pass a GET /cards payload as argv[1] to also
assert the measured per-expansion pools (the numbers quoted in arenas.py).

    python3 tools/test_expansions.py [cards.json]
"""
import json, sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
from abagent import arenas

KHAOS_C   = 10   # playable, khaos, common
START_C   = 11   # playable, starter, common
KHAOS_R   = 12   # playable, khaos, rare
BASIC     = 13   # playable, infinite, filed nowhere
RETIRED_K = 14   # khaos but retired -> not playable
UNFILED   = 15   # playable, common, NO expansion tag
TOKEN     = 16   # token rarity -> not playable

CAT = {
    KHAOS_C:   {"rarity": "common",   "deck_limit": 4, "tags": ["khaos", "common"]},
    START_C:   {"rarity": "common",   "deck_limit": 4, "tags": ["starter", "common"]},
    KHAOS_R:   {"rarity": "rare",     "deck_limit": 2, "tags": ["khaos", "rare"]},
    BASIC:     {"rarity": "infinite", "deck_limit": 100, "tags": []},
    RETIRED_K: {"rarity": "common",   "deck_limit": 4, "tags": ["khaos"], "is_retired": 1},
    UNFILED:   {"rarity": "common",   "deck_limit": 4, "tags": ["common"]},
    TOKEN:     {"rarity": "token",    "deck_limit": 4, "tags": ["khaos"]},
}

fails = []
def check(name, cond, detail=""):
    print(("  ok   " if cond else "  FAIL ") + name + ("" if cond else f"  <- {detail}"))
    if not cond: fails.append(name)

print("1. generation pool is STRICT: prove it legal or leave it out")
k = arenas.legal_catalog(CAT, "pure", {"khaos"})
check("keeps khaos cards", KHAOS_C in k and KHAOS_R in k)
check("keeps the infinite basic", BASIC in k)
check("drops another expansion", START_C not in k)
check("drops an UNFILED playable card", UNFILED not in k, sorted(k))
check("keeps unplayable rows for lookup", RETIRED_K in k and TOKEN in k, sorted(k))

print("2. no restriction leaves every caller untouched")
check("None == the old behaviour", arenas.legal_catalog(CAT, "pure") == CAT)
check("empty set is also no restriction", arenas.legal_catalog(CAT, "pure", set()) == CAT)

print("3. both rules compose (a Pauper league limited to khaos)")
pk = arenas.legal_catalog(CAT, "pauper", {"khaos"})
check("khaos common survives", KHAOS_C in pk)
check("khaos RARE is cut by pauper", KHAOS_R not in pk, sorted(pk))
check("infinite basic survives pauper+khaos", BASIC in pk)

print("4. judging is LENIENT: only a card filed elsewhere is forbidden")
bad = arenas.illegal_cards([KHAOS_C, START_C, BASIC, UNFILED, 9999], CAT, "pure", {"khaos"})
check("flags the foreign expansion", START_C in bad, bad)
check("does NOT flag the unfiled card", UNFILED not in bad, bad)
check("does NOT flag an unknown id", 9999 not in bad, bad)
check("does NOT flag the basic", BASIC not in bad, bad)
check("no restriction -> nothing flagged",
      arenas.illegal_cards([KHAOS_C, START_C], CAT, "pure") == [])

print("5. the CHECK half of declaring the list")
check("unfiled_playable finds it", arenas.unfiled_playable(CAT) == [UNFILED],
      arenas.unfiled_playable(CAT))
check("and does NOT cry wolf over an unfiled BASIC",
      BASIC not in arenas.unfiled_playable(CAT), arenas.unfiled_playable(CAT))

print("6. a typo is refused, not silently ignored")
try:
    arenas.legal_catalog(CAT, "pure", {"kaos"})
    check("ValueError on unknown expansion", False, "no raise")
except ValueError as e:
    check("ValueError on unknown expansion", "kaos" in str(e), str(e))

if len(sys.argv) > 1:
    print(f"7. against the real payload ({sys.argv[1]})")
    d = json.load(open(sys.argv[1]))
    cards = d["cards"] if isinstance(d, dict) else d
    cat = {int(c["id"]): c for c in cards}
    check("no playable card is unfiled", arenas.unfiled_playable(cat) == [],
          arenas.unfiled_playable(cat)[:10])
    # Measured 2026-11-18. These are cards FILED under each expansion; the
    # three infinite basics are themselves filed under `starter`, so starter's
    # count already contains them and the others do not. That is exactly why
    # the assertion below is the set invariant rather than "n + 3".
    EXPECT = {"starter": 423, "khaos": 202, "outworld": 194,
              "skeletron-inv": 179, "underworld": 36}
    from abagent.moves import is_playable
    basics = {cid for cid, c in cat.items()
              if is_playable(c) and c.get("rarity") == arenas.BASIC_RARITY}
    check("exactly 3 playable basics", len(basics) == 3, sorted(basics))
    for slug, n in EXPECT.items():
        pool = {cid for cid, c in arenas.legal_catalog(cat, "pure", {slug}).items()
                if is_playable(c)}
        filed = {cid for cid, c in cat.items()
                 if is_playable(c) and slug in arenas.expansions_of(c)}
        check(f"{slug}: {n} cards filed here", len(filed) == n, len(filed))
        check(f"{slug}: pool == filed + the basics, nothing else",
              pool == (filed | basics), len(pool))
        copies = sum(int(cat[cid].get("deck_limit") or 0) for cid in pool)
        check(f"{slug}: enough copies for a 100-card deck", copies >= 100, copies)

print(f"\n{len(fails)} failure(s)" if fails else "\nall assertions pass")
sys.exit(1 if fails else 0)
