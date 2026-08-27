#!/usr/bin/env bash
# Entrypoint for the isolated agent.
#
# First start: enroll with the one-off setup key the bootstrap left on the
# tmpfs mount, over --setup-key-file — the key never appears in argv, compose
# configuration, or the image. Every later start: the client state volume
# already holds the peer identity, and `netbird up` reconnects without any
# key, which is what lets the key be single-use and revoked.
set -euo pipefail

log() { echo "agent-entry: $*" >&2; }

KEY_FILE=/var/run/agent-secret/setup_key
MGMT="https://<{ agent-network-host }>"

/usr/local/bin/netbird service run &
daemon=$!

for i in $(seq 1 30); do
  netbird status >/dev/null 2>&1 && break
  sleep 1
done

if netbird status 2>&1 | grep -qiE 'NeedsLogin|not logged in|Disconnected'; then
  if netbird status 2>&1 | grep -qiE 'NeedsLogin|not logged in'; then
    if [[ -s $KEY_FILE ]]; then
      log "enrolling with the one-off setup key"
      netbird up --setup-key-file "$KEY_FILE" --management-url "$MGMT"
    else
      log "no stored identity and no setup key; connecting will fail until one is provided"
      netbird up --management-url "$MGMT" || true
    fi
  else
    netbird up --management-url "$MGMT" || true
  fi
else
  netbird up --management-url "$MGMT" || true
fi

log "agent is up; holding"
wait "$daemon"
