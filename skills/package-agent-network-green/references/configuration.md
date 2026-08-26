# Configuration reference

Every key in `colors.yml`. Non-secret values only: credentials are
`COLORS_PAR_*` environment variables in a gitignored `.envrc.private`.

Keys are kebab-case. A key's `COLORS_PAR_` name is its upper-snake form —
`agent-network-host` overlays from `COLORS_PAR_AGENT_NETWORK_HOST`. **Never
export `COLORS_PAR_PROFILE`**: the profile keys remote state, and overlaying
it would point one deployment at another's. The package refuses to run when it
is set.

## Core

| Key | Meaning |
|---|---|
| `profile` | This deployment's identity. Keys remote state as `<profile>/<stage>.tfstate`, names the machine, its firewall, the SSH keypair and the `~/.ssh/config` alias. |
| `workdir` | Where generated output lands. Conventionally `.colors`. |
| `provider-compute` | Must be `vultr`. |
| `provider-dns` | Must be `cloudflare`. |
| `provider-backend` | `local`, `s3` or `r2`. |
| `compute-prevent-destroy` | Keep `true` in committed state. Destruction needs `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false` for one run. |

## Agent Network

| Key | Meaning |
|---|---|
| `agent-network-host` | Public hostname for the whole demo: dashboard, REST API, management/signal gRPC, relay WebSocket, embedded IdP, and the base the generated agent-network endpoint hangs one label beneath. The DNS stage creates it **and** `*.<host>` — the wildcard is contract, because the endpoint label is minted at bootstrap and nothing knows it earlier. |
| `agent-network-letsencrypt-email` | Contact address for ACME (Traefik's TLS-ALPN-01 for the base name, and the reverse proxy's own endpoint certificates). |
| `agent-network-admin-email` | Owner of the local admin account, minted headlessly by `POST /api/setup`. Its password is generated on the host, create-once: `/etc/agent-network/secrets/admin_password`. |
| `agent-network-admin-name` | Display name for that account. |
| `agent-network-provider-models` | The models the Anthropic provider claims, each with `id` and per-1k prices (`input-per-1k`, `output-per-1k`, optional `cache-read-per-1k`, `cache-creation-per-1k`). Must claim at least one model outside the allowlist so the guardrail denial is demonstrable. |
| `agent-network-allowed-models` | The guardrail's model allowlist; a non-empty subset of the claimed models. Every Claude Code model knob in the agent is pinned to the first entry. |
| `agent-network-policy-budget-usd-per-day` | Per-group USD cap on the agents policy, over an epoch-aligned one-day window. Must not exceed the global budget. |
| `agent-network-policy-tokens-per-day` | Per-group token cap on the agents policy. Must not exceed the global token cap. |
| `agent-network-global-budget-usd-per-day` | Account-wide USD ceiling (a global limit rule), the backstop across every policy and provider. |
| `agent-network-global-tokens-per-day` | Account-wide token ceiling. |
| `agent-network-log-retention-days` | Access-log retention, 7–90. Usage metering is unconditional and unaffected. |
| `agent-network-stun-port` | UDP port for STUN, bundled into `netbird-server`. Conventionally `3478`. The only UDP published. |
| `agent-network-log-level` | `error`, `warn`, `info` or `debug`. |
| `agent-network-gateway-subnet` | CIDR for the egress-capable compose network carrying the stack. Traefik holds `.10` (the proxy trusts it for PROXY protocol). Must not overlap the agent subnet. |
| `agent-network-agent-subnet` | CIDR for the internal (no-egress) network. Members are exactly Traefik (`.10`, the agent's bootstrap), the reverse proxy (`.11`, the WireGuard leg) and the agent (`.20`). Also the subject of the DOCKER-USER isolation rules. |

## Images and versions

An explicit tag or `@sha256:` digest is **required** — a bare
`repository/name` means `:latest` by implication and is refused, as are
`:latest` and `:main` (with or without a digest). This package owns its
templates rather than running upstream's installer, so nothing warns you when
a floating tag moves; pin tag@digest and bump deliberately.

`agent-network-server-image`, `agent-network-dashboard-image`,
`agent-network-proxy-image`, `agent-network-traefik-image`,
`agent-network-agent-base-image`.

`agent-network-claude-code-version` and `agent-network-netbird-client-version`
are exact `x.y.z` versions for the payloads built into the agent image; the
NetBird client tarball is verified against its release checksums. Move the
server image, proxy image and client version together — one release train.

## Vultr

| Key | Meaning |
|---|---|
| `vultr-region`, `vultr-plan`, `vultr-os-id` | Instance shape. |
| `vultr-name` | Optional. Absent, blank or `REPLACE_ME` means the machine is named after the profile (Compute Name Standard). |
| `vultr-ssh-keys` | Optional. Absent selects keygen mode (the package owns `~/.ssh/<profile>`); present is an explicit account key id and the package touches no key material. |
| `vultr-ssh-sources`, `vultr-http-sources`, `vultr-stun-sources` | Source CIDRs for 22/tcp, 80+443/tcp, and STUN udp. No WireGuard port is published: the only peer is on the internal Docker network. |

## State backend

| Key | Meaning |
|---|---|
| `r2-bucket`, `r2-endpoint` | Remote OpenTofu state, keyed `<profile>/<stage>.tfstate`. |

## Credentials (`.envrc.private`)

| Variable | Purpose |
|---|---|
| `COLORS_PAR_VULTR_API_KEY` | Compute. |
| `COLORS_PAR_CLOUDFLARE_API_TOKEN` | DNS records only; TLS is TLS-ALPN-01 end to end and needs no DNS scope beyond record edits. |
| `COLORS_PAR_R2_ACCESS_KEY_ID`, `COLORS_PAR_R2_SECRET_ACCESS_KEY` | State backend. |
| `COLORS_PAR_ANTHROPIC_API_KEY` | Handed to NetBird's encrypted store at converge; the agent never sees it. A deliberately fake value is a supported demo mode (acceptance then expects the relayed upstream 401). |

Everything else is generated on the host, create-once, under
`/etc/agent-network/secrets`: the relay auth secret, the datastore encryption
key, the session cookie key, the proxy access token, the admin password, the
durable automation token — and the agent's one-off setup key, which lives
only on tmpfs and is revoked after enrollment.
