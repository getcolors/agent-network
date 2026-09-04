# agent-network

A tri-colour Package Skill (green, red, blue) that provisions a minimal,
single-node demo of
[NetBird Agent Network](https://docs.netbird.io/agent-network) — keyless,
identity-gated LLM access — on one Vultr instance or one DigitalOcean
droplet, from a single `colors.yml`.

OpenTofu manages the machine, its provider firewall and two unproxied
Cloudflare `A` records (the base name and its wildcard). Ansible converges Traefik, the
combined `netbird-server`, the dashboard in agent-network-only mode, and the
NetBird reverse proxy in private mode; bootstraps the control plane headlessly
(admin account, endpoint, Anthropic provider, model-allowlist guardrail,
policy with per-group caps, account-wide global limit); and starts the
**isolated agent** — a container on an internal Docker network with no
internet route, running the NetBird client and headless Claude Code, whose
only path to an LLM is the keyless endpoint over the WireGuard tunnel.

Convergence proves the claim or fails: raw-TCP isolation probes with a
success control, the tunnel up, the keyless call through the endpoint, both
denial classes (guardrail and routing) at zero upstream cost, Claude Code on
the same governed path, access-log attribution to the agent's peer identity,
limits read back against desired state, an external probe showing the
endpoint refuses callers outside the overlay, and isolation re-asserted after
a Docker restart and a reboot.

## Install

```sh
npx skills add getcolors/agent-network
cp .agents/skills/package-agent-network-green/green ./green
chmod +x green
./green build
./green create --dry-run
```

`build` and `--dry-run` work on a fresh checkout with an empty environment and
no credentials. Real creation and deletion require explicit authorization.

## What you get

| | |
|---|---|
| `https://<host>` | dashboard (agent-network view), REST API, management and signal gRPC, relay WebSocket, embedded IdP |
| `https://<label>.<host>` | the generated agent-network endpoint — tunnel-only, keyless |
| UDP 3478 | STUN, bundled into `netbird-server` |
| `agent-network-agent` | the isolated agent container: NetBird client + Claude Code, no egress |

The provider firewall — Vultr's or DigitalOcean's, the same rule set on both
— opens 22, 80 and 443 TCP and 3478 UDP, and nothing more. No WireGuard port
is published: the only peer lives on the internal Docker network.

## Two compute providers

`provider-compute` selects `vultr` or `digitalocean`. Each provider is a
template directory of its own, with its own credential
(`COLORS_PAR_VULTR_API_KEY` or `COLORS_PAR_DO_TOKEN`) and its own
provider-scoped keys (`vultr-region`, `vultr-plan`, `vultr-os-id`;
`digitalocean-region`, `digitalocean-size`, `digitalocean-image`; and
`<provider>-ssh-sources` / `<provider>-http-sources` /
`<provider>-stun-sources` on both). Keys of the unselected provider are
ignored, so one `colors.yml` can carry both. `<provider>-name` is optional
and defaults to the profile, and keygen mode — the package owning
`~/.ssh/<profile>` when `<provider>-ssh-keys` is absent — works on both. On
DigitalOcean the droplet joins the region's default VPC, discovered at plan
time; the package creates none.

Switching providers is a rebuild, never an apply: a profile whose state
already holds a machine refuses a create or delete under a different
`provider-compute` until it is set back and deleted.

## Fake-key mode

A deliberately fake `COLORS_PAR_ANTHROPIC_API_KEY` is a supported mode: the
acceptance suite then expects Anthropic's own 401 relayed through the proxy,
which still proves isolation, tunnel DNS, policy authorization and
server-side key injection — with nothing billable. Swap in a real key and
re-run `create` for real completions.

## Configuration

Every key is documented in
[`skills/package-agent-network-green/references/configuration.md`](skills/package-agent-network-green/references/configuration.md).

## Development

```sh
cd green && bb test    # unit tests (canonical Clojure implementation)
cd green && bb golden  # render all four fixtures, diff against committed goldens
cd red && bun test && bun run typecheck   # TypeScript implementation
cd blue && uv run pytest                  # Python implementation
./scripts/parity.sh    # all three colours render byte-identical trees, both providers
./scripts/launcher.sh  # launcher self-checks, from the repository root
```

Four golden fixtures — one per compute provider per keypair mode, because
the SSH Keypair Standard has two modes (keygen, where the package owns
`~/.ssh/<profile>`, and opt-out, an explicit account key id) and a provider
is only advertised by a golden that proves its tree renders. Cross-repo
development uses `AGENT_NETWORK_LIB_ROOT`, `GREEN_LIB_ROOT` and
`ONCE_LIB_ROOT` as working-tree overrides.

## Disposability

This deployment is disposable by design: no backup subsystem. Recovery is a
guarded `delete` (needs `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false` for one
run) followed by `create`, which regenerates the endpoint hostname and every
peer identity.

## License

[MIT](LICENSE)
