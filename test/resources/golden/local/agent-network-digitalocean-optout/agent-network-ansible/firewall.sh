#!/usr/bin/env bash
# The second isolation boundary, independent of Docker's own: DOCKER-USER
# rules that confine the agent subnet to its attachment matrix. Implemented
# against the pinned Docker firewall backend (iptables via iptables-nft on
# Ubuntu) — the DOCKER-USER chain is that backend's contract; no
# native-nftables chain layout is assumed.
#
# The allow list is the attachment matrix, port-scoped where ports are
# static:
#
#   1. TCP to Traefik's agent-net address on 443 and 80 — the agent's
#      management/signal/relay bootstrap and nothing else of Traefik;
#   2. anything to the reverse proxy's agent-net address — the WireGuard leg,
#      deliberately not port-scoped: ICE negotiates ephemeral UDP candidates
#      on both sides, and a static port list would either break the leg or
#      degenerate into "all UDP" while pretending otherwise;
#   3. established return traffic;
#   4. everything else sourced from the agent subnet is dropped, so even if
#      the `internal: true` boundary regressed, the agent still has no path
#      out.
#
# Idempotent (-C before -I) and re-run by systemd whenever Docker (re)starts,
# because a Docker restart rebuilds DOCKER-USER without these rules.
set -euo pipefail

AGENT_SUBNET="172.31.0.0/24"
TRAEFIK_AGENT_IP="172.31.0.10"
PROXY_AGENT_IP="172.31.0.11"

# Wait for Docker to have created the chain.
for i in $(seq 1 30); do
  iptables -L DOCKER-USER -n >/dev/null 2>&1 && break
  sleep 2
done

ensure() { # ensure POSITION RULE... — insert if absent
  local pos=$1; shift
  iptables -C DOCKER-USER "$@" 2>/dev/null || iptables -I DOCKER-USER "$pos" "$@"
}

# An earlier revision installed one broad intra-subnet RETURN; it would match
# before the port-scoped rules below and make them decorative, so it is
# removed if present.
iptables -D DOCKER-USER -s "$AGENT_SUBNET" -d "$AGENT_SUBNET" -j RETURN 2>/dev/null || true

ensure 1 -s "$AGENT_SUBNET" -d "$TRAEFIK_AGENT_IP" -p tcp --dport 443 -j RETURN
ensure 2 -s "$AGENT_SUBNET" -d "$TRAEFIK_AGENT_IP" -p tcp --dport 80 -j RETURN
ensure 3 -s "$AGENT_SUBNET" -d "$PROXY_AGENT_IP" -j RETURN
# The proxy's own address sits inside the agent subnet, and ICE lets either
# side initiate the WireGuard leg — proxy-sourced traffic must not fall
# through to the subnet drop.
ensure 4 -s "$PROXY_AGENT_IP" -j RETURN
ensure 5 -d "$AGENT_SUBNET" -m conntrack --ctstate RELATED,ESTABLISHED -j RETURN
ensure 6 -s "$AGENT_SUBNET" -j DROP

echo "agent-network-firewall: DOCKER-USER rules present for $AGENT_SUBNET"
