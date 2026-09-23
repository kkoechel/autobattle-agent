#!/usr/bin/env bash
# Deploy abagent to subgames-nyc1.
#
# The droplet also serves live Reprisal, Ossuary, Mage Wars and Blind Wizards,
# so this installs under /opt (never /var/www, which is web-served) and runs as
# an unprivileged system user with no shell.
set -euo pipefail

HOST="${ABAGENT_HOST:-206.189.231.147}"   # reprisal.autobattle.online
KEY="${ABAGENT_KEY:-$HOME/AB-DROPLET-ROOT.PRIV}"
DEST=/opt/abagent
SSH=(ssh -i "$KEY" -o IdentitiesOnly=yes -o ConnectTimeout=15 "root@$HOST")

[ -n "${AB_API_KEY:-}" ] || { echo "AB_API_KEY must be set" >&2; exit 1; }
[ -n "${ABAGENT_DECK_ID:-}" ] || { echo "ABAGENT_DECK_ID must be set (the deck the agent owns; make one with 'clone')" >&2; exit 1; }
[ -n "${ABAGENT_SECOND_DECK_ID:-}" ] || { echo "ABAGENT_SECOND_DECK_ID must be set (the Double Entry Pass slot)" >&2; exit 1; }

# A NameError is valid syntax, so py_compile and ast.parse both pass it. This
# is the gate that would have stopped a broken cmd_cycle reaching the timer.
echo "==> checking for unbound names"
python3 tools/check_names.py || { echo "refusing to deploy" >&2; exit 1; }

echo "==> preparing $HOST:$DEST"
"${SSH[@]}" bash -s <<'REMOTE'
set -euo pipefail
id -u abagent >/dev/null 2>&1 || useradd --system --home-dir /opt/abagent \
  --shell /usr/sbin/nologin abagent
mkdir -p /opt/abagent/abagent /opt/abagent/bin /opt/abagent/var
chown -R abagent:abagent /opt/abagent
REMOTE

echo "==> syncing code"
rsync -az --delete -e "ssh -i $KEY -o IdentitiesOnly=yes" \
  --exclude '__pycache__' \
  abagent/ "root@$HOST:$DEST/abagent/"
rsync -az -e "ssh -i $KEY -o IdentitiesOnly=yes" \
  deploy/abagent.service deploy/abagent.timer "root@$HOST:/etc/systemd/system/"

# The key goes over stdin, never on a command line -- an argv is visible to
# every user on the box via ps for as long as the command runs.
echo "==> installing credentials (0600, abagent-owned)"
printf 'AB_API_KEY=%s\nABAGENT_DECK_ID=%s\nABAGENT_SECOND_DECK_ID=%s\n' \
  "$AB_API_KEY" "$ABAGENT_DECK_ID" "$ABAGENT_SECOND_DECK_ID" | "${SSH[@]}" \
  'umask 077 && cat > /etc/abagent.env && chown abagent:abagent /etc/abagent.env'

echo "==> fetching validator + card catalogue as abagent"
"${SSH[@]}" bash -s <<'REMOTE'
set -euo pipefail
chown -R abagent:abagent /opt/abagent
cd /opt/abagent
sudo -u abagent --preserve-env=AB_API_KEY \
  env "$(grep '^AB_API_KEY=' /etc/abagent.env)" \
  python3 -u -m abagent.cli --arena pure fetch
REMOTE

echo "==> reloading units (timer NOT enabled)"
"${SSH[@]}" 'systemctl daemon-reload'

# Deliberately does not start the timer. Enabling it means the agent begins
# editing and registering a real deck into real cohorts against real players,
# which is a decision to make deliberately after watching one cycle by hand.
cat <<NEXT

==> installed, timer is NOT running.

  dry run one cycle:
    ssh root@$HOST 'systemd-run --uid=abagent --pipe --wait \\
      --property=EnvironmentFile=/etc/abagent.env \\
      --working-directory=/opt/abagent \\
      /usr/bin/python3 -u -m abagent.cli --arena pure --deck-id <ID> cycle'

  then, to let it run every 10 minutes:
    ssh root@$HOST systemctl enable --now abagent.timer

  logs:   ssh root@$HOST journalctl -u abagent -f
  stop:   ssh root@$HOST systemctl disable --now abagent.timer
NEXT
