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
printf 'AB_API_KEY=%s\n' "$AB_API_KEY" | "${SSH[@]}" \
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

echo "==> enabling timer"
"${SSH[@]}" 'systemctl daemon-reload && systemctl enable --now abagent.timer \
  && systemctl list-timers abagent.timer --no-pager'

echo "==> done"
echo "    logs:   ssh root@$HOST journalctl -u abagent -f"
echo "    status: ssh root@$HOST systemctl list-timers abagent.timer"
echo "    stop:   ssh root@$HOST systemctl disable --now abagent.timer"
