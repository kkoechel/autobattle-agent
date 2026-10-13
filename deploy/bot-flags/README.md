# Agent dispositions

Four agents running one algorithm on partitioned data still converge —
partitioning only delays it. Each of these gives an agent a different
temperament, so they explore differently rather than explore different
corners identically.

Theme and seed partitions keep them off each other's ground; the flags below
decide how each one *behaves* on the ground it has.

| agent | temperament | the bet |
|---|---|---|
| quill | **new cards only** | seeds solely from cards added in the last 14 days, with the measured and combo generators off. Tests what just shipped, before anyone has built around it. No seed partition: it should see every new card, not a quarter of them. A low floor (0.45) because a new-card deck has to be allowed to place badly — refusing to install anything that screens under the established decks is how a new card never gets tried at all |
| ember | **contender** | the one agent chasing rank 1 rather than novelty. Patient as before — 0 rotations, refines hard — plus a counter round one cycle in three that hunts its worst matchups. It keeps the 50/100 novelty cap: the deck at rank 1 is itself novel (26/100), so beating it never required copying anything |
| vane  | contrarian | overlap capped at 30 instead of 50. Forced further from the field than anything else we run |
| rook  | wide net | screens twice the candidates at a low bar. Tests whether the floor is throwing away good decks |

Cross-agent novelty needs no configuration: each agent's exemption set is only
its own deck, so every other agent's deck sits in the field and is checked at
that agent's own overlap cap. No agent can clone another.

## Second wave: the non-standard arenas (2026-10-13)

The first four all play `pure` (Standard). These four do not, which is a
different kind of experiment: Standard's 664-card pool is the one the whole
field optimises against, and the restricted arenas are much less worked over.

| agent | arena | field | legal cards | temperament |
|---|---|---|---|---|
| pike  | pauper | 22 | 169 | **contender** — beat the #1 deck specifically |
| wren  | pauper | 22 | 169 | explorer, partitioned off pike |
| sable | journeyman | 80 | 415 | explorer; the largest non-standard field |
| flint | chaos | 29 | 664 | explorer, overlap capped at 40 |

### Why these four arenas and not the other three buildable ones

`legendary` has a three-deck field: placement there is almost entirely noise
and there is nearly nothing to discover. `bot_challenge` is a 55-deck field of
bots, so it measures how we do against the house rather than against players.
`tournament_*` are opt-in per event, not standing.

### What Pauper actually asks for

Pauper's #1 on 2026-10-13 is **AD&D (FryGuy2), 21-0-0, 100%** — 100 cards, 8
distinct, and every single one costs 1. The best shell the generators can
build cold screens **19.0W of 22 and goes 0-3-0 against AD&D**. That is the
same shape as ember vs Novice Rabble 93 in Standard: the leader is not a
generally better deck, it is a matchup. Hence pike runs `--contend`, which
screens candidates against the worst matchups alone — where the effect is
undiluted — while still accepting only on total wins against the whole field.

### Overlap caps are arena-specific

`MAX_OVERLAP = 50` was calibrated on `pure`, where 664 cards are legal and the
field's median twin shares 6. It does not transfer:

| arena | legal cards | field-vs-field overlap (median / p95) | share of our candidates passing at 30 / 40 / 50 |
|---|---|---|---|
| pauper | 169 | 26 / 39 | 40% / 80% / 94% |
| journeyman | 415 | 24 / 38 | 24% / 86% / 99% |
| chaos | 664 | 15 / 32 | 77% / 96% / 99% |

So Pauper and Journeyman keep the default 50 — vane's contrarian 30 would
reject 60% and 76% of everything buildable there respectively, which is not
originality, it is an agent that never writes. Chaos can afford 40.

## Tournaments

All eight agents also enter the daily tournaments, via
`abagent-tourney@<name>.timer` (every six hours).

Tournament registration is the one kind that is **not** standing:

```php
$standing = empty($arena['is_tournament']);      // api/v1/index.php
```

so it writes `cohort_deck_entries` for the open cohort and nothing durable.
One call buys one event. Each tournament runs daily with a 24h presubmission
window, so there is always exactly one open cohort and any cadence under 24h
catches every event; re-entering the same deck only moves `registered_at`.
Entry is free — no autogold is spent on either the v1 or the `api/cohort.php`
path, which matters because these accounts hold 0 AG.

| tournament | closes | rule | who enters |
|---|---|---|---|
| Nightly Standard | 00:00 UTC | none | all eight |
| Pauper Tournament | 06:00 UTC | common + infinite | pike, wren |
| Champion Standard | 12:00 UTC | requires a champion | **nobody** — no generator sets a champion_id |
| Daily Standard | 18:00 UTC | none | all eight |

### What this does to the tournament fields

Worth knowing before reading a placement as an achievement: these events were
already mostly bots. The Daily Standard tournament we joined held **two humans
and seven site filler bots**. Our eight agents are now the largest block in it,
so an agent winning a daily tournament has mostly beaten filler — and a human
entering now faces eight tuned decks where there were seven weak ones.
