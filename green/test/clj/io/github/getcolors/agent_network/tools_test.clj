(ns io.github.getcolors.agent-network.tools-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.agent-network.tools :as tools]
            [io.github.getcolors.agent-network.validate :as validate]
            [io.github.getcolors.agent-network.validate-test :refer [fixture optout do-fixture do-optout]]))

(defn- spec-for [opts file]
  (some #(when (str/ends-with? (str (:target %)) file) %) (tools/ansible-specs opts)))

(deftest firewall-sources-parse
  (let [data (tools/infrastructure-data (fixture))]
    (is (= ["0.0.0.0/0"] (tools/cidrs data :vultr-http-sources)))
    (is (= ["0.0.0.0/0"] (tools/cidrs data :vultr-stun-sources)))))

(deftest infrastructure-data-carries-the-ssh-mode
  (is (true? (:ssh-keygen (tools/infrastructure-data (fixture)))))
  (is (false? (:ssh-keygen (tools/infrastructure-data (optout)))))
  (is (true? (:ssh-keygen (tools/infrastructure-data (do-fixture)))))
  (is (false? (:ssh-keygen (tools/infrastructure-data (do-optout))))))

(deftest infrastructure-data-reads-the-selected-providers-keys
  ;; The template interpolates one resolved name and one resolved list per
  ;; port, whichever provider they came from — the STUN list included.
  (let [data (tools/infrastructure-data (do-fixture :digitalocean-ssh-sources ["10.0.0.0/8"]
                                                    :digitalocean-stun-sources ["198.51.100.0/24"]
                                                    :vultr-ssh-sources ["192.0.2.0/24"]))]
    (is (= "[\"10.0.0.0/8\"]" (:ssh-sources-hcl data)))
    (is (= "[\"198.51.100.0/24\"]" (:stun-sources-hcl data)))
    (is (= "agent-network-digitalocean-fixture" (:compute-name data))))
  (is (= "agent-network-fixture" (:compute-name (tools/infrastructure-data (fixture))))))

(deftest template-directory-follows-the-provider
  (is (= :io.github.getcolors.agent-network.tools.infrastructure.vultr/main.tf
         (tools/infrastructure-template (fixture))))
  (is (= :io.github.getcolors.agent-network.tools.infrastructure.digitalocean/main.tf
         (tools/infrastructure-template (do-fixture))))
  ;; A registry entry without a template directory would pass every unit test
  ;; and fail the first build.
  (doseq [provider (keys validate/compute-providers)]
    (is (io/resource (str "io/github/getcolors/agent-network/tools/infrastructure/" provider "/main.tf"))
        provider)))

(deftest every-provider-template-mirrors-the-whole-rule-set
  ;; The firewall admits 22, 80/443 and STUN over UDP on every provider, and
  ;; records which provider produced the params.
  (doseq [provider (keys validate/compute-providers)]
    (let [tf (slurp (io/resource (str "io/github/getcolors/agent-network/tools/infrastructure/" provider "/main.tf")))]
      (is (str/includes? tf "ssh-sources-hcl") provider)
      (is (str/includes? tf "http-sources-hcl") provider)
      (is (str/includes? tf "stun-sources-hcl") provider)
      (is (str/includes? tf "\"udp\"") provider)
      (is (str/includes? tf "<{ agent-network-stun-port }>") provider)
      (is (str/includes? tf (str "provider = \"" provider "\"")) provider))))

(deftest fallback-params-are-shaped-per-provider
  (is (= {:provider "vultr" :ip "192.0.2.10" :user "root" :sudoer "root" :name "agent-network-fixture"}
         (tools/fallback-params (fixture))))
  (is (= {:provider "digitalocean" :ip "192.0.2.10" :user "root" :sudoer "root"
          :name "agent-network-digitalocean-fixture"}
         (tools/fallback-params (do-fixture)))))

(deftest a-real-create-refuses-a-missing-ip-output
  ;; 192.0.2.10 is the documentation address build renders with; a real
  ;; converge must never fall back to it.
  (let [refused (tools/resolved-compute {} (tools/fallback-params (fixture)) nil)]
    (is (= 1 (:green/exit refused)))
    (is (str/includes? (:green/err refused) "compute produced no ip output")))
  (let [refused (tools/resolved-compute {} (tools/fallback-params (fixture)) {:name "x"})]
    (is (= 1 (:green/exit refused))))
  (let [ok (tools/resolved-compute {} (tools/fallback-params (fixture))
                                   {:ip "203.0.113.9" :provider "vultr"})]
    (is (nil? (:green/exit ok)))
    (is (= "203.0.113.9" (:ip ok)))))

(deftest every-label-derives-from-one-resolved-name
  ;; Compute Name Standard §3: one function answers "what is this deployment's
  ;; machine called", and the firewall asks it too rather than keeping a second
  ;; copy of the profile.
  (let [data (tools/infrastructure-data (fixture :vultr-name "override-box"))]
    (is (= "override-box" (:compute-name data)))))

(deftest dns-zone-is-registrable-domain
  (is (= "example.com" (validate/zone (fixture)))))

(deftest dns-creates-the-name-and-its-wildcard-unproxied
  ;; Unproxied because Cloudflare's proxy is an HTTP proxy: UDP STUN does not
  ;; survive it and both certificate paths — Traefik's TLS-ALPN-01 and the
  ;; reverse proxy's own endpoint ACME — would terminate at the proxy.
  ;;
  ;; The wildcard is contract, not convenience: the endpoint hostname is a
  ;; label management mints beneath the base domain at bootstrap, and nothing
  ;; knows it before it exists.
  (let [json-out (tools/dns-json (assoc (fixture) :ip "192.0.2.10"))]
    (is (str/includes? json-out "agent-network.example.com"))
    (is (str/includes? json-out "*.agent-network.example.com"))
    (is (str/includes? json-out "192.0.2.10"))
    (is (str/includes? json-out "\"proxied\" : false"))
    (is (not (str/includes? json-out "true")))))

(deftest inventory-keeps-one-target
  (let [inventory (tools/inventory (assoc (fixture) :ip "192.0.2.10"))]
    (is (str/includes? inventory "192.0.2.10"))
    (is (str/includes? inventory "agent-network-fixture"))))

(deftest ansible-renders-the-whole-stack
  (let [targets (map #(str (:target %)) (tools/ansible-specs (fixture)))]
    (doseq [f ["ansible.cfg" "main.yml" "cleanup.yml" "compose.yml" "config.yaml"
               "dashboard.env" "proxy.env" "traefik-dynamic.yaml" "bootstrap.sh"
               "agent.Dockerfile" "agent-entry.sh" "smoke.sh" "status.sh"
               "firewall.sh" "firewall.service" "desired.json" "inventory.json"]]
      (is (some #(str/ends-with? % f) targets) f))))

(deftest the-operator-secret-reaches-the-host-as-a-lookup-not-a-value
  ;; `.colors/` is generated output and the goldens are committed, so the
  ;; secret must never be the thing that lands on disk — the expression is.
  ;; The lookup lives literally in the template rather than in the data map,
  ;; because Selmer HTML-escapes a value it interpolates and Ansible would
  ;; receive `&#39;` instead of a quote.
  (let [template (slurp (io/resource "io/github/getcolors/agent-network/tools/ansible/main.yml"))]
    (is (str/includes? template "lookup('env','COLORS_PAR_ANTHROPIC_API_KEY')"))))

(deftest the-data-map-carries-no-operator-secret
  (let [data (:data (spec-for (fixture) "main.yml"))]
    (is (= "agent-network.example.com" (:agent-network-host data)))
    (is (nil? (:anthropic-api-key data)))))

(deftest generated-secrets-are-placeholders-in-the-rendered-config
  ;; Host-generated secrets are substituted on the host at install time, so
  ;; what `build` renders — and what a golden commits — is the placeholder.
  (let [config (slurp (io/resource "io/github/getcolors/agent-network/tools/ansible/config.yaml"))
        proxy (slurp (io/resource "io/github/getcolors/agent-network/tools/ansible/proxy.env"))]
    (doseq [ph ["__RELAY_AUTH_SECRET__" "__SESSION_COOKIE_ENCRYPTION_KEY__"
                "__DATASTORE_ENCRYPTION_KEY__"]]
      (is (str/includes? config ph) ph))
    (is (str/includes? proxy "__PROXY_TOKEN__"))))

(deftest desired-json-carries-the-control-plane-contract
  ;; The host bootstrap reconciles against this document; its shape is the
  ;; wire shape of the agent-network API (underscore keys, per-1k prices).
  (let [desired (json/parse-string (tools/desired-json (fixture)) true)]
    (is (= "anthropic_api" (get-in desired [:provider :provider_id])))
    (is (= "https://api.anthropic.com" (get-in desired [:provider :upstream_url])))
    (is (= ["claude-haiku-4-5-20251001"] (:allowed_models desired)))
    (is (= 2 (count (get-in desired [:provider :models]))))
    (is (= 0.001 (get-in desired [:provider :models 0 :input_per_1k])))
    (is (= 2 (get-in desired [:policy :budget_usd_per_day])))
    (is (= 5 (get-in desired [:global :budget_usd_per_day])))
    (is (= 7 (:log_retention_days desired)))
    ;; Non-secret by construction: nothing shaped like a credential belongs
    ;; here, and the API key in particular must not.
    (is (not (str/includes? (tools/desired-json (fixture)) "api_key")))))

(deftest a-delete-without-compute-skips-the-host-entirely
  ;; There is no machine to stop, and the cleanup play would only fail against
  ;; the placeholder address.
  (is (= 0 (:green/exit (tools/ansible-step (assoc (fixture) :green/event :delete))))))

(deftest acceptance-is-skipped-outside-a-real-create
  (doseq [event [:build :delete]]
    (is (= 0 (:green/exit (tools/acceptance-step (assoc (fixture) :green/event event)))))))

(deftest hairpin-is-broken-by-exactly-two-mappings
  ;; Two containers must reach the public hostname without leaving the box:
  ;; the agent's bootstrap terminates at Traefik on the internal network
  ;; (tunnel DNS does not exist yet), and the proxy's embedded client reaches
  ;; signal/relay at Traefik on the gateway network (hairpin NAT otherwise).
  ;; Nothing else is mapped — in particular not the endpoint hostname, whose
  ;; resolution is management's, pushed over the tunnel, because that is what
  ;; keeps the metered path on the tunnel and the identity real.
  (let [compose (slurp (io/resource "io/github/getcolors/agent-network/tools/ansible/compose.yml"))]
    (is (str/includes? compose "<{ agent-network-host }>:<{ traefik-agent-ip }>"))
    (is (str/includes? compose "<{ agent-network-host }>:<{ traefik-ip }>"))
    (is (= 2 (count (re-seq #"(?m)^\s+extra_hosts:" compose))))))

(deftest the-agent-joins-only-the-internal-network
  (let [compose (slurp (io/resource "io/github/getcolors/agent-network/tools/ansible/compose.yml"))
        service (second (re-find #"(?ms)^  agent:\n(.*?)^volumes:" compose))]
    (is (some? service) "the agent service block must be findable")
    (is (str/includes? service "agent:\n        ipv4_address"))
    (is (not (re-find #"(?m)^\s+gateway" service))
        "the agent may not attach to the egress-capable network")
    (is (str/includes? compose "internal: true"))))

(deftest the-dashboard-runs-the-agent-network-preset
  ;; `init_react_envs` exits 1 without USE_AUTH0 and supervisord carries on
  ;; (nginx then serves placeholders while `/` returns 200); and this package
  ;; exists to show the focused agent-network surface, so the preset flag must
  ;; be present — the sibling `netbird` package asserts its absence.
  (let [env (slurp (io/resource "io/github/getcolors/agent-network/tools/ansible/dashboard.env"))]
    (is (str/includes? env "USE_AUTH0=false"))
    (is (str/includes? env "NETBIRD_AGENT_NETWORK_ONLY=true"))))

(deftest every-claude-model-knob-is-pinned
  ;; A single unpinned tier (subagents included) lets Claude Code name a model
  ;; the guardrail rejects, and the demo's happy path dies on its own policy.
  (let [main (slurp (io/resource "io/github/getcolors/agent-network/tools/ansible/main.yml"))]
    (doseq [knob ["ANTHROPIC_MODEL" "ANTHROPIC_SMALL_FAST_MODEL"
                  "ANTHROPIC_DEFAULT_OPUS_MODEL" "ANTHROPIC_DEFAULT_SONNET_MODEL"
                  "ANTHROPIC_DEFAULT_HAIKU_MODEL" "CLAUDE_CODE_SUBAGENT_MODEL"]]
      (is (str/includes? main (str knob "=<{ allowed-model }>")) knob))))

(deftest the-setup-key-travels-by-file-never-argv
  ;; client/cmd/root.go: --setup-key-file reads the key from a path;
  ;; --setup-key would put it in a process listing.
  (let [entry (slurp (io/resource "io/github/getcolors/agent-network/tools/ansible/agent-entry.sh"))]
    (is (str/includes? entry "--setup-key-file"))
    (is (not (re-find #"--setup-key\s+[^-f]" entry)))))
