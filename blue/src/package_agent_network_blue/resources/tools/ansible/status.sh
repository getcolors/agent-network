#!/usr/bin/env bash
# What an operator runs to see whether this host is well. Deliberately local
# and queryable rather than an alerting integration: this package ships no
# monitoring stack, and pretending otherwise would be the same class of error
# as trusting an exit code.
set -uo pipefail
COMPOSE="docker compose -f /opt/agent-network/compose.yml --profile agent"

echo "== containers"
$COMPOSE ps --format 'table {{.Name}}\t{{.Status}}'

echo; echo "== certificates"
ep=$(cat /etc/agent-network/state/endpoint 2>/dev/null)
for h in <{ agent-network-host }> $ep; do
  exp=$(echo | openssl s_client -servername "$h" -connect "<{ agent-network-host }>:443" 2>/dev/null \
        | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)
  echo "$h expires ${exp:-unknown}"
done

echo; echo "== endpoint"
echo "  ${ep:-not bootstrapped}"

echo; echo "== agent"
docker exec agent-network-agent netbird status 2>/dev/null | sed -n '1,8p' \
  || echo "  agent container is not running"

echo; echo "== usage (last 14 days)"
if [[ -s /etc/agent-network/secrets/pat ]]; then
  curl -fsS -H "Authorization: Token $(cat /etc/agent-network/secrets/pat)" \
    "https://<{ agent-network-host }>/api/agent-network/access-logs?page=1&page_size=1" 2>/dev/null \
    | jq -r '"  \(.total_records // 0) requests in the access log"' \
    || echo "  management unreachable"
else
  echo "  no automation credential"
fi

echo; echo "== dashboard"
echo "  https://<{ agent-network-host }>/  (admin: <{ agent-network-admin-email }>;"
echo "  password on this host: /etc/agent-network/secrets/admin_password)"
