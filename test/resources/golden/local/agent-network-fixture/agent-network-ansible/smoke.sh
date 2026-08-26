#!/usr/bin/env bash
# End-to-end proof that the demo demonstrates what it claims.
#
# A green stack proves almost nothing here: every container can be healthy
# while the agent quietly has internet access, or while the endpoint denies
# everything, or while requests flow unmetered. So each gate asserts the
# specific fact the demo stands on, negative space included:
#
#   1. the agent cannot reach the internet — raw TCP to several addresses,
#      route-table inspection, and a control probe that must SUCCEED so the
#      failures above are isolation, not breakage;
#   2. the tunnel is up;
#   3. the keyless call through the endpoint traverses policy and key
#      injection (with a deliberately fake upstream key the expected outcome
#      is Anthropic's own 401 relayed through the proxy — the full path,
#      nothing billable); 3b/3c the two denial classes, which never reach
#      upstream at all;
#   4. headless Claude Code takes the same governed path, and the access log
#      attributes everything to the agent's peer identity;
#   5. the configured limits read back as desired state says.
#
# `--isolation-only` re-runs gate 1, which is what the Docker-restart and
# reboot resilience checks re-assert.
set -euo pipefail

API="https://agent-network.example.com/api"
PAT=$(cat /etc/agent-network/secrets/pat)
ENDPOINT=$(cat /etc/agent-network/state/endpoint)
AGENT=agent-network-agent
ALLOWED="claude-haiku-4-5-20251001"
DENIED="claude-sonnet-4-5-20250929"
UNCLAIMED="model-that-no-provider-claims-3c"

log() { echo "agent-network-smoke: $*" >&2; }
api() { curl -fsS -X "$1" "$API$2" -H "Authorization: Token $PAT" \
          -H 'content-type: application/json' ${3:+--data "$3"}; }
in_agent() { docker exec "$AGENT" "$@"; }

# --- gate 1: isolation ------------------------------------------------------

log "gate 1: isolation probes"
for target in 1.1.1.1/443 8.8.8.8/53 140.82.121.4/443 9.9.9.9/443; do
  host=${target%/*}; port=${target#*/}
  if in_agent timeout 5 bash -c "</dev/tcp/$host/$port" 2>/dev/null; then
    log "FAIL: the agent reached $host:$port directly; it must have no internet path"
    exit 1
  fi
done

if [[ -n $(in_agent ip route show default 2>/dev/null) ]]; then
  log "FAIL: the agent has a default route"
  in_agent ip route >&2
  exit 1
fi
if [[ -n $(in_agent ip -6 route show default 2>/dev/null) ]]; then
  log "FAIL: the agent has an IPv6 default route"
  exit 1
fi

# The control probe: the one path the agent may use must work, or the
# failures above prove breakage rather than isolation.
if ! in_agent timeout 5 bash -c "</dev/tcp/172.31.0.10/443" 2>/dev/null; then
  log "FAIL: the agent cannot reach its own gateway; the failures above are breakage, not isolation"
  exit 1
fi

log "gate 1 passed; reachable surface:"
for target in "172.31.0.10/443" "172.31.0.10/80" "172.31.0.11/8443"; do
  host=${target%/*}; port=${target#*/}
  if in_agent timeout 3 bash -c "</dev/tcp/$host/$port" 2>/dev/null; then
    log "  open  $host:$port"
  else
    log "  closed $host:$port"
  fi
done

if [[ ${1:-} == --isolation-only ]]; then
  log "PASS (isolation only)"
  exit 0
fi

# --- gate 2: the tunnel -----------------------------------------------------

log "gate 2: tunnel status"
up=0
for i in $(seq 1 30); do
  status=$(in_agent netbird status 2>&1 || true)
  if grep -qi 'Management: Connected' <<<"$status" && grep -qi 'Signal: Connected' <<<"$status"; then
    up=1; break
  fi
  sleep 5
done
if [[ $up != 1 ]]; then
  log "FAIL: the agent's tunnel did not come up"
  in_agent netbird status -d >&2 || true
  exit 1
fi
log "gate 2 passed"

# --- gate 3: the keyless call ----------------------------------------------

probe() { # probe MODEL -> "<code> <body>" via global reply/code
  local model=$1
  code=$(in_agent curl -sS -o /tmp/.probe -w '%{http_code}' --max-time 60 \
    -X POST "https://$ENDPOINT/v1/messages" \
    -H 'content-type: application/json' -H 'anthropic-version: 2023-06-01' \
    --data "{\"model\":\"$model\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"Reply with the single word pong.\"}]}" \
    2>/dev/null) || code=000
  body=$(in_agent cat /tmp/.probe 2>/dev/null || true)
}

log "gate 3: keyless call through $ENDPOINT"
mode=""
for i in $(seq 1 30); do
  probe "$ALLOWED"
  if [[ $code == 200 ]] && grep -q '"content"' <<<"$body"; then
    mode=real; break
  fi
  # The deliberately fake upstream key: Anthropic's own 401 relayed through
  # the proxy proves tunnel, endpoint DNS, policy authorization and
  # server-side key injection — everything NetBird owns — without a billable
  # completion. A NetBird denial would be 403 with a policy reason instead.
  if [[ $code == 401 ]] && grep -qi 'authentication_error' <<<"$body"; then
    mode=fake; break
  fi
  log "  attempt $i: HTTP $code (endpoint TLS or route may still be settling)"
  sleep 10
done
if [[ -z $mode ]]; then
  log "FAIL: the keyless call neither completed nor returned the upstream 401"
  log "  last: HTTP $code: $(head -c 300 <<<"$body")"
  exit 1
fi
log "gate 3 passed ($mode-key mode)"

log "gate 3b: guardrail denial for $DENIED"
probe "$DENIED"
if [[ $code != 403 ]] || ! grep -qiE 'model_blocked|allowlist|not[ _-]?allowed' <<<"$body"; then
  log "FAIL: expected 403 model-not-allowed, got HTTP $code: $(head -c 300 <<<"$body")"
  exit 1
fi
log "gate 3b passed"

log "gate 3c: routing denial for $UNCLAIMED"
probe "$UNCLAIMED"
if [[ $code -lt 400 ]] || ! grep -qiE 'model_not_routable|no provider|not[ _-]?available' <<<"$body"; then
  log "FAIL: expected a model-not-available denial, got HTTP $code: $(head -c 300 <<<"$body")"
  exit 1
fi
log "gate 3c passed"

# --- gate 4: the payload ----------------------------------------------------

log "gate 4: headless Claude Code"
log "  versions: $(in_agent cat /etc/agent-versions | tr '\n' ' ')"
claude_out=$(in_agent claude -p 'Reply with the single word pong.' 2>&1) && claude_rc=0 || claude_rc=$?
if [[ $mode == real ]]; then
  if [[ $claude_rc != 0 ]] || ! grep -qi 'pong' <<<"$claude_out"; then
    log "FAIL: Claude Code did not complete: $(head -c 300 <<<"$claude_out")"
    exit 1
  fi
else
  # Fake-key mode: the failure must be the relayed upstream authentication
  # error — a NetBird denial or a network error would mean Claude Code's
  # traffic took a different path than gate 3 proved.
  if [[ $claude_rc == 0 ]]; then
    log "FAIL: Claude Code completed against a fake upstream key"
    exit 1
  fi
  if ! grep -qiE '401|authentication' <<<"$claude_out"; then
    log "FAIL: Claude Code failed, but not with the relayed upstream 401: $(head -c 300 <<<"$claude_out")"
    exit 1
  fi
fi
log "gate 4 passed"

# --- attribution and limits -------------------------------------------------

log "asserting access-log attribution"
logs=$(api GET "/agent-network/access-logs?page=1&page_size=100")
total=$(jq -r '.total_records // (.data|length)' <<<"$logs")
if [[ ${total:-0} -lt 3 ]]; then
  log "FAIL: expected at least the three probes in the access log, found ${total:-0}"
  exit 1
fi
# Allowed requests may name only the allowlisted model; the two denials must
# be present with their reasons; and nothing in the log is unattributed.
bad_model=$(jq -r --arg m "$ALLOWED" \
  '[.data[] | select((.decision=="allow" or .status_code==200 or .status_code==401)
                     and .model != null and .model != $m)] | length' <<<"$logs")
if [[ $bad_model != 0 ]]; then
  log "FAIL: an allowed request named a model other than $ALLOWED"
  jq -r '.data[] | "\(.timestamp) \(.model) \(.decision) \(.deny_reason)"' <<<"$logs" >&2
  exit 1
fi
for reason in 'model_blocked|allowlist|not[ _-]?allowed' 'model_not_routable|no provider|not[ _-]?available'; do
  if ! jq -r '.data[].deny_reason // empty' <<<"$logs" | grep -qiE "$reason"; then
    log "FAIL: no denial with reason matching /$reason/ in the access log"
    exit 1
  fi
done
unattributed=$(jq -r '[.data[] | select((.user_id // "") == "")] | length' <<<"$logs")
if [[ $unattributed != 0 ]]; then
  log "FAIL: $unattributed access-log entries carry no caller identity"
  exit 1
fi

log "asserting configured limits match desired state"
desired=/opt/agent-network/desired.json
pol=$(api GET /agent-network/policies | jq -c '.[] | select(.name=="colors-agents-anthropic")')
[[ -n $pol ]] || { log "FAIL: the policy is missing"; exit 1; }
for check in \
  ".limits.budget_limit.group_cap_usd==$(jq .policy.budget_usd_per_day "$desired")" \
  ".limits.token_limit.group_cap==$(jq .policy.tokens_per_day "$desired")" \
  ".enabled==true"; do
  jq -e "$check" <<<"$pol" >/dev/null || { log "FAIL: policy check $check"; exit 1; }
done
rule=$(api GET /agent-network/budget-rules | jq -c '.[] | select(.name=="colors-global-ceiling")')
[[ -n $rule ]] || { log "FAIL: the global limit is missing"; exit 1; }
jq -e ".limits.budget_limit.group_cap_usd==$(jq .global.budget_usd_per_day "$desired")" \
  <<<"$rule" >/dev/null || { log "FAIL: global budget cap drifted"; exit 1; }
settings=$(api GET /agent-network/settings)
jq -e ".enable_log_collection==true and .access_log_retention_days==$(jq .log_retention_days "$desired")" \
  <<<"$settings" >/dev/null || { log "FAIL: log-collection settings drifted"; exit 1; }

log "PASS: isolation, tunnel, keyless path, both denials, payload, attribution, limits"
