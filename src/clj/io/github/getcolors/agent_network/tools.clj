(ns io.github.getcolors.agent-network.tools
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [green.ansible :as ansible]
            [green.cli :as green-cli]
            [green.process :as process]
            [green.scaffold :as sc]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.agent-network.ssh :as ssh]
            [io.github.getcolors.agent-network.ssh-config :as ssh-config]
            [io.github.getcolors.agent-network.validate :as validate]))

(def infrastructure-tool "agent-network-infrastructure")
(def dns-tool "agent-network-dns")
(def ansible-tool "agent-network-ansible")
(def ansible-local-tool "agent-network-ansible-local")
(def root "io.github.getcolors.agent-network.tools")
(def template-opts sc/preserve-jinja-delimiters)

(defn tool-dir [opts tool] (green-cli/stage-dir opts tool {:default-profile "agent-network"}))
(defn template [path file] (keyword (str root "." path) file))
(defn spec [source target data] {:template source :target target :data data :opts template-opts})
(defn raw-spec [target content] (sc/content-spec target content))

(defn cidrs [opts k]
  (let [v (get opts k) xs (if (sequential? v) v (str/split (str v) #"[,\s]+"))]
    (->> xs (map (comp str/trim str)) (remove str/blank?) vec)))

(defn credential-env [opts & slots]
  (not-empty
   (into {} (keep (fn [[k env-var]]
                    (when-let [v (not-empty (str (get opts k)))] [env-var v])))
         (apply merge (map #(validate/tofu-env opts %) (conj (vec slots) :provider-backend))))))
(defn backend-credential-env [opts] (credential-env opts))

(defn fallback-params [opts]
  {:ip "192.0.2.10" :user "root" :sudoer "root" :name (validate/compute-name opts)})
(defn output-params [result]
  (some-> (get-in result [:tofu/outputs :params]) walk/keywordize-keys))

;; ---------------------------------------------------------------- compute

(defn infrastructure-data [opts]
  (assoc opts
         :ssh-keygen (validate/keygen? opts)
         :compute-name (validate/compute-name opts)
         :ssh-sources-hcl (tofu/hcl-list (cidrs opts :vultr-ssh-sources))
         :http-sources-hcl (tofu/hcl-list (cidrs opts :vultr-http-sources))
         :stun-sources-hcl (tofu/hcl-list (cidrs opts :vultr-stun-sources))))

(defn infrastructure-step [opts]
  (let [dir (tool-dir opts infrastructure-tool)
        specs [(spec (template "infrastructure" "main.tf") (str dir "/main.tf")
                     (infrastructure-data opts))]
        result (tofu/tofu-with-spec opts specs
                                    {:dir dir :env (credential-env opts :provider-compute)})]
    (cond
      (wf/failed? result) result
      (= :build (:green/event opts)) (merge result (fallback-params opts))
      (= :delete (:green/event opts)) result
      :else (merge result (fallback-params opts) (output-params result)))))

;; -------------------------------------------------------------------- dns

(defn dns-json
  "The base record and its wildcard, both unproxied.

  Unproxied because Cloudflare's proxy is an HTTP proxy: UDP STUN on 3478 does
  not survive it, and both certificate paths — Traefik's TLS-ALPN-01 for the
  base name and the reverse proxy's own ACME for generated endpoint hostnames —
  terminate at the proxy instead of on this host, which breaks issuance.

  The wildcard is not convenience but contract: the agent-network endpoint is
  a hostname management mints one label beneath the base domain when the
  account bootstraps, and nothing knows that label before it exists. A record
  per endpoint would put a converge-time fact into desired state."
  [opts]
  (tofu/constructs-json
   [(tofu/construct :resource :cloudflare_dns_record :agent_network
                    {:zone_id "${data.cloudflare_zone.zone.id}"
                     :name (:agent-network-host opts) :content (:ip opts) :type "A"
                     :proxied false :ttl 60})
    (tofu/construct :resource :cloudflare_dns_record :agent_network_wildcard
                    {:zone_id "${data.cloudflare_zone.zone.id}"
                     :name (str "*." (:agent-network-host opts)) :content (:ip opts)
                     :type "A" :proxied false :ttl 60})]))

(defn dns-step [opts]
  (let [dir (tool-dir opts dns-tool)
        data (assoc opts
                    :ip (or (:ip opts) (:ip (fallback-params opts)))
                    :agent-network-zone (validate/zone opts))
        specs [(spec (template "dns" "main.tf") (str dir "/main.tf") data)
               (raw-spec (str dir "/record.tf.json") (dns-json data))]]
    (tofu/tofu-with-spec opts specs {:dir dir :env (credential-env opts :provider-dns)})))

;; ---------------------------------------------------------- ansible (local)

(defn ansible-local-data
  "Only what a `build` genuinely knows. The address, the user and the alias are
  run-time facts and reach the play as extra-vars instead, so the rendered
  playbook carries no IP and is identical on every workstation (SSH Config
  Standard §6)."
  [opts]
  (assoc opts
         :ssh-keygen (validate/keygen? opts)
         :ssh-config-identity-file (ssh-config/identity-file opts)))

(defn ansible-local-specs [opts]
  (let [dir (tool-dir opts ansible-local-tool) data (ansible-local-data opts)]
    [(spec (template "ansible-local" "ansible.cfg") (str dir "/ansible.cfg") data)
     (spec (template "ansible-local" "inventory.ini") (str dir "/inventory.ini") data)
     (spec (template "ansible-local" "main.yml") (str dir "/main.yml") data)]))

(defn ansible-local-step
  "Write or remove the `~/.ssh/config` block. The same playbook serves both
  events; `block_state` is what distinguishes them."
  [opts]
  (let [dir (tool-dir opts ansible-local-tool)
        delete? (= :delete (:green/event opts))]
    (ansible/ansible-with-spec opts
      {:dir dir :inventory "inventory.ini"
       :playbooks {:create "main.yml" :delete "main.yml"}
       :extra-vars {:host_alias (ssh-config/host-alias opts)
                    :ip (or (:ip opts) (:ip (fallback-params opts)))
                    :user (or (:user opts) "root")
                    :block_state (if delete? "absent" "present")}}
      (ansible-local-specs opts))))

;; ---------------------------------------------------------------- ansible

(defn inventory [opts]
  (json/generate-string
   {:all {:children {:agent-network {:hosts {(:profile opts)
                                             {:ansible_host (or (:ip opts) "192.0.2.10")
                                              :ansible_user "root"}}}}}}
   {:pretty true}))

(defn desired-json
  "The control plane's desired state, one JSON document the host bootstrap
  reconciles against. Everything in it is non-secret — the Anthropic key
  reaches the bootstrap as an environment variable resolved at play time and
  never lands in a rendered file."
  [opts]
  (json/generate-string
   {:host (:agent-network-host opts)
    :admin_email (:agent-network-admin-email opts)
    :admin_name (:agent-network-admin-name opts)
    :provider
    ;; The catalog id, from GET /api/agent-network/catalog/providers on the
    ;; pinned release — "anthropic" alone is a 422.
    {:provider_id "anthropic_api"
     :name "Anthropic"
     :upstream_url "https://api.anthropic.com"
     :models (for [m (validate/provider-models opts)]
               (cond-> {:id (str (:id m))
                        :input_per_1k (:input-per-1k m)
                        :output_per_1k (:output-per-1k m)}
                 (some? (:cache-read-per-1k m))
                 (assoc :cache_read_per_1k (:cache-read-per-1k m))
                 (some? (:cache-creation-per-1k m))
                 (assoc :cache_creation_per_1k (:cache-creation-per-1k m))))}
    :allowed_models (validate/allowed-models opts)
    :policy {:budget_usd_per_day (:agent-network-policy-budget-usd-per-day opts)
             :tokens_per_day (:agent-network-policy-tokens-per-day opts)}
    :global {:budget_usd_per_day (:agent-network-global-budget-usd-per-day opts)
             :tokens_per_day (:agent-network-global-tokens-per-day opts)}
    :log_retention_days (:agent-network-log-retention-days opts)}
   {:pretty true}))

(defn ansible-data
  "Template values for the Ansible stage.

  Deliberately carries no operator secret. The Anthropic key reaches the host
  as an Ansible `lookup('env', ...)` expression written literally into
  main.yml, where `preserve-jinja-delimiters` passes it through untouched —
  routing it through this map instead would let Selmer HTML-escape the quotes
  and hand Ansible `&#39;`. The secret therefore exists only in the process
  that needs it: not in `.colors/`, not in a golden, not in this map."
  [opts]
  (assoc opts
         :ip (or (:ip opts) "192.0.2.10")
         :traefik-ip (validate/traefik-ip opts)
         :traefik-agent-ip (validate/traefik-agent-ip opts)
         :proxy-agent-ip (validate/proxy-agent-ip opts)
         :agent-ip (validate/agent-ip opts)
         :allowed-model (validate/allowed-model opts)
         :denied-claimed-model (validate/denied-claimed-model opts)
         :ssh-keygen (validate/keygen? opts)))

(defn ansible-specs [opts]
  (let [dir (tool-dir opts ansible-tool) data (ansible-data opts)]
    [(spec (template "ansible" "ansible.cfg") (str dir "/ansible.cfg") data)
     (spec (template "ansible" "main.yml") (str dir "/main.yml") data)
     (spec (template "ansible" "cleanup.yml") (str dir "/cleanup.yml") data)
     (spec (template "ansible" "compose.yml") (str dir "/compose.yml") data)
     (spec (template "ansible" "config.yaml") (str dir "/config.yaml") data)
     (spec (template "ansible" "dashboard.env") (str dir "/dashboard.env") data)
     (spec (template "ansible" "proxy.env") (str dir "/proxy.env") data)
     (spec (template "ansible" "traefik-dynamic.yaml") (str dir "/traefik-dynamic.yaml") data)
     (spec (template "ansible" "bootstrap.sh") (str dir "/bootstrap.sh") data)
     (spec (template "ansible" "agent.Dockerfile") (str dir "/agent.Dockerfile") data)
     (spec (template "ansible" "agent-entry.sh") (str dir "/agent-entry.sh") data)
     (spec (template "ansible" "smoke.sh") (str dir "/smoke.sh") data)
     (spec (template "ansible" "status.sh") (str dir "/status.sh") data)
     (spec (template "ansible" "firewall.sh") (str dir "/firewall.sh") data)
     (spec (template "ansible" "firewall.service") (str dir "/firewall.service") data)
     (raw-spec (str dir "/desired.json") (desired-json data))
     (raw-spec (str dir "/inventory.json") (inventory data))]))

(defn ansible-step [opts]
  (let [dir (tool-dir opts ansible-tool)]
    (if (and (= :delete (:green/event opts)) (not (:ip opts)))
      ;; No compute in state: there is no host to stop, and the cleanup play
      ;; would only fail against the placeholder address.
      (assoc opts :green/exit 0)
      (ansible/ansible-with-spec opts
        {:dir dir :inventory "inventory.json"
         :playbooks {:create "main.yml" :delete "cleanup.yml"}
         :host-key-checking false}
        (ansible-specs opts)))))

;; ------------------------------------------------------------- acceptance

(defn wait-for
  "True once `args` exits zero, retrying every five seconds."
  [args attempts]
  (loop [n attempts]
    (let [r (process/run-with-timeout args {} 20000)]
      (cond (zero? (:exit r)) true
            (pos? n) (do (Thread/sleep 5000) (recur (dec n)))
            :else false))))

(defn run [args] (process/run-with-timeout args {} 20000))
(defn out [args] (str/trim (str (:out (run args)))))

(defn cert-error
  "Why the certificate for `host` is not acceptable, or nil when it is.

  Traefik answers 443 with a self-signed default certificate when ACME has
  failed, so a reachable HTTPS endpoint proves nothing on its own. Three
  separate facts are checked: the chain validates against the system trust
  store (`curl` without `-k` fails otherwise), the certificate names this host,
  and it is not about to expire."
  [host]
  (let [s-client (str "echo | openssl s_client -servername " host
                      " -connect " host ":443 2>/dev/null")]
    (cond
      (not (zero? (:exit (run ["curl" "-fsS" "-o" "/dev/null" (str "https://" host "/")]))))
      (str "the certificate for " host " is not trusted by the system store; Traefik is "
           "probably serving its self-signed default because ACME failed")

      (not (str/includes?
            (out ["sh" "-c" (str s-client " | openssl x509 -noout -ext subjectAltName")])
            host))
      (str "the certificate served for " host " does not name it")

      (not (zero? (:exit (run ["sh" "-c" (str s-client
                                              " | openssl x509 -noout -checkend 604800")]))))
      (str "the certificate for " host " expires within seven days and has not renewed")

      :else nil)))

(defn closed?
  "Whether a TCP port refuses a connection from out here. `bind to loopback`
  regresses silently while every positive check still passes, so absence is
  asserted rather than assumed."
  [host port]
  (not (zero? (:exit (run ["sh" "-c" (str "timeout 5 bash -c '</dev/tcp/" host "/" port "' 2>/dev/null")])))))

(defn ssh-out
  "One command on the deployment host, over the machine key."
  [opts command]
  (let [args (concat ["ssh" "-o" "BatchMode=yes" "-o" "StrictHostKeyChecking=accept-new"]
                     (ssh/identity-args opts)
                     [(str "root@" (:ip opts)) command])]
    (out (vec args))))

(defn local-overlay-error
  "The external negative probe must run from outside the overlay, and this
  workstation is where it runs. A NetBird interface here would mean the probe
  can silently succeed through the tunnel and prove nothing, so its absence is
  checked rather than assumed."
  []
  (let [links (out ["sh" "-c" "ip -o link show 2>/dev/null | awk -F': ' '{print $2}'"])]
    (when (re-find #"(?m)^(wt|netbird)" links)
      (str "this workstation carries a NetBird/WireGuard interface ("
           (first (re-seq #"(?m)^(?:wt|netbird)[^\s@]*" links))
           "); the external tunnel-only probe would not be external. "
           "Disconnect the local NetBird client and re-run create."))))

(defn acceptance-step
  "Public health checks after a real create.

  What runs here is what the internet can see: the dashboard, its certificate,
  its substituted configuration, the ports that must refuse, and — the claim
  this demo exists to make — that the generated agent-network endpoint denies
  a caller who is not on the overlay. The in-tunnel proofs (isolation, the
  keyless call, the denial reasons, attribution) run inside the playbook as
  `agent-network-smoke`, where the automation credential lives."
  [opts]
  (if (not= :create (:green/event opts))
    (assoc opts :green/exit 0)
    (let [host (:agent-network-host opts)
          ip (:ip opts)]
      (cond
        (not (wait-for ["curl" "-fsS" "-o" "/dev/null" (str "https://" host "/")] 60))
        (assoc opts :green/exit 1
               :green/err "the dashboard did not become reachable over HTTPS")

        :else
        (let [cert-errs (keep cert-error [host])
              ;; The dashboard substitutes its configuration into the built
              ;; assets at container start, and the script that does it exits
              ;; non-zero on a missing variable while supervisord carries on.
              ;; nginx then serves the placeholders verbatim and every request
              ;; for `/` still returns 200 — so the page has to be read, not
              ;; merely fetched. This shipped once in the sibling package.
              page (out ["curl" "-fsS" (str "https://" host "/")])
              chunks (->> (re-seq #"/_next/static/chunks/[A-Za-z0-9_.\-]+\.js" page)
                          distinct (take 6))
              unsubstituted (some (fn [u]
                                    (when (str/includes?
                                           (out ["curl" "-fsS" (str "https://" host u)])
                                           "$NETBIRD_")
                                      u))
                                  chunks)
              ;; Ports that must not answer from outside: the server's metrics
              ;; and healthcheck listeners, the proxy's direct 8443, and the
              ;; proxy's WireGuard 51820 (the only peer is on the internal
              ;; Docker network; nothing external enrolls).
              open (remove #(closed? ip %) [9090 9000 8443])
              overlay-err (local-overlay-error)
              endpoint (ssh-out opts "cat /etc/agent-network/state/endpoint 2>/dev/null")]
          (cond
            (seq cert-errs)
            (assoc opts :green/exit 1 :green/err (str/join "; " cert-errs))

            unsubstituted
            (assoc opts :green/exit 1
                   :green/err (str "the dashboard is serving unsubstituted configuration in "
                                   unsubstituted "; init_react_envs failed at container start "
                                   "(a missing variable makes it exit 1 while nginx keeps serving)"))

            (seq open)
            (assoc opts :green/exit 1
                   :green/err (str "ports that must not be public answered: "
                                   (str/join ", " open)))

            (some? overlay-err)
            (assoc opts :green/exit 1 :green/err overlay-err)

            (str/blank? endpoint)
            (assoc opts :green/exit 1
                   :green/err "the host records no agent-network endpoint; bootstrap did not complete")

            ;; The tunnel-only claim, tested rather than asserted: the same
            ;; request the agent makes must fail from here, where there is no
            ;; tunnel. -k because the endpoint's certificate may legitimately
            ;; still be issuing; what is under test is the deny, not the chain
            ;; (gate 3 inside the tunnel already proved the chain).
            ;; Served means bypassed: a 200 completion OR the relayed
            ;; upstream 401 both prove the caller's request reached Anthropic
            ;; through server-side key injection without a tunnel identity.
            ;; The correct outcome is the proxy's own pre-identity denial
            ;; (observed: a bare 403), which reaches no upstream and writes no
            ;; access-log entry.
            (let [probe (out ["sh" "-c"
                              (str "curl -sk --max-time 20 -w '\\nHTTPCODE:%{http_code}' "
                                   "-X POST https://" endpoint "/v1/messages "
                                   "-H 'content-type: application/json' "
                                   "--data '{\"model\":\"" (validate/allowed-model opts)
                                   "\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}'")])
                  code (or (second (re-find #"HTTPCODE:(\d+)" probe)) "000")]
              (or (= code "200")
                  (and (= code "401") (str/includes? probe "authentication_error"))))
            (assoc opts :green/exit 1
                   :green/err (str "the agent-network endpoint " endpoint
                                   " served a caller outside the overlay (the request "
                                   "reached the upstream); it must be tunnel-only"))

            ;; And the probe must have left no unattributed access-log entry:
            ;; pre-identity denials are dropped before logging, so any entry
            ;; without a caller identity means something external was served.
            (let [unattributed
                  (ssh-out opts
                           (str "curl -fsS -H \"Authorization: Token $(cat /etc/agent-network/secrets/pat)\" "
                                "'https://" host "/api/agent-network/access-logs?page=1&page_size=100' "
                                "| jq -r '[.data[] | select((.user_id // \"\") == \"\")] | length'"))]
              (not= "0" (str/trim (str unattributed))))
            (assoc opts :green/exit 1
                   :green/err "the access log holds entries with no caller identity; an external request was served")

            :else
            (assoc opts :green/exit 0
                   :agent-network/acceptance {:dashboard "configured"
                                              :certificate "trusted"
                                              :closed-ports "confirmed"
                                              :endpoint endpoint
                                              :tunnel-only "confirmed"})))))))
