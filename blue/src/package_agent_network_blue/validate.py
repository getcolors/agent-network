"""Validation over desired state, the port of io.github.getcolors.agent-network.validate.

Green renders its keys as Clojure keywords, so every message here carries the
same leading colon — the three colours must report identical errors for one
colors.yml.
"""

from __future__ import annotations

import json
import re

from blue.cli import par_name
from package_once_blue import ssh as once_ssh
from package_once_blue.utils import registrable_domain
from package_once_blue.validate import providers as once_providers

profile_par = par_name("profile")

# provider-compute -> what that choice implies.
#
# `required` are the non-secret keys that provider's template interpolates,
# `secrets` the credentials it needs through COLORS_PAR_*, and `tofu-env` the
# subset OpenTofu reads from the process environment itself. Keeping the three
# together is what stops a provider being validated against one set of keys and
# run with another — a stage exporting a credential nobody checked for, or a
# check demanding a key no template uses. The keys of this map are the
# advertised providers; a provider without a template directory and a golden
# is not advertised.
#
# Three source lists rather than the standard's two, because this package
# publishes STUN over UDP beside 22/80/443 and the firewall on every provider
# mirrors that rule.
#
# Two keys the templates read are deliberately not required. `<provider>-name`
# is an optional override of the profile (Compute Name Standard), and
# `<provider>-ssh-keys` is meaningful by its absence (SSH Keypair Standard).
# Keys of the unselected provider are accepted and ignored, so one colors.yml
# stays portable between providers.
compute_providers = {
    "digitalocean": {
        "required": ["digitalocean-region", "digitalocean-size", "digitalocean-image",
                     "digitalocean-ssh-sources", "digitalocean-http-sources",
                     "digitalocean-stun-sources"],
        "secrets": ["do-token"],
        "tofu-env": {"do-token": "DIGITALOCEAN_TOKEN"},
    },
    "vultr": {
        "required": ["vultr-region", "vultr-plan", "vultr-os-id",
                     "vultr-ssh-sources", "vultr-http-sources", "vultr-stun-sources"],
        "secrets": ["vultr-api-key"],
        "tofu-env": {"vultr-api-key": "VULTR_API_KEY"},
    },
}

# The provider a deployment created before this package recorded one in its
# compute output must be running: the only one it ever offered.
default_compute_provider = "vultr"

# Every key desired state must carry whichever provider is selected. The
# provider-scoped keys come from `compute_providers`.
#
# Two deliberate absences. `<provider>-ssh-keys` selects opt-out mode by being
# present, so requiring it would make every conforming keygen deployment
# invalid. `<provider>-name` is the Compute Name Standard's optional override:
# a fresh colors.yml that omits it is complete and names the machine after the
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
# What each provider accepts as a machine name, checked here rather than
# discovered mid-apply. DigitalOcean droplet names are hostname-like; Vultr
# labels are free-form console text, held to a safe subset.
name_rules = {
    "digitalocean": {
        "re": re.compile(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?"),
        "message": "must be a hostname-like name: lowercase letters, digits, dots and hyphens, 1-63 characters",
    },
    "vultr": {
        "re": re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,62}"),
        "message": "must be a safe 1-63 character name",
    },
}


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


def compute_provider(opts: dict) -> dict | None:
    return compute_providers.get(_s(opts.get("provider-compute")))


def compute_key(opts: dict, suffix: str) -> str:
    """Desired state names compute keys after the provider, so the shared steps
    reach them through the selected provider rather than a fixed prefix."""
    return f"{_s(opts.get('provider-compute'))}-{suffix}"


def compute_name(opts: dict) -> str:
    """What this deployment calls its machine. The one function that answers it
    — every label, including the firewall's, derives from this and never from
    the raw override key or a second copy of the profile (§3). The override is
    read from the selected provider's `<provider>-name` alone."""
    override = opts.get(compute_key(opts, "name"))
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


def cidrs(opts: dict, key: str) -> list[str]:
    """A source list as desired state or an overlay string carries it: a YAML
    list, or one string of comma- or space-separated entries."""
    value = opts.get(key)
    xs = value if isinstance(value, list) else re.split(r"[,\s]+", _s(value))
    return [s for s in (_s(x).strip() for x in xs) if s]


# Syntactic CIDR checks, the same in every colour and deliberately not a
# resolver: an address library that accepts a hostname would let a firewall
# source depend on DNS at apply time.
_ipv4_re = re.compile(
    r"(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}")
_hex_group_re = re.compile(r"[0-9A-Fa-f]{1,4}")


def _fold_ipv4_tail(s: str) -> str | None:
    """An IPv4-embedded address (``::ffff:192.0.2.1``, ``64:ff9b::192.0.2.33``)
    carries a dotted quad in last position only. It stands for two 16-bit
    groups, so it is checked as IPv4 and folded into two zero groups before
    the group arithmetic; None when the tail is dotted but not an IPv4
    address. A dotted quad anywhere else falls through to the hex-group check
    and fails there."""
    i = s.rfind(":")
    tail = s[i + 1:] if i >= 0 else s
    if "." not in tail:
        return s
    if i >= 0 and _ipv4_re.fullmatch(tail):
        return s[:i + 1] + "0:0"
    return None


def _ipv6_address(raw: str) -> bool:
    s = _fold_ipv4_tail(raw)
    if s is None:
        return False

    def groups(part: str) -> list[str]:
        return [] if not part.strip() else part.split(":")
    if "::" in s:
        halves = s.split("::")
        if len(halves) != 2:
            return False
        gs = [g for half in halves for g in groups(half)]
        return len(gs) <= 7 and all(_hex_group_re.fullmatch(g) for g in gs)
    gs = groups(s)
    return len(gs) == 8 and all(_hex_group_re.fullmatch(g) for g in gs)


def cidr(s) -> bool:
    """Whether `s` is a syntactically valid IPv4 or IPv6 CIDR: an address, a
    slash, and a prefix length the address family allows."""
    parts = _s(s).split("/")
    if len(parts) != 2 or not re.fullmatch(r"\d{1,3}", parts[1]):
        return False
    address, n = parts[0], int(parts[1])
    if _ipv4_re.fullmatch(address):
        return 0 <= n <= 32
    if _ipv6_address(address):
        return 0 <= n <= 128
    return False


def source_errors(opts: dict) -> list[str]:
    """The network contract: the selected provider's SSH sources must name at
    least one CIDR — a machine nobody can reach is not a deployment — and
    every entry of all three lists must be one. An empty HTTP list is allowed
    and means no public HTTP; an empty STUN list means no public STUN.
    Refusing beats defaulting: a silent default-open in front of a control
    plane is worse than a validation error."""
    ssh_key = compute_key(opts, "ssh-sources")
    http_key = compute_key(opts, "http-sources")
    stun_key = compute_key(opts, "stun-sources")
    errors: list[str] = []
    if not missing(opts.get(ssh_key)) and not cidrs(opts, ssh_key):
        errors.append(f":{ssh_key} must list at least one CIDR")
    for key in [ssh_key, http_key, stun_key]:
        if missing(opts.get(key)):
            continue
        for entry in cidrs(opts, key):
            if not cidr(entry):
                errors.append(f":{key} entry {json.dumps(entry)} is not an IPv4 or IPv6 CIDR")
    return errors


def provider_errors(opts: dict) -> list[str]:
    """Checks that hold only for the selected provider. Keys of the other
    provider are ignored, never refused. The *resolved* machine name is
    validated against the provider's rules rather than passed through unread
    (Compute Name Standard §2): an override is checked as itself, and a
    profile that falls through as the name is checked too, because a profile
    Vultr accepts as a label can be a droplet name DigitalOcean refuses
    mid-apply. The error names the key the value came from. A blank resolved
    value is skipped, so a missing profile reports `is required` alone."""
    errors: list[str] = []
    name_key = compute_key(opts, "name")
    rule = name_rules.get(_s(opts.get("provider-compute")))
    override = not placeholder(opts.get(name_key))
    name = compute_name(opts)
    source = (f":{name_key}" if override
              else f":profile (the {_s(opts.get('provider-compute'))} machine name)")
    if rule and name.strip() and (len(name) > 63 or not rule["re"].fullmatch(name)):
        errors.append(f"{source} {rule['message']}")
    provider = opts.get("provider-compute")
    if provider == "vultr":
        os_id = opts.get("vultr-os-id")
        if not (missing(os_id) or (isinstance(os_id, int) and not isinstance(os_id, bool))):
            errors.append(":vultr-os-id must be Vultr's numeric operating-system id")
    elif provider == "digitalocean":
        # No VPC is created: the region's default is discovered at plan time,
        # and a pinned UUID or a CIDR would make this package start owning one.
        if "digitalocean-vpc-uuid" in opts:
            errors.append(":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime")
        if "digitalocean-vpc-cidr" in opts:
            errors.append(":digitalocean-vpc-cidr must be absent; this package must not create a VPC")
    return errors


def env_errors(env: dict) -> list[str]:
    if _s(env.get(profile_par)):
        return [f"{profile_par} is set; profile must come from colors.yml only"]
    return []


def state_errors(opts: dict) -> list[str]:
    errors: list[str] = []
    provider = compute_provider(opts)
    errors += [f":{k} is required"
               for k in [*required, *((provider or {}).get("required", []))]
               if missing(opts.get(k))]
    if not provider:
        errors.append(":provider-compute must be one of "
                      + ", ".join(sorted(compute_providers)))
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
    if provider:
        errors += provider_errors(opts) + source_errors(opts)
    return errors


def provider_state_errors(opts: dict, params: dict | None) -> list[str]:
    """Provider switching is a rebuild, never an apply. Every provider shares
    one state key, so a changed provider-compute on a profile whose state
    already holds compute would plan a cross-provider replacement — and a
    delete would render and destroy the *selected* provider's template against
    the wrong lifecycle. `params` is the compute stage's recorded output, or
    None when the state holds none; its `provider` is the registry name the
    template that produced it belongs to. A recorded output without one
    predates this package recording it, which makes it the default
    provider's."""
    if params is None:
        return []
    selected = _s(opts.get("provider-compute"))
    recorded = _s(params.get("provider"))
    if recorded and recorded != selected:
        return [f"state holds a {recorded} machine; set provider-compute back to "
                f"{recorded} and delete first"]
    if not recorded and selected != default_compute_provider:
        return ["state holds a machine with no recorded provider, created before this "
                f"package recorded one, which makes it a {default_compute_provider} "
                f"machine; set provider-compute back to {default_compute_provider} "
                "and delete first"]
    return []


def backend_secrets(opts: dict) -> list[str]:
    entry = once_providers["provider-backend"].get(str(opts.get("provider-backend")), {})
    return entry.get("secrets", [])


def provider_secrets(opts: dict) -> list[str]:
    """What talking to the providers needs, on any real event: the selected
    compute provider's credential and Cloudflare's."""
    return [*((compute_provider(opts) or {}).get("secrets", [])), "cloudflare-api-token"]

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
    keys = [*provider_secrets(opts), *per_event, *backend_secrets(opts)]
    return [f"required credential is not set: {par_name(k)}"
            for k in dict.fromkeys(keys) if missing(opts.get(k))]


def tofu_env(opts: dict, slot: str) -> dict[str, str]:
    if slot == "provider-compute":
        return (compute_provider(opts) or {}).get("tofu-env", {})
    if slot == "provider-dns":
        return {"cloudflare-api-token": "CLOUDFLARE_API_TOKEN"}
    if slot == "provider-backend":
        entry = once_providers["provider-backend"].get(str(opts.get("provider-backend")), {})
        return entry.get("tofu-env", {})
    return {}
