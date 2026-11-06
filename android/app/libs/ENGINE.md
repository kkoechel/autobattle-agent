# engine.aar

Gitignored, because it is a 2MB binary — so this file records what the
checked-out tree expects, and `tools/check_engine_parity.sh` verifies the
built artefact against the live league before a release.

| | |
|---|---|
| engine version | **4.59** |
| built from | `autobattle.online` commit `a99ddd3`, `Engine v4.59: a discarded card leaves the hand before it arrives` (engine subtree `f3b1485`) |
| built on | 2026-11-06 |
| built by | `shell/engine/build_aar.sh`, extracted with `git archive` so the main repo is never touched |

## Build it from the engine source at the version the league scores with

```sh
cd /path/to/autobattle.online
git archive <commit> shell/engine | tar -x -C /tmp/eng
cd /tmp/eng/shell/engine && bash build_aar.sh engine.aar
```

**Do not build from HEAD without checking.** On 2026-10-18 HEAD was
`Engine v4.43 ... BUILT, NOT DEPLOYED` — shipping it would have put players
on an engine *ahead* of the one scoring them, which is the same fault as a
stale AAR in the other direction. The target is whatever
`validate_linux_amd64 --version` reports on subgames-nyc1.

**It moves under you.** Between the morning and the evening of 2026-10-18
the league went 4.42 → 4.44, and the only reason the release did not ship a
stale engine is that `tools/check_engine_parity.sh` runs before the bundle.
Run it every time; the gap opens silently.

It happened again on 2026-11-01: the AAR was rebuilt to 4.54 on 10-31 from
the two commits named at the time, and v4.55 (`9d2980e`) shipped the next
day, so the bundle built on 10-31 was already one version behind before it
was uploaded. The engine's own commit is what to record — not HEAD, which
by 11-02 was two unrelated commits further on with the same engine tree.

**And again on 2026-11-03 — three builds, three staleness events, in four
days.** 4.54 built 10-31, stale 11-01. 4.55 built 11-02, stale 11-03 at
15:33 when v4.56 (`cf16f98`) shipped. 4.56 built 11-03. The lesson is now
quantified: **the expected shelf life of a build here is about one day.**
Treat `check_engine_parity.sh` as a pre-upload step, not a post-build one.

## 2026-11-06: the gap reached players, and it carried a crash

The paragraph above used to end "not one of the three was ever uploaded, so
the gap has never actually reached a player". That is no longer true. The
11-03 bundle — versionCode 41, engine **4.56** — was uploaded to Play, and by
11-06 the league scored with **4.59**. So the thing every note in this file
was written to prevent happened anyway: players ran an engine three versions
behind the one deciding their results, for three days.

It was not only a parity gap. v4.59 (`a99ddd3`) fixed a **panic**: both Go
engines sent a hand card to the discard pile and only then cut it out of the
hand by its remembered index, so a card whose `on_discard` discards again left
that index past the end of the hand. That recursion only became reachable in
4.56 itself (`engine.go:1108` — "every other effect resolves like any
trigger's; they were dropped silently before"), so **4.56, 4.57 and 4.58 all
carry it** and 4.56 is the version that opened it. On the server it failed
every Standard Arena Phase 1 shard holding deck 144280 from 03:04 UTC. On the
phone it is worse: `engine.go` has no `recover()` and Kotlin calls
`Mobile.run()` unwrapped, so the panic aborts the process instead of raising
an exception.

Reproduced rather than inferred, with a synthetic payload (a forced hand
discard of a card whose `on_discard` discards again):
`4.56 -> panic: slice bounds out of range [10:9]` at `engine.go:7758`;
`4.59 -> exit 0`. Note that the committed test payload in
`androidTest/assets/` is from 2026-09-24 and hashes **identically** under 4.56
and 4.59 — it contains nothing that exercises 4.57, 4.58 or 4.59, so passing
it proves no collateral change but says nothing about the fix. A regression
suite that only runs old fixtures cannot see a new bug.

The practical consequence for `versionCode`: 41 is **spent**. It stayed 41
across three builds on the reasoning that the rule against reuse is about
binaries *in circulation* — correct reasoning, but the note asserting "not in
circulation" aged into a false one while the next build was still reading it.
From here: assume a built code is spent unless the Play console says
otherwise. 42 is engine 4.59, versionName 1.31.
