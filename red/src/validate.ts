import { parName } from "red/cli";
import type { Opts } from "red/workflow";
import { compute, providers, registrableDomain } from "package-once-red";
import { onceSsh } from "./once.ts";

export const profilePar = parName("profile");

// provider-compute -> what that choice implies.
//
// `required` are the non-secret keys that provider's template interpolates,
// `secrets` the credentials it needs through COLORS_PAR_*, and `tofuEnv` the
// subset OpenTofu reads from the process environment itself. Keeping the three
// together is what stops a provider being validated against one set of keys and
// run with another — a stage exporting a credential nobody checked for, or a
// check demanding a key no template uses. The keys of this map are the
// advertised providers; a provider without a template directory and a golden
// is not advertised.
//
// Three source lists rather than the standard's two, because this package
// publishes STUN over UDP beside 22/80/443 and the firewall on every provider
// mirrors that rule.
//
// Two keys the templates read are deliberately not required. `<provider>-name`
// is an optional override of the profile (Compute Name Standard), and
// `<provider>-ssh-keys` is meaningful by its absence (SSH Keypair Standard).
// Keys of the unselected provider are accepted and ignored, so one colors.yml
// stays portable between providers.
export const computeProviders: compute.Registry = {
  digitalocean: {
    required: ["digitalocean-region", "digitalocean-size", "digitalocean-image",
               "digitalocean-ssh-sources", "digitalocean-http-sources",
               "digitalocean-stun-sources"],
    secrets: ["do-token"],
    tofuEnv: { "do-token": "DIGITALOCEAN_TOKEN" },
  },
  vultr: {
    required: ["vultr-region", "vultr-plan", "vultr-os-id",
               "vultr-ssh-sources", "vultr-http-sources", "vultr-stun-sources"],
    secrets: ["vultr-api-key"],
    tofuEnv: { "vultr-api-key": "VULTR_API_KEY" },
  },
};

// The provider a deployment created before this package recorded one in its
// compute output must be running: the only one it ever offered.
export const defaultComputeProvider = "vultr";

// How this package describes itself to ONCE's `compute`, the Compute Provider
// Standard's operations over a package-owned registry. The registry and the
// default are the data above; `sources` names the firewall lists the templates
// read — SSH must list at least one CIDR; an empty HTTP list means no public
// HTTP and an empty STUN list no public STUN, the third list being the one
// thing this package adds to the standard's two. The name rules are ONCE's.
export const spec: compute.ComputeSpec = {
  registry: computeProviders,
  default: defaultComputeProvider,
  sources: { nonEmpty: ["ssh-sources"], mayBeEmpty: ["http-sources", "stun-sources"] },
};

// Every key desired state must carry whichever provider is selected. The
// provider-scoped keys come from `computeProviders`.
//
// Two deliberate absences. `<provider>-ssh-keys` selects opt-out mode by being
// present, so requiring it would make every conforming keygen deployment
// invalid. `<provider>-name` is the Compute Name Standard's optional override:
// a fresh colors.yml that omits it is complete and names the machine after the
// profile. There is likewise no `package` key — §5 removes a key that can hold
// exactly one value.
export const required = [
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
];

export const imageKeys = [
  "agent-network-server-image", "agent-network-dashboard-image",
  "agent-network-proxy-image", "agent-network-traefik-image",
  "agent-network-agent-base-image",
];

const hostRe = /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/;
const emailRe = /^[^@\s]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/;
// An explicit tag or digest is mandatory. A bare `repository/name` means
// `:latest` by implication and would walk straight past a suffix check for
// ":latest", which is why the shape is required rather than the suffix denied.
// `tag@sha256:...` — the shape every image key here actually carries — pins
// both the human-readable version and the exact bytes.
const imagePinnedRe = /^[^\s@]+(?::[^\s:@]+@sha256:[0-9a-f]{64}|:[^\s:@]+|@sha256:[0-9a-f]{64})$/;
const cidrRe = /^(?:\d{1,3}\.){3}\d{1,3}\/\d{1,2}$/;
const versionRe = /^[0-9]+\.[0-9]+\.[0-9]+$/;
const modelIdRe = /^[A-Za-z0-9][A-Za-z0-9._:-]*$/;

export function missing(value: unknown): boolean {
  return value === null || value === undefined ||
    (typeof value === "string" && value.trim() === "");
}

// `<provider>-<suffix>`: desired state names compute keys after the provider,
// so the shared steps reach them through the selected provider rather than a
// fixed prefix. ONCE's; named here so `tools` reads the same.
export const computeKey = compute.computeKey;

// What this deployment calls its machine: `<provider>-name` when present,
// else the profile (Compute Name Standard). ONCE's; every label, including
// the firewall's, derives from this one answer and never from the raw
// override key or a second copy of the profile (§3).
export const computeName = compute.computeName;

// Whether this deployment owns its machine keypair. Delegates to ONCE, the
// standard's reference implementation, so one rule decides it everywhere.
export function keygen(opts: Opts): boolean {
  return onceSsh.keygen(opts);
}

// A fixed address inside `subnet`, derived rather than configured: a value
// that can only correctly be `<subnet>.N` is a transcription step, and
// transcription drifts.
export function subnetIp(subnet: unknown, n: number): string | undefined {
  const base = String(subnet ?? "").split("/")[0] ?? "";
  const octets = base.split(".");
  return octets.length === 4 ? [...octets.slice(0, 3), String(n)].join(".") : undefined;
}

// The attachment matrix, each address derived once. On `gateway-net` Traefik
// holds .10 — the proxy's NB_PROXY_TRUSTED_PROXIES needs it, exactly as the
// upstream installer wires TRAEFIK_IP. On `agent-net` Traefik holds .10 (the
// agent's management/signal/relay bootstrap lands here via extra_hosts), the
// reverse proxy .11 (the WireGuard leg), and the agent .20. These four are the
// whole reachable surface of the isolated network, and the DOCKER-USER allow
// list is written from them.
export const traefikIp = (opts: Opts) => subnetIp(opts["agent-network-gateway-subnet"], 10);
export const traefikAgentIp = (opts: Opts) => subnetIp(opts["agent-network-agent-subnet"], 10);
export const proxyAgentIp = (opts: Opts) => subnetIp(opts["agent-network-agent-subnet"], 11);
export const agentIp = (opts: Opts) => subnetIp(opts["agent-network-agent-subnet"], 20);

// The Cloudflare zone the host and its wildcard belong to.
export function zone(opts: Opts): string | undefined {
  return registrableDomain(opts["agent-network-host"]);
}

export type ProviderModel = Record<string, unknown>;

// The models the Anthropic provider claims, however YAML handed them over.
export function providerModels(opts: Opts): ProviderModel[] {
  const value = opts["agent-network-provider-models"];
  return Array.isArray(value) ? value.map((m) => (m ?? {}) as ProviderModel) : [];
}

export function allowedModels(opts: Opts): string[] {
  const value = opts["agent-network-allowed-models"];
  return Array.isArray(value) ? value.map(String) : [];
}

// The model every Claude Code knob is pinned to.
export function allowedModel(opts: Opts): string | undefined {
  return allowedModels(opts)[0];
}

// A model the provider claims but the guardrail does not allow — the
// guardrail-denial probe's negative case. Its existence is validated, so
// acceptance can rely on it.
export function deniedClaimedModel(opts: Opts): string | undefined {
  const allowed = new Set(allowedModels(opts));
  for (const model of providerModels(opts)) {
    const id = String(model.id);
    if (!allowed.has(id)) return id;
  }
  return undefined;
}

// Whether two /24-or-smaller IPv4 CIDRs share addresses. Deliberately simple:
// both subnets here are package-shaped (a.b.c.0/nn with nn >= 16), and a
// byte-precise comparison over that shape beats a dependency.
export function subnetOverlap(a: unknown, b: unknown): boolean {
  const parts = (cidr: unknown): [number, number] => {
    const [ip, bits] = String(cidr).split("/");
    const octets = String(ip).split(".").map(Number);
    const n = Number(bits);
    const addr = octets.reduce((acc, octet) => (acc * 256 + octet) | 0, 0);
    const mask = n === 0 ? 0 : (-1 << (32 - n)) | 0;
    return [(addr & mask) | 0, mask];
  };
  const popcount = (v: number): number => {
    let count = 0;
    for (let x = v >>> 0; x !== 0; x >>>= 1) count += x & 1;
    return count;
  };
  const [aNet, aMask] = parts(a);
  const [bNet, bMask] = parts(b);
  const mask = popcount(aMask) > popcount(bMask) ? bMask : aMask;
  return (aNet & mask) === (bNet & mask);
}

export function posNum(value: unknown): value is number {
  return typeof value === "number" && value > 0;
}

export function modelErrors(opts: Opts): string[] {
  const models = providerModels(opts);
  const allowed = allowedModels(opts);
  const claimed = new Set(models.map((model) => String(model.id)));
  const errors: string[] = [];
  if (!(Array.isArray(opts["agent-network-provider-models"]) && models.length > 0)) {
    errors.push(":agent-network-provider-models must be a non-empty list");
  }
  for (const model of models) {
    if (missing(model.id) || !modelIdRe.test(String(model.id))) {
      errors.push(":agent-network-provider-models entries must carry a model id");
    }
  }
  for (const model of models) {
    if (!(posNum(model["input-per-1k"]) && posNum(model["output-per-1k"]))) {
      errors.push(`model ${model.id ?? ""} must carry positive input-per-1k and output-per-1k prices`);
    }
  }
  if (!(Array.isArray(opts["agent-network-allowed-models"]) && allowed.length > 0)) {
    errors.push(":agent-network-allowed-models must be a non-empty list");
  }
  for (const model of allowed) {
    if (!claimed.has(model)) {
      errors.push(`:agent-network-allowed-models entry ${model} is not claimed by the provider`);
    }
  }
  // The demo's guardrail-denial probe needs a model that routing accepts and
  // the allowlist rejects. Without one, gate 3b has no negative case and the
  // guardrail is configured but never demonstrated.
  const allowedSet = new Set(allowed);
  if (models.length > 0 && allowed.length > 0 &&
      models.every((model) => allowedSet.has(String(model.id)))) {
    errors.push(":agent-network-provider-models must claim at least one model outside :agent-network-allowed-models");
  }
  return errors;
}

// A source list as desired state or an overlay string carries it. ONCE's, so
// the validator and the templates can never disagree about what an entry is.
export const cidrs = compute.cidrs;

export function envErrors(env: Record<string, string | undefined>): string[] {
  return String(env[profilePar] ?? "").length
    ? [`${profilePar} is set; profile must come from colors.yml only`]
    : [];
}

// Every problem with desired state at once: the missing keys (this package's
// and the selected provider's), the package's own checks, then the Compute
// Provider Standard's — selection, the network contract and the provider
// rules — which are ONCE's over `spec`.
export function stateErrors(opts: Opts): string[] {
  const errors: string[] = [];
  for (const key of [...required, ...compute.requiredKeys(spec, opts)]) {
    if (missing(opts[key])) errors.push(`:${key} is required`);
  }
  if (opts["provider-dns"] !== "cloudflare") {
    errors.push(":provider-dns must be cloudflare");
  }
  if (!["local", "s3", "r2"].includes(String(opts["provider-backend"]))) {
    errors.push(":provider-backend must be local, s3, or r2");
  }
  if (typeof opts["compute-prevent-destroy"] !== "boolean") {
    errors.push(":compute-prevent-destroy must be true or false");
  }
  if (!missing(opts["agent-network-host"]) &&
      !hostRe.test(String(opts["agent-network-host"]))) {
    errors.push(":agent-network-host must be a fully qualified hostname");
  }
  for (const key of ["agent-network-letsencrypt-email", "agent-network-admin-email"]) {
    const value = opts[key];
    if (!missing(value) && !emailRe.test(String(value))) {
      errors.push(`:${key} must be an email address`);
    }
  }
  for (const key of imageKeys) {
    const value = opts[key];
    if (!missing(value) && !imagePinnedRe.test(String(value))) {
      errors.push(`:${key} must carry an explicit image tag or digest`);
    }
  }
  // This package owns its templates rather than following the upstream
  // installer, so nothing tells it when a floating tag moved underneath it.
  for (const key of imageKeys) {
    const value = String(opts[key]);
    if (value.endsWith(":latest") || value.endsWith(":main") ||
        value.includes(":latest@") || value.includes(":main@")) {
      errors.push(`:${key} must not track a floating tag; pin the version`);
    }
  }
  for (const key of ["agent-network-claude-code-version",
                     "agent-network-netbird-client-version",
                     "agent-network-lego-version"]) {
    const value = opts[key];
    if (!missing(value) && !versionRe.test(String(value))) {
      errors.push(`:${key} must be an exact x.y.z version`);
    }
  }
  const stunPort = opts["agent-network-stun-port"];
  if (!(missing(stunPort) ||
        (typeof stunPort === "number" && Number.isInteger(stunPort) &&
         stunPort > 0 && stunPort < 65536))) {
    errors.push(":agent-network-stun-port must be a port number");
  }
  for (const key of ["agent-network-gateway-subnet", "agent-network-agent-subnet"]) {
    const value = opts[key];
    if (!missing(value) && !cidrRe.test(String(value))) {
      errors.push(`:${key} must be a CIDR block`);
    }
  }
  // Deterministic build-time validation only (the workstation cannot know the
  // target host's routes; converge re-checks there before creating the
  // networks). Overlapping subnets would let one compose network shadow the
  // other and silently break both the isolation boundary and the firewall
  // allow list derived from static addresses.
  if (cidrRe.test(String(opts["agent-network-gateway-subnet"])) &&
      cidrRe.test(String(opts["agent-network-agent-subnet"])) &&
      subnetOverlap(opts["agent-network-gateway-subnet"], opts["agent-network-agent-subnet"])) {
    errors.push(":agent-network-gateway-subnet and :agent-network-agent-subnet must not overlap");
  }
  if (!(missing(opts["agent-network-log-level"]) ||
        ["error", "warn", "info", "debug"].includes(String(opts["agent-network-log-level"])))) {
    errors.push(":agent-network-log-level must be error, warn, info, or debug");
  }
  // 7-90 mirrors the dashboard's own retention range; usage metering is
  // unconditional and unaffected.
  const retention = opts["agent-network-log-retention-days"];
  if (!(missing(retention) ||
        (typeof retention === "number" && Number.isInteger(retention) &&
         retention >= 7 && retention <= 90))) {
    errors.push(":agent-network-log-retention-days must be an integer between 7 and 90");
  }
  for (const key of ["agent-network-policy-budget-usd-per-day",
                     "agent-network-policy-tokens-per-day",
                     "agent-network-global-budget-usd-per-day",
                     "agent-network-global-tokens-per-day"]) {
    const value = opts[key];
    if (!missing(value) && !posNum(value)) {
      errors.push(`:${key} must be a positive number`);
    }
  }
  // The global rule is the backstop: a policy cap above it would never bind
  // and the desired state would be lying about which limit is the ceiling.
  if (posNum(opts["agent-network-policy-budget-usd-per-day"]) &&
      posNum(opts["agent-network-global-budget-usd-per-day"]) &&
      opts["agent-network-policy-budget-usd-per-day"] > opts["agent-network-global-budget-usd-per-day"]) {
    errors.push(":agent-network-policy-budget-usd-per-day must not exceed the global budget");
  }
  if (posNum(opts["agent-network-policy-tokens-per-day"]) &&
      posNum(opts["agent-network-global-tokens-per-day"]) &&
      opts["agent-network-policy-tokens-per-day"] > opts["agent-network-global-tokens-per-day"]) {
    errors.push(":agent-network-policy-tokens-per-day must not exceed the global token cap");
  }
  if ([opts["agent-network-provider-models"], opts["agent-network-allowed-models"]]
      .some((value) => !missing(value))) {
    errors.push(...modelErrors(opts));
  }
  errors.push(...compute.stateErrors(spec, opts));
  return errors;
}

export function backendSecrets(opts: Opts): string[] {
  return providers["provider-backend"]?.[String(opts["provider-backend"])]?.secrets ?? [];
}

// What talking to the providers needs, on any real event: the selected
// compute provider's credential and Cloudflare's.
export function providerSecrets(opts: Opts): string[] {
  return [...compute.secrets(spec, opts), "cloudflare-api-token"];
}

// What converging the machine needs, and therefore only a create.
//
// One entry, deliberately. Everything else this deployment holds is generated
// on the host and supplied by nobody: the relay auth secret, the datastore
// encryption key, the session cookie key, the proxy access token, the local
// admin password, the durable automation token, and the agent's one-off setup
// key. The Anthropic key is the exception because it authenticates against an
// account this host does not own; it is handed to NetBird's encrypted store at
// converge time and the agent container never sees it.
export const applicationSecrets = ["anthropic-api-key"];

// Credentials a real event needs. A delete tears down infrastructure with the
// provider credentials alone: this deployment is disposable by design, holds
// nothing worth a final archive, and demanding the Anthropic key to destroy a
// machine would just be a lock on the exit.
export function secretErrors(opts: Opts, event: string | undefined): string[] {
  const eventKeys = event === "create" ? applicationSecrets : [];
  const keys = [...new Set([...providerSecrets(opts), ...eventKeys, ...backendSecrets(opts)])];
  return keys.filter((key) => missing(opts[key]))
    .map((key) => `required credential is not set: ${parName(key)}`);
}

export function tofuEnv(opts: Opts, slot: string): Record<string, string> {
  switch (slot) {
    case "provider-compute":
      return compute.tofuEnv(spec, opts);
    case "provider-dns":
      return { "cloudflare-api-token": "CLOUDFLARE_API_TOKEN" };
    case "provider-backend":
      return providers["provider-backend"]?.[String(opts["provider-backend"])]?.tofuEnv ?? {};
    default:
      return {};
  }
}
