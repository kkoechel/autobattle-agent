# AutoBattle Agent (Android)

An advisor for [autobattle.online](https://autobattle.online). It analyses the
player's deck against the live field, recommends changes, and explains them.
The player decides; the app never plays for them.

## Why the engine runs on the phone

AutoBattle ships its simulation engine, and it is the same engine that scores
live cohorts — so an offline result matches the real game exactly for a given
seed. Running it locally means no server, no per-user compute cost, and no
round trip.

Measured on a Galaxy S22 (8 cores), engine v3.94:

| workload | 1 worker | 4 workers |
|---|---|---|
| analyse a deck vs the field (1,491 matches) | 6.8s | **1.0s** |
| screen 40 candidate decks (20,160 matches) | 128s | **24s** |

Pass `workers = 4`, not `NumCPU()`. The S22 is 1 prime + 3 big + 4 little
cores and the little ones bought ~4% for roughly twice the power.

## Engine dependency

`app/libs/engine.aar` is built from `shell/engine` in the autobattle.online
repo:

```
cd ~/Documents/autobattle.online/shell/engine && ./build_aar.sh
cp engine.aar ~/Documents/autobattle-agent/android/app/libs/
```

Needs NDK r27c and JDK 17. `build_aar.sh` documents a non-obvious toolchain
workaround — gomobile writes its own go.mod asking for a Go version that
cannot be downloaded.

It is gitignored on purpose. A committed binary drifts from the engine it was
cut from, and this one carries a digest that must match what the game's own
validator produces.

```kotlin
val env = JSONObject(Mobile.run(payloadJson, 4L))
env.getString("hash")            // consensus digest
env.getJSONArray("results")      // one entry per match
Mobile.version()                 // "3.94"
```

## Relationship to the Python agent

`../abagent` is the same ideas running server-side: it took a mid-table deck to
rank 1 on the live ladder using this engine as a fitness function. The Go port
in steps 3-4 moves its harness, paired significance test, archetype generator
and mutation onto the phone, and each is validated by reproducing a number the
Python already produces rather than by looking reasonable.

## The test that matters

`EngineParityTest` asserts the digest computed **inside the app on the device**
equals the one `validate --local-payload` produces on x86. Everything else was
already verified on the Go side; this is the only check that covers the JNI
bridge, where a mangled string or a truncated result would pass every Go test
and still ship wrong numbers.

```
./gradlew :app:connectedDebugAndroidTest
```

Verified to fail when the expected digest is altered — a parity test that
cannot fail is not a parity test. Re-derive the constant from the CLI, never
from what the app currently returns, whenever the engine changes.
