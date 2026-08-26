#!/usr/bin/env bash
# The second isolation boundary, independent of Docker's own: DOCKER-USER
# rules that confine the agent subnet to itself. Implemented against the
# pinned Docker firewall backend (iptables via iptables-nft on Ubuntu) — the
# DOCKER-USER chain is that backend's contract; no native-nftables chain
# layout is assumed.
#
#   1. traffic within the agent subnet is returned to Docker's own handling —
#      its only members are Traefik, the reverse proxy, and the agent, which
#      is exactly the reachable surface the demo intends;
#   2. anything else sourced from the agent subnet is dropped, so even if the
#      `internal: true` boundary regressed, the agent still has no path out.
#
# Idempotent (-C before -I) and re-run by systemd whenever Docker (re)starts,
# because a Docker restart rebuilds DOCKER-USER without these rules.
set -euo pipefail

AGENT_SUBNET="<{ agent-network-agent-subnet }>"

# Wait for Docker to have created the chain.
for i in $(seq 1 30); do
  iptables -L DOCKER-USER -n >/dev/null 2>&1 && break
  sleep 2
done

iptables -C DOCKER-USER -s "$AGENT_SUBNET" -d "$AGENT_SUBNET" -j RETURN 2>/dev/null \
  || iptables -I DOCKER-USER 1 -s "$AGENT_SUBNET" -d "$AGENT_SUBNET" -j RETURN
iptables -C DOCKER-USER -s "$AGENT_SUBNET" -j DROP 2>/dev/null \
  || iptables -I DOCKER-USER 2 -s "$AGENT_SUBNET" -j DROP

echo "agent-network-firewall: DOCKER-USER rules present for $AGENT_SUBNET"
