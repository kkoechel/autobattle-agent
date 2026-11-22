import json, subprocess, sys
sys.path.insert(0, "/home/hhkk/Documents/autobattle-agent")
from abagent.harness import Harness, opponents_from_meta

A = "/home/hhkk/Documents/autobattle-agent/android/app/src/androidTest/assets"
VAL = "/home/hhkk/Documents/autobattle.online/shell/validate_linux_amd64"
ver = subprocess.run([VAL, "-version"], capture_output=True, text=True).stdout.split()[-1]

catalog = json.load(open(f"{A}/cards.json"))
meta = json.load(open(f"{A}/meta_pure.json"))
cand = json.load(open(f"{A}/candidate.json"))
old = json.load(open(f"{A}/reference.json"))

opps = opponents_from_meta(meta, exclude_deck_ids={cand["deck_id"]})
seeds = old["seeds"]
assert len(opps) == old["n_opponents"], f"{len(opps)} opponents vs {old['n_opponents']}"

sc = Harness(VAL, catalog).evaluate(
    [("C0", cand["cards"], cand.get("battle_plan") or {})], opps, seeds)["C0"]

new = {
    "seeds": seeds,
    "wins": sc.wins,
    "losses": sc.losses,
    "draws": sc.draws,
    "sd": sc.wins_sd,
    "n_opponents": sc.n_opponents,
    "wins_by_seed": {str(s): sc.wins_by_seed[s] for s in seeds},
    "_engine_version": ver,
    "_regenerated": "2026-11-21",
    "_note": ("Engine-version-bound. Produced by abagent's own harness via "
              "validate_linux_amd64; regenerate on an engine bump with "
              "tools/regen_reference.py, and record the version here."),
}
json.dump(new, open(f"{A}/reference.json", "w"), indent=1)
print(f"regenerated at engine {ver}")
print(f"  wins {old['wins']:.10f} -> {new['wins']:.10f}")
print(f"  sd   {old['sd']:.10f} -> {new['sd']:.10f}")
print(f"  seeds changed: {sum(1 for s in seeds if old['wins_by_seed'][str(s)] != new['wins_by_seed'][str(s)])} of {len(seeds)}")
print(f"  matches the phone's 63.666666666666664: {abs(new['wins'] - 63.666666666666664) < 1e-9}")
