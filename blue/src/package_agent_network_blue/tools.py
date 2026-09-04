"""The steps and every template spec, the port of io.github.getcolors.agent-network.tools."""

from __future__ import annotations

import asyncio
import json
import math
import re
from decimal import Decimal
from pathlib import Path

from blue import tofu
from blue.ansible import ansible_with_spec
from blue.cli import stage_dir
from blue.runtime import runtime
from blue.scaffold import PRESERVE_JINJA_DELIMITERS, content_spec
from package_once_blue import compute as once_compute

from . import ssh, ssh_config, validate

infrastructure_tool = "agent-network-infrastructure"
dns_tool = "agent-network-dns"
ansible_tool = "agent-network-ansible"
ansible_local_tool = "agent-network-ansible-local"
ROOT = Path(__file__).parent / "resources"
template_opts = PRESERVE_JINJA_DELIMITERS


def tool_dir(opts: dict, tool: str) -> str:
    return stage_dir(opts, tool, default_profile="agent-network")


def template(path: str, file: str) -> dict:
    """A template from the tree this colour carries, keyed the way green names
    its classpath resources: dots in `path` are directories."""
    name = f"tools/{path.replace('.', '/')}/{file}"
    return {"name": name, "content": (ROOT / name).read_text()}


def spec(source: dict, target: str, data: dict) -> dict:
    return {"template": source, "target": target, "data": data, "opts": template_opts}


def raw_spec(target: str, content: str) -> dict:
    return content_spec(target, content)


# The source lists as validate parses them, so the template and the
# validator can never disagree about what an entry is.
cidrs = validate.cidrs


def credential_env(opts: dict, *slots: str) -> dict[str, str] | None:
    merged: dict[str, str] = {}
    for slot in [*slots, "provider-backend"]:
        merged.update(validate.tofu_env(opts, slot))
    result = {}
    for key, env_var in merged.items():
        value = "" if opts.get(key) is None else str(opts.get(key))
        if value:
            result[env_var] = value
    return result or None


def backend_credential_env(opts: dict) -> dict[str, str] | None:
    return credential_env(opts)


# What `build` and `--dry-run` render in place of a compute output: the
# documentation address, shaped like the selected provider's real `params` so
# every later stage sees the same keys either way. ONCE's.
fallback_params = once_compute.fallback_params

# Refuse to hand 192.0.2.10 to Ansible — and to the DNS records — on a real
# converge whose compute output carries no `ip`. ONCE's; `infrastructure_step`
# is what wires it.
resolved_compute = once_compute.resolved_compute


# ---------------------------------------------------------------- compute


def infrastructure_data(opts: dict) -> dict:
    """Template values for the compute stage. The name and the three source
    lists are resolved here once, so a template interpolates values and never
    branches on which provider it belongs to."""
    return {**opts,
            "ssh-keygen": validate.keygen(opts),
            "compute-name": validate.compute_name(opts),
            "ssh-sources-hcl": tofu.hcl_list(
                cidrs(opts, validate.compute_key(opts, "ssh-sources"))),
            "http-sources-hcl": tofu.hcl_list(
                cidrs(opts, validate.compute_key(opts, "http-sources"))),
            "stun-sources-hcl": tofu.hcl_list(
                cidrs(opts, validate.compute_key(opts, "stun-sources")))}


def infrastructure_template(opts: dict) -> dict:
    """Providers are selected by template directory,
    `infrastructure/<provider>/`, not by conditionals inside one file; the
    rendered target is the same `main.tf` whichever directory it came from."""
    return template(f"infrastructure.{opts.get('provider-compute')}", "main.tf")


async def infrastructure_step(opts: dict) -> dict:
    dir = tool_dir(opts, infrastructure_tool)
    specs = [spec(infrastructure_template(opts), f"{dir}/main.tf",
                  infrastructure_data(opts))]
    result = await tofu.tofu_with_spec(
        opts, specs, dir=dir, env=credential_env(opts, "provider-compute"))
    if (result.get("blue/exit") or 0) > 0:
        return result
    if opts.get("blue/event") == "build":
        return {**result, **fallback_params(opts)}
    if opts.get("blue/event") == "delete":
        return result
    return resolved_compute(result, fallback_params(opts), once_compute.output_params(result))


# -------------------------------------------------------------------- dns


def dns_json(opts: dict) -> str:
    """The base record and its wildcard, both unproxied.

    Unproxied because Cloudflare's proxy is an HTTP proxy: UDP STUN on 3478
    does not survive it, and both certificate paths — Traefik's TLS-ALPN-01 for
    the base name and the reverse proxy's own ACME for generated endpoint
    hostnames — terminate at the proxy instead of on this host, which breaks
    issuance.

    The wildcard is not convenience but contract: the agent-network endpoint is
    a hostname management mints one label beneath the base domain when the
    account bootstraps, and nothing knows that label before it exists. A record
    per endpoint would put a converge-time fact into desired state."""
    return tofu.constructs_json([
        tofu.construct("resource", "cloudflare_dns_record", "agent_network",
                       {"zone_id": "${data.cloudflare_zone.zone.id}",
                        "name": opts.get("agent-network-host"),
                        "content": opts.get("ip"), "type": "A",
                        "proxied": False, "ttl": 60}),
        tofu.construct("resource", "cloudflare_dns_record", "agent_network_wildcard",
                       {"zone_id": "${data.cloudflare_zone.zone.id}",
                        "name": f"*.{opts.get('agent-network-host')}",
                        "content": opts.get("ip"),
                        "type": "A", "proxied": False, "ttl": 60})])


async def dns_step(opts: dict) -> dict:
    dir = tool_dir(opts, dns_tool)
    data = {**opts,
            "ip": opts.get("ip") or fallback_params(opts)["ip"],
            "agent-network-zone": validate.zone(opts)}
    specs = [spec(template("dns", "main.tf"), f"{dir}/main.tf", data),
             raw_spec(f"{dir}/record.tf.json", dns_json(data))]
    return await tofu.tofu_with_spec(
        opts, specs, dir=dir, env=credential_env(opts, "provider-dns"))


# ---------------------------------------------------------- ansible (local)


def ansible_local_data(opts: dict) -> dict:
    """Only what a `build` genuinely knows. The address, the user and the alias
    are run-time facts and reach the play as extra-vars instead, so the
    rendered playbook carries no IP and is identical on every workstation (SSH
    Config Standard §6)."""
    return {**opts,
            "ssh-keygen": validate.keygen(opts),
            "ssh-config-identity-file": ssh_config.identity_file(opts)}


def ansible_local_specs(opts: dict) -> list[dict]:
    dir = tool_dir(opts, ansible_local_tool)
    data = ansible_local_data(opts)
    return [spec(template("ansible-local", name), f"{dir}/{name}", data)
            for name in ["ansible.cfg", "inventory.ini", "main.yml"]]


async def ansible_local_step(opts: dict) -> dict:
    """Write or remove the `~/.ssh/config` block. The same playbook serves both
    events; `block_state` is what distinguishes them."""
    dir = tool_dir(opts, ansible_local_tool)
    delete = opts.get("blue/event") == "delete"
    return await ansible_with_spec(
        opts, ansible_local_specs(opts),
        dir=dir, inventory="inventory.ini",
        playbooks={"create": "main.yml", "delete": "main.yml"},
        extra_vars={"host_alias": ssh_config.host_alias(opts),
                    "ip": opts.get("ip") or fallback_params(opts)["ip"],
                    "user": opts.get("user") or "root",
                    "block_state": "absent" if delete else "present"})


# ---------------------------------------------------------------- ansible


def _java_double(x: float) -> str:
    """Java's Double.toString, which is what Green's cheshire JSON emits for
    floats: decimal between 1e-3 and 1e7, `d.dddE±e` scientific outside it.
    Python's own repr disagrees exactly where the fixture prices live
    (0.0001 -> "1.0E-4"), and the goldens carry the Java form."""
    if math.isnan(x):
        return "NaN"
    if math.isinf(x):
        return "Infinity" if x > 0 else "-Infinity"
    negative = math.copysign(1.0, x) < 0
    magnitude = abs(x)
    if magnitude == 0.0:
        return "-0.0" if negative else "0.0"
    _sign, digits, exponent = Decimal(repr(magnitude)).as_tuple()
    digit_str = "".join(map(str, digits)).rstrip("0") or "0"
    dec_exp = exponent + len(digits) - 1
    if -3 <= dec_exp < 7:
        if dec_exp >= 0:
            whole = digit_str[:dec_exp + 1].ljust(dec_exp + 1, "0")
            frac = digit_str[dec_exp + 1:] or "0"
        else:
            whole = "0"
            frac = "0" * (-dec_exp - 1) + digit_str
        rendered = f"{whole}.{frac}"
    else:
        mantissa = digit_str[0] + "." + (digit_str[1:] or "0")
        rendered = f"{mantissa}E{dec_exp}"
    return ("-" if negative else "") + rendered


def _pretty(value, indent=0):
    """Cheshire's pretty JSON, byte for byte — Green's artifact contract."""
    if isinstance(value, list):
        if not value:
            return "[ ]"
        return "[ " + ", ".join(_pretty(item, indent) for item in value) + " ]"
    if isinstance(value, dict):
        if not value:
            return "{ }"
        pad = " " * (indent + 2)
        body = ",\n".join(f"{pad}{json.dumps(str(k))} : {_pretty(v, indent + 2)}"
                          for k, v in value.items())
        return "{\n" + body + "\n" + " " * indent + "}"
    if isinstance(value, float) and not isinstance(value, bool):
        return _java_double(value)
    return json.dumps(value)


def inventory(opts: dict) -> str:
    return _pretty(
        {"all": {"children": {"agent-network": {"hosts": {
            opts.get("profile"): {"ansible_host": opts.get("ip") or "192.0.2.10",
                                  "ansible_user": "root"}}}}}})


def desired_json(opts: dict) -> str:
    """The control plane's desired state, one JSON document the host bootstrap
    reconciles against. Everything in it is non-secret — the Anthropic key
    reaches the bootstrap as an environment variable resolved at play time and
    never lands in a rendered file."""
    def get(model, key):
        return model.get(key) if isinstance(model, dict) else None

    def model_entry(model) -> dict:
        entry = {"id": validate._s(get(model, "id")),
                 "input_per_1k": get(model, "input-per-1k"),
                 "output_per_1k": get(model, "output-per-1k")}
        if get(model, "cache-read-per-1k") is not None:
            entry["cache_read_per_1k"] = get(model, "cache-read-per-1k")
        if get(model, "cache-creation-per-1k") is not None:
            entry["cache_creation_per_1k"] = get(model, "cache-creation-per-1k")
        return entry

    return _pretty(
        {"host": opts.get("agent-network-host"),
         "admin_email": opts.get("agent-network-admin-email"),
         "admin_name": opts.get("agent-network-admin-name"),
         "provider":
         # The catalog id, from GET /api/agent-network/catalog/providers on the
         # pinned release — "anthropic" alone is a 422.
         {"provider_id": "anthropic_api",
          "name": "Anthropic",
          "upstream_url": "https://api.anthropic.com",
          "models": [model_entry(m) for m in validate.provider_models(opts)]},
         "allowed_models": validate.allowed_models(opts),
         "policy": {"budget_usd_per_day": opts.get("agent-network-policy-budget-usd-per-day"),
                    "tokens_per_day": opts.get("agent-network-policy-tokens-per-day")},
         "global": {"budget_usd_per_day": opts.get("agent-network-global-budget-usd-per-day"),
                    "tokens_per_day": opts.get("agent-network-global-tokens-per-day")},
         "log_retention_days": opts.get("agent-network-log-retention-days")})


def ansible_data(opts: dict) -> dict:
    """Template values for the Ansible stage.

    Deliberately carries no operator secret. The Anthropic key reaches the host
    as an Ansible `lookup('env', ...)` expression written literally into
    main.yml, where `preserve-jinja-delimiters` passes it through untouched —
    routing it through this map instead would let the renderer HTML-escape the
    quotes and hand Ansible `&#39;`. The secret therefore exists only in the
    process that needs it: not in `.colors/`, not in a golden, not in this
    map."""
    return {**opts,
            "ip": opts.get("ip") or "192.0.2.10",
            "traefik-ip": validate.traefik_ip(opts),
            "traefik-agent-ip": validate.traefik_agent_ip(opts),
            "proxy-agent-ip": validate.proxy_agent_ip(opts),
            "agent-ip": validate.agent_ip(opts),
            "allowed-model": validate.allowed_model(opts),
            "denied-claimed-model": validate.denied_claimed_model(opts),
            "ssh-keygen": validate.keygen(opts)}


ANSIBLE_FILES = [
    "ansible.cfg", "main.yml", "cleanup.yml", "compose.yml", "config.yaml",
    "dashboard.env", "proxy.env", "traefik-dynamic.yaml", "bootstrap.sh",
    "agent.Dockerfile", "agent-entry.sh", "smoke.sh", "status.sh",
    "firewall.sh", "firewall.service",
]


def ansible_specs(opts: dict) -> list[dict]:
    dir = tool_dir(opts, ansible_tool)
    data = ansible_data(opts)
    return [*[spec(template("ansible", name), f"{dir}/{name}", data)
              for name in ANSIBLE_FILES],
            raw_spec(f"{dir}/desired.json", desired_json(data)),
            raw_spec(f"{dir}/inventory.json", inventory(data))]


async def ansible_step(opts: dict) -> dict:
    dir = tool_dir(opts, ansible_tool)
    if opts.get("blue/event") == "delete" and not opts.get("ip"):
        # No compute in state: there is no host to stop, and the cleanup play
        # would only fail against the placeholder address.
        return {**opts, "blue/exit": 0}
    return await ansible_with_spec(
        opts, ansible_specs(opts),
        dir=dir, inventory="inventory.json",
        playbooks={"create": "main.yml", "delete": "cleanup.yml"},
        host_key_checking=False)


# ------------------------------------------------------------- acceptance


async def wait_for(args: list[str], attempts: int) -> bool:
    """True once `args` exits zero, retrying every five seconds."""
    n = attempts
    while True:
        result = await runtime.exec(args, timeout_ms=20000)
        if result.exit == 0:
            return True
        if n > 0:
            await asyncio.sleep(5)
            n -= 1
        else:
            return False


async def run(args: list[str]):
    return await runtime.exec(args, timeout_ms=20000)


async def out(args: list[str]) -> str:
    return str((await run(args)).out or "").strip()


async def cert_error(host: str) -> str | None:
    """Why the certificate for `host` is not acceptable, or None when it is.

    Traefik answers 443 with a self-signed default certificate when ACME has
    failed, so a reachable HTTPS endpoint proves nothing on its own. Three
    separate facts are checked: the chain validates against the system trust
    store (`curl` without `-k` fails otherwise), the certificate names this
    host, and it is not about to expire."""
    s_client = (f"echo | openssl s_client -servername {host}"
                f" -connect {host}:443 2>/dev/null")
    if (await run(["curl", "-fsS", "-o", "/dev/null", f"https://{host}/"])).exit != 0:
        return (f"the certificate for {host} is not trusted by the system store; Traefik is "
                "probably serving its self-signed default because ACME failed")
    san = await out(["sh", "-c", f"{s_client} | openssl x509 -noout -ext subjectAltName"])
    if host not in san:
        return f"the certificate served for {host} does not name it"
    if (await run(["sh", "-c", f"{s_client} | openssl x509 -noout -checkend 604800"])).exit != 0:
        return f"the certificate for {host} expires within seven days and has not renewed"
    return None


async def closed(host: str, port: int) -> bool:
    """Whether a TCP port refuses a connection from out here. `bind to
    loopback` regresses silently while every positive check still passes, so
    absence is asserted rather than assumed."""
    result = await run(["sh", "-c",
                        f"timeout 5 bash -c '</dev/tcp/{host}/{port}' 2>/dev/null"])
    return result.exit != 0


async def ssh_out(opts: dict, command: str) -> str:
    """One command on the deployment host, over the machine key."""
    args = ["ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new",
            *ssh.identity_args(opts), f"root@{opts.get('ip')}", command]
    return await out(args)


async def local_overlay_error() -> str | None:
    """The external negative probe must run from outside the overlay, and this
    workstation is where it runs. A NetBird interface here would mean the probe
    can silently succeed through the tunnel and prove nothing, so its absence
    is checked rather than assumed."""
    links = await out(["sh", "-c", "ip -o link show 2>/dev/null | awk -F': ' '{print $2}'"])
    if re.search(r"(?m)^(wt|netbird)", links):
        found = re.search(r"(?m)^(?:wt|netbird)[^\s@]*", links)
        return (f"this workstation carries a NetBird/WireGuard interface ("
                f"{found.group(0) if found else ''}"
                "); the external tunnel-only probe would not be external. "
                "Disconnect the local NetBird client and re-run create.")
    return None


async def acceptance_step(opts: dict) -> dict:
    """Public health checks after a real create.

    What runs here is what the internet can see: the dashboard, its
    certificate, its substituted configuration, the ports that must refuse,
    and — the claim this demo exists to make — that the generated
    agent-network endpoint denies a caller who is not on the overlay. The
    in-tunnel proofs (isolation, the keyless call, the denial reasons,
    attribution) run inside the playbook as `agent-network-smoke`, where the
    automation credential lives."""
    if opts.get("blue/event") != "create":
        return {**opts, "blue/exit": 0}
    host = opts.get("agent-network-host")
    ip = opts.get("ip")
    if not await wait_for(["curl", "-fsS", "-o", "/dev/null", f"https://{host}/"], 60):
        return {**opts, "blue/exit": 1,
                "blue/err": "the dashboard did not become reachable over HTTPS"}
    cert_errors = [e for e in [await cert_error(host)] if e]
    # The dashboard substitutes its configuration into the built assets at
    # container start, and the script that does it exits non-zero on a missing
    # variable while supervisord carries on. nginx then serves the placeholders
    # verbatim and every request for `/` still returns 200 — so the page has to
    # be read, not merely fetched. This shipped once in the sibling package.
    page = await out(["curl", "-fsS", f"https://{host}/"])
    chunks = list(dict.fromkeys(
        re.findall(r"/_next/static/chunks/[A-Za-z0-9_.\-]+\.js", page)))[:6]
    unsubstituted = None
    for url in chunks:
        if "$NETBIRD_" in await out(["curl", "-fsS", f"https://{host}{url}"]):
            unsubstituted = url
            break
    # Ports that must not answer from outside: the server's metrics and
    # healthcheck listeners, the proxy's direct 8443, and the proxy's WireGuard
    # 51820 (the only peer is on the internal Docker network; nothing external
    # enrolls).
    open_ports = [p for p in [9090, 9000, 8443] if not await closed(ip, p)]
    overlay_error = await local_overlay_error()
    endpoint = await ssh_out(opts, "cat /etc/agent-network/state/endpoint 2>/dev/null")
    if cert_errors:
        return {**opts, "blue/exit": 1, "blue/err": "; ".join(cert_errors)}
    if unsubstituted:
        return {**opts, "blue/exit": 1,
                "blue/err": (f"the dashboard is serving unsubstituted configuration in "
                             f"{unsubstituted}; init_react_envs failed at container start "
                             "(a missing variable makes it exit 1 while nginx keeps serving)")}
    if open_ports:
        return {**opts, "blue/exit": 1,
                "blue/err": ("ports that must not be public answered: "
                             + ", ".join(str(p) for p in open_ports))}
    if overlay_error is not None:
        return {**opts, "blue/exit": 1, "blue/err": overlay_error}
    if not endpoint.strip():
        return {**opts, "blue/exit": 1,
                "blue/err": "the host records no agent-network endpoint; bootstrap did not complete"}
    # The tunnel-only claim, tested rather than asserted: the same request the
    # agent makes must fail from here, where there is no tunnel. -k because the
    # endpoint's certificate may legitimately still be issuing; what is under
    # test is the deny, not the chain (gate 3 inside the tunnel already proved
    # the chain). Served means bypassed: a 200 completion OR the relayed
    # upstream 401 both prove the caller's request reached Anthropic through
    # server-side key injection without a tunnel identity. The correct outcome
    # is the proxy's own pre-identity denial (observed: a bare 403), which
    # reaches no upstream and writes no access-log entry.
    probe = await out(["sh", "-c",
                       ("curl -sk --max-time 20 -w '\\nHTTPCODE:%{http_code}' "
                        f"-X POST https://{endpoint}/v1/messages "
                        "-H 'content-type: application/json' "
                        '--data \'{"model":"' + str(validate.allowed_model(opts))
                        + '","max_tokens":16,"messages":[{"role":"user","content":"hi"}]}\'')])
    found = re.search(r"HTTPCODE:(\d+)", probe)
    code = found.group(1) if found else "000"
    # Exactly the pre-identity 403, fail-closed: a 200 or the upstream 401
    # means the caller was served through key injection, and any other status
    # (000, 404, 429, 5xx) means the probe observed something other than the
    # deny it exists to prove.
    if code != "403":
        return {**opts, "blue/exit": 1,
                "blue/err": (f"the agent-network endpoint {endpoint}"
                             " did not answer an outside caller with the "
                             "pre-identity 403; it must be tunnel-only")}
    # And the probe must have left no unattributed access-log entry:
    # pre-identity denials are dropped before logging, so any entry without a
    # caller identity means something external was served.
    unattributed = await ssh_out(
        opts,
        ('curl -fsS -H "Authorization: Token $(cat /etc/agent-network/secrets/pat)" '
         f"'https://{host}/api/agent-network/access-logs?page=1&page_size=100' "
         "| jq -r '[.data[] | select((.user_id // \"\") == \"\")] | length'"))
    if unattributed.strip() != "0":
        return {**opts, "blue/exit": 1,
                "blue/err": ("the access log holds entries with no caller identity; "
                             "an external request was served")}
    return {**opts, "blue/exit": 0,
            "agent-network/acceptance": {"dashboard": "configured",
                                         "certificate": "trusted",
                                         "closed-ports": "confirmed",
                                         "endpoint": endpoint,
                                         "tunnel-only": "confirmed"}}
