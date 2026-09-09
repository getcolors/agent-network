import { readPars, parName } from "red/cli";
import * as dryRun from "red/dry-run";
import { preflight } from "red/lifecycle";
import * as progress from "red/progress";
import * as tofu from "red/tofu";
import { adviceAdd, failed, workflow, type Opts, type WireDecl } from "red/workflow";
import * as compute from "./compute.ts";
import * as sshConfig from "./ssh-config.ts";
import * as tools from "./tools.ts";
import * as validate from "./validate.ts";

export const defaults: Opts = {
  "provider-compute": "vultr", "provider-dns": "cloudflare",
  "provider-backend": "r2", "compute-prevent-destroy": true,
  workdir: ".colors",
};

export async function startStep(
  opts: Opts,
  env: Record<string, string | undefined> = process.env,
): Promise<Opts> {
  return preflight(opts, {
    defaults,
    overlay: readPars,
    validators: [
      (_opts, environment) => validate.envErrors(environment),
      (current) => validate.stateErrors(current),
      (current, _environment, { event, real }) =>
        real && (event === "create" || event === "delete")
          ? validate.secretErrors(current, event)
          : [],
      (current, _environment, { event, real }) =>
        real && event === "delete" && current["compute-prevent-destroy"]
          ? [`compute destruction is protected; set ${parName("compute-prevent-destroy")}=false to delete`]
          : [],
    ],
    afterValidate: (current, _environment, {event,real}) => real && event==='create' ? sshConfig.preflight({...current,'red/exit':0}) : {...current,'red/exit':0},
  }, env);
}

export function wireFn(step: string, runOpts: Opts): WireDecl | undefined {
  if (runOpts["red/event"] === "delete") {
    const graph: Record<string, WireDecl> = {
      "agent-network/start": [startStep, "agent-network/load"], "agent-network/load": [compute.loadStep, "agent-network/ansible"],
      "agent-network/ansible": [tools.ansibleStep, "agent-network/dns"],
      // The `~/.ssh/config` block goes before the destroy, the opposite of the
      // keypair below. A block that outlives its host is stale but harmless; a
      // key that predeceases its host locks the operator out of a machine that
      // still exists. Both orders are deliberate; see standards/ssh-config.md.
      "agent-network/dns": [tools.dnsStep, "agent-network/ssh-config"],
      "agent-network/ssh-config": [tools.ansibleLocalStep, "agent-network/infrastructure"],
      "agent-network/infrastructure": [tools.infrastructureStep],
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
    // resolve to this host. The record existing is necessary but not
    // sufficient — the playbook additionally waits for public resolvers to
    // carry it before starting anything.
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
  "agent-network/ansible", "agent-network/acceptance", "agent-network/load",
];

function create() {
  let wf = workflow({ start: "agent-network/start", wireFn });
  wf = adviceAdd(wf,"agent-network/dns","before","agent-network.workflow/backend",backendAdvice(tools.dnsTool));
  return dryRun.advise(progress.advise(wf), sideEffecting);
}

export const agentNetworkWorkflow = create();
