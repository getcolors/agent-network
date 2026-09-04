"""The graph, the port of io.github.getcolors.agent-network.workflow."""

from __future__ import annotations

import os

from blue import dry_run, progress, tofu
from blue.cli import par_name, read_pars
from blue.lifecycle import preflight
from blue.workflow import advice_add, failed, workflow

from . import ssh, ssh_config, tools, validate

DEFAULTS = {"provider-compute": validate.default_compute_provider,
            "provider-dns": "cloudflare",
            "provider-backend": "local", "compute-prevent-destroy": True,
            "workdir": ".colors"}


async def state_output(opts: dict) -> dict | None:
    """Compute params recorded in the infrastructure state; None when the
    state holds none. An unreadable backend raises — `read_state` is where
    the two are told apart, because create and delete treat them
    differently."""
    outputs = await tofu.outputs(tools.tool_dir(opts, tools.infrastructure_tool),
                                 tools.backend_credential_env(opts))
    return (outputs or {}).get("params")


async def read_state(opts: dict, reader=None) -> dict:
    """One read of the compute state per run, shaped so a caller can tell
    nothing recorded from nothing readable: `{"params": m}` where `m` may be
    None, or `{"error": message}`. Needs backend credentials only. The reader
    defaults to `state_output` at call time so tests can replace it on the
    module."""
    try:
        return {"params": await (reader or state_output)(opts)}
    except Exception as e:  # noqa: BLE001 — every failure means "unreadable"
        return {"error": str(e)}


def lifecycle_event(context: dict) -> bool:
    """A real create or delete: the two events that touch a provider."""
    return bool(context["real"] and context["event"] in ("create", "delete"))


def provider_validator(opts: dict, event: str | None, state: dict) -> list[str]:
    """Compute Provider Standard §4 before the credentials. The recorded
    provider is compared with the selected one first, so a mistaken provider
    edit reports the actionable error — put it back and delete — rather than
    a missing token for the provider that was just selected. On a create an
    unreadable backend counts as no state (a fresh clone has none) and the
    credentials are checked as usual; on a delete `adopt_state` refuses it
    after validation."""
    mismatch = validate.provider_state_errors(opts, state.get("params"))
    return mismatch if mismatch else validate.secret_errors(opts, event)


def adopt_state(opts: dict, state: dict) -> dict:
    """A real delete runs the ansible cleanup before the infrastructure step,
    so the instance address must come out of the existing state here. A
    readable state without compute params leaves `ip` unset and the cleanup
    step skips itself; an unreadable backend fails loudly — swallowing it is
    how a live teardown ends up converging against 192.0.2.10."""
    if state.get("error") is not None:
        return {**opts, "blue/exit": 1,
                "blue/err": ("could not read the infrastructure state for the "
                             f"delete cleanup: {state['error']}\n"
                             "fix the backend credentials and retry; a delete that "
                             "cannot see its state has nothing to address")}
    return {**ssh.with_machine_key(opts), **(state.get("params") or {}), "blue/exit": 0}


async def start_step(original: dict, env: dict | None = None) -> dict:
    # The state is read once, up front, on the same defaulted and overlaid
    # opts the validators see — the overlay is what carries the backend
    # credentials — and only for the two events that touch a provider. The
    # validator and the after-validate share the one read.
    environment = dict(os.environ if env is None else env)
    overlaid = read_pars({**DEFAULTS, **original}, environment)
    context = {"event": overlaid.get("blue/event"), "real": not overlaid.get("blue/dry-run")}
    state = await read_state(overlaid) if lifecycle_event(context) else {}

    # The machine key's create matrix and the provider preflight run before
    # any template is rendered: an unowned key on disk or at the provider
    # stops the run while stopping is still free. Delete fills the same
    # template values — a destroy renders before it destroys — and adopts the
    # recorded address, but checks no key, because its key cleanup runs after
    # the compute destroy.
    async def after(opts, _env, ctx):
        real, event = ctx["real"], ctx["event"]
        if real and event == "delete":
            return adopt_state(opts, state)
        if real and event == "create":
            async def recorded(_opts):
                return state.get("params")
            opts = await ssh.ensure_key(opts, recorded)
            if failed(opts):
                return opts
            opts = ssh.preflight(ssh.with_machine_key(opts))
            if failed(opts):
                return opts
            opts = ssh_config.preflight(opts)
            if failed(opts):
                return opts
            return {**opts, "blue/exit": 0}
        return {**ssh.with_machine_key(opts), "blue/exit": 0}

    return await preflight(
        original, defaults=DEFAULTS, overlay=read_pars, env=environment,
        validators=[
            lambda _o, e, _c: validate.env_errors(e),
            lambda o, _e, _c: validate.state_errors(o),
            lambda o, _e, c: (provider_validator(o, c["event"], state)
                              if lifecycle_event(c) else []),
            lambda o, _e, c: ([f"compute destruction is protected; set "
                               f"{par_name('compute-prevent-destroy')}=false to delete"]
                              if c["real"] and c["event"] == "delete"
                              and o.get("compute-prevent-destroy") else []),
        ],
        after_validate=after)


def wire_fn(step: str, run_opts: dict):
    if run_opts.get("blue/event") == "delete":
        return {
            "agent-network/start": (start_step, "agent-network/ansible"),
            "agent-network/ansible": (tools.ansible_step, "agent-network/dns"),
            # The `~/.ssh/config` block goes before the destroy, the opposite
            # of the keypair below. A block that outlives its host is stale but
            # harmless; a key that predeceases its host locks the operator out
            # of a machine that still exists. Both orders are deliberate; see
            # standards/ssh-config.md.
            "agent-network/dns": (tools.dns_step, "agent-network/ssh-config"),
            "agent-network/ssh-config": (tools.ansible_local_step, "agent-network/infrastructure"),
            "agent-network/infrastructure": (tools.infrastructure_step, "agent-network/ssh-cleanup"),
            "agent-network/ssh-cleanup": (ssh.cleanup_step,),
        }.get(step)
    return {
        "agent-network/start": (start_step, "agent-network/infrastructure"),
        # After compute, which is where the address first exists, and before
        # the stage that converges the machine.
        "agent-network/infrastructure": (tools.infrastructure_step, "agent-network/ssh-config"),
        "agent-network/ssh-config": (tools.ansible_local_step, "agent-network/dns"),
        # DNS before convergence: Traefik asks Let's Encrypt for a certificate
        # the moment it starts, and TLS-ALPN-01 only succeeds once the names
        # resolve to this host — the wildcard included, because the reverse
        # proxy issues its own certificates for generated endpoint hostnames
        # the same way. The record existing is necessary but not sufficient —
        # the playbook additionally waits for public resolvers to carry it
        # before starting anything.
        "agent-network/dns": (tools.dns_step, "agent-network/ansible"),
        "agent-network/ansible": (tools.ansible_step, "agent-network/acceptance"),
        "agent-network/acceptance": (tools.acceptance_step,),
    }.get(step)


def backend_advice(tool: str):
    return tofu.conventional_backend_advice(
        dir=lambda o, tool=tool: tools.tool_dir(o, tool),
        key=lambda o, tool=tool: f"{o.get('profile') or ''}/{tool}.tfstate")


side_effecting = ["agent-network/infrastructure", "agent-network/dns",
                  "agent-network/ssh-config", "agent-network/ansible",
                  "agent-network/acceptance", "agent-network/ssh-cleanup"]


def create_workflow():
    wf = workflow(start="agent-network/start", wire_fn=wire_fn)
    wf = advice_add(wf, "agent-network/infrastructure", "before",
                    "agent-network.workflow/backend",
                    backend_advice(tools.infrastructure_tool))
    wf = advice_add(wf, "agent-network/dns", "before",
                    "agent-network.workflow/backend",
                    backend_advice(tools.dns_tool))
    return dry_run.advise(progress.advise(wf), side_effecting)


agent_network_workflow = create_workflow()
