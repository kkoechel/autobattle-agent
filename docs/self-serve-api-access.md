# Self-serve API access — spec for the autobattle.online side

Written for whoever is working on the autobattle.online repo. Nothing here
touches the Android app; the app side is already built and waits on this.

## Why

The Android advisor app ("AutoBattle Agent") runs the real engine on the
phone, reads the player's deck and the arena meta, and recommends changes. It
is an advisor: the player applies the change. Today they apply it by hand on
the website, because `PUT /decks/{id}` is gated.

Everything the app does **today** works on a plain key — `/me`, `/cards`,
`/meta`, `/results`, `/decks/{id}` read. The only thing missing is letting a
player opt themselves in so the app can eventually write the deck back.
**This is not blocking the app shipping.** It is the gate on "apply this
change" ever existing.

## Current state (verified against github/master @ 9ce363c)

`require_api_user()` — `api/v1/index.php:129`:

```php
if (empty($authed_user['is_api_user']) && empty($authed_user['is_admin'])
    && empty($authed_user['is_playtester'])) {
    fail(403, 'API access not enabled for your account — ask an admin to enable API User on your profile.');
}
```

It gates exactly five call sites:

| line | route | effect |
|---|---|---|
| 531 | `GET /decks[/{id}]` | read own decks |
| 562 | `POST /decks/slots` | **spends autogold** via `buy_deck_slot()` |
| 579 | `POST /decks` | create |
| 584 | `PUT /decks/{id}` | update |
| 589 | `DELETE /decks/{id}` | delete |

`is_api_user` is a `users` column, toggled by an admin in
`admin/players.php:227` / `inc/players.php`.

`game/profile.php:509` already has the panel to change: when the flag is off it
renders *"Your key currently only works for battle validation. Contact an admin
to enable full API access."* That copy is the thing that becomes a toggle.

## What is actually being granted

I checked rather than assumed, because the scoping is the whole question:

- `route_decks_get` (1693), `route_decks_put` (2187), `route_decks_delete`
  (2886) all select `WHERE id=? AND user_id=?` before doing anything.
- `route_decks_post` (2152) creates against the caller's `$userId`.

So the deck routes are **self-scoped**: enabling the flag lets a player act on
their own decks and nobody else's. It is an access gate, not a safety one.

**The exception, and the reason this needs a decision rather than just a
toggle:** `POST /decks/slots` (562) is under the same gate and calls
`buy_deck_slot($db, $uid)`, which spends the player's autogold. Self-serve
enablement therefore means a leaked or borrowed key can drain autogold into
deck slots, which is not true today for a player who never asked an admin.

**Decided: split the gate.** The site-side reviewer argued for this and was
right; the original lean toward a single toggle is withdrawn.

The deciding property is recoverability, which the first draft failed to
weigh. The four deck routes are destructive but reversible — a leaked key can
wreck your decks and you rebuild them. `buy_deck_slot()` is neither: at 277 AG
a slot and up to 82 slots available, a borrowed key can burn ~22,700 AG with
no undo. Bundling an irreversible spend into a convenience toggle is only
equivalent to the rest until the first time it isn't.

The shape, which needs no capability system:

- new column `is_api_user_self`, set by the player from their profile
- `POST /decks/slots` keeps requiring admin-granted `is_api_user`
- the four self-scoped deck routes accept **either** flag

One correction to the record, which changes nothing about the decision. The
argument cited "15 redundant Double Entry Passes" as precedent for unintended
AG spend. Checked against both accounts: each holds a pass to 2027-01-06 —
104 days, ~15 weeks, ~2,655 AG — which is what kkoechel asked for verbatim
("renew the double deck entry pass for both throgh december"). That spend was
deliberate. The real near-miss was `ensure_pass()` calling `purchase()` merely
to READ the expiry date, which on a ten-minute timer is 25,488 AG/day; it was
fixed before deploy and never spent. The irreversibility argument does not
need the precedent and is not weakened by losing it.

## The change

1. A self-serve control in the API Key panel at `game/profile.php:509`,
   replacing the "contact an admin" branch with an opt-in.
2. It should be explicit rather than a bare switch — the key already exists and
   already works for validation; what changes is what it unlocks. Something
   that states plainly: *anyone with this key can create, change and delete
   your decks* (and, under option 1, *spend your autogold on deck slots*).
3. Reversible from the same place. A player who turns it on and changes their
   mind should not need an admin.
4. Admin toggle keeps working and keeps overriding.

## Acceptance criteria

- A signed-in non-admin, non-playtester player can enable and disable it from
  their own profile, with no admin involved.
- With it off, `PUT /decks/{id}` returns **403** and the existing error text.
- With it on, `PUT /decks/{id}` on **their own** deck returns 200.
- With it on, `PUT /decks/{id}` on **someone else's** deck still returns 404
  (it already does — please confirm it still does after the change).
- **With `is_api_user_self` on but `is_api_user` off, `POST /decks/slots`
  returns 403.** This is the whole point of the split; it wants a test, not
  just an implementation.
- **`require_discord_bot()` (api/v1/index.php) must NOT accept the new flag.**
  It currently checks `is_api_user` alongside an `@autobattle.bot` email; if
  the new column is folded in carelessly, a self-enabled player with a
  matching email could reach bot routes. Unlikely, worth a test.
- **The profile panel at `game/profile.php:509` must reflect either flag.** Its
  condition is `is_api_user || is_playtester || admin` today, so a player who
  self-enables would otherwise still be told to "contact an admin".
- Toggling off revokes immediately, without a new key.
- `GET /me` reports the state unambiguously.

### What /me must expose

This is the app's only hard dependency. The app needs to answer one question —
*may I offer an "apply this change" button?* — which under the split is
`is_api_user || is_api_user_self`. Either is fine:

- return both columns and let the client OR them, or
- return a derived `can_write_decks` boolean (preferred — it keeps the rule on
  the server, so a later change to the gate does not need an app release).

The app does not buy deck slots and will not ask for that capability.

## What the app will do with it

Nothing automatic. The app is an advisor and stays one — it shows a
recommendation, and "apply" is a button the player presses. It will never
write a deck the player has not seen. The app also never sends the key
anywhere but autobattle.online; it is stored with AES/GCM under a
hardware-backed Android Keystore key.

One thing worth knowing about what the app will be applying: a recommendation
only reaches the player if it clears `gain >= 0.4 wins AND t >= 2.0`, measured
paired over 61 cohorts against the full field. In practice, for a deck near the
top, almost everything is rejected — the last run screened 107 unplayed cards
and validated the best at +0.0 (t=-0.1). So the write path will see far less
traffic than "an app that edits decks" suggests.
