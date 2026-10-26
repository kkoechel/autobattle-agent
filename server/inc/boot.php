<?php
declare(strict_types=1);
/**
 * Shared bootstrap: config, database, catalogue, auth, JSON replies.
 *
 * This service shares NOTHING with autobattle.online. Its only contact with
 * the main game is bin/sync_cards.php, which does a read-only GET /api/v1/cards
 * and writes a file here. There is no connection string to the main database
 * anywhere in this tree, by construction rather than by discipline.
 */

const CARD_FILE = __DIR__ . '/../var/cards.json';

// How many rounds of deck snapshots to keep for replay. At 15-minute rounds
// 96 is a day, which is the span a player might plausibly want to re-watch;
// results are kept forever, only the lists that played are pruned.
const REPLAY_KEEP_ROUNDS = 96;

// How many decks a player may keep on the shelf.
//
// Defined HERE, not beside the routes that read it: POST /round calls
// keep_good_deck() and is matched well before the saved-deck routes
// are reached, so a const declared down there would not exist yet.
const SAVED_MAX = 12;

function cfg(): array {
    static $c = null;
    if ($c === null) {
        $p = __DIR__ . '/config.php';
        if (!is_file($p)) {
            fail(500, 'server not configured');
        }
        $c = require $p;
    }
    return $c;
}

function db(): PDO {
    static $db = null;
    if ($db === null) {
        $c = cfg();
        $db = new PDO(
            "mysql:host={$c['db_host']};dbname={$c['db_name']};charset=utf8mb4",
            $c['db_user'], $c['db_pass'],
            [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
             PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
             PDO::ATTR_EMULATE_PREPARES => false]
        );
    }
    return $db;
}

/** The forked catalogue, keyed by id. Pulled from the main game, never written. */
function catalogue(): array {
    static $cards = null;
    if ($cards === null) {
        if (!is_file(CARD_FILE)) {
            fail(503, 'card catalogue not synced yet');
        }
        $cards = [];
        foreach (json_decode((string)file_get_contents(CARD_FILE), true) ?: [] as $c) {
            $cards[(int)$c['id']] = $c;
        }
    }
    return $cards;
}

function banned_ids(): array {
    static $b = null;
    if ($b === null) {
        $b = array_map('intval',
            db()->query('SELECT card_id FROM bans')->fetchAll(PDO::FETCH_COLUMN));
    }
    return $b;
}

function reply(array $body, int $code = 200): never {
    http_response_code($code);
    header('Content-Type: application/json');
    echo json_encode($body, JSON_UNESCAPED_SLASHES);
    exit;
}

function fail(int $code, string $msg): never {
    reply(['ok' => false, 'error' => $msg], $code);
}

function body(): array {
    $raw = file_get_contents('php://input') ?: '';
    if ($raw === '') return [];
    $j = json_decode($raw, true);
    if (!is_array($j)) fail(400, 'body must be a JSON object');
    return $j;
}

/** Bearer token -> player row, or 401. */
function require_player(): array {
    $hdr = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
    if (!preg_match('/^Bearer\s+([A-Za-z0-9]{32,128})$/', trim($hdr), $m)) {
        fail(401, 'missing or malformed bearer token');
    }
    $st = db()->prepare('SELECT * FROM players WHERE key_hash = ?');
    $st->execute([hash('sha256', $m[1])]);
    $p = $st->fetch();
    if (!$p) fail(401, 'unknown token');
    db()->prepare('UPDATE players SET last_seen = NOW() WHERE id = ?')
        ->execute([$p['id']]);
    return $p;
}

/**
 * Is this a legal 100-card deck?
 *
 * Checked HERE and not trusted from the client, because the client is a phone
 * the player controls. Returns an error string, or null when the deck is fine.
 */
/**
 * The admin token, for the privileged CLI clients (bin/round.py, bin/ban.py).
 *
 * hash_equals rather than ===, and an empty configured token is a refusal
 * rather than a free pass: a missing admin_token must not mean "no check".
 */
function require_admin(): void {
    $tok  = (string)($_SERVER['HTTP_X_ADMIN_TOKEN'] ?? '');
    $want = (string)(cfg()['admin_token'] ?? '');
    if ($want === '' || !hash_equals($want, $tok)) fail(403, 'admin token required');
}

/**
 * Put a top-quarter finish on the player's shelf.
 *
 * Deduplicated by CONTENT, not by name: the agent names decks after the card
 * they lean on, so a run of good rounds with the same list would otherwise
 * fill the shelf with twelve copies of one deck. A deck already there is
 * only updated if this finish was better than the one recorded against it.
 *
 * EVICTS ONLY WHAT IT PUT THERE. The shelf is capped, and when it is full
 * something has to go -- but a deck the player named and wrote a note
 * against is theirs. Deleting that to make room for a good finish would be
 * the app throwing away work it was asked to keep, so if the shelf is full
 * of hand-picked decks this quietly does nothing.
 */
function keep_good_deck(int $pid, array $d, int $place, int $of, int $rid): void {
    $cards = json_encode($d['cards'] ?? []);
    $plan  = json_encode($d['battle_plan'] ?? new stdClass());

    $same = db()->prepare(
        'SELECT id, best_place FROM saved_decks
          WHERE player_id = ? AND cards_json = ? LIMIT 1');
    $same->execute([$pid, $cards]);
    if ($row = $same->fetch()) {
        if ($row['best_place'] === null || $place < (int)$row['best_place']) {
            db()->prepare('UPDATE saved_decks SET best_place = ?, of_decks = ?
                            WHERE id = ?')
                ->execute([$place, $of, (int)$row['id']]);
        }
        return;
    }

    $cnt = db()->prepare('SELECT COUNT(*) FROM saved_decks WHERE player_id = ?');
    $cnt->execute([$pid]);
    if ((int)$cnt->fetchColumn() >= SAVED_MAX) {
        // The weakest auto-saved deck, and only if this finish beats it.
        $worst = db()->prepare(
            'SELECT id, best_place FROM saved_decks
              WHERE player_id = ? AND auto = 1
              ORDER BY best_place IS NULL DESC, best_place DESC, id ASC LIMIT 1');
        $worst->execute([$pid]);
        $w = $worst->fetch();
        if (!$w) return;                                   // all hand-picked
        if ($w['best_place'] !== null && (int)$w['best_place'] <= $place) return;
        db()->prepare('DELETE FROM saved_decks WHERE id = ?')
            ->execute([(int)$w['id']]);
    }

    db()->prepare(
        'INSERT INTO saved_decks
            (player_id, name, cards_json, plan_json, revision, best_place,
             of_decks, note, auto)
         VALUES (?,?,?,?,?,?,?,?,1)')
        ->execute([$pid, substr((string)($d['deck_name'] ?? '?'), 0, 80),
                   $cards, $plan, (int)($d['revision'] ?? 0),
                   $place, $of,
                   "kept automatically, $place of $of in round $rid"]);
}

/**
 * Take a banned card out of every deck already in play.
 *
 * WITHOUT THIS A BAN IS COSMETIC. deck_problem() refuses a banned card on
 * SUBMISSION, but a deck registered before the ban is never looked at again,
 * so it keeps playing the card every fifteen minutes. Measured on the live
 * league the first time anyone checked: 23 of 34 decks were still playing a
 * banned card, house decks among them. The card had been removed from the
 * rulebook and from nobody's board.
 *
 * Repairs rather than rejects, because the alternative is deleting a deck
 * the player did not choose to lose. The missing copies are made up by
 * topping up what the deck ALREADY runs, in descending order of how many it
 * runs -- that keeps the deck's own character instead of substituting a
 * stranger. Infinite-rarity cards are the last resort: they are the baseline
 * every deck may play without limit, which is exactly what a filler needs to
 * be, and they guarantee the deck can always be made legal.
 *
 * Returns the number of decks changed.
 */
function repair_decks_for_bans(): int {
    $cards = catalogue();
    $banned = array_flip(banned_ids());
    if (!$banned) return 0;

    $limit = function (int $id) use ($cards): int {
        return (int)($cards[$id]['deck_limit'] ?? 0);
    };
    // Baseline filler, cheapest first so a repair does not quietly hand the
    // deck a pile of expensive cards it cannot cast.
    $filler = [];
    foreach ($cards as $id => $c) {
        if (isset($banned[$id])) continue;
        if (($c['rarity'] ?? '') !== 'infinite') continue;
        if (!empty($c['is_retired']) || !empty($c['is_vip'])) continue;
        $filler[] = (int)$id;
    }
    usort($filler, fn($x, $y) => ($cards[$x]['cost'] ?? 0) <=> ($cards[$y]['cost'] ?? 0));

    $changed = 0;
    // Keyed by player_id: `decks` holds one row per player and has no id
    // column of its own.
    foreach (db()->query('SELECT player_id, cards_json FROM decks') as $d) {
        $entries = json_decode($d['cards_json'], true);
        if (!is_array($entries)) continue;
        $kept = [];
        $missing = 0;
        foreach ($entries as $e) {
            $id = (int)($e['card_id'] ?? 0);
            $q  = (int)($e['quantity'] ?? 0);
            if (isset($banned[$id])) { $missing += $q; continue; }
            if ($q > 0) $kept[$id] = ($kept[$id] ?? 0) + $q;
        }
        if ($missing === 0) continue;

        // Top up what the deck already runs, most-played first.
        arsort($kept);
        foreach ($kept as $id => $q) {
            if ($missing <= 0) break;
            $room = max(0, $limit($id) - $q);
            $take = min($room, $missing);
            if ($take > 0) { $kept[$id] = $q + $take; $missing -= $take; }
        }
        // Then the baseline, which has no practical limit.
        foreach ($filler as $id) {
            if ($missing <= 0) break;
            $have = $kept[$id] ?? 0;
            $room = max(0, $limit($id) - $have);
            $take = min($room, $missing);
            if ($take > 0) { $kept[$id] = $have + $take; $missing -= $take; }
        }
        if ($missing > 0) continue;   // cannot be made legal; leave it alone

        $out = [];
        foreach ($kept as $id => $q) $out[] = ['card_id' => $id, 'quantity' => $q];
        $problem = deck_problem($out);
        if ($problem !== null) continue;      // never write a deck we just broke

        db()->prepare(
            'UPDATE decks SET cards_json = ?, revision = revision + 1,
                              updated_at = NOW() WHERE player_id = ?')
            ->execute([json_encode($out), (int)$d['player_id']]);
        $changed++;
    }
    return $changed;
}

function deck_problem(array $entries): ?string {
    $cards = catalogue();
    if (!$cards) return 'catalogue unavailable';
    $banned = array_flip(banned_ids());
    $total = 0;
    $seen = [];
    foreach ($entries as $e) {
        if (!is_array($e) || !isset($e['card_id'], $e['quantity'])) {
            return 'each entry needs card_id and quantity';
        }
        $id = (int)$e['card_id'];
        $q  = (int)$e['quantity'];
        if ($q < 1) return "quantity must be positive (card $id)";
        if (isset($seen[$id])) return "card $id listed twice";
        $seen[$id] = true;
        if (!isset($cards[$id])) return "unknown card $id";
        if (isset($banned[$id])) {
            return "{$cards[$id]['name']} is banned this season";
        }
        // Checked here as well as on the phone: the client filters retired
        // cards when it BUILDS, but nothing stopped a deck containing one
        // from being stored, and a card retired after a deck was saved would
        // otherwise sit in it indefinitely.
        if (!empty($cards[$id]['is_retired'])) {
            return "{$cards[$id]['name']} is retired and cannot be played";
        }
        $limit = (int)($cards[$id]['deck_limit'] ?? 0);
        if ($limit <= 0) return "{$cards[$id]['name']} cannot be put in a deck";
        if ($q > $limit) {
            return "{$cards[$id]['name']}: $q copies exceeds the limit of $limit";
        }
        $total += $q;
    }
    if ($total !== 100) return "deck must be exactly 100 cards, got $total";
    return null;
}


/**
 * When the next round will be scored, as UTC "Y-m-d H:i:s".
 *
 * Mirrors agentgame-round.timer's OnCalendar=*:04/30. Without this the
 * standings look frozen -- a player trains, submits, and then stares at an
 * unchanged table with nothing telling them a round is coming. The cadence is
 * stated in two places, which is a real (small) duplication; the alternative
 * is parsing systemd from PHP, and the timer moving without this changing
 * would show a time a few minutes out rather than break anything.
 */
function next_round_at(): string {
    $now = new DateTimeImmutable('now', new DateTimeZone('UTC'));
    // Scheduled minutes are 4 and 34. The clamp below is for minutes 0-3,
    // where the arithmetic would otherwise skip past :04 and promise :34 --
    // wrong for four minutes of every hour. Checked against all 60.
    $m = (int)$now->format('i');
    $next = $m < 4 ? 4 : (intdiv($m - 4, 30) + 1) * 30 + 4;
    $base = $now->setTime((int)$now->format('H'), 0, 0);
    return $base->modify("+$next minutes")->format('Y-m-d H:i:s');
}
