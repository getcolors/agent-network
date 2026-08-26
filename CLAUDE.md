# CLAUDE.md

## Repository

`agent-network` is a Green-only Package Skill for a minimal, single-node demo
of [NetBird Agent Network](https://docs.netbird.io/agent-network) — keyless,
identity-gated LLM access — on one Vultr instance. OpenTofu manages the
instance, a firewall (22/80/443 TCP and 3478 UDP) and two unproxied Cloudflare
`A` records: the base name and its **wildcard**. Ansible converges a Docker
Compose stack of Traefik, the combined `netbird-server`, the dashboard in
agent-network-only mode, and the NetBird reverse proxy in private mode; then
bootstraps the control plane headlessly and starts the **isolated agent** — a
container with no internet route running the NetBird client and headless
Claude Code. The first consumer is `../agent-network-vultr`.

The sibling `../netbird` package is the architectural parent: same combined
server, same launcher/goldens/standards conformance, opposite emphasis (that
one is a production control plane with Authentik; this one is a demo whose
product is an isolation claim). Read its CLAUDE.md for the combined-server
background; differences below are deliberate.

## The demo's claim, and where it is enforced

The agent container can reach exactly two addresses, and the LLM only through
one of them:

- **Isolation, twice.** The agent sits alone with Traefik and the reverse
  proxy on an `internal: true` compose network (no egress route), and a
  DOCKER-USER ruleset (`firewall.sh`, kept installed by a systemd unit with
  `PartOf=docker.service`, because a Docker restart rebuilds the chain)
  confines the agent subnet to itself independently. Acceptance probes the
  negative space — raw TCP to multiple external addresses, route-table and
  IPv6 inspection — **and** a control probe that must succeed, so a broken
  agent cannot masquerade as an isolated one. Both boundaries are re-asserted
  after a real Docker restart and a real reboot (once; the proofs are
  recorded in `/etc/agent-network/state/`).
- **The metered path is the tunnel, natively.** Management pushes authorized
  peers a DNS custom zone resolving the endpoint hostname to the proxy peer's
  overlay address, plus a synthesized ACL admitting TCP 80/443
  (`SynthesizePrivateServiceZones` / `injectPrivateServicePolicies` in
  netbird v0.77.1). The compose therefore maps **only the base domain** via
  `extra_hosts` (bootstrap precedes tunnel DNS); mapping the endpoint would
  bypass the identity boundary, and a test asserts exactly one mapping
  exists.
- **A caller without a tunnel has no identity.** Private services validate
  the tunnel peer; the public `HostSNI(*)` passthrough still exposes 443, so
  the workflow's acceptance step probes the endpoint from outside the
  overlay and requires the denial.

## Why this package does not run `getting-started.sh`

Same reasoning as `netbird`, one preset later: upstream's installer with
`NETBIRD_AGENT_NETWORK=true` generates a compose (Traefik + dashboard +
combined server + reverse proxy with `NB_PROXY_PRIVATE=true`), interactive
choices and `:latest` images included. Two generators for one deployment
would leave the authoritative one being whichever ran last, so the templates
here are derived from what that preset generates (fetched and read
2026-08-26) and maintained as this package's own, with images lifted into
desired state as **tag@digest** pins. When bumping
`agent-network-server-image`, re-read the installer and move the server,
proxy and client version together — one release train.

Details the installer encodes that are easy to lose:

- **Two-phase startup**: core services first; then the proxy token is minted
  with `netbird-server admin token create` (create-once, atomically
  persisted, delete-by-name first so a crash never leaves an undiscoverable
  live token); then the proxy starts.
- **TLS is TLS-ALPN-01 end to end**: Traefik for the base name; the proxy
  runs its own ACME (`NB_PROXY_ACME_CHALLENGE_TYPE=tls-alpn-01`) for
  generated endpoint hostnames behind Traefik's TCP `HostSNI(*)` passthrough
  with PROXY protocol v2 (`NB_PROXY_TRUSTED_PROXIES` = Traefik's fixed
  address). No DNS-01 anywhere; the Cloudflare token needs record-edit scope
  only.
- **The dashboard's `USE_AUTH0=false`** and the agent-network preset flag
  `NETBIRD_AGENT_NETWORK_ONLY=true`. `init_react_envs` exits 1 on a missing
  variable while supervisord carries on and nginx serves `$NETBIRD_*`
  placeholders with `/` returning 200 — acceptance reads the served chunks.

## The control plane is reconciled, not scripted

`bootstrap.sh` reconciles by stable name against `desired.json` (rendered
from `colors.yml`), so a crash after any POST is repaired by re-running
converge. The API surface was verified in dashboard v2.91.1 source, not
prose docs:

- `POST /api/setup` (once; `NB_SETUP_PAT_ENABLED=true`) → immediately
  exchanged for a durable `colors-automation` PAT (atomic persist, orphan
  revocation, rotation before expiry).
- `POST /api/agent-network/settings {proxy_address}` **is what mints the
  endpoint label** — not the first provider, whatever the quickstart prose
  implies. 409 means a concurrent bootstrap won, which is success. Retried
  while the proxy cluster registers. `PUT` is full-replace and must echo the
  immutable `endpoint`/`proxy_address`.
- Providers / guardrails / policies / budget-rules under
  `/api/agent-network/*`, all name-reconciled. The provider claims **two
  models and the guardrail allows one**, so both denial classes are
  demonstrable at zero upstream cost: guardrail ("model not allowed") and
  routing ("model not available"). Validation enforces that shape.
- Limits are **per-group on the agents peer group** — the caller is an
  autonomous peer, not an IdP user — with an account-wide budget rule as the
  ceiling; both are read back at acceptance against desired state.

## The setup key's whole life

One-off type (single-use is server-enforced), one hour, `auto_groups` puts
the peer straight into `agents`. It travels as a file on tmpfs
(`/run/agent-network`, recreated by tmpfiles.d) into
`netbird up --setup-key-file` — never argv, never compose config, never the
image. `--post-enroll` verifies the peer and its group, revokes the key,
removes the file, and asserts `docker inspect` on the agent shows no trace.
An enrolled agent reconnects from its state volume and never needs a key
again, which is what makes single-use possible.

## Fake-key mode is a supported mode, not a degraded one

With a deliberately fake `COLORS_PAR_ANTHROPIC_API_KEY`, gate 3 and gate 4 of
`smoke.sh` expect Anthropic's own 401 relayed through the proxy — which
proves every link NetBird owns (isolation, tunnel, endpoint DNS, policy,
server-side key injection reaching the upstream) with nothing billable. The
denial gates never reach upstream and are unaffected. A real key upgrades the
same gates to require completions; swapping is an `.envrc.private` edit and a
re-converge (the provider PUT rotates the stored key).

## Every Claude Code model knob is pinned

`ANTHROPIC_MODEL`, `ANTHROPIC_SMALL_FAST_MODEL`, the three
`ANTHROPIC_DEFAULT_*_MODEL`s and `CLAUDE_CODE_SUBAGENT_MODEL` all carry the
allowlisted model, and telemetry/update traffic is disabled. One unpinned
tier would let Claude Code name a model the guardrail rejects and the happy
path would die on its own policy. A test enumerates the knobs.

## Disposable by design

No backup subsystem — the deliberate opposite of `netbird`. The box holds
real state (datastore, keys, PATs, TLS material, usage history) but none of
it is worth outliving the box: recovery is a guarded `delete` + `create`,
which regenerates the endpoint hostname and every peer identity. The README
says so plainly; do not add backups without revisiting that decision.

## Commands

```sh
bb test                # 87 tests
bb golden              # two fixtures (keygen + opt-out), byte-for-byte
bb golden:accept       # after an intended change — read the diff first
./scripts/launcher.sh  # launcher self-checks
bb pin                 # stamp the launcher after a push
```

Working-tree overrides: `AGENT_NETWORK_LIB_ROOT`, `GREEN_LIB_ROOT`,
`ONCE_LIB_ROOT` — absolute paths, because `scripts/launcher.sh` runs from a
temp directory. The root `./green` is a symlink to the skill payload inside
this repo; in a deployment it is a **copy** that must be refreshed after
`npx skills update -p`.

## Sources

Upstream docs `netbirdio/docs` `src/pages/agent-network/*`; the quickstart
installer `pkgs.netbird.io/getting-started.sh`; management source
`netbirdio/netbird@v0.77.1`; dashboard source `netbirdio/dashboard@v2.91.1`
(the authority for the agent-network REST shapes). All read 2026-08-26.
Where this package contradicts the prose docs — endpoint minting, the
tunnel-only DNS mechanism — the source is the authority, and the relevant
finding is recorded beside the code that depends on it.
