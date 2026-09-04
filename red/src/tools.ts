import * as ansible from "red/ansible";
import { stageDir } from "red/cli";
import { PRESERVE_JINJA_DELIMITERS, contentSpec, type Spec, type Template } from "red/scaffold";
import * as tofu from "red/tofu";
import { runtime } from "red/runtime";
import type { Opts } from "red/workflow";
import { failed } from "red/workflow";
import * as ssh from "./ssh.ts";
import * as sshConfig from "./ssh-config.ts";
import * as validate from "./validate.ts";

import ansibleLocalCfg from "../resources/tools/ansible-local/ansible.cfg" with { type: "text" };
import ansibleLocalInventory from "../resources/tools/ansible-local/inventory.ini" with { type: "text" };
import ansibleLocalMain from "../resources/tools/ansible-local/main.yml" with { type: "text" };
import ansibleCfg from "../resources/tools/ansible/ansible.cfg" with { type: "text" };
import ansibleMain from "../resources/tools/ansible/main.yml" with { type: "text" };
import ansibleCleanup from "../resources/tools/ansible/cleanup.yml" with { type: "text" };
import ansibleCompose from "../resources/tools/ansible/compose.yml" with { type: "text" };
import ansibleConfig from "../resources/tools/ansible/config.yaml" with { type: "text" };
import ansibleDashboardEnv from "../resources/tools/ansible/dashboard.env" with { type: "text" };
import ansibleProxyEnv from "../resources/tools/ansible/proxy.env" with { type: "text" };
import ansibleTraefikDynamic from "../resources/tools/ansible/traefik-dynamic.yaml" with { type: "text" };
import ansibleBootstrap from "../resources/tools/ansible/bootstrap.sh" with { type: "text" };
import ansibleAgentDockerfile from "../resources/tools/ansible/agent.Dockerfile" with { type: "text" };
import ansibleAgentEntry from "../resources/tools/ansible/agent-entry.sh" with { type: "text" };
import ansibleSmoke from "../resources/tools/ansible/smoke.sh" with { type: "text" };
import ansibleStatus from "../resources/tools/ansible/status.sh" with { type: "text" };
import ansibleFirewallSh from "../resources/tools/ansible/firewall.sh" with { type: "text" };
import ansibleFirewallService from "../resources/tools/ansible/firewall.service" with { type: "text" };
import dnsMainTf from "../resources/tools/dns/main.tf" with { type: "text" };
import infrastructureDigitaloceanTf from "../resources/tools/infrastructure/digitalocean/main.tf" with { type: "text" };
import infrastructureVultrTf from "../resources/tools/infrastructure/vultr/main.tf" with { type: "text" };

export const infrastructureTool = "agent-network-infrastructure";
export const dnsTool = "agent-network-dns";
export const ansibleTool = "agent-network-ansible";
export const ansibleLocalTool = "agent-network-ansible-local";
export const templateOpts = PRESERVE_JINJA_DELIMITERS;

export function toolDir(opts: Opts, tool: string): string {
  return stageDir(opts, tool, { defaultProfile: "agent-network" });
}

const template = (name: string, content: string): Template => ({ name, content });

// One compute template per advertised provider, keyed the way green names its
// classpath resources. A registry entry without a template here fails the
// first build, never a unit test — which is why parity renders every fixture.
const infrastructureTemplates: Record<string, string> = {
  digitalocean: infrastructureDigitaloceanTf,
  vultr: infrastructureVultrTf,
};

function spec(source: Template, target: string, data: Opts): Spec {
  return { template: source, target, data, opts: templateOpts };
}

const rawSpec = (target: string, content: string): Spec => contentSpec(target, content);

// The source lists as validate parses them, so the template and the
// validator can never disagree about what an entry is.
export const cidrs = validate.cidrs;

export function credentialEnv(opts: Opts, ...slots: string[]): Record<string, string> | undefined {
  const mapping: Record<string, string> = Object.assign(
    {},
    ...[...slots, "provider-backend"].map((slot) => validate.tofuEnv(opts, slot)),
  );
  const env: Record<string, string> = {};
  for (const [key, envVar] of Object.entries(mapping)) {
    const value = String(opts[key] ?? "");
    if (value.length > 0) env[envVar] = value;
  }
  return Object.keys(env).length > 0 ? env : undefined;
}

export const backendCredentialEnv = (opts: Opts) => credentialEnv(opts);

// What `build` and `--dry-run` render in place of a compute output: the
// documentation address, shaped like the selected provider's real `params` so
// every later stage sees the same keys either way.
export function fallbackParams(opts: Opts): Record<string, unknown> {
  return { provider: opts["provider-compute"], ip: "192.0.2.10", user: "root", sudoer: "root",
    name: validate.computeName(opts) };
}

export function outputParams(result: Opts): Record<string, unknown> | undefined {
  const params = (result["tofu/outputs"] as Record<string, unknown> | undefined)?.params;
  return params && typeof params === "object" ? params as Record<string, unknown> : undefined;
}

// Refuse to hand 192.0.2.10 to Ansible. That is the documentation address the
// credential-free build and dry-run paths render with; on a real converge a
// missing compute output must fail loudly rather than quietly point the whole
// playbook — and the DNS records — at TEST-NET.
export function resolvedCompute(
  result: Opts,
  fallback: Record<string, unknown>,
  outputs: Record<string, unknown> | undefined,
): Opts {
  if (outputs?.ip) return { ...result, ...fallback, ...outputs };
  return { ...result, "red/exit": 1,
    "red/err": "compute produced no ip output; refusing to converge against the documentation address" };
}

// ---------------------------------------------------------------- compute

// Template values for the compute stage. The name and the three source lists
// are resolved here once, so a template interpolates values and never branches
// on which provider it belongs to.
export function infrastructureData(opts: Opts): Opts {
  return {
    ...opts,
    "ssh-keygen": validate.keygen(opts),
    "compute-name": validate.computeName(opts),
    "ssh-sources-hcl": tofu.hclList(cidrs(opts, validate.computeKey(opts, "ssh-sources"))),
    "http-sources-hcl": tofu.hclList(cidrs(opts, validate.computeKey(opts, "http-sources"))),
    "stun-sources-hcl": tofu.hclList(cidrs(opts, validate.computeKey(opts, "stun-sources"))),
  };
}

// Providers are selected by template directory, `infrastructure/<provider>/`,
// not by conditionals inside one file; the rendered target is the same
// `main.tf` whichever directory it came from.
export function infrastructureTemplate(opts: Opts): Template {
  const provider = String(opts["provider-compute"]);
  const content = infrastructureTemplates[provider];
  if (content === undefined) throw new Error(`template not found: infrastructure/${provider}/main.tf`);
  return template(`infrastructure/${provider}/main.tf`, content);
}

export async function infrastructureStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, infrastructureTool);
  const specs = [spec(infrastructureTemplate(opts), `${dir}/main.tf`, infrastructureData(opts))];
  const result = await tofu.tofuWithSpec(opts, specs,
    { dir, env: credentialEnv(opts, "provider-compute") });
  if (failed(result)) return result;
  if (opts["red/event"] === "build") return { ...result, ...fallbackParams(opts) };
  if (opts["red/event"] === "delete") return result;
  return resolvedCompute(result, fallbackParams(opts), outputParams(result));
}

// -------------------------------------------------------------------- dns

// The base record and its wildcard, both unproxied.
//
// Unproxied because Cloudflare's proxy is an HTTP proxy: UDP STUN on 3478 does
// not survive it, and both certificate paths — Traefik's TLS-ALPN-01 for the
// base name and the reverse proxy's own ACME for generated endpoint hostnames —
// terminate at the proxy instead of on this host, which breaks issuance.
//
// The wildcard is not convenience but contract: the agent-network endpoint is
// a hostname management mints one label beneath the base domain when the
// account bootstraps, and nothing knows that label before it exists. A record
// per endpoint would put a converge-time fact into desired state.
export function dnsJson(opts: Opts): string {
  return tofu.constructsJson([
    tofu.construct("resource", "cloudflare_dns_record", "agent_network", {
      zone_id: "${data.cloudflare_zone.zone.id}",
      name: opts["agent-network-host"], content: opts.ip, type: "A",
      proxied: false, ttl: 60,
    }),
    tofu.construct("resource", "cloudflare_dns_record", "agent_network_wildcard", {
      zone_id: "${data.cloudflare_zone.zone.id}",
      name: `*.${opts["agent-network-host"]}`, content: opts.ip,
      type: "A", proxied: false, ttl: 60,
    }),
  ]);
}

export async function dnsStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, dnsTool);
  const data: Opts = {
    ...opts,
    ip: opts.ip ?? fallbackParams(opts).ip,
    "agent-network-zone": validate.zone(opts),
  };
  const specs = [
    spec(template("dns/main.tf", dnsMainTf), `${dir}/main.tf`, data),
    rawSpec(`${dir}/record.tf.json`, dnsJson(data)),
  ];
  return tofu.tofuWithSpec(opts, specs, { dir, env: credentialEnv(opts, "provider-dns") });
}

// ---------------------------------------------------------- ansible (local)

// Only what a `build` genuinely knows. The address, the user and the alias are
// run-time facts and reach the play as extra-vars instead, so the rendered
// playbook carries no IP and is identical on every workstation (SSH Config
// Standard §6).
export function ansibleLocalData(opts: Opts): Opts {
  return {
    ...opts,
    "ssh-keygen": validate.keygen(opts),
    "ssh-config-identity-file": sshConfig.identityFile(opts),
  };
}

export function ansibleLocalSpecs(opts: Opts): Spec[] {
  const dir = toolDir(opts, ansibleLocalTool);
  const data = ansibleLocalData(opts);
  return [
    spec(template("ansible-local/ansible.cfg", ansibleLocalCfg), `${dir}/ansible.cfg`, data),
    spec(template("ansible-local/inventory.ini", ansibleLocalInventory), `${dir}/inventory.ini`, data),
    spec(template("ansible-local/main.yml", ansibleLocalMain), `${dir}/main.yml`, data),
  ];
}

// Write or remove the `~/.ssh/config` block. The same playbook serves both
// events; `block_state` is what distinguishes them.
export async function ansibleLocalStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, ansibleLocalTool);
  const isDelete = opts["red/event"] === "delete";
  return ansible.ansibleWithSpec(opts, {
    dir,
    inventory: "inventory.ini",
    playbooks: { create: "main.yml", delete: "main.yml" },
    extraVars: {
      host_alias: sshConfig.hostAlias(opts),
      ip: opts.ip ?? fallbackParams(opts).ip,
      user: opts.user ?? "root",
      block_state: isDelete ? "absent" : "present",
    },
  }, ansibleLocalSpecs(opts));
}

// ---------------------------------------------------------------- ansible

// Java's Double.toString, which is what Cheshire renders floats through and
// therefore what green's committed desired.json carries (0.0001 is "1.0E-4").
// Integral numbers print as longs. JS's shortest-round-trip digits are the
// same digits Java chooses; only the layout differs.
function javaNumber(value: number): string {
  if (Number.isInteger(value)) return String(value);
  const negative = value < 0;
  const [mantissa, exponentPart] = Math.abs(value).toExponential().split("e");
  const exponent = Number(exponentPart);
  const digits = mantissa!.replace(".", "");
  let body: string;
  if (exponent >= -3 && exponent < 7) {
    if (exponent >= 0) {
      const intPart = digits.padEnd(exponent + 1, "0").slice(0, exponent + 1);
      const fracPart = digits.slice(exponent + 1);
      body = `${intPart}.${fracPart.length > 0 ? fracPart : "0"}`;
    } else {
      body = `0.${"0".repeat(-exponent - 1)}${digits}`;
    }
  } else {
    const rest = digits.slice(1);
    body = `${digits[0]}.${rest.length > 0 ? rest : "0"}E${exponent}`;
  }
  return negative ? `-${body}` : body;
}

// Cheshire's pretty printer, byte for byte: spaces around colons, arrays
// inline, nested objects newline-indented, floats in Java notation.
function pretty(value: unknown, indent = 0): string {
  if (Array.isArray(value)) {
    if (value.length === 0) return "[ ]";
    return `[ ${value.map((item) => pretty(item, indent)).join(", ")} ]`;
  }
  if (value !== null && typeof value === "object") {
    const entries = Object.entries(value);
    if (entries.length === 0) return "{ }";
    const pad = " ".repeat(indent + 2);
    return `{\n${entries
      .map(([key, nested]) => `${pad}${JSON.stringify(key)} : ${pretty(nested, indent + 2)}`)
      .join(",\n")}\n${" ".repeat(indent)}}`;
  }
  if (typeof value === "number") return javaNumber(value);
  return JSON.stringify(value ?? null);
}

export function inventory(opts: Opts): string {
  return pretty({
    all: {
      children: {
        "agent-network": {
          hosts: {
            [String(opts.profile)]: {
              ansible_host: opts.ip ?? "192.0.2.10",
              ansible_user: "root",
            },
          },
        },
      },
    },
  });
}

// The control plane's desired state, one JSON document the host bootstrap
// reconciles against. Everything in it is non-secret — the Anthropic key
// reaches the bootstrap as an environment variable resolved at play time and
// never lands in a rendered file.
export function desiredJson(opts: Opts): string {
  return pretty({
    host: opts["agent-network-host"],
    admin_email: opts["agent-network-admin-email"],
    admin_name: opts["agent-network-admin-name"],
    provider: {
      // The catalog id, from GET /api/agent-network/catalog/providers on the
      // pinned release — "anthropic" alone is a 422.
      provider_id: "anthropic_api",
      name: "Anthropic",
      upstream_url: "https://api.anthropic.com",
      models: validate.providerModels(opts).map((model) => ({
        id: String(model.id),
        input_per_1k: model["input-per-1k"],
        output_per_1k: model["output-per-1k"],
        ...(model["cache-read-per-1k"] != null
          ? { cache_read_per_1k: model["cache-read-per-1k"] } : {}),
        ...(model["cache-creation-per-1k"] != null
          ? { cache_creation_per_1k: model["cache-creation-per-1k"] } : {}),
      })),
    },
    allowed_models: validate.allowedModels(opts),
    policy: {
      budget_usd_per_day: opts["agent-network-policy-budget-usd-per-day"],
      tokens_per_day: opts["agent-network-policy-tokens-per-day"],
    },
    global: {
      budget_usd_per_day: opts["agent-network-global-budget-usd-per-day"],
      tokens_per_day: opts["agent-network-global-tokens-per-day"],
    },
    log_retention_days: opts["agent-network-log-retention-days"],
  });
}

// Template values for the Ansible stage.
//
// Deliberately carries no operator secret. The Anthropic key reaches the host
// as an Ansible `lookup('env', ...)` expression written literally into
// main.yml, where `PRESERVE_JINJA_DELIMITERS` passes it through untouched —
// routing it through this map instead would let the renderer HTML-escape the
// quotes and hand Ansible `&#39;`. The secret therefore exists only in the
// process that needs it: not in `.colors/`, not in a golden, not in this map.
export function ansibleData(opts: Opts): Opts {
  return {
    ...opts,
    ip: opts.ip ?? "192.0.2.10",
    "traefik-ip": validate.traefikIp(opts),
    "traefik-agent-ip": validate.traefikAgentIp(opts),
    "proxy-agent-ip": validate.proxyAgentIp(opts),
    "agent-ip": validate.agentIp(opts),
    "allowed-model": validate.allowedModel(opts),
    "denied-claimed-model": validate.deniedClaimedModel(opts),
    "ssh-keygen": validate.keygen(opts),
  };
}

export function ansibleSpecs(opts: Opts): Spec[] {
  const dir = toolDir(opts, ansibleTool);
  const data = ansibleData(opts);
  const files: Array<[string, string]> = [
    ["ansible.cfg", ansibleCfg],
    ["main.yml", ansibleMain],
    ["cleanup.yml", ansibleCleanup],
    ["compose.yml", ansibleCompose],
    ["config.yaml", ansibleConfig],
    ["dashboard.env", ansibleDashboardEnv],
    ["proxy.env", ansibleProxyEnv],
    ["traefik-dynamic.yaml", ansibleTraefikDynamic],
    ["bootstrap.sh", ansibleBootstrap],
    ["agent.Dockerfile", ansibleAgentDockerfile],
    ["agent-entry.sh", ansibleAgentEntry],
    ["smoke.sh", ansibleSmoke],
    ["status.sh", ansibleStatus],
    ["firewall.sh", ansibleFirewallSh],
    ["firewall.service", ansibleFirewallService],
  ];
  return [
    ...files.map(([name, content]) =>
      spec(template(`ansible/${name}`, content), `${dir}/${name}`, data)),
    rawSpec(`${dir}/desired.json`, desiredJson(data)),
    rawSpec(`${dir}/inventory.json`, inventory(data)),
  ];
}

export async function ansibleStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, ansibleTool);
  if (opts["red/event"] === "delete" && !opts.ip) {
    // No compute in state: there is no host to stop, and the cleanup play
    // would only fail against the placeholder address.
    return { ...opts, "red/exit": 0 };
  }
  return ansible.ansibleWithSpec(opts, {
    dir,
    inventory: "inventory.json",
    playbooks: { create: "main.yml", delete: "cleanup.yml" },
    hostKeyChecking: false,
  }, ansibleSpecs(opts));
}

// ------------------------------------------------------------- acceptance

async function run(args: string[]) {
  return runtime.exec(args, { timeoutMs: 20000 });
}

async function out(args: string[]): Promise<string> {
  return String((await run(args)).out ?? "").trim();
}

// True once `args` exits zero, retrying every five seconds.
export async function waitFor(args: string[], attempts: number): Promise<boolean> {
  for (let remaining = attempts; ; remaining -= 1) {
    const result = await run(args);
    if (result.exit === 0) return true;
    if (remaining <= 0) return false;
    await Bun.sleep(5000);
  }
}

// Why the certificate for `host` is not acceptable, or undefined when it is.
//
// Traefik answers 443 with a self-signed default certificate when ACME has
// failed, so a reachable HTTPS endpoint proves nothing on its own. Three
// separate facts are checked: the chain validates against the system trust
// store (`curl` without `-k` fails otherwise), the certificate names this host,
// and it is not about to expire.
export async function certError(host: string): Promise<string | undefined> {
  const sClient = `echo | openssl s_client -servername ${host} -connect ${host}:443 2>/dev/null`;
  if ((await run(["curl", "-fsS", "-o", "/dev/null", `https://${host}/`])).exit !== 0) {
    return `the certificate for ${host} is not trusted by the system store; Traefik is ` +
      "probably serving its self-signed default because ACME failed";
  }
  if (!(await out(["sh", "-c", `${sClient} | openssl x509 -noout -ext subjectAltName`])).includes(host)) {
    return `the certificate served for ${host} does not name it`;
  }
  if ((await run(["sh", "-c", `${sClient} | openssl x509 -noout -checkend 604800`])).exit !== 0) {
    return `the certificate for ${host} expires within seven days and has not renewed`;
  }
  return undefined;
}

// Whether a TCP port refuses a connection from out here. `bind to loopback`
// regresses silently while every positive check still passes, so absence is
// asserted rather than assumed.
export async function closed(host: unknown, port: number): Promise<boolean> {
  const probe = `timeout 5 bash -c '</dev/tcp/${host}/${port}' 2>/dev/null`;
  return (await run(["sh", "-c", probe])).exit !== 0;
}

// One command on the deployment host, over the machine key.
export async function sshOut(opts: Opts, command: string): Promise<string> {
  const args = [
    "ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new",
    ...ssh.identityArgs(opts),
    `root@${opts.ip}`, command,
  ];
  return out(args);
}

// The external negative probe must run from outside the overlay, and this
// workstation is where it runs. A NetBird interface here would mean the probe
// can silently succeed through the tunnel and prove nothing, so its absence is
// checked rather than assumed.
export async function localOverlayError(): Promise<string | undefined> {
  const links = await out(["sh", "-c", "ip -o link show 2>/dev/null | awk -F': ' '{print $2}'"]);
  if (!/^(wt|netbird)/m.test(links)) return undefined;
  const found = links.match(/^(?:wt|netbird)[^\s@]*/m)?.[0];
  return `this workstation carries a NetBird/WireGuard interface (${found}); ` +
    "the external tunnel-only probe would not be external. " +
    "Disconnect the local NetBird client and re-run create.";
}

// Public health checks after a real create.
//
// What runs here is what the internet can see: the dashboard, its certificate,
// its substituted configuration, the ports that must refuse, and — the claim
// this demo exists to make — that the generated agent-network endpoint denies
// a caller who is not on the overlay. The in-tunnel proofs (isolation, the
// keyless call, the denial reasons, attribution) run inside the playbook as
// `agent-network-smoke`, where the automation credential lives.
export async function acceptanceStep(opts: Opts): Promise<Opts> {
  if (opts["red/event"] !== "create") return { ...opts, "red/exit": 0 };
  const host = String(opts["agent-network-host"]);
  const ip = opts.ip;
  if (!(await waitFor(["curl", "-fsS", "-o", "/dev/null", `https://${host}/`], 60))) {
    return { ...opts, "red/exit": 1,
      "red/err": "the dashboard did not become reachable over HTTPS" };
  }
  const certErrors = [await certError(host)]
    .filter((error): error is string => error !== undefined);
  // The dashboard substitutes its configuration into the built assets at
  // container start, and the script that does it exits non-zero on a missing
  // variable while supervisord carries on. nginx then serves the placeholders
  // verbatim and every request for `/` still returns 200 — so the page has to
  // be read, not merely fetched. This shipped once in the sibling package.
  const page = await out(["curl", "-fsS", `https://${host}/`]);
  const chunks = [...new Set(page.match(/\/_next\/static\/chunks\/[A-Za-z0-9_.\-]+\.js/g) ?? [])]
    .slice(0, 6);
  let unsubstituted: string | undefined;
  for (const url of chunks) {
    if ((await out(["curl", "-fsS", `https://${host}${url}`])).includes("$NETBIRD_")) {
      unsubstituted = url;
      break;
    }
  }
  // Ports that must not answer from outside: the server's metrics and
  // healthcheck listeners, the proxy's direct 8443, and the proxy's WireGuard
  // 51820 (the only peer is on the internal Docker network; nothing external
  // enrolls).
  const open: number[] = [];
  for (const port of [9090, 9000, 8443]) {
    if (!(await closed(ip, port))) open.push(port);
  }
  const overlayErr = await localOverlayError();
  const endpoint = await sshOut(opts, "cat /etc/agent-network/state/endpoint 2>/dev/null");
  if (certErrors.length > 0) {
    return { ...opts, "red/exit": 1, "red/err": certErrors.join("; ") };
  }
  if (unsubstituted) {
    return { ...opts, "red/exit": 1,
      "red/err": `the dashboard is serving unsubstituted configuration in ` +
        `${unsubstituted}; init_react_envs failed at container start ` +
        "(a missing variable makes it exit 1 while nginx keeps serving)" };
  }
  if (open.length > 0) {
    return { ...opts, "red/exit": 1,
      "red/err": `ports that must not be public answered: ${open.join(", ")}` };
  }
  if (overlayErr !== undefined) {
    return { ...opts, "red/exit": 1, "red/err": overlayErr };
  }
  if (endpoint.trim() === "") {
    return { ...opts, "red/exit": 1,
      "red/err": "the host records no agent-network endpoint; bootstrap did not complete" };
  }
  // The tunnel-only claim, tested rather than asserted: the same request the
  // agent makes must fail from here, where there is no tunnel. -k because the
  // endpoint's certificate may legitimately still be issuing; what is under
  // test is the deny, not the chain (gate 3 inside the tunnel already proved
  // the chain). Served means bypassed: a 200 completion OR the relayed
  // upstream 401 both prove the caller's request reached Anthropic through
  // server-side key injection without a tunnel identity. The correct outcome
  // is the proxy's own pre-identity denial (observed: a bare 403), which
  // reaches no upstream and writes no access-log entry.
  const probe = await out(["sh", "-c",
    `curl -sk --max-time 20 -w '\\nHTTPCODE:%{http_code}' ` +
      `-X POST https://${endpoint}/v1/messages ` +
      "-H 'content-type: application/json' " +
      `--data '{"model":"${validate.allowedModel(opts)}",` +
      `"max_tokens":16,"messages":[{"role":"user","content":"hi"}]}'`]);
  const code = probe.match(/HTTPCODE:(\d+)/)?.[1] ?? "000";
  // Exactly the pre-identity 403, fail-closed: a 200 or the upstream 401
  // means the caller was served through key injection, and any other status
  // (000, 404, 429, 5xx) means the probe observed something other than the
  // deny it exists to prove.
  if (code !== "403") {
    return { ...opts, "red/exit": 1,
      "red/err": `the agent-network endpoint ${endpoint}` +
        " did not answer an outside caller with the " +
        "pre-identity 403; it must be tunnel-only" };
  }
  // And the probe must have left no unattributed access-log entry:
  // pre-identity denials are dropped before logging, so any entry without a
  // caller identity means something external was served.
  const unattributed = await sshOut(opts,
    'curl -fsS -H "Authorization: Token $(cat /etc/agent-network/secrets/pat)" ' +
      `'https://${host}/api/agent-network/access-logs?page=1&page_size=100' ` +
      "| jq -r '[.data[] | select((.user_id // \"\") == \"\")] | length'");
  if (unattributed.trim() !== "0") {
    return { ...opts, "red/exit": 1,
      "red/err": "the access log holds entries with no caller identity; an external request was served" };
  }
  return { ...opts, "red/exit": 0,
    "agent-network/acceptance": {
      dashboard: "configured",
      certificate: "trusted",
      "closed-ports": "confirmed",
      endpoint,
      "tunnel-only": "confirmed",
    } };
}
