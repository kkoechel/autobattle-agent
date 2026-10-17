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
        'SELECT p.id AS player_id, p.handle, p.is_bot, d.name, d.revision,
                d.cards_json, d.plan_json
           FROM decks d JOIN players p ON p.id = d.player_id
          ORDER BY p.id'
    )->fetchAll();
    $out = [];
    foreach ($rows as $r) {
        $out[] = ['player_id' => (int)$r['player_id'], 'handle' => $r['handle'],
                  'is_bot' => (bool)$r['is_bot'], 'deck_name' => $r['name'],
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
        'SELECT r.placement, p.handle, p.is_bot, r.deck_name, r.revision,
                r.wins, r.losses, r.draws
           FROM results r JOIN players p ON p.id = r.player_id
          WHERE r.round_id = ? ORDER BY r.placement');
    $st->execute([$rd['id']]);
    reply(['ok' => true,
           'round' => ['id' => (int)$rd['id'], 'finished_at' => $rd['finished_at'],
                       'decks' => (int)$rd['n_decks'], 'seeds' => (int)$rd['seeds'],
                       'next_at' => next_round_at()],
           'standings' => $st->fetchAll()]);
}

// ---- GET /cards ---------------------------------------------------------
// The forked catalogue plus the ban list. Served whole: the phone needs
// effects_json and stats_json to run the engine, so a slim variant would
// make its decks silently inert.
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
        db()->prepare('INSERT INTO rounds (n_decks, seeds, note, finished_at)
                       VALUES (?,?,?,NOW())')
            ->execute([count($rows), (int)($b['seeds'] ?? 0),
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
