#!/usr/bin/env bash
# Discovery agent for ShadowDrake's rental slot. Shares the code tree and the
# validator with the other two services; own cache dir, credentials and timer.
set -euo pipefail
HOST="${ABAGENT_HOST:-206.189.231.147}"
KEY="${ABAGENT_KEY:-$HOME/AB-DROPLET-ROOT.PRIV}"
SSH=(ssh -i "$KEY" -o IdentitiesOnly=yes -o ConnectTimeout=15 "root@$HOST")
for v in AB_API_KEY ABAGENT_DECK_ID ABAGENT_SECOND_DECK_ID; do
  [ -n "${!v:-}" ] || { echo "$v must be set" >&2; exit 1; }
done
python3 tools/check_names.py || { echo "refusing to deploy" >&2; exit 1; }
rsync -az --delete -e "ssh -i $KEY -o IdentitiesOnly=yes" --exclude '__pycache__' \
  abagent/ "root@$HOST:/opt/abagent/abagent/"
rsync -az -e "ssh -i $KEY -o IdentitiesOnly=yes" \
  deploy/abagent-discover.service deploy/abagent-discover.timer \
  deploy/abagent-explore.service deploy/abagent-explore.timer \
  "root@$HOST:/etc/systemd/system/"
printf 'AB_API_KEY=%s\nABAGENT_DECK_ID=%s\nABAGENT_SECOND_DECK_ID=%s\n' \
  "$AB_API_KEY" "$ABAGENT_DECK_ID" "$ABAGENT_SECOND_DECK_ID" | "${SSH[@]}" \
  'umask 077 && cat > /etc/abagent-discover.env \
   && chown abagent:abagent /etc/abagent-discover.env \
   && mkdir -p /opt/abagent/var-discover \
   && chown -R abagent:abagent /opt/abagent/var-discover'
"${SSH[@]}" 'systemctl daemon-reload && systemctl restart abagent-explore.timer'
echo "installed. enable with: ssh root@$HOST systemctl enable --now abagent-discover.timer"
