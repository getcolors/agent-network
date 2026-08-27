"""Validation over desired state, the port of io.github.getcolors.agent-network.validate.

Green renders its keys as Clojure keywords, so every message here carries the
same leading colon — the three colours must report identical errors for one
colors.yml.
"""

from __future__ import annotations

import re

from blue.cli import par_name
from package_once_blue import ssh as once_ssh
from package_once_blue.utils import registrable_domain
from package_once_blue.validate import providers as once_providers

profile_par = par_name("profile")

# Every key desired state must carry.
#
# Two deliberate absences. `vultr-ssh-keys` selects opt-out mode by being
# present, so requiring it would make every conforming keygen deployment
# invalid. `vultr-name` is the Compute Name Standard's optional override: a
# fresh colors.yml that omits it is complete and names the machine after the
# profile. There is likewise no `package` key — §5 removes a key that can hold
# exactly one value.
required = [
    "profile", "workdir", "provider-compute", "provider-dns", "provider-backend",
    "compute-prevent-destroy",
    "agent-network-host", "agent-network-letsencrypt-email",
    "agent-network-admin-email", "agent-network-admin-name",
    "agent-network-provider-models", "agent-network-allowed-models",
    "agent-network-policy-budget-usd-per-day", "agent-network-policy-tokens-per-day",
    "agent-network-global-budget-usd-per-day", "agent-network-global-tokens-per-day",
    "agent-network-log-retention-days",
    "agent-network-stun-port", "agent-network-log-level",
    "agent-network-gateway-subnet", "agent-network-agent-subnet",
    "agent-network-server-image", "agent-network-dashboard-image",
    "agent-network-proxy-image", "agent-network-traefik-image",
    "agent-network-agent-base-image",
    "agent-network-claude-code-version", "agent-network-netbird-client-version",
    "agent-network-lego-version",
    "vultr-region", "vultr-plan", "vultr-os-id",
    "vultr-ssh-sources", "vultr-http-sources", "vultr-stun-sources",
    "r2-bucket", "r2-endpoint",
]

image_keys = [
    "agent-network-server-image", "agent-network-dashboard-image",
    "agent-network-proxy-image", "agent-network-traefik-image",
    "agent-network-agent-base-image",
]

host_re = re.compile(r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")
email_re = re.compile(r"[^@\s]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")
# An explicit tag or digest is mandatory. A bare `repository/name` means
# `:latest` by implication and would walk straight past a suffix check for
# ":latest", which is why the shape is required rather than the suffix denied.
# `tag@sha256:...` — the shape every image key here actually carries — pins
# both the human-readable version and the exact bytes.
image_re = re.compile(r"[^\s:@]+(?:/[^\s:@]+)*(?::[^\s:@]+)?(?:@sha256:[0-9a-f]{64})?")
image_pinned_re = re.compile(r"[^\s@]+(?::[^\s:@]+@sha256:[0-9a-f]{64}|:[^\s:@]+|@sha256:[0-9a-f]{64})")
cidr_re = re.compile(r"(?:\d{1,3}\.){3}\d{1,3}/\d{1,2}")
version_re = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")
model_id_re = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]*")
# Vultr labels accept letters, digits, dashes, underscores and periods.
vultr_name_re = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,62}")


def _s(value) -> str:
    """Clojure's `str`: nil renders empty, booleans lowercase."""
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def missing(value) -> bool:
    return value is None or (isinstance(value, str) and not value.strip())


def placeholder(value) -> bool:
    """Absent, blank or REPLACE_ME all mean 'use the profile' (Compute Name
    Standard §2: presence is the only switch)."""
    return missing(value) or _s(value).strip() == "REPLACE_ME"


def compute_name(opts: dict) -> str:
    """What this deployment calls its machine. The one function that answers it
    — every label, including the firewall's, derives from this and never from
    the raw override key or a second copy of the profile (§3)."""
    override = opts.get("vultr-name")
    return _s(opts.get("profile")) if placeholder(override) else _s(override).strip()


def keygen(opts: dict) -> bool:
    """Whether this deployment owns its machine keypair. Delegates to ONCE, the
    standard's reference implementation, so one rule decides it everywhere."""
    return once_ssh.keygen(opts)


def subnet_ip(subnet, n: int) -> str | None:
    """A fixed address inside `subnet`, derived rather than configured: a value
    that can only correctly be `<subnet>.N` is a transcription step, and
    transcription drifts."""
    base = _s(subnet).split("/")[0]
    octets = base.split(".")
    if len(octets) != 4:
        return None
    return ".".join([*octets[:3], str(n)])


# The attachment matrix, each address derived once. On `gateway-net` Traefik
# holds .10 — the proxy's NB_PROXY_TRUSTED_PROXIES needs it, exactly as the
# upstream installer wires TRAEFIK_IP. On `agent-net` Traefik holds .10 (the
# agent's management/signal/relay bootstrap lands here via extra_hosts), the
# reverse proxy .11 (the WireGuard leg), and the agent .20. These four are the
# whole reachable surface of the isolated network, and the DOCKER-USER allow
# list is written from them.
def traefik_ip(opts: dict) -> str | None:
    return subnet_ip(opts.get("agent-network-gateway-subnet"), 10)


def traefik_agent_ip(opts: dict) -> str | None:
    return subnet_ip(opts.get("agent-network-agent-subnet"), 10)


def proxy_agent_ip(opts: dict) -> str | None:
    return subnet_ip(opts.get("agent-network-agent-subnet"), 11)


def agent_ip(opts: dict) -> str | None:
    return subnet_ip(opts.get("agent-network-agent-subnet"), 20)


def zone(opts: dict) -> str | None:
    """The Cloudflare zone the host and its wildcard belong to."""
    return registrable_domain(opts.get("agent-network-host"))


def _seq(value) -> list:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    if isinstance(value, (tuple, set)):
        return list(value)
    return [value]


def provider_models(opts: dict) -> list:
    """The models the Anthropic provider claims, exactly as YAML handed them
    over (green keywordizes; blue's dicts already carry the same names)."""
    return _seq(opts.get("agent-network-provider-models"))


def allowed_models(opts: dict) -> list[str]:
    return [_s(m) for m in _seq(opts.get("agent-network-allowed-models"))]


def allowed_model(opts: dict) -> str | None:
    """The model every Claude Code knob is pinned to."""
    models = allowed_models(opts)
    return models[0] if models else None


def denied_claimed_model(opts: dict) -> str | None:
    """A model the provider claims but the guardrail does not allow — the
    guardrail-denial probe's negative case. Its existence is validated, so
    acceptance can rely on it."""
    allowed = set(allowed_models(opts))
    for model in provider_models(opts):
        claimed = _s(_get(model, "id"))
        if claimed not in allowed:
            return claimed
    return None


def _get(model, key):
    return model.get(key) if isinstance(model, dict) else None


def subnet_overlap(a, b) -> bool:
    """Whether two /24-or-smaller IPv4 CIDRs share addresses. Deliberately
    simple: both subnets here are package-shaped (a.b.c.0/nn with nn >= 16),
    and a byte-precise comparison over that shape beats a dependency."""
    def parts(cidr):
        ip, bits = _s(cidr).split("/")
        octets = [int(o) for o in ip.split(".")]
        n = int(bits)
        addr = 0
        for octet in octets:
            addr = addr * 256 + octet
        mask = (-1 << (32 - n)) & 0xFFFFFFFF
        return addr & mask, mask

    (a_net, a_mask), (b_net, b_mask) = parts(a), parts(b)
    mask = b_mask if bin(a_mask).count("1") > bin(b_mask).count("1") else a_mask
    return (a_net & mask) == (b_net & mask)


def pos_num(value) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0


def model_errors(opts: dict) -> list[str]:
    models = provider_models(opts)
    allowed = allowed_models(opts)
    claimed = {_s(_get(m, "id")) for m in models}
    errors: list[str] = []
    if not (isinstance(opts.get("agent-network-provider-models"), list) and models):
        errors.append(":agent-network-provider-models must be a non-empty list")
    for model in models:
        model_id = _get(model, "id")
        if missing(model_id) or not model_id_re.fullmatch(_s(model_id)):
            errors.append(":agent-network-provider-models entries must carry a model id")
    for model in models:
        if not (pos_num(_get(model, "input-per-1k")) and pos_num(_get(model, "output-per-1k"))):
            errors.append(f"model {_s(_get(model, 'id'))} must carry positive "
                          "input-per-1k and output-per-1k prices")
    if not (isinstance(opts.get("agent-network-allowed-models"), list) and allowed):
        errors.append(":agent-network-allowed-models must be a non-empty list")
    for model in allowed:
        if model not in claimed:
            errors.append(f":agent-network-allowed-models entry {model} "
                          "is not claimed by the provider")
    # The demo's guardrail-denial probe needs a model that routing accepts and
    # the allowlist rejects. Without one, gate 3b has no negative case and the
    # guardrail is configured but never demonstrated.
    if models and allowed and all(_s(_get(m, "id")) in set(allowed) for m in models):
        errors.append(":agent-network-provider-models must claim at least one "
                      "model outside :agent-network-allowed-models")
    return errors


def env_errors(env: dict) -> list[str]:
    if _s(env.get(profile_par)):
        return [f"{profile_par} is set; profile must come from colors.yml only"]
    return []


def state_errors(opts: dict) -> list[str]:
    errors: list[str] = []
    errors += [f":{k} is required" for k in required if missing(opts.get(k))]
    if opts.get("provider-compute") != "vultr":
        errors.append(":provider-compute must be vultr")
    if opts.get("provider-dns") != "cloudflare":
        errors.append(":provider-dns must be cloudflare")
    if opts.get("provider-backend") not in ("local", "s3", "r2"):
        errors.append(":provider-backend must be local, s3, or r2")
    if not isinstance(opts.get("compute-prevent-destroy"), bool):
        errors.append(":compute-prevent-destroy must be true or false")
    host = opts.get("agent-network-host")
    if not missing(host) and not host_re.fullmatch(_s(host)):
        errors.append(":agent-network-host must be a fully qualified hostname")
    for k in ["agent-network-letsencrypt-email", "agent-network-admin-email"]:
        v = opts.get(k)
        if not missing(v) and not email_re.fullmatch(_s(v)):
            errors.append(f":{k} must be an email address")
    for k in image_keys:
        v = opts.get(k)
        if not missing(v) and not image_pinned_re.fullmatch(_s(v)):
            errors.append(f":{k} must carry an explicit image tag or digest")
    # This package owns its templates rather than following the upstream
    # installer, so nothing tells it when a floating tag moved underneath it.
    for k in image_keys:
        v = _s(opts.get(k))
        if (v.endswith(":latest") or v.endswith(":main")
                or ":latest@" in v or ":main@" in v):
            errors.append(f":{k} must not track a floating tag; pin the version")
    for k in ["agent-network-claude-code-version", "agent-network-netbird-client-version",
              "agent-network-lego-version"]:
        v = opts.get(k)
        if not missing(v) and not version_re.fullmatch(_s(v)):
            errors.append(f":{k} must be an exact x.y.z version")
    stun = opts.get("agent-network-stun-port")
    if not (missing(stun)
            or (isinstance(stun, int) and not isinstance(stun, bool) and 0 < stun < 65536)):
        errors.append(":agent-network-stun-port must be a port number")
    for k in ["agent-network-gateway-subnet", "agent-network-agent-subnet"]:
        v = opts.get(k)
        if not missing(v) and not cidr_re.fullmatch(_s(v)):
            errors.append(f":{k} must be a CIDR block")
    # Deterministic build-time validation only (the workstation cannot know the
    # target host's routes; converge re-checks there before creating the
    # networks). Overlapping subnets would let one compose network shadow the
    # other and silently break both the isolation boundary and the firewall
    # allow list derived from static addresses.
    if (cidr_re.fullmatch(_s(opts.get("agent-network-gateway-subnet")))
            and cidr_re.fullmatch(_s(opts.get("agent-network-agent-subnet")))
            and subnet_overlap(opts.get("agent-network-gateway-subnet"),
                               opts.get("agent-network-agent-subnet"))):
        errors.append(":agent-network-gateway-subnet and :agent-network-agent-subnet must not overlap")
    if not (missing(opts.get("agent-network-log-level"))
            or _s(opts.get("agent-network-log-level")) in ("error", "warn", "info", "debug")):
        errors.append(":agent-network-log-level must be error, warn, info, or debug")
    # 7-90 mirrors the dashboard's own retention range; usage metering is
    # unconditional and unaffected.
    retention = opts.get("agent-network-log-retention-days")
    if not (missing(retention)
            or (isinstance(retention, int) and not isinstance(retention, bool)
                and 7 <= retention <= 90)):
        errors.append(":agent-network-log-retention-days must be an integer between 7 and 90")
    for k in ["agent-network-policy-budget-usd-per-day",
              "agent-network-policy-tokens-per-day",
              "agent-network-global-budget-usd-per-day",
              "agent-network-global-tokens-per-day"]:
        v = opts.get(k)
        if not missing(v) and not pos_num(v):
            errors.append(f":{k} must be a positive number")
    # The global rule is the backstop: a policy cap above it would never bind
    # and the desired state would be lying about which limit is the ceiling.
    if (pos_num(opts.get("agent-network-policy-budget-usd-per-day"))
            and pos_num(opts.get("agent-network-global-budget-usd-per-day"))
            and opts.get("agent-network-policy-budget-usd-per-day")
            > opts.get("agent-network-global-budget-usd-per-day")):
        errors.append(":agent-network-policy-budget-usd-per-day must not exceed the global budget")
    if (pos_num(opts.get("agent-network-policy-tokens-per-day"))
            and pos_num(opts.get("agent-network-global-tokens-per-day"))
            and opts.get("agent-network-policy-tokens-per-day")
            > opts.get("agent-network-global-tokens-per-day")):
        errors.append(":agent-network-policy-tokens-per-day must not exceed the global token cap")
    if any(not missing(v) for v in [opts.get("agent-network-provider-models"),
                                    opts.get("agent-network-allowed-models")]):
        errors += model_errors(opts)
    os_id = opts.get("vultr-os-id")
    if not (missing(os_id) or (isinstance(os_id, int) and not isinstance(os_id, bool))):
        errors.append(":vultr-os-id must be Vultr's numeric operating-system id")
    # The override is validated against the provider's rules rather than
    # passed through unread (Compute Name Standard §2).
    if not (placeholder(opts.get("vultr-name"))
            or vultr_name_re.fullmatch(_s(opts.get("vultr-name")).strip())):
        errors.append(":vultr-name must be letters, digits, dot, dash or underscore")
    return errors


def backend_secrets(opts: dict) -> list[str]:
    entry = once_providers["provider-backend"].get(str(opts.get("provider-backend")), {})
    return entry.get("secrets", [])


# What talking to the providers needs, on any real event.
provider_secrets = ["vultr-api-key", "cloudflare-api-token"]

# What converging the machine needs, and therefore only a create.
#
# One entry, deliberately. Everything else this deployment holds is generated
# on the host and supplied by nobody: the relay auth secret, the datastore
# encryption key, the session cookie key, the proxy access token, the local
# admin password, the durable automation token, and the agent's one-off setup
# key. The Anthropic key is the exception because it authenticates against an
# account this host does not own; it is handed to NetBird's encrypted store at
# converge time and the agent container never sees it.
application_secrets = ["anthropic-api-key"]


def secret_errors(opts: dict, event: str | None) -> list[str]:
    """Credentials a real event needs. A delete tears down infrastructure with
    the provider credentials alone: this deployment is disposable by design,
    holds nothing worth a final archive, and demanding the Anthropic key to
    destroy a machine would just be a lock on the exit."""
    per_event = {"create": application_secrets}.get(event, [])
    keys = [*provider_secrets, *per_event, *backend_secrets(opts)]
    return [f"required credential is not set: {par_name(k)}"
            for k in dict.fromkeys(keys) if missing(opts.get(k))]


def tofu_env(opts: dict, slot: str) -> dict[str, str]:
    if slot == "provider-compute":
        return {"vultr-api-key": "VULTR_API_KEY"}
    if slot == "provider-dns":
        return {"cloudflare-api-token": "CLOUDFLARE_API_TOKEN"}
    if slot == "provider-backend":
        entry = once_providers["provider-backend"].get(str(opts.get("provider-backend")), {})
        return entry.get("tofu-env", {})
    return {}
