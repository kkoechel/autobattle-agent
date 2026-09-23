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

First full result, on a real Standard deck, **changing no cards at all**:

```
start  cheapest / hold 5 / random targets      25.4W 25.4L 18.2D   ~rank 25
final  card_order[40] / least_armor targets    41.2W 14.9L 13.0D   ~rank 17
```

Two confirmed changes out of eight challengers, and the last sweep confirmed
nothing — so the *scalar* plan fields are close to spent. Of the two, one is
nearly all of it: giving the engine an explicit `card_order` instead of
`cheapest` is worth +17.4 wins on its own.

### Since then

`cycle` also searches the **play order and the card list together**, with
candidates proposed by the arena's own finalized cohort — 70 real decks with
full lists and the rank each finished at. Cards in ≥4 of the top 15 that we
do not run are add candidates; our cards no top deck runs are cut candidates.

That is hypothesis generation, not inference. Mean finishing rank per card is
badly confounded: most standouts are `deck_limit: 1` legendaries, so the decks
running them are also the better-resourced accounts. Every proposal still has
to clear the same paired t-test in our own deck.

**Acceptance is three stages, and the third is not optional.** Sweep (select
from ~16), confirm (narrow to 3 on a fresh block), then validate the single
survivor on seeds nothing was selected on, with no max taken over anything.
Skipping that third stage let a swap "confirm" at +3.8W, t=2.6 that was
actually worth +1.48W over 124 fresh seeds. Taking the best of several on the
confirmation block makes that block selection data — the winner's curse, one
level up from where it was first fixed.

`validate_seeds` is **161**, from measurement rather than taste. On that same
+1.48W swap: 21 seeds → t=0.86; 41 seeds → −0.88W at t=−0.54, *the wrong
sign*; 81 → +2.22W; 161 → +1.54W at t=2.45. A validation stage that cannot
resolve the effects reaching it rejects everything, which looks like rigour
and is really a broken instrument. It costs ~125s, so budget about two
accepted moves per cycle.

Two levers remain, in order:

1. **An LLM proposer.** The field mining does more of this job than expected —
   it cuts the candidate space from 524 cards to ~16 grounded proposals. What
   it cannot do is suggest a swap nobody in the field has tried, which is the
   narrower job left: read `rules_text` and the per-opponent record and
   propose what the standings do not already demonstrate.
2. **Multi-card moves.** Every swap so far is one card for one card. The
   distance from ~45W to the 60W at the top of the field is ~10 single swaps,
   or a smaller number of coordinated ones.

`Score.per_opponent` records the W/L/D against each of the 69 decks
individually and is still unread. That is the diagnostic worth feeding a
proposer — not "we win 60%" but "we go 0-5 against these three decks, and
here is what they play".
