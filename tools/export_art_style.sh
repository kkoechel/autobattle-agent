#!/usr/bin/env bash
# Export one art STYLE from the main game and hand it to the agent coordinator.
#
# WHY THIS EXISTS. The main game holds several art pieces per card (2,959
# submissions over 846 cards) tagged by style -- woodblock, schiele,
# vectorposter, classic -- and picks between them per user via
# users.preferred_art_style. Its v1 API does NOT apply that preference, so the
# catalogue the agent game syncs carries each card's DEFAULT art rather than
# the style we want. This bridges that gap until the API exposes style
# directly, at which point this script and the merge step in sync_cards.sh
# both come out.
#
# READ-ONLY AGAINST MAIN. One SELECT, run from a temp file that deletes
# itself; nothing is written to the main game's database, web root or config.
# The only thing this changes is a file on the agent coordinator.
set -euo pipefail

STYLE=${STYLE:-vectorposter}
MAIN=${MAIN:-157.245.113.109}          # app2-nyc3
MAIN_USER=${MAIN_USER:-ab}
COORD=${COORD:-206.189.231.147}        # subgames-nyc1
COORD_USER=${COORD_USER:-root}
KEY=${KEY:-$HOME/AB-DROPLET-ROOT.PRIV}
DEST=${DEST:-/var/www/games/agentgame/var/art_style.json}

SSH=(ssh -i "$KEY" -o StrictHostKeyChecking=accept-new -o IdentitiesOnly=yes -o ConnectTimeout=15)
SCP=(scp -q -i "$KEY" -o StrictHostKeyChecking=accept-new -o IdentitiesOnly=yes -o ConnectTimeout=15)

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
REMOTE=/tmp/ab_art_export_$$.php

cat > "$TMP/export.php" <<'PHP'
<?php
// Read-only. Emits {"<card_id>": "<image_url>", ...} for one style tag.
require_once '/var/www/autobattle/inc/db.php';
$style = $argv[1] ?? 'vectorposter';
$db = get_db();

// The ordering is copied from card_art_submit.php's own style pass so this
// picks the SAME piece the website would show a player with that preference:
//   1. the card's own default art, if it carries the tag
//   2. then most-voted
//   3. then newest
// Diverging here would mean the agent game quietly showed different art from
// the game it forked its catalogue from.
$sql = "SELECT s.card_id, s.image_url
        FROM card_art_submissions s
        JOIN card_art_tags t ON t.art_submission_id = s.id AND t.tag = ?
        JOIN cards c ON c.id = s.card_id
        WHERE s.moderation_status = 'approved' AND s.status <> 'rejected'
        ORDER BY s.card_id, (s.image_url = c.image_url) DESC, s.vote_count DESC, s.id DESC";
$st = $db->prepare($sql);
$st->execute([$style]);

$out = [];
foreach ($st->fetchAll(PDO::FETCH_ASSOC) as $r) {
    $cid = (string)(int)$r['card_id'];
    if (isset($out[$cid])) continue;          // first per card wins, per the ORDER BY
    if (($r['image_url'] ?? '') === '') continue;
    $out[$cid] = $r['image_url'];
}
fwrite(STDERR, sprintf("%s: %d cards\n", $style, count($out)));
echo json_encode($out, JSON_UNESCAPED_SLASHES);
PHP

echo "exporting '$STYLE' from $MAIN ..."
"${SCP[@]}" "$TMP/export.php" "$MAIN_USER@$MAIN:$REMOTE"
"${SSH[@]}" "$MAIN_USER@$MAIN" "php $REMOTE '$STYLE'; rm -f $REMOTE" > "$TMP/art_style.json"

# Validate before it goes anywhere. A truncated or empty map would silently
# revert every card to default art on the next sync, which looks like nothing
# happened rather than like a failure.
N=$(python3 -c "import json,sys; d=json.load(open('$TMP/art_style.json')); print(len(d))")
[ "$N" -ge 500 ] || { echo "only $N cards in the export; refusing to publish" >&2; exit 1; }
echo "exported $N cards"

"${SCP[@]}" "$TMP/art_style.json" "$COORD_USER@$COORD:$DEST.tmp"
"${SSH[@]}" "$COORD_USER@$COORD" "
  install -m 0644 -o www-data -g www-data '$DEST.tmp' '$DEST' && rm -f '$DEST.tmp' &&
  /var/www/games/agentgame/bin/sync_cards.sh"
echo "published to $COORD:$DEST and re-synced"
