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
 * Mirrors agentgame-round.timer's OnCalendar=*:02/15. Without this the
 * standings look frozen -- a player trains, submits, and then stares at an
 * unchanged table with nothing telling them a round is coming. The cadence is
 * stated in two places, which is a real (small) duplication; the alternative
 * is parsing systemd from PHP, and the timer moving without this changing
 * would show a time a few minutes out rather than break anything.
 */
function next_round_at(): string {
    $now = new DateTimeImmutable('now', new DateTimeZone('UTC'));
    // Scheduled minutes are 2, 17, 32, 47. The clamp below is for minutes 0
    // and 1, where the arithmetic would otherwise skip past :02 and promise
    // :17 -- wrong for two minutes of every hour. Checked against all 60.
    $m = (int)$now->format('i');
    $next = $m < 2 ? 2 : (intdiv($m - 2, 15) + 1) * 15 + 2;
    $base = $now->setTime((int)$now->format('H'), 0, 0);
    return $base->modify("+$next minutes")->format('Y-m-d H:i:s');
}
