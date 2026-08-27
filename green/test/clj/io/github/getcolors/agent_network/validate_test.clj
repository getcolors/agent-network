(ns io.github.getcolors.agent-network.validate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.cli :as green-cli]
            [io.github.getcolors.agent-network.validate :as validate]))

(def fixture-file "test/fixtures/colors.yml")
(def optout-file "test/fixtures/optout.yml")

(defn- read-fixture [path overrides]
  (merge (green-cli/read-state path (str/replace (slurp path) "WORKDIR" ".colors"))
         overrides))
(defn fixture [& {:as overrides}] (read-fixture fixture-file overrides))
(defn optout [& {:as overrides}] (read-fixture optout-file overrides))

(deftest fixture-is-valid (is (= [] (validate/state-errors (fixture)))))
(deftest optout-fixture-is-valid (is (= [] (validate/state-errors (optout)))))

(deftest machine-key-is-not-required
  ;; The standard makes absence meaningful: requiring vultr-ssh-keys would make
  ;; every conforming deployment invalid.
  (is (not-any? #(str/includes? % "vultr-ssh-keys") (validate/state-errors (fixture)))))

(deftest absent-machine-key-selects-keygen
  (is (true? (validate/keygen? (fixture))))
  (is (false? (validate/keygen? (optout)))))

;; --- Compute Name Standard -------------------------------------------------

(deftest a-name-key-is-not-required
  ;; §1: a fresh colors.yml that omits it is complete.
  (is (not-any? #(str/includes? % "vultr-name") (validate/state-errors (fixture)))))

(deftest the-machine-is-named-after-the-profile
  (is (= "agent-network-fixture" (validate/compute-name (fixture)))))

(deftest presence-is-the-only-switch
  ;; §2: absent, blank and REPLACE_ME all mean the profile; anything else is
  ;; the name.
  (doseq [v [nil "" "   " "REPLACE_ME"]]
    (is (= "agent-network-fixture" (validate/compute-name (fixture :vultr-name v))) (pr-str v)))
  (is (= "custom-box" (validate/compute-name (fixture :vultr-name "custom-box")))))

(deftest the-override-is-validated-not-passed-through
  ;; §2: validate against the provider's naming rules rather than reading it
  ;; unread.
  (is (some #(str/includes? % "vultr-name")
            (validate/state-errors (fixture :vultr-name "not a valid label!"))))
  (is (= [] (validate/state-errors (fixture :vultr-name "agent-box_1.a")))))

(deftest there-is-no-package-key
  ;; §5: a key that can hold exactly one value carries no information.
  (is (not-any? #(str/includes? % "package") (validate/state-errors (fixture))))
  (is (not (contains? (set validate/required) :package))))

;; --- desired state ---------------------------------------------------------

(deftest reports-all-errors
  (let [errors (validate/state-errors
                (fixture :agent-network-host "bad"
                         :agent-network-server-image "floating"
                         :agent-network-letsencrypt-email "not-an-email"
                         :provider-dns "other" :provider-compute "digitalocean"
                         :agent-network-log-retention-days 0
                         :agent-network-stun-port 70000
                         :agent-network-gateway-subnet "nonsense"
                         :agent-network-claude-code-version "latest"
                         :vultr-os-id "2284"))]
    (is (<= 9 (count errors)))
    (doseq [part ["host" "image" "letsencrypt-email" "provider-dns" "provider-compute"
                  "os-id" "retention-days" "stun-port" "gateway-subnet"
                  "claude-code-version"]]
      (is (some #(str/includes? % part) errors) part))))

(deftest the-two-subnets-must-not-overlap
  ;; One compose network shadowing the other would break both the isolation
  ;; boundary and the DOCKER-USER allow list derived from static addresses.
  (is (some #(str/includes? % "must not overlap")
            (validate/state-errors (fixture :agent-network-agent-subnet "172.30.0.0/24"))))
  (is (some #(str/includes? % "must not overlap")
            (validate/state-errors (fixture :agent-network-agent-subnet "172.30.0.0/16")))))

(deftest addresses-derive-from-the-subnets
  (is (= "172.30.0.10" (validate/traefik-ip (fixture))))
  (is (= "172.31.0.10" (validate/traefik-agent-ip (fixture))))
  (is (= "172.31.0.11" (validate/proxy-agent-ip (fixture))))
  (is (= "172.31.0.20" (validate/agent-ip (fixture))))
  (is (= "10.9.0.10" (validate/traefik-ip (fixture :agent-network-gateway-subnet "10.9.0.0/24")))))

(deftest the-guardrail-needs-a-deniable-model
  ;; Gate 3b demonstrates the guardrail denial, which needs a model routing
  ;; accepts and the allowlist rejects. Allowlisting everything the provider
  ;; claims would configure the guardrail and never demonstrate it.
  (is (some #(str/includes? % "outside")
            (validate/state-errors
             (fixture :agent-network-allowed-models
                      ["claude-haiku-4-5-20251001" "claude-sonnet-4-5-20250929"])))))

(deftest allowed-models-must-be-claimed
  (is (some #(str/includes? % "not claimed")
            (validate/state-errors
             (fixture :agent-network-allowed-models ["claude-3-opus"])))))

(deftest the-denied-claimed-model-is-derived
  (is (= "claude-haiku-4-5-20251001" (validate/allowed-model (fixture))))
  (is (= "claude-sonnet-4-5-20250929" (validate/denied-claimed-model (fixture)))))

(deftest models-need-prices
  (is (some #(str/includes? % "positive input-per-1k")
            (validate/state-errors
             (fixture :agent-network-provider-models
                      [{"id" "claude-haiku-4-5-20251001"}
                       {"id" "claude-sonnet-4-5-20250929"
                        "input-per-1k" 0.003 "output-per-1k" 0.015}])))))

(deftest policy-caps-must-not-exceed-the-global-ceiling
  ;; The global rule is the backstop; a policy cap above it would never bind.
  (is (some #(str/includes? % "must not exceed")
            (validate/state-errors (fixture :agent-network-policy-budget-usd-per-day 50))))
  (is (some #(str/includes? % "must not exceed")
            (validate/state-errors (fixture :agent-network-policy-tokens-per-day 99999999)))))

(deftest retention-mirrors-the-product-range
  (is (some #(str/includes? % "between 7 and 90")
            (validate/state-errors (fixture :agent-network-log-retention-days 5))))
  (is (= [] (validate/state-errors (fixture :agent-network-log-retention-days 90)))))

(deftest accepts-a-digest-pin
  (is (= [] (validate/state-errors
             (fixture :agent-network-traefik-image
                      (str "traefik@sha256:" (apply str (repeat 64 "a"))))))))

(deftest no-image-may-float
  (doseq [k validate/image-keys]
    (is (some #(str/includes? % "floating tag")
              (validate/state-errors (fixture k "netbirdio/netbird-server:latest")))
        (str k))))

(deftest a-floating-tag-with-a-digest-still-floats
  ;; `latest@sha256:...` is pinned bytes under a lying label; the next accept
  ;; of a digest bump would silently re-derive "latest" as the version.
  (is (some #(str/includes? % "floating tag")
            (validate/state-errors
             (fixture :agent-network-server-image
                      (str "netbirdio/netbird-server:latest@sha256:"
                           (apply str (repeat 64 "a"))))))))

(deftest an-untagged-image-is-refused
  ;; `repository/name` means :latest by implication and would walk past a
  ;; suffix-only check for ":latest".
  (is (some #(str/includes? % "explicit image tag")
            (validate/state-errors (fixture :agent-network-server-image "netbirdio/netbird-server")))))

(deftest versions-are-exact
  (doseq [k [:agent-network-claude-code-version :agent-network-netbird-client-version]]
    (is (some #(str/includes? % "exact x.y.z")
              (validate/state-errors (fixture k "2.x"))) (str k))))

(deftest profile-overlay-is-refused
  (is (seq (validate/env-errors {"COLORS_PAR_PROFILE" "other"})))
  (is (nil? (validate/env-errors {}))))

;; --- credentials -----------------------------------------------------------

(deftest a-create-names-every-operator-secret
  (let [errors (str/join "\n" (validate/secret-errors (fixture) :create))]
    (doseq [name ["COLORS_PAR_VULTR_API_KEY" "COLORS_PAR_CLOUDFLARE_API_TOKEN"
                  "COLORS_PAR_ANTHROPIC_API_KEY"]]
      (is (str/includes? errors name) name))
    ;; Generated on the host and supplied by nobody.
    (doseq [absent ["RELAY" "SESSION" "ENCRYPTION_KEY" "PROXY_TOKEN"
                    "ADMIN_PASSWORD" "SETUP_KEY" "PAT"]]
      (is (not (str/includes? errors absent)) absent))))

(deftest a-delete-does-not-ask-for-the-anthropic-key
  ;; This deployment is disposable: a delete needs the provider credentials
  ;; alone, and demanding the Anthropic key to destroy a machine would just be
  ;; a lock on the exit.
  (let [errors (str/join "\n" (validate/secret-errors (fixture) :delete))]
    (is (str/includes? errors "COLORS_PAR_VULTR_API_KEY"))
    (is (str/includes? errors "COLORS_PAR_CLOUDFLARE_API_TOKEN"))
    (is (not (str/includes? errors "ANTHROPIC")))))
