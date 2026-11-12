# engine.aar

Gitignored, because it is a 2MB binary — so this file records what the
checked-out tree expects, and `tools/check_engine_parity.sh` verifies the
built artefact against the live league before a release.

| | |
|---|---|
| engine version | **4.61** |
| built from | `autobattle.online` commit `05e7256`, `Engine v4.61: a supplement can be paid with restricted energy` (engine subtree `857be5f`) |
| built on | 2026-11-11 |
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

## 2026-11-11: v4.60, and a bundle that went stale without being uploaded

Built from `6c7a6c2` (engine subtree `6dae313`) on the day it landed, on the
main session's request in `~/Documents/agent-handoff/to-agent.md`. versionCode
**43**, versionName **1.32**.

What 4.60 changes: `gain_restricted_energy` takes `exclude_supertypes`, so
energy can pay for anything that is *none* of the listed supertypes; and
matching restricted entries are now spent narrowest first, ephemeral before
persistent, then in gain order. No live card uses `exclude_supertypes` yet, so
that half changes no current result. The spend order does: 27 of 16,660 matches
in the main repo's whole-catalogue sweep, 8 of them changing the winner.

**versionCode 42 was never uploaded, and is now unuploadable.** It was built
11-06 with engine 4.59 and `cardbattle-agent-1.31-42.aab` is still in
`~/Downloads`; on 11-11 the league moved to 4.60, so sending 42 to Play would
put players one version behind again — the exact fault this file exists to
prevent, arriving this time through a bundle that simply *waited*. That is the
fourth staleness event in eleven days and the first caused by delay rather than
by building from the wrong commit. The shelf life noted above (about a day) is
not a rule of thumb about building; it is a rule about *uploading*.

### How this build was verified without a phone

`adb devices` was empty, so the on-device step did not run. What did run proves
the engine rather than the packaging: `cmd/mobilecheck` calls `mobile.Run`, the
same bind surface the AAR exposes, so comparing its results array against
`validate --local-payload` tests the library the app will actually call.

| fixture | v4.60 CLI | engine lib 4.60 | bind surface 4.60 | bind surface 4.59 |
|---|---|---|---|---|
| `build_v460_payload.py` | `8116798d…` | `8116798d…` | `8116798d…` | `02cfcfb1…` |
| `androidTest/assets/payload.json` | `e6ebcfa4…` | `e6ebcfa4…` | `e6ebcfa4…` | `e6ebcfa4…` |

Read both rows. The first says the v4.60 behaviour is in this binary *and* that
the fixture can tell 4.59 from 4.60 — a fixture that cannot is not evidence.
The second says nothing changed that should not have, and is identical under
both engines, so on its own it would have accepted a 4.59 AAR as a 4.60 one.
The main repo's `check_v460.js` passes all eight behaviour assertions on the
same payload.

Still unproven here, and only a phone can prove it: that the arm64 `.so` inside
the bundle is the one just built (checked by byte-comparison against the AAR,
below), and that the app runs a rehearsal end to end on the device.

## 2026-11-11, later the same evening: v4.61, versionCode 44

Built from `05e7256` (engine subtree `857be5f`). versionCode **44**,
versionName **1.33**. `PARITY OK` against a league already on v4.61 — the
droplet's validator was replaced at 00:56 UTC, about three hours after the
4.60 AAR was built. **versionCode 43 was therefore stale before it was
uploaded, exactly as 42 was.** Three bundles now sit in `~/Downloads` and only
`cardbattle-agent-1.33-44.aab` is uploadable.

v4.61 came from this side of the handoff: `Plan.kt` quoted the old supplement
reserve formula, checking the quotation against `can_pay_supplement` showed the
engine counted only ordinary energy where `can_afford` counts restricted energy
too, and the main session fixed it the same evening. The comment in `Plan.kt` is
still a quotation and not a second implementation — it is worth keeping true
for exactly this reason.

### What the parity fixtures can witness

`androidTest/assets/` now holds `payload_v460.json` and `payload_v461.json`
beside the 2026-09-24 `payload.json`, and `EngineParityTest` asserts a
CLI-derived digest for each. Measured across 4.59, 4.60 and 4.61:

| fixture | 4.59 | 4.60 | 4.61 |
|---|---|---|---|
| `payload.json` (1491) | `1b5e48ac…` | `1b5e48ac…` | `1b5e48ac…` |
| `payload_v460.json` (120) | `02cfcfb1…` | `b303ea88…` | `b303ea88…` |
| `payload_v461.json` (80) | `7a547e44…` | `7a547e44…` | `7a547e44…` |

Only the middle row separates anything, and only 4.59 from 4.60. **No digest
here can catch a 4.60 AAR**, because v4.61 changes which pool pays a supplement
and only changes *whether* it is paid when ordinary energy is short — in that
fixture ordinary energy accrues +1 a turn across 138 turns, so the payment
happens under both engines and only the per-play log differs. `check_v461.js`
reads that log and fails 2 of its 6 checks on a 4.60 engine; a result digest
cannot. Verified three independent ways: `cmd/mobilecheck` built from all three
trees, `cmd/parity` arrays compared with `cmp`, and the committed v4.60
validator binary (`git show 6c7a6c2:shell/validate_linux_amd64`) against the
v4.61 one. `run_sweep.py` reports Go, Python and JS agreeing 80/80 at 4.61, so
this is a fixture limitation and not a parity break.

The assertion that actually catches a stale AAR is therefore
`Mobile.version()`. It read **"3.94"** until today — seven weeks and nine
releases out of date, so the test had been failing for anyone who ran it and
witnessing nothing for anyone who did not. Both it and the digests must be
re-derived whenever the engine moves.
