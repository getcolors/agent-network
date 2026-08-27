#!/usr/bin/env bash
# Guarded, idempotent bootstrap of the Agent Network control plane.
#
# Every step reconciles by stable name against observed state, so running this
# twice changes nothing and a crash after any POST is repaired — not
# duplicated — by running it again. Desired state arrives as
# /opt/agent-network/desired.json, rendered from colors.yml; the one secret,
# the Anthropic API key, arrives as $ANTHROPIC_API_KEY in this process's
# environment and is written to nothing but the API request that stores it in
# NetBird's encrypted datastore.
#
# The order is forced by the product:
#
#   1. the only headless way to get the first credential is POST /api/setup,
#      which works exactly once; its short-lived PAT is exchanged immediately
#      for a durable one, atomically persisted;
#   2. the agent-network endpoint exists only after the account's settings
#      are bootstrapped against a registered proxy cluster, so that POST is
#      retried until the proxy has come up (409 means a concurrent bootstrap
#      won, which is success);
#   3. providers, guardrails, policies and global limits all hang off those
#      two facts;
#   4. the agent's one-off setup key is minted last, only while no agent peer
#      exists, and `--post-enroll` verifies the enrollment, revokes the key,
#      and removes every local copy.
set -euo pipefail

API="https://<{ agent-network-host }>/api"
SECRETS=/etc/agent-network/secrets
STATE=/etc/agent-network/state
DESIRED=/opt/agent-network/desired.json
RUN_SECRET=/run/agent-network
umask 077
mkdir -p "$SECRETS" "$STATE" "$RUN_SECRET"
chmod 0700 "$RUN_SECRET"

log() { echo "agent-network-bootstrap: $*" >&2; }

api() { # api METHOD PATH [BODY]
  local method=$1 path=$2 body=${3:-}
  if [[ -n $body ]]; then
    curl -fsS -X "$method" "$API$path" -H "Authorization: Token $(cat "$SECRETS/pat")" \
      -H 'content-type: application/json' --data "$body"
  else
    curl -fsS -X "$method" "$API$path" -H "Authorization: Token $(cat "$SECRETS/pat")"
  fi
}

persist() { # persist FILE VALUE — atomic: a crash never leaves a half-written credential
  local file=$1 value=$2
  printf '%s' "$value" > "$file.tmp"
  chmod 0600 "$file.tmp"
  mv -f "$file.tmp" "$file"
}

# --- 1. the automation credential -------------------------------------------

if [[ ! -s $SECRETS/pat ]]; then
  log "no automation credential; attempting first-owner setup"
  setup_body=$(jq -nc \
    --arg e "$(jq -r .admin_email "$DESIRED")" \
    --arg n "$(jq -r .admin_name "$DESIRED")" \
    --arg p "$(cat "$SECRETS/admin_password")" \
    '{email:$e, name:$n, password:$p, create_pat:true, pat_expire_in:7}')

  if setup_out=$(curl -fsS -X POST "$API/setup" -H 'content-type: application/json' \
                   --data "$setup_body" 2>/dev/null); then
    setup_pat=$(jq -r '.personal_access_token // empty' <<<"$setup_out")
    [[ -n $setup_pat ]] || { log "FATAL: /api/setup returned no PAT"; exit 1; }
    # Persisted immediately, short-lived as it is: a crash between here and
    # the durable exchange must leave a working credential, or every retry
    # would hit the already-set-up wall below with nothing stored. The
    # rotation logic further down replaces it with a durable token on the
    # same run or any later one.
    persist "$SECRETS/pat" "$setup_pat"
    log "local owner created"
  else
    log "FATAL: this instance is already set up but no automation credential is stored."
    log "  The one-time setup PAT cannot be reissued. Recover by creating a PAT"
    log "  in the dashboard as $(jq -r .admin_email "$DESIRED") and writing it to"
    log "  $SECRETS/pat with mode 0600, then converge again."
    exit 1
  fi

  # The setup PAT expires in days and everything after this line depends on a
  # credential that does not. Exchange first, persist atomically, then let any
  # orphan from a crashed earlier attempt be revoked below.
  me=$(curl -fsS "$API/users" -H "Authorization: Token $setup_pat" \
       | jq -r '.[] | select(.is_current==true) | .id' | head -1)
  [[ -n $me ]] || { log "FATAL: the owner did not resolve to a user"; exit 1; }
  durable=$(curl -fsS -X POST "$API/users/$me/tokens" -H "Authorization: Token $setup_pat" \
            -H 'content-type: application/json' \
            --data "$(jq -nc '{name:"colors-automation", expires_in:365}')" \
            | jq -r '.plain_token // empty')
  [[ -n $durable ]] || { log "FATAL: could not create the durable credential"; exit 1; }
  persist "$SECRETS/pat" "$durable"
  unset setup_pat setup_out durable
  log "automation credential stored; setup PAT discarded"
fi

# A crash between `create` and `persist` on some earlier run may have left
# extra live tokens under the same name. The newest is the one on disk (the
# file is written before anything else uses the token); the rest are revoked
# so no undiscoverable credential stays live.
me=$(api GET /users | jq -r '.[] | select(.is_current==true) | .id' | head -1)
if [[ -n $me ]]; then
  dupes=$(api GET "/users/$me/tokens" | jq -r \
    '[.[] | select(.name=="colors-automation")] | sort_by(.expiration_date) | .[:-1][].id')
  for t in $dupes; do
    log "revoking orphaned automation token $t"
    api DELETE "/users/$me/tokens/$t" >/dev/null || true
  done
fi

# A crash between persisting the setup PAT and the durable exchange leaves a
# working but short-lived credential and no named token; mint the durable one
# whenever it is missing, with whatever credential is on disk.
named=$(api GET "/users/$me/tokens" 2>/dev/null | jq -r \
  '[.[] | select(.name=="colors-automation")] | length' || echo 0)
if [[ ${named:-0} == 0 ]]; then
  log "no durable automation token; minting one"
  fresh=$(api POST "/users/$me/tokens" \
    "$(jq -nc '{name:"colors-automation", expires_in:365}')" | jq -r '.plain_token // empty')
  [[ -n $fresh ]] || { log "FATAL: could not create the durable credential"; exit 1; }
  persist "$SECRETS/pat" "$fresh"
  unset fresh
fi

# Rotate before it lapses.
expiry=$(api GET "/users/$me/tokens" 2>/dev/null | jq -r \
  '[.[] | select(.name=="colors-automation")] | sort_by(.expiration_date) | last | .expiration_date // empty' \
  || true)
if [[ -n ${expiry:-} ]]; then
  days=$(( ( $(date -d "$expiry" +%s) - $(date +%s) ) / 86400 ))
  if (( days < 30 )); then
    log "automation credential expires in ${days}d; rotating"
    fresh=$(api POST "/users/$me/tokens" \
      "$(jq -nc '{name:"colors-automation", expires_in:365}')" | jq -r '.plain_token // empty')
    if [[ -n $fresh ]]; then persist "$SECRETS/pat" "$fresh"; log "rotated"; fi
  fi
fi

# --- post-enroll mode -------------------------------------------------------
#
# Runs after the agent container has started: verify the enrollment, then
# close the credential that made it possible.

if [[ ${1:-} == --post-enroll ]]; then
  gid=$(cat "$STATE/agents-group-id" 2>/dev/null || true)
  [[ -n $gid ]] || { log "FATAL: no agents group recorded"; exit 1; }
  peer=$(api GET /peers | jq -r --arg g "$gid" \
    '[.[] | select((.groups // []) | any(.id==$g))] | first | .id // empty')
  [[ -n $peer ]] || { log "FATAL: no peer has enrolled into the agents group"; exit 1; }
  persist "$STATE/agent-peer-id" "$peer"
  log "agent enrolled as peer $peer"

  for id in $(api GET /setup-keys | jq -r '.[] | select(.name=="colors-agent") | .id'); do
    api PUT "/setup-keys/$id" "$(jq -nc '{revoked:true}')" >/dev/null 2>&1 \
      || api DELETE "/setup-keys/$id" >/dev/null 2>&1 || true
    log "setup key $id closed"
  done

  # While the tmpfs copy still exists, its literal value is what must be
  # absent from anything persistent or inspectable — container config and
  # bounded logs — not merely the words "setup key".
  if [[ -s $RUN_SECRET/setup_key ]]; then
    keyval=$(cat "$RUN_SECRET/setup_key")
    if docker inspect agent-network-agent 2>/dev/null | grep -qF "$keyval"; then
      log "FATAL: the setup key value appears in the agent container's configuration"
      exit 1
    fi
    if docker logs --tail 2000 agent-network-agent 2>&1 | grep -qF "$keyval"; then
      log "FATAL: the setup key value appears in the agent container's logs"
      exit 1
    fi
    unset keyval
  fi
  rm -f "$RUN_SECRET/setup_key"

  if docker inspect agent-network-agent 2>/dev/null | grep -qi 'setup[-_]key'; then
    log "FATAL: the agent container's configuration mentions a setup key"
    exit 1
  fi
  log "post-enroll complete"
  exit 0
fi

# --- 2. the endpoint --------------------------------------------------------

settings=$(api GET /agent-network/settings || echo '{}')
endpoint=$(jq -r '.endpoint // empty' <<<"$settings")
if [[ -z $endpoint ]]; then
  log "bootstrapping the agent network against proxy cluster <{ agent-network-host }>"
  for i in $(seq 1 30); do
    out=$(curl -sS -o /tmp/.an-settings -w '%{http_code}' -X POST "$API/agent-network/settings" \
      -H "Authorization: Token $(cat "$SECRETS/pat")" -H 'content-type: application/json' \
      --data "$(jq -nc --arg p "<{ agent-network-host }>" '{proxy_address:$p}')" || true)
    if [[ $out == 200 || $out == 201 || $out == 409 ]]; then break; fi
    log "settings bootstrap not ready (HTTP $out); the proxy may still be registering"
    sleep 10
    [[ $i == 30 ]] && { log "FATAL: the agent network did not bootstrap"; cat /tmp/.an-settings >&2; exit 1; }
  done
  settings=$(api GET /agent-network/settings)
  endpoint=$(jq -r '.endpoint // empty' <<<"$settings")
fi
[[ -n $endpoint ]] || { log "FATAL: no endpoint after settings bootstrap"; exit 1; }
persist "$STATE/endpoint" "$endpoint"
chmod 0644 "$STATE/endpoint"
log "endpoint: $endpoint"

# Log collection and retention: a full-replace PUT that must echo the
# immutable identity, applied only on drift.
proxy_address=$(jq -r '.proxy_address' <<<"$settings")
want=$(jq -nc --arg e "$endpoint" --arg p "$proxy_address" \
  --argjson r "$(jq .log_retention_days "$DESIRED")" \
  '{endpoint:$e, proxy_address:$p, enable_log_collection:true,
    enable_prompt_collection:false, redact_pii:false, access_log_retention_days:$r}')
have=$(jq -c '{endpoint, proxy_address, enable_log_collection, enable_prompt_collection,
               redact_pii, access_log_retention_days}' <<<"$settings")
if [[ $(jq -S . <<<"$want") != $(jq -S . <<<"$have") ]]; then
  log "reconciling log-collection settings"
  api PUT /agent-network/settings "$want" >/dev/null
fi

# --- 3. the provider --------------------------------------------------------

provider_body() { # with or without the api key
  jq -c --arg k "${1:-}" '
    .provider
    | {provider_id, name, upstream_url, models, enabled:true}
    + (if $k == "" then {} else {api_key:$k} end)' "$DESIRED"
}

pid=$(api GET /agent-network/providers | jq -r \
  --arg n "$(jq -r .provider.name "$DESIRED")" '.[] | select(.name==$n) | .id' | head -1)
if [[ -z $pid ]]; then
  [[ -n ${ANTHROPIC_API_KEY:-} ]] || { log "FATAL: ANTHROPIC_API_KEY is not set"; exit 1; }
  log "connecting the Anthropic provider"
  pid=$(api POST /agent-network/providers "$(provider_body "$ANTHROPIC_API_KEY")" | jq -r '.id')
  [[ -n $pid && $pid != null ]] || { log "FATAL: the provider was not created"; exit 1; }
  log "provider connected as $pid"
else
  # Reconcile models and, when the key is present in the environment, rotate
  # it too — a converge with a fresh key is how the key is swapped.
  api PUT "/agent-network/providers/$pid" "$(provider_body "${ANTHROPIC_API_KEY:-}")" >/dev/null
fi
persist "$STATE/provider-id" "$pid"

# --- 4. the guardrail -------------------------------------------------------

grail_body=$(jq -c '{name:"colors-allowlist",
  description:"Only the models desired state allows.",
  checks:{model_allowlist:{enabled:true, models:.allowed_models},
          prompt_capture:{enabled:false, redact_pii:false}}}' "$DESIRED")
gr=$(api GET /agent-network/guardrails | jq -r \
  '.[] | select(.name=="colors-allowlist") | .id' | head -1)
if [[ -z $gr ]]; then
  log "creating the model-allowlist guardrail"
  gr=$(api POST /agent-network/guardrails "$grail_body" | jq -r '.id')
else
  api PUT "/agent-network/guardrails/$gr" "$grail_body" >/dev/null
fi
[[ -n $gr && $gr != null ]] || { log "FATAL: the guardrail was not created"; exit 1; }

# --- 5. the agents group ----------------------------------------------------

gid=$(api GET /groups | jq -r '.[] | select(.name=="agents") | .id' | head -1)
if [[ -z $gid ]]; then
  log "creating the agents group"
  gid=$(api POST /groups "$(jq -nc '{name:"agents"}')" | jq -r '.id')
fi
[[ -n $gid && $gid != null ]] || { log "FATAL: the agents group was not created"; exit 1; }
persist "$STATE/agents-group-id" "$gid"
chmod 0644 "$STATE/agents-group-id"

# --- 6. the policy ----------------------------------------------------------
#
# Per-group caps on the agents group: the caller is an autonomous peer, not an
# IdP user, so the group bucket is the one that binds it. The window is one
# day, epoch-aligned by the server.

limits() { # limits SCOPE — .policy or .global from desired.json
  jq -c --arg s "$1" '
    (if $s=="policy" then .policy else .global end) as $l
    | {token_limit:{enabled:true, group_cap:$l.tokens_per_day,
                    user_cap:$l.tokens_per_day, window_seconds:86400},
       budget_limit:{enabled:true, group_cap_usd:$l.budget_usd_per_day,
                     user_cap_usd:$l.budget_usd_per_day, window_seconds:86400}}' "$DESIRED"
}

policy_body=$(jq -nc --arg g "$gid" --arg p "$pid" --arg gr "$gr" \
  --argjson l "$(limits policy)" \
  '{name:"colors-agents-anthropic",
    description:"The agents group reaches Anthropic, allowlisted and capped.",
    enabled:true, source_groups:[$g], destination_provider_ids:[$p],
    guardrail_ids:[$gr], limits:$l}')
pol=$(api GET /agent-network/policies | jq -r \
  '.[] | select(.name=="colors-agents-anthropic") | .id' | head -1)
if [[ -z $pol ]]; then
  log "creating the access policy"
  pol=$(api POST /agent-network/policies "$policy_body" | jq -r '.id')
else
  api PUT "/agent-network/policies/$pol" "$policy_body" >/dev/null
fi
[[ -n $pol && $pol != null ]] || { log "FATAL: the policy was not created"; exit 1; }

# --- 7. the global ceiling --------------------------------------------------

rule_body=$(jq -nc --argjson l "$(limits global)" \
  '{name:"colors-global-ceiling", enabled:true, target_groups:[], target_users:[],
    limits:$l}')
rule=$(api GET /agent-network/budget-rules | jq -r \
  '.[] | select(.name=="colors-global-ceiling") | .id' | head -1)
if [[ -z $rule ]]; then
  log "creating the account-wide global limit"
  rule=$(api POST /agent-network/budget-rules "$rule_body" | jq -r '.id')
else
  api PUT "/agent-network/budget-rules/$rule" "$rule_body" >/dev/null
fi
[[ -n $rule && $rule != null ]] || { log "FATAL: the global limit was not created"; exit 1; }

# --- 8. the agent's one-off key ---------------------------------------------
#
# Minted only while no peer occupies the agents group; single-use, one hour,
# delivered on tmpfs. `--post-enroll` revokes it and removes the file.

enrolled=$(api GET /peers | jq -r --arg g "$gid" \
  '[.[] | select((.groups // []) | any(.id==$g))] | length')
if [[ $enrolled == 0 ]]; then
  if [[ ! -s $RUN_SECRET/setup_key ]]; then
    # A leftover unclaimed key from a crashed run is closed before a fresh one
    # is minted, so exactly one usable key exists at a time.
    for id in $(api GET /setup-keys | jq -r '.[] | select(.name=="colors-agent") | .id'); do
      api PUT "/setup-keys/$id" "$(jq -nc '{revoked:true}')" >/dev/null 2>&1 \
        || api DELETE "/setup-keys/$id" >/dev/null 2>&1 || true
    done
    log "minting the agent's one-off setup key"
    key=$(api POST /setup-keys "$(jq -nc --arg g "$gid" \
      '{name:"colors-agent", type:"one-off", expires_in:3600, usage_limit:1,
        auto_groups:[$g], ephemeral:false}')" | jq -r '.key // empty')
    [[ -n $key ]] || { log "FATAL: no setup key returned"; exit 1; }
    persist "$RUN_SECRET/setup_key" "$key"
    unset key
    log "setup key staged for enrollment"
  fi
else
  rm -f "$RUN_SECRET/setup_key"
fi

log "bootstrap complete"
