"""The graph, the port of io.github.getcolors.agent-network.workflow."""

from __future__ import annotations

import os

from blue import dry_run, progress, tofu
from blue.cli import par_name, read_pars
from blue.lifecycle import preflight
from blue.workflow import advice_add, failed, workflow
from package_once_blue import compute as once_compute

from . import ssh, ssh_config, tools, validate

DEFAULTS = {"provider-compute": validate.default_compute_provider,
            "provider-dns": "cloudflare",
            "provider-backend": "local", "compute-prevent-destroy": True,
            "workdir": ".colors"}


async def state_output(opts: dict) -> dict | None:
    """Compute params recorded in the infrastructure state; None when the
    state holds none. An unreadable backend raises the SDK's `StepError`,
    which `once_compute.read_state` turns into `{"error": message}` — create
    and delete treat the two differently. Kept local, and looked up on this
    module at call time, so tests can replace it."""
    outputs = await tofu.outputs(tools.tool_dir(opts, tools.infrastructure_tool),
                                 tools.backend_credential_env(opts))
    return (outputs or {}).get("params")


async def start_step(original: dict, env: dict | None = None) -> dict:
    # The state is read once, up front, on the same defaulted and overlaid
    # opts the validators see — the overlay is what carries the backend
    # credentials — and only for the two events that touch a provider. The
    # validator and the after-validate share the one read.
    environment = dict(os.environ if env is None else env)
    overlaid = read_pars({**DEFAULTS, **original}, environment)
    context = {"event": overlaid.get("blue/event"), "real": not overlaid.get("blue/dry-run")}
    state = (await once_compute.read_state(overlaid, state_output)
             if once_compute.lifecycle_event(context) else {})

    # The machine key's create matrix and the provider preflight run before
    # any template is rendered: an unowned key on disk or at the provider
    # stops the run while stopping is still free. Delete fills the same
    # template values — a destroy renders before it destroys — and adopts the
    # recorded address, but checks no key, because its key cleanup runs after
    # the compute destroy.
    async def after(opts, _env, ctx):
        real, event = ctx["real"], ctx["event"]
        if real and event == "delete":
            return once_compute.adopt_state(opts, "delete", state)
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
            # Standard §4 before the credentials: a recorded provider that
            # differs from the selected one reports the actionable error, not
            # a missing token for the provider that was just selected. The
            # thunk carries the event: a delete asks for no Anthropic key.
            lambda o, _e, c: (once_compute.provider_validator(
                validate.spec, o, state.get("params"),
                lambda: validate.secret_errors(o, c["event"]))
                if once_compute.lifecycle_event(c) else []),
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
