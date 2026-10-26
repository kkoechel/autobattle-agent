#!/usr/bin/env python3
"""How much of each league deck never reaches the board?

  report.py <census_bin> <cards.json> <meta.json> [opponents] [seeds]

Static analysis cannot answer this. A card can be legal, on-theme and still
never be cast because its gate never opens, its cost floor is out of reach, or
the deck simply never gets that far down its own play order. This plays real
matches and counts what was played.
"""
import collections
import json
import random
import subprocess
import sys
import tempfile

census_bin, cards_path, meta_path = sys.argv[1], sys.argv[2], sys.argv[3]
n_opp = int(sys.argv[4]) if len(sys.argv) > 4 else 20
n_seed = int(sys.argv[5]) if len(sys.argv) > 5 else 11

cards = json.load(open(cards_path))
cat = {c["id"]: c for c in cards}
decks = [d for d in json.load(open(meta_path))["decks"] if d.get("cards")]


def flat(d):
    out = []
    for e in d.get("cards") or []:
        cid = e["card_id"] if isinstance(e, dict) else e
        q = e.get("quantity", 1) if isinstance(e, dict) else 1
        out += [cid] * q
    return out


slots, matches = [], []
for i, d in enumerate(decks):
    slots.append({"slot_id": f"d{i}", "cards": flat(d),
                  "battle_plan": d.get("battle_plan") or {}, "champion_id": None})
rng = random.Random(7)
for i in range(len(decks)):
    others = [j for j in range(len(decks)) if j != i]
    for j in rng.sample(others, min(n_opp, len(others))):
        for s in range(2000, 2000 + n_seed):
            matches.append({"match_id": f"d{i}|d{j}|{s}",
                            "slot_a": f"d{i}", "slot_b": f"d{j}", "seed": s})

payload = {"cards": {str(c["id"]): c for c in cards},
           "slots": slots, "matches": matches, "chaos_effect": ""}
with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
    json.dump(payload, fh)
    path = fh.name
print(f"{len(slots)} decks, {len(matches)} matches", file=sys.stderr)

out = json.loads(subprocess.run([census_bin, path], capture_output=True,
                                text=True, check=True).stdout)
played = {k: {int(c): n for c, n in v.items()} for k, v in out["played"].items()}
keys = out["slot_keys"]

rows, never_tally = [], collections.Counter()
for i, d in enumerate(decks):
    pl = played.get(keys.get(f"d{i}"))
    if pl is None:
        continue
    cnt = collections.Counter(flat(d))
    never = [c for c in cnt if pl.get(c, 0) == 0]
    for c in never:
        never_tally[c] += 1
    rows.append((d.get("rank"), d.get("deck_name"), d.get("win_pct"),
                 sum(cnt[c] for c in never), len(never), cnt, never))

dead = [r[3] for r in rows]
print(f"\n{len(rows)} decks censused")
print(f"dead copies per deck: mean {sum(dead)/len(dead):.1f} of 100, "
      f"max {max(dead)}, decks with none: {sum(1 for x in dead if x == 0)}")

rows.sort(key=lambda r: -r[3])
print("\nworst:")
for rank, name, pct, cop, dis, cnt, never in rows[:10]:
    print(f"  rank {str(rank):>3}  {str(name)[:26]:28} {pct:5.1f}%  {cop:3} dead")
    for c in sorted(never, key=lambda c: -cnt[c])[:3]:
        print(f"        {cnt[c]:3}x {cat.get(c, {}).get('name', c)[:30]}")

print("\ncards most often dead where they appear:")
for c, n in never_tally.most_common(10):
    cc = cat.get(c, {})
    print(f"  {cc.get('name', c)[:30]:32} cost {cc.get('cost'):>3}  dead in {n} decks")

top = [r for r in rows if r[0] and r[0] <= 20]
rest = [r for r in rows if r[0] and r[0] > 20]
if top and rest:
    print(f"\ntop-20 decks: {sum(r[3] for r in top)/len(top):.1f} dead copies; "
          f"the rest: {sum(r[3] for r in rest)/len(rest):.1f}")
