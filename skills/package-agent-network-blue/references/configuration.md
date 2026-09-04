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
| `provider-compute` | `vultr` or `digitalocean`. Selects the compute template and which provider-scoped keys below are read; the other provider's keys are ignored. Switching on a profile that already holds a machine is refused — see below. |
| `provider-dns` | Must be `cloudflare`. |
| `provider-backend` | `local`, `s3` or `r2`. |
| `compute-prevent-destroy` | Keep `true` in committed state. Destruction needs `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false` for one run. |

## Agent Network

| Key | Meaning |
|---|---|
| `agent-network-host` | Public hostname for the whole demo: dashboard, REST API, management/signal gRPC, relay WebSocket, embedded IdP, and the base the generated agent-network endpoint hangs one label beneath. The DNS stage creates it **and** `*.<host>` — the wildcard is contract, because the endpoint label is minted at bootstrap and nothing knows it earlier. |
| `agent-network-letsencrypt-email` | Contact address for ACME: Traefik's TLS-ALPN-01 for the base name, and lego's DNS-01 for the wildcard endpoint certificate. |
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
are exact `x.y.z` versions for the payloads built into the agent image;
`agent-network-lego-version` pins the DNS-01 client that issues the wildcard
certificate at converge time. Both downloads are verified against their
release checksums. Move the server image, proxy image and client version
together — one release train.

## Vultr (`provider-compute: vultr`)

| Key | Meaning |
|---|---|
| `vultr-region`, `vultr-plan`, `vultr-os-id` | Instance shape. `vultr-os-id` is numeric; 2284 is Ubuntu 24.04 LTS x64. |
| `vultr-name` | Optional. Absent, blank or `REPLACE_ME` means the machine is named after the profile (Compute Name Standard). Letters, digits, `.`, `_`, `-`, 1-63 characters; updates in place and is never a hostname. |
| `vultr-ssh-keys` | Optional. Absent selects keygen mode (the package owns `~/.ssh/<profile>`); present is an explicit account key id and the package touches no key material. |
| `vultr-ssh-sources`, `vultr-http-sources`, `vultr-stun-sources` | Source CIDRs for 22/tcp, 80+443/tcp, and STUN udp. No WireGuard port is published: the only peer is on the internal Docker network. |

## DigitalOcean (`provider-compute: digitalocean`)

| Key | Meaning |
|---|---|
| `digitalocean-region`, `digitalocean-size`, `digitalocean-image` | Droplet shape. `region` and `image` are ForceNew; `size` resizes in place. `ubuntu-24-04-x64` is the image slug the package was built for. |
| `digitalocean-name` | Optional. Absent, blank or `REPLACE_ME` means the droplet is named after the profile. Hostname-like (lowercase letters, digits, dots, hyphens; 1-63 characters), checked before any provider call. Renames in place, but the guest hostname cloud-init set at creation lags until a rebuild. |
| `digitalocean-ssh-keys` | Optional. Absent selects keygen mode; present is an existing account key id or fingerprint and the package touches no key material. |
| `digitalocean-ssh-sources`, `digitalocean-http-sources`, `digitalocean-stun-sources` | Source CIDRs for 22/tcp, 80+443/tcp, and STUN udp — the same rule set as on Vultr. |

The droplet joins the region's default VPC (`default-<region>`), discovered at
plan time. `digitalocean-vpc-uuid` and `digitalocean-vpc-cidr` are refused:
this package neither pins nor creates a VPC.

## The firewall sources

The provider firewall is the load-bearing layer on both providers — inbound
22 from `<provider>-ssh-sources`, 80 and 443 from `<provider>-http-sources`,
STUN over UDP from `<provider>-stun-sources`, nothing else; Ansible manages
no host firewall for these ports (the DOCKER-USER rules it does install are
the agent's isolation boundary, not port management). Every entry must be a
syntactically valid IPv4 or IPv6 CIDR and the SSH list must not be empty,
both checked before any provider call. An empty HTTP or STUN list is allowed
and means that service is not public.

## Switching providers

Provider switching is a rebuild, never an apply. Both providers share one
state key, so a changed `provider-compute` on a profile whose state already
holds a machine is refused on create *and* delete with
`state holds a <recorded> machine; set provider-compute back to <recorded> and
delete first`. A deployment created before this package recorded the provider
in its compute output is treated as Vultr. The check reads the state with the
backend credentials alone and runs before the provider credential check, so a
mistaken edit reports the actionable error rather than a missing token. On a
real delete an unreadable backend is an error (`could not read the
infrastructure state for the delete cleanup`), never an empty state; on a
real create a compute stage that applied without an address is refused
(`compute produced no ip output`) rather than converged against the
documentation address.

## State backend

| Key | Meaning |
|---|---|
| `r2-bucket`, `r2-endpoint` | Remote OpenTofu state, keyed `<profile>/<stage>.tfstate`. |

## Credentials (`.envrc.private`)

| Variable | Purpose |
|---|---|
| `COLORS_PAR_VULTR_API_KEY` | Compute, with `provider-compute: vultr`. |
| `COLORS_PAR_DO_TOKEN` | Compute, with `provider-compute: digitalocean`. Only the selected provider's credential is required; the other is ignored. |
| `COLORS_PAR_CLOUDFLARE_API_TOKEN` | Record edits only: the A records, and the DNS-01 TXT records lego uses to issue the wildcard certificate at converge time. |
| `COLORS_PAR_R2_ACCESS_KEY_ID`, `COLORS_PAR_R2_SECRET_ACCESS_KEY` | State backend. |
| `COLORS_PAR_ANTHROPIC_API_KEY` | Handed to NetBird's encrypted store at converge; the agent never sees it. A deliberately fake value is a supported demo mode (acceptance then expects the relayed upstream 401). |

Everything else is generated on the host, create-once, under
`/etc/agent-network/secrets`: the relay auth secret, the datastore encryption
key, the session cookie key, the proxy access token, the admin password, the
durable automation token — and the agent's one-off setup key, which lives
only on tmpfs and is revoked after enrollment.
