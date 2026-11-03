# engine.aar

Gitignored, because it is a 2MB binary — so this file records what the
checked-out tree expects, and `tools/check_engine_parity.sh` verifies the
built artefact against the live league before a release.

| | |
|---|---|
| engine version | **4.56** |
| built from | `autobattle.online` commit `cf16f98`, `Engine v4.56: a card's own on_discard trigger runs any effect` (engine subtree `aaccb03`) |
| built on | 2026-11-03 |
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
15:33 when v4.56 (`cf16f98`) shipped. 4.56 built 11-03. Not one of the three
was ever uploaded, so the gap has never actually reached a player — but that
is the parity check doing its job, not luck, and the lesson is now
quantified: **the expected shelf life of a build here is about one day.**
Treat `check_engine_parity.sh` as a pre-upload step, not a post-build one.

The practical consequence for `versionCode`: it has stayed 41 across all
three builds, because the rule against reuse is about binaries *in
circulation* and none of these entered it. The moment one is uploaded, the
next build bumps.
