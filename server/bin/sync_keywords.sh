#!/usr/bin/env bash
# Pull the keyword glossary from the main game, as JSON.
#
# Card text prints bare keywords -- "Shatter", "Dispel", "Gravecast" -- and a
# player who has never seen the main game cannot know what they mean. The
# glossary is pure data (inc/card_keywords.php returns a literal array, no
# database), so it is exported rather than reimplemented.
#
# Read-only against main, like the card catalogue, and run from a machine that
# can reach the autobattle host. Keywords change rarely, so this is manual
# rather than a timer -- re-run it when the main game adds one.
set -euo pipefail
HOST=${HOST:-autobattle}
OUT=${OUT:-/tmp/keywords.json}
ssh "$HOST" 'cd /var/www/autobattle && php -r "
require \"inc/card_keywords.php\";
echo json_encode(card_keywords(), JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE);
"' > "$OUT"
N=$(python3 -c "import json;print(len(json.load(open('$OUT'))))")
[ "$N" -ge 20 ] || { echo "only $N keywords; refusing" >&2; exit 1; }
echo "exported $N keywords to $OUT"
