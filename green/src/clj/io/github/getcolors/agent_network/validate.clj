(ns io.github.getcolors.agent-network.validate
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [green.cli :as green-cli]
            [io.github.getcolors.agent-network.compute :as compute] [io.github.getcolors.compute :as library] [io.github.getcolors.compute-planning :as planning]
            [io.github.getcolors.once.utils :as once-utils]
            [io.github.getcolors.once.validate :as once-validate]))

(def profile-par (green-cli/par-name :profile))

(def default-compute-provider "vultr")

(def required
  "Every key desired state must carry whichever provider is selected. The
  provider-scoped keys come from `compute-providers`.

  Two deliberate absences. `<provider>-ssh-keys` selects opt-out mode by being
  present, so requiring it would make every conforming keygen deployment
  invalid. `<provider>-name` is the Compute Name Standard's optional override:
  a fresh colors.yml that omits it is complete and names the machine after the
  profile. There is likewise no `package` key — §5 removes a key that can hold
  exactly one value."
  [:profile :workdir :provider-compute :provider-dns :provider-backend
   :compute-prevent-destroy
   :agent-network-host :agent-network-letsencrypt-email
   :agent-network-admin-email :agent-network-admin-name
   :agent-network-provider-models :agent-network-allowed-models
   :agent-network-policy-budget-usd-per-day :agent-network-policy-tokens-per-day
   :agent-network-global-budget-usd-per-day :agent-network-global-tokens-per-day
   :agent-network-log-retention-days
   :agent-network-stun-port :agent-network-log-level
   :agent-network-gateway-subnet :agent-network-agent-subnet
   :agent-network-server-image :agent-network-dashboard-image
   :agent-network-proxy-image :agent-network-traefik-image
   :agent-network-agent-base-image
   :agent-network-claude-code-version :agent-network-netbird-client-version
   :agent-network-lego-version
   :r2-bucket :r2-endpoint])

(def image-keys
  [:agent-network-server-image :agent-network-dashboard-image
   :agent-network-proxy-image :agent-network-traefik-image
   :agent-network-agent-base-image])

(def host-re #"^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")
(def email-re #"^[^@\s]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")
;; An explicit tag or digest is mandatory. A bare `repository/name` means
;; `:latest` by implication and would walk straight past a suffix check for
;; ":latest", which is why the shape is required rather than the suffix denied.
;; `tag@sha256:...` — the shape every image key here actually carries — pins
;; both the human-readable version and the exact bytes.
(def image-re #"^[^\s:@]+(?:/[^\s:@]+)*(?::[^\s:@]+)?(?:@sha256:[0-9a-f]{64})?$")
(def image-pinned-re #"^[^\s@]+(?::[^\s:@]+@sha256:[0-9a-f]{64}|:[^\s:@]+|@sha256:[0-9a-f]{64})$")
(def cidr-re #"^(?:\d{1,3}\.){3}\d{1,3}/\d{1,2}$")
(def version-re #"^[0-9]+\.[0-9]+\.[0-9]+$")
(def model-id-re #"^[A-Za-z0-9][A-Za-z0-9._:-]*$")

(defn missing? [x] (or (nil? x) (and (string? x) (str/blank? x))))

(defn compute-name [opts] (get-in (planning/plan-deployment opts compute/topology (compute/requirements opts)) [:cluster :nodes 0 :name]))
(defn keygen? [opts] (= "managed" (get-in (planning/plan-deployment opts compute/topology (compute/requirements opts)) [:key :mode])))
(defn cidrs [opts key] (let [v (get opts key)] (if (sequential? v) (vec v) (vec (remove str/blank? (str/split (str v) #"[,\s]+"))))))

(defn subnet-ip
  "A fixed address inside `subnet`, derived rather than configured: a value
  that can only correctly be `<subnet>.N` is a transcription step, and
  transcription drifts."
  [subnet n]
  (let [base (first (str/split (str subnet) #"/"))
        octets (str/split base #"\.")]
    (when (= 4 (count octets))
      (str/join "." (concat (take 3 octets) [(str n)])))))

;; The attachment matrix, each address derived once. On `gateway-net` Traefik
;; holds .10 — the proxy's NB_PROXY_TRUSTED_PROXIES needs it, exactly as the
;; upstream installer wires TRAEFIK_IP. On `agent-net` Traefik holds .10 (the
;; agent's management/signal/relay bootstrap lands here via extra_hosts), the
;; reverse proxy .11 (the WireGuard leg), and the agent .20. These four are the
;; whole reachable surface of the isolated network, and the DOCKER-USER allow
;; list is written from them.
(defn traefik-ip [opts] (subnet-ip (:agent-network-gateway-subnet opts) 10))
(defn traefik-agent-ip [opts] (subnet-ip (:agent-network-agent-subnet opts) 10))
(defn proxy-agent-ip [opts] (subnet-ip (:agent-network-agent-subnet opts) 11))
(defn agent-ip [opts] (subnet-ip (:agent-network-agent-subnet opts) 20))

(defn zone
  "The Cloudflare zone the host and its wildcard belong to."
  [opts]
  (once-utils/registrable-domain (:agent-network-host opts)))

(defn provider-models
  "The models the Anthropic provider claims, keywordized however YAML handed
  them over."
  [opts]
  (mapv walk/keywordize-keys (:agent-network-provider-models opts)))

(defn allowed-models [opts]
  (mapv str (:agent-network-allowed-models opts)))

(defn allowed-model
  "The model every Claude Code knob is pinned to."
  [opts]
  (first (allowed-models opts)))

(defn denied-claimed-model
  "A model the provider claims but the guardrail does not allow — the
  guardrail-denial probe's negative case. Its existence is validated, so
  acceptance can rely on it."
  [opts]
  (let [allowed (set (allowed-models opts))]
    (some #(when-not (contains? allowed (str (:id %))) (str (:id %)))
          (provider-models opts))))

(defn subnet-overlap?
  "Whether two /24-or-smaller IPv4 CIDRs share addresses. Deliberately simple:
  both subnets here are package-shaped (a.b.c.0/nn with nn >= 16), and a
  byte-precise comparison over that shape beats a dependency."
  [a b]
  (letfn [(parts [cidr]
            (let [[ip bits] (str/split (str cidr) #"/")
                  octets (mapv #(Long/parseLong %) (str/split ip #"\."))
                  n (Long/parseLong bits)
                  addr (reduce (fn [acc o] (+ (* acc 256) o)) 0 octets)
                  mask (bit-shift-left -1 (- 32 n))]
              [(bit-and addr mask) mask]))]
    (let [[a-net a-mask] (parts a) [b-net b-mask] (parts b)
          mask (if (> (Long/bitCount (bit-and a-mask 0xffffffff))
                      (Long/bitCount (bit-and b-mask 0xffffffff)))
                 b-mask a-mask)]
      (= (bit-and a-net mask) (bit-and b-net mask)))))

(defn pos-num? [x] (and (number? x) (pos? x)))

(defn model-errors [opts]
  (let [models (provider-models opts)
        allowed (allowed-models opts)
        claimed (set (map #(str (:id %)) models))]
    (concat
     (when-not (and (sequential? (:agent-network-provider-models opts)) (seq models))
       [":agent-network-provider-models must be a non-empty list"])
     (for [m models
           :when (or (missing? (:id m)) (not (re-matches model-id-re (str (:id m)))))]
       ":agent-network-provider-models entries must carry a model id")
     (for [m models
           :let [in (:input-per-1k m) out (:output-per-1k m)]
           :when (not (and (pos-num? in) (pos-num? out)))]
       (str "model " (:id m) " must carry positive input-per-1k and output-per-1k prices"))
     (when-not (and (sequential? (:agent-network-allowed-models opts)) (seq allowed))
       [":agent-network-allowed-models must be a non-empty list"])
     (for [m allowed :when (not (contains? claimed m))]
       (str ":agent-network-allowed-models entry " m " is not claimed by the provider"))
     ;; The demo's guardrail-denial probe needs a model that routing accepts
     ;; and the allowlist rejects. Without one, gate 3b has no negative case
     ;; and the guardrail is configured but never demonstrated.
     (when (and (seq models) (seq allowed)
                (every? #(contains? (set allowed) (str (:id %))) models))
       [":agent-network-provider-models must claim at least one model outside :agent-network-allowed-models"]))))

(defn env-errors [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set; profile must come from colors.yml only")]))

(defn state-errors
  "Every problem with desired state at once: the missing keys (this package's
  and the selected provider's), the package's own checks, then the Compute
  Provider Standard's — selection, the network contract and the provider
  rules — which are ONCE's over `spec`."
  [opts]
  (vec
   (concat
    (for [k required
          :when (missing? (get opts k))]
      (str k " is required"))
    (when-not (= "cloudflare" (:provider-dns opts))
      [":provider-dns must be cloudflare"])
    (when-not (boolean? (:compute-prevent-destroy opts))
      [":compute-prevent-destroy must be true or false"])
    (when (and (not (missing? (:agent-network-host opts)))
               (not (re-matches host-re (str (:agent-network-host opts)))))
      [":agent-network-host must be a fully qualified hostname"])
    (for [k [:agent-network-letsencrypt-email :agent-network-admin-email]
          :let [v (get opts k)]
          :when (and (not (missing? v)) (not (re-matches email-re (str v))))]
      (str k " must be an email address"))
    (for [k image-keys
          :let [v (get opts k)]
          :when (and (not (missing? v)) (not (re-matches image-pinned-re (str v))))]
      (str k " must carry an explicit image tag or digest"))
    ;; This package owns its templates rather than following the upstream
    ;; installer, so nothing tells it when a floating tag moved underneath it.
    (for [k image-keys
          :let [v (str (get opts k))]
          :when (or (str/ends-with? v ":latest") (str/ends-with? v ":main")
                    (str/includes? v ":latest@") (str/includes? v ":main@"))]
      (str k " must not track a floating tag; pin the version"))
    (for [k [:agent-network-claude-code-version :agent-network-netbird-client-version
             :agent-network-lego-version]
          :let [v (get opts k)]
          :when (and (not (missing? v)) (not (re-matches version-re (str v))))]
      (str k " must be an exact x.y.z version"))
    (when-not (or (missing? (:agent-network-stun-port opts))
                  (and (integer? (:agent-network-stun-port opts))
                       (< 0 (:agent-network-stun-port opts) 65536)))
      [":agent-network-stun-port must be a port number"])
    (for [k [:agent-network-gateway-subnet :agent-network-agent-subnet]
          :let [v (get opts k)]
          :when (and (not (missing? v)) (not (re-matches cidr-re (str v))))]
      (str k " must be a CIDR block"))
    ;; Deterministic build-time validation only (the workstation cannot know
    ;; the target host's routes; converge re-checks there before creating the
    ;; networks). Overlapping subnets would let one compose network shadow the
    ;; other and silently break both the isolation boundary and the firewall
    ;; allow list derived from static addresses.
    (when (and (re-matches cidr-re (str (:agent-network-gateway-subnet opts)))
               (re-matches cidr-re (str (:agent-network-agent-subnet opts)))
               (subnet-overlap? (:agent-network-gateway-subnet opts)
                                (:agent-network-agent-subnet opts)))
      [":agent-network-gateway-subnet and :agent-network-agent-subnet must not overlap"])
    (when-not (or (missing? (:agent-network-log-level opts))
                  (contains? #{"error" "warn" "info" "debug"}
                             (str (:agent-network-log-level opts))))
      [":agent-network-log-level must be error, warn, info, or debug"])
    ;; 7-90 mirrors the dashboard's own retention range; usage metering is
    ;; unconditional and unaffected.
    (when-not (or (missing? (:agent-network-log-retention-days opts))
                  (and (integer? (:agent-network-log-retention-days opts))
                       (<= 7 (:agent-network-log-retention-days opts) 90)))
      [":agent-network-log-retention-days must be an integer between 7 and 90"])
    (for [k [:agent-network-policy-budget-usd-per-day
             :agent-network-policy-tokens-per-day
             :agent-network-global-budget-usd-per-day
             :agent-network-global-tokens-per-day]
          :let [v (get opts k)]
          :when (and (not (missing? v)) (not (pos-num? v)))]
      (str k " must be a positive number"))
    ;; The global rule is the backstop: a policy cap above it would never bind
    ;; and the desired state would be lying about which limit is the ceiling.
    (when (and (pos-num? (:agent-network-policy-budget-usd-per-day opts))
               (pos-num? (:agent-network-global-budget-usd-per-day opts))
               (> (:agent-network-policy-budget-usd-per-day opts)
                  (:agent-network-global-budget-usd-per-day opts)))
      [":agent-network-policy-budget-usd-per-day must not exceed the global budget"])
    (when (and (pos-num? (:agent-network-policy-tokens-per-day opts))
               (pos-num? (:agent-network-global-tokens-per-day opts))
               (> (:agent-network-policy-tokens-per-day opts)
                  (:agent-network-global-tokens-per-day opts)))
      [":agent-network-policy-tokens-per-day must not exceed the global token cap"])
    (when (seq (remove missing? [(:agent-network-provider-models opts)
                                 (:agent-network-allowed-models opts)]))
      (model-errors opts))
    (compute/errors opts))))

(defn backend-secrets [opts]
  (:secrets (get-in library/registry
                    [:backend (keyword (:provider-backend opts))])))

(defn provider-secrets
  "What talking to the providers needs, on any real event: the selected
  compute provider's credential and Cloudflare's."
  [opts]
  [:cloudflare-api-token])

(def application-secrets
  "What converging the machine needs, and therefore only a create.

  One entry, deliberately. Everything else this deployment holds is generated
  on the host and supplied by nobody: the relay auth secret, the datastore
  encryption key, the session cookie key, the proxy access token, the local
  admin password, the durable automation token, and the agent's one-off setup
  key. The Anthropic key is the exception because it authenticates against an
  account this host does not own; it is handed to NetBird's encrypted store at
  converge time and the agent container never sees it."
  [:anthropic-api-key])

(defn secret-errors
  "Credentials a real event needs. A delete tears down infrastructure with the
  provider credentials alone: this deployment is disposable by design, holds
  nothing worth a final archive, and demanding the Anthropic key to destroy a
  machine would just be a lock on the exit."
  [opts event]
  (let [keys (concat (provider-secrets opts)
                     (case event
                       :create application-secrets
                       [])
                     (backend-secrets opts))]
    (for [k (distinct keys) :when (missing? (get opts k))]
      (str "required credential is not set: " (green-cli/par-name k)))))

(defn tofu-env [opts slot]
  (case slot
    :provider-compute {}
    :provider-dns {:cloudflare-api-token "CLOUDFLARE_API_TOKEN"}
    :provider-backend (if (= "r2" (:provider-backend opts)) {:r2-access-key-id "AWS_ACCESS_KEY_ID" :r2-secret-access-key "AWS_SECRET_ACCESS_KEY"} {})
    {}))
