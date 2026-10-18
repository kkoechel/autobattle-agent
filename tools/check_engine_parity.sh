#!/usr/bin/env bash
# Does the engine in the app match the engine that scores the league?
#
# WHY THIS EXISTS. engine.aar is gitignored -- a build input nobody versions.
# It was built on 24 September and nothing rebuilt it when the validator was
# rebuilt, so by 18 October the phone ran a September engine against a v4.42
# league: every win count shown to a player, including the "show me what it
# can do" trial, was measured under different rules from the ones deciding
# the result. Three weeks, silently.
#
# The version cannot be grepped out of the AAR -- Go packs string literals
# into one blob, so "4.42" appears inside a larger run and an anchored match
# finds unrelated numbers like "98.9" instead. So the build records what it
# produced in engine.aar.json, which IS tracked, and this checks that the
# file on disk is still that artefact and that its version is the live one.
#
# Run before every release.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
AAR=${AAR:-$HERE/../android/app/libs/engine.aar}
META=${META:-$HERE/../android/app/libs/engine.aar.json}
HOST=${HOST:-206.189.231.147}
USER_=${USER_:-root}
KEY=${KEY:-$HOME/AB-DROPLET-ROOT.PRIV}
VALIDATOR=${VALIDATOR:-/opt/abagent/bin/validate_linux_amd64}

[ -f "$AAR" ]  || { echo "no AAR at $AAR" >&2; exit 1; }
[ -f "$META" ] || { echo "no $META -- rebuild the AAR and record it" >&2; exit 1; }

WANT_SHA=$(python3 -c "import json;print(json.load(open('$META'))['sha256'])")
WANT_VER=$(python3 -c "import json;print(json.load(open('$META'))['version'])")
HAVE_SHA=$(sha256sum "$AAR" | cut -d' ' -f1)

if [ "$WANT_SHA" != "$HAVE_SHA" ]; then
    echo "  the AAR on disk is NOT the one recorded in engine.aar.json" >&2
    echo "    recorded $WANT_SHA" >&2
    echo "    on disk  $HAVE_SHA" >&2
    exit 1
fi

SRV=$(ssh -i "$KEY" -o StrictHostKeyChecking=accept-new -o IdentitiesOnly=yes \
        -o ConnectTimeout=15 "$USER_@$HOST" "$VALIDATOR --version" \
      | grep -oE 'v[0-9]+\.[0-9]+' | tr -d v)
[ -n "$SRV" ] || { echo "  could not read the league's validator version" >&2; exit 2; }

echo "  league scores with : v$SRV"
echo "  AAR is             : v$WANT_VER (checksum matches)"
[ "$SRV" = "$WANT_VER" ] && { echo "  PARITY OK"; exit 0; }
echo "  MISMATCH: rebuild the AAR from the engine source at v$SRV" >&2
exit 1
