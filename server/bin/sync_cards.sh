#!/usr/bin/env bash
# Pull the card catalogue for the forked game.
#
# Deliberately copies abagent's already-synced file rather than calling the
# main game's API again: abagent refreshes /opt/abagent/var/cards.json from
# GET /api/v1/cards every cycle, so this needs no second credential on the
# box and cannot write to the main game even by accident. Validated before it
# replaces anything -- a truncated catalogue would make every deck illegal.
#
# ART STYLE. If var/art_style.json is present it maps card_id -> image_url for
# one art style, and is merged in here rather than at serve time: GET /cards
# streams this file verbatim, and decoding a megabyte of JSON per request to
# rewrite one field would be a real cost for a value that changes hourly at
# most. See tools/export_art_style.sh for where the map comes from. When the
# main game's v1 API can serve a style directly, this block and that script
# both come out and nothing else changes.
set -euo pipefail
SRC=${SRC:-/opt/abagent/var/cards.json}
DST=${DST:-/var/www/games/agentgame/var/cards.json}
ART=${ART:-/var/www/games/agentgame/var/art_style.json}
# Overridable so the merge can be exercised off-server, where chown is denied.
OWNER=${OWNER:-www-data}
GROUP=${GROUP:-www-data}
[ -s "$SRC" ] || { echo "source catalogue missing or empty: $SRC" >&2; exit 1; }

TMP=$(mktemp); trap 'rm -f "$TMP"' EXIT

N=$(ART="$ART" SRC="$SRC" TMP="$TMP" python3 - <<'PY'
import json, os, sys

src = json.load(open(os.environ["SRC"]))
if not isinstance(src, list):
    sys.exit("source catalogue is not a JSON list")

art = {}
path = os.environ["ART"]
if os.path.exists(path):
    art = {int(k): v for k, v in json.load(open(path)).items() if v}

applied = 0
for c in src:
    url = art.get(int(c["id"]))
    if not url:
        continue
    c["image_url"] = url
    # The thumbnail is generated from the card's DEFAULT art and there is no
    # per-piece thumbnail, so leaving it would pair one style's thumb with
    # another style's full art. The main game drops it for exactly this
    # reason; nothing in the agent game reads it.
    if "thumbnail_url" in c:
        c["thumbnail_url"] = None
    applied += 1

# Stable encoding: this output is byte-compared against the live file to
# decide whether anything changed, so key order and separators must not drift
# between runs.
with open(os.environ["TMP"], "w") as fh:
    json.dump(src, fh, sort_keys=True, separators=(",", ":"))
print(f"{len(src)} {applied}")
PY
) || { echo "could not build catalogue from $SRC" >&2; exit 1; }

COUNT=${N%% *}
APPLIED=${N##* }
[ "$COUNT" -ge 500 ] || { echo "only $COUNT cards in source; refusing" >&2; exit 1; }

if [ -s "$DST" ] && cmp -s "$TMP" "$DST"; then
    echo "catalogue unchanged ($COUNT cards, $APPLIED styled)"; exit 0
fi
install -m 0644 -o "$OWNER" -g "$GROUP" "$TMP" "$DST.tmp"
mv -f "$DST.tmp" "$DST"
echo "catalogue synced: $COUNT cards, $APPLIED styled"
