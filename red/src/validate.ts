import { parName } from "red/cli";
import type { Opts } from "red/workflow";
import { providers, registrableDomain } from "package-once-red";
import { onceSsh } from "./once.ts";

export const profilePar = parName("profile");

interface ProviderEntry {
  required: string[];
  secrets: string[];
  tofuEnv: Record<string, string>;
}

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
export const computeProviders: Record<string, ProviderEntry> = {
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
// What each provider accepts as a machine name, checked here rather than
// discovered mid-apply. DigitalOcean droplet names are hostname-like; Vultr
// labels are free-form console text, held to a safe subset.
const nameRules: Record<string, { re: RegExp; message: string }> = {
  digitalocean: {
    re: /^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$/,
    message: "must be a hostname-like name: lowercase letters, digits, dots and hyphens, 1-63 characters",
  },
  vultr: {
    re: /^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$/,
    message: "must be a safe 1-63 character name",
  },
};

export function missing(value: unknown): boolean {
  return value === null || value === undefined ||
    (typeof value === "string" && value.trim() === "");
}

// Absent, blank or REPLACE_ME all mean 'use the profile' (Compute Name
// Standard §2: presence is the only switch).
export function placeholder(value: unknown): boolean {
  return missing(value) || String(value).trim() === "REPLACE_ME";
}

export function computeProvider(opts: Opts): ProviderEntry | undefined {
  return computeProviders[String(opts["provider-compute"])];
}

// Desired state names compute keys after the provider, so the shared steps
// reach them through the selected provider rather than a fixed prefix.
export function computeKey(opts: Opts, suffix: string): string {
  return `${opts["provider-compute"]}-${suffix}`;
}

// What this deployment calls its machine. The one function that answers it —
// every label, including the firewall's, derives from this and never from the
// raw override key or a second copy of the profile (§3). The override is read
// from the selected provider's `<provider>-name` alone.
export function computeName(opts: Opts): string {
  const override = opts[computeKey(opts, "name")];
  return placeholder(override)
    ? String(opts.profile ?? "")
    : String(override).trim();
}

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

// A source list as desired state or an overlay string carries it: a YAML
// list, or one string of comma- or space-separated entries.
export function cidrs(opts: Opts, key: string): string[] {
  const value = opts[key];
  const parts = Array.isArray(value) ? value : String(value ?? "").split(/[,\s]+/);
  return parts.map((part) => String(part).trim()).filter((part) => part.length > 0);
}

// Syntactic CIDR checks, the same in every colour and deliberately not a
// resolver: an address library that accepts a hostname would let a firewall
// source depend on DNS at apply time.
const ipv4Re = /^(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$/;
const hexGroupRe = /^[0-9A-Fa-f]{1,4}$/;

// An IPv4-embedded address (`::ffff:192.0.2.1`, `64:ff9b::192.0.2.33`) carries
// a dotted quad in last position only. It stands for two 16-bit groups, so it
// is checked as IPv4 and folded into two zero groups before the group
// arithmetic; undefined when the tail is dotted but not an IPv4 address. A
// dotted quad anywhere else falls through to the hex-group check and fails.
function foldIpv4Tail(s: string): string | undefined {
  const i = s.lastIndexOf(":");
  const tail = i >= 0 ? s.slice(i + 1) : s;
  if (!tail.includes(".")) return s;
  if (i >= 0 && ipv4Re.test(tail)) return `${s.slice(0, i + 1)}0:0`;
  return undefined;
}

function ipv6Address(raw: string): boolean {
  const s = foldIpv4Tail(raw);
  if (s === undefined) return false;
  const groups = (part: string) => (part.trim() === "" ? [] : part.split(":"));
  if (s.includes("::")) {
    const halves = s.split("::");
    if (halves.length !== 2) return false;
    const gs = halves.flatMap(groups);
    return gs.length <= 7 && gs.every((g) => hexGroupRe.test(g));
  }
  const gs = groups(s);
  return gs.length === 8 && gs.every((g) => hexGroupRe.test(g));
}

// Whether `s` is a syntactically valid IPv4 or IPv6 CIDR: an address, a
// slash, and a prefix length the address family allows.
export function cidr(s: unknown): boolean {
  const [address, prefix, ...more] = String(s).split("/");
  if (more.length > 0 || prefix === undefined || !/^\d{1,3}$/.test(prefix)) return false;
  const n = Number(prefix);
  if (ipv4Re.test(address ?? "")) return n >= 0 && n <= 32;
  if (ipv6Address(address ?? "")) return n >= 0 && n <= 128;
  return false;
}

// The network contract: the selected provider's SSH sources must name at
// least one CIDR — a machine nobody can reach is not a deployment — and every
// entry of all three lists must be one. An empty HTTP list is allowed and
// means no public HTTP; an empty STUN list means no public STUN. Refusing
// beats defaulting: a silent default-open in front of a control plane is worse
// than a validation error.
export function sourceErrors(opts: Opts): string[] {
  const sshKey = computeKey(opts, "ssh-sources");
  const httpKey = computeKey(opts, "http-sources");
  const stunKey = computeKey(opts, "stun-sources");
  const errors: string[] = [];
  if (!missing(opts[sshKey]) && cidrs(opts, sshKey).length === 0) {
    errors.push(`:${sshKey} must list at least one CIDR`);
  }
  for (const key of [sshKey, httpKey, stunKey]) {
    if (missing(opts[key])) continue;
    for (const entry of cidrs(opts, key)) {
      if (!cidr(entry)) errors.push(`:${key} entry ${JSON.stringify(entry)} is not an IPv4 or IPv6 CIDR`);
    }
  }
  return errors;
}

// Checks that hold only for the selected provider. Keys of the other provider
// are ignored, never refused. The *resolved* machine name is validated against
// the provider's rules rather than passed through unread (Compute Name
// Standard §2): an override is checked as itself, and a profile that falls
// through as the name is checked too, because a profile Vultr accepts as a
// label can be a droplet name DigitalOcean refuses mid-apply. The error names
// the key the value came from. A blank resolved value is skipped, so a missing
// profile reports `is required` alone.
export function providerErrors(opts: Opts): string[] {
  const errors: string[] = [];
  const nameKey = computeKey(opts, "name");
  const rule = nameRules[String(opts["provider-compute"])];
  const override = !placeholder(opts[nameKey]);
  const name = computeName(opts);
  const source = override ? `:${nameKey}` : `:profile (the ${opts["provider-compute"]} machine name)`;
  if (rule && name.trim() !== "" && (name.length > 63 || !rule.re.test(name))) {
    errors.push(`${source} ${rule.message}`);
  }
  switch (opts["provider-compute"]) {
    case "vultr": {
      const osId = opts["vultr-os-id"];
      if (!(missing(osId) || (typeof osId === "number" && Number.isInteger(osId)))) {
        errors.push(":vultr-os-id must be Vultr's numeric operating-system id");
      }
      break;
    }
    case "digitalocean":
      // No VPC is created: the region's default is discovered at plan time,
      // and a pinned UUID or a CIDR would make this package start owning one.
      if ("digitalocean-vpc-uuid" in opts) {
        errors.push(":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime");
      }
      if ("digitalocean-vpc-cidr" in opts) {
        errors.push(":digitalocean-vpc-cidr must be absent; this package must not create a VPC");
      }
      break;
    default:
      break;
  }
  return errors;
}

export function envErrors(env: Record<string, string | undefined>): string[] {
  return String(env[profilePar] ?? "").length
    ? [`${profilePar} is set; profile must come from colors.yml only`]
    : [];
}

export function stateErrors(opts: Opts): string[] {
  const errors: string[] = [];
  const provider = computeProvider(opts);
  for (const key of [...required, ...(provider?.required ?? [])]) {
    if (missing(opts[key])) errors.push(`:${key} is required`);
  }
  if (!provider) {
    errors.push(`:provider-compute must be one of ${Object.keys(computeProviders).sort().join(", ")}`);
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
  if (provider) errors.push(...providerErrors(opts), ...sourceErrors(opts));
  return errors;
}

// Provider switching is a rebuild, never an apply. Every provider shares one
// state key, so a changed provider-compute on a profile whose state already
// holds compute would plan a cross-provider replacement — and a delete would
// render and destroy the *selected* provider's template against the wrong
// lifecycle. `params` is the compute stage's recorded output, or undefined
// when the state holds none; its `provider` is the registry name the template
// that produced it belongs to. A recorded output without one predates this
// package recording it, which makes it the default provider's.
export function providerStateErrors(
  opts: Opts,
  params: Record<string, unknown> | undefined,
): string[] {
  if (!params) return [];
  const selected = String(opts["provider-compute"]);
  const recorded = String(params.provider ?? "");
  if (recorded.length > 0 && recorded !== selected) {
    return [`state holds a ${recorded} machine; set provider-compute back to ${recorded} and delete first`];
  }
  if (recorded.length === 0 && selected !== defaultComputeProvider) {
    return ["state holds a machine with no recorded provider, created before this " +
      `package recorded one, which makes it a ${defaultComputeProvider} machine; ` +
      `set provider-compute back to ${defaultComputeProvider} and delete first`];
  }
  return [];
}

export function backendSecrets(opts: Opts): string[] {
  return providers["provider-backend"]?.[String(opts["provider-backend"])]?.secrets ?? [];
}

// What talking to the providers needs, on any real event: the selected
// compute provider's credential and Cloudflare's.
export function providerSecrets(opts: Opts): string[] {
  return [...(computeProvider(opts)?.secrets ?? []), "cloudflare-api-token"];
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
      return computeProvider(opts)?.tofuEnv ?? {};
    case "provider-dns":
      return { "cloudflare-api-token": "CLOUDFLARE_API_TOKEN" };
    case "provider-backend":
      return providers["provider-backend"]?.[String(opts["provider-backend"])]?.tofuEnv ?? {};
    default:
      return {};
  }
}
