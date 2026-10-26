#!/usr/bin/env python3
"""Add play-counting hooks to a COPY of the engine. Never the real one.

WHY A PATCH SCRIPT AND NOT A PATCH FILE. The engine moves constantly -- three
versions in one day on 2026-10-25 -- and a context diff would break on every
one of them. These three edits are anchored on text that has been stable
across many versions, and the script fails loudly if an anchor is missing
rather than silently producing an engine that counts nothing.

WHY NOT JUST ADD IT TO THE ENGINE. shell/engine belongs to autobattle.online,
which this project is pull-only against. The hooks live here, get applied to
an archived copy, and the build verifies the result is behaviourally identical
to the unpatched engine before any number it produces is believed.
"""
import re
import sys

SRC = sys.argv[1]
s = open(SRC, encoding="utf-8").read()


def edit(old, new, why):
    global s
    if s.count(old) < 1:
        sys.exit(f"census patch: anchor missing for {why}\n  wanted: {old[:70]!r}")
    s = s.replace(old, new, 1)


edit("\tctx *gameCtx\n",
     "\tctx *gameCtx\n\tcensusKey string // census build only\n",
     "the per-deck key on player")

edit("""	ps[0].ctx, ps[1].ctx = ctx, ctx
	ps[0].opponent, ps[1].opponent = ps[1], ps[0]""",
     """	ps[0].ctx, ps[1].ctx = ctx, ctx
	ps[0].censusKey, ps[1].censusKey = CensusKeyFor(ca), CensusKeyFor(cb)
	ps[0].opponent, ps[1].opponent = ps[1], ps[0]""",
     "tagging each side with its deck")

# The one place a card is committed to play, reached from hand AND from the
# gravecast-from-discard path, so both are counted.
edit("\t\tap.gc().iidCounter++\n",
     "\t\tcensusRecord(ap.censusKey, cid)\n\t\tap.gc().iidCounter++\n",
     "the play commit point")

edit("func playOneCard(", '''var censusMu sync.Mutex
var censusPlayed = map[string]map[int]int{}

func censusRecord(key string, cid int) {
	if key == "" {
		return
	}
	censusMu.Lock()
	m := censusPlayed[key]
	if m == nil {
		m = map[int]int{}
		censusPlayed[key] = m
	}
	m[cid]++
	censusMu.Unlock()
}

// CensusDump returns played-counts per deck key.
func CensusDump() map[string]map[int]int {
	censusMu.Lock()
	defer censusMu.Unlock()
	out := map[string]map[int]int{}
	for k, v := range censusPlayed {
		c := map[int]int{}
		for id, n := range v {
			c[id] = n
		}
		out[k] = c
	}
	return out
}

// CensusKeyFor fingerprints a deck so a caller can map counts back to a slot.
func CensusKeyFor(cards []int) string {
	cp := append([]int(nil), cards...)
	sort.Ints(cp)
	h := fnv.New64a()
	for _, c := range cp {
		fmt.Fprintf(h, "%d,", c)
	}
	return fmt.Sprintf("%016x", h.Sum64())
}

func playOneCard(''', "the census itself")

m = re.search(r"^import \(\n(.*?)^\)\n", s, re.S | re.M)
if not m:
    sys.exit("census patch: could not find the import block")
block = m.group(1)
for want in ['"hash/fnv"', '"sort"', '"sync"', '"fmt"']:
    if want not in block:
        block = block.rstrip() + "\n\t" + want + "\n"
s = s[:m.start(1)] + block + s[m.end(1):]

open(SRC, "w", encoding="utf-8").write(s)
print("census patch: applied")
