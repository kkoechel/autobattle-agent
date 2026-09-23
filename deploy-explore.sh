#!/usr/bin/env bash
# Deploy the exploration agent alongside the competing one.
#
# Shares the code tree at /opt/abagent and the validator binary; keeps its own
# cache directory, credentials and timer. Run deploy.sh first -- this assumes
# the tree, the user and the binary already exist.
set -euo pipefail

HOST="${ABAGENT_HOST:-206.189.231.147}"
KEY="${ABAGENT_KEY:-$HOME/AB-DROPLET-ROOT.PRIV}"
SSH=(ssh -i "$KEY" -o IdentitiesOnly=yes -o ConnectTimeout=15 "root@$HOST")

for v in AB_API_KEY ABAGENT_DECK_ID ABAGENT_SECOND_DECK_ID; do
  [ -n "${!v:-}" ] || { echo "$v must be set (the EXPLORER account's values)" >&2; exit 1; }
done

echo "==> checking for unbound names"
python3 tools/check_names.py || { echo "refusing to deploy" >&2; exit 1; }

echo "==> syncing code and units"
rsync -az --delete -e "ssh -i $KEY -o IdentitiesOnly=yes" --exclude '__pycache__' \
  abagent/ "root@$HOST:/opt/abagent/abagent/"
rsync -az -e "ssh -i $KEY -o IdentitiesOnly=yes" \
  deploy/abagent-explore.service deploy/abagent-explore.timer \
  "root@$HOST:/etc/systemd/system/"

echo "==> credentials (0600) and cache dir"
printf 'AB_API_KEY=%s\nABAGENT_DECK_ID=%s\nABAGENT_SECOND_DECK_ID=%s\n' \
  "$AB_API_KEY" "$ABAGENT_DECK_ID" "$ABAGENT_SECOND_DECK_ID" | "${SSH[@]}" \
  'umask 077 && cat > /etc/abagent-explore.env \
   && chown abagent:abagent /etc/abagent-explore.env \
   && mkdir -p /opt/abagent/var-explore \
   && chown -R abagent:abagent /opt/abagent/var-explore'

echo "==> reloading units (timer NOT enabled)"
"${SSH[@]}" 'systemctl daemon-reload'

cat <<NEXT

==> installed. The explorer writes ONLY the rental slot, never the primary.

  one run by hand:
    ssh root@$HOST systemctl start abagent-explore.service && \\
    ssh root@$HOST journalctl -u abagent-explore -n 40 --no-pager

  then hourly:
    ssh root@$HOST systemctl enable --now abagent-explore.timer

  stop:  ssh root@$HOST systemctl disable --now abagent-explore.timer
NEXT
