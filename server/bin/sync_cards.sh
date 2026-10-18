#!/usr/bin/env bash
# Pull the card catalogue for the forked game, in the art style we want.
#
# The main game holds several art pieces per card, tagged by style, and its
# v1 API takes ?art_style=<tag> to pick between them. Without it every card
# comes back with its DEFAULT art, which is a mix of styles.
#
# This replaced an export that read the main game's DATABASE directly and
# merged a card_id -> image_url map in here. The API is strictly safer and is
# now the single place that decides which piece is a card's art, so there is
# nothing left to drift.
#
# ONE GET, NOTHING ELSE. The credential comes from /etc/abagent.env via the
# unit, is used for a single read, and the main game is never written to.
# A failed fetch falls back to abagent's own cached catalogue rather than
# leaving the game without one -- abagent refreshes that file every cycle, so
# the fallback is current, it just carries default art instead of the style.
set -euo pipefail
STYLE=${STYLE:-vectorposter}
API=${API:-https://autobattle.online/api/v1/cards}
SRC=${SRC:-/opt/abagent/var/cards.json}
DST=${DST:-/var/www/games/agentgame/var/cards.json}
# Overridable so the build can be exercised off-server, where chown is denied.
OWNER=${OWNER:-www-data}
GROUP=${GROUP:-www-data}

TMP=$(mktemp); trap 'rm -f "$TMP"' EXIT

N=$(API="$API" STYLE="$STYLE" SRC="$SRC" TMP="$TMP" python3 - <<'PY'
import json, os, sys, urllib.request

api, style = os.environ["API"], os.environ["STYLE"]
key = os.environ.get("AB_API_KEY", "")
cards, source = None, ""

if key:
    try:
        req = urllib.request.Request(f"{api}?art_style={style}",
                                     headers={"Authorization": "Bearer " + key,
                                              "User-Agent": "agentgame-sync"})
        with urllib.request.urlopen(req, timeout=120) as r:
            cards = json.loads(r.read().decode())["cards"]
        source = f"api({style})"
    except Exception as e:
        print(f"api fetch failed, falling back to abagent cache: {e}", file=sys.stderr)
else:
    print("AB_API_KEY not set, falling back to abagent cache", file=sys.stderr)

if cards is None:
    cards = json.load(open(os.environ["SRC"]))
    source = "abagent-cache"

if not isinstance(cards, list):
    sys.exit("catalogue is not a JSON list")

# Stable encoding: this output is byte-compared against the live file to
# decide whether anything changed, so key order and separators must not drift
# between runs.
with open(os.environ["TMP"], "w") as fh:
    json.dump(cards, fh, sort_keys=True, separators=(",", ":"))
print(f"{len(cards)} {source}")
PY
) || { echo "could not build catalogue" >&2; exit 1; }

COUNT=${N%% *}
SOURCE=${N##* }
# A truncated catalogue would make every deck illegal, so it never lands.
[ "$COUNT" -ge 500 ] || { echo "only $COUNT cards from $SOURCE; refusing" >&2; exit 1; }

if [ -s "$DST" ] && cmp -s "$TMP" "$DST"; then
    echo "catalogue unchanged ($COUNT cards, $SOURCE)"; exit 0
fi
install -m 0644 -o "$OWNER" -g "$GROUP" "$TMP" "$DST.tmp"
mv -f "$DST.tmp" "$DST"
echo "catalogue synced: $COUNT cards, $SOURCE"
