import { readPars, parName } from "red/cli";
import * as dryRun from "red/dry-run";
import { preflight, type PreflightContext } from "red/lifecycle";
import * as progress from "red/progress";
import * as tofu from "red/tofu";
import { adviceAdd, failed, workflow, type Opts, type WireDecl } from "red/workflow";
import * as ssh from "./ssh.ts";
import * as sshConfig from "./ssh-config.ts";
import * as tools from "./tools.ts";
import * as validate from "./validate.ts";

export const defaults: Opts = {
  "provider-compute": validate.defaultComputeProvider, "provider-dns": "cloudflare",
  "provider-backend": "local", "compute-prevent-destroy": true,
  workdir: ".colors",
};

export type StateReader = (opts: Opts) => Promise<Record<string, unknown> | undefined>;

// One read of the compute state per run, shaped so a caller can tell nothing
// recorded from nothing readable: `params` may be undefined, or `error` is
// set. Needs backend credentials only.
export interface StateRead { params?: Record<string, unknown>; error?: string }

// Compute params recorded in the infrastructure state; undefined when the
// state holds none. An unreadable backend throws — `readState` is where the
// two are told apart, because create and delete treat them differently.
export async function stateOutput(opts: Opts): Promise<Record<string, unknown> | undefined> {
  const outputs = await tofu.outputs(
    tools.toolDir(opts, tools.infrastructureTool),
    tools.backendCredentialEnv(opts),
  );
  const params = outputs.params;
  return params && typeof params === "object" ? params as Record<string, unknown> : undefined;
}

export async function readState(opts: Opts, reader: StateReader = stateOutput): Promise<StateRead> {
  try {
    return { params: await reader(opts) };
  } catch (error) {
    return { error: error instanceof Error ? error.message : String(error) };
  }
}

// A real create or delete: the two events that touch a provider.
export function lifecycleEvent({ event, real }: PreflightContext): boolean {
  return real && (event === "create" || event === "delete");
}

// Compute Provider Standard §4 before the credentials. The recorded provider
// is compared with the selected one first, so a mistaken provider edit reports
// the actionable error — put it back and delete — rather than a missing token
// for the provider that was just selected. On a create an unreadable backend
// counts as no state (a fresh clone has none) and the credentials are checked
// as usual; on a delete `adoptState` refuses it after validation.
export function providerValidator(opts: Opts, event: string | undefined, state: StateRead): string[] {
  const mismatch = validate.providerStateErrors(opts, state.params);
  return mismatch.length > 0 ? mismatch : validate.secretErrors(opts, event);
}

// A real delete runs the ansible cleanup before the infrastructure step, so
// the instance address must come out of the existing state here. A readable
// state without compute params leaves `ip` unset and the cleanup step skips
// itself; an unreadable backend fails loudly — swallowing it is how a live
// teardown ends up converging against 192.0.2.10.
export function adoptState(opts: Opts, state: StateRead): Opts {
  if (state.error !== undefined) {
    return { ...opts, "red/exit": 1,
      "red/err": "could not read the infrastructure state for the delete cleanup: " +
        `${state.error}\nfix the backend credentials and retry; a delete that ` +
        "cannot see its state has nothing to address" };
  }
  return { ...ssh.withMachineKey(opts), ...(state.params ?? {}), "red/exit": 0 };
}

export async function startStep(
  opts: Opts,
  env: Record<string, string | undefined> = process.env,
  reader: StateReader = stateOutput,
): Promise<Opts> {
  // The state is read once, up front, on the same defaulted and overlaid opts
  // the validators see — the overlay is what carries the backend credentials —
  // and only for the two events that touch a provider. The validator and the
  // after-validate share the one read; the reader is injectable so tests never
  // shell out to tofu.
  const overlaid = readPars({ ...defaults, ...opts }, env);
  const context: PreflightContext = {
    event: typeof overlaid["red/event"] === "string" ? overlaid["red/event"] as string : undefined,
    real: !overlaid["red/dry-run"],
  };
  const state: StateRead = lifecycleEvent(context) ? await readState(overlaid, reader) : {};
  return preflight(opts, {
    defaults,
    overlay: readPars,
    validators: [
      (_opts, environment) => validate.envErrors(environment),
      (current) => validate.stateErrors(current),
      (current, _environment, ctx) =>
        (lifecycleEvent(ctx) ? providerValidator(current, ctx.event, state) : []),
      (current, _environment, { event, real }) =>
        real && event === "delete" && current["compute-prevent-destroy"]
          ? [`compute destruction is protected; set ${parName("compute-prevent-destroy")}=false to delete`]
          : [],
    ],
    // The machine key's create matrix and the provider preflight run before
    // any template is rendered: an unowned key on disk or at the provider
    // stops the run while stopping is still free. Delete fills the same
    // template values — a destroy renders before it destroys — and adopts the
    // recorded address, but checks no key, because its key cleanup runs after
    // the compute destroy.
    afterValidate: async (current, _environment, { event, real }) => {
      if (real && event === "delete") return adoptState(current, state);
      if (real && event === "create") {
        let next = await ssh.ensureKey(current, async () => state.params);
        if (failed(next)) return next;
        next = await ssh.preflight(ssh.withMachineKey(next));
        if (!failed(next)) next = sshConfig.preflight(next);
        return failed(next) ? next : { ...next, "red/exit": 0 };
      }
      return { ...ssh.withMachineKey(current), "red/exit": 0 };
    },
  }, env);
}

export function wireFn(step: string, runOpts: Opts): WireDecl | undefined {
  if (runOpts["red/event"] === "delete") {
    const graph: Record<string, WireDecl> = {
      "agent-network/start": [startStep, "agent-network/ansible"],
      "agent-network/ansible": [tools.ansibleStep, "agent-network/dns"],
      // The `~/.ssh/config` block goes before the destroy, the opposite of the
      // keypair below. A block that outlives its host is stale but harmless; a
      // key that predeceases its host locks the operator out of a machine that
      // still exists. Both orders are deliberate; see standards/ssh-config.md.
      "agent-network/dns": [tools.dnsStep, "agent-network/ssh-config"],
      "agent-network/ssh-config": [tools.ansibleLocalStep, "agent-network/infrastructure"],
      "agent-network/infrastructure": [tools.infrastructureStep, "agent-network/ssh-cleanup"],
      "agent-network/ssh-cleanup": [ssh.cleanupStep],
    };
    return graph[step];
  }
  const graph: Record<string, WireDecl> = {
    "agent-network/start": [startStep, "agent-network/infrastructure"],
    // After compute, which is where the address first exists, and before the
    // stage that converges the machine.
    "agent-network/infrastructure": [tools.infrastructureStep, "agent-network/ssh-config"],
    "agent-network/ssh-config": [tools.ansibleLocalStep, "agent-network/dns"],
    // DNS before convergence: Traefik asks Let's Encrypt for a certificate
    // the moment it starts, and TLS-ALPN-01 only succeeds once the names
    // resolve to this host — the wildcard included, because the reverse
    // proxy issues its own certificates for generated endpoint hostnames
    // the same way. The record existing is necessary but not sufficient —
    // the playbook additionally waits for public resolvers to carry it
    // before starting anything.
    "agent-network/dns": [tools.dnsStep, "agent-network/ansible"],
    "agent-network/ansible": [tools.ansibleStep, "agent-network/acceptance"],
    "agent-network/acceptance": [tools.acceptanceStep],
  };
  return graph[step];
}

export function backendAdvice(tool: string) {
  return tofu.conventionalBackendAdvice({
    dir: (opts) => tools.toolDir(opts, tool),
    key: (opts) => `${opts.profile ?? ""}/${tool}.tfstate`,
  });
}

export const sideEffecting = [
  "agent-network/infrastructure", "agent-network/dns", "agent-network/ssh-config",
  "agent-network/ansible", "agent-network/acceptance", "agent-network/ssh-cleanup",
];

function create() {
  let wf = workflow({ start: "agent-network/start", wireFn });
  wf = adviceAdd(wf, "agent-network/infrastructure", "before", "agent-network.workflow/backend",
    backendAdvice(tools.infrastructureTool));
  wf = adviceAdd(wf, "agent-network/dns", "before", "agent-network.workflow/backend",
    backendAdvice(tools.dnsTool));
  return dryRun.advise(progress.advise(wf), sideEffecting);
}

export const agentNetworkWorkflow = create();
