from conftest import do_fixture, do_optout, fixture, optout
from package_agent_network_blue import validate


def test_fixture_is_valid():
    assert validate.state_errors(fixture()) == []


def test_optout_fixture_is_valid():
    assert validate.state_errors(optout()) == []


def test_digitalocean_fixtures_are_valid():
    assert validate.state_errors(do_fixture()) == []
    assert validate.state_errors(do_optout()) == []


# --- the spec handed to ONCE -------------------------------------------------


def test_the_spec_carries_this_packages_registry_sources_and_default():
    # The operations are ONCE's; this is the data they run over. A colour
    # whose registry, sources or default drifts fails here, in that colour.
    assert sorted(validate.spec["registry"]) == ["digitalocean", "vultr"]
    assert validate.spec["registry"] is validate.compute_providers
    assert validate.spec["registry"]["digitalocean"] == {
        "required": ["digitalocean-region", "digitalocean-size", "digitalocean-image",
                     "digitalocean-ssh-sources", "digitalocean-http-sources",
                     "digitalocean-stun-sources"],
        "secrets": ["do-token"],
        "tofu-env": {"do-token": "DIGITALOCEAN_TOKEN"},
    }
    assert validate.spec["registry"]["vultr"] == {
        "required": ["vultr-region", "vultr-plan", "vultr-os-id",
                     "vultr-ssh-sources", "vultr-http-sources", "vultr-stun-sources"],
        "secrets": ["vultr-api-key"],
        "tofu-env": {"vultr-api-key": "VULTR_API_KEY"},
    }
    # Three lists, not the standard's two: STUN is a published UDP port here.
    assert validate.spec["sources"] == {"non_empty": ["ssh-sources"],
                                        "may_be_empty": ["http-sources", "stun-sources"]}
    assert validate.spec["default"] == "vultr"
    assert validate.spec["default"] == validate.default_compute_provider
    # The name rules are ONCE's.
    assert "name_rules" not in validate.spec


# --- the compute-provider registry (Compute Provider Standard) ---------------


def test_unsupported_provider_names_the_advertised_ones():
    assert ":provider-compute must be one of digitalocean, vultr" in \
        validate.state_errors(fixture({"provider-compute": "hetzner"}))


def test_required_keys_follow_the_selected_provider():
    assert ":digitalocean-size is required" in validate.state_errors(do_fixture({"digitalocean-size": None}))
    assert ":digitalocean-stun-sources is required" in \
        validate.state_errors(do_fixture({"digitalocean-stun-sources": None}))
    assert ":vultr-plan is required" in validate.state_errors(fixture({"vultr-plan": None}))
    # The other provider's keys are neither required nor refused, so one
    # colors.yml can carry both and move between providers by one edit.
    assert not any("vultr" in e for e in validate.state_errors(do_fixture()))
    assert validate.state_errors(fixture({"digitalocean-region": "ams3",
                                          "digitalocean-size": "s-1vcpu-1gb"})) == []
    assert validate.state_errors(do_fixture({"vultr-os-id": "not-checked-here"})) == []


def test_name_and_machine_key_are_never_required():
    for errors in [validate.state_errors(fixture({"vultr-name": None})),
                   validate.state_errors(do_fixture())]:
        assert not any("-name" in e for e in errors)
        assert not any("-ssh-keys" in e for e in errors)


def test_compute_key_is_provider_scoped():
    assert validate.compute_key(fixture(), "ssh-sources") == "vultr-ssh-sources"
    assert validate.compute_key(do_fixture(), "stun-sources") == "digitalocean-stun-sources"


def test_the_name_override_is_read_from_the_selected_provider_alone():
    assert validate.compute_name(do_fixture()) == "agent-network-digitalocean-fixture"
    assert validate.compute_name(do_optout()) == "agent-network-digitalocean-optout"
    assert validate.compute_name(do_fixture({"vultr-name": "custom-label"})) == \
        "agent-network-digitalocean-fixture"
    assert validate.compute_name(do_fixture({"digitalocean-name": "droplet-01"})) == "droplet-01"


# --- the network contract ----------------------------------------------------


def test_ssh_sources_must_not_be_empty():
    assert ":vultr-ssh-sources must list at least one CIDR" in \
        validate.state_errors(fixture({"vultr-ssh-sources": []}))
    assert ":digitalocean-ssh-sources must list at least one CIDR" in \
        validate.state_errors(do_fixture({"digitalocean-ssh-sources": " , "}))
    # No public HTTP, or no public STUN, is a legitimate deployment.
    assert validate.state_errors(fixture({"vultr-http-sources": []})) == []
    assert validate.state_errors(fixture({"vultr-stun-sources": []})) == []
    assert validate.state_errors(do_fixture({"digitalocean-http-sources": []})) == []
    assert validate.state_errors(do_fixture({"digitalocean-stun-sources": []})) == []


def test_malformed_sources_are_refused_before_any_provider_call():
    assert ':vultr-http-sources entry "10.0.0.0" is not an IPv4 or IPv6 CIDR' in \
        validate.state_errors(fixture({"vultr-http-sources": ["0.0.0.0/0", "10.0.0.0"]}))
    assert ':vultr-stun-sources entry "stun.example.com/32" is not an IPv4 or IPv6 CIDR' in \
        validate.state_errors(fixture({"vultr-stun-sources": ["stun.example.com/32"]}))
    assert ':digitalocean-ssh-sources entry "office.example.com/32" is not an IPv4 or IPv6 CIDR' in \
        validate.state_errors(do_fixture({"digitalocean-ssh-sources": "office.example.com/32"}))
    # Only the selected provider's lists are checked.
    assert validate.state_errors(do_fixture({"vultr-ssh-sources": ["garbage"]})) == []


def test_machine_key_is_not_required():
    # The standard makes absence meaningful: requiring vultr-ssh-keys would
    # make every conforming deployment invalid.
    assert not any("vultr-ssh-keys" in e for e in validate.state_errors(fixture()))


def test_absent_machine_key_selects_keygen():
    assert validate.keygen(fixture()) is True
    assert validate.keygen(optout()) is False


# --- Compute Name Standard ---------------------------------------------------


def test_a_name_key_is_not_required():
    # §1: a fresh colors.yml that omits it is complete.
    assert not any("vultr-name" in e for e in validate.state_errors(fixture()))


def test_the_machine_is_named_after_the_profile():
    assert validate.compute_name(fixture()) == "agent-network-fixture"


def test_presence_is_the_only_switch():
    # §2: absent, blank and REPLACE_ME all mean the profile; anything else is
    # the name.
    for value in [None, "", "   ", "REPLACE_ME"]:
        assert validate.compute_name(fixture({"vultr-name": value})) == "agent-network-fixture", repr(value)
    assert validate.compute_name(fixture({"vultr-name": "custom-box"})) == "custom-box"


def test_the_override_is_validated_not_passed_through():
    # §2: validate against the provider's naming rules rather than reading it
    # unread.
    assert any("vultr-name" in e
               for e in validate.state_errors(fixture({"vultr-name": "not a valid label!"})))
    assert validate.state_errors(fixture({"vultr-name": "agent-box_1.a"})) == []


def test_there_is_no_package_key():
    # §5: a key that can hold exactly one value carries no information.
    assert not any("package" in e for e in validate.state_errors(fixture()))
    assert "package" not in validate.required


# --- desired state -----------------------------------------------------------


def test_reports_all_errors():
    errors = validate.state_errors(fixture({
        "agent-network-host": "bad",
        "agent-network-server-image": "floating",
        "agent-network-letsencrypt-email": "not-an-email",
        "provider-dns": "other", "provider-compute": "hetzner",
        "agent-network-log-retention-days": 0,
        "agent-network-stun-port": 70000,
        "agent-network-gateway-subnet": "nonsense",
        "agent-network-claude-code-version": "latest"}))
    assert len(errors) >= 8
    for part in ["host", "image", "letsencrypt-email", "provider-dns", "provider-compute",
                 "retention-days", "stun-port", "gateway-subnet",
                 "claude-code-version"]:
        assert any(part in e for e in errors), part


def test_the_two_subnets_must_not_overlap():
    # One compose network shadowing the other would break both the isolation
    # boundary and the DOCKER-USER allow list derived from static addresses.
    assert any("must not overlap" in e for e in validate.state_errors(
        fixture({"agent-network-agent-subnet": "172.30.0.0/24"})))
    assert any("must not overlap" in e for e in validate.state_errors(
        fixture({"agent-network-agent-subnet": "172.30.0.0/16"})))


def test_addresses_derive_from_the_subnets():
    assert validate.traefik_ip(fixture()) == "172.30.0.10"
    assert validate.traefik_agent_ip(fixture()) == "172.31.0.10"
    assert validate.proxy_agent_ip(fixture()) == "172.31.0.11"
    assert validate.agent_ip(fixture()) == "172.31.0.20"
    assert validate.traefik_ip(fixture({"agent-network-gateway-subnet": "10.9.0.0/24"})) == "10.9.0.10"


def test_the_guardrail_needs_a_deniable_model():
    # Gate 3b demonstrates the guardrail denial, which needs a model routing
    # accepts and the allowlist rejects. Allowlisting everything the provider
    # claims would configure the guardrail and never demonstrate it.
    assert any("outside" in e for e in validate.state_errors(
        fixture({"agent-network-allowed-models":
                 ["claude-haiku-4-5-20251001", "claude-sonnet-4-5-20250929"]})))


def test_allowed_models_must_be_claimed():
    assert any("not claimed" in e for e in validate.state_errors(
        fixture({"agent-network-allowed-models": ["claude-3-opus"]})))


def test_the_denied_claimed_model_is_derived():
    assert validate.allowed_model(fixture()) == "claude-haiku-4-5-20251001"
    assert validate.denied_claimed_model(fixture()) == "claude-sonnet-4-5-20250929"


def test_models_need_prices():
    assert any("positive input-per-1k" in e for e in validate.state_errors(
        fixture({"agent-network-provider-models":
                 [{"id": "claude-haiku-4-5-20251001"},
                  {"id": "claude-sonnet-4-5-20250929",
                   "input-per-1k": 0.003, "output-per-1k": 0.015}]})))


def test_policy_caps_must_not_exceed_the_global_ceiling():
    # The global rule is the backstop; a policy cap above it would never bind.
    assert any("must not exceed" in e for e in validate.state_errors(
        fixture({"agent-network-policy-budget-usd-per-day": 50})))
    assert any("must not exceed" in e for e in validate.state_errors(
        fixture({"agent-network-policy-tokens-per-day": 99999999})))


def test_retention_mirrors_the_product_range():
    assert any("between 7 and 90" in e for e in validate.state_errors(
        fixture({"agent-network-log-retention-days": 5})))
    assert validate.state_errors(fixture({"agent-network-log-retention-days": 90})) == []


def test_accepts_a_digest_pin():
    assert validate.state_errors(fixture(
        {"agent-network-traefik-image": "traefik@sha256:" + "a" * 64})) == []


def test_no_image_may_float():
    for key in validate.image_keys:
        assert any("floating tag" in e for e in validate.state_errors(
            fixture({key: "netbirdio/netbird-server:latest"}))), key


def test_a_floating_tag_with_a_digest_still_floats():
    # `latest@sha256:...` is pinned bytes under a lying label; the next accept
    # of a digest bump would silently re-derive "latest" as the version.
    assert any("floating tag" in e for e in validate.state_errors(
        fixture({"agent-network-server-image":
                 "netbirdio/netbird-server:latest@sha256:" + "a" * 64})))


def test_an_untagged_image_is_refused():
    # `repository/name` means :latest by implication and would walk past a
    # suffix-only check for ":latest".
    assert any("explicit image tag" in e for e in validate.state_errors(
        fixture({"agent-network-server-image": "netbirdio/netbird-server"})))


def test_versions_are_exact():
    for key in ["agent-network-claude-code-version", "agent-network-netbird-client-version"]:
        assert any("exact x.y.z" in e for e in validate.state_errors(
            fixture({key: "2.x"}))), key


def test_profile_overlay_is_refused():
    assert validate.env_errors({"COLORS_PAR_PROFILE": "other"})
    assert not validate.env_errors({})


# --- credentials -------------------------------------------------------------


def test_a_create_names_every_operator_secret():
    errors = "\n".join(validate.secret_errors(fixture(), "create"))
    for name in ["COLORS_PAR_VULTR_API_KEY", "COLORS_PAR_CLOUDFLARE_API_TOKEN",
                 "COLORS_PAR_ANTHROPIC_API_KEY"]:
        assert name in errors, name
    # Generated on the host and supplied by nobody.
    for absent in ["RELAY", "SESSION", "ENCRYPTION_KEY", "PROXY_TOKEN",
                   "ADMIN_PASSWORD", "SETUP_KEY", "PAT"]:
        assert absent not in errors, absent
    assert "COLORS_PAR_DO_TOKEN" not in errors


def test_secrets_and_tofu_env_follow_the_selected_provider():
    errors = "\n".join(validate.secret_errors(do_fixture(), "create"))
    assert "COLORS_PAR_DO_TOKEN" in errors
    assert "COLORS_PAR_CLOUDFLARE_API_TOKEN" in errors
    assert "COLORS_PAR_ANTHROPIC_API_KEY" in errors
    assert "COLORS_PAR_VULTR_API_KEY" not in errors
    assert validate.tofu_env(do_fixture(), "provider-compute") == {"do-token": "DIGITALOCEAN_TOKEN"}
    assert validate.tofu_env(fixture(), "provider-compute") == {"vultr-api-key": "VULTR_API_KEY"}
    assert validate.tofu_env(fixture({"provider-compute": "hetzner"}), "provider-compute") == {}


def test_a_delete_does_not_ask_for_the_anthropic_key():
    # This deployment is disposable: a delete needs the provider credentials
    # alone, and demanding the Anthropic key to destroy a machine would just
    # be a lock on the exit.
    errors = "\n".join(validate.secret_errors(fixture(), "delete"))
    assert "COLORS_PAR_VULTR_API_KEY" in errors
    assert "COLORS_PAR_CLOUDFLARE_API_TOKEN" in errors
    assert "ANTHROPIC" not in errors
