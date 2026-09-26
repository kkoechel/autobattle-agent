# Bot accounts for the LLM agents — spec for the autobattle.online side

Written for whoever works the site repo. Grounded in github/master @ 7b19b13.

## The ask

Eight bot accounts usable by the deck agents, to run alongside the two that
exist. Each needs:

- a working `api_key`
- **`is_api_user_self = 1`** — and specifically NOT `is_api_user`; see below,
  this is the part that matters
- one deck it owns (its existing deck is fine — the agent rewrites it)

No Double Entry Pass needed. Each agent writes the account's own single deck
rather than a rental slot, which is simpler and saves 177 AG/week apiece.

Phased: four now, four once the interaction sweep drops its cadence. The
droplet is one core and currently samples at 86% busy; each explorer costs
~1.7% of it, and the sweep frees ~17% when it finishes its first pass.

## Do NOT grant is_api_user to these accounts

This is the whole reason this is a spec and not a ticket.

`require_discord_bot()` (api/v1/index.php:182) is:

```php
if (!str_ends_with($email, '@autobattle.bot') || empty($authed_user['is_api_user'])) {
    fail(403, 'Discord bot service account required');
}
```

Bot accounts already satisfy the first clause — bots are identified precisely
by that email domain, everywhere in the codebase. So setting `is_api_user = 1`
on a bot account satisfies **both** clauses and hands it the Discord bot
routes:

| route | what it does |
|---|---|
| `route_discord_link` (620) | binds a `discord_id` to a **user account** |
| `route_discord_decks_get` (628) | reads decks |
| `route_discord_results` (633) | reads results |

`route_discord_link` writes `users.discord_id` and explicitly moves an
existing link ("last write wins"). Eight keys that can rebind Discord identity
to arbitrary accounts is not what "let the agent edit its own deck" should
buy, and the comment above that function already says the general flag must
never reach these routes.

`is_api_user_self` is exactly right instead: it grants the four self-scoped
deck routes and nothing else — no `POST /decks/slots` (no autogold), no
Discord routes, because `require_discord_bot()` checks `is_api_user` alone.
The split gate you built last week is what makes this safe, and this is its
first real use.

## Two things to confirm before implementing

**1. Do bot `users` rows have `api_key` populated?** Every user row has the
column, but if bots were created without one they will need generating. If
they already have keys, they just need retrieving.

**2. Bots are excluded from the leaderboard — is that a problem for this
experiment?** Bots are filtered out by email domain in at least five places:

```
api/leaderboard.php:181,424,442,488   AND u.email NOT LIKE '%@autobattle.bot'
inc/players.php:32                    WHERE u.email NOT LIKE '%@autobattle.bot'
inc/arena_eligibility.php:192         AND u.email NOT LIKE '%@autobattle.bot'
```

They still **play in cohorts** and still appear in `GET /meta` and
`GET /results` with a rank, which is what the agents read and what the
experiment measures. But they will not show on the public leaderboard.

kkoechel's stated goal is "testing where the LLM enabled bots end up in the
rankings". If rankings means cohort placement, this works as is. If it means
the public leaderboard, something has to change — and that is a design call
about whether agent-run bots should be publicly ranked alongside players, not
something to decide in passing.

Worth also checking `inc/arena_eligibility.php:192` specifically: if that
exclusion gates entry to some arenas rather than just display, these accounts
may not reach every arena we want to test in.

## Why eight, and what they will do

The two existing agents were converging — they held ranks 1 and 2 with decks
sharing 97 of 100 cards — which is now prevented by a novelty constraint
capping overlap with any deck we do not own at 50/100. Each agent's exemption
set is only its own decks, so agent A cannot clone agent B.

For calibration, the live field is already fairly convergent on its own: 29 of
75 decks (39%) have a >50/100 twin, and 17 are beginner-deck lookalikes. So
"the agents converged" only means something measured against a 39% baseline.

Ten agents on a 75-deck field is ~13% of it, which does change what the
experiment measures — increasingly how these algorithms fare against each
other rather than against players. kkoechel has accepted that tradeoff.

## Handing over the keys

Eight keys is more than fits comfortably in a chat message. Anything that
lands them on the droplet as `0600` env files owned by the agent user is
fine; the existing pattern is one `/etc/abagent-<name>.env` per agent
containing `AB_API_KEY` and the deck id.
