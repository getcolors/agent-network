import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import type { Opts } from "red/workflow";
import * as ssh from "../src/ssh.ts";
import * as sshConfig from "../src/ssh-config.ts";
import * as tools from "../src/tools.ts";
import * as validate from "../src/validate.ts";
import * as workflow from "../src/workflow.ts";

const fixtureFile = join(import.meta.dir, "../../test/fixtures/colors.yml");
const optoutFile = join(import.meta.dir, "../../test/fixtures/optout.yml");
const doFixtureFile = join(import.meta.dir, "../../test/fixtures/colors-digitalocean.yml");
const doOptoutFile = join(import.meta.dir, "../../test/fixtures/optout-digitalocean.yml");

function readFixture(path: string, overrides: Opts): Opts {
  const text = readFileSync(path, "utf8").replaceAll("WORKDIR", ".colors");
  return { ...(Bun.YAML.parse(text) as Opts), ...overrides };
}

const fixture = (overrides: Opts = {}) => readFixture(fixtureFile, overrides);
const optout = (overrides: Opts = {}) => readFixture(optoutFile, overrides);
const doFixture = (overrides: Opts = {}) => readFixture(doFixtureFile, overrides);
const doOptout = (overrides: Opts = {}) => readFixture(doOptoutFile, overrides);

const resource = (name: string) =>
  readFileSync(join(import.meta.dir, "../resources/tools", name), "utf8");

// ~/.ssh redirection: ONCE's ssh module and this package's ssh-config both
// read $HOME at call time, exactly so tests can point them at a fresh
// temporary home.
let savedHome: string | undefined;
let home: string;
beforeEach(() => {
  savedHome = process.env.HOME;
  home = mkdtempSync(join(tmpdir(), "agent-network-red-test"));
  process.env.HOME = home;
});
afterEach(() => {
  process.env.HOME = savedHome;
  rmSync(home, { recursive: true, force: true });
});

function write(path: string, content: string) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, content);
}

// --- desired state -----------------------------------------------------------

describe("validate", () => {
  test("all four fixtures are valid", () => {
    expect(validate.stateErrors(fixture())).toEqual([]);
    expect(validate.stateErrors(optout())).toEqual([]);
    expect(validate.stateErrors(doFixture())).toEqual([]);
    expect(validate.stateErrors(doOptout())).toEqual([]);
  });

  // --- the compute-provider registry (Compute Provider Standard)

  test("an unsupported provider names the advertised ones", () => {
    expect(validate.stateErrors(fixture({ "provider-compute": "hetzner" })))
      .toContain(":provider-compute must be one of digitalocean, vultr");
  });

  test("required keys follow the selected provider", () => {
    expect(validate.stateErrors(doFixture({ "digitalocean-size": null })))
      .toContain(":digitalocean-size is required");
    expect(validate.stateErrors(doFixture({ "digitalocean-stun-sources": null })))
      .toContain(":digitalocean-stun-sources is required");
    expect(validate.stateErrors(fixture({ "vultr-plan": null })))
      .toContain(":vultr-plan is required");
    // The other provider's keys are neither required nor refused, so one
    // colors.yml can carry both and move between providers by one edit.
    expect(validate.stateErrors(doFixture()).some((e) => e.includes("vultr"))).toBe(false);
    expect(validate.stateErrors(fixture({ "digitalocean-region": "ams3",
      "digitalocean-size": "s-1vcpu-1gb" }))).toEqual([]);
    expect(validate.stateErrors(doFixture({ "vultr-os-id": "not-checked-here" }))).toEqual([]);
  });

  test("name and machine key are never required", () => {
    for (const errors of [validate.stateErrors(fixture({ "vultr-name": null })),
                          validate.stateErrors(doFixture())]) {
      expect(errors.some((e) => e.includes("-name"))).toBe(false);
      expect(errors.some((e) => e.includes("-ssh-keys"))).toBe(false);
    }
  });

  test("vultr-os-id is checked on Vultr only", () => {
    expect(validate.stateErrors(fixture({ "vultr-os-id": "2284" })))
      .toContain(":vultr-os-id must be Vultr's numeric operating-system id");
    expect(validate.stateErrors(doFixture({ "vultr-os-id": "2284" }))).toEqual([]);
  });

  test("DigitalOcean refuses a pinned or created VPC", () => {
    const errors = validate.stateErrors(doFixture({ "digitalocean-vpc-uuid": "abc",
      "digitalocean-vpc-cidr": "10.0.0.0/16" }));
    expect(errors.some((e) => e.startsWith(":digitalocean-vpc-uuid must be absent"))).toBe(true);
    expect(errors.some((e) => e.startsWith(":digitalocean-vpc-cidr must be absent"))).toBe(true);
    // An unselected provider's keys are ignored, VPC keys included.
    expect(validate.stateErrors(fixture({ "digitalocean-vpc-uuid": "abc" }))).toEqual([]);
  });

  test("compute keys are provider-scoped", () => {
    expect(validate.computeKey(fixture(), "ssh-sources")).toBe("vultr-ssh-sources");
    expect(validate.computeKey(doFixture(), "stun-sources")).toBe("digitalocean-stun-sources");
  });

  test("the name override is read from the selected provider alone", () => {
    expect(validate.computeName(doFixture())).toBe("agent-network-digitalocean-fixture");
    expect(validate.computeName(doOptout())).toBe("agent-network-digitalocean-optout");
    expect(validate.computeName(doFixture({ "vultr-name": "custom-label" })))
      .toBe("agent-network-digitalocean-fixture");
    expect(validate.computeName(doFixture({ "digitalocean-name": "droplet-01" }))).toBe("droplet-01");
  });

  test("the name override follows the provider's rules", () => {
    // Vultr labels are console text; DigitalOcean droplet names are
    // hostnames, so an underscore that Vultr accepts fails at DigitalOcean.
    expect(validate.stateErrors(fixture({ "vultr-name": "a".repeat(64) })))
      .toContain(":vultr-name must be a safe 1-63 character name");
    expect(validate.stateErrors(fixture({ "vultr-name": "invalid_name" }))).toEqual([]);
    const err = ":digitalocean-name must be a hostname-like name: lowercase letters, digits, dots and hyphens, 1-63 characters";
    for (const bad of ["invalid_name", "Upper", "-leading", "a".repeat(64)]) {
      expect(validate.stateErrors(doFixture({ "digitalocean-name": bad }))).toContain(err);
    }
    expect(validate.stateErrors(doFixture({ "digitalocean-name": "agent.net-01" }))).toEqual([]);
  });

  test("the resolved name is validated when the profile is the name", () => {
    // With no override the profile *is* the machine name, so it is held to
    // the selected provider's rule too; the error names where the value came from.
    const err = ":profile (the digitalocean machine name) must be a hostname-like name: lowercase letters, digits, dots and hyphens, 1-63 characters";
    expect(validate.stateErrors(doFixture({ profile: "Prod_Name" }))).toContain(err);
    // Vultr accepts the same profile as a label.
    expect(validate.stateErrors(fixture({ profile: "Prod_Name" }))).toEqual([]);
    // A valid override shadows an invalid profile.
    expect(validate.stateErrors(doFixture({ profile: "Prod_Name", "digitalocean-name": "droplet-01" }))).toEqual([]);
    // An invalid override reports the override's key, not the profile's.
    const overridden = validate.stateErrors(doFixture({ profile: "Prod_Name", "digitalocean-name": "Bad_Name" }));
    expect(overridden.some((e) => e.startsWith(":digitalocean-name must be"))).toBe(true);
    expect(overridden.some((e) => e.startsWith(":profile"))).toBe(false);
    // A missing profile is `is required` alone, never a name error.
    const missing = validate.stateErrors(doFixture({ profile: null }));
    expect(missing).toContain(":profile is required");
    expect(missing.some((e) => e.includes("machine name"))).toBe(false);
  });

  // --- the network contract

  test("cidr syntax", () => {
    for (const ok of ["0.0.0.0/0", "10.0.0.0/8", "203.0.113.7/32", "::/0", "2001:db8::/32",
                      "fe80::1/128", "2001:db8:0:0:0:0:0:1/64",
                      // IPv4-embedded: a dotted quad in last position stands for two groups.
                      "::ffff:192.0.2.1/128", "64:ff9b::192.0.2.33/96", "1:2:3:4:5:6:192.0.2.1/128"]) {
      expect(validate.cidr(ok)).toBe(true);
    }
    for (const bad of ["10.0.0.0", "10.0.0.256/8", "10.0.0.0/33", "2001:db8::/129", "example.com/24",
                       "1:::2/64", "2001:db8::1::2/64", "1:2:3:4:5:6:7:8:9/64", "", "/24", "10.0.0.0/8/8",
                       // a bad quad, a short quad, too many groups, a quad not in last position
                       "::ffff:192.0.2.256/128", "::ffff:192.0.2/128", "1:2:3:4:5:6:7:192.0.2.1/128",
                       "192.0.2.1::/64", "::ffff:192.0.2.1:1/128"]) {
      expect(validate.cidr(bad)).toBe(false);
    }
  });

  test("ssh sources must not be empty; no public HTTP or STUN is fine", () => {
    expect(validate.stateErrors(fixture({ "vultr-ssh-sources": [] })))
      .toContain(":vultr-ssh-sources must list at least one CIDR");
    expect(validate.stateErrors(doFixture({ "digitalocean-ssh-sources": " , " })))
      .toContain(":digitalocean-ssh-sources must list at least one CIDR");
    expect(validate.stateErrors(fixture({ "vultr-http-sources": [] }))).toEqual([]);
    expect(validate.stateErrors(fixture({ "vultr-stun-sources": [] }))).toEqual([]);
    expect(validate.stateErrors(doFixture({ "digitalocean-http-sources": [] }))).toEqual([]);
    expect(validate.stateErrors(doFixture({ "digitalocean-stun-sources": [] }))).toEqual([]);
  });

  test("malformed sources are refused before any provider call", () => {
    expect(validate.stateErrors(fixture({ "vultr-http-sources": ["0.0.0.0/0", "10.0.0.0"] })))
      .toContain(':vultr-http-sources entry "10.0.0.0" is not an IPv4 or IPv6 CIDR');
    expect(validate.stateErrors(fixture({ "vultr-stun-sources": ["stun.example.com/32"] })))
      .toContain(':vultr-stun-sources entry "stun.example.com/32" is not an IPv4 or IPv6 CIDR');
    expect(validate.stateErrors(doFixture({ "digitalocean-ssh-sources": "office.example.com/32" })))
      .toContain(':digitalocean-ssh-sources entry "office.example.com/32" is not an IPv4 or IPv6 CIDR');
    // Only the selected provider's lists are checked.
    expect(validate.stateErrors(doFixture({ "vultr-ssh-sources": ["garbage"] }))).toEqual([]);
  });

  // --- provider switching is a rebuild

  test("provider state is compared with the selection", () => {
    expect(validate.providerStateErrors(fixture(), undefined)).toEqual([]);
    expect(validate.providerStateErrors(fixture(), { provider: "vultr", ip: "203.0.113.9" })).toEqual([]);
    expect(validate.providerStateErrors(doFixture(), { provider: "digitalocean" })).toEqual([]);
    expect(validate.providerStateErrors(fixture(), { provider: "digitalocean", ip: "203.0.113.9" }))
      .toEqual(["state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"]);
    expect(validate.providerStateErrors(doFixture(), { provider: "vultr" }))
      .toEqual(["state holds a vultr machine; set provider-compute back to vultr and delete first"]);
  });

  test("legacy state without a provider is the default provider's", () => {
    expect(validate.providerStateErrors(fixture(), { ip: "203.0.113.9" })).toEqual([]);
    const [error] = validate.providerStateErrors(doFixture(), { ip: "203.0.113.9" });
    expect(error).toContain("no recorded provider");
    expect(error).toContain("set provider-compute back to vultr and delete first");
  });

  test("the machine key and the name key are not required", () => {
    // The standard makes absence meaningful: requiring vultr-ssh-keys would
    // make every conforming keygen deployment invalid, and a fresh colors.yml
    // that omits vultr-name is complete (Compute Name Standard §1).
    const errors = validate.stateErrors(fixture());
    expect(errors.some((e) => e.includes("vultr-ssh-keys"))).toBe(false);
    expect(errors.some((e) => e.includes("vultr-name"))).toBe(false);
  });

  test("absent machine key selects keygen", () => {
    expect(validate.keygen(fixture())).toBe(true);
    expect(validate.keygen(optout())).toBe(false);
  });

  test("the machine is named after the profile; presence is the only switch", () => {
    expect(validate.computeName(fixture())).toBe("agent-network-fixture");
    for (const value of [null, "", "   ", "REPLACE_ME"]) {
      expect(validate.computeName(fixture({ "vultr-name": value }))).toBe("agent-network-fixture");
    }
    expect(validate.computeName(fixture({ "vultr-name": "custom-box" }))).toBe("custom-box");
  });

  test("the name override is validated, not passed through", () => {
    expect(validate.stateErrors(fixture({ "vultr-name": "not a valid label!" }))
      .some((e) => e.includes("vultr-name"))).toBe(true);
    expect(validate.stateErrors(fixture({ "vultr-name": "agent-box_1.a" }))).toEqual([]);
  });

  test("there is no package key", () => {
    // §5: a key that can hold exactly one value carries no information.
    expect(validate.required.includes("package")).toBe(false);
  });

  test("reports all errors at once", () => {
    const errors = validate.stateErrors(fixture({
      "agent-network-host": "bad",
      "agent-network-server-image": "floating",
      "agent-network-letsencrypt-email": "not-an-email",
      "provider-dns": "other", "provider-compute": "hetzner",
      "agent-network-log-retention-days": 0,
      "agent-network-stun-port": 70000,
      "agent-network-gateway-subnet": "nonsense",
      "agent-network-claude-code-version": "latest",
    }));
    expect(errors.length).toBeGreaterThanOrEqual(8);
    for (const part of ["host", "image", "letsencrypt-email", "provider-dns",
                        "provider-compute", "retention-days",
                        "stun-port", "gateway-subnet", "claude-code-version"]) {
      expect(errors.some((e) => e.includes(part))).toBe(true);
    }
  });

  test("the two subnets must not overlap", () => {
    // One compose network shadowing the other would break both the isolation
    // boundary and the DOCKER-USER allow list derived from static addresses.
    expect(validate.stateErrors(fixture({ "agent-network-agent-subnet": "172.30.0.0/24" }))
      .some((e) => e.includes("must not overlap"))).toBe(true);
    expect(validate.stateErrors(fixture({ "agent-network-agent-subnet": "172.30.0.0/16" }))
      .some((e) => e.includes("must not overlap"))).toBe(true);
  });

  test("addresses derive from the subnets", () => {
    expect(validate.traefikIp(fixture())).toBe("172.30.0.10");
    expect(validate.traefikAgentIp(fixture())).toBe("172.31.0.10");
    expect(validate.proxyAgentIp(fixture())).toBe("172.31.0.11");
    expect(validate.agentIp(fixture())).toBe("172.31.0.20");
    expect(validate.traefikIp(fixture({ "agent-network-gateway-subnet": "10.9.0.0/24" })))
      .toBe("10.9.0.10");
  });

  test("the guardrail needs a deniable model", () => {
    // Gate 3b demonstrates the guardrail denial, which needs a model routing
    // accepts and the allowlist rejects. Allowlisting everything the provider
    // claims would configure the guardrail and never demonstrate it.
    expect(validate.stateErrors(fixture({
      "agent-network-allowed-models":
        ["claude-haiku-4-5-20251001", "claude-sonnet-4-5-20250929"],
    })).some((e) => e.includes("outside"))).toBe(true);
  });

  test("allowed models must be claimed", () => {
    expect(validate.stateErrors(fixture({ "agent-network-allowed-models": ["claude-3-opus"] }))
      .some((e) => e.includes("not claimed"))).toBe(true);
  });

  test("the denied claimed model is derived", () => {
    expect(validate.allowedModel(fixture())).toBe("claude-haiku-4-5-20251001");
    expect(validate.deniedClaimedModel(fixture())).toBe("claude-sonnet-4-5-20250929");
  });

  test("models need prices", () => {
    expect(validate.stateErrors(fixture({
      "agent-network-provider-models": [
        { id: "claude-haiku-4-5-20251001" },
        { id: "claude-sonnet-4-5-20250929", "input-per-1k": 0.003, "output-per-1k": 0.015 },
      ],
    })).some((e) => e.includes("positive input-per-1k"))).toBe(true);
  });

  test("policy caps must not exceed the global ceiling", () => {
    // The global rule is the backstop; a policy cap above it would never bind.
    expect(validate.stateErrors(fixture({ "agent-network-policy-budget-usd-per-day": 50 }))
      .some((e) => e.includes("must not exceed"))).toBe(true);
    expect(validate.stateErrors(fixture({ "agent-network-policy-tokens-per-day": 99999999 }))
      .some((e) => e.includes("must not exceed"))).toBe(true);
  });

  test("retention mirrors the product range", () => {
    expect(validate.stateErrors(fixture({ "agent-network-log-retention-days": 5 }))
      .some((e) => e.includes("between 7 and 90"))).toBe(true);
    expect(validate.stateErrors(fixture({ "agent-network-log-retention-days": 90 }))).toEqual([]);
  });

  test("images: digests pass, floating and untagged are refused", () => {
    expect(validate.stateErrors(
      fixture({ "agent-network-traefik-image": `traefik@sha256:${"a".repeat(64)}` }))).toEqual([]);
    for (const key of validate.imageKeys) {
      expect(validate.stateErrors(fixture({ [key]: "netbirdio/netbird-server:latest" }))
        .some((e) => e.includes("floating tag"))).toBe(true);
    }
    // `latest@sha256:...` is pinned bytes under a lying label.
    expect(validate.stateErrors(fixture({
      "agent-network-server-image": `netbirdio/netbird-server:latest@sha256:${"a".repeat(64)}`,
    })).some((e) => e.includes("floating tag"))).toBe(true);
    expect(validate.stateErrors(fixture({ "agent-network-server-image": "netbirdio/netbird-server" }))
      .some((e) => e.includes("explicit image tag"))).toBe(true);
  });

  test("versions are exact", () => {
    for (const key of ["agent-network-claude-code-version",
                       "agent-network-netbird-client-version"]) {
      expect(validate.stateErrors(fixture({ [key]: "2.x" }))
        .some((e) => e.includes("exact x.y.z"))).toBe(true);
    }
  });

  test("profile overlay is refused", () => {
    expect(validate.envErrors({ COLORS_PAR_PROFILE: "other" }).length).toBe(1);
    expect(validate.envErrors({})).toEqual([]);
  });

  test("a create names every operator secret and nothing generated", () => {
    const errors = validate.secretErrors(fixture(), "create").join("\n");
    for (const name of ["COLORS_PAR_VULTR_API_KEY", "COLORS_PAR_CLOUDFLARE_API_TOKEN",
                        "COLORS_PAR_ANTHROPIC_API_KEY"]) {
      expect(errors).toContain(name);
    }
    // Generated on the host and supplied by nobody.
    for (const absent of ["RELAY", "SESSION", "ENCRYPTION_KEY", "PROXY_TOKEN",
                          "ADMIN_PASSWORD", "SETUP_KEY", "PAT"]) {
      expect(errors).not.toContain(absent);
    }
    expect(errors).not.toContain("COLORS_PAR_DO_TOKEN");
  });

  test("secrets and tofu env follow the selected provider", () => {
    const errors = validate.secretErrors(doFixture(), "create").join("\n");
    expect(errors).toContain("COLORS_PAR_DO_TOKEN");
    expect(errors).toContain("COLORS_PAR_CLOUDFLARE_API_TOKEN");
    expect(errors).toContain("COLORS_PAR_ANTHROPIC_API_KEY");
    expect(errors).not.toContain("COLORS_PAR_VULTR_API_KEY");
    expect(validate.tofuEnv(doFixture(), "provider-compute")).toEqual({ "do-token": "DIGITALOCEAN_TOKEN" });
    expect(validate.tofuEnv(fixture(), "provider-compute")).toEqual({ "vultr-api-key": "VULTR_API_KEY" });
    expect(validate.tofuEnv(fixture({ "provider-compute": "hetzner" }), "provider-compute")).toEqual({});
  });

  test("a delete does not ask for the anthropic key", () => {
    // This deployment is disposable: a delete needs the provider credentials
    // alone, and demanding the Anthropic key to destroy a machine would just
    // be a lock on the exit.
    const errors = validate.secretErrors(fixture(), "delete").join("\n");
    expect(errors).toContain("COLORS_PAR_VULTR_API_KEY");
    expect(errors).toContain("COLORS_PAR_CLOUDFLARE_API_TOKEN");
    expect(errors).not.toContain("ANTHROPIC");
  });

  test("dns zone is the registrable domain", () => {
    expect(validate.zone(fixture())).toBe("example.com");
  });
});

// --- tools -------------------------------------------------------------------

describe("tools", () => {
  test("firewall sources parse and infrastructure data carries the ssh mode", () => {
    const data = tools.infrastructureData(fixture());
    expect(tools.cidrs(data, "vultr-http-sources")).toEqual(["0.0.0.0/0"]);
    expect(tools.cidrs(data, "vultr-stun-sources")).toEqual(["0.0.0.0/0"]);
    expect(data["ssh-keygen"]).toBe(true);
    expect(tools.infrastructureData(optout())["ssh-keygen"]).toBe(false);
    expect(tools.infrastructureData(doFixture())["ssh-keygen"]).toBe(true);
    expect(tools.infrastructureData(doOptout())["ssh-keygen"]).toBe(false);
  });

  test("infrastructure data reads the selected provider's keys", () => {
    // The template interpolates one resolved name and one resolved list per
    // port, whichever provider they came from — the STUN list included.
    const data = tools.infrastructureData(doFixture({ "digitalocean-ssh-sources": ["10.0.0.0/8"],
      "digitalocean-stun-sources": ["198.51.100.0/24"], "vultr-ssh-sources": ["192.0.2.0/24"] }));
    expect(data["ssh-sources-hcl"]).toBe('["10.0.0.0/8"]');
    expect(data["stun-sources-hcl"]).toBe('["198.51.100.0/24"]');
    expect(data["compute-name"]).toBe("agent-network-digitalocean-fixture");
    expect(tools.infrastructureData(fixture())["compute-name"]).toBe("agent-network-fixture");
  });

  test("the template directory follows the provider", () => {
    expect(tools.infrastructureTemplate(fixture()).name).toBe("infrastructure/vultr/main.tf");
    expect(tools.infrastructureTemplate(doFixture()).name).toBe("infrastructure/digitalocean/main.tf");
    // A registry entry without a template would pass every unit test and
    // fail the first build.
    expect(() => tools.infrastructureTemplate(fixture({ "provider-compute": "hetzner" }))).toThrow();
  });

  test("every provider template mirrors the whole rule set", () => {
    // The firewall admits 22, 80/443 and STUN over UDP on every provider, and
    // records which provider produced the params.
    for (const provider of Object.keys(validate.computeProviders)) {
      const tf = tools.infrastructureTemplate(fixture({ "provider-compute": provider })).content;
      for (const needle of ["ssh-sources-hcl", "http-sources-hcl", "stun-sources-hcl", '"udp"',
                            "<{ agent-network-stun-port }>", `provider = "${provider}"`]) {
        expect(tf).toContain(needle);
      }
    }
  });

  test("fallback params are shaped per provider", () => {
    expect(tools.fallbackParams(fixture())).toEqual({ provider: "vultr", ip: "192.0.2.10",
      user: "root", sudoer: "root", name: "agent-network-fixture" });
    expect(tools.fallbackParams(doFixture())).toEqual({ provider: "digitalocean", ip: "192.0.2.10",
      user: "root", sudoer: "root", name: "agent-network-digitalocean-fixture" });
  });

  test("a real create refuses a missing ip output", () => {
    // 192.0.2.10 is the documentation address build renders with; a real
    // converge must never fall back to it.
    const refused = tools.resolvedCompute({}, tools.fallbackParams(fixture()), undefined);
    expect(refused["red/exit"]).toBe(1);
    expect(String(refused["red/err"])).toContain("compute produced no ip output");
    expect(tools.resolvedCompute({}, tools.fallbackParams(fixture()), { name: "x" })["red/exit"]).toBe(1);
    const ok = tools.resolvedCompute({}, tools.fallbackParams(fixture()),
      { ip: "203.0.113.9", provider: "vultr" });
    expect(ok["red/exit"]).toBeUndefined();
    expect(ok.ip).toBe("203.0.113.9");
  });

  test("every label derives from one resolved name", () => {
    // Compute Name Standard §3: the firewall asks the same function rather
    // than keeping a second copy of the profile.
    expect(tools.infrastructureData(fixture({ "vultr-name": "override-box" }))["compute-name"])
      .toBe("override-box");
  });

  test("dns creates the name and its wildcard, unproxied", () => {
    // The wildcard is contract, not convenience: the endpoint hostname is a
    // label management mints beneath the base domain at bootstrap, and
    // nothing knows it before it exists.
    const json = tools.dnsJson(fixture({ ip: "192.0.2.10" }));
    expect(json).toContain("agent-network.example.com");
    expect(json).toContain("*.agent-network.example.com");
    expect(json).toContain("192.0.2.10");
    expect(json).toContain('"proxied" : false');
    expect(json).not.toContain("true");
  });

  test("the inventory keeps one target", () => {
    const inventory = tools.inventory(fixture({ ip: "192.0.2.10" }));
    expect(inventory).toContain("192.0.2.10");
    expect(inventory).toContain("agent-network-fixture");
  });

  test("the ansible stage renders the whole stack", () => {
    const targets = tools.ansibleSpecs(fixture()).map((s) => String(s.target));
    for (const file of ["ansible.cfg", "main.yml", "cleanup.yml", "compose.yml",
                        "config.yaml", "dashboard.env", "proxy.env",
                        "traefik-dynamic.yaml", "bootstrap.sh", "agent.Dockerfile",
                        "agent-entry.sh", "smoke.sh", "status.sh", "firewall.sh",
                        "firewall.service", "desired.json", "inventory.json"]) {
      expect(targets.some((t) => t.endsWith(file))).toBe(true);
    }
  });

  test("the operator secret reaches the host as a lookup, not a value", () => {
    // `.colors/` is generated output and the goldens are committed, so the
    // secret must never be the thing that lands on disk — the expression is.
    expect(resource("ansible/main.yml"))
      .toContain("lookup('env','COLORS_PAR_ANTHROPIC_API_KEY')");
  });

  test("the data map carries no operator secret", () => {
    const spec = tools.ansibleSpecs(fixture())
      .find((s) => String(s.target).endsWith("main.yml"));
    const data = spec?.data ?? {};
    expect(data["agent-network-host"]).toBe("agent-network.example.com");
    expect(data["anthropic-api-key"]).toBeUndefined();
  });

  test("generated secrets are placeholders in the rendered config", () => {
    const config = resource("ansible/config.yaml");
    for (const ph of ["__RELAY_AUTH_SECRET__", "__SESSION_COOKIE_ENCRYPTION_KEY__",
                      "__DATASTORE_ENCRYPTION_KEY__"]) {
      expect(config).toContain(ph);
    }
    expect(resource("ansible/proxy.env")).toContain("__PROXY_TOKEN__");
  });

  test("desired.json carries the control-plane contract", () => {
    // The host bootstrap reconciles against this document; its shape is the
    // wire shape of the agent-network API (underscore keys, per-1k prices).
    const raw = tools.desiredJson(fixture());
    const desired = JSON.parse(raw);
    expect(desired.provider.provider_id).toBe("anthropic_api");
    expect(desired.provider.upstream_url).toBe("https://api.anthropic.com");
    expect(desired.allowed_models).toEqual(["claude-haiku-4-5-20251001"]);
    expect(desired.provider.models.length).toBe(2);
    expect(desired.provider.models[0].input_per_1k).toBe(0.001);
    expect(desired.policy.budget_usd_per_day).toBe(2);
    expect(desired.global.budget_usd_per_day).toBe(5);
    expect(desired.log_retention_days).toBe(7);
    // Cheshire's float notation is the committed byte contract.
    expect(raw).toContain("1.0E-4");
    // Non-secret by construction: nothing shaped like a credential belongs
    // here, and the API key in particular must not.
    expect(raw).not.toContain("api_key");
  });

  test("a delete without compute skips the host entirely", async () => {
    const result = await tools.ansibleStep(fixture({ "red/event": "delete" }));
    expect(result["red/exit"]).toBe(0);
  });

  test("acceptance is skipped outside a real create", async () => {
    for (const event of ["build", "delete"]) {
      const result = await tools.acceptanceStep(fixture({ "red/event": event }));
      expect(result["red/exit"]).toBe(0);
    }
  });

  test("hairpin is broken by exactly two mappings", () => {
    // Two containers must reach the public hostname without leaving the box:
    // the agent's bootstrap terminates at Traefik on the internal network
    // (tunnel DNS does not exist yet), and the proxy's embedded client
    // reaches signal/relay at Traefik on the gateway network (hairpin NAT
    // otherwise). Nothing else is mapped.
    const compose = resource("ansible/compose.yml");
    expect(compose).toContain("<{ agent-network-host }>:<{ traefik-agent-ip }>");
    expect(compose).toContain("<{ agent-network-host }>:<{ traefik-ip }>");
    expect(compose.match(/^\s+extra_hosts:/gm)?.length).toBe(2);
  });

  test("the agent joins only the internal network", () => {
    const compose = resource("ansible/compose.yml");
    const service = compose.match(/^  agent:\n([\s\S]*?)^volumes:/m)?.[1];
    expect(service).toBeDefined();
    expect(service!).toContain("agent:\n        ipv4_address");
    expect(/^\s+gateway/m.test(service!)).toBe(false);
    expect(compose).toContain("internal: true");
  });

  test("the dashboard runs the agent-network preset", () => {
    // `init_react_envs` exits 1 without USE_AUTH0, and this package exists to
    // show the focused agent-network surface — the sibling `netbird` package
    // asserts the preset flag's absence.
    const env = resource("ansible/dashboard.env");
    expect(env).toContain("USE_AUTH0=false");
    expect(env).toContain("NETBIRD_AGENT_NETWORK_ONLY=true");
  });

  test("every claude model knob is pinned", () => {
    // A single unpinned tier (subagents included) lets Claude Code name a
    // model the guardrail rejects, and the demo's happy path dies on its own
    // policy.
    const main = resource("ansible/main.yml");
    for (const knob of ["ANTHROPIC_MODEL", "ANTHROPIC_SMALL_FAST_MODEL",
                        "ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL",
                        "ANTHROPIC_DEFAULT_HAIKU_MODEL", "CLAUDE_CODE_SUBAGENT_MODEL"]) {
      expect(main).toContain(`${knob}=<{ allowed-model }>`);
    }
  });

  test("the setup key travels by file, never argv", () => {
    // client/cmd/root.go: --setup-key-file reads the key from a path;
    // --setup-key would put it in a process listing.
    const entry = resource("ansible/agent-entry.sh");
    expect(entry).toContain("--setup-key-file");
    expect(/--setup-key\s+[^-f]/.test(entry)).toBe(false);
  });

  test("tool dirs live under <workdir>/<profile>", () => {
    const opts = { workdir: "/work", profile: "agent-network-fixture" };
    expect(tools.toolDir(opts, tools.infrastructureTool))
      .toBe("/work/agent-network-fixture/agent-network-infrastructure");
    expect(tools.toolDir(opts, tools.ansibleLocalTool))
      .toBe("/work/agent-network-fixture/agent-network-ansible-local");
  });

  test("backend advice writes the conventional state address", () => {
    const work = mkdtempSync(join(tmpdir(), "agent-network-red-backend"));
    try {
      const opts = fixture({ workdir: work, "provider-backend": "r2" });
      workflow.backendAdvice(tools.dnsTool)(opts);
      const backend = JSON.parse(readFileSync(
        join(work, "agent-network-fixture", "agent-network-dns", "backend.tf.json"), "utf8"));
      const s3 = backend.terraform.backend.s3;
      expect(s3.bucket).toBe("tofu-state-example");
      expect(s3.key).toBe("agent-network-fixture/agent-network-dns.tfstate");
      expect(s3.endpoints.s3).toBe("https://example.eu.r2.cloudflarestorage.com");
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
  });
});

// --- ssh keypair (SSH Keypair Standard) --------------------------------------

describe("ssh", () => {
  test("build renders a stable placeholder path", () => {
    const opts = ssh.withMachineKey(fixture({ "red/event": "build" }));
    expect(String(opts["ssh-public-key-path"])).toStartWith(ssh.buildPlaceholderDir);
    expect(opts["vultr-ssh-keys"]).toBe(opts["ssh-public-key-path"]);
    expect(String(opts["ssh-private-key-path"])).not.toContain(home);
  });

  test("the build placeholder lands on the selected provider's key", () => {
    // ONCE's table decides which desired-state key carries the machine key,
    // so a second provider needs no second branch here.
    const opts = ssh.withMachineKey(doFixture({ "red/event": "build" }));
    expect(opts["digitalocean-ssh-keys"]).toBe(opts["ssh-public-key-path"]);
    expect("vultr-ssh-keys" in opts).toBe(false);
    expect(String(opts["ssh-public-key-path"])).toStartWith(ssh.buildPlaceholderDir);
    const optedOut = ssh.withMachineKey(doOptout({ "red/event": "build" }));
    expect(optedOut["digitalocean-ssh-keys"]).toBe("00000000");
    expect(optedOut["ssh-public-key-path"]).toBeUndefined();
  });

  test("a dry-run renders the placeholder too", () => {
    const opts = ssh.withMachineKey(fixture({ "red/event": "create", "red/dry-run": true }));
    expect(String(opts["ssh-public-key-path"])).toStartWith(ssh.buildPlaceholderDir);
  });

  test("real events render the real path", () => {
    const opts = ssh.withMachineKey(fixture({ "red/event": "create" }));
    expect(opts["ssh-private-key-path"]).toBe(join(home, ".ssh", "agent-network-fixture"));
    expect(opts["ssh-public-key-path"]).toBe(join(home, ".ssh", "agent-network-fixture.pub"));
  });

  test("opt-out passes through untouched", () => {
    for (const event of ["build", "create", "delete"]) {
      const opts = ssh.withMachineKey(optout({ "red/event": event }));
      expect(opts["vultr-ssh-keys"]).toBe("00000000-0000-0000-0000-000000000000");
      expect(opts["ssh-public-key-path"]).toBeUndefined();
      expect(opts["ssh-keygen"]).toBeUndefined();
    }
  });

  test("first create generates the keypair", async () => {
    const opts = await ssh.ensureKey(fixture({ "red/event": "create" }), async () => undefined);
    const prv = join(home, ".ssh", "agent-network-fixture");
    const pub = `${prv}.pub`;
    expect(opts["red/err"]).toBeUndefined();
    expect(existsSync(prv)).toBe(true);
    expect(existsSync(pub)).toBe(true);
    // ed25519, no passphrase, profile-named comment
    expect(readFileSync(pub, "utf8")).toContain("ssh-ed25519");
    expect(readFileSync(pub, "utf8")).toContain("agent-network-fixture managed by Colors");
    // 600 on the private key, 700 on ~/.ssh
    expect(statSync(prv).mode & 0o777).toBe(0o600);
    expect(statSync(join(home, ".ssh")).mode & 0o777).toBe(0o700);
  });

  test("converge reuses an existing key", async () => {
    write(join(home, ".ssh", "agent-network-fixture"), "private");
    write(join(home, ".ssh", "agent-network-fixture.pub"), "ssh-ed25519 AAAA test");
    const opts = await ssh.ensureKey(fixture({ "red/event": "create" }),
      async () => ({ ip: "192.0.2.10" }));
    expect(opts["red/err"]).toBeUndefined();
    expect(readFileSync(join(home, ".ssh", "agent-network-fixture"), "utf8")).toBe("private");
  });

  test("state without a key is an error", async () => {
    const opts = await ssh.ensureKey(fixture({ "red/event": "create" }),
      async () => ({ ip: "192.0.2.10" }));
    expect(opts["red/exit"]).toBe(1);
    expect(String(opts["red/err"])).toContain("does not hold the machine key");
    expect(String(opts["red/err"])).toContain("rebuild");
  });

  test("a key without state is never overwritten", async () => {
    const prv = join(home, ".ssh", "agent-network-fixture");
    write(prv, "irreplaceable");
    write(`${prv}.pub`, "ssh-ed25519 AAAA test");
    const opts = await ssh.ensureKey(fixture({ "red/event": "create" }), async () => undefined);
    expect(opts["red/exit"]).toBe(1);
    expect(String(opts["red/err"])).toContain("no compute state is readable");
    expect(String(opts["red/err"])).toContain("survives");
    expect(readFileSync(prv, "utf8")).toBe("irreplaceable");
  });

  test("half a keypair is an error", async () => {
    write(join(home, ".ssh", "agent-network-fixture"), "private");
    const opts = await ssh.ensureKey(fixture({ "red/event": "create" }), async () => undefined);
    expect(opts["red/exit"]).toBe(1);
    expect(String(opts["red/err"])).toContain("half a keypair");
  });

  test("opt-out generates nothing", async () => {
    const opts = await ssh.ensureKey(optout({ "red/event": "create" }), async () => undefined);
    expect(opts["red/err"]).toBeUndefined();
    expect(existsSync(join(home, ".ssh"))).toBe(false);
  });

  test("preflight passes when no account key matches, or when it is ours", async () => {
    const clean = await ssh.preflight(ssh.withMachineKey(fixture({ "red/event": "create" })),
      async () => [{ id: "1", name: "someone-else", public: "ssh-ed25519 BBBB" }]);
    expect(clean["red/err"]).toBeUndefined();
    const owned = await ssh.preflight(
      ssh.withMachineKey(fixture({ "red/event": "create",
        "once/ssh-state-params": { ssh_key_id: "abc" } })),
      async () => [{ id: "abc", name: "agent-network-fixture", public: "ssh-ed25519 AAAA" }]);
    expect(owned["red/err"]).toBeUndefined();
  });

  test("preflight refuses our leftover key", async () => {
    write(join(home, ".ssh", "agent-network-fixture.pub"), "ssh-ed25519 AAAA comment");
    const opts = await ssh.preflight(ssh.withMachineKey(fixture({ "red/event": "create" })),
      async () => [{ id: "abc", name: "agent-network-fixture", public: "ssh-ed25519 AAAA" }]);
    expect(opts["red/exit"]).toBe(1);
    expect(String(opts["red/err"])).toContain("previous delete");
    expect(String(opts["red/err"])).toContain("delete that key");
  });

  test("preflight refuses a foreign key and says do not delete it", async () => {
    write(join(home, ".ssh", "agent-network-fixture.pub"), "ssh-ed25519 OURS comment");
    const opts = await ssh.preflight(ssh.withMachineKey(fixture({ "red/event": "create" })),
      async () => [{ id: "abc", name: "agent-network-fixture", public: "ssh-ed25519 THEIRS" }]);
    expect(opts["red/exit"]).toBe(1);
    expect(String(opts["red/err"])).toContain("Do not delete it");
  });

  test("preflight lists keys with the selected provider's token", async () => {
    // ONCE selects the REST API and the token by provider; this proves the
    // delegation hands each provider its own credential.
    const seen: Array<[string, string]> = [];
    const capture = async (provider: string, token: string) => { seen.push([provider, token]); return []; };
    await ssh.preflight(ssh.withMachineKey(doFixture({ "red/event": "create",
      "do-token": "do-secret", "vultr-api-key": "wrong" })), capture);
    await ssh.preflight(ssh.withMachineKey(fixture({ "red/event": "create",
      "vultr-api-key": "vultr-secret", "do-token": "wrong" })), capture);
    expect(seen).toEqual([["digitalocean", "do-secret"], ["vultr", "vultr-secret"]]);
  });

  test("preflight failure is an error, not a skip", async () => {
    const opts = await ssh.preflight(ssh.withMachineKey(fixture({ "red/event": "create" })),
      async () => { throw new Error("HTTP 500"); });
    expect(opts["red/exit"]).toBe(1);
    expect(String(opts["red/err"])).toContain("cannot list");
  });

  test("delete removes the keypair; ~/.ssh itself survives", () => {
    write(join(home, ".ssh", "agent-network-fixture"), "private");
    write(join(home, ".ssh", "agent-network-fixture.pub"), "public");
    ssh.cleanupStep(fixture({ "red/event": "delete", "ssh-keygen": true }));
    expect(existsSync(join(home, ".ssh", "agent-network-fixture"))).toBe(false);
    expect(existsSync(join(home, ".ssh", "agent-network-fixture.pub"))).toBe(false);
    expect(existsSync(join(home, ".ssh"))).toBe(true);
  });

  test("cleanup is inert on create and in opt-out mode", () => {
    write(join(home, ".ssh", "agent-network-fixture"), "private");
    ssh.cleanupStep(fixture({ "red/event": "create", "ssh-keygen": true }));
    expect(existsSync(join(home, ".ssh", "agent-network-fixture"))).toBe(true);
    ssh.cleanupStep(optout({ "red/event": "delete" }));
    expect(existsSync(join(home, ".ssh", "agent-network-fixture"))).toBe(true);
  });
});

// --- ~/.ssh/config (SSH Config Standard) -------------------------------------

describe("ssh-config", () => {
  test("the alias is the profile and the identity file keeps the tilde", () => {
    expect(sshConfig.hostAlias(fixture())).toBe("agent-network-fixture");
    expect(sshConfig.identityFile(fixture())).toBe("~/.ssh/agent-network-fixture");
    expect(sshConfig.identityFile(fixture())).not.toContain(home);
  });

  test("the marker is the alias alone", () => {
    expect(sshConfig.beginMarker("agent-network-vultr"))
      .toBe("# BEGIN agent-network-vultr ANSIBLE MANAGED BLOCK");
    expect(sshConfig.endMarker("agent-network-vultr"))
      .toBe("# END agent-network-vultr ANSIBLE MANAGED BLOCK");
  });

  test("a foreign stanza is found; our own block is not foreign", () => {
    expect(sshConfig.foreignStanzaLine(
      ["Host other", "    HostName 192.0.2.1", "", "Host agent-network-fixture"],
      "agent-network-fixture")).toBe(4);
    const alias = "agent-network-fixture";
    expect(sshConfig.foreignStanzaLine(
      [sshConfig.beginMarker(alias), `Host ${alias}`, "    HostName 192.0.2.1",
       sshConfig.endMarker(alias)], alias)).toBeUndefined();
  });

  test("a stanza after our block is still foreign", () => {
    const alias = "agent-network-fixture";
    expect(sshConfig.foreignStanzaLine(
      [sshConfig.beginMarker(alias), `Host ${alias}`, sshConfig.endMarker(alias),
       `Host ${alias}`], alias)).toBe(4);
  });

  test("a block under a retired marker is foreign", () => {
    const alias = "agent-network-vultr";
    expect(sshConfig.foreignStanzaLine(
      [`# BEGIN agent-network ${alias} ANSIBLE MANAGED BLOCK`, `Host ${alias}`,
       `# END agent-network ${alias} ANSIBLE MANAGED BLOCK`], alias)).toBe(2);
  });

  test("multi-pattern host lines count; unrelated files are left alone", () => {
    expect(sshConfig.foreignStanzaLine(["Host web agent-network-fixture db"],
      "agent-network-fixture")).toBe(1);
    expect(sshConfig.foreignStanzaLine(["Host build", "Host agent-network-other"],
      "agent-network-fixture")).toBeUndefined();
  });

  test("an option above the first Host is refused; comments and Host openers are fine", () => {
    expect(sshConfig.leadingOptionLine(["ServerAliveInterval 60", "Host a"])).toBe(1);
    expect(sshConfig.leadingOptionLine(["# comment", "", "IdentitiesOnly yes", "Host a"])).toBe(3);
    expect(sshConfig.leadingOptionLine(["Host a", "    User root"])).toBeUndefined();
    expect(sshConfig.leadingOptionLine(["# lead comment", "", "Host a", "    User root"])).toBeUndefined();
    expect(sshConfig.leadingOptionLine(["Match host b", "    User root"])).toBeUndefined();
    expect(sshConfig.leadingOptionLine(["# nothing here", ""])).toBeUndefined();
  });

  test("preflight refuses rather than overwrites", () => {
    const refused = sshConfig.preflight(fixture(), {
      adoptError: () => "already declares `Host x`",
      placementError: () => undefined,
    });
    expect(refused["red/exit"]).toBe(1);
    expect(String(refused["red/err"])).toContain("already declares");
    const clean = sshConfig.preflight(fixture(), {
      adoptError: () => undefined,
      placementError: () => undefined,
    });
    expect(clean["red/exit"]).toBeUndefined();
  });

  test("adopt and placement errors read the real file and mention the recovery", () => {
    write(join(home, ".ssh", "config"), "ServerAliveInterval 60\nHost agent-network-fixture\n");
    expect(String(sshConfig.adoptError(fixture()))).toContain("Host agent-network-fixture");
    expect(String(sshConfig.placementError(fixture()))).toContain("Host *");
  });

  test("the local play renders no address and follows keygen mode", () => {
    const data = tools.ansibleLocalData(fixture({ ip: "203.0.113.7" }));
    expect(data["ssh-config-identity-file"]).toBe("~/.ssh/agent-network-fixture");
    expect(data["ssh-keygen"]).toBe(true);
    expect(tools.ansibleLocalData(optout())["ssh-keygen"]).toBe(false);
  });

  test("the local stage renders three files", () => {
    const targets = tools.ansibleLocalSpecs(fixture()).map((s) => String(s.target));
    for (const file of ["/ansible.cfg", "/inventory.ini", "/main.yml"]) {
      expect(targets.some((t) => t.endsWith(file))).toBe(true);
    }
    expect(targets.every((t) => t.includes("agent-network-ansible-local"))).toBe(true);
  });
});

// --- workflow ----------------------------------------------------------------

describe("workflow", () => {
  // The compute state is read once per run, through the injectable reader,
  // on a real create or delete. Every lifecycle test stubs it: undefined is a
  // readable state holding no compute, a map is a recorded `params`, and a
  // throw is a backend that cannot be read.
  const start = (opts: Opts, state: Record<string, unknown> | undefined) =>
    workflow.startStep(opts, {}, async () => state);
  const startUnreadable = (opts: Opts) =>
    workflow.startStep(opts, {}, async () => { throw new Error("tofu output failed: no backend"); });
  const credentials = { "vultr-api-key": "v", "do-token": "d", "cloudflare-api-token": "c",
    "r2-access-key-id": "a", "r2-secret-access-key": "s", "anthropic-api-key": "k" };

  test("build and dry-run need no credentials and never touch ~/.ssh or the state", async () => {
    // The standard forbids reading, creating, or requiring anything under
    // ~/.ssh on a build or dry-run: they render from desired state alone.
    // A poisoned config proves nothing in the build path reads it, and a
    // throwing reader proves nothing on these paths reads the backend.
    write(join(home, ".ssh", "config"), "ServerAliveInterval 60\nHost agent-network-fixture\n");
    for (const opts of [fixture({ "red/event": "build" }),
                        fixture({ "red/event": "create", "red/dry-run": true }),
                        doFixture({ "red/event": "delete", "red/dry-run": true })]) {
      const result = await startUnreadable(opts);
      expect(result["red/exit"]).toBe(0);
      expect(String(result["ssh-public-key-path"])).toStartWith("/home/build-placeholder");
    }
  });

  test("a real create requires credentials", async () => {
    const result = await start(fixture({ "red/event": "create" }), undefined);
    expect(result["red/exit"]).toBe(2);
    expect(String(result["red/err"])).toContain("COLORS_PAR_VULTR_API_KEY");
    expect(String(result["red/err"])).toContain("COLORS_PAR_CLOUDFLARE_API_TOKEN");
    expect(String(result["red/err"])).toContain("COLORS_PAR_ANTHROPIC_API_KEY");
  });

  test("a real create and delete require the selected provider's credentials", async () => {
    const create = await start(doFixture({ "red/event": "create" }), undefined);
    expect(create["red/exit"]).toBe(2);
    expect(String(create["red/err"])).toContain("COLORS_PAR_DO_TOKEN");
    expect(String(create["red/err"])).toContain("COLORS_PAR_ANTHROPIC_API_KEY");
    expect(String(create["red/err"])).not.toContain("COLORS_PAR_VULTR_API_KEY");
    const del = await start(doFixture({ "red/event": "delete", "compute-prevent-destroy": false }), undefined);
    expect(del["red/exit"]).toBe(2);
    expect(String(del["red/err"])).toContain("COLORS_PAR_DO_TOKEN");
    expect(String(del["red/err"])).not.toContain("ANTHROPIC");
    expect(String(del["red/err"])).not.toContain("COLORS_PAR_VULTR_API_KEY");
    const vultr = await start(fixture({ "red/event": "delete", "compute-prevent-destroy": false }), undefined);
    expect(String(vultr["red/err"])).toContain("COLORS_PAR_VULTR_API_KEY");
    expect(String(vultr["red/err"])).not.toContain("COLORS_PAR_DO_TOKEN");
  });

  test("delete is protected", async () => {
    const result = await start(fixture({ "red/event": "delete" }), undefined);
    expect(result["red/exit"]).toBe(2);
    expect(String(result["red/err"])).toContain("COMPUTE_PREVENT_DESTROY");
  });

  // --- provider switching is a rebuild, never an apply

  test("a provider switch is refused on create and delete", async () => {
    for (const event of ["create", "delete"]) {
      const vultr = await start(fixture({ "red/event": event, "compute-prevent-destroy": false }),
        { provider: "digitalocean", ip: "203.0.113.9" });
      expect(vultr["red/exit"]).toBe(2);
      expect(String(vultr["red/err"]))
        .toContain("state holds a digitalocean machine; set provider-compute back to digitalocean and delete first");
      // The validator order is the thing under test: the actionable error,
      // not a missing token for the provider that was just selected.
      expect(String(vultr["red/err"])).not.toContain("required credential is not set");
      const digitalocean = await start(doFixture({ "red/event": event, "compute-prevent-destroy": false }),
        { provider: "vultr", ip: "203.0.113.9" });
      expect(digitalocean["red/exit"]).toBe(2);
      expect(String(digitalocean["red/err"])).toContain("state holds a vultr machine; set provider-compute back to vultr");
      expect(String(digitalocean["red/err"])).not.toContain("COLORS_PAR_DO_TOKEN");
    }
  });

  test("legacy state accepts only the default provider", async () => {
    for (const event of ["create", "delete"]) {
      const vultr = await start(fixture({ "red/event": event, "compute-prevent-destroy": false }),
        { ip: "203.0.113.9" });
      expect(String(vultr["red/err"])).not.toContain("state holds");
      expect(String(vultr["red/err"])).toContain("required credential is not set");
      const digitalocean = await start(doFixture({ "red/event": event, "compute-prevent-destroy": false }),
        { ip: "203.0.113.9" });
      expect(digitalocean["red/exit"]).toBe(2);
      expect(String(digitalocean["red/err"])).toContain("no recorded provider");
      expect(String(digitalocean["red/err"])).toContain("set provider-compute back to vultr and delete first");
      expect(String(digitalocean["red/err"])).not.toContain("COLORS_PAR_DO_TOKEN");
    }
  });

  test("a matching provider passes to the credentials", async () => {
    const result = await start(fixture({ "red/event": "create" }), { provider: "vultr", ip: "203.0.113.9" });
    expect(result["red/exit"]).toBe(2);
    expect(String(result["red/err"])).not.toContain("state holds");
    expect(String(result["red/err"])).toContain("COLORS_PAR_VULTR_API_KEY");
  });

  test("an unreadable backend counts as no state on create", async () => {
    // A fresh clone has no readable state and must still be able to create.
    const result = await startUnreadable(fixture({ "red/event": "create" }));
    expect(result["red/exit"]).toBe(2);
    expect(String(result["red/err"])).not.toContain("could not read");
    expect(String(result["red/err"])).not.toContain("state holds");
    expect(String(result["red/err"])).toContain("COLORS_PAR_VULTR_API_KEY");
  });

  test("an unreadable backend fails a real delete closed", async () => {
    // Swallowing it is how a teardown ends up converging against 192.0.2.10.
    const result = await startUnreadable(fixture({ ...credentials, "red/event": "delete",
      "compute-prevent-destroy": false }));
    expect(result["red/exit"]).toBe(1);
    expect(String(result["red/err"])).toContain("could not read the infrastructure state for the delete cleanup");
    expect(String(result["red/err"])).toContain("no backend");
  });

  test("a real delete adopts the recorded address", async () => {
    const adopted = await start(fixture({ ...credentials, "red/event": "delete", "compute-prevent-destroy": false }),
      { provider: "vultr", ip: "203.0.113.9", user: "root" });
    expect(adopted["red/exit"]).toBe(0);
    expect(adopted.ip).toBe("203.0.113.9");
    // A readable state without compute leaves the address unset, and the
    // cleanup step skips itself.
    const empty = await start(fixture({ ...credentials, "red/event": "delete", "compute-prevent-destroy": false }),
      undefined);
    expect(empty["red/exit"]).toBe(0);
    expect(empty.ip).toBeUndefined();
  });

  test("the create graph orders the stack", () => {
    const next = (step: string) =>
      (workflow.wireFn(step, { "red/event": "create" }) ?? []).slice(1);
    expect(next("agent-network/start")).toEqual(["agent-network/infrastructure"]);
    expect(next("agent-network/infrastructure")).toEqual(["agent-network/ssh-config"]);
    expect(next("agent-network/ssh-config")).toEqual(["agent-network/dns"]);
    // DNS before convergence: Traefik asks Let's Encrypt for a certificate as
    // soon as it starts, and TLS-ALPN-01 only succeeds once the names —
    // wildcard included — resolve.
    expect(next("agent-network/dns")).toEqual(["agent-network/ansible"]);
    expect(next("agent-network/ansible")).toEqual(["agent-network/acceptance"]);
  });

  test("delete removes the config block before the destroy and the key after it", () => {
    const next = (step: string) =>
      (workflow.wireFn(step, { "red/event": "delete" }) ?? []).slice(1);
    expect(next("agent-network/start")).toEqual(["agent-network/ansible"]);
    expect(next("agent-network/ansible")).toEqual(["agent-network/dns"]);
    expect(next("agent-network/dns")).toEqual(["agent-network/ssh-config"]);
    expect(next("agent-network/ssh-config")).toEqual(["agent-network/infrastructure"]);
    expect(next("agent-network/infrastructure")).toEqual(["agent-network/ssh-cleanup"]);
    expect(next("agent-network/ssh-cleanup")).toEqual([]);
  });
});
