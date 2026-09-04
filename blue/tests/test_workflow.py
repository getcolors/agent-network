import pytest
from blue.workflow import StepError
from conftest import do_fixture, fixture
from package_agent_network_blue import workflow

# The compute state is read once per run, through `state_output`, on a real
# create or delete. Every lifecycle test stubs it: None is a readable state
# holding no compute, a dict is a recorded `params`, and a raise is a backend
# that cannot be read.

CREDENTIALS = {"vultr-api-key": "v", "do-token": "d", "cloudflare-api-token": "c",
               "r2-access-key-id": "a", "r2-secret-access-key": "s", "anthropic-api-key": "k"}


@pytest.fixture
def state(monkeypatch):
    def install(params):
        async def stub(_opts):
            return params
        monkeypatch.setattr(workflow, "state_output", stub)
    return install


@pytest.fixture
def unreadable(monkeypatch):
    # The shape `blue.tofu` raises: the SDK's StepError. Only that is an
    # unreadable backend; anything else propagates as a defect.
    async def boom(_opts):
        raise StepError("tofu output failed: no backend")
    monkeypatch.setattr(workflow, "state_output", boom)


async def test_build_and_dry_run_need_no_credentials():
    result = await workflow.start_step({**fixture(), "blue/event": "build"}, env={})
    assert result["blue/exit"] == 0
    result = await workflow.start_step(
        {**fixture(), "blue/event": "create", "blue/dry-run": True}, env={})
    assert result["blue/exit"] == 0
    result = await workflow.start_step({**do_fixture(), "blue/event": "build"}, env={})
    assert result["blue/exit"] == 0


async def test_build_and_dry_run_never_touch_ssh_or_state(unreadable):
    # The standard forbids reading, creating, or requiring anything under
    # ~/.ssh on a build or dry-run: they render from desired state alone. Nor
    # do they read the backend: a raising state read proves nothing on these
    # paths reaches it.
    for opts in [{**fixture(), "blue/event": "build"},
                 {**fixture(), "blue/event": "create", "blue/dry-run": True},
                 {**do_fixture(), "blue/event": "delete", "blue/dry-run": True}]:
        result = await workflow.start_step(opts, env={})
        assert result["blue/exit"] == 0
        assert str(result["ssh-public-key-path"]).startswith("/home/build-placeholder"), \
            "a build must not name the operator's home directory"


async def test_real_create_requires_credentials(state):
    state(None)
    result = await workflow.start_step({**fixture(), "blue/event": "create"}, env={})
    assert result["blue/exit"] == 2
    assert "COLORS_PAR_VULTR_API_KEY" in result["blue/err"]
    assert "COLORS_PAR_CLOUDFLARE_API_TOKEN" in result["blue/err"]
    assert "COLORS_PAR_ANTHROPIC_API_KEY" in result["blue/err"]


async def test_real_create_and_delete_require_the_selected_providers_credentials(state):
    state(None)
    create = await workflow.start_step({**do_fixture(), "blue/event": "create"}, env={})
    assert create["blue/exit"] == 2
    assert "COLORS_PAR_DO_TOKEN" in create["blue/err"]
    assert "COLORS_PAR_ANTHROPIC_API_KEY" in create["blue/err"]
    assert "COLORS_PAR_VULTR_API_KEY" not in create["blue/err"]
    delete = await workflow.start_step(
        {**do_fixture(), "blue/event": "delete", "compute-prevent-destroy": False}, env={})
    assert delete["blue/exit"] == 2
    assert "COLORS_PAR_DO_TOKEN" in delete["blue/err"]
    assert "ANTHROPIC" not in delete["blue/err"]
    assert "COLORS_PAR_VULTR_API_KEY" not in delete["blue/err"]
    vultr = await workflow.start_step(
        {**fixture(), "blue/event": "delete", "compute-prevent-destroy": False}, env={})
    assert "COLORS_PAR_VULTR_API_KEY" in vultr["blue/err"]
    assert "COLORS_PAR_DO_TOKEN" not in vultr["blue/err"]


async def test_delete_is_protected(state):
    state(None)
    result = await workflow.start_step({**fixture(), "blue/event": "delete"}, env={})
    assert result["blue/exit"] == 2
    assert "COMPUTE_PREVENT_DESTROY" in result["blue/err"]


# --- provider switching is a rebuild, never an apply -------------------------


async def test_a_provider_switch_is_refused_on_create_and_delete(state):
    for event in ["create", "delete"]:
        state({"provider": "digitalocean", "ip": "203.0.113.9"})
        vultr = await workflow.start_step(
            {**fixture(), "blue/event": event, "compute-prevent-destroy": False}, env={})
        assert vultr["blue/exit"] == 2, event
        assert ("state holds a digitalocean machine; set provider-compute back to "
                "digitalocean and delete first") in vultr["blue/err"]
        # The validator order is the thing under test: the actionable error,
        # not a missing token for the provider that was just selected.
        assert "required credential is not set" not in vultr["blue/err"]
        state({"provider": "vultr", "ip": "203.0.113.9"})
        digitalocean = await workflow.start_step(
            {**do_fixture(), "blue/event": event, "compute-prevent-destroy": False}, env={})
        assert digitalocean["blue/exit"] == 2
        assert "state holds a vultr machine; set provider-compute back to vultr" in digitalocean["blue/err"]
        assert "COLORS_PAR_DO_TOKEN" not in digitalocean["blue/err"]


async def test_legacy_state_accepts_only_the_default_provider(state):
    state({"ip": "203.0.113.9"})
    for event in ["create", "delete"]:
        vultr = await workflow.start_step(
            {**fixture(), "blue/event": event, "compute-prevent-destroy": False}, env={})
        assert "state holds" not in vultr["blue/err"], event
        assert "required credential is not set" in vultr["blue/err"], event
        digitalocean = await workflow.start_step(
            {**do_fixture(), "blue/event": event, "compute-prevent-destroy": False}, env={})
        assert digitalocean["blue/exit"] == 2
        assert "no recorded provider" in digitalocean["blue/err"], event
        assert "set provider-compute back to vultr and delete first" in digitalocean["blue/err"]
        assert "COLORS_PAR_DO_TOKEN" not in digitalocean["blue/err"]


async def test_a_matching_provider_passes_to_the_credentials(state):
    state({"provider": "vultr", "ip": "203.0.113.9"})
    result = await workflow.start_step({**fixture(), "blue/event": "create"}, env={})
    assert result["blue/exit"] == 2
    assert "state holds" not in result["blue/err"]
    assert "COLORS_PAR_VULTR_API_KEY" in result["blue/err"]


async def test_an_unreadable_backend_counts_as_no_state_on_create(unreadable):
    # A fresh clone has no readable state and must still be able to create.
    result = await workflow.start_step({**fixture(), "blue/event": "create"}, env={})
    assert result["blue/exit"] == 2
    assert "could not read" not in result["blue/err"]
    assert "state holds" not in result["blue/err"]
    assert "COLORS_PAR_VULTR_API_KEY" in result["blue/err"]


async def test_a_real_create_on_a_fresh_work_directory_reports_the_credentials_not_a_crash(tmp_path):
    # No stub: the real `state_output` runs against a work directory that
    # holds no stage yet, exactly as a fresh clone's first create does. The
    # SDK's output read raises its StepError there; ONCE's `read_state` must
    # read that as an unreadable state — no state on a create — and the run
    # must reach the credential check.
    result = await workflow.start_step(
        {**fixture(), "blue/event": "create", "workdir": str(tmp_path / "fresh")}, env={})
    assert result["blue/exit"] == 2
    assert "COLORS_PAR_VULTR_API_KEY" in result["blue/err"]
    assert "could not read" not in result["blue/err"]


async def test_an_unreadable_backend_fails_a_real_delete_closed(unreadable, tmp_path, monkeypatch):
    # Swallowing it is how a teardown ends up converging against 192.0.2.10.
    monkeypatch.setenv("HOME", str(tmp_path))
    result = await workflow.start_step(
        {**fixture(), **CREDENTIALS, "blue/event": "delete", "compute-prevent-destroy": False}, env={})
    assert result["blue/exit"] == 1
    assert "could not read the infrastructure state for the delete cleanup" in result["blue/err"]
    assert "no backend" in result["blue/err"]


async def test_a_real_delete_adopts_the_recorded_address(state, tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    state({"provider": "vultr", "ip": "203.0.113.9", "user": "root"})
    adopted = await workflow.start_step(
        {**fixture(), **CREDENTIALS, "blue/event": "delete", "compute-prevent-destroy": False}, env={})
    assert adopted["blue/exit"] == 0
    assert adopted["ip"] == "203.0.113.9"
    # A readable state without compute leaves the address unset, and the
    # cleanup step skips itself.
    state(None)
    empty = await workflow.start_step(
        {**fixture(), **CREDENTIALS, "blue/event": "delete", "compute-prevent-destroy": False}, env={})
    assert empty["blue/exit"] == 0
    assert empty.get("ip") is None


def test_graph_orders_the_stack():
    create = {"blue/event": "create"}
    assert workflow.wire_fn("agent-network/start", create)[1:] == ("agent-network/infrastructure",)
    assert workflow.wire_fn("agent-network/infrastructure", create)[1:] == ("agent-network/ssh-config",)
    assert workflow.wire_fn("agent-network/ssh-config", create)[1:] == ("agent-network/dns",)
    # DNS before convergence: Traefik asks Let's Encrypt for a certificate as
    # soon as it starts, and TLS-ALPN-01 only succeeds once the names resolve —
    # the wildcard included, for the reverse proxy's endpoint certificates.
    assert workflow.wire_fn("agent-network/dns", create)[1:] == ("agent-network/ansible",)
    assert workflow.wire_fn("agent-network/ansible", create)[1:] == ("agent-network/acceptance",)


def test_delete_removes_the_key_after_the_compute_destroy():
    # The ordering is what makes "key present ⇔ deployment exists" hold: a
    # failed destroy never reaches the cleanup step, and correctly leaves the
    # key that is still the only credential to whatever survived.
    delete = {"blue/event": "delete"}
    assert workflow.wire_fn("agent-network/start", delete)[1:] == ("agent-network/ansible",)
    assert workflow.wire_fn("agent-network/infrastructure", delete)[1:] == ("agent-network/ssh-cleanup",)
    assert workflow.wire_fn("agent-network/ssh-cleanup", delete)[1:] == ()


def test_backend_addresses_key_state_by_profile_and_tool():
    dir = workflow.tools.tool_dir(fixture(), workflow.tools.infrastructure_tool)
    assert dir.endswith(".colors/agent-network-fixture/agent-network-infrastructure")
