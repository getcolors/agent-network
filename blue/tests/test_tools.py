import json
import re

from conftest import fixture, optout
from package_agent_network_blue import tools, validate


def spec_for(opts, file):
    return next(s for s in tools.ansible_specs(opts)
                if str(s["target"]).endswith(file))


def resource(path):
    return (tools.ROOT / path).read_text()


def test_firewall_sources_parse():
    data = tools.infrastructure_data(fixture())
    assert tools.cidrs(data, "vultr-http-sources") == ["0.0.0.0/0"]
    assert tools.cidrs(data, "vultr-stun-sources") == ["0.0.0.0/0"]


def test_infrastructure_data_carries_the_ssh_mode():
    assert tools.infrastructure_data(fixture())["ssh-keygen"] is True
    assert tools.infrastructure_data(optout())["ssh-keygen"] is False


def test_every_label_derives_from_one_resolved_name():
    # Compute Name Standard §3: one function answers "what is this deployment's
    # machine called", and the firewall asks it too rather than keeping a
    # second copy of the profile.
    data = tools.infrastructure_data(fixture({"vultr-name": "override-box"}))
    assert data["compute-name"] == "override-box"


def test_dns_zone_is_registrable_domain():
    assert validate.zone(fixture()) == "example.com"


def test_dns_creates_the_name_and_its_wildcard_unproxied():
    # Unproxied because Cloudflare's proxy is an HTTP proxy: UDP STUN does not
    # survive it and both certificate paths — Traefik's TLS-ALPN-01 and the
    # reverse proxy's own endpoint ACME — would terminate at the proxy.
    #
    # The wildcard is contract, not convenience: the endpoint hostname is a
    # label management mints beneath the base domain at bootstrap, and nothing
    # knows it before it exists.
    json_out = tools.dns_json({**fixture(), "ip": "192.0.2.10"})
    assert "agent-network.example.com" in json_out
    assert "*.agent-network.example.com" in json_out
    assert "192.0.2.10" in json_out
    assert '"proxied" : false' in json_out
    assert "true" not in json_out


def test_inventory_keeps_one_target():
    inventory = tools.inventory({**fixture(), "ip": "192.0.2.10"})
    assert "192.0.2.10" in inventory
    assert "agent-network-fixture" in inventory


def test_ansible_renders_the_whole_stack():
    targets = [str(s["target"]) for s in tools.ansible_specs(fixture())]
    for file in ["ansible.cfg", "main.yml", "cleanup.yml", "compose.yml", "config.yaml",
                 "dashboard.env", "proxy.env", "traefik-dynamic.yaml", "bootstrap.sh",
                 "agent.Dockerfile", "agent-entry.sh", "smoke.sh", "status.sh",
                 "firewall.sh", "firewall.service", "desired.json", "inventory.json"]:
        assert any(t.endswith(file) for t in targets), file


def test_the_operator_secret_reaches_the_host_as_a_lookup_not_a_value():
    # `.colors/` is generated output and the goldens are committed, so the
    # secret must never be the thing that lands on disk — the expression is.
    # The lookup lives literally in the template rather than in the data map,
    # because the renderer HTML-escapes a value it interpolates and Ansible
    # would receive `&#39;` instead of a quote.
    template = resource("tools/ansible/main.yml")
    assert "lookup('env','COLORS_PAR_ANTHROPIC_API_KEY')" in template


def test_the_data_map_carries_no_operator_secret():
    data = spec_for(fixture(), "main.yml")["data"]
    assert data["agent-network-host"] == "agent-network.example.com"
    assert data.get("anthropic-api-key") is None


def test_generated_secrets_are_placeholders_in_the_rendered_config():
    # Host-generated secrets are substituted on the host at install time, so
    # what `build` renders — and what a golden commits — is the placeholder.
    config = resource("tools/ansible/config.yaml")
    proxy = resource("tools/ansible/proxy.env")
    for placeholder in ["__RELAY_AUTH_SECRET__", "__SESSION_COOKIE_ENCRYPTION_KEY__",
                        "__DATASTORE_ENCRYPTION_KEY__"]:
        assert placeholder in config, placeholder
    assert "__PROXY_TOKEN__" in proxy


def test_desired_json_carries_the_control_plane_contract():
    # The host bootstrap reconciles against this document; its shape is the
    # wire shape of the agent-network API (underscore keys, per-1k prices).
    desired = json.loads(tools.desired_json(fixture()))
    assert desired["provider"]["provider_id"] == "anthropic_api"
    assert desired["provider"]["upstream_url"] == "https://api.anthropic.com"
    assert desired["allowed_models"] == ["claude-haiku-4-5-20251001"]
    assert len(desired["provider"]["models"]) == 2
    assert desired["provider"]["models"][0]["input_per_1k"] == 0.001
    assert desired["policy"]["budget_usd_per_day"] == 2
    assert desired["global"]["budget_usd_per_day"] == 5
    assert desired["log_retention_days"] == 7
    # Non-secret by construction: nothing shaped like a credential belongs
    # here, and the API key in particular must not.
    assert "api_key" not in tools.desired_json(fixture())


def test_desired_json_floats_render_in_the_java_shape():
    # Cheshire emits Java Double.toString: the goldens carry 1.0E-4, not
    # Python's 0.0001.
    desired = tools.desired_json(fixture())
    assert '"cache_read_per_1k" : 1.0E-4' in desired
    assert '"cache_read_per_1k" : 3.0E-4' in desired


async def test_a_delete_without_compute_skips_the_host_entirely():
    # There is no machine to stop, and the cleanup play would only fail against
    # the placeholder address.
    result = await tools.ansible_step({**fixture(), "blue/event": "delete"})
    assert result["blue/exit"] == 0


async def test_acceptance_is_skipped_outside_a_real_create():
    for event in ["build", "delete"]:
        result = await tools.acceptance_step({**fixture(), "blue/event": event})
        assert result["blue/exit"] == 0


def test_hairpin_is_broken_by_exactly_two_mappings():
    # Two containers must reach the public hostname without leaving the box:
    # the agent's bootstrap terminates at Traefik on the internal network
    # (tunnel DNS does not exist yet), and the proxy's embedded client reaches
    # signal/relay at Traefik on the gateway network (hairpin NAT otherwise).
    # Nothing else is mapped — in particular not the endpoint hostname, whose
    # resolution is management's, pushed over the tunnel, because that is what
    # keeps the metered path on the tunnel and the identity real.
    compose = resource("tools/ansible/compose.yml")
    assert "<{ agent-network-host }>:<{ traefik-agent-ip }>" in compose
    assert "<{ agent-network-host }>:<{ traefik-ip }>" in compose
    assert len(re.findall(r"(?m)^\s+extra_hosts:", compose)) == 2


def test_the_agent_joins_only_the_internal_network():
    compose = resource("tools/ansible/compose.yml")
    found = re.search(r"(?ms)^  agent:\n(.*?)^volumes:", compose)
    assert found is not None, "the agent service block must be findable"
    service = found.group(1)
    assert "agent:\n        ipv4_address" in service
    assert not re.search(r"(?m)^\s+gateway", service), \
        "the agent may not attach to the egress-capable network"
    assert "internal: true" in compose


def test_the_dashboard_runs_the_agent_network_preset():
    # `init_react_envs` exits 1 without USE_AUTH0 and supervisord carries on
    # (nginx then serves placeholders while `/` returns 200); and this package
    # exists to show the focused agent-network surface, so the preset flag must
    # be present — the sibling `netbird` package asserts its absence.
    env = resource("tools/ansible/dashboard.env")
    assert "USE_AUTH0=false" in env
    assert "NETBIRD_AGENT_NETWORK_ONLY=true" in env


def test_every_claude_model_knob_is_pinned():
    # A single unpinned tier (subagents included) lets Claude Code name a model
    # the guardrail rejects, and the demo's happy path dies on its own policy.
    main = resource("tools/ansible/main.yml")
    for knob in ["ANTHROPIC_MODEL", "ANTHROPIC_SMALL_FAST_MODEL",
                 "ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL",
                 "ANTHROPIC_DEFAULT_HAIKU_MODEL", "CLAUDE_CODE_SUBAGENT_MODEL"]:
        assert f"{knob}=<{{ allowed-model }}>" in main, knob


def test_the_setup_key_travels_by_file_never_argv():
    # client/cmd/root.go: --setup-key-file reads the key from a path;
    # --setup-key would put it in a process listing.
    entry = resource("tools/ansible/agent-entry.sh")
    assert "--setup-key-file" in entry
    assert not re.search(r"--setup-key\s+[^-f]", entry)
