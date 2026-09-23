# abagent

An automated player for [autobattle.online](https://autobattle.online).

AutoBattle is an *auto*battler: there are no turns to play. A deck's entire
decision surface is its 100-card list and its **battle plan** — about fifteen
standing-instruction fields the engine reads while it plays for you. So this
is not a bot that plays matches; it is a search over decks and plans, with an
offline copy of the real engine as the fitness function.

## How the loop works

| stage | what it uses | cost |
|---|---|---|
| get the field | `GET /meta?arena=pure` — the latest finalized cohort's 70 decks, card lists and battle plans, already shaped like the engine's `slots[]` | one request |
| score a candidate | the `validate` binary, `--local-payload`, offline | ~155 matches/sec/core |
| confirm it | `PUT /decks/{id}` + `POST /cohort/register`, then `GET /results` | ~10 min |

The validator is the same engine that scores live cohorts, so an offline
result matches the real game exactly for a given seed. Measured against the
live Standard standings, a full 70-deck round robin reproduces each deck's
record to within a few wins — the residual is seed choice, not model error.

## Why the statistics are the hard part

A cohort is 69 matches with one seed per pairing, and a deck's win count has a
**single-cohort standard deviation of about 6 wins**. That is the number that
governs the whole design:

- Every candidate in a sweep plays the *same* seeds against the *same*
  opponents, so comparisons are paired and most of that variance cancels.
- Nothing is accepted on a sweep result. The sweep's winner is re-measured
  against the incumbent on **fresh** seeds, because picking the best of ~36
  noisy estimates systematically overestimates it.
- The confirmation is a paired t-test with a `--min-t` threshold, not a check
  on the sign of the difference. At 21 seeds the standard error is ~1.7 wins,
  so a "+0.4 wins, therefore better" rule accepts coin flips. Stacking a few
  of those tunes the plan to the seed block instead of to the game.

Fitness is `(wins, -losses)` because that is exactly how cohorts rank: wins
descending, then losses ascending — verified against a live 70-deck cohort
with zero violations across all 69 adjacent pairs.

Draws matter. Standard runs about **27% draws**, and they are concentrated in
the middle of the table: top decks draw 6–7 of 69, bottom decks 15–16, the
field average 18.8. A mid-table deck's path upward runs mostly through
converting draws into wins, which a wins-minus-losses fitness would miss.

## Usage

```bash
export AB_API_KEY=...                       # from /game/profile.php

python3 -m abagent.cli fetch                # validator (sha256-verified) + cards + meta
python3 -m abagent.cli baseline             # score the active deck against the field
python3 -m abagent.cli climb --apply        # tune the battle plan, write it back
python3 -m abagent.cli results              # recent placements
python3 -m abagent.cli cycle                # one unattended pass (what the timer runs)
```

`--arena` defaults to `pure` (Standard); `--deck-id` defaults to your active
deck.

## Deployment

`./deploy.sh` installs to `/opt/abagent` on subgames-nyc1 and runs `cycle`
on a systemd timer at `*:06/10` — just after Standard finalizes at ~:05:41,
leaving ~9 minutes before the next close.

That droplet is **1 vCPU, 2GB, no swap**, and it also serves live Reprisal,
Ossuary, Mage Wars and Blind Wizards. So the unit uses `CPUWeight=20`
(proportional — yields under contention, uses the idle core otherwise) rather
than a hard `CPUQuota`, and sets `MemoryMax=600M`, because with no swap an
unbounded process is an OOM rather than a slowdown.

## Things that will bite you

- **`is_valid` is not legality.** It means 90–100 cards, nothing more.
  `PUT /decks/{id}` checks neither ownership nor per-card `deck_limit`.
- **Nothing in the cohort path checks card ownership**, so an agent-built deck
  plays normally regardless. But the *website* save path
  (`api/decks.php`, `min($need, $owned, $deck_limit)`) does clamp — so a deck
  built here collapses the first time a human opens it in the web deck builder
  and saves. Unless the account has `has_all_cards`, in which case that path
  doesn't clamp either.
- **Use `tags`, never `ability_tags`.** `tags` is what the engine matches tag
  effects against; `ability_tags` is a display-only derived set that differs on
  342 of 859 cards. Getting it wrong doesn't error — tag effects just quietly
  find no targets and you get confident, wrong numbers.
- **Fetch the full card catalogue.** `?fields=slim|rules` drops `effects_json`
  and `stats_json`, which the engine needs, and `?playable=1` drops token
  cards — a deck that creates tokens silently no-ops without their definitions.
- **Arena `rules_json` is not exposed by the API.** Pauper's rarity cap,
  Champion's champion requirement and Beginner's whitelist are invisible to a
  client. Standard's is empty, so this is only a problem when moving arenas.
- **`play_priority` has three silent no-ops.** `card_order`, `type_order` and
  `tag_order` each sort the hand only `if len(plan.X) > 0`. Set one without its
  companion list and the engine does nothing at all — the hand keeps draw
  order. Nothing errors. A search that offers these values unseeded is testing
  `draw_order` three times under three different names, and will report the
  result under whichever name it tried first. This cost us a real finding: the
  unseeded version measured +3.3 wins and named it `card_order`; seeded with an
  actual ranking, the same field is worth **+17.4 wins (t=11.3)**.

## Status

Shipped: the harness, the plan search, the cycle, the deployment.

Next: the LLM proposer. The card list is untouched so far — everything above
tunes standing instructions only, which was deliberate, to find out how much
of the gap is plan and how much is cards before spending a token on card
design.
