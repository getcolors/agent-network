(ns io.github.getcolors.agent-network.validate
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [green.cli :as green-cli]
            [io.github.getcolors.once.ssh :as once-ssh]
            [io.github.getcolors.once.utils :as once-utils]
            [io.github.getcolors.once.validate :as once-validate]))

(def profile-par (green-cli/par-name :profile))

(def compute-providers
  "provider-compute -> what that choice implies.

  `:required` are the non-secret keys that provider's template interpolates,
  `:secrets` the credentials it needs through COLORS_PAR_*, and `:tofu-env` the
  subset OpenTofu reads from the process environment itself. Keeping the three
  together is what stops a provider being validated against one set of keys and
  run with another — a stage exporting a credential nobody checked for, or a
  check demanding a key no template uses. The keys of this map are the
  advertised providers; a provider without a template directory and a golden
  is not advertised.

  Three source lists rather than the standard's two, because this package
  publishes STUN over UDP beside 22/80/443 and the firewall on every provider
  mirrors that rule.

  Two keys the templates read are deliberately not required. `<provider>-name`
  is an optional override of the profile (Compute Name Standard), and
  `<provider>-ssh-keys` is meaningful by its absence (SSH Keypair Standard).
  Keys of the unselected provider are accepted and ignored, so one colors.yml
  stays portable between providers."
  {"digitalocean"
   {:required [:digitalocean-region :digitalocean-size :digitalocean-image
               :digitalocean-ssh-sources :digitalocean-http-sources
               :digitalocean-stun-sources]
    :secrets [:do-token]
    :tofu-env {:do-token "DIGITALOCEAN_TOKEN"}}
   "vultr"
   {:required [:vultr-region :vultr-plan :vultr-os-id
               :vultr-ssh-sources :vultr-http-sources :vultr-stun-sources]
    :secrets [:vultr-api-key]
    :tofu-env {:vultr-api-key "VULTR_API_KEY"}}})

(def default-compute-provider
  "The provider a deployment created before this package recorded one in its
  compute output must be running: the only one it ever offered."
  "vultr")

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
(def name-rules
  "What each provider accepts as a machine name, checked here rather than
  discovered mid-apply. DigitalOcean droplet names are hostname-like; Vultr
  labels are free-form console text, held to a safe subset."
  {"digitalocean" {:re #"^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$"
                   :message "must be a hostname-like name: lowercase letters, digits, dots and hyphens, 1-63 characters"}
   "vultr" {:re #"^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$"
            :message "must be a safe 1-63 character name"}})

(defn missing? [x] (or (nil? x) (and (string? x) (str/blank? x))))

(defn placeholder?
  "Absent, blank or REPLACE_ME all mean 'use the profile' (Compute Name
  Standard §2: presence is the only switch)."
  [v]
  (or (missing? v) (= "REPLACE_ME" (str/trim (str v)))))

(defn compute-provider [opts] (get compute-providers (:provider-compute opts)))

(defn compute-key
  "Desired state names compute keys after the provider, so the shared steps
  reach them through the selected provider rather than a fixed prefix."
  [opts suffix]
  (keyword (str (:provider-compute opts) "-" suffix)))

(defn compute-name
  "What this deployment calls its machine. The one function that answers it —
  every label, including the firewall's, derives from this and never from the
  raw override key or a second copy of the profile (§3). The override is read
  from the selected provider's `<provider>-name` alone."
  [opts]
  (let [override (get opts (compute-key opts "name"))]
    (if (placeholder? override) (str (:profile opts)) (str/trim (str override)))))

(defn keygen?
  "Whether this deployment owns its machine keypair. Delegates to ONCE, the
  standard's reference implementation, so one rule decides it everywhere."
  [opts]
  (once-ssh/keygen? opts))

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

(defn cidrs
  "A source list as desired state or an overlay string carries it: a YAML
  list, or one string of comma- or space-separated entries."
  [opts k]
  (let [v (get opts k) xs (if (sequential? v) v (str/split (str v) #"[,\s]+"))]
    (->> xs (map (comp str/trim str)) (remove str/blank?) vec)))

;; Syntactic CIDR checks, the same in every colour and deliberately not a
;; resolver: an address library that accepts a hostname would let a firewall
;; source depend on DNS at apply time.
(def ^:private ipv4-re
  #"^(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$")
(def ^:private hex-group-re #"^[0-9A-Fa-f]{1,4}$")

(defn- fold-ipv4-tail
  "An IPv4-embedded address (`::ffff:192.0.2.1`, `64:ff9b::192.0.2.33`)
  carries a dotted quad in last position only. It stands for two 16-bit
  groups, so it is checked as IPv4 and folded into two zero groups before the
  group arithmetic; nil when the tail is dotted but not an IPv4 address. A
  dotted quad anywhere else falls through to the hex-group check and fails
  there."
  [s]
  (let [i (str/last-index-of s ":")
        tail (if i (subs s (inc i)) s)]
    (cond
      (not (str/includes? tail ".")) s
      (and i (re-matches ipv4-re tail)) (str (subs s 0 (inc i)) "0:0")
      :else nil)))

(defn- ipv6-address? [raw]
  (when-let [s (fold-ipv4-tail raw)]
    (let [groups (fn [part] (if (str/blank? part) [] (str/split part #":" -1)))]
      (if (str/includes? s "::")
        (let [halves (str/split s #"::" -1)]
          (and (= 2 (count halves))
               (let [gs (mapcat groups halves)]
                 (and (<= (count gs) 7) (every? #(re-matches hex-group-re %) gs)))))
        (let [gs (groups s)]
          (and (= 8 (count gs)) (every? #(re-matches hex-group-re %) gs)))))))

(defn cidr?
  "Whether `s` is a syntactically valid IPv4 or IPv6 CIDR: an address, a
  slash, and a prefix length the address family allows."
  [s]
  (let [[address prefix & more] (str/split (str s) #"/" -1)]
    (and (nil? more) (some? prefix) (re-matches #"^\d{1,3}$" prefix)
         (let [n (Long/parseLong prefix)]
           (cond
             (re-matches ipv4-re address) (<= 0 n 32)
             (ipv6-address? address) (<= 0 n 128)
             :else false)))))

(defn source-errors
  "The network contract: the selected provider's SSH sources must name at
  least one CIDR — a machine nobody can reach is not a deployment — and every
  entry of all three lists must be one. An empty HTTP list is allowed and
  means no public HTTP; an empty STUN list means no public STUN. Refusing
  beats defaulting: a silent default-open in front of a control plane is worse
  than a validation error."
  [opts]
  (let [ssh-key (compute-key opts "ssh-sources")
        http-key (compute-key opts "http-sources")
        stun-key (compute-key opts "stun-sources")]
    (concat
     (when (and (not (missing? (get opts ssh-key))) (empty? (cidrs opts ssh-key)))
       [(str ssh-key " must list at least one CIDR")])
     (for [k [ssh-key http-key stun-key]
           :when (not (missing? (get opts k)))
           entry (cidrs opts k)
           :when (not (cidr? entry))]
       (str k " entry " (pr-str entry) " is not an IPv4 or IPv6 CIDR")))))

(defn provider-errors
  "Checks that hold only for the selected provider. Keys of the other provider
  are ignored, never refused. The *resolved* machine name is validated against
  the provider's rules rather than passed through unread (Compute Name
  Standard §2): an override is checked as itself, and a profile that falls
  through as the name is checked too, because a profile Vultr accepts as a
  label can be a droplet name DigitalOcean refuses mid-apply. The error names
  the key the value came from. A blank resolved value is skipped, so a missing
  profile reports `is required` alone."
  [opts]
  (let [name-key (compute-key opts "name")
        {:keys [re message]} (get name-rules (:provider-compute opts))
        override? (not (placeholder? (get opts name-key)))
        name (compute-name opts)
        source (if override?
                 (str name-key)
                 (str ":profile (the " (:provider-compute opts) " machine name)"))]
    (concat
     (when (and re (not (str/blank? name))
                (or (> (count name) 63) (not (re-matches re name))))
       [(str source " " message)])
     (case (:provider-compute opts)
       "vultr"
       (when-not (or (missing? (:vultr-os-id opts)) (integer? (:vultr-os-id opts)))
         [":vultr-os-id must be Vultr's numeric operating-system id"])
       "digitalocean"
       (concat
        ;; No VPC is created: the region's default is discovered at plan time,
        ;; and a pinned UUID or a CIDR would make this package start owning one.
        (when (contains? opts :digitalocean-vpc-uuid)
          [":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime"])
        (when (contains? opts :digitalocean-vpc-cidr)
          [":digitalocean-vpc-cidr must be absent; this package must not create a VPC"]))
       nil))))

(defn env-errors [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set; profile must come from colors.yml only")]))

(defn state-errors [opts]
  (vec
   (concat
    (for [k (concat required (:required (compute-provider opts)))
          :when (missing? (get opts k))]
      (str k " is required"))
    (when-not (compute-provider opts)
      [(str ":provider-compute must be one of "
            (str/join ", " (sort (keys compute-providers))))])
    (when-not (= "cloudflare" (:provider-dns opts))
      [":provider-dns must be cloudflare"])
    (when-not (contains? #{"local" "s3" "r2"} (:provider-backend opts))
      [":provider-backend must be local, s3, or r2"])
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
    (when (compute-provider opts)
      (concat (provider-errors opts) (source-errors opts))))))

(defn provider-state-errors
  "Provider switching is a rebuild, never an apply. Every provider shares one
  state key, so a changed provider-compute on a profile whose state already
  holds compute would plan a cross-provider replacement — and a delete would
  render and destroy the *selected* provider's template against the wrong
  lifecycle. `params` is the compute stage's recorded output, or nil when the
  state holds none; its `provider` is the registry name the template that
  produced it belongs to. A recorded output without one predates this package
  recording it, which makes it the default provider's."
  [opts params]
  (let [selected (:provider-compute opts)
        recorded (some-> (:provider params) str not-empty)]
    (cond
      (nil? params) nil

      (and recorded (not= recorded selected))
      [(str "state holds a " recorded " machine; set provider-compute back to "
            recorded " and delete first")]

      (and (nil? recorded) (not= selected default-compute-provider))
      [(str "state holds a machine with no recorded provider, created before this "
            "package recorded one, which makes it a " default-compute-provider
            " machine; set provider-compute back to " default-compute-provider
            " and delete first")]

      :else nil)))

(defn backend-secrets [opts]
  (:secrets (get-in once-validate/providers
                    [:provider-backend (:provider-backend opts)])))

(defn provider-secrets
  "What talking to the providers needs, on any real event: the selected
  compute provider's credential and Cloudflare's."
  [opts]
  (concat (:secrets (compute-provider opts)) [:cloudflare-api-token]))

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
    :provider-compute (:tofu-env (compute-provider opts) {})
    :provider-dns {:cloudflare-api-token "CLOUDFLARE_API_TOKEN"}
    :provider-backend (:tofu-env (get-in once-validate/providers
                                         [:provider-backend (:provider-backend opts)]) {})
    {}))
