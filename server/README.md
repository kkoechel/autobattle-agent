# AutoBattle Agent game — coordinator

Live at `https://agent.autobattle.online` on subgames-nyc1, alongside Reprisal,
Ossuary, Mage Wars and Blind Wizards. Own database (`agentgame`), own vhost,
own Let's Encrypt cert, following the house pattern for that box.

## What this is, and what the phone is

The phone does the expensive half — it builds and iterates a deck on-device
with the real engine. This service only holds the state one phone cannot: who
is playing, their current deck, and how the last round went.

**Standings are computed here, not reported by clients.** At beta scale a
round-robin is a few thousand matches and seconds of CPU, so computing it
server-side means there is no result to forge and no consensus protocol to get
wrong. Cost is O(decks²): 8 decks ≈ 1.2k matches, 50 ≈ 51k, 100 ≈ 208k. Around
a hundred players that stops fitting in the window, and sharding scoring out to
the phones — which the main game's shard/consensus machinery already shows how
to do — is what replaces it.

## It cannot touch the main game

By construction, not by discipline. There is no autobattle.online connection
string anywhere in this tree. The card catalogue is a FILE, refreshed by
`bin/sync_cards.sh`, which copies abagent's already-pulled
`/opt/abagent/var/cards.json` — so this service needs no credential for the
main game and cannot write to it even by accident.

## Endpoints

| route | auth | purpose |
|---|---|---|
| `POST /api/join` | — | `{handle}` → a bearer token, shown **once** (only its SHA-256 is stored) |
| `GET /api/me` | bearer | who am I, and my current deck revision |
| `PUT /api/deck` | bearer | submit a deck; validated server-side |
| `GET /api/field` | bearer | everyone's current deck, so the phone can iterate against the real opposition |
| `GET /api/standings` | — | the latest finished round |
| `GET /api/cards` | — | the forked catalogue + ban list |
| `POST /api/round` | admin | written by `bin/round.py` |
| `GET /api/health` | — | card/player/deck counts |

Deck legality is checked **here**, because the client is a phone the player
controls: exactly 100 cards, every id known, no duplicate entries, per-card
count within `deck_limit`, nothing banned. Verified against known-bad decks —
99 cards, 101 copies, unknown id, duplicate entry and negative quantity are all
rejected; a legal deck is accepted.

## Pieces

- `public/index.php` — the whole API. DocumentRoot is `public/` so `inc/`
  (which holds the DB password) is not reachable by URL; confirmed 404.
- `inc/boot.php` — config, DB, catalogue, auth, deck validation.
- `bin/round.py` — scores a round-robin. A privileged *client* of the API
  rather than a second thing that knows the schema, so all DB access stays in
  one file. Reuses abagent's `Harness`, so the validator payload is built by
  the code the agents have used for weeks instead of a reimplementation.
- `bin/sync_cards.sh` — pulls the catalogue; validates before replacing, since
  a truncated file would make every deck illegal.

## Timers

`agentgame-round.timer` every 15 min, `agentgame-cards.timer` hourly.

## Not in v1

Bans (the table exists so the rotating pool needs no migration), card
submission, and any LLM. See the project memory for why.
