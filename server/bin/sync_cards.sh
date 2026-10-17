#!/usr/bin/env bash
# Pull the card catalogue for the forked game.
#
# Deliberately copies abagent's already-synced file rather than calling the
# main game's API again: abagent refreshes /opt/abagent/var/cards.json from
# GET /api/v1/cards every cycle, so this needs no second credential on the
# box and cannot write to the main game even by accident. Validated before it
# replaces anything -- a truncated catalogue would make every deck illegal.
set -euo pipefail
SRC=${SRC:-/opt/abagent/var/cards.json}
DST=${DST:-/var/www/games/agentgame/var/cards.json}
[ -s "$SRC" ] || { echo "source catalogue missing or empty: $SRC" >&2; exit 1; }
N=$(python3 -c "import json,sys; print(len(json.load(open('$SRC'))))") || {
    echo "source catalogue is not valid JSON" >&2; exit 1; }
[ "$N" -ge 500 ] || { echo "only $N cards in source; refusing" >&2; exit 1; }
if [ -s "$DST" ] && cmp -s "$SRC" "$DST"; then
    echo "catalogue unchanged ($N cards)"; exit 0
fi
install -m 0644 -o www-data -g www-data "$SRC" "$DST.tmp"
mv -f "$DST.tmp" "$DST"
echo "catalogue synced: $N cards"
