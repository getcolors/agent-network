(ns io.github.getcolors.agent-network.validate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.cli :as green-cli]
            [io.github.getcolors.agent-network.validate :as validate]))

(def fixture-file "test/fixtures/colors.yml")
(def optout-file "test/fixtures/optout.yml")
(def do-fixture-file "test/fixtures/colors-digitalocean.yml")
(def do-optout-file "test/fixtures/optout-digitalocean.yml")

(defn- read-fixture [path overrides]
  (merge (green-cli/read-state path (str/replace (slurp path) "WORKDIR" ".colors"))
         overrides))
(defn fixture [& {:as overrides}] (read-fixture fixture-file overrides))
(defn optout [& {:as overrides}] (read-fixture optout-file overrides))
(defn do-fixture [& {:as overrides}] (read-fixture do-fixture-file overrides))
(defn do-optout [& {:as overrides}] (read-fixture do-optout-file overrides))

(deftest fixture-is-valid (is (= [] (validate/state-errors (fixture)))))
(deftest optout-fixture-is-valid (is (= [] (validate/state-errors (optout)))))

(deftest digitalocean-fixtures-are-valid
  (is (= [] (validate/state-errors (do-fixture))))
  (is (= [] (validate/state-errors (do-optout)))))

;; --- the compute-provider registry (Compute Provider Standard) --------------

(deftest unsupported-provider-names-the-advertised-ones
  (is (some #{":provider-compute must be one of digitalocean, vultr"}
            (validate/state-errors (fixture :provider-compute "hetzner")))))

(deftest required-keys-follow-the-selected-provider
  (is (some #{":digitalocean-size is required"}
            (validate/state-errors (do-fixture :digitalocean-size nil))))
  (is (some #{":digitalocean-stun-sources is required"}
            (validate/state-errors (do-fixture :digitalocean-stun-sources nil))))
  (is (some #{":vultr-plan is required"}
            (validate/state-errors (fixture :vultr-plan nil))))
  ;; The other provider's keys are neither required nor refused, so one
  ;; colors.yml can carry both and move between providers by one edit.
  (is (not-any? #(str/includes? % "vultr") (validate/state-errors (do-fixture))))
  (is (= [] (validate/state-errors (fixture :digitalocean-region "ams3"
                                            :digitalocean-size "s-1vcpu-1gb"))))
  (is (= [] (validate/state-errors (do-fixture :vultr-os-id "not-checked-here")))))

(deftest name-and-machine-key-are-never-required
  (doseq [errors [(validate/state-errors (fixture :vultr-name nil))
                  (validate/state-errors (do-fixture))]]
    (is (not-any? #(str/includes? % "-name") errors))
    (is (not-any? #(str/includes? % "-ssh-keys") errors))))

(deftest vultr-os-id-is-checked-on-vultr-only
  (is (some #{":vultr-os-id must be Vultr's numeric operating-system id"}
            (validate/state-errors (fixture :vultr-os-id "2284"))))
  (is (= [] (validate/state-errors (do-fixture :vultr-os-id "2284")))))

(deftest digitalocean-refuses-a-pinned-or-created-vpc
  (let [errors (validate/state-errors (do-fixture :digitalocean-vpc-uuid "abc"
                                                  :digitalocean-vpc-cidr "10.0.0.0/16"))]
    (is (some #(str/starts-with? % ":digitalocean-vpc-uuid must be absent") errors))
    (is (some #(str/starts-with? % ":digitalocean-vpc-cidr must be absent") errors)))
  ;; An unselected provider's keys are ignored, VPC keys included.
  (is (= [] (validate/state-errors (fixture :digitalocean-vpc-uuid "abc")))))

(deftest compute-key-is-provider-scoped
  (is (= :vultr-ssh-sources (validate/compute-key (fixture) "ssh-sources")))
  (is (= :digitalocean-stun-sources (validate/compute-key (do-fixture) "stun-sources"))))

(deftest the-name-override-is-read-from-the-selected-provider-alone
  (is (= "agent-network-digitalocean-fixture" (validate/compute-name (do-fixture))))
  (is (= "agent-network-digitalocean-optout" (validate/compute-name (do-optout))))
  (is (= "agent-network-digitalocean-fixture"
         (validate/compute-name (do-fixture :vultr-name "custom-label"))))
  (is (= "droplet-01" (validate/compute-name (do-fixture :digitalocean-name "droplet-01")))))

(deftest the-name-override-follows-the-providers-rules
  ;; Vultr labels are console text; DigitalOcean droplet names are hostnames,
  ;; so an underscore that Vultr accepts fails at DigitalOcean.
  (is (some #{":vultr-name must be a safe 1-63 character name"}
            (validate/state-errors (fixture :vultr-name (apply str (repeat 64 "a"))))))
  (is (= [] (validate/state-errors (fixture :vultr-name "invalid_name"))))
  (let [err ":digitalocean-name must be a hostname-like name: lowercase letters, digits, dots and hyphens, 1-63 characters"]
    (doseq [bad ["invalid_name" "Upper" "-leading" (apply str (repeat 64 "a"))]]
      (is (some #{err} (validate/state-errors (do-fixture :digitalocean-name bad))) bad))
    (is (= [] (validate/state-errors (do-fixture :digitalocean-name "agent.net-01"))))))

(deftest the-resolved-name-is-validated-when-the-profile-is-the-name
  ;; With no override the profile *is* the machine name, so it is held to the
  ;; selected provider's rule too; the error names where the value came from.
  (let [err ":profile (the digitalocean machine name) must be a hostname-like name: lowercase letters, digits, dots and hyphens, 1-63 characters"]
    (is (some #{err} (validate/state-errors (do-fixture :profile "Prod_Name"))))
    ;; Vultr accepts the same profile as a label.
    (is (= [] (validate/state-errors (fixture :profile "Prod_Name"))))
    ;; A valid override shadows an invalid profile.
    (is (= [] (validate/state-errors (do-fixture :profile "Prod_Name" :digitalocean-name "droplet-01"))))
    ;; An invalid override reports the override's key, not the profile's.
    (let [errors (validate/state-errors (do-fixture :profile "Prod_Name" :digitalocean-name "Bad_Name"))]
      (is (some #(str/starts-with? % ":digitalocean-name must be") errors))
      (is (not-any? #(str/starts-with? % ":profile") errors)))
    ;; A missing profile is `is required` alone, never a name error.
    (let [errors (validate/state-errors (do-fixture :profile nil))]
      (is (some #{":profile is required"} errors))
      (is (not-any? #(str/includes? % "machine name") errors)))))

;; --- the network contract --------------------------------------------------

(deftest cidr-syntax
  (doseq [ok ["0.0.0.0/0" "10.0.0.0/8" "203.0.113.7/32" "::/0" "2001:db8::/32"
              "fe80::1/128" "2001:db8:0:0:0:0:0:1/64"
              ;; IPv4-embedded: a dotted quad in last position stands for two groups.
              "::ffff:192.0.2.1/128" "64:ff9b::192.0.2.33/96" "1:2:3:4:5:6:192.0.2.1/128"]]
    (is (validate/cidr? ok) ok))
  (doseq [bad ["10.0.0.0" "10.0.0.256/8" "10.0.0.0/33" "2001:db8::/129" "example.com/24"
               "1:::2/64" "2001:db8::1::2/64" "1:2:3:4:5:6:7:8:9/64" "" "/24" "10.0.0.0/8/8"
               ;; a bad quad, a short quad, too many groups, a quad not in last position
               "::ffff:192.0.2.256/128" "::ffff:192.0.2/128" "1:2:3:4:5:6:7:192.0.2.1/128"
               "192.0.2.1::/64" "::ffff:192.0.2.1:1/128"]]
    (is (not (validate/cidr? bad)) bad)))

(deftest ssh-sources-must-not-be-empty
  (is (some #{":vultr-ssh-sources must list at least one CIDR"}
            (validate/state-errors (fixture :vultr-ssh-sources []))))
  (is (some #{":digitalocean-ssh-sources must list at least one CIDR"}
            (validate/state-errors (do-fixture :digitalocean-ssh-sources " , "))))
  ;; No public HTTP, or no public STUN, is a legitimate deployment.
  (is (= [] (validate/state-errors (fixture :vultr-http-sources []))))
  (is (= [] (validate/state-errors (fixture :vultr-stun-sources []))))
  (is (= [] (validate/state-errors (do-fixture :digitalocean-http-sources []))))
  (is (= [] (validate/state-errors (do-fixture :digitalocean-stun-sources [])))))

(deftest malformed-sources-are-refused-before-any-provider-call
  (is (some #{":vultr-http-sources entry \"10.0.0.0\" is not an IPv4 or IPv6 CIDR"}
            (validate/state-errors (fixture :vultr-http-sources ["0.0.0.0/0" "10.0.0.0"]))))
  (is (some #{":vultr-stun-sources entry \"stun.example.com/32\" is not an IPv4 or IPv6 CIDR"}
            (validate/state-errors (fixture :vultr-stun-sources ["stun.example.com/32"]))))
  (is (some #{":digitalocean-ssh-sources entry \"office.example.com/32\" is not an IPv4 or IPv6 CIDR"}
            (validate/state-errors (do-fixture :digitalocean-ssh-sources "office.example.com/32"))))
  ;; Only the selected provider's lists are checked.
  (is (= [] (validate/state-errors (do-fixture :vultr-ssh-sources ["garbage"])))))

;; --- provider switching is a rebuild ---------------------------------------

(deftest provider-state-is-compared-with-the-selection
  (is (nil? (validate/provider-state-errors (fixture) nil)))
  (is (nil? (validate/provider-state-errors (fixture) {:provider "vultr" :ip "203.0.113.9"})))
  (is (nil? (validate/provider-state-errors (do-fixture) {:provider "digitalocean"})))
  (is (= ["state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"]
         (validate/provider-state-errors (fixture) {:provider "digitalocean" :ip "203.0.113.9"})))
  (is (= ["state holds a vultr machine; set provider-compute back to vultr and delete first"]
         (validate/provider-state-errors (do-fixture) {:provider "vultr"}))))

(deftest legacy-state-without-a-provider-is-the-default-providers
  ;; Every deployment created before adoption recorded no provider and runs
  ;; the only provider the package ever offered.
  (is (nil? (validate/provider-state-errors (fixture) {:ip "203.0.113.9"})))
  (let [[err] (validate/provider-state-errors (do-fixture) {:ip "203.0.113.9"})]
    (is (str/includes? err "no recorded provider"))
    (is (str/includes? err "set provider-compute back to vultr and delete first"))))

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
                         :provider-dns "other" :provider-compute "hetzner"
                         :agent-network-log-retention-days 0
                         :agent-network-stun-port 70000
                         :agent-network-gateway-subnet "nonsense"
                         :agent-network-claude-code-version "latest"))]
    (is (<= 8 (count errors)))
    (doseq [part ["host" "image" "letsencrypt-email" "provider-dns" "provider-compute"
                  "retention-days" "stun-port" "gateway-subnet"
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
      (is (not (str/includes? errors absent)) absent))
    (is (not (str/includes? errors "COLORS_PAR_DO_TOKEN")))))

(deftest secrets-and-tofu-env-follow-the-selected-provider
  (let [errors (str/join "\n" (validate/secret-errors (do-fixture) :create))]
    (is (str/includes? errors "COLORS_PAR_DO_TOKEN"))
    (is (str/includes? errors "COLORS_PAR_CLOUDFLARE_API_TOKEN"))
    (is (str/includes? errors "COLORS_PAR_ANTHROPIC_API_KEY"))
    (is (not (str/includes? errors "COLORS_PAR_VULTR_API_KEY"))))
  (is (= {:do-token "DIGITALOCEAN_TOKEN"} (validate/tofu-env (do-fixture) :provider-compute)))
  (is (= {:vultr-api-key "VULTR_API_KEY"} (validate/tofu-env (fixture) :provider-compute)))
  (is (= {} (validate/tofu-env (fixture :provider-compute "hetzner") :provider-compute))))

(deftest a-delete-does-not-ask-for-the-anthropic-key
  ;; This deployment is disposable: a delete needs the provider credentials
  ;; alone, and demanding the Anthropic key to destroy a machine would just be
  ;; a lock on the exit.
  (let [errors (str/join "\n" (validate/secret-errors (fixture) :delete))]
    (is (str/includes? errors "COLORS_PAR_VULTR_API_KEY"))
    (is (str/includes? errors "COLORS_PAR_CLOUDFLARE_API_TOKEN"))
    (is (not (str/includes? errors "ANTHROPIC")))))
