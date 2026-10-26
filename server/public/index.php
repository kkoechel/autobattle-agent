<?php
declare(strict_types=1);
/**
 * AutoBattle Agent game -- coordinator API.
 *
 * The phone does the expensive half: it builds and iterates a deck on-device
 * with the real engine. This service only holds the shared state that a
 * single phone cannot -- who is playing, what their current deck is, and how
 * last round went.
 *
 * Standings are computed HERE, by bin/round.php, rather than reported by the
 * client. At beta scale a full round-robin is a few thousand matches, which
 * is seconds on this box, and computing it server-side means there is no
 * result to forge and no consensus protocol to get wrong. That trade stops
 * being free somewhere in the hundreds of players, and the shard/consensus
 * design is what replaces it then.
 */
require __DIR__ . '/../inc/boot.php';

$path   = trim((string)parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH), '/');
$path   = preg_replace('#^api/#', '', $path);
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

// ---- POST /join ---------------------------------------------------------
if ($path === 'join' && $method === 'POST') {
    $b = body();
    $handle = trim((string)($b['handle'] ?? ''));
    if (!preg_match('/^[A-Za-z0-9 _-]{3,24}$/', $handle)) {
        fail(400, 'handle must be 3-24 chars: letters, digits, space, _ or -');
    }
    $ex = db()->prepare('SELECT id FROM players WHERE handle = ?');
    $ex->execute([$handle]);
    if ($ex->fetch()) fail(409, 'that handle is taken');

    $token = bin2hex(random_bytes(24));
    db()->prepare('INSERT INTO players (handle, key_hash) VALUES (?,?)')
        ->execute([$handle, hash('sha256', $token)]);
    $id = (int)db()->lastInsertId();

    // Hand out a starter so the player is in the league immediately. Picked
    // at random from the pool rather than always the best one: every new
    // player getting the identical list would make the standings a mirror.
    // Absent pool is not an error -- the player simply starts empty and
    // trains from cold, which is what happened before starters existed.
    $st = db()->query(
        'SELECT name, cards_json, plan_json FROM starters ORDER BY RAND() LIMIT 1')->fetch();
    $starter = null;
    if ($st) {
        db()->prepare(
            'INSERT INTO decks (player_id, name, cards_json, plan_json)
             VALUES (?,?,?,?)'
        )->execute([$id, $st['name'], $st['cards_json'], $st['plan_json']]);
        $starter = $st['name'];
    }
    // Shown exactly once. Only the hash is stored.
    reply(['ok' => true, 'player_id' => $id, 'handle' => $handle,
           'token' => $token, 'starter_deck' => $starter]);
}

// ---- GET /me ------------------------------------------------------------
if ($path === 'me' && $method === 'GET') {
    $p = require_player();
    $st = db()->prepare(
        'SELECT name, revision, updated_at, cards_json, plan_json
           FROM decks WHERE player_id = ?');
    $st->execute([$p['id']]);
    $d = $st->fetch();
    if ($d) {
        // The full list, not just its name. This is a deck-building game and
        // the player could not see their own deck -- the one thing the whole
        // app is about.
        $d['cards'] = json_decode($d['cards_json'], true);
        $d['battle_plan'] = json_decode($d['plan_json'], true) ?: new stdClass();
        unset($d['cards_json'], $d['plan_json']);
        $d['revision'] = (int)$d['revision'];
    }
    reply(['ok' => true, 'player_id' => (int)$p['id'], 'handle' => $p['handle'],
           'deck' => $d ?: null]);
}

// ---- PUT /deck ----------------------------------------------------------
if ($path === 'deck' && $method === 'PUT') {
    $p = require_player();
    $b = body();
    $name = trim((string)($b['name'] ?? ''));
    if ($name === '' || mb_strlen($name) > 80) fail(400, 'deck needs a name of 1-80 chars');
    $entries = $b['cards'] ?? null;
    if (!is_array($entries) || !$entries) fail(400, 'cards must be a non-empty array');
    if ($why = deck_problem($entries)) fail(400, $why);
    $plan = $b['battle_plan'] ?? [];
    if (!is_array($plan)) fail(400, 'battle_plan must be an object');

    // Normalise before storing so the round runner never has to think about
    // key order or stray fields.
    $norm = [];
    foreach ($entries as $e) {
        $norm[] = ['card_id' => (int)$e['card_id'], 'quantity' => (int)$e['quantity']];
    }
    usort($norm, fn($a, $x) => $a['card_id'] <=> $x['card_id']);

    db()->prepare(
        'INSERT INTO decks (player_id, name, cards_json, plan_json)
         VALUES (?,?,?,?)
         ON DUPLICATE KEY UPDATE name = VALUES(name),
             cards_json = VALUES(cards_json), plan_json = VALUES(plan_json),
             revision = revision + 1'
    )->execute([$p['id'], $name, json_encode($norm),
                json_encode($plan, JSON_FORCE_OBJECT)]);
    $st = db()->prepare('SELECT revision FROM decks WHERE player_id = ?');
    $st->execute([$p['id']]);
    reply(['ok' => true, 'revision' => (int)$st->fetchColumn()]);
}

// ---- POST /deck/reset ---------------------------------------------------
// Start over: throw the current deck away and take a fresh starter.
//
// Keeps the player, their handle and their results history -- this abandons
// a line of development, it does not abandon the account. A player who has
// driven their agent into a corner needs a way out that is not "uninstall".
if ($path === 'deck/reset' && $method === 'POST') {
    $p = require_player();
    $st = db()->query(
        'SELECT name, cards_json, plan_json FROM starters ORDER BY RAND() LIMIT 1')->fetch();
    if (!$st) fail(503, 'no starter decks are available right now');
    db()->prepare(
        'INSERT INTO decks (player_id, name, cards_json, plan_json)
         VALUES (?,?,?,?)
         ON DUPLICATE KEY UPDATE name = VALUES(name),
             cards_json = VALUES(cards_json), plan_json = VALUES(plan_json),
             revision = revision + 1'
    )->execute([$p['id'], $st['name'], $st['cards_json'], $st['plan_json']]);
    $r = db()->prepare('SELECT revision FROM decks WHERE player_id = ?');
    $r->execute([$p['id']]);
    reply(['ok' => true, 'name' => $st['name'], 'revision' => (int)$r->fetchColumn()]);
}

// ---- GET /field ---------------------------------------------------------
// Everyone's current deck, so the phone can iterate against the real
// opposition locally instead of guessing at it.
if ($path === 'field' && $method === 'GET') {
    require_player();
    $rows = db()->query(
        'SELECT p.id AS player_id, p.handle, p.is_bot, p.faction, d.name, d.revision,
                d.cards_json, d.plan_json
           FROM decks d JOIN players p ON p.id = d.player_id
          ORDER BY p.id'
    )->fetchAll();
    $out = [];
    foreach ($rows as $r) {
        $out[] = ['player_id' => (int)$r['player_id'], 'handle' => $r['handle'],
                  'is_bot' => (bool)$r['is_bot'], 'faction' => (string)$r['faction'],
                  'deck_name' => $r['name'],
                  'revision' => (int)$r['revision'],
                  'cards' => json_decode($r['cards_json'], true),
                  'battle_plan' => json_decode($r['plan_json'], true) ?: new stdClass()];
    }
    reply(['ok' => true, 'decks' => $out]);
}

// ---- GET /standings -----------------------------------------------------
if ($path === 'standings' && $method === 'GET') {
    $rd = db()->query(
        'SELECT * FROM rounds WHERE finished_at IS NOT NULL
          ORDER BY id DESC LIMIT 1')->fetch();
    if (!$rd) reply(['ok' => true, 'round' => null, 'standings' => []]);
    $st = db()->prepare(
        'SELECT r.placement, p.handle, p.is_bot, p.faction, r.deck_name, r.revision,
                r.wins, r.losses, r.draws
           FROM results r JOIN players p ON p.id = r.player_id
          WHERE r.round_id = ? ORDER BY r.placement');
    $st->execute([$rd['id']]);
    // The ban list rides along here. The client needs it to notice that a
    // card in the player's deck has been banned, and the only other way to
    // get it is GET /cards, which is a megabyte of catalogue -- far too much
    // to pull on every refresh for a list that is usually empty.
    reply(['ok' => true,
           'round' => ['id' => (int)$rd['id'], 'finished_at' => $rd['finished_at'],
                       'decks' => (int)$rd['n_decks'], 'seeds' => (int)$rd['seeds'],
                       'next_at' => next_round_at()],
           'banned' => banned_ids(),
           'standings' => $st->fetchAll()]);
}

// ---- GET /leaderboard ---------------------------------------------------
// Form over time, not a snapshot.
//
// One round is 21 seeds against a handful of opponents, so a placement
// bounces around for reasons that have nothing to do with the deck. A player
// watching only the latest round cannot tell improvement from noise, which
// makes the one thing the game asks of them -- did my change help? --
// unanswerable.
//
// Averaged over a window of recent rounds instead. Average WINS is reported
// alongside average placement because placement silently depends on how many
// people are playing: finishing 5th of 11 is better than 5th of 6, and as
// the league grows every existing player's average placement would drift
// without anyone's deck changing.
if ($path === 'leaderboard' && $method === 'GET') {
    $window = (int)($_GET['rounds'] ?? 48);        // 48 rounds ~= 12 hours
    $window = max(1, min(500, $window));
    $latest = (int)db()->query(
        'SELECT COALESCE(MAX(id),0) FROM rounds WHERE finished_at IS NOT NULL')
        ->fetchColumn();
    $since = $latest - $window;
    $st = db()->prepare(
        'SELECT p.handle, p.is_bot, p.faction,
                COUNT(*)            AS rounds,
                AVG(r.placement)    AS avg_place,
                AVG(r.wins)         AS avg_wins,
                MIN(r.placement)    AS best_place,
                MAX(r.revision)     AS revision
           FROM results r JOIN players p ON p.id = r.player_id
          WHERE r.round_id > ?
          GROUP BY p.id
          ORDER BY avg_place ASC, avg_wins DESC');
    $st->execute([$since]);
    $rows = [];
    foreach ($st->fetchAll() as $r) {
        $rows[] = [
            'handle'     => $r['handle'],
            'is_bot'     => (int)$r['is_bot'],
            'faction'    => (string)$r['faction'],
            'rounds'     => (int)$r['rounds'],
            'avg_place'  => round((float)$r['avg_place'], 2),
            'avg_wins'   => round((float)$r['avg_wins'], 2),
            'best_place' => (int)$r['best_place'],
            'revision'   => (int)$r['revision'],
        ];
    }
    reply(['ok' => true, 'window' => $window, 'latest_round' => $latest,
           'table' => $rows]);
}

// ---- GET /cards ---------------------------------------------------------
// The forked catalogue plus the ban list. Served whole: the phone needs
// effects_json and stats_json to run the engine, so a slim variant would
// make its decks silently inert.
// Keyword glossary, so card text can explain itself. Tiny (11KB) and
// changes only when the main game adds a keyword, so it rides with /cards
// rather than earning its own round trip.
if ($path === 'keywords' && $method === 'GET') {
    $f = __DIR__ . '/../var/keywords.json';
    if (!is_file($f)) fail(503, 'keyword glossary not synced yet');
    header('Content-Type: application/json');
    echo '{"ok":true,"keywords":' . file_get_contents($f) . '}';
    exit;
}

if ($path === 'cards' && $method === 'GET') {
    $bans = banned_ids();
    header('Content-Type: application/json');
    header('X-Card-Count: ' . count(catalogue()));
    echo '{"ok":true,"banned":' . json_encode($bans) . ',"cards":'
         . file_get_contents(CARD_FILE) . '}';
    exit;
}

// ---- POST /round --------------------------------------------------------
// Written by bin/round.py, which is a privileged CLIENT rather than a second
// thing that knows the schema: all database access stays in this file.
if ($path === 'round' && $method === 'POST') {
    $tok = (string)($_SERVER['HTTP_X_ADMIN_TOKEN'] ?? '');
    $want = (string)(cfg()['admin_token'] ?? '');
    if ($want === '' || !hash_equals($want, $tok)) fail(403, 'admin token required');
    $b = body();
    $rows = $b['results'] ?? null;
    if (!is_array($rows) || !$rows) fail(400, 'results must be a non-empty array');

    // Placement by wins, then fewest losses -- a 20-2 is ahead of a 20-5.
    usort($rows, fn($x, $y) => [$y['wins'] ?? 0, $x['losses'] ?? 0]
                           <=> [$x['wins'] ?? 0, $y['losses'] ?? 0]);
    db()->beginTransaction();
    try {
        db()->prepare('INSERT INTO rounds (n_decks, seeds, seed0, note, finished_at)
                       VALUES (?,?,?,?,NOW())')
            ->execute([count($rows), (int)($b['seeds'] ?? 0),
                       (int)($b['seed0'] ?? 0),
                       substr((string)($b['note'] ?? ''), 0, 255)]);
        $rid = (int)db()->lastInsertId();
        $ins = db()->prepare(
            'INSERT INTO results (round_id, player_id, deck_name, revision,
                                  wins, losses, draws, placement)
             VALUES (?,?,?,?,?,?,?,?)');
        foreach ($rows as $i => $r) {
            $ins->execute([$rid, (int)$r['player_id'],
                           substr((string)($r['deck_name'] ?? '?'), 0, 80),
                           (int)($r['revision'] ?? 0), (int)($r['wins'] ?? 0),
                           (int)($r['losses'] ?? 0), (int)($r['draws'] ?? 0),
                           $i + 1]);
        }

        // The lists as they played, so the round can be watched and not just
        // read. Optional on purpose: an older bin/round.py posts no `decks`
        // and must still be able to score a round -- it loses the replay,
        // not the result.
        $snaps = $b['decks'] ?? null;
        if (is_array($snaps)) {
            $sn = db()->prepare(
                'INSERT INTO round_decks (round_id, player_id, handle,
                                          deck_name, revision, cards_json,
                                          plan_json)
                 VALUES (?,?,?,?,?,?,?)');
            foreach ($snaps as $d) {
                if (!isset($d['player_id'], $d['cards'])) continue;
                $sn->execute([$rid, (int)$d['player_id'],
                              substr((string)($d['handle'] ?? '?'), 0, 40),
                              substr((string)($d['deck_name'] ?? '?'), 0, 80),
                              (int)($d['revision'] ?? 0),
                              json_encode($d['cards']),
                              json_encode($d['battle_plan'] ?? new stdClass())]);
            }
        }
        // KEEP THE GOOD FINISHES, so a player can go back to a deck that
        // was working. "Good" is the top QUARTER of the field, which scales
        // as the league grows -- a fixed placement would mean something
        // different at nine decks and at ninety.
        //
        // Done here because this is the one moment where the deck as it
        // played and the placement it earned are both in hand.
        $cut = max(1, intdiv(count($rows), 4));
        if (is_array($snaps)) {
            $byPlayer = [];
            foreach ($snaps as $d) {
                if (isset($d['player_id'])) $byPlayer[(int)$d['player_id']] = $d;
            }
            foreach ($rows as $i => $r) {
                if ($i + 1 > $cut) break;              // sorted by placement
                $pid = (int)($r['player_id'] ?? 0);
                $d = $byPlayer[$pid] ?? null;
                if (!$d) continue;
                keep_good_deck($pid, $d, $i + 1, count($rows), $rid);
            }
        }

        // Snapshots are bulky (every deck in the league, every 15 minutes) and
        // only the recent past is watchable in practice, so they are pruned
        // while the results they belong to are kept forever.
        db()->prepare('DELETE FROM round_decks WHERE round_id <= ?')
            ->execute([$rid - REPLAY_KEEP_ROUNDS]);
        db()->commit();
    } catch (Throwable $e) {
        db()->rollBack();
        fail(500, 'could not store round: ' . $e->getMessage());
    }
    reply(['ok' => true, 'round_id' => $rid, 'decks' => count($rows)]);
}

// ---- POST /starters -----------------------------------------------------
// Replaces the starter pool wholesale. Written by bin/make_starters.py, which
// measures candidates against the live field and keeps the ones that land
// mid-table. Replace rather than append: a pool that only ever grows fills
// with decks that were mid-table against a field that has since moved on.
if ($path === 'starters' && $method === 'POST') {
    $tok = (string)($_SERVER['HTTP_X_ADMIN_TOKEN'] ?? '');
    $want = (string)(cfg()['admin_token'] ?? '');
    if ($want === '' || !hash_equals($want, $tok)) fail(403, 'admin token required');
    $rows = body()['starters'] ?? null;
    if (!is_array($rows) || !$rows) fail(400, 'starters must be a non-empty array');

    // Validated with the same function the player path uses. A starter is
    // the one deck nobody chose, so an illegal one would be a server-authored
    // bug that looks like the player cheated.
    foreach ($rows as $i => $r) {
        if ($why = deck_problem($r['cards'] ?? [])) fail(400, "starter $i: $why");
    }
    db()->beginTransaction();
    try {
        db()->exec('DELETE FROM starters');
        $ins = db()->prepare(
            'INSERT INTO starters (name, cards_json, plan_json, wins, opponents)
             VALUES (?,?,?,?,?)');
        foreach ($rows as $r) {
            $norm = [];
            foreach ($r['cards'] as $e) {
                $norm[] = ['card_id' => (int)$e['card_id'],
                           'quantity' => (int)$e['quantity']];
            }
            usort($norm, fn($a, $x) => $a['card_id'] <=> $x['card_id']);
            $ins->execute([substr((string)($r['name'] ?? 'Starter'), 0, 80),
                           json_encode($norm),
                           json_encode($r['battle_plan'] ?? [], JSON_FORCE_OBJECT),
                           (float)($r['wins'] ?? 0), (int)($r['opponents'] ?? 0)]);
        }
        db()->commit();
    } catch (Throwable $e) {
        db()->rollBack();
        fail(500, 'could not store starters: ' . $e->getMessage());
    }
    reply(['ok' => true, 'starters' => count($rows)]);
}

// ---- POST /bans ---------------------------------------------------------
// Apply one ban and rotate the pool. Admin only, and the only place bans are
// written -- bin/ban.py decides WHICH card, this decides what that means.
//
// Rotating rather than accumulating: a format that only ever loses cards
// shrinks until nothing is left, and a card that was banned a long time ago
// is no longer the card that warped the field. The oldest leaves when the
// pool is full, so a ban is a suspension with a known end.
if ($path === 'bans' && $method === 'POST') {
    require_admin();
    $b = body();
    $keep = max(1, min(50, (int)($b['keep'] ?? 7)));
    $card = (int)($b['card_id'] ?? 0);
    $cards = catalogue();
    if ($card && !isset($cards[$card])) fail(400, "unknown card $card");

    $released = [];
    db()->beginTransaction();
    try {
        if ($card) {
            db()->prepare(
                'INSERT IGNORE INTO bans (card_id, reason) VALUES (?,?)'
            )->execute([$card, mb_substr((string)($b['reason'] ?? ''), 0, 255)]);
        }
        // Oldest first, so the pool is a queue rather than a graveyard.
        $over = db()->query(
            'SELECT card_id FROM bans ORDER BY banned_at ASC, card_id ASC')
            ->fetchAll(PDO::FETCH_COLUMN);
        while (count($over) > $keep) {
            $old = (int)array_shift($over);
            db()->prepare('DELETE FROM bans WHERE card_id = ?')->execute([$old]);
            $released[] = ['card_id' => $old,
                           'name' => $cards[$old]['name'] ?? "card $old"];
        }
        db()->commit();
    } catch (Throwable $e) {
        db()->rollBack();
        fail(500, 'could not apply the ban: ' . $e->getMessage());
    }

    // A BAN HAS TO LEAVE THE BOARD, NOT JUST THE RULEBOOK. Outside the
    // transaction on purpose: the ban itself is the thing that must not be
    // half-applied, while a repair that fails partway has still legalised
    // every deck it reached, and the next ban will finish the rest.
    $repaired = repair_decks_for_bans();

    $now = [];
    foreach (db()->query(
        'SELECT card_id, banned_at, reason FROM bans ORDER BY banned_at DESC') as $r) {
        $id = (int)$r['card_id'];
        $now[] = ['card_id' => $id, 'name' => $cards[$id]['name'] ?? "card $id",
                  'banned_at' => $r['banned_at'], 'reason' => $r['reason']];
    }
    reply(['ok' => true, 'banned' => $now, 'released' => $released, 'keep' => $keep, 'repaired' => $repaired]);
}

// ---- GET /bans ----------------------------------------------------------
// What is out, and when. Public: a player needs to know which cards their
// deck may not contain.
//
// DELIBERATELY NOT the reason. The figure behind a ban is one sample from a
// wide distribution -- measured against the live field, a single card's
// contribution came out anywhere between +1.60 and +8.93 wins depending only
// on which two decks were drawn -- so publishing "it cost its deck 2.40 wins
// a round" asserts a precision the measurement does not have. The ban stands
// on the ranking, which is stable; the magnitude does not, and a card being
// out of the format is the whole of what a player needs to act.
//
// The reason is still written to the row and to the journal. Withholding it
// here is about not publishing a shaky number, not about not keeping one --
// it is exactly what we need when deciding whether the pool is measuring
// signal.
if ($path === 'bans' && $method === 'GET') {
    $cards = catalogue();
    $out = [];
    foreach (db()->query(
        'SELECT card_id, banned_at FROM bans ORDER BY banned_at DESC') as $r) {
        $id = (int)$r['card_id'];
        $out[] = ['card_id' => $id, 'name' => $cards[$id]['name'] ?? "card $id",
                  'banned_at' => $r['banned_at']];
    }
    reply(['ok' => true, 'banned' => $out]);
}

// ---- saved decks --------------------------------------------------------
// The agent only ever has ONE deck, and training replaces it. Without a
// shelf, trying a new idea costs you the deck that was working.
//
// Snapshots, not references: restoring gives back the cards that were saved,
// not whatever the live deck has become since.

if ($path === 'decks/saved' && $method === 'GET') {
    $p = require_player();
    $st = db()->prepare(
        'SELECT id, name, revision, best_place, of_decks, note, created_at,
                cards_json, plan_json, auto
           FROM saved_decks WHERE player_id = ?
          -- Best finish first: the shelf is for going back to what worked,
          -- so the deck that placed highest should be the one in reach.
          ORDER BY best_place IS NULL, best_place ASC, id DESC');
    $st->execute([$p['id']]);
    $rows = [];
    foreach ($st->fetchAll() as $r) {
        $rows[] = [
            'id' => (int)$r['id'], 'name' => $r['name'],
            'revision' => (int)$r['revision'],
            'best_place' => $r['best_place'] === null ? null : (int)$r['best_place'],
            'of_decks' => $r['of_decks'] === null ? null : (int)$r['of_decks'],
            'note' => $r['note'], 'created_at' => $r['created_at'],
            'auto' => (bool)$r['auto'],
            'cards' => json_decode($r['cards_json'], true),
            'battle_plan' => json_decode($r['plan_json'], true) ?: new stdClass(),
        ];
    }
    reply(['ok' => true, 'saved' => $rows, 'max' => SAVED_MAX]);
}

if ($path === 'decks/saved' && $method === 'POST') {
    $p = require_player();
    $b = body();
    $st = db()->prepare(
        'SELECT name, revision, cards_json, plan_json FROM decks WHERE player_id = ?');
    $st->execute([$p['id']]);
    $d = $st->fetch();
    if (!$d) fail(400, 'you have no deck to save');

    // The same list twice is a shelf full of one deck. Compared by content
    // rather than by name, because the agent names decks after their seed
    // card and two different builds often share a name.
    $dupe = db()->prepare(
        'SELECT id FROM saved_decks WHERE player_id = ? AND cards_json = ?');
    $dupe->execute([$p['id'], $d['cards_json']]);
    if ($row = $dupe->fetch()) {
        reply(['ok' => true, 'id' => (int)$row['id'], 'already' => true]);
    }

    $cnt = db()->prepare('SELECT COUNT(*) FROM saved_decks WHERE player_id = ?');
    $cnt->execute([$p['id']]);
    if ((int)$cnt->fetchColumn() >= SAVED_MAX) {
        fail(409, 'that is ' . SAVED_MAX . ' saved decks; remove one first');
    }

    // Its best finish, so the shelf says why a deck was worth keeping.
    $best = db()->prepare(
        'SELECT MIN(r.placement) AS p, MAX(rd.n_decks) AS n
           FROM results r JOIN rounds rd ON rd.id = r.round_id
          WHERE r.player_id = ? AND r.revision = ?');
    $best->execute([$p['id'], (int)$d['revision']]);
    $b2 = $best->fetch();

    $ins = db()->prepare(
        'INSERT INTO saved_decks
            (player_id, name, cards_json, plan_json, revision, best_place,
             of_decks, note)
         VALUES (?,?,?,?,?,?,?,?)');
    $ins->execute([$p['id'], $d['name'], $d['cards_json'], $d['plan_json'],
                   (int)$d['revision'],
                   $b2 && $b2['p'] !== null ? (int)$b2['p'] : null,
                   $b2 && $b2['n'] !== null ? (int)$b2['n'] : null,
                   isset($b['note']) ? mb_substr((string)$b['note'], 0, 120) : null]);
    reply(['ok' => true, 'id' => (int)db()->lastInsertId()]);
}

// Rename the note on a deck already kept.
//
// A note could only be set at the moment of saving, which is the moment a
// player knows least about why the deck mattered -- that is usually learned
// a few rounds later, when it turns out to be the one that beat the mill
// decks. Separate from restore and delete because it changes nothing about
// the deck itself.
if ($path === 'decks/saved/note' && $method === 'POST') {
    $p = require_player();
    $b = body();
    $id = (int)($b['id'] ?? 0);
    // Scoped to the caller's own shelf, so an id from someone else's simply
    // does not match rather than erroring in a way that confirms it exists.
    $st = db()->prepare(
        'UPDATE saved_decks SET note = ? WHERE id = ? AND player_id = ?');
    $note = trim((string)($b['note'] ?? ''));
    $st->execute([$note === '' ? null : mb_substr($note, 0, 120), $id, $p['id']]);
    if ($st->rowCount() === 0) {
        // No row changed can mean "not yours" or "same text as before", and
        // the second is not an error, so check which before complaining.
        $own = db()->prepare(
            'SELECT 1 FROM saved_decks WHERE id = ? AND player_id = ?');
        $own->execute([$id, $p['id']]);
        if (!$own->fetch()) fail(404, 'no such kept deck');
    }
    reply(['ok' => true, 'id' => $id,
           'note' => $note === '' ? null : mb_substr($note, 0, 120)]);
}

if ($path === 'decks/saved/restore' && $method === 'POST') {
    $p = require_player();
    $id = (int)(body()['id'] ?? 0);
    $st = db()->prepare(
        'SELECT name, cards_json, plan_json FROM saved_decks
          WHERE id = ? AND player_id = ?');
    $st->execute([$id, $p['id']]);
    $sv = $st->fetch();
    if (!$sv) fail(404, 'no such saved deck');

    // Validated on the way back in: a deck saved before a ban is no longer
    // legal, and restoring it unchecked would quietly enter an illegal list.
    if ($why = deck_problem(json_decode($sv['cards_json'], true) ?: [])) {
        fail(400, "that deck cannot be played now: $why");
    }
    db()->prepare(
        'INSERT INTO decks (player_id, name, cards_json, plan_json)
         VALUES (?,?,?,?)
         ON DUPLICATE KEY UPDATE name = VALUES(name),
             cards_json = VALUES(cards_json), plan_json = VALUES(plan_json),
             revision = revision + 1, updated_at = NOW()'
    )->execute([$p['id'], $sv['name'], $sv['cards_json'], $sv['plan_json']]);
    reply(['ok' => true, 'name' => $sv['name']]);
}

if ($path === 'decks/saved/delete' && $method === 'POST') {
    $p = require_player();
    $id = (int)(body()['id'] ?? 0);
    $st = db()->prepare('DELETE FROM saved_decks WHERE id = ? AND player_id = ?');
    $st->execute([$id, $p['id']]);
    reply(['ok' => true, 'removed' => $st->rowCount()]);
}

// ---- GET /history -------------------------------------------------------
// The caller's OWN round-by-round record: what each deck actually did, round
// by round, newest first.
//
// /leaderboard already gives an average over a window, and an average is the
// wrong shape for the question a player is really asking -- "is it getting
// better?" -- because a steady climb and a flat line with one good round
// average the same. The deck revision rides along so a run of results can be
// attributed to the list that played them rather than to whatever is in the
// slot now.
if ($path === 'history' && $method === 'GET') {
    $p = require_player();
    $n = max(1, min(200, (int)($_GET['rounds'] ?? 40)));
    $st = db()->prepare(
        'SELECT r.round_id, r.deck_name, r.revision, r.wins, r.losses,
                r.draws, r.placement, rd.finished_at, rd.n_decks, rd.seed0,
                (SELECT COUNT(*) FROM round_decks x
                  WHERE x.round_id = r.round_id) AS snap
           FROM results r JOIN rounds rd ON rd.id = r.round_id
          WHERE r.player_id = ?
          ORDER BY r.round_id DESC
          LIMIT ' . $n);
    $st->execute([$p['id']]);
    $rows = [];
    foreach ($st->fetchAll() as $r) {
        $rows[] = [
            'round_id'    => (int)$r['round_id'],
            'finished_at' => $r['finished_at'],
            'deck_name'   => $r['deck_name'],
            'revision'    => (int)$r['revision'],
            'wins'        => (float)$r['wins'],
            'losses'      => (float)$r['losses'],
            'draws'       => (float)$r['draws'],
            'placement'   => (int)$r['placement'],
            'of'          => (int)$r['n_decks'],
            // Whether this round can be WATCHED, not inferred from its age:
            // snapshots began part-way through the league's history and are
            // pruned at the far end, so the app offers a replay only where
            // one exists rather than discovering that at tap time.
            'replayable'  => ((int)$r['snap']) > 1,
        ];
    }
    reply(['ok' => true, 'rounds' => $rows]);
}

// ---- GET /replay --------------------------------------------------------
// Everything needed to re-play a scored round on the phone: every deck as it
// actually was, and the seed block it was scored on.
//
// The per-MATCH outcomes are deliberately not stored. The phone holds the same
// engine and now the same decks and the same seeds, so it can re-derive them
// itself -- and a client that re-derives them can answer "show me a game we
// won" or "...one that shows off poison" without this service having to
// anticipate the question. Storing 9x8x21 match records per round to answer a
// question the phone can answer locally would be the worse trade.
if ($path === 'replay' && $method === 'GET') {
    // Readable with the ADMIN token as well as a player's, because bin/ban.py
    // needs exactly this: the decks as they played a round, paired with how
    // they placed. Reading the live /field instead would pair current decks
    // with last round's placements, and an agent that retrained in between
    // would be a different deck wearing an old result.
    $admin = hash_equals((string)(cfg()['admin_token'] ?? '_'),
                         (string)($_SERVER['HTTP_X_ADMIN_TOKEN'] ?? ''))
             && (string)(cfg()['admin_token'] ?? '') !== '';
    $p = $admin ? ['id' => 0] : require_player();
    $want = (int)($_GET['round'] ?? 0);
    if ($want > 0) {
        $st = db()->prepare('SELECT id, seed0, seeds, n_decks, finished_at
                               FROM rounds WHERE id = ?');
        $st->execute([$want]);
    } else {
        // Newest round that still HAS a snapshot, not simply the newest round:
        // pruning and the pre-snapshot history both leave rounds that cannot
        // be replayed, and an empty answer for "the latest game" reads as a
        // broken feature rather than an old one.
        $st = db()->query('SELECT r.id, r.seed0, r.seeds, r.n_decks,
                                  r.finished_at
                             FROM rounds r
                             JOIN round_decks d ON d.round_id = r.id
                            GROUP BY r.id ORDER BY r.id DESC LIMIT 1');
    }
    $r = $st->fetch();
    if (!$r) fail(404, 'no replayable round');
    $rid = (int)$r['id'];

    $st = db()->prepare(
        'SELECT d.player_id, d.handle, d.deck_name, d.revision, d.cards_json,
                d.plan_json, res.wins, res.losses, res.draws, res.placement
           FROM round_decks d
           LEFT JOIN results res
                  ON res.round_id = d.round_id AND res.player_id = d.player_id
          WHERE d.round_id = ?
          ORDER BY res.placement IS NULL, res.placement');
    $st->execute([$rid]);
    $decks = [];
    foreach ($st->fetchAll() as $d) {
        $decks[] = [
            'player_id'   => (int)$d['player_id'],
            'is_me'       => (int)$d['player_id'] === (int)$p['id'],
            'handle'      => $d['handle'],
            'deck_name'   => $d['deck_name'],
            'revision'    => (int)$d['revision'],
            'cards'       => json_decode($d['cards_json'], true),
            'battle_plan' => json_decode($d['plan_json'], true) ?: new stdClass(),
            'wins'        => $d['wins'] === null ? null : (int)$d['wins'],
            'losses'      => $d['losses'] === null ? null : (int)$d['losses'],
            'draws'       => $d['draws'] === null ? null : (int)$d['draws'],
            'placement'   => $d['placement'] === null ? null : (int)$d['placement'],
        ];
    }
    if (!$decks) fail(404, 'round ' . $rid . ' has no deck snapshot');
    reply(['ok' => true, 'round_id' => $rid, 'finished_at' => $r['finished_at'],
           'seed0' => (int)$r['seed0'], 'seeds' => (int)$r['seeds'],
           'n_decks' => (int)$r['n_decks'], 'decks' => $decks]);
}

// ---- GET /idea ----------------------------------------------------------
// A specific card the agent could build around, with a reason.
//
// Proposed HERE rather than in the app because naming a card needs the
// catalogue, and the only client route to that is GET /cards -- a megabyte,
// to pick one name. The server already holds it.
//
// Preference order is the whole point: a card NOBODY in the league plays is
// a more interesting suggestion than a card everyone plays, and a card with
// a real tag family behind it can actually be built around, where one with
// no relatives produces a pile of unrelated staples.
if ($path === 'idea' && $method === 'GET') {
    require_player();
    $cards = catalogue();
    $banned = array_flip(banned_ids());

    $played = [];
    foreach (db()->query('SELECT cards_json FROM decks')->fetchAll(PDO::FETCH_COLUMN) as $j) {
        foreach (json_decode((string)$j, true) ?: [] as $e) {
            $played[(int)$e['card_id']] = true;
        }
    }
    // How many other cards share a tag with this one -- a cheap proxy for
    // "is there a deck here".
    $tagCount = [];
    foreach ($cards as $c) {
        foreach (($c['tags'] ?? []) as $t) {
            $tagCount[$t] = ($tagCount[$t] ?? 0) + 1;
        }
    }

    $pool = [];
    foreach ($cards as $id => $c) {
        if (isset($banned[$id]) || isset($played[$id])) continue;
        // RETIRED CARDS ARE THE TRAP HERE. This route prefers cards nobody
        // plays, and a retired card satisfies that by definition -- it CANNOT
        // be played. 37% of everything it could suggest was retired, so the
        // agent kept offering to build around cards that are not in the game.
        // Mirrors Cards.isPlayable on the phone, which is why no deck ever
        // actually contained one: only the suggestion was wrong.
        if (!empty($c['is_retired']) || !empty($c['is_vip'])) continue;
        if (($c['rarity'] ?? '') === 'token') continue;
        if ((int)($c['deck_limit'] ?? 0) <= 0) continue;
        if (trim((string)($c['rules_text'] ?? '')) === '') continue;
        $best = 0;
        foreach (($c['tags'] ?? []) as $t) $best = max($best, $tagCount[$t] ?? 0);
        if ($best < 25) continue;          // not enough relatives to build with
        $pool[] = ['id' => $id, 'name' => $c['name'], 'family' => $best,
                   'rarity' => $c['rarity'] ?? '', 'text' => $c['rules_text'] ?? ''];
    }
    if (!$pool) fail(404, 'no unplayed card worth suggesting right now');
    $pick = $pool[random_int(0, count($pool) - 1)];
    reply(['ok' => true, 'card_id' => $pick['id'], 'name' => $pick['name'],
           'rarity' => $pick['rarity'],
           'text' => mb_substr((string)$pick['text'], 0, 160),
           'reason' => 'Nobody in the league is playing it.']);
}

// ---- GET /privacy -------------------------------------------------------
// Served by the front controller as well as directly, so both /privacy and
// /privacy.html work -- Play stores whichever URL is pasted into the console
// and a 404 on the privacy link is a review rejection.
if (($path === 'privacy' || $path === 'privacy.html') && $method === 'GET') {
    $f = __DIR__ . '/privacy.html';
    if (is_file($f)) {
        header('Content-Type: text/html; charset=utf-8');
        readfile($f);
        exit;
    }
}

// ---- GET /health --------------------------------------------------------
if ($path === 'health' && $method === 'GET') {
    reply(['ok' => true, 'cards' => count(catalogue()),
           'players' => (int)db()->query('SELECT COUNT(*) FROM players')->fetchColumn(),
           'decks' => (int)db()->query('SELECT COUNT(*) FROM decks')->fetchColumn(),
           'starters' => (int)db()->query('SELECT COUNT(*) FROM starters')->fetchColumn()]);
}

fail(404, 'no such endpoint');
