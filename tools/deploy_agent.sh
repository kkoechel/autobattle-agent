#!/usr/bin/env bash
# Deploy the Python agent package and/or the coordinator to subgames.
#
# WHY THIS EXISTS. deploy.sh installs /opt/abagent wholesale and needs four
# secrets in the environment, which is right for a first install and heavy for
# "two modules changed". The coordinator under /var/www/games/agentgame had no
# script at all and was being copied by hand, which is how a file drifts from
# the repo without anyone noticing.
#
# It refuses to deploy a tree that differs from the repo in files it is not
# being asked to send, verifies every file by checksum ON THE HOST after
# copying, and keeps a dated backup of whatever it overwrote. A deploy that
# says "done" without comparing the bytes it left behind is a deploy that has
# silently dropped files before.
#
#   tools/deploy_agent.sh agent        the abagent package -> /opt/abagent
#   tools/deploy_agent.sh coordinator  server/{inc,public,bin} + units
#   tools/deploy_agent.sh both
#   tools/deploy_agent.sh agent --dry  show what would change, send nothing
set -euo pipefail

HOST="${ABAGENT_SSH_HOST:-subgames}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
WHAT="${1:-}"; shift || true
DRY=""; [ "${1:-}" = "--dry" ] && DRY=1

case "$WHAT" in
  agent|coordinator|both) ;;
  *) echo "usage: $0 {agent|coordinator|both} [--dry]" >&2; exit 2 ;;
esac

say() { printf '==> %s\n' "$*"; }

# A NameError is valid syntax, so py_compile and ast.parse both pass it.
# Same gate deploy.sh uses, for the same reason.
if [ "$WHAT" != "coordinator" ]; then
  say "checking for unbound names"
  python3 "$REPO/tools/check_names.py" || { echo "refusing to deploy" >&2; exit 1; }
fi

# ---------------------------------------------------------------- compare ---
# Returns the files that differ, and separately warns about files that differ
# but are NOT ours to send -- the drift signal.
compare() {   # compare <local dir> <remote dir> <glob>
  local ldir="$1" rdir="$2" glob="$3" f l r
  for f in $(cd "$ldir" && ls $glob 2>/dev/null); do
    l=$(md5sum "$ldir/$f" | cut -d' ' -f1)
    r=$(ssh -n "$HOST" "md5sum '$rdir/$f' 2>/dev/null | cut -d' ' -f1" || true)
    [ "$l" = "$r" ] || printf '%s\n' "$f"
  done
}

push() {      # push <local dir> <remote dir> <owner> <files...>
  local ldir="$1" rdir="$2" owner="$3"; shift 3
  [ $# -gt 0 ] || { say "nothing to send to $rdir"; return 0; }
  say "sending to $rdir: $*"
  if [ -n "$DRY" ]; then say "(dry run)"; return 0; fi
  ssh -n "$HOST" "mkdir -p /root/deploy-bak-$STAMP$rdir"
  local f
  for f in "$@"; do
    ssh -n "$HOST" "[ -f '$rdir/$f' ] && cp -a '$rdir/$f' '/root/deploy-bak-$STAMP$rdir/$f' || true"
    scp -q "$ldir/$f" "$HOST:$rdir/$f"
  done
  ssh -n "$HOST" "chown $owner $(printf "'%s/%s' " "$rdir" "$@")"
  # VERIFY ON THE HOST, per file. The copy reporting success is not evidence
  # that the right bytes are there.
  local bad=0
  for f in "$@"; do
    local l r
    l=$(md5sum "$ldir/$f" | cut -d' ' -f1)
    r=$(ssh -n "$HOST" "md5sum '$rdir/$f' | cut -d' ' -f1")
    if [ "$l" = "$r" ]; then printf '    ok  %s\n' "$f"
    else printf '    MISMATCH %s (local %s / host %s)\n' "$f" "$l" "$r"; bad=1; fi
  done
  [ "$bad" = 0 ] || { echo "deploy FAILED verification; backup at /root/deploy-bak-$STAMP" >&2; exit 1; }
}

if [ "$WHAT" = "agent" ] || [ "$WHAT" = "both" ]; then
  say "comparing abagent/ against $HOST:/opt/abagent/abagent"
  mapfile -t changed < <(compare "$REPO/abagent" /opt/abagent/abagent '*.py')
  push "$REPO/abagent" /opt/abagent/abagent abagent:abagent "${changed[@]}"
fi

if [ "$WHAT" = "coordinator" ] || [ "$WHAT" = "both" ]; then
  for pair in "server/inc:/var/www/games/agentgame/inc:*.php:www-data:www-data" \
              "server/public:/var/www/games/agentgame/public:*.php:www-data:www-data" \
              "server/bin:/var/www/games/agentgame/bin:*.py:root:root" \
              "server/deploy:/etc/systemd/system:*.service:root:root" \
              "server/deploy:/etc/systemd/system:*.timer:root:root"; do
    IFS=: read -r sub rdir glob ow gr <<<"$pair"
    say "comparing $sub against $HOST:$rdir ($glob)"
    mapfile -t changed < <(compare "$REPO/$sub" "$rdir" "$glob")
    push "$REPO/$sub" "$rdir" "$ow:$gr" "${changed[@]}"
  done
  if [ -z "$DRY" ]; then
    say "reloading systemd"
    ssh -n "$HOST" 'systemctl daemon-reload'
    say "php -l on what was sent"
    ssh -n "$HOST" 'for f in /var/www/games/agentgame/inc/*.php /var/www/games/agentgame/public/*.php; do php -l "$f" >/dev/null || echo "SYNTAX ERROR: $f"; done; echo "lint done"'
  fi
fi

say "done${DRY:+ (dry run)}; backups under /root/deploy-bak-$STAMP"
