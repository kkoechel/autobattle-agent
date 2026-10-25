#!/usr/bin/env bash
# Notice when the league's engine moves, so a stale app AAR cannot ship unseen.
#
# WHY ONLY THE LEAGUE VERSION. The real question is "does the phone's engine
# match the league's", but the AAR lives on a laptop that is not always on and
# is gitignored besides, so this box cannot read it. It does not need to: ANY
# move of the league engine is the signal to go and check. On 2026-10-25 the
# league went 4.44 -> 4.49 -> 4.51 in one day and the app shipped against
# 4.44 for weeks, where 8 of 12 matches in a controlled comparison resolved
# differently. One line in a log would have caught it.
#
# Deliberately does NOT rebuild anything. ENGINE.md warns that building from a
# tree AHEAD of the league is the same fault mirrored, and that call wants a
# human. This only reports.
set -euo pipefail

VALIDATOR=/opt/abagent/bin/validate_linux_amd64
STATE=/opt/abagent/var/engine_seen.txt
OUT=/opt/abagent/var/engine_drift.json

now=$("$VALIDATOR" --version 2>&1 | grep -oE 'v[0-9]+\.[0-9]+' | head -1)
[ -z "$now" ] && { echo "engine-watch: could not read validator version"; exit 1; }

prev=""
[ -f "$STATE" ] && prev=$(cat "$STATE")

if [ "$prev" != "$now" ] && [ -n "$prev" ]; then
  echo "ENGINE DRIFT: league moved $prev -> $now. The app's engine.aar must be"
  echo "ENGINE DRIFT: rebuilt from that version, or every number it shows a"
  echo "ENGINE DRIFT: player is computed under different rules. Run"
  echo "ENGINE DRIFT: tools/check_engine_parity.sh in the autobattle-agent repo."
  changed=true
else
  echo "engine-watch: league at $now (unchanged)"
  changed=false
fi

printf '{"league":"%s","previous":"%s","changed":%s,"checked_at":"%s"}\n' \
  "$now" "${prev:-$now}" "$changed" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$OUT"
echo "$now" > "$STATE"
